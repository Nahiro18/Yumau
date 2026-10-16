package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

object DesktopStreamResolver {
    private val defaultClientOrder = listOf(
        YouTubeClient.ANDROID_VR_NO_AUTH,
        YouTubeClient.IOS,
        YouTubeClient.ANDROID_MUSIC,
        YouTubeClient.VISIONOS,
        YouTubeClient.ANDROID_VR_1_61_48,
        YouTubeClient.WEB_REMIX,
    )

    private val streamCache = ConcurrentHashMap<String, Pair<ResolvedAudioStream, Long>>()
    private const val CACHE_TTL_MS = 5 * 60 * 60 * 1000L // 5h: las URLs de Google suelen vivir ~6h

    // Negative cache para FLAC: evita pagar 2s en cada click cuando Qobuz no tiene el tema
    private val flacMissCache = ConcurrentHashMap<String, Long>()
    private const val FLAC_MISS_TTL_MS = 30 * 60 * 1000L

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
                        if (now - v.timestamp < CACHE_TTL_MS && v.url.isNotBlank()) {
                            diskCache[k] = v
                            // Hidratamos tambien la cache en memoria para hits de 0ms
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
        // Escritura en background para no bloquear el hilo de reproduccion
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                // Limitamos a 400 entradas mas recientes
                val snapshot = diskCache.entries.sortedByDescending { it.value.timestamp }.take(400)
                    .associate { it.key to it.value }
                diskCacheFile.writeText(jsonLenient.encodeToString(snapshot))
            } catch (_: Exception) {}
        }
    }

    private fun putCache(key: String, stream: ResolvedAudioStream) {
        val now = System.currentTimeMillis()
        streamCache[key] = stream to now
        if (stream.url.isNotBlank() && !stream.url.contains("youtube.com/watch")) {
            diskCache[key] = DiskStreamEntry(
                url = stream.url, itag = stream.itag, mimeType = stream.mimeType,
                bitrate = stream.bitrate, isOpus = stream.isOpus, durationMs = stream.durationMs,
                clientName = stream.clientName, formatLabel = stream.formatLabel, timestamp = now
            )
            saveDiskCacheThrottled()
        }
    }

    fun prefetch(
        videoId: String,
        title: String = "",
        artist: String = "",
        durationMs: Long = 0L,
        playbackSource: String = "YT_MUSIC",
        flacQuality: String = "HI_RES",
        preferredQuality: String = "HIGH",
        preferredClientName: String = "ANDROID_VR"
    ) {
        if (videoId.isBlank()) return
        loadDiskCacheIfNeeded()
        val cacheKey = cacheKey(videoId, playbackSource, flacQuality, preferredQuality)
        streamCache[cacheKey]?.let { (_, ts) ->
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return
        }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                resolveStreamUrl(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    playbackSource = playbackSource,
                    flacQuality = flacQuality,
                    preferredQuality = preferredQuality,
                    preferredClientName = preferredClientName
                )
            }
        }
    }

    /** Prefetch de los siguientes N temas de la cola (llamar tras cada play). */
    fun prefetchQueue(
        items: List<Pair<String, Triple<String, String, Long>>>,
        playbackSource: String,
        flacQuality: String,
        preferredQuality: String,
        preferredClientName: String,
    ) {
        if (items.isEmpty()) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            // Solo los 3 siguientes, en paralelo pero con limite natural
            items.take(3).forEach { (videoId, meta) ->
                launch {
                    runCatching {
                        prefetch(
                            videoId = videoId, title = meta.first, artist = meta.second,
                            durationMs = meta.third, playbackSource = playbackSource,
                            flacQuality = flacQuality, preferredQuality = preferredQuality,
                            preferredClientName = preferredClientName
                        )
                    }
                }
            }
        }
    }

    suspend fun resolveStreamUrl(
        videoId: String,
        title: String = "",
        artist: String = "",
        durationMs: Long = 0L,
        playbackSource: String = "YT_MUSIC",
        flacQuality: String = "HI_RES",
        preferredQuality: String = "HIGH",
        preferredClientName: String = "ANDROID_VR"
    ): ResolvedAudioStream = withContext(Dispatchers.IO) {
        loadDiskCacheIfNeeded()
        val cacheKey = cacheKey(videoId, playbackSource, flacQuality, preferredQuality)
        streamCache[cacheKey]?.let { (cachedStream, timestamp) ->
            if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS && cachedStream.url.isNotBlank()) {
                return@withContext cachedStream
            } else {
                streamCache.remove(cacheKey)
            }
        }
        diskCache[cacheKey]?.let { disk ->
            if (System.currentTimeMillis() - disk.timestamp < CACHE_TTL_MS && disk.url.isNotBlank()) {
                val mem = ResolvedAudioStream(
                    url = disk.url, itag = disk.itag, mimeType = disk.mimeType,
                    bitrate = disk.bitrate, isOpus = disk.isOpus, durationMs = disk.durationMs,
                    clientName = disk.clientName, formatLabel = disk.formatLabel
                )
                streamCache[cacheKey] = mem to disk.timestamp
                return@withContext mem
            } else {
                diskCache.remove(cacheKey)
            }
        }

        // MODO YT PURO: via rapida sin tocar FLAC (0 overhead)
        if (playbackSource != "FLAC") {
            val yt = resolveYouTubeFast(videoId, preferredQuality, preferredClientName, durationMs)
            if (yt != null) {
                putCache(cacheKey, yt)
                return@withContext yt
            }
            return@withContext ResolvedAudioStream(
                url = "https://www.youtube.com/watch?v=$videoId",
                durationMs = durationMs,
                formatLabel = "WEB • OPUS"
            )
        }

        // MODO FLAC: carrera en paralelo FLAC vs YT.
        // Si FLAC responde rapido (<1.2s) gana (lossless). Si no, YT responde primero
        // y no bloqueamos la reproduccion esperando a Qobuz.
        if (title.isBlank()) {
            val yt = resolveYouTubeFast(videoId, preferredQuality, preferredClientName, durationMs)
            if (yt != null) {
                putCache(cacheKey, yt)
                return@withContext yt
            }
            return@withContext ResolvedAudioStream(
                url = "https://www.youtube.com/watch?v=$videoId",
                durationMs = durationMs,
                formatLabel = "WEB • OPUS"
            )
        }

        val flacKey = "$title|$artist|$flacQuality"
        val flacMissTs = flacMissCache[flacKey]
        val skipFlac = flacMissTs != null && System.currentTimeMillis() - flacMissTs < FLAC_MISS_TTL_MS

        val result: ResolvedAudioStream = coroutineScope {
            val flacDeferred = if (skipFlac) null else async {
                withTimeoutOrNull(2000L) {
                    DesktopFlacResolver.resolve(
                        title = title,
                        artist = artist,
                        durationMs = durationMs,
                        quality = flacQuality
                    )
                }
            }
            val ytDeferred = async {
                resolveYouTubeFast(videoId, preferredQuality, preferredClientName, durationMs)
            }

            // Esperamos FLAC solo hasta 1200ms: si llega, es lossless inmediato.
            val fastFlac = if (flacDeferred != null) {
                withTimeoutOrNull(1200L) { flacDeferred.await() }
            } else null

            if (fastFlac != null) {
                ytDeferred.cancel()
                // Cancelamos el deferred FLAC restante si ya obtuvimos resultado (ya completo)
                fastFlac
            } else {
                // YT manda: respuesta en ~300-900ms tipico. FLAC sigue en background
                // por si llega antes que YT falle.
                val yt = try { ytDeferred.await() } catch (_: Exception) { null }
                if (yt != null) {
                    // No cancelamos flacDeferred bruscamente: si FLAC llega tarde lo ignoramos,
                    // pero marcamos miss solo cuando FLAC confirma fallo.
                    if (flacDeferred != null) {
                        launch {
                            val lateFlac = runCatching { flacDeferred.await() }.getOrNull()
                            if (lateFlac == null) {
                                flacMissCache[flacKey] = System.currentTimeMillis()
                            }
                        }
                    }
                    yt
                } else {
                    // YT fallo: esperamos FLAC como ultimo recurso (hasta su timeout)
                    val flacWait = try { flacDeferred?.await() } catch (_: Exception) { null }
                    if (flacWait != null) {
                        flacWait
                    } else {
                        if (flacDeferred != null) flacMissCache[flacKey] = System.currentTimeMillis()
                        ResolvedAudioStream(
                            url = "https://www.youtube.com/watch?v=$videoId",
                            durationMs = durationMs,
                            formatLabel = "WEB • OPUS"
                        )
                    }
                }
            }
        }

        // No cacheamos el fallback watch-url en disco (mpv lo resuelve con yt-dlp, lento).
        if (!result.url.contains("youtube.com/watch")) {
            putCache(cacheKey, result)
        } else {
            streamCache[cacheKey] = result to System.currentTimeMillis()
        }
        result
    }

    /**
     * Via rapida de YouTube: carrera paralela de los 2 mejores clientes.
     * El primero que devuelva URL directa gana (~300-900ms tipico).
     */
    private suspend fun resolveYouTubeFast(
        videoId: String,
        preferredQuality: String,
        preferredClientName: String,
        durationMs: Long,
    ): ResolvedAudioStream? = withContext(Dispatchers.IO) {
        val clients = getClientCandidates(preferredClientName)

        // Solo los 2 primeros en paralelo: menos carga, misma velocidad.
        // El 3er cliente queda como fallback secuencial.
        val topClients = clients.take(2)
        val candidateChannel = kotlinx.coroutines.channels.Channel<ResolvedAudioStream>(capacity = topClients.size)
        val probeJobs = topClients.map { client ->
            launch {
                val stream = tryResolveClient(videoId, client, preferredQuality, durationMs)
                if (stream != null) {
                    candidateChannel.trySend(stream)
                }
            }
        }

        val fastResult = withTimeoutOrNull(2500L) {
            candidateChannel.receiveCatching().getOrNull()
        }

        if (fastResult != null) {
            probeJobs.forEach { it.cancel() }
            return@withContext fastResult
        }

        // Fallback secuencial para el resto (incluye el 3er cliente rapido)
        for (client in clients.drop(2)) {
            val stream = tryResolveClient(videoId, client, preferredQuality, durationMs)
            if (stream != null) {
                probeJobs.forEach { runCatching { it.cancel() } }
                return@withContext stream
            }
        }
        null
    }

    private suspend fun tryResolveClient(
        videoId: String,
        client: YouTubeClient,
        preferredQuality: String,
        durationMs: Long
    ): ResolvedAudioStream? {
        return try {
            val response = withTimeoutOrNull(2500L) {
                YouTube.player(videoId = videoId, client = client).getOrNull()
            } ?: return null

            if (response.playabilityStatus.status != "OK") return null

            val formats = response.streamingData?.adaptiveFormats
                ?.filter { it.isAudio && it.bitrate > 0 }
                .orEmpty()

            if (formats.isEmpty()) return null

            val songDurationMs = response.videoDetails
                ?.lengthSeconds
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.times(1000L) ?: durationMs

            val candidate = selectFormat(formats, preferredQuality) ?: formats.firstOrNull() ?: return null

            // FAST PATH: URL directa sin deofuscacion JS pesada
            val streamUrl = if (!candidate.url.isNullOrBlank()) {
                candidate.url
            } else {
                // CIPHER PATH: solo si el formato exige descifrado de firma
                withTimeoutOrNull(1800L) {
                    NewPipeUtils.getStreamUrl(candidate, videoId, client).getOrNull()
                } ?: candidate.url
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
        } catch (_: Exception) {
            null
        }
    }

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
