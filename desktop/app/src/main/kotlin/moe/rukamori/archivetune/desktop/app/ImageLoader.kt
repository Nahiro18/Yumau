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
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object ImageCache {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun getOrLoad(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        cache[url]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val connection = URI(url).toURL().openConnection()
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
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

@Composable
fun rememberNetworkImage(url: String?): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        bitmap = ImageCache.getOrLoad(url)
    }

    return bitmap
}
