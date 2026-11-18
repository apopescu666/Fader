package com.fader.mobil.engine

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.fader.mobil.core.EngineBackend
import com.fader.mobil.core.PlayTrack
import com.fader.mobil.core.SessionItems
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Exposes [EngineBackend] as a Media3 [Player], so the MediaSession, Android Auto
 * and the headset buttons drive the custom engine instead of ExoPlayer.
 */
@UnstableApi
class EngineAdapter : SimpleBasePlayer(Looper.getMainLooper()) {

    private val handler = Handler(Looper.getMainLooper())
    private var items: List<MediaItem> = emptyList()

    private val ticker = object : Runnable {
        override fun run() {
            invalidateState()
            handler.postDelayed(this, 500)
        }
    }

    init {
        handler.postDelayed(ticker, 500)
    }

    override fun getState(): State {
        val snapshot = EngineBackend.snapshot()
        val playlist = items.mapIndexed { index, item ->
            MediaItemData.Builder(index)
                .setMediaItem(item)
                .setDurationUs(
                    when {
                        index == snapshot.index && snapshot.durationMs > 0 ->
                            snapshot.durationMs * 1000
                        else -> {
                            val known = item.mediaMetadata.extras
                                ?.getLong("fader.durationMs", 0L) ?: 0L
                            if (known > 0) known * 1000 else C.TIME_UNSET
                        }
                    },
                )
                .build()
        }
        val safeIndex = if (playlist.isEmpty()) 0 else snapshot.index.coerceIn(0, playlist.lastIndex)
        return State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(safeIndex)
            .setContentPositionMs(snapshot.positionMs)
            .setPlaybackState(if (playlist.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .setPlayWhenReady(
                snapshot.playing,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setRepeatMode(repeatForEngine(EngineBackend.repeat))
            .setShuffleModeEnabled(EngineBackend.shuffle)
            .build()
    }

    private fun repeatForEngine(mode: Int): Int = when (mode) {
        2 -> Player.REPEAT_MODE_ONE
        1 -> Player.REPEAT_MODE_ALL
        else -> Player.REPEAT_MODE_OFF
    }

    private fun toTrack(item: MediaItem): PlayTrack? {
        val url = SessionItems.urlOf(item)
            ?: item.localConfiguration?.uri?.toString()
            ?: return null
        if (url.isBlank()) return null
        val remote = url.startsWith("http://") || url.startsWith("https://")
        return PlayTrack(
            url = url,
            remote = remote,
            title = com.fader.mobil.core.RemoteLibrary.decodeName(
                item.mediaMetadata.title?.toString().orEmpty(),
            ),
            artist = com.fader.mobil.core.RemoteLibrary.decodeName(
                item.mediaMetadata.artist?.toString().orEmpty(),
            ),
            album = com.fader.mobil.core.RemoteLibrary.decodeName(
                item.mediaMetadata.albumTitle?.toString().orEmpty(),
            ),
            headers = EngineBackend.headersFor(url),
        )
    }

    private fun applyItems(startIndex: Int, startPositionMs: Long) {
        val tracks = items.mapNotNull { toTrack(it) }
        if (tracks.isEmpty()) return
        val container = items.firstNotNullOfOrNull { SessionItems.containerOf(it) }
            ?: items.firstNotNullOfOrNull { item ->
                SessionItems.urlOf(item)?.let { SessionItems.folderContainerFor(it) }
            }
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        EngineBackend.playCarTracks(tracks, index, startPositionMs.coerceAtLeast(0), container)
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        items = mediaItems.toList()
        applyItems(startIndex, startPositionMs)
        invalidateState()
        return Futures.immediateFuture(null)
    }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        val list = items.toMutableList()
        list.addAll(index.coerceIn(0, list.size), mediaItems)
        items = list
        val snapshot = EngineBackend.snapshot()
        applyItems(snapshot.index, snapshot.positionMs)
        invalidateState()
        return Futures.immediateFuture(null)
    }

    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        val list = items.toMutableList()
        val from = fromIndex.coerceIn(0, list.size)
        val to = toIndex.coerceIn(from, list.size)
        repeat(to - from) { list.removeAt(from) }
        list.addAll(from, mediaItems)
        items = list
        val snapshot = EngineBackend.snapshot()
        applyItems(snapshot.index, snapshot.positionMs)
        invalidateState()
        return Futures.immediateFuture(null)
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val list = items.toMutableList()
        val from = fromIndex.coerceIn(0, list.size)
        val to = toIndex.coerceIn(from, list.size)
        repeat(to - from) { list.removeAt(from) }
        items = list
        val snapshot = EngineBackend.snapshot()
        if (list.isEmpty()) {
            EngineBackend.stop()
        } else {
            applyItems(snapshot.index, snapshot.positionMs)
        }
        invalidateState()
        return Futures.immediateFuture(null)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) EngineBackend.play() else EngineBackend.pause()
        return Futures.immediateFuture(null)
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateFuture(null)

    override fun handleStop(): ListenableFuture<*> {
        EngineBackend.pause()
        return Futures.immediateFuture(null)
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        EngineBackend.jumpTo(mediaItemIndex, positionMs)
        return Futures.immediateFuture(null)
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        val target = when (repeatMode) {
            2 -> 2
            1 -> 1
            else -> 0
        }
        while (EngineBackend.repeat != target) EngineBackend.cycleRepeat()
        invalidateState()
        return Futures.immediateFuture(null)
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        EngineBackend.setShuffle(shuffleModeEnabled)
        invalidateState()
        return Futures.immediateFuture(null)
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacks(ticker)
        return Futures.immediateFuture(null)
    }
}
