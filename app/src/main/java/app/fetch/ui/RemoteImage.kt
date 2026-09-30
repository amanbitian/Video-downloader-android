package app.fetch.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private val cache = LruCache<String, ImageBitmap>(24)

/** Small preview image (og:image, video poster). Downsampled, size-capped, cached; renders nothing until loaded or on failure. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, maxPixels: Int = 480) {
    var image by remember(url) { mutableStateOf(url?.let { cache.get(it) }) }
    LaunchedEffect(url) {
        if (url != null && image == null) {
            image = withContext(Dispatchers.IO) { runCatching { load(url, maxPixels) }.getOrNull() }?.also { cache.put(url, it) }
        }
    }
    image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier) }
}

private fun load(url: String, maxPixels: Int): ImageBitmap? {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 8_000 }
    val bytes = try {
        if (connection.responseCode !in 200..299) return null
        connection.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
                if (out.size() > MAX_IMAGE_BYTES) return null
            }
            out.toByteArray()
        }
    } finally { connection.disconnect() }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPixels && bounds.outHeight / (sample * 2) >= maxPixels / 2) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
