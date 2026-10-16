package moe.rukamori.archivetune.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

object ImageCache {
    private const val MAX_ENTRIES = 300
    // Thread-safe LRU Cache (memoria) para scroll fluido estilo Android
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
                return size > MAX_ENTRIES
            }
        }
    )

    // Limita descargas concurrentes para no saturar sockets
    private val downloadSemaphore = Semaphore(8)

    private val diskDir: File by lazy {
        File(System.getProperty("user.home"), ".config/YumaPlayer/cache/images").apply { mkdirs() }
    }

    private fun diskFileFor(url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(diskDir, "$hash.img")
    }

    suspend fun getOrLoad(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        cache[url]?.let { return it }

        return withContext(Dispatchers.IO) {
            downloadSemaphore.withPermit {
                cache[url]?.let { return@withPermit it }
                // 1. Disco primero (0 red si ya se vio la caratula)
                try {
                    val f = diskFileFor(url)
                    if (f.exists() && f.length() > 0 && f.length() < 8_000_000) {
                        runCatching {
                            val bitmap = loadImageBitmap(f.inputStream().buffered())
                            cache[url] = bitmap
                            return@withPermit bitmap
                        }
                    }
                } catch (_: Exception) {}
                // 2. Red con timeouts cortos para no congelar listas
                try {
                    val connection = URI(url).toURL().openConnection()
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000
                    val bytes = connection.getInputStream().use { it.readBytes() }
                    if (bytes.isEmpty()) return@withPermit null
                    val bitmap = loadImageBitmap(ByteArrayInputStream(bytes))
                    cache[url] = bitmap
                    // Guardamos en disco en background (thumbnails pequenos)
                    runCatching {
                        if (bytes.size < 2_000_000) diskFileFor(url).writeBytes(bytes)
                    }
                    bitmap
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}

@Composable
fun rememberNetworkImage(url: String?): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        bitmap = ImageCache.getOrLoad(url)
    }

    return bitmap
}
