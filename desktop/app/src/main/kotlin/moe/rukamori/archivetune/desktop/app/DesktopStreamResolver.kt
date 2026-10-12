package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.innertube.pages.NewPipeUtils
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

object DesktopStreamResolver {
    private val defaultClientOrder = listOf(
        YouTubeClient.VISIONOS,
        YouTubeClient.ANDROID_VR_NO_AUTH,
        YouTubeClient.ANDROID_VR_1_61_48,
        YouTubeClient.IOS,
        YouTubeClient.ANDROID_MUSIC,
        YouTubeClient.WEB_REMIX,
    )

    private val streamCache = ConcurrentHashMap<String, Pair<ResolvedAudioStream, Long>>()
    private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L

    fun getClientCandidates(preferredClientName: String?): List<YouTubeClient> {
        val preferred = when (preferredClientName) {
            "VISIONOS" -> YouTubeClient.VISIONOS
            "ANDROID_VR" -> YouTubeClient.ANDROID_VR_NO_AUTH
            "IOS" -> YouTubeClient.IOS
            "ANDROID_MUSIC" -> YouTubeClient.ANDROID_MUSIC
            "WEB_REMIX" -> YouTubeClient.WEB_REMIX
            else -> YouTubeClient.VISIONOS
        }
        return listOf(preferred) + defaultClientOrder.filter { it != preferred }
    }

    fun prefetch(
        videoId: String,
        title: String = "",
        artist: String = "",
        durationMs: Long = 0L,
        playbackSource: String = "YT_MUSIC",
        flacQuality: String = "HI_RES",
        preferredQuality: String = "HIGH",
        preferredClientName: String = "VISIONOS"
    ) {
        val cacheKey = if (playbackSource == "FLAC") "${videoId}_FLAC_${flacQuality}" else "${videoId}_$preferredQuality"
        if (streamCache.containsKey(cacheKey)) return
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

    suspend fun resolveStreamUrl(
        videoId: String,
        title: String = "",
        artist: String = "",
        durationMs: Long = 0L,
        playbackSource: String = "YT_MUSIC",
        flacQuality: String = "HI_RES",
        preferredQuality: String = "HIGH",
        preferredClientName: String = "VISIONOS"
    ): ResolvedAudioStream = withContext(Dispatchers.IO) {
        val cacheKey = if (playbackSource == "FLAC") "${videoId}_FLAC_${flacQuality}" else "${videoId}_$preferredQuality"
        streamCache[cacheKey]?.let { (cachedStream, timestamp) ->
            if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                return@withContext cachedStream
            } else {
                streamCache.remove(cacheKey)
            }
        }

        if (playbackSource == "FLAC" && title.isNotBlank()) {
            val flacStream = DesktopFlacResolver.resolve(
                title = title,
                artist = artist,
                durationMs = durationMs,
                quality = flacQuality
            )
            if (flacStream != null) {
                streamCache[cacheKey] = flacStream to System.currentTimeMillis()
                return@withContext flacStream
            }
        }

        val clients = getClientCandidates(preferredClientName)

        // Fast parallel race of top 2 clients for ultra-fast startup (<2s)
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

        val fastResult = withTimeoutOrNull(3000L) {
            candidateChannel.receiveCatching().getOrNull()
        }

        if (fastResult != null) {
            probeJobs.forEach { it.cancel() }
            streamCache[cacheKey] = fastResult to System.currentTimeMillis()
            return@withContext fastResult
        }

        // Sequential fallback for remaining clients if top 2 failed
        for (client in clients.drop(2)) {
            val stream = tryResolveClient(videoId, client, preferredQuality, durationMs)
            if (stream != null) {
                streamCache[cacheKey] = stream to System.currentTimeMillis()
                return@withContext stream
            }
        }

        // Fallback to direct youtube watch URL for mpv built-in yt-dlp handler
        ResolvedAudioStream(
            url = "https://www.youtube.com/watch?v=$videoId",
            durationMs = durationMs,
            formatLabel = "AUTO"
        )
    }

    private suspend fun tryResolveClient(
        videoId: String,
        client: YouTubeClient,
        preferredQuality: String,
        durationMs: Long
    ): ResolvedAudioStream? {
        return try {
            val response = withTimeoutOrNull(3000L) {
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

            // FAST PATH: Use direct URL without heavy JS deobfuscation
            val streamUrl = if (!candidate.url.isNullOrBlank()) {
                candidate.url
            } else {
                // CIPHER PATH: Only if format requires signature decipher
                withTimeoutOrNull(2500L) {
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
