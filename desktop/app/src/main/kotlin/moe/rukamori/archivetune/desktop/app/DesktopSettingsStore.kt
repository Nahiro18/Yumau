package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DesktopAppSettings(
    val playbackSource: String = "YT_MUSIC", // "YT_MUSIC", "FLAC"
    val flacQuality: String = "HI_RES", // "CD", "HI_RES", "MAX"
    val qobuzAppId: String = "",
    val qobuzAppSecret: String = "",
    val qobuzUserAuthToken: String = "",
    val audioQuality: String = "HIGH", // HIGH (Opus 160kbps), MEDIUM (128kbps), LOW (50-70kbps)
    val streamingClient: String = "ANDROID_VR", // ANDROID_VR, IOS, WEB_REMIX
    val volumeNormalization: Boolean = true,
    val skipSilence: Boolean = false,
    val autoSkipNextOnError: Boolean = true,
    val autoScrollLyrics: Boolean = true,
    val discordRpcEnabled: Boolean = false,
)

object DesktopSettingsStore {
    private val configDir = File(System.getProperty("user.home"), ".config/YumaPlayer").apply { mkdirs() }
    private val settingsFile = File(configDir, "settings.json")
    val cacheDir = File(configDir, "cache").apply { mkdirs() }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val _settings = MutableStateFlow(DesktopAppSettings())
    val settings: StateFlow<DesktopAppSettings> = _settings.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        try {
            if (settingsFile.exists()) {
                val loaded = json.decodeFromString<DesktopAppSettings>(settingsFile.readText())
                _settings.value = loaded
            }
        } catch (_: Exception) {}
    }

    suspend fun updateSettings(transform: (DesktopAppSettings) -> DesktopAppSettings) = withContext(Dispatchers.IO) {
        val updated = transform(_settings.value)
        _settings.value = updated
        try {
            settingsFile.writeText(json.encodeToString(updated))
        } catch (_: Exception) {}
    }

    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        var freedBytes = 0L
        fun deleteRecursively(f: File) {
            if (f.isDirectory) {
                f.listFiles()?.forEach { deleteRecursively(it) }
            }
            freedBytes += f.length()
            f.delete()
        }
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { deleteRecursively(it) }
        }
        freedBytes
    }

    fun getCacheSizeBytes(): Long {
        var total = 0L
        if (cacheDir.exists()) {
            cacheDir.walkTopDown().forEach {
                if (it.isFile) total += it.length()
            }
        }
        return total
    }
}
