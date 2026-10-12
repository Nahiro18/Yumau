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
import java.net.URI
import java.util.Collections
import java.util.LinkedHashMap

object ImageCache {
    private const val MAX_ENTRIES = 120
    // Thread-safe LRU Cache bounded to MAX_ENTRIES (prevents heap memory leaks)
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
                return size > MAX_ENTRIES
            }
        }
    )

    // Limits concurrent network downloads to avoid thread/socket exhaustion
    private val downloadSemaphore = Semaphore(6)

    suspend fun getOrLoad(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        cache[url]?.let { return it }

        return withContext(Dispatchers.IO) {
            downloadSemaphore.withPermit {
                cache[url]?.let { return@withPermit it }
                try {
                    val connection = URI(url).toURL().openConnection()
                    connection.connectTimeout = 6000
                    connection.readTimeout = 6000
                    val bytes = connection.getInputStream().use { it.readBytes() }
                    val bitmap = loadImageBitmap(ByteArrayInputStream(bytes))
                    cache[url] = bitmap
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
