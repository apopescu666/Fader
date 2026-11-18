package com.fader.mobil.playback

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.fader.mobil.core.FaderPlayer
import com.fader.mobil.core.LocalLibrary
import com.fader.mobil.core.RemoteLibrary
import com.fader.mobil.core.Search
import com.fader.mobil.core.SearchHit
import com.fader.mobil.core.SessionItems
import com.fader.mobil.core.WebDavCredentials
import com.fader.mobil.data.Favorite
import com.fader.mobil.data.FavoritesStore
import com.fader.mobil.data.PlaylistStore
import com.fader.mobil.data.PlaybackSessionStore
import com.fader.mobil.data.QueueStore
import com.fader.mobil.data.UiState
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Media library service for Android Auto: it exposes the sources, expands a
 * tapped song to its whole list, and plays through the app engine.
 */
class FaderMediaService : MediaLibraryService() {

    private var librarySession: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var carConnected = false
    private var searchHits: List<SearchHit> = emptyList()

    override fun onCreate() {
        super.onCreate()
        FaderPlayer.init(this)
        RemoteLibrary.init(this)
        FaderPlayer.setShuffle(com.fader.mobil.data.SettingsStore.shuffle(this))
        FaderPlayer.applyCrossfade(com.fader.mobil.data.SettingsStore.fade(this))
        val player = FaderPlayer.sessionPlayer ?: return
        librarySession = MediaLibrarySession.Builder(this, player, callback)
            .setBitmapLoader(artLoader)
            .setCustomLayout(listOf(favoriteButton(), shuffleButton(), repeatButton()))
            .build()
        lastPlayer = player
        FaderPlayer.addListener(engineWatcher)
        FaderPlayer.note("car service created")
        scope.launch { bootstrap() }
    }

    /** Makes both sources usable when the car process starts from scratch. */
    private suspend fun bootstrap() {
        ensureRemote()
        loadLocalIfAllowed()
        librarySession?.notifyChildrenChanged("root", 0, null)
    }

    private suspend fun ensureRemote() {
        if (RemoteLibrary.isConnected) return
        val saved = WebDavCredentials.load(this) ?: return
        runCatching { RemoteLibrary.connect(saved.url, saved.user, saved.password) }
    }

    private suspend fun loadLocalIfAllowed() {
        if (LocalLibrary.isReady || !canReadAudio()) return
        withContext(Dispatchers.IO) { runCatching { LocalLibrary.load(this@FaderMediaService) } }
    }

    /** Saves the song that is playing to Favorites; it stays there after the car. */
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
        refreshCustomLayout()
    }

    private fun canReadAudio(): Boolean {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private var lastLayoutKey: String? = null

    private fun isCurrentFavorite(): Boolean {
        val url = FaderPlayer.currentUrl ?: return false
        return runCatching {
            FavoritesStore.all(this).any { it.url == url }
        }.getOrDefault(false)
    }

    private fun refreshCustomLayout() {
        val session = librarySession ?: return
        val key = listOf(
            FaderPlayer.currentUrl,
            isCurrentFavorite(),
            FaderPlayer.shuffle,
            FaderPlayer.repeatMode,
        ).joinToString("|")
        if (key == lastLayoutKey) return
        lastLayoutKey = key
        runCatching { session.setCustomLayout(listOf(favoriteButton(), shuffleButton(), repeatButton())) }
    }

    private fun favoriteButton(): CommandButton {
        val favorite = isCurrentFavorite()
        return CommandButton.Builder()
            .setDisplayName(if (favorite) "Favorite: remove" else "Favorite: add")
            .setIconResId(
                if (favorite) com.fader.mobil.R.drawable.ic_favorite_red
                else com.fader.mobil.R.drawable.ic_favorite_outline,
            )
            .setSessionCommand(SessionCommand(CMD_FAVORITE, Bundle.EMPTY))
            .build()
    }

    private fun repeatButton(): CommandButton {
        val mode = FaderPlayer.repeatMode
        // Standard Media3 icons: Android Auto renders them with a clear
        // active/inactive shape, unlike a custom drawable it may tint.
        val icon = when (mode) {
            2 -> CommandButton.ICON_REPEAT_ONE
            1 -> CommandButton.ICON_REPEAT_ALL
            else -> CommandButton.ICON_REPEAT_OFF
        }
        val label = when (mode) {
            2 -> "Repeat: one"
            1 -> "Repeat: all"
            else -> "Repeat: off"
        }
        return CommandButton.Builder()
            .setDisplayName(label)
            .setIconResId(icon)
            .setSessionCommand(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
            .build()
    }

    private fun shuffleButton(): CommandButton = CommandButton.Builder()
        .setDisplayName(if (FaderPlayer.shuffle) "Shuffle: on" else "Shuffle: off")
        .setIconResId(
            if (FaderPlayer.shuffle) {
                CommandButton.ICON_SHUFFLE_ON
            } else {
                CommandButton.ICON_SHUFFLE_OFF
            },
        )
        .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
        .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        FaderPlayer.note("car session requested by ${controllerInfo.packageName}")
        return librarySession
    }

    /** The phone notification is owned by PlaybackService, so skip the second one. */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) = Unit

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        FaderPlayer.note("task removed: stopping")
        FaderPlayer.stop()
        stopSelf()
    }

    override fun onDestroy() {
        FaderPlayer.note("car service destroyed")
        FaderPlayer.removeListener(engineWatcher)
        librarySession?.release()
        librarySession = null
        super.onDestroy()
    }

    private var lastPlayer: androidx.media3.common.Player? = null

    /** Crossfade swaps the underlying engine; the car session follows it. */
    private val engineWatcher = FaderPlayer.Listener {
        refreshCustomLayout()
        val session = librarySession ?: return@Listener
        val player = FaderPlayer.sessionPlayer ?: return@Listener
        if (player !== lastPlayer) {
            lastPlayer = player
            runCatching { session.setPlayer(player) }
        }
    }

    private val artPool = java.util.concurrent.Executors.newFixedThreadPool(2)

    /** Covers already decoded for the car: scrolling lists no longer re-decodes. */
    private val artCache = object : android.util.LruCache<String, android.graphics.Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.allocationByteCount
    }

    /** Loads covers for the car: per-track embedded art, folder art as fallback. */
    private val artLoader = object : androidx.media3.common.util.BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

        override fun decodeBitmap(data: ByteArray): ListenableFuture<android.graphics.Bitmap> {
            val future = SettableFuture.create<android.graphics.Bitmap>()
            artPool.execute {
                val bitmap = runCatching {
                    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                }.getOrNull()
                if (bitmap != null) future.set(bitmap)
                else future.setException(java.io.IOException("Artwork decode failed"))
            }
            return future
        }

        override fun loadBitmap(uri: android.net.Uri): ListenableFuture<android.graphics.Bitmap> {
            artCache.get(uri.toString())?.let { return Futures.immediateFuture(it) }
            val future = SettableFuture.create<android.graphics.Bitmap>()
            artPool.execute {
                val bitmap = runCatching { artworkFor(uri) }.getOrNull()
                if (bitmap != null) {
                    artCache.put(uri.toString(), bitmap)
                    future.set(bitmap)
                } else {
                    future.setException(java.io.IOException("No artwork for $uri"))
                }
            }
            return future
        }
    }

    private fun artworkFor(uri: android.net.Uri): android.graphics.Bitmap? {
        val value = uri.toString()
        return when {
            value.startsWith("remote:") -> kotlinx.coroutines.runBlocking {
                RemoteLibrary.coverBitmapFor(value.removePrefix("remote:"))
            }
            uri.scheme == "content" -> contentResolver.loadThumbnail(
                uri,
                android.util.Size(512, 512),
                null,
            )
            uri.scheme == "file" -> uri.path?.let { RemoteLibrary.decodeCoverFile(it) }
            else -> null
        }
    }

    private val callback = object : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val packageName = controller.packageName.orEmpty()
            FaderPlayer.note("car controller: $packageName")
            if (packageName.contains("gearhead") || packageName.contains("androidauto")) {
                carConnected = true
                FaderPlayer.enterCarMode()
            }
            val result = super.onConnect(session, controller)
            val commands = result.availableSessionCommands
                .buildUpon()
                .add(SessionCommand(CMD_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(commands, result.availablePlayerCommands)
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            FaderPlayer.note("car disconnected: ${controller.packageName}")
            if (carConnected) {
                carConnected = false
                FaderPlayer.exitCarMode()
            }
            super.onDisconnected(session, controller)
        }

        /**
         * Android Auto calls this when it wants to resume playback (for example
         * "start music automatically" on connection).
         */
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            FaderPlayer.resetForCarPlayback()
            val player = FaderPlayer.sessionPlayer
            if (player != null && player.mediaItemCount > 0) {
                val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                val index = player.currentMediaItemIndex.coerceIn(0, items.lastIndex)
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        items,
                        index,
                        player.currentPosition.coerceAtLeast(0),
                    ),
                )
            }
            // Prefer the full saved queue so next/previous work after a restart.
            val session = PlaybackSessionStore.load(this@FaderMediaService)
            if (session != null) {
                val items = session.tracks.mapNotNull { track ->
                    FaderPlayer.mediaItemForSession(track.url, track.title, track.artist, "", null)
                }
                if (items.isNotEmpty()) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            items,
                            session.index.coerceIn(0, items.lastIndex),
                            session.positionMs.toLong().coerceAtLeast(0),
                        ),
                    )
                }
            }
            val saved = UiState.playback(this@FaderMediaService)
            val url = saved?.trackUrl
            if (url != null) {
                val item = FaderPlayer.mediaItemForSession(url, null)
                if (item != null) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            listOf(item),
                            0,
                            saved.positionMs.toLong().coerceAtLeast(0),
                        ),
                    )
                }
            }
            return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                val result = runCatching { children(parentId) }
                result.onSuccess { list ->
                    val paged = if (pageSize > 0) {
                        list.drop(page * pageSize).take(pageSize)
                    } else {
                        list
                    }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(paged), params))
                }.onFailure {
                    future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = runCatching { itemForId(mediaId) }.getOrNull()
                ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        /**
         * A tapped song is expanded to its whole list, so playback continues
         * with the folder, playlist, favorites or the queue it came from.
         */
        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val tapped = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
            val container = tapped?.mediaMetadata?.extras?.getString(EXTRA_CONTAINER)
                ?: tapped?.mediaId?.removePrefix("track:")?.let { folderContainerFor(it) }
            if (mediaItems.size == 1 && container != null) {
                val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                scope.launch {
                    runCatching {
                        // Shuffle is applied inside the engine, so the queue it
                        // reports and the order it plays are always the same.
                        val tracks = containerTracks(container)
                        val resolved = tracks.mapNotNull { track ->
                            FaderPlayer.mediaItemForSession(
                                track.url,
                                track.title,
                                track.artist,
                                track.album,
                                track.artwork,
                                container,
                            )?.let { track.url to it }
                        }
                        val items = resolved.map { it.second }
                        if (items.isEmpty()) {
                            future.setException(java.io.IOException("Nothing to play"))
                            return@launch
                        }
                        val tappedUrl = tapped?.mediaId?.removePrefix("track:").orEmpty()
                        val index = resolved.indexOfFirst { it.first == tappedUrl }
                            .coerceIn(0, items.lastIndex)
                        FaderPlayer.prepareExternalQueue()
                        FaderPlayer.resetForCarPlayback()
                        FaderPlayer.ensureBackgroundService()
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(
                                items,
                                index,
                                startPositionMs.coerceAtLeast(0),
                            ),
                        )
                    }.onFailure { future.setException(it) }
                }
                return future
            }

            val resolved = mediaItems.mapNotNull { item ->
                runCatching { resolve(item) }.getOrNull()
            }
            FaderPlayer.prepareExternalQueue()
            FaderPlayer.resetForCarPlayback()
            FaderPlayer.ensureBackgroundService()
            val index = startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    resolved,
                    index,
                    startPositionMs.coerceAtLeast(0),
                ),
            )
        }

        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                runCatching {
                    val needsRemote = mediaItems.any {
                        val url = it.mediaId.removePrefix("track:")
                        url.startsWith("http")
                    }
                    if (needsRemote) ensureRemote()
                    loadLocalIfAllowed()
                    mediaItems.mapNotNull { item ->
                        runCatching { resolve(item) }.getOrNull()
                    }.toMutableList()
                }.onSuccess { resolved ->
                    if (resolved.isNotEmpty()) FaderPlayer.ensureBackgroundService()
                    future.set(resolved)
                }.onFailure { future.setException(it) }
            }
            return future
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_FAVORITE) {
                toggleFavorite()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_REPEAT) {
                FaderPlayer.cycleRepeat()
                refreshCustomLayout()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_SHUFFLE) {
                val enabled = !FaderPlayer.shuffle
                FaderPlayer.setShuffle(enabled)
                com.fader.mobil.data.SettingsStore.setShuffle(this@FaderMediaService, enabled)
                refreshCustomLayout()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val hits = runCatching { Search.query(query) }.getOrDefault(emptyList())
                searchHits = hits
                session.notifySearchResultChanged(browser, query, hits.size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val list = searchHits.map { hitItem(it) }
            val paged = if (pageSize > 0) list.drop(page * pageSize).take(pageSize) else list
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(paged), params),
            )
        }
    }

    // ---- tree ----------------------------------------------------------

    private fun root(): MediaItem = MediaItem.Builder()
        .setMediaId("root")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Fader")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    private fun browse(id: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()

    private suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == "root" -> {
            ensureRemote()
            buildList {
                // Short titles: Android Auto puts them in narrow pills.
                add(browse("local", "Phone"))
                if (RemoteLibrary.isConnected) {
                    add(browse("remote", "NAS", RemoteLibrary.hostLabel))
                }
                add(browse("favorites", "Favorites"))
                add(browse("playlists", "Playlists"))
                add(browse("queue", "Queue"))
            }
        }

        parentId == "local" -> {
            loadLocalIfAllowed()
            if (LocalLibrary.isReady) {
                folderChildren(LocalLibrary.musicRoot().url, "Phone", false)
            } else {
                emptyList()
            }
        }

        parentId == "remote" -> {
            ensureRemote()
            val rootUrl = RemoteLibrary.rootUrl
            if (rootUrl == null) emptyList()
            else folderChildren(rootUrl, RemoteLibrary.hostLabel ?: "NAS", true)
        }

        parentId == "favorites" -> withContext(Dispatchers.IO) {
            FavoritesStore.all(this@FaderMediaService).map { favorite ->
                trackItem(
                    url = favorite.url,
                    title = favorite.title,
                    artist = favorite.artist,
                    album = favorite.album,
                    remote = favorite.remote,
                    container = CONTAINER_FAVORITES,
                )
            }
        }

        parentId == "queue" -> withContext(Dispatchers.IO) {
            QueueStore.all(this@FaderMediaService).map { track ->
                trackItem(
                    url = track.url,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    remote = track.remote,
                    container = CONTAINER_QUEUE,
                )
            }
        }

        parentId == "playlists" -> withContext(Dispatchers.IO) {
            PlaylistStore.all(this@FaderMediaService).map { playlist ->
                browse("playlist:${playlist.id}", playlist.name, "${playlist.tracks.size} songs")
            }
        }

        parentId.startsWith("playlist:") -> withContext(Dispatchers.IO) {
            val id = parentId.removePrefix("playlist:")
            val playlist = PlaylistStore.all(this@FaderMediaService).firstOrNull { it.id == id }
            playlist?.tracks?.map { track ->
                trackItem(
                    url = track.url,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    remote = track.remote,
                    container = "playlist|$id",
                )
            } ?: emptyList()
        }

        parentId.startsWith("folder:") -> {
            val encoded = parentId.removePrefix("folder:")
            val remote = encoded.startsWith("http")
            val url = encoded.substringBefore("|")
            val name = com.fader.mobil.core.RemoteLibrary.decodeName(
                encoded.substringAfter("|", url.trimEnd('/').substringAfterLast('/')),
            )
            folderChildren(url, name, remote)
        }

        else -> emptyList()
    }

    /**
     * Folder contents for the car: memory cache, then the local database, then
     * the network. Only the first visit pays the PROPFIND price.
     */
    private suspend fun folderListing(url: String, name: String): com.fader.mobil.core.Listing? =
        withContext(Dispatchers.IO) {
            com.fader.mobil.core.ListingCache.get(url)?.let { return@withContext it }
            val stored = com.fader.mobil.core.NasStore.loadListing(this@FaderMediaService, url)
            if (stored != null) {
                com.fader.mobil.core.ListingCache.put(url, stored)
                scope.launch {
                    runCatching { RemoteLibrary.listing(url, name) }.getOrNull()?.let { fresh ->
                        com.fader.mobil.core.ListingCache.put(url, fresh)
                        withContext(Dispatchers.IO) {
                            com.fader.mobil.core.NasStore.saveListing(this@FaderMediaService, fresh)
                        }
                    }
                }
                return@withContext stored
            }
            val fresh = runCatching { RemoteLibrary.listing(url, name) }.getOrNull()
            if (fresh != null) {
                com.fader.mobil.core.ListingCache.put(url, fresh)
                withContext(Dispatchers.IO) {
                    com.fader.mobil.core.NasStore.saveListing(this@FaderMediaService, fresh)
                }
            }
            fresh
        }

    private suspend fun folderChildren(url: String, name: String, remote: Boolean): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (remote) ensureRemote()
            if (!remote) loadLocalIfAllowed()
            val listing = if (remote) {
                folderListing(url, name) ?: return@withContext emptyList()
            } else {
                runCatching { LocalLibrary.listing(url, name) }.getOrNull()
                    ?: return@withContext emptyList()
            }

            val container = "folder|${if (remote) 1 else 0}|$url|$name"

            buildList {
                listing.folders.forEach { folder ->
                    add(browse("folder:${folder.url}|${folder.name}", folder.name))
                }
                listing.tracks.forEach { track ->
                    add(
                        trackItem(
                            url = track.url,
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            remote = remote,
                            artUri = if (remote) null else track.artUri,
                            container = container,
                            durationMs = track.durationMs,
                        ),
                    )
                }
            }
        }

    private fun trackItem(
        url: String,
        title: String,
        artist: String,
        album: String,
        remote: Boolean,
        artUri: String? = null,
        container: String? = null,
        durationMs: Long = 0,
    ): MediaItem {
        // Android Auto can read this content:// URI from our provider.
        val artwork = com.fader.mobil.data.CoverProvider.uriFor(url).toString()
        val extras = Bundle().apply {
            if (container != null) putString(EXTRA_CONTAINER, container)
            if (durationMs > 0) putLong(EXTRA_DURATION, durationMs)
        }
        return MediaItem.Builder()
            .setMediaId("track:$url")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(
                        title.ifBlank {
                            com.fader.mobil.core.RemoteLibrary.decodeName(
                                url.substringAfterLast('/'),
                            )
                        },
                    )
                    .setArtist(artist.ifBlank { "Unknown artist" })
                    .setAlbumTitle(album)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setArtworkUri(artwork?.let { android.net.Uri.parse(it) })
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun itemForId(mediaId: String): MediaItem? = when {
        mediaId.startsWith("folder:") -> {
            val encoded = mediaId.removePrefix("folder:")
            val url = encoded.substringBefore("|")
            val name = com.fader.mobil.core.RemoteLibrary.decodeName(
                encoded.substringAfter("|", url.trimEnd('/').substringAfterLast('/')),
            )
            browse(mediaId, name)
        }
        else -> null
    }

    /** Turns a browsed entry into something the engine can actually open. */
    private fun resolve(mediaId: String): MediaItem? {
        val stub = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(MediaMetadata.Builder().build())
            .build()
        return resolve(stub)
    }

    /** Same, but keeps the container extra so a later setMediaItems expands. */
    private fun resolve(item: MediaItem): MediaItem? {
        val mediaId = item.mediaId
        if (!mediaId.startsWith("track:")) return itemForId(mediaId)
        val url = mediaId.removePrefix("track:")
        val art = if (url.startsWith("http")) null else LocalLibrary.art(url)?.uri
        val container = SessionItems.containerOf(item) ?: folderContainerFor(url)
        return FaderPlayer.mediaItemForSession(
            url,
            item.mediaMetadata.title?.toString().orEmpty(),
            item.mediaMetadata.artist?.toString().orEmpty(),
            item.mediaMetadata.albumTitle?.toString().orEmpty(),
            art,
            container,
        )
    }

    /** Fallback when the controller dropped the extras: use the file's folder. */
    private fun folderContainerFor(url: String): String? {
        val trimmed = url.trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "")
        if (parent.isEmpty()) return null
        val remote = url.startsWith("http")
        val name = parent.trimEnd('/').substringAfterLast('/')
        return "folder|${if (remote) 1 else 0}|$parent/|$name"
    }

    private fun hitItem(hit: SearchHit): MediaItem = trackItem(
        url = hit.url,
        title = hit.name.substringBeforeLast('.'),
        artist = hit.folderName,
        album = "",
        remote = hit.remote,
        container = CONTAINER_SEARCH,
    )

    // ---- containers ----------------------------------------------------

    private data class CarTrack(
        val url: String,
        val title: String,
        val artist: String,
        val album: String,
        val remote: Boolean,
        val artwork: String?,
    )

    private suspend fun containerTracks(container: String): List<CarTrack> =
        withContext(Dispatchers.IO) {
            loadLocalIfAllowed()
            when {
                container == CONTAINER_FAVORITES -> FavoritesStore
                    .all(this@FaderMediaService)
                    .map { favorite ->
                        CarTrack(
                            favorite.url,
                            favorite.title,
                            favorite.artist,
                            favorite.album,
                            favorite.remote,
                            artworkFor(favorite.url, favorite.remote),
                        )
                    }

                container == CONTAINER_QUEUE -> QueueStore
                    .all(this@FaderMediaService)
                    .map { track ->
                        CarTrack(
                            track.url,
                            track.title,
                            track.artist,
                            track.album,
                            track.remote,
                            artworkFor(track.url, track.remote),
                        )
                    }

                container == CONTAINER_SEARCH -> searchHits.map { hit ->
                    CarTrack(
                        hit.url,
                        hit.name.substringBeforeLast('.'),
                        hit.folderName,
                        "",
                        hit.remote,
                        artworkFor(hit.url, hit.remote),
                    )
                }

                container.startsWith("playlist|") -> {
                    val id = container.removePrefix("playlist|")
                    val playlist = PlaylistStore.all(this@FaderMediaService)
                        .firstOrNull { it.id == id }
                    playlist?.tracks?.map { track ->
                        CarTrack(
                            track.url,
                            track.title,
                            track.artist,
                            track.album,
                            track.remote,
                            artworkFor(track.url, track.remote),
                        )
                    } ?: emptyList()
                }

                container.startsWith("folder|") -> {
                    val parts = container.split("|", limit = 4)
                    if (parts.size < 4) return@withContext emptyList()
                    val remote = parts[1] == "1"
                    val url = parts[2]
                    val name = parts[3]
                    if (remote) ensureRemote()
                    if (!remote) loadLocalIfAllowed()
                    val listing = if (remote) {
                        folderListing(url, name) ?: return@withContext emptyList()
                    } else {
                        runCatching { LocalLibrary.listing(url, name) }.getOrNull()
                            ?: return@withContext emptyList()
                    }
                    listing.tracks.map { track ->
                        CarTrack(
                            track.url,
                            track.title,
                            track.artist,
                            track.album,
                            remote,
                            if (remote) "remote:${track.url}" else track.artUri,
                        )
                    }
                }

                else -> emptyList()
            }
        }

    private fun artworkFor(url: String, remote: Boolean): String? =
        if (remote) "remote:$url" else LocalLibrary.art(url)?.uri

    private companion object {
        const val EXTRA_CONTAINER = "fader.container"
        const val EXTRA_DURATION = "fader.durationMs"
        const val CMD_SHUFFLE = "fader.shuffle"
        const val CMD_REPEAT = "fader.repeat"
        const val CMD_FAVORITE = "fader.favorite"
        const val CONTAINER_FAVORITES = "favorites"
        const val CONTAINER_QUEUE = "queue"
        const val CONTAINER_SEARCH = "search"
    }
}
