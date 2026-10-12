package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.kugou.KuGou
import moe.rukamori.archivetune.lrclib.LrcLib
import moe.rukamori.archivetune.simpmusic.SimpMusicLyrics
import java.io.File

object DesktopLyricsManager {

    suspend fun getLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationSeconds: Int,
    ): String? = withContext(Dispatchers.IO) {
        val settings = DesktopSettingsStore.settings.value

        // Check local lyrics cache first
        val cacheKey = videoId.ifBlank { "${artist}_$title" }.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val cacheFile = File(DesktopSettingsStore.lyricsCacheDir, "$cacheKey.lrc")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                return@withContext cacheFile.readText()
            } catch (_: Exception) {}
        }

        val providers = settings.lyricsProviderOrder.split(",").map { it.trim() }

        for (provider in providers) {
            val lyrics = when (provider) {
                "LRCLIB" -> if (settings.enableLrclib) fetchLrcLib(title, artist, durationSeconds) else null
                "KuGou" -> if (settings.enableKugou) fetchKuGou(title, artist, durationSeconds) else null
                "SimpMusic" -> if (settings.enableSimpMusic) fetchSimpMusic(videoId, durationSeconds) else null
                else -> null
            }

            if (!lyrics.isNullOrBlank()) {
                // Save to lyrics cache
                try {
                    cacheFile.writeText(lyrics)
                } catch (_: Exception) {}
                return@withContext lyrics
            }
        }

        // Fallback: If prioritized list didn't hit, try any enabled provider
        if (settings.enableLrclib && !providers.contains("LRCLIB")) {
            fetchLrcLib(title, artist, durationSeconds)?.let {
                try { cacheFile.writeText(it) } catch (_: Exception) {}
                return@withContext it
            }
        }
        if (settings.enableKugou && !providers.contains("KuGou")) {
            fetchKuGou(title, artist, durationSeconds)?.let {
                try { cacheFile.writeText(it) } catch (_: Exception) {}
                return@withContext it
            }
        }
        if (settings.enableSimpMusic && !providers.contains("SimpMusic")) {
            fetchSimpMusic(videoId, durationSeconds)?.let {
                try { cacheFile.writeText(it) } catch (_: Exception) {}
                return@withContext it
            }
        }

        null
    }

    private suspend fun fetchLrcLib(title: String, artist: String, durationSeconds: Int): String? {
        return runCatching {
            LrcLib.getLyrics(
                title = title,
                artist = artist,
                duration = durationSeconds
            ).getOrNull()
        }.getOrNull()
    }

    private suspend fun fetchKuGou(title: String, artist: String, durationSeconds: Int): String? {
        return runCatching {
            KuGou.getLyrics(
                title = title,
                artist = artist,
                duration = durationSeconds
            ).getOrNull()
        }.getOrNull()
    }

    private suspend fun fetchSimpMusic(videoId: String, durationSeconds: Int): String? {
        if (videoId.isBlank()) return null
        return runCatching {
            SimpMusicLyrics.getLyrics(
                videoId = videoId,
                duration = durationSeconds
            ).getOrNull()
        }.getOrNull()
    }
}
