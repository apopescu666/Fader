package com.fader.mobil.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.Request

private val gradients = listOf(
    listOf(Color(0xFF7FF3E6), Color(0xFF18A9A0), Color(0xFF0B3F47), Color(0xFF071A24)),
    listOf(Color(0xFFFFD2E4), Color(0xFFE0638F), Color(0xFF6D1B3C), Color(0xFF2A0A18)),
    listOf(Color(0xFFFFE8A3), Color(0xFFE0A13C), Color(0xFF6B3F10), Color(0xFF241305)),
    listOf(Color(0xFFC9D6FF), Color(0xFF6D7FD6), Color(0xFF232A63), Color(0xFF0D1026)),
    listOf(Color(0xFFB8FFD9), Color(0xFF2FA96A), Color(0xFF0D4A2C), Color(0xFF05170F)),
    listOf(Color(0xFFFFCF9E), Color(0xFFD97A3E), Color(0xFF6B2F14), Color(0xFF241006)),
    listOf(Color(0xFFE6D4FF), Color(0xFF9472D9), Color(0xFF3D2A6B), Color(0xFF150F24)),
    listOf(Color(0xFFA9E8FF), Color(0xFF3F9FC9), Color(0xFF134557), Color(0xFF061A22)),
)

private fun brushFor(index: Int): Brush =
    Brush.linearGradient(gradients[(index - 1).coerceIn(0, gradients.size - 1)])

/**
 * Album art cache. Keyed per album, decoded off the main thread, held in a
 * memory LRU so scrolling never re-decodes the same cover.
 */
object ArtworkLoader {
    private const val MAX_BYTES = 24 * 1024 * 1024

    private val memory = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val http = OkHttpClient()
    private val waiters = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun cached(key: String): Bitmap? = memory.get(key)

    /** Average cover colour, darkened so it can sit behind text. */
    fun dominantColor(key: String): androidx.compose.ui.graphics.Color? {
        val bitmap = memory.get(key) ?: return null
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var samples = 0
        val stepX = maxOf(1, bitmap.width / 16)
        val stepY = maxOf(1, bitmap.height / 16)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                if (luminance in 0.06..0.94) {
                    red += r
                    green += g
                    blue += b
                    samples++
                }
                x += stepX
            }
            y += stepY
        }
        if (samples == 0) return null
        val scale = 0.42
        return androidx.compose.ui.graphics.Color(
            (red / samples * scale).toInt().coerceIn(0, 255),
            (green / samples * scale).toInt().coerceIn(0, 255),
            (blue / samples * scale).toInt().coerceIn(0, 255),
        )
    }

    /** Remote covers (http/https) are fetched with the share's auth header. */
    fun loadRemote(
        context: Context,
        key: String,
        url: String,
        authHeader: String?,
        onLoaded: (Bitmap?) -> Unit,
    ) {
        memory.get(key)?.let {
            onLoaded(it)
            return
        }
        waiters.computeIfAbsent(key) { java.util.concurrent.CopyOnWriteArrayList() }.add(onLoaded)
        if (!inFlight.add(key)) return
        val client = http
        scope.launch {
            val bitmap = runCatching {
                val builder = Request.Builder().url(url)
                if (authHeader != null) builder.header("Authorization", authHeader)
                val bytes = client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.bytes()
                } ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
            inFlight.remove(key)
            if (bitmap != null) memory.put(key, bitmap)
            val pending = waiters.remove(key)
            withContext(Dispatchers.Main) { pending?.forEach { it(bitmap) } }
        }
    }

    /** Remote track whose picture lives inside the file: extract on demand. */
    fun loadTrack(context: Context, key: String, trackUrl: String, onLoaded: (Bitmap?) -> Unit) {
        memory.get(key)?.let {
            onLoaded(it)
            return
        }
        waiters.computeIfAbsent(key) { java.util.concurrent.CopyOnWriteArrayList() }.add(onLoaded)
        if (!inFlight.add(key)) return
        scope.launch {
            val path = runCatching { com.fader.mobil.core.RemoteLibrary.coverPath(trackUrl) }.getOrNull()
            var bitmap = path?.let { decodeSampled(it) }
            if (bitmap == null) {
                val folderCover = com.fader.mobil.core.RemoteLibrary.folderCover(trackUrl)
                if (folderCover != null) bitmap = fetch(folderCover)
            }
            inFlight.remove(key)
            if (bitmap != null) memory.put(key, bitmap)
            val pending = waiters.remove(key)
            withContext(Dispatchers.Main) { pending?.forEach { it(bitmap) } }
        }
    }

    /** Downloads a cover image with the share credentials and decodes it. */
    private fun fetch(url: String): Bitmap? = runCatching {
        val builder = Request.Builder().url(url)
        com.fader.mobil.core.RemoteLibrary.authHeader?.let { builder.header("Authorization", it) }
        val bytes = http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun decodeSampled(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Cover already extracted to the app cache. */
    fun loadFile(context: Context, key: String, path: String, onLoaded: (Bitmap?) -> Unit) {
        memory.get(key)?.let {
            onLoaded(it)
            return
        }
        waiters.computeIfAbsent(key) { java.util.concurrent.CopyOnWriteArrayList() }.add(onLoaded)
        if (!inFlight.add(key)) return
        scope.launch {
            val bitmap = runCatching { decodeSampled(path) }.getOrNull()
            inFlight.remove(key)
            if (bitmap != null) memory.put(key, bitmap)
            val pending = waiters.remove(key)
            withContext(Dispatchers.Main) { pending?.forEach { it(bitmap) } }
        }
    }

    fun load(context: Context, key: String, uri: String, onLoaded: (Bitmap?) -> Unit) {
        memory.get(key)?.let {
            onLoaded(it)
            return
        }
        // Several visible rows often share an album: every caller gets the result.
        waiters.computeIfAbsent(key) { java.util.concurrent.CopyOnWriteArrayList() }.add(onLoaded)
        if (!inFlight.add(key)) return
        val resolver = context.applicationContext.contentResolver
        scope.launch {
            val bitmap = runCatching {
                resolver.loadThumbnail(Uri.parse(uri), Size(512, 512), null)
            }.getOrNull()
            inFlight.remove(key)
            if (bitmap != null) memory.put(key, bitmap)
            val pending = waiters.remove(key)
            withContext(Dispatchers.Main) {
                pending?.forEach { it(bitmap) }
            }
        }
    }
}

/** Cover art with a deterministic gradient fallback when nothing is embedded. */
@Composable
fun Artwork(
    index: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    artKey: String? = null,
    artUri: String? = null,
) {
    val context = LocalContext.current
    var bitmap by remember(artKey) {
        mutableStateOf(artKey?.let { ArtworkLoader.cached(it) })
    }

    LaunchedEffect(artKey, artUri) {
        if (bitmap == null && artKey != null && artUri != null) {
            if (artUri.startsWith("http")) {
                ArtworkLoader.loadRemote(context, artKey, artUri, com.fader.mobil.core.RemoteLibrary.authHeader) { loaded ->
                    if (loaded != null) bitmap = loaded
                }
            } else if (artUri.startsWith("remote:")) {
                ArtworkLoader.loadTrack(context, artKey, artUri.removePrefix("remote:")) { loaded ->
                    if (loaded != null) bitmap = loaded
                }
            } else if (artUri.startsWith("file:")) {
                ArtworkLoader.loadFile(context, artKey, artUri.removePrefix("file:")) { loaded ->
                    if (loaded != null) bitmap = loaded
                }
            } else {
                ArtworkLoader.load(context, artKey, artUri) { loaded ->
                    if (loaded != null) bitmap = loaded
                }
            }
        }
    }

    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(brushFor(index)),
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun ArtworkBox(
    index: Int,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    artKey: String? = null,
    artUri: String? = null,
) {
    val context = LocalContext.current
    var bitmap by remember(artKey) {
        mutableStateOf(artKey?.let { ArtworkLoader.cached(it) })
    }
    LaunchedEffect(artKey, artUri) {
        if (bitmap == null && artKey != null && artUri != null) {
            ArtworkLoader.load(context, artKey, artUri) { loaded ->
                if (loaded != null) bitmap = loaded
            }
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(brushFor(index)),
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
