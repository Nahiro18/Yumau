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
    // Reproducción y Audio
    val playbackSource: String = "YT_MUSIC", // "YT_MUSIC", "FLAC"
    val flacQuality: String = "HI_RES", // "CD", "HI_RES", "MAX"
    val flacFallbackToYoutube: Boolean = true, // si FLAC no encuentra la pista, usa YouTube (secuencial, sin carrera)
    val qobuzAppId: String = "",
    val qobuzAppSecret: String = "",
    val qobuzUserAuthToken: String = "",
    val youtubeCookie: String = "", // cabecera Cookie de youtube.com (supera el bloqueo antibot)
    val audioQuality: String = "HIGH", // HIGH (Opus 160kbps), MEDIUM (128kbps), LOW (50-70kbps)
    val streamingClient: String = "ANDROID_VR", // ANDROID_VR, IOS, WEB_REMIX
    val volumeNormalization: Boolean = true,
    val skipSilence: Boolean = false,
    val autoSkipNextOnError: Boolean = true,
    val seekDurationSeconds: Int = 10, // 5, 10, 15, 30

    // Letras
    val autoScrollLyrics: Boolean = true,
    val lyricsClick: Boolean = true,
    val lyricsLineBlur: Boolean = true,
    val lyricsTextSize: Float = 22f, // 16f .. 36f
    val lyricsLineSpacing: Float = 1.2f, // 1.0f .. 2.0f
    val enableLrclib: Boolean = true,
    val enableKugou: Boolean = true,
    val enableSimpMusic: Boolean = true,
    val lyricsProviderOrder: String = "LRCLIB,KuGou,SimpMusic",

    // Apariencia
    val darkMode: String = "AUTO", // "ON", "OFF", "AUTO"
    val pureBlack: Boolean = false,
    val appFont: String = "DEFAULT", // "DEFAULT", "SYSTEM"
    val defaultTab: Int = 0, // 0: Home, 1: Search, 2: Library
    val disableAnimations: Boolean = false,

    // Contenido
    val contentLanguage: String = "system",
    val contentCountry: String = "system",
    val hideExplicit: Boolean = false,

    // Integraciones
    val discordRpcEnabled: Boolean = false,
    val lastFmUsername: String = "",
    val lastFmSessionKey: String = "",
    val lastFmScrobblingEnabled: Boolean = false,
)

object DesktopSettingsStore {
    private val configDir = File(System.getProperty("user.home"), ".config/YumaPlayer").apply { mkdirs() }
    private val settingsFile = File(configDir, "settings.json")
    val cacheDir = File(configDir, "cache").apply { mkdirs() }
    val audioCacheDir = File(cacheDir, "audio").apply { mkdirs() }
    val imagesCacheDir = File(cacheDir, "images").apply { mkdirs() }
    val lyricsCacheDir = File(cacheDir, "lyrics").apply { mkdirs() }

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
        clearDirectory(cacheDir)
    }

    suspend fun clearAudioCache(): Long = withContext(Dispatchers.IO) {
        clearDirectory(audioCacheDir)
    }

    suspend fun clearImageCache(): Long = withContext(Dispatchers.IO) {
        clearDirectory(imagesCacheDir)
    }

    suspend fun clearLyricsCache(): Long = withContext(Dispatchers.IO) {
        clearDirectory(lyricsCacheDir)
    }

    private fun clearDirectory(dir: File): Long {
        var freedBytes = 0L
        fun deleteRecursively(f: File) {
            if (f.isDirectory) {
                f.listFiles()?.forEach { deleteRecursively(it) }
            }
            freedBytes += f.length()
            f.delete()
        }
        if (dir.exists()) {
            dir.listFiles()?.forEach { deleteRecursively(it) }
        }
        return freedBytes
    }

    fun getCacheSizeBytes(): Long = getDirectorySizeBytes(cacheDir)
    fun getAudioCacheSizeBytes(): Long = getDirectorySizeBytes(audioCacheDir)
    fun getImageCacheSizeBytes(): Long = getDirectorySizeBytes(imagesCacheDir)
    fun getLyricsCacheSizeBytes(): Long = getDirectorySizeBytes(lyricsCacheDir)

    private fun getDirectorySizeBytes(dir: File): Long {
        var total = 0L
        if (dir.exists()) {
            dir.walkTopDown().forEach {
                if (it.isFile) total += it.length()
            }
        }
        return total
    }

    fun exportSettingsJson(): String {
        return json.encodeToString(_settings.value)
    }

    suspend fun importSettingsJson(jsonContent: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val imported = json.decodeFromString<DesktopAppSettings>(jsonContent)
            _settings.value = imported
            settingsFile.writeText(json.encodeToString(imported))
            true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Convierte lo que pegue el usuario (cabecera "Cookie: a=b; c=d" o un cookies.txt en
 * formato Netscape) en una cabecera Cookie valida para youtube.com. Devuelve null si
 * no hay ninguna cookie util.
 */
fun buildYouTubeCookieHeader(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val netscape = lines.any { line -> line.count { it == '\t' } >= 6 }
    val pairs = if (netscape) {
        lines.mapNotNull { line ->
            val clean = line.removePrefix("#HttpOnly_")
            if (clean.startsWith("#")) return@mapNotNull null
            val parts = clean.split('\t')
            if (parts.size < 7) return@mapNotNull null
            val domain = parts[0]
            if (!domain.contains("youtube.com") && !domain.contains("google.com")) return@mapNotNull null
            "${parts[5].trim()}=${parts[6].trim()}"
        }
    } else {
        lines
            .flatMap { line -> line.replace(Regex("(?i)^cookie:\\s*"), "").split(';') }
            .map { it.trim() }
            .filter { it.contains('=') }
    }
    val header = pairs
        .filter { it.substringBefore('=').isNotBlank() }
        .distinct()
        .joinToString("; ")
    return header.ifBlank { null }
}
