package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val clientCandidates = listOf(
        YouTubeClient.ANDROID_TESTSUITE,
        YouTubeClient.IOS,
        YouTubeClient.ANDROID_VR_NO_AUTH,
        YouTubeClient.WEB_REMIX,
        YouTubeClient.ANDROID_VR_1_61_48,
    )

    private val streamCache = ConcurrentHashMap<String, Pair<ResolvedAudioStream, Long>>()
    private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L

    suspend fun resolveStreamUrl(
        videoId: String,
        title: String = "",
        artist: String = "",
        durationMs: Long = 0L,
        playbackSource: String = "YT_MUSIC",
        flacQuality: String = "HI_RES",
        preferredQuality: String = "HIGH"
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

        for (client in clientCandidates) {
            try {
                val response = YouTube.player(videoId = videoId, client = client).getOrNull() ?: continue
                if (response.playabilityStatus.status != "OK") continue

                val formats = response.streamingData?.adaptiveFormats
                    ?.filter { it.isAudio && it.bitrate > 0 }
                    .orEmpty()

                if (formats.isEmpty()) continue

                val durationMs = response.videoDetails
                    ?.lengthSeconds
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.times(1000L) ?: 0L

                val candidate = selectFormat(formats, preferredQuality) ?: formats.firstOrNull()
                if (candidate != null) {
                    val streamUrl = NewPipeUtils.getStreamUrl(candidate, videoId, client).getOrNull()
                        ?: candidate.url

                    if (!streamUrl.isNullOrBlank()) {
                        val isOpus = candidate.mimeType.contains("audio/webm", ignoreCase = true) ||
                                candidate.mimeType.contains("opus", ignoreCase = true) ||
                                candidate.itag == 251 || candidate.itag == 250 || candidate.itag == 249

                        val formatLabel = formatAudioQualityLabel(candidate.mimeType, candidate.itag, candidate.bitrate)

                        val resolved = ResolvedAudioStream(
                            url = streamUrl,
                            itag = candidate.itag,
                            mimeType = candidate.mimeType,
                            bitrate = candidate.bitrate,
                            isOpus = isOpus,
                            durationMs = durationMs,
                            clientName = client.clientName,
                            formatLabel = formatLabel
                        )
                        streamCache[cacheKey] = resolved to System.currentTimeMillis()
                        return@withContext resolved
                    }
                }
            } catch (_: Exception) {
                // Continue to next client candidate
            }
        }

        // Fallback to direct youtube watch URL for mpv built-in yt-dlp handler
        ResolvedAudioStream(
            url = "https://www.youtube.com/watch?v=$videoId",
            durationMs = 0L,
            formatLabel = "AUTO"
        )
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
        val opusFormats = formats.filter {
            it.mimeType.contains("audio/webm", ignoreCase = true) ||
            it.mimeType.contains("opus", ignoreCase = true) ||
            it.itag == 251 || it.itag == 250 || it.itag == 249
        }

        return when (quality) {
            "HIGH" -> {
                opusFormats.find { it.itag == 251 }
                    ?: opusFormats.maxByOrNull { it.bitrate }
                    ?: formats.maxByOrNull { it.bitrate }
            }
            "MEDIUM" -> {
                formats.find { it.itag == 140 }
                    ?: opusFormats.find { it.itag == 250 }
                    ?: formats.minByOrNull { kotlin.math.abs(it.bitrate - 128_000) }
            }
            "LOW" -> {
                opusFormats.find { it.itag == 249 }
                    ?: formats.minByOrNull { it.bitrate }
            }
            else -> opusFormats.find { it.itag == 251 } ?: formats.maxByOrNull { it.bitrate }
        }
    }
}
