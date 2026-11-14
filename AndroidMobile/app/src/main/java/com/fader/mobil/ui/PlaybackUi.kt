package com.fader.mobil.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fader.mobil.core.FaderPlayer
import kotlinx.coroutines.delay

/** Observable snapshot of the engine, for Compose. */
class PlaybackState {
    var url by mutableStateOf<String?>(null)
        internal set
    var title by mutableStateOf<String?>(null)
        internal set
    var artist by mutableStateOf("")
        internal set
    var album by mutableStateOf("")
        internal set
    var isPlaying by mutableStateOf(false)
        internal set
    var positionMs by mutableStateOf(0)
        internal set
    var durationMs by mutableStateOf(0)
        internal set
    var artKey by mutableStateOf<String?>(null)
        internal set
    var artUri by mutableStateOf<String?>(null)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set

    internal fun refresh() {
        url = FaderPlayer.currentUrl
        title = FaderPlayer.title
        artist = FaderPlayer.artist.orEmpty()
        album = FaderPlayer.album.orEmpty()
        isPlaying = FaderPlayer.isPlaying
        positionMs = FaderPlayer.positionMs
        durationMs = FaderPlayer.durationMs
        error = FaderPlayer.lastError
        val art = FaderPlayer.currentArt()
        artKey = art?.key
        artUri = art?.uri
    }
}

@Composable
fun rememberPlaybackState(): PlaybackState {
    val state = remember { PlaybackState() }

    DisposableEffect(Unit) {
        val listener = FaderPlayer.Listener { state.refresh() }
        FaderPlayer.addListener(listener)
        state.refresh()
        onDispose { FaderPlayer.removeListener(listener) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (state.isPlaying) state.refresh()
            delay(500)
        }
    }

    return state
}

fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
