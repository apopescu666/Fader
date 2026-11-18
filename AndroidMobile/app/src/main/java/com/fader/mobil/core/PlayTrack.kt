package com.fader.mobil.core

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.fader.mobil.engine.AudioSource

/**
 * A track as the playback engine sees it: one resolvable URL plus the metadata
 * the UI and the notification need. Independent of any player implementation.
 */
data class PlayTrack(
    val url: String,
    val remote: Boolean,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val headers: Map<String, String> = emptyMap(),
) {
    fun toSource(): AudioSource = AudioSource(
        url = url,
        remote = remote,
        headers = headers,
        title = title,
        artist = artist,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
    )
}

/** TrackItem already knows where the bytes live; this only flattens it. */
internal fun TrackItem.toPlayTrack(): PlayTrack {
    val remote = entry.url.startsWith("http://") || entry.url.startsWith("https://")
    val headers = runCatching { nas.httpHeaders(entry)?.toMap() ?: emptyMap() }
        .getOrDefault(emptyMap())
    return PlayTrack(
        url = entry.url,
        remote = remote,
        title = title.orEmpty(),
        artist = artist.orEmpty(),
        album = album.orEmpty(),
        durationMs = if (duration > 0) duration.toLong() else entry.durationMs,
        sizeBytes = entry.size,
        headers = headers,
    )
}

/** Builds media items for the car session and back again. No player needed. */
object SessionItems {

    const val MEDIA_ID_PREFIX = "track:"
    const val EXTRA_CONTAINER = "fader.container"

    fun item(
        url: String,
        title: String?,
        artist: String?,
        album: String?,
        artworkUri: String?,
        container: String? = null,
    ): MediaItem {
        val extras = Bundle().apply {
            if (container != null) putString(EXTRA_CONTAINER, container)
        }
        val fallback = RemoteLibrary.decodeName(url.substringAfterLast('/'))
        val metadata = MediaMetadata.Builder()
            .setTitle(RemoteLibrary.decodeName(title?.takeIf { it.isNotBlank() } ?: fallback))
            .setArtist(RemoteLibrary.decodeName(artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"))
            .setAlbumTitle(RemoteLibrary.decodeName(album.orEmpty()))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .apply { if (artworkUri != null) setArtworkUri(android.net.Uri.parse(artworkUri)) }
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(MEDIA_ID_PREFIX + url)
            .setMediaMetadata(metadata)
            .build()
    }

    /** The URL a car media item points at, or null for anything else. */
    fun urlOf(item: MediaItem): String? =
        if (item.mediaId.startsWith(MEDIA_ID_PREFIX)) {
            item.mediaId.removePrefix(MEDIA_ID_PREFIX)
        } else {
            null
        }

    fun containerOf(item: MediaItem): String? =
        item.mediaMetadata.extras?.getString(EXTRA_CONTAINER)

    /** "folder|remote|parent/|name" for a track URL (used when extras are lost). */
    fun folderContainerFor(url: String): String? {
        val trimmed = url.trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "")
        if (parent.isEmpty()) return null
        val remote = url.startsWith("http")
        val name = RemoteLibrary.decodeName(parent.trimEnd('/').substringAfterLast('/'))
        return "folder|${if (remote) 1 else 0}|$parent/|$name"
    }
}
