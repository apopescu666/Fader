package com.fader.mobil.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Size
import com.fader.mobil.core.LocalLibrary
import com.fader.mobil.core.RemoteLibrary
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

/**
 * Serves album art to other processes (Android Auto) as content:// URIs.
 * Artwork is embedded in every track, so it is extracted once and cached.
 */
class CoverProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { RemoteLibrary.init(it) }
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val key = uri.pathSegments.lastOrNull() ?: throw FileNotFoundException()
        val url = runCatching {
            String(Base64.decode(key, Base64.URL_SAFE or Base64.NO_WRAP))
        }.getOrNull() ?: throw FileNotFoundException()
        val file = coverFile(url) ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun coverFile(url: String): File? {
        val ctx = context ?: return null
        val dir = File(ctx.cacheDir, "aa-covers").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val out = File(dir, "$hash.jpg")
        if (out.isFile && out.length() > 0) return out

        val remote = url.startsWith("http://") || url.startsWith("https://")
        val bitmap = (if (remote) remoteBitmap(url) else localBitmap(url)) ?: return null
        val scaled = scale(bitmap)
        return runCatching {
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            out.takeIf { it.length() > 0 }
        }.getOrNull()
    }

    private fun localBitmap(url: String): Bitmap? = runCatching {
        val ctx = context ?: return@runCatching null
        val art = LocalLibrary.art(url) ?: return@runCatching null
        val uri = Uri.parse(art.uri)
        // Covers live inside the audio file, so read the embedded picture.
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(ctx, uri)
            val bytes = retriever.embeddedPicture
            if (bytes != null) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                ctx.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    private fun remoteBitmap(url: String): Bitmap? = runCatching {
        val ctx = context ?: return@runCatching null
        if (!RemoteLibrary.isConnected) {
            val saved = com.fader.mobil.core.WebDavCredentials.load(ctx)
            if (saved != null) {
                runBlocking { RemoteLibrary.connect(saved.url, saved.user, saved.password) }
            }
        }
        val path = runBlocking { RemoteLibrary.coverPath(url) } ?: return@runCatching null
        BitmapFactory.decodeFile(path)
    }.getOrNull()

    private fun scale(source: Bitmap): Bitmap {
        val max = maxOf(source.width, source.height)
        if (max <= 480) return source
        val ratio = 480f / max
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        const val AUTHORITY = "com.fader.mobil.covers"

        fun uriFor(url: String): Uri {
            val key = Base64.encodeToString(
                url.toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP,
            )
            return Uri.parse("content://$AUTHORITY/$key")
        }
    }
}
