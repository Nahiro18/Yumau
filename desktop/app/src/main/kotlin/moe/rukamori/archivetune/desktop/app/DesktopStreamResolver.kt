package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.innertube.pages.NewPipeUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class ResolvedAudioStream(
    val url: String,
    val itag: Int? = null,
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val isOpus: Boolean = false,
    val durationMs: Long = 0L,
    val clientName: String? = null,
    val formatLabel: String? = null,
)

@Serializable
private data class DiskStreamEntry(
    val url: String,
    val itag: Int? = null,
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val isOpus: Boolean = false,
    val durationMs: Long = 0L,
    val clientName: String? = null,
    val formatLabel: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Resolutor de streams SIN carrera. Usa exclusivamente la fuente elegida en Ajustes:
 *  - "YT_MUSIC": solo clientes de YouTube Music.
 *  - "FLAC": solo Qobuz/FLAC (con fallback opcional a YouTube si el ajuste lo permite).
 *
 * Nunca devuelve una URL "https://youtube.com/watch?v=..." porque mpv invocaria
 * yt-dlp y eso puede tardar MINUTOS. Si no hay URL directa, devuelve null y la UI
 * muestra un error claro de inmediato.
 */
object DesktopStreamResolver {
    private val defaultClientOrder = listOf(
        YouTubeClient.ANDROID_VR_NO_AUTH,
        YouTubeClient.IOS,
        YouTubeClient.ANDROID_MUSIC,
        YouTubeClient.VISIONOS,
        YouTubeClient.WEB_REMIX,
    )

    private val streamCache = ConcurrentHashMap<String, Pair<ResolvedAudioStream, Long>>()
    // 2h: las URLs de googlevideo caducan y reusar una expirada provoca 403 y esperas largas
    private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L

    // --- Motor de descifrado (parametro "n" de YouTube) ---
    // Todas las URLs de audio hoy traen "n", asi que sin desofuscar el audio puede
    // llegar throttled. Pero desofuscar con el motor FRIO cuesta segundos (descarga
    // del player + compilacion) y, como lo cancelabamos, nunca se guardaba el plan:
    // se repetia en cada tema. Ahora el motor se calienta UNA vez al arrancar y
    // solo se usa cuando ya esta listo (transformacional en milisegundos).
    private val cipherWarmStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val cipherFastReady = java.util.concurrent.atomic.AtomicBoolean(false)
    // Si desofuscar tarda demasiado (Rhino lento), dejamos de pagar ese coste en
    // cada tema durante 10 min y usamos la URL directa (que va sobrada de velocidad).
    private val cipherDisabledUntilMs = java.util.concurrent.atomic.AtomicLong(0L)

    // La PRIMERA peticion de red de la sesion paga DNS + TLS de music.youtube.com
    // (~1.8-2.0 s medidos). Antes la ventana corta tiraba ese primer resultado (llegaba
    // a los 2.05 s) y la cancion no sonaba nunca. Tras la primera respuesta todo baja
    // a ~100-300 ms, asi que ampliamos presupuesto solo mientras este frio.
    private val playerNetworkWarm = java.util.concurrent.atomic.AtomicBoolean(false)

    // Motivo del ultimo fallo de YouTube, para que la UI no diga "revisa la conexion"
    // cuando en realidad YouTube pide iniciar sesion (bloqueo antibot).
    const val HINT_LOGIN = "login"
    const val HINT_UNAVAILABLE = "unavailable"
    const val HINT_NETWORK = "network"
    @Volatile
    var lastFailureHint: String? = null
        private set

    /**
     * Descarga y compila el plan del descifrador en segundo plano. Idempotente y
     * sin coste para el usuario: se llama al arrancar la app y de forma defensiva
     * la primera vez que hace falta.
     */
    fun warmUpCipher() {
        if (!cipherWarmStarted.compareAndSet(false, true)) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val start = System.currentTimeMillis()
            runCatching {
                withTimeoutOrNull(60_000L) {
                    moe.rukamori.archivetune.obfuscator.MoriCipherRuntime.refresh(force = false)
                }
            }
            // Tras el warmup el plan queda en memoria (y en disco): los transforms
            // posteriores son milisegundos.
            cipherFastReady.set(true)
            DesktopDiag.log("cipher-warmup", System.currentTimeMillis() - start)
        }
    }

    private val jsonLenient = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val diskCacheFile: File by lazy {
        File(System.getProperty("user.home"), ".config/YumaPlayer/stream_cache.json").apply {
            parentFile?.mkdirs()
        }
    }
    private val diskCache = ConcurrentHashMap<String, DiskStreamEntry>()
    @Volatile private var diskLoaded = false
    @Volatile private var lastDiskSave = 0L

    fun getClientCandidates(preferredClientName: String?): List<YouTubeClient> {
        val preferred = when (preferredClientName) {
            "ANDROID_VR" -> YouTubeClient.ANDROID_VR_NO_AUTH
            "IOS" -> YouTubeClient.IOS
            "ANDROID_MUSIC" -> YouTubeClient.ANDROID_MUSIC
            "VISIONOS" -> YouTubeClient.VISIONOS
            "WEB_REMIX" -> YouTubeClient.WEB_REMIX
            else -> YouTubeClient.ANDROID_VR_NO_AUTH
        }
        return listOf(preferred) + defaultClientOrder.filter { it != preferred }
    }

    private fun cacheKey(videoId: String, playbackSource: String, flacQuality: String, preferredQuality: String): String {
        return if (playbackSource == "FLAC") "${videoId}_FLAC_${flacQuality}" else "${videoId}_$preferredQuality"
    }

    /** Detecta si una URL de streaming ya expiro (las de googlevideo traen ?expire=<unix>). */
    private fun isUrlExpired(url: String): Boolean {
        return runCatching {
            val query = url.substringAfter("?", "")
            if (query.isEmpty()) return false
            val expire = query.split("&")
                .firstOrNull { it.startsWith("expire=") }
                ?.substringAfter("=")
                ?.toLongOrNull() ?: return false
            expire < (System.currentTimeMillis() / 1000L) - 60L
        }.getOrDefault(false)
    }

    /**
     * Solo URLs de streaming DIRECTAS sirven. Versiones anteriores de la app guardaban
     * "youtube.com/watch?v=..." como fallback en la cache; mpv entonces invocaba yt-dlp
     * y tardaba MINUTOS. Se descartan aqui (y de la cache en disco).
     */
    private fun isUsableStreamUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (lower.contains("youtube.com/watch") || lower.contains("youtu.be/") ||
            lower.contains("music.youtube.com/watch")
        ) return false
        return !isUrlExpired(url)
    }

    private fun isStale(stream: ResolvedAudioStream, timestamp: Long): Boolean {
        if (!isUsableStreamUrl(stream.url)) return true
        return System.currentTimeMillis() - timestamp >= CACHE_TTL_MS
    }

    private fun loadDiskCacheIfNeeded() {
        if (diskLoaded) return
        synchronized(this) {
            if (diskLoaded) return
            diskLoaded = true
            try {
                if (diskCacheFile.exists() && diskCacheFile.length() > 0 && diskCacheFile.length() < 4_000_000) {
                    val map = jsonLenient.decodeFromString<Map<String, DiskStreamEntry>>(diskCacheFile.readText())
                    val now = System.currentTimeMillis()
                    for ((k, v) in map) {
                        if (now - v.timestamp < CACHE_TTL_MS && isUsableStreamUrl(v.url)) {
                            diskCache[k] = v
                            streamCache[k] = ResolvedAudioStream(
                                url = v.url, itag = v.itag, mimeType = v.mimeType,
                                bitrate = v.bitrate, isOpus = v.isOpus, durationMs = v.durationMs,
                                clientName = v.clientName, formatLabel = v.formatLabel
                            ) to v.timestamp
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun saveDiskCacheThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastDiskSave < 10_000L) return
        lastDiskSave = now
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val snapshot = diskCache.entries.sortedByDescending { it.value.timestamp }.take(400)
                    .associate { it.key to it.value }
                diskCacheFile.writeText(jsonLenient.encodeToString(snapshot))
            } catch (_: Exception) {}
        }
    }

    private fun putCache(key: String, stream: ResolvedAudioStream) {
        val now = System.currentTimeMillis()
        streamCache[key] = stream to now
        if (stream.url.isNotBlank()) {
            diskCache[key] = DiskStreamEntry(
                url = stream.url, itag = stream.itag, mimeType = stream.mimeType,
                bitrate = stream.bitrate, isOpus = stream.isOpus, durationMs = stream.durationMs,
                clientName = stream.clientName, formatLabel = stream.formatLabel, timestamp = now
            )
            saveDiskCacheThrottled()
        }
    }

    /** Invalida las entradas de un video (p.ej. tras un 403 de una URL expirada). */
    fun invalidate(videoId: String) {
        if (videoId.isBlank()) return
        streamCache.keys.removeIf { it.startsWith("$videoId") }
        diskCache.keys.removeIf { it.startsWith("$videoId") }
    }

    fun prefetch(
        videoId: String,
        title: String = "",
        artist: String = "",
        durationMs: Long = 0L,
        playbackSource: String = "YT_MUSIC",
        flacQuality: String = "HI_RES",
        preferredQuality: String = "HIGH",
        preferredClientName: String = "ANDROID_VR",
        flacFallbackToYoutube: Boolean = true,
    ) {
        if (videoId.isBlank()) return
        loadDiskCacheIfNeeded()
        val key = cacheKey(videoId, playbackSource, flacQuality, preferredQuality)
        streamCache[key]?.let { (s, ts) -> if (!isStale(s, ts)) return }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                resolveStreamUrl(
                    videoId = videoId, title = title, artist = artist, durationMs = durationMs,
                    playbackSource = playbackSource, flacQuality = flacQuality,
                    preferredQuality = preferredQuality, preferredClientName = preferredClientName,
                    flacFallbackToYoutube = flacFallbackToYoutube
                )
            }
        }
    }

    /**
     * Resuelve el stream de audio usando SOLO la fuente configurada (sin carrera).
     * Devuelve null si la fuente no pudo resolver (la UI muestra el error al instante).
     */
    suspend fun resolveStreamUrl(
        videoId: String,
        title: String = "",
        artist: String = "",
        durationMs: Long = 0L,
        playbackSource: String = "YT_MUSIC",
        flacQuality: String = "HI_RES",
        preferredQuality: String = "HIGH",
        preferredClientName: String = "ANDROID_VR",
        flacFallbackToYoutube: Boolean = true,
    ): ResolvedAudioStream? = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        loadDiskCacheIfNeeded()
        val key = cacheKey(videoId, playbackSource, flacQuality, preferredQuality)

        streamCache[key]?.let { (cached, ts) ->
            if (!isStale(cached, ts)) {
                DesktopDiag.log("resolve", System.currentTimeMillis() - startedAt, "cache=mem src=$playbackSource")
                return@withContext cached
            }
            streamCache.remove(key)
        }
        diskCache[key]?.let { disk ->
            if (System.currentTimeMillis() - disk.timestamp < CACHE_TTL_MS && isUsableStreamUrl(disk.url)) {
                val mem = ResolvedAudioStream(
                    url = disk.url, itag = disk.itag, mimeType = disk.mimeType,
                    bitrate = disk.bitrate, isOpus = disk.isOpus, durationMs = disk.durationMs,
                    clientName = disk.clientName, formatLabel = disk.formatLabel
                )
                streamCache[key] = mem to disk.timestamp
                DesktopDiag.log("resolve", System.currentTimeMillis() - startedAt, "cache=disk src=$playbackSource")
                return@withContext mem
            } else {
                diskCache.remove(key)
            }
        }

        val resolved = when (playbackSource) {
            "FLAC" -> resolveViaFlac(videoId, title, artist, durationMs, flacQuality, flacFallbackToYoutube, preferredQuality, preferredClientName)
            else -> resolveViaYouTube(videoId, preferredQuality, preferredClientName, durationMs)
        }

        if (resolved != null && resolved.url.isNotBlank()) {
            putCache(key, resolved)
            DesktopDiag.log(
                "resolve", System.currentTimeMillis() - startedAt,
                "network src=$playbackSource client=${resolved.clientName} fmt=${resolved.formatLabel}"
            )
            resolved
        } else {
            DesktopDiag.log("resolve", System.currentTimeMillis() - startedAt, "FAILED src=$playbackSource")
            null
        }
    }

    /** Solo FLAC. Si falla y el ajuste lo permite, cae a YouTube (secuencial, sin carrera). */
    private suspend fun resolveViaFlac(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        flacQuality: String,
        fallbackToYoutube: Boolean,
        preferredQuality: String,
        preferredClientName: String,
    ): ResolvedAudioStream? {
        if (title.isNotBlank()) {
            val flacStartedAt = System.currentTimeMillis()
            val flac = DesktopFlacResolver.resolve(
                title = title, artist = artist, durationMs = durationMs, quality = flacQuality
            )
            DesktopDiag.log(
                "flac", System.currentTimeMillis() - flacStartedAt,
                if (flac != null) "hit ${flac.formatLabel}" else "miss (fallback=$fallbackToYoutube)"
            )
            if (flac != null) return flac
        }
        if (!fallbackToYoutube) return null
        return resolveViaYouTube(videoId, preferredQuality, preferredClientName, durationMs)
    }

    /**
     * Solo YouTube Music: TODOS los clientes se prueban a la vez y gana el primero que
     * devuelva una URL directa. Antes se probaban 3 y, si tardaban >2 s, sus resultados
     * se tiraban a la basura y solo se esperaba a otros 2: con la conexion en frio (la
     * primera peticion tarda ~2 s de TLS) la cancion no sonaba nunca.
     * Sin URLs de watch (evita yt-dlp). Devuelve null si ningun cliente dio URL directa.
     */
    private suspend fun resolveViaYouTube(
        videoId: String,
        preferredQuality: String,
        preferredClientName: String,
        durationMs: Long,
    ): ResolvedAudioStream? = withContext(Dispatchers.IO) {
        val clients = getClientCandidates(preferredClientName)
        val warm = playerNetworkWarm.get()
        // En frio hay que pagar DNS+TLS una vez; despues 2.8 s sobran de sobra.
        val budgetMs = if (warm) 2_800L else 8_000L
        val startedAt = System.currentTimeMillis()
        val failures = java.util.concurrent.ConcurrentHashMap<String, String>()

        val candidateChannel = kotlinx.coroutines.channels.Channel<ResolvedAudioStream>(capacity = clients.size)
        val probeJobs = clients.map { client ->
            launch {
                val stream = tryResolveClient(videoId, client, preferredQuality, durationMs, budgetMs, failures)
                if (stream != null) candidateChannel.trySend(stream)
            }
        }
        // Si TODOS los clientes fallan rapido (video bloqueado, 400...), cerramos el
        // canal para no esperar el presupuesto entero antes de enseñar el error.
        val closerJob = launch {
            probeJobs.forEach { runCatching { it.join() } }
            candidateChannel.close()
        }

        val result = withTimeoutOrNull(budgetMs) {
            candidateChannel.receiveCatching().getOrNull()
        }
        probeJobs.forEach { runCatching { it.cancel() } }
        closerJob.cancel()

        if (result == null) {
            // Distinguir "YouTube pide cuenta" de "no hay red" evita mensajes que
            // mandan al usuario a revisar la conexion cuando el problema es otro.
            val details = failures.values.joinToString(" ").lowercase()
            lastFailureHint = when {
                details.contains("login_required") || details.contains("bot") ||
                    details.contains("cuenta") -> HINT_LOGIN
                details.contains("unplayable") || details.contains("no disponible") -> HINT_UNAVAILABLE
                else -> HINT_NETWORK
            }
        }

        DesktopDiag.log(
            "yt", System.currentTimeMillis() - startedAt,
            if (result != null) "ok client=${result.clientName} warm=$warm"
            else "sin candidatos warm=$warm hint=$lastFailureHint"
        )
        result
    }

    private suspend fun tryResolveClient(
        videoId: String,
        client: YouTubeClient,
        preferredQuality: String,
        durationMs: Long,
        timeoutMs: Long,
        failures: MutableMap<String, String>,
    ): ResolvedAudioStream? {
        val clientStartedAt = System.currentTimeMillis()
        fun logClient(detail: String) {
            failures[client.clientName] = detail
            DesktopDiag.log("yt-client", System.currentTimeMillis() - clientStartedAt, "${client.clientName}: $detail")
        }
        return try {
            val playerResult = withTimeoutOrNull(timeoutMs) {
                YouTube.player(videoId = videoId, client = client)
            }
            if (playerResult == null) {
                logClient("timeout ${timeoutMs}ms")
                return null
            }
            // En cuanto una peticion completa, la conexion queda caliente para el resto.
            playerNetworkWarm.set(true)
            val response = playerResult.getOrNull()
            if (response == null) {
                val failure = playerResult.exceptionOrNull()
                logClient(failure?.let { "${it::class.simpleName}: ${it.message?.take(90)}" } ?: "sin respuesta")
                return null
            }

            if (response.playabilityStatus.status != "OK") {
                logClient("${response.playabilityStatus.status} ${response.playabilityStatus.reason?.take(70) ?: ""}".trim())
                return null
            }

            val formats = response.streamingData?.adaptiveFormats
                ?.filter { it.isAudio && it.bitrate > 0 }
                .orEmpty()

            if (formats.isEmpty()) {
                logClient("sin formatos de audio")
                return null
            }

            val songDurationMs = response.videoDetails
                ?.lengthSeconds
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.times(1000L) ?: durationMs

            val candidate = selectFormat(formats, preferredQuality) ?: formats.firstOrNull() ?: return null

            val direct = candidate.url
            val cipherReady = cipherFastReady.get() &&
                System.currentTimeMillis() >= cipherDisabledUntilMs.get()
            val streamUrl = when {
                // URL directa sin 'n': se usa tal cual (0 esperas).
                !direct.isNullOrBlank() && !hasNParam(direct) -> direct
                // Con 'n' (TODAS las de YouTube hoy): desofuscar SOLO con el motor caliente.
                // Con el motor frio costaria segundos por tema y se cancelaria, dejando
                // el player a medio descargar cada vez.
                !direct.isNullOrBlank() && cipherReady -> {
                    val t0 = System.currentTimeMillis()
                    val transformed = withTimeoutOrNull(900L) {
                        NewPipeUtils.getStreamUrl(candidate, videoId, client).getOrNull()
                    }
                    if (transformed.isNullOrBlank()) {
                        // No bloqueamos la reproduccion: directa + pausa de 10 min.
                        cipherDisabledUntilMs.set(System.currentTimeMillis() + 10 * 60 * 1000L)
                        DesktopDiag.log("cipher-n", System.currentTimeMillis() - t0, "lento: URL directa")
                    } else {
                        DesktopDiag.log("cipher-n", System.currentTimeMillis() - t0, "ok")
                    }
                    transformed ?: direct
                }
                !direct.isNullOrBlank() -> direct
                // Sin URL directa: hay que descifrar por obligacion (con timeout).
                else -> withTimeoutOrNull(1800L) {
                    NewPipeUtils.getStreamUrl(candidate, videoId, client).getOrNull()
                }
            }

            if (!streamUrl.isNullOrBlank()) {
                val isOpus = candidate.mimeType.contains("audio/webm", ignoreCase = true) ||
                        candidate.mimeType.contains("opus", ignoreCase = true) ||
                        candidate.itag == 251 || candidate.itag == 250 || candidate.itag == 249

                val formatLabel = formatAudioQualityLabel(candidate.mimeType, candidate.itag, candidate.bitrate)

                ResolvedAudioStream(
                    url = streamUrl,
                    itag = candidate.itag,
                    mimeType = candidate.mimeType,
                    bitrate = candidate.bitrate,
                    isOpus = isOpus,
                    durationMs = songDurationMs,
                    clientName = client.clientName,
                    formatLabel = formatLabel
                )
            } else null
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // Si la carrera ya tiene ganador, cancelar este cliente NO es un fallo.
            throw cancel
        } catch (failure: Throwable) {
            logClient("${failure::class.simpleName}: ${failure.message?.take(90)}")
            null
        }
    }

    /** Detecta el parametro "n" (throttling de YouTube) en una URL de streaming. */
    private fun hasNParam(url: String): Boolean =
        url.contains("?n=") || url.contains("&n=")

    fun formatAudioQualityLabel(mimeType: String?, itag: Int?, bitrate: Int?): String {
        val m = mimeType.orEmpty().lowercase()
        val codec = when {
            m.contains("opus") || itag in listOf(249, 250, 251) -> "OPUS"
            m.contains("mp4a") || m.contains("aac") || itag == 140 -> "AAC"
            m.contains("mp3") -> "MP3"
            m.contains("flac") -> "FLAC"
            m.isNotBlank() -> m.substringBefore(";").substringAfter("/").uppercase()
            else -> "AUDIO"
        }
        val kbps = when {
            itag == 251 -> 160
            itag == 140 -> 128
            itag == 250 -> 70
            itag == 249 -> 50
            bitrate != null && bitrate > 0 -> (bitrate + 500) / 1000
            else -> 0
        }
        return if (kbps > 0) "$codec • $kbps kbps" else codec
    }

    private fun selectFormat(
        formats: List<PlayerResponse.StreamingData.Format>,
        quality: String
    ): PlayerResponse.StreamingData.Format? {
        val directFormats = formats.filter { !it.url.isNullOrBlank() }
        val pool = if (directFormats.isNotEmpty()) directFormats else formats

        val opusFormats = pool.filter {
            it.mimeType.contains("audio/webm", ignoreCase = true) ||
            it.mimeType.contains("opus", ignoreCase = true) ||
            it.itag == 251 || it.itag == 250 || it.itag == 249
        }

        return when (quality) {
            "HIGH" -> {
                opusFormats.find { it.itag == 251 }
                    ?: opusFormats.maxByOrNull { it.bitrate }
                    ?: pool.maxByOrNull { it.bitrate }
            }
            "MEDIUM" -> {
                pool.find { it.itag == 140 }
                    ?: opusFormats.find { it.itag == 250 }
                    ?: pool.minByOrNull { kotlin.math.abs(it.bitrate - 128_000) }
            }
            "LOW" -> {
                opusFormats.find { it.itag == 249 }
                    ?: pool.minByOrNull { it.bitrate }
            }
            else -> opusFormats.find { it.itag == 251 } ?: pool.maxByOrNull { it.bitrate }
        }
    }
}
