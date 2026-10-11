package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.innertube.pages.NewPipeUtils

data class ResolvedAudioStream(
    val url: String,
    val itag: Int? = null,
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val isOpus: Boolean = false,
    val durationMs: Long = 0L,
    val clientName: String? = null,
)

object DesktopStreamResolver {
    private val clientCandidates = listOf(
        YouTubeClient.ANDROID_VR_1_61_48,
        YouTubeClient.IOS,
        YouTubeClient.ANDROID_VR_NO_AUTH,
        YouTubeClient.WEB_REMIX,
    )

    suspend fun resolveStreamUrl(
        videoId: String,
        preferredQuality: String = "HIGH"
    ): ResolvedAudioStream = withContext(Dispatchers.IO) {
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

                        return@withContext ResolvedAudioStream(
                            url = streamUrl,
                            itag = candidate.itag,
                            mimeType = candidate.mimeType,
                            bitrate = candidate.bitrate,
                            isOpus = isOpus,
                            durationMs = durationMs,
                            clientName = client.clientName
                        )
                    }
                }
            } catch (_: Exception) {
                // Try next client
            }
        }

        // Fallback to direct youtube watch URL for mpv built-in yt-dlp handler
        ResolvedAudioStream(
            url = "https://www.youtube.com/watch?v=$videoId",
            durationMs = 0L
        )
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
                // 1st priority: itag 251 (160kbps Opus), then highest bitrate Opus, then highest bitrate audio
                opusFormats.find { it.itag == 251 }
                    ?: opusFormats.maxByOrNull { it.bitrate }
                    ?: formats.maxByOrNull { it.bitrate }
            }
            "MEDIUM" -> {
                formats.find { it.itag == 140 } // AAC 128kbps
                    ?: opusFormats.find { it.itag == 250 } // Opus ~70kbps
                    ?: formats.minByOrNull { kotlin.math.abs(it.bitrate - 128_000) }
            }
            "LOW" -> {
                opusFormats.find { it.itag == 249 } // Opus ~50kbps
                    ?: formats.minByOrNull { it.bitrate }
            }
            else -> opusFormats.find { it.itag == 251 } ?: formats.maxByOrNull { it.bitrate }
        }
    }
}
