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
        loadDiskCacheIfNeeded()
        val key = cacheKey(videoId, playbackSource, flacQuality, preferredQuality)

        streamCache[key]?.let { (cached, ts) ->
            if (!isStale(cached, ts)) return@withContext cached
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
            resolved
        } else {
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
            val flac = DesktopFlacResolver.resolve(
                title = title, artist = artist, durationMs = durationMs, quality = flacQuality
            )
            if (flac != null) return flac
        }
        if (!fallbackToYoutube) return null
        return resolveViaYouTube(videoId, preferredQuality, preferredClientName, durationMs)
    }

    /**
     * Solo YouTube Music: carrera corta de 3 clientes en paralelo, luego 2 de refuerzo.
     * Sin URLs de watch (evita yt-dlp). Devuelve null si ningun cliente dio URL directa.
     */
    private suspend fun resolveViaYouTube(
        videoId: String,
        preferredQuality: String,
        preferredClientName: String,
        durationMs: Long,
    ): ResolvedAudioStream? = withContext(Dispatchers.IO) {
        val clients = getClientCandidates(preferredClientName)

        val topClients = clients.take(3)
        val candidateChannel = kotlinx.coroutines.channels.Channel<ResolvedAudioStream>(capacity = topClients.size)
        val probeJobs = topClients.map { client ->
            launch {
                val stream = tryResolveClient(videoId, client, preferredQuality, durationMs)
                if (stream != null) candidateChannel.trySend(stream)
            }
        }

        val fastResult = withTimeoutOrNull(2000L) {
            candidateChannel.receiveCatching().getOrNull()
        }
        if (fastResult != null) {
            probeJobs.forEach { it.cancel() }
            return@withContext fastResult
        }

        // Refuerzo: 2 clientes mas, en paralelo, timeout corto
        val backup = clients.drop(3).take(2)
        val backupChannel = kotlinx.coroutines.channels.Channel<ResolvedAudioStream>(capacity = backup.size)
        val backupJobs = backup.map { client ->
            launch {
                val stream = tryResolveClient(videoId, client, preferredQuality, durationMs)
                if (stream != null) backupChannel.trySend(stream)
            }
        }
        val backupResult = withTimeoutOrNull(1800L) {
            backupChannel.receiveCatching().getOrNull()
        }
        probeJobs.forEach { runCatching { it.cancel() } }
        backupJobs.forEach { runCatching { it.cancel() } }
        backupResult
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

            val direct = candidate.url
            // FAST PATH: URL directa. PERO si trae el parametro "n" (throttling de YouTube),
            // hay que deofuscarlo o el audio cargara a velocidad ridicula (minutos).
            // El runtime de cifrado ya esta calentado en segundo plano, asi que es rapido.
            val streamUrl = when {
                !direct.isNullOrBlank() && !hasNParam(direct) -> direct
                !direct.isNullOrBlank() -> withTimeoutOrNull(1500L) {
                    NewPipeUtils.getStreamUrl(candidate, videoId, client).getOrNull()
                } ?: direct
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
        } catch (_: Exception) {
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
