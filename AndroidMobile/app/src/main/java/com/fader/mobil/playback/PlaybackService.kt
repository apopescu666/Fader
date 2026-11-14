package com.fader.mobil.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Bundle
import android.view.KeyEvent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.util.Size
import com.fader.mobil.MainActivity
import com.fader.mobil.core.FaderPlayer
import com.fader.mobil.data.Favorite
import com.fader.mobil.data.FavoritesStore
import com.fader.mobil.core.LocalLibrary
import com.fader.mobil.core.RemoteLibrary
import com.fader.mobil.core.Search
import com.fader.mobil.core.WebDavCredentials
import com.fader.mobil.data.PlaybackSessionStore
import kotlinx.coroutines.runBlocking

/**
 * Foreground service that keeps playback alive with a media notification and a
 * platform MediaSession (lock screen, headsets, Bluetooth).
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSession
    @Volatile private var lastMediaButtonAt = 0L
    private lateinit var notifications: NotificationManager

    private val listener = FaderPlayer.Listener { publishSafely() }


    override fun onCreate() {
        super.onCreate()
        // The service can be started by a headset button with no UI running.
        runCatching { FaderPlayer.init(applicationContext) }
        try {
            setUp()
        } catch (error: Throwable) {
            stopSelf()
        }
    }

    private fun setUp() {
        notifications = getSystemService(NotificationManager::class.java)
        createChannel()

        session = MediaSession(this, "Fader").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    FaderPlayer.note("session: onPlay")
                    FaderPlayer.play()
                }

                override fun onPause() {
                    val screen = runCatching {
                        (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
                    }.getOrDefault(true)
                    val ago = if (lastMediaButtonAt == 0L) {
                        "never"
                    } else {
                        "${(System.currentTimeMillis() - lastMediaButtonAt) / 1000}s ago"
                    }
                    FaderPlayer.note("session: onPause screenOn=$screen lastButton=$ago")
                    FaderPlayer.pause()
                }
                override fun onSkipToNext() = FaderPlayer.next()
                override fun onSkipToPrevious() = FaderPlayer.previous()
                override fun onSeekTo(pos: Long) = FaderPlayer.seekTo(pos.toInt())
                override fun onStop() = FaderPlayer.stop()

                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        CUSTOM_FAVORITE, CUSTOM_UNFAVORITE -> toggleFavorite()
                        CUSTOM_CLOSE -> {
                            FaderPlayer.stop()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                        else -> super.onCustomAction(action, extras)
                    }
                }

                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    lastMediaButtonAt = System.currentTimeMillis()
                    FaderPlayer.note(
                        "media button ${event?.keyCode} action=${event?.action} " +
                            "source=${event?.source}",
                    )
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }

                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    // The car session handles voice search; this covers the phone.
                    if (FaderPlayer.carMode) return
                    val text = query?.trim().orEmpty()
                    if (text.length < 2) return
                    Thread {
                        val hits = runCatching { runBlocking { Search.query(text) } }
                            .getOrDefault(emptyList())
                        if (hits.isNotEmpty()) {
                            FaderPlayer.playSearchResults(hits)
                        }
                    }.start()
                }
            })
            isActive = true
            runCatching {
                setMediaButtonReceiver(
                    PendingIntent.getBroadcast(
                        this@PlaybackService,
                        1,
                        Intent(this@PlaybackService, MediaButtonReceiver::class.java)
                            .setAction(Intent.ACTION_MEDIA_BUTTON),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
        }

        FaderPlayer.addListener(listener)
        startForeground(NOTIFICATION_ID, build())
        publish()
    }

    private fun publishSafely() {
        runCatching { publish() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                FaderPlayer.note("intent: play/pause")
                FaderPlayer.toggle()
            }
            ACTION_NEXT -> FaderPlayer.next()
            ACTION_PREVIOUS -> FaderPlayer.previous()
            ACTION_STOP -> {
                FaderPlayer.note("intent: stop")
                FaderPlayer.stop()
            }
            ACTION_FAVORITE -> toggleFavorite()
            ACTION_RESUME_LAST -> resumeLastSession()
            ACTION_CLOSE -> {
                FaderPlayer.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        publishSafely()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        FaderPlayer.note("service destroyed")
        FaderPlayer.removeListener(listener)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Closing the app always stops playback and the service, so it cannot
        // linger in the background and come back on its own.
        FaderPlayer.stop()
        stopSelf()
    }

    private fun publish() {
        val title = FaderPlayer.title
        val artwork = artwork()
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, FaderPlayer.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, FaderPlayer.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, FaderPlayer.durationMs.toLong())
                .apply { if (artwork != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork) }
                .build(),
        )
        session.setPlaybackState(buildPlaybackState().build())
        runCatching { notifications.notify(NOTIFICATION_ID, build(artwork)) }
    }

    private fun build(art: Bitmap? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val subtitle = listOf(FaderPlayer.artist, FaderPlayer.album)
            .filter { !it.isNullOrBlank() }
            .joinToString(" · ")

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.fader.mobil.R.drawable.ic_stat_fader)
            .setContentTitle(FaderPlayer.title ?: "Fader")
            .setContentText(subtitle.ifBlank { "Resuming…" })
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3),
            )
            .addAction(
                action(
                    ACTION_FAVORITE,
                    if (isFavorite(FaderPlayer.currentUrl)) "Remove from favorites" else "Add to favorites",
                    if (isFavorite(FaderPlayer.currentUrl)) {
                        com.fader.mobil.R.drawable.ic_favorite_red
                    } else {
                        com.fader.mobil.R.drawable.ic_favorite
                    },
                ),
            )
            .addAction(action(ACTION_PREVIOUS, "Previous", android.R.drawable.ic_media_previous))
            .addAction(
                if (FaderPlayer.isPlaying) {
                    action(ACTION_PLAY_PAUSE, "Pause", android.R.drawable.ic_media_pause)
                } else {
                    action(ACTION_PLAY_PAUSE, "Play", android.R.drawable.ic_media_play)
                },
            )
            .addAction(action(ACTION_NEXT, "Next", android.R.drawable.ic_media_next))
            .addAction(
                action(
                    ACTION_CLOSE,
                    "Close player",
                    com.fader.mobil.R.drawable.ic_close,
                ),
            )

        art?.let { builder.setLargeIcon(it) }

        // AIMP-style custom layout: five buttons on the lock screen.
        val rv = android.widget.RemoteViews(packageName, com.fader.mobil.R.layout.fader_notification)
        rv.setTextViewText(com.fader.mobil.R.id.fader_title, FaderPlayer.title ?: "Fader")
        rv.setTextViewText(com.fader.mobil.R.id.fader_artist, subtitle)
        art?.let { rv.setImageViewBitmap(com.fader.mobil.R.id.fader_cover, it) }
        rv.setImageViewResource(
            com.fader.mobil.R.id.fader_play,
            if (FaderPlayer.isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play,
        )
        rv.setOnClickPendingIntent(
            com.fader.mobil.R.id.fader_like,
            pending(ACTION_FAVORITE),
        )
        rv.setOnClickPendingIntent(
            com.fader.mobil.R.id.fader_prev,
            pending(ACTION_PREVIOUS),
        )
        rv.setOnClickPendingIntent(
            com.fader.mobil.R.id.fader_play,
            pending(ACTION_PLAY_PAUSE),
        )
        rv.setOnClickPendingIntent(
            com.fader.mobil.R.id.fader_next,
            pending(ACTION_NEXT),
        )
        rv.setOnClickPendingIntent(
            com.fader.mobil.R.id.fader_close,
            pending(ACTION_CLOSE),
        )
        // Only the expanded notification uses the custom five-button layout.
        runCatching { builder.setCustomBigContentView(rv) }
        return builder.build()
    }

    private fun resumeLastSession() {
        Thread {
            runCatching {
                RemoteLibrary.init(applicationContext)
                val saved = WebDavCredentials.load(this)
                if (saved != null && !RemoteLibrary.isConnected) {
                    kotlinx.coroutines.runBlocking {
                        RemoteLibrary.connect(saved.url, saved.user, saved.password)
                    }
                }
                val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    android.Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                }
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    permission,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted && !LocalLibrary.isReady) LocalLibrary.load(this)
            }
            val session = PlaybackSessionStore.load(this) ?: return@Thread
            FaderPlayer.note("media button: session with ${session.tracks.size} tracks")
            // ExoPlayer must be touched on the main thread only.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val restored = runCatching {
                    FaderPlayer.restoreSession(session.tracks, session.index, session.positionMs)
                }.getOrDefault(false)
                FaderPlayer.note("media button: restored=$restored")
                if (restored) {
                    FaderPlayer.play()
                    return@post
                }
                // Fallback: at least bring the single last song back.
                val saved = com.fader.mobil.data.UiState.playback(this)
                if (saved != null) {
                    val single = runCatching {
                        FaderPlayer.restoreLastTrack(
                            saved.trackUrl, saved.title, saved.artist, "", saved.positionMs,
                        )
                    }.getOrDefault(false)
                    if (single) FaderPlayer.resumeRestored()
                }
            }
        }.start()
    }

    private fun isFavorite(url: String?): Boolean =
        url != null && FavoritesStore.isFavorite(this, url)

    /** Adds or removes the track that is playing, straight from the lock screen. */
    private fun toggleFavorite() {
        val url = FaderPlayer.currentUrl ?: return
        val remote = url.startsWith("http://") || url.startsWith("https://")
        val folderUrl = if (remote) {
            url.substringBeforeLast('/', "") + "/"
        } else {
            url.substringBeforeLast('/', "")
        }
        val favorite = Favorite(
            url = url,
            title = FaderPlayer.title.orEmpty(),
            artist = FaderPlayer.artist.orEmpty(),
            album = FaderPlayer.album.orEmpty(),
            folderUrl = folderUrl,
            folderName = folderUrl.trimEnd('/').substringAfterLast('/'),
            remote = remote,
        )
        FavoritesStore.toggle(this, favorite)
        // Refresh the notification and the heart icon right away.
        publish()
    }

    /** Current track cover, from the in-memory cache, the remote cache or MediaStore. */
    private fun artwork(): Bitmap? = runCatching {
        val art = FaderPlayer.currentArt() ?: return@runCatching null
        com.fader.mobil.ui.components.ArtworkLoader.cached(art.key)?.let { return@runCatching it }

        val uri = art.uri
        when {
            uri.startsWith("remote:") -> {
                val path = com.fader.mobil.core.RemoteLibrary
                    .cachedCoverPath(uri.removePrefix("remote:")) ?: return@runCatching null
                BitmapFactory.decodeFile(path)
            }

            uri.startsWith("file:") -> BitmapFactory.decodeFile(uri.removePrefix("file:"))

            else -> contentResolver.loadThumbnail(android.net.Uri.parse(uri), Size(512, 512), null)
        }
    }.getOrNull()

    /** Position travels with the state so the system can draw its seek bar. */
    private fun buildPlaybackState(): PlaybackState.Builder {
        val playing = FaderPlayer.isPlaying
        val favoriteNow = isFavorite(FaderPlayer.currentUrl)
        val favoriteAction = PlaybackState.CustomAction.Builder(
            if (favoriteNow) CUSTOM_UNFAVORITE else CUSTOM_FAVORITE,
            if (favoriteNow) "Remove from favorites" else "Add to favorites",
            if (favoriteNow) {
                com.fader.mobil.R.drawable.ic_favorite_red
            } else {
                com.fader.mobil.R.drawable.ic_favorite_outline
            },
        ).build()
        return PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_STOP,
            )
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                FaderPlayer.positionMs.toLong(),
                if (playing) 1f else 0f,
            )
            .addCustomAction(favoriteAction)
            .addCustomAction(
                PlaybackState.CustomAction.Builder(
                    CUSTOM_CLOSE,
                    "Close",
                    com.fader.mobil.R.drawable.ic_close,
                ).build(),
            )
    }

    private fun pending(actionName: String): android.app.PendingIntent =
        android.app.PendingIntent.getService(
            this,
            actionName.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(actionName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun action(actionName: String, label: String, icon: Int): Notification.Action {
        val intent = Intent(this, PlaybackService::class.java).setAction(actionName)
        val pending = PendingIntent.getService(
            this, actionName.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), label, pending)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = "Playback controls"
        }
        notifications.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.fader.mobil.PLAY_PAUSE"
        const val ACTION_NEXT = "com.fader.mobil.NEXT"
        const val ACTION_PREVIOUS = "com.fader.mobil.PREVIOUS"
        const val ACTION_FAVORITE = "com.fader.mobil.FAVORITE"
        const val ACTION_RESUME_LAST = "com.fader.mobil.RESUME_LAST"
        const val ACTION_CLOSE = "com.fader.mobil.CLOSE"
        const val CUSTOM_FAVORITE = "com.fader.mobil.custom.FAVORITE"
        const val CUSTOM_UNFAVORITE = "com.fader.mobil.custom.UNFAVORITE"
        const val CUSTOM_CLOSE = "com.fader.mobil.custom.CLOSE"
        const val ACTION_STOP = "com.fader.mobil.STOP"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
    }
}
