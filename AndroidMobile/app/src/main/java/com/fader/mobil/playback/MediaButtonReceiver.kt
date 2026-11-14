package com.fader.mobil.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * Starts playback from the saved session when a headset / paired device sends
 * a play command while the app is not running (like AIMP does).
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = runCatching {
            intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        }.getOrNull()
        if (event != null && event.action != KeyEvent.ACTION_DOWN) return

        // A bare intent (no key event) comes from our own session registration:
        // treat it as a play request.
        val resume = event == null || when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> true
            else -> false
        }
        if (!resume) return

        runCatching {
            val service = Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_RESUME_LAST)
            ContextCompat.startForegroundService(context, service)
        }
    }
}
