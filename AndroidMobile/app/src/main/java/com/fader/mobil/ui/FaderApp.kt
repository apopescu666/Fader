package com.fader.mobil.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fader.mobil.core.FaderPlayer
import com.fader.mobil.core.TrackQueue
import com.fader.mobil.core.Listing
import com.fader.mobil.core.LocalLibrary
import com.fader.mobil.core.RemoteLibrary
import com.fader.mobil.core.SavedShare
import com.fader.mobil.core.Search
import com.fader.mobil.core.SearchHit
import com.fader.mobil.core.TrackEntry
import com.fader.mobil.core.WebDavCredentials
import com.fader.mobil.data.Favorite
import com.fader.mobil.data.FavoritesStore
import com.fader.mobil.data.LastFolder
import com.fader.mobil.data.Playlist
import com.fader.mobil.data.PlaylistStore
import com.fader.mobil.data.PlaybackSession
import com.fader.mobil.data.PlaybackSessionStore
import com.fader.mobil.data.QueueStore
import com.fader.mobil.data.SessionTrack
import com.fader.mobil.data.SavedPlayback
import com.fader.mobil.data.Source
import com.fader.mobil.data.SourceKind
import com.fader.mobil.data.Track
import com.fader.mobil.data.UiState
import com.fader.mobil.ui.components.FaderTabBar
import com.fader.mobil.ui.components.MiniPlayer
import com.fader.mobil.ui.components.Tab
import com.fader.mobil.ui.screens.AddSourceScreen
import com.fader.mobil.ui.screens.BrowseScreen
import com.fader.mobil.ui.screens.LibraryScreen
import com.fader.mobil.ui.screens.PlayerScreen
import com.fader.mobil.ui.screens.PlaylistsScreen
import com.fader.mobil.ui.screens.QueueScreen
import com.fader.mobil.ui.screens.SearchScreen
import com.fader.mobil.ui.screens.SettingsScreen
import com.fader.mobil.ui.theme.Accent
import com.fader.mobil.ui.theme.Surface1
import com.fader.mobil.ui.theme.TextPrimary
import com.fader.mobil.ui.theme.Bg
import com.fader.mobil.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class NavFolder(val url: String, val name: String, val remote: Boolean)

private const val FAVORITES_URL = "favorites://"
private const val PLAYLIST_PREFIX = "playlist://"
private const val PLAYLISTS_URL = "playlists://"

private fun favoriteFrom(listing: Listing, entry: TrackEntry): Favorite = Favorite(
    url = entry.url,
    title = entry.title,
    artist = entry.artist,
    album = entry.album,
    folderUrl = listing.url,
    folderName = listing.name,
    remote = listing.remote,
)

private fun playlistListing(playlist: Playlist): Listing {
    val tracks = playlist.tracks.map { track ->
        TrackEntry(
            name = track.url.substringAfterLast('/'),
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = 0,
            url = track.url,
            artKey = "track:" + track.url,
            artUri = if (track.remote) RemoteLibrary.art(track.url).uri else LocalLibrary.art(track.url)?.uri,
        )
    }
    return Listing(PLAYLIST_PREFIX + playlist.id, playlist.name, emptyList(), tracks, remote = false)
}

private fun favoritesListing(favorites: List<Favorite>): Listing {
    val tracks = favorites.map { favorite ->
        TrackEntry(
            name = favorite.url.substringAfterLast('/'),
            title = favorite.title,
            artist = favorite.artist,
            album = favorite.album,
            durationMs = 0,
            url = favorite.url,
            artKey = "track:" + favorite.url,
            artUri = if (favorite.remote) {
                RemoteLibrary.art(favorite.url).uri
            } else {
                LocalLibrary.art(favorite.url)?.uri
            },
        )
    }
    return Listing(FAVORITES_URL, "Favorites", emptyList(), tracks, remote = false)
}

@Composable
fun FaderApp() {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val playback = rememberPlaybackState()

    var tab by rememberSaveable { mutableStateOf(Tab.Library) }
    var stack by remember { mutableStateOf<List<NavFolder>>(emptyList()) }
    var playingListing by remember { mutableStateOf<Listing?>(null) }
    var playingPath by remember {
        mutableStateOf(
            UiState.source(context)?.let { saved ->
                listOf(NavFolder(saved.url, saved.name, saved.remote))
            } ?: emptyList(),
        )
    }
    var expanded by rememberSaveable { mutableStateOf(false) }

    var showAddSource by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var remoteConnected by remember { mutableStateOf(RemoteLibrary.isConnected) }
    var savedShare by remember { mutableStateOf<SavedShare?>(null) }
    var favorites by remember { mutableStateOf(FavoritesStore.all(context)) }
    var playlists by remember { mutableStateOf(PlaylistStore.all(context)) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var addTracks by remember { mutableStateOf<List<Favorite>?>(null) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var pickerExclude by remember { mutableStateOf<String?>(null) }
    var moveFromFavorites by remember { mutableStateOf(false) }
    var managePlaylist by remember { mutableStateOf<Playlist?>(null) }
    var manageName by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchedQuery by remember { mutableStateOf("") }
    var queueRemoteFlags by remember { mutableStateOf<List<Boolean>>(emptyList()) }
    var queueUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshToken by remember { mutableStateOf(0) }
    var queueVersion by remember { mutableStateOf(0) }
    var userQueue by remember { mutableStateOf(QueueStore.all(context)) }
    var focusTrackUrl by remember { mutableStateOf<String?>(null) }
    var focusFolderUrl by remember { mutableStateOf<String?>(null) }
    var shuffleState by remember { mutableStateOf(com.fader.mobil.core.FaderPlayer.shuffle) }
    var showClearFavorites by remember { mutableStateOf(false) }
    var showTrackInfo by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    var autoConnecting by remember { mutableStateOf(false) }

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var songCount by remember { mutableStateOf(-1) }

    val notificationPermission = Manifest.permission.POST_NOTIFICATIONS
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, notificationPermission) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(notificationPermission)
        }
    }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(permission)
    }

    LaunchedEffect(Unit) {
        val saved = WebDavCredentials.load(context) ?: return@LaunchedEffect
        savedShare = saved
        if (RemoteLibrary.isConnected) return@LaunchedEffect
        autoConnecting = true
        val result = RemoteLibrary.connect(saved.url, saved.user, saved.password)
        autoConnecting = false
        if (result.isSuccess) remoteConnected = true
    }

    LaunchedEffect(granted) {
        if (granted) {
            songCount = -1
            songCount = withContext(Dispatchers.IO) { LocalLibrary.load(context) }
        }
    }

    val sources = listOf(
        Source(
            id = "local",
            name = "On this phone",
            detail = when {
                !granted -> "Allow music access to scan"
                songCount < 0 -> "Scanning…"
                else -> "Internal storage · $songCount songs"
            },
            kind = SourceKind.Local,
        ),
        Source(
            id = "webdav",
            name = RemoteLibrary.hostLabel ?: "WebDAV share",
            detail = when {
                autoConnecting -> "Connecting…"
                remoteConnected -> "HTTPS · connected"
                savedShare != null -> "Saved · tap to reconnect"
                else -> "Not connected · tap to add"
            },
            kind = SourceKind.WebDav,
        ),
    )

    LaunchedEffect(remoteConnected, songCount) {
        if (restored) return@LaunchedEffect
        val saved = UiState.folder(context) ?: return@LaunchedEffect
        val ready = if (saved.remote) remoteConnected else songCount >= 0
        if (!ready) return@LaunchedEffect
        restored = true
        if (stack.isEmpty() && !showAddSource) {
            stack = listOf(NavFolder(saved.url, saved.name, saved.remote))
        }
    }

    val top = stack.lastOrNull()
    LaunchedEffect(top) {
        if (top != null) UiState.saveFolder(context, LastFolder(top.url, top.name, top.remote))
    }

    var listing by remember { mutableStateOf<Listing?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showSearch, searchQuery) {
        if (!showSearch) return@LaunchedEffect
        val text = searchQuery.trim()
        if (text.length < 2) {
            searchResults = emptyList()
            searchLoading = false
            searchedQuery = ""
            return@LaunchedEffect
        }
        // Show the spinner while the query is still settling, so the empty
        // state never flashes before the results arrive.
        searchLoading = true
        kotlinx.coroutines.delay(350)
        val found = runCatching { Search.query(text) }.getOrDefault(emptyList())
        searchResults = found
        searchedQuery = text
        searchLoading = false
    }

    LaunchedEffect(top, favorites, playlists) {
        val current = top ?: return@LaunchedEffect
        if (current.url == FAVORITES_URL) {
            listing = favoritesListing(favorites)
        } else if (current.url.startsWith(PLAYLIST_PREFIX)) {
            val id = current.url.removePrefix(PLAYLIST_PREFIX)
            playlists.firstOrNull { it.id == id }?.let { listing = playlistListing(it) }
        }
    }


    LaunchedEffect(top, refreshToken) {
        val current = top
        if (current == null) {
            listing = null
            loading = false
            loadError = null
            return@LaunchedEffect
        }
        if (current.url == FAVORITES_URL) {
            listing = favoritesListing(favorites)
            loading = false
            loadError = null
            return@LaunchedEffect
        }
        if (current.url == PLAYLISTS_URL) {
            listing = null
            loading = false
            loadError = null
            return@LaunchedEffect
        }
        if (current.url.startsWith(PLAYLIST_PREFIX)) {
            val id = current.url.removePrefix(PLAYLIST_PREFIX)
            val playlist = playlists.firstOrNull { it.id == id }
            listing = playlist?.let { playlistListing(it) }
            loading = false
            loadError = if (playlist == null) "Playlist not found" else null
            return@LaunchedEffect
        }
        listing = null
        loading = true
        loadError = null
        val result = runCatching {
            if (current.remote) {
                RemoteLibrary.listing(current.url, current.name)
            } else {
                withContext(Dispatchers.IO) { LocalLibrary.listing(current.url, current.name) }
            }
        }
        listing = result.getOrNull()
        loadError = result.exceptionOrNull()?.let { it.message ?: "Could not open folder" }
        loading = false
    }

    fun toggleFavorite(entry: TrackEntry, from: Listing) {
        if (from.url == FAVORITES_URL) {
            FavoritesStore.remove(context, entry.url)
            favorites = favorites.filterNot { it.url == entry.url }
            return
        }
        if (from.url.startsWith(PLAYLIST_PREFIX)) {
            val id = from.url.removePrefix(PLAYLIST_PREFIX)
            val stored = playlists.firstOrNull { it.id == id }
                ?.tracks
                ?.firstOrNull { it.url == entry.url }
            val favorite = stored ?: favoriteFrom(from, entry)
            val nowFavorite = FavoritesStore.toggle(context, favorite)
            com.fader.mobil.core.FaderPlayer.refresh()
            favorites = if (nowFavorite) {
                favorites + favorite
            } else {
                favorites.filterNot { it.url == favorite.url }
            }
            return
        }
        val favorite = Favorite(
            url = entry.url,
            title = entry.title,
            artist = entry.artist,
            album = entry.album,
            folderUrl = from.url,
            folderName = from.name,
            remote = from.remote,
        )
        val nowFavorite = FavoritesStore.toggle(context, favorite)
        com.fader.mobil.core.FaderPlayer.refresh()
                favorites = if (nowFavorite) {
            favorites + favorite
        } else {
            favorites.filterNot { it.url == favorite.url }
        }
    }

    LaunchedEffect(playback.url) {
        val url = playback.url ?: return@LaunchedEffect
        if (!com.fader.mobil.data.UiSettings.announceTrack) return@LaunchedEffect
        val title = playback.title ?: return@LaunchedEffect
        val artist = playback.artist
        view.announceForAccessibility(
            if (artist.isBlank()) "Now playing $title" else "Now playing $title by $artist",
        )
    }

    LaunchedEffect(Unit) {
        runCatching { com.fader.mobil.core.FaderPlayer.setUserQueue(userQueue) }
    }

    // Favorites can change from the notification / lock screen too.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1500)
            val latest = runCatching { FavoritesStore.all(context) }.getOrDefault(emptyList())
            if (latest != favorites) favorites = latest
        }
    }

    var restoreTried by remember { mutableStateOf(false) }
    var pendingAutoStart by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(songCount, remoteConnected, playback.url) {
        if (restoreTried || playback.url != null) return@LaunchedEffect
        val session = PlaybackSessionStore.load(context)
        if (session != null) {
            val needsRemote = session.tracks.any { it.remote }
            val needsLocal = session.tracks.any { !it.remote }
            if ((needsRemote && !remoteConnected) || (needsLocal && songCount < 0)) {
                return@LaunchedEffect
            }
            restoreTried = true
            val ok = runCatching {
                com.fader.mobil.core.FaderPlayer.restoreSession(
                    session.tracks,
                    session.index,
                    session.positionMs,
                )
            }.getOrDefault(false)
            if (ok) {
                if (session.folderUrl.isNotBlank()) {
                    val target = NavFolder(session.folderUrl, session.folderName, session.remote)
                    stack = listOf(target)
                    playingPath = listOf(target)
                    focusFolderUrl = session.folderUrl
                    focusTrackUrl = session.tracks[session.index].url
                }
                com.fader.mobil.core.FaderPlayer.ensureBackgroundService()
                return@LaunchedEffect
            }
        }
        val saved = UiState.playback(context) ?: return@LaunchedEffect
        val ready = if (saved.remote) remoteConnected else songCount >= 0
        if (!ready) return@LaunchedEffect
        restoreTried = true
        if (saved.folderUrl.isBlank()) return@LaunchedEffect
        // Open the folder and start the saved song inside its real list, so the
        // queue continues with the rest of the folder.
        val target = NavFolder(saved.folderUrl, saved.folderName, saved.remote)
        stack = listOf(target)
        playingPath = listOf(target)
        pendingAutoStart = saved.trackUrl
    }

    fun openContainer(container: String) {
        val trackUrl = playback.url
        tab = Tab.Library
        when {
            container == "favorites" -> {
                stack = listOf(NavFolder(FAVORITES_URL, "Favorites", remote = false))
            }
            container == "queue" -> {
                tab = Tab.Queue
            }
            container == "search" -> {
                showSearch = true
            }
            container.startsWith("playlist|") -> {
                val id = container.removePrefix("playlist|")
                val name = playlists.firstOrNull { it.id == id }?.name ?: "Playlist"
                stack = listOf(NavFolder(PLAYLIST_PREFIX + id, name, remote = false))
            }
            container.startsWith("folder|") -> {
                val parts = container.split("|", limit = 4)
                if (parts.size >= 4) {
                    val remote = parts[1] == "1"
                    val url = parts[2]
                    val name = parts[3]
                    stack = listOf(NavFolder(url, name, remote))
                    if (trackUrl != null) focusTrackUrl = trackUrl
                }
            }
        }
    }

    fun toggleFavoriteItem(favorite: Favorite) {
        val nowFavorite = FavoritesStore.toggle(context, favorite)
        com.fader.mobil.core.FaderPlayer.refresh()
        favorites = if (nowFavorite) {
            favorites + favorite
        } else {
            favorites.filterNot { it.url == favorite.url }
        }
    }

    fun maybeResume(url: String?) {
        if (url == null) return
        val saved = UiState.playback(context) ?: return
        if (saved.trackUrl != url || saved.positionMs <= 500) return
        scope.launch {
            kotlinx.coroutines.delay(400)
            FaderPlayer.seekTo(saved.positionMs)
        }
    }

    fun saveSession() {
        val url = playback.url ?: return
        val folder = playingPath.lastOrNull()
        val entries = runCatching { com.fader.mobil.core.FaderPlayer.queueEntries() }
            .getOrDefault(emptyList())
        if (folder != null) {
            UiState.savePlayback(
                context,
                SavedPlayback(
                    trackUrl = url,
                    positionMs = playback.positionMs,
                    folderUrl = folder.url,
                    folderName = folder.name,
                    remote = folder.remote,
                    title = playback.title.orEmpty(),
                    artist = playback.artist,
                ),
            )
        }
        if (entries.isEmpty()) return
        val index = entries.indexOfFirst { it.url == url }.coerceAtLeast(0)
        PlaybackSessionStore.save(
            context,
            PlaybackSession(
                folderUrl = playingListing?.url ?: folder?.url.orEmpty(),
                folderName = playingListing?.name ?: folder?.name.orEmpty(),
                remote = playingListing?.remote ?: folder?.remote ?: false,
                tracks = entries.map { entry ->
                    SessionTrack(entry.url, entry.title, entry.artist, entry.remote)
                },
                index = index,
                positionMs = playback.positionMs,
            ),
        )
    }

    LaunchedEffect(playback.url) {
        saveSession()
    }

    LaunchedEffect(playback.isPlaying, playback.url) {
        while (true) {
            kotlinx.coroutines.delay(5000)
            saveSession()
        }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun playTrack(index: Int) {
        val current = listing ?: return
        if (current.url == FAVORITES_URL) {
            val started = TrackQueue.play(
                remoteFlags = favorites.map { it.remote },
                urls = favorites.map { it.url },
                index = index,
            )
            if (!started) {
                toast("Connect a source to play favorites")
                return
            }
            queueRemoteFlags = favorites.map { it.remote }
            queueUrls = favorites.map { it.url }
            playingListing = current
            playingPath = stack
            UiState.saveSource(context, LastFolder(current.url, current.name, current.remote))
            maybeResume(current.tracks.getOrNull(index)?.url)
            return
        }
        if (current.url.startsWith(PLAYLIST_PREFIX)) {
            val id = current.url.removePrefix(PLAYLIST_PREFIX)
            val playlist = playlists.firstOrNull { it.id == id }
            if (playlist == null) {
                toast("Playlist not found")
                return
            }
            val started = TrackQueue.play(
                remoteFlags = playlist.tracks.map { it.remote },
                urls = playlist.tracks.map { it.url },
                index = index,
            )
            if (!started) {
                toast("Connect a source to play this playlist")
                return
            }
            queueRemoteFlags = playlist.tracks.map { it.remote }
            queueUrls = playlist.tracks.map { it.url }
            playingListing = current
            playingPath = stack
            queueRemoteFlags = playlist.tracks.map { it.remote }
            queueUrls = playlist.tracks.map { it.url }
            UiState.saveSource(context, LastFolder(current.url, current.name, current.remote))
            maybeResume(current.tracks.getOrNull(index)?.url)
            return
        }
        if (current.remote) {
            scope.launch {
                RemoteLibrary.play(current.url, current.name, index)
                    .onSuccess {
                        queueRemoteFlags = current.tracks.map { current.remote }
                        queueUrls = current.tracks.map { it.url }
                        playingListing = current
                        playingPath = stack
                        UiState.saveSource(context, LastFolder(current.url, current.name, current.remote))
                        maybeResume(current.tracks.getOrNull(index)?.url)
                    }
                    .onFailure { toast("Cannot play: ${it.message ?: "unknown error"}") }
            }
        } else {
            LocalLibrary.play(current.url, current.name, index)
            queueRemoteFlags = current.tracks.map { current.remote }
            queueUrls = current.tracks.map { it.url }
            playingListing = current
            playingPath = stack
            UiState.saveSource(context, LastFolder(current.url, current.name, current.remote))
            maybeResume(current.tracks.getOrNull(index)?.url)
        }
    }

    fun connect(server: String, user: String, password: String) {
        connecting = true
        connectError = null
        scope.launch {
            val result = RemoteLibrary.connect(server.trim(), user.trim(), password)
            connecting = false
            result.onSuccess {
                remoteConnected = true
                showAddSource = false
                RemoteLibrary.rootUrl?.let { root ->
                    WebDavCredentials.save(context, root, user, password)
                    savedShare = SavedShare(root, user, password)
                }
                val root = RemoteLibrary.rootUrl
                if (root != null) {
                    stack = listOf(NavFolder(root, RemoteLibrary.hostLabel ?: "SMB", remote = true))
                }
            }.onFailure { failure ->
                connectError = describeConnectError(failure)
            }
        }
    }

    fun closePlayer() {
        expanded = false
        if (playingPath.isNotEmpty()) stack = playingPath
    }

    var lastShownError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playback.error) {
        val error = playback.error
        if (error == null) {
            lastShownError = null
        } else if (error != lastShownError) {
            lastShownError = error
            toast(error)
        }
    }

    BackHandler(
        enabled = expanded || showAddSource || showSearch || stack.isNotEmpty() || tab != Tab.Library,
    ) {
        when {
            expanded -> closePlayer()
            showAddSource -> {
                showAddSource = false
                connectError = null
            }
            showSearch -> showSearch = false
            // Back from Queue/Settings returns to the list you were browsing.
            tab != Tab.Library -> tab = Tab.Library
            stack.isNotEmpty() -> stack = stack.dropLast(1)
            else -> tab = Tab.Library
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Box(Modifier.weight(1f)) {
                when {
                    showAddSource -> AddSourceScreen(
                        connecting = connecting,
                        error = connectError,
                        initialUrl = savedShare?.url.orEmpty(),
                        initialUser = savedShare?.user.orEmpty(),
                        onCancel = {
                            showAddSource = false
                            connectError = null
                        },
                        onConnect = { url, user, password -> connect(url, user, password) },
                    )

                    tab == Tab.Library && showSearch -> SearchScreen(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        results = searchResults,
                        loading = searchLoading || (searchQuery.trim().length >= 2 && searchedQuery != searchQuery.trim()),
                        favoriteUrls = remember(favorites) { favorites.map { it.url }.toSet() },
                        onBack = { showSearch = false },
                        onPlay = { index ->
                            val hits = searchResults
                            val hit = hits.getOrNull(index)
                            if (hit != null) {
                                queueRemoteFlags = hits.map { it.remote }
                                queueUrls = hits.map { it.url }
                                TrackQueue.play(queueRemoteFlags, queueUrls, index)
                                playingListing = Listing(
                                    url = "search://",
                                    name = "Search",
                                    folders = emptyList(),
                                    tracks = hits.map { item ->
                                        TrackEntry(
                                            name = item.name,
                                            title = item.name.substringBeforeLast('.'),
                                            artist = item.folderName,
                                            album = "",
                                            durationMs = 0,
                                            url = item.url,
                                            artKey = "track:" + item.url,
                                            artUri = if (item.remote) {
                                                "remote:" + item.url
                                            } else {
                                                LocalLibrary.art(item.url)?.uri
                                            },
                                        )
                                    },
                                    remote = false,
                                )
                                playingPath = stack
                                showSearch = false
                            }
                        },
                        onToggleFavorite = { hit ->
                            val favorite = Favorite(
                                url = hit.url,
                                title = hit.name.substringBeforeLast('.'),
                                artist = hit.folderName,
                                album = "",
                                folderUrl = hit.folderUrl,
                                folderName = hit.folderName,
                                remote = hit.remote,
                            )
                            val nowFavorite = FavoritesStore.toggle(context, favorite)
                            com.fader.mobil.core.FaderPlayer.refresh()
                            favorites = if (nowFavorite) favorites + favorite
                            else favorites.filterNot { it.url == favorite.url }
                                                    },
                        onOpenFolder = { hit ->
                            showSearch = false
                            focusTrackUrl = hit.url
                            focusFolderUrl = hit.folderUrl
                            stack = listOf(
                                NavFolder(
                                    hit.folderUrl,
                                    hit.folderName.ifBlank {
                                        if (hit.remote) "WebDAV" else "On this phone"
                                    },
                                    hit.remote,
                                ),
                            )
                        },
                        onAddToPlaylist = { hit ->
                            addTracks = listOf(
                                Favorite(
                                    url = hit.url,
                                    title = hit.name.substringBeforeLast('.'),
                                    artist = hit.folderName,
                                    album = "",
                                    folderUrl = hit.folderUrl,
                                    folderName = hit.folderName,
                                    remote = hit.remote,
                                ),
                            )
                            pickerExclude = null
                            moveFromFavorites = false
                            showPlaylistPicker = true
                        },
                    )

                    tab == Tab.Library && top != null && top.url == PLAYLISTS_URL -> PlaylistsScreen(
                        playlists = playlists,
                        onBack = { stack = stack.dropLast(1) },
                        onOpen = { playlist ->
                            stack = stack + NavFolder(
                                PLAYLIST_PREFIX + playlist.id,
                                playlist.name,
                                remote = false,
                            )
                        },
                        onNew = {
                            playlistName = ""
                            showNewPlaylist = true
                        },
                        onManage = { playlist ->
                            managePlaylist = playlist
                            manageName = playlist.name
                        },
                    )

                    tab == Tab.Library && top != null && loading -> LoadingPane("Opening ${top.name}…")
                    tab == Tab.Library && top != null && loadError != null -> ErrorPane(
                        message = loadError ?: "",
                        onBack = { stack = stack.dropLast(1) },
                    )
                    tab == Tab.Library && listing != null && top != null -> BrowseScreen(
                        listing = listing!!,
                        playlistId = listing?.url
                            ?.takeIf { it.startsWith(PLAYLIST_PREFIX) }
                            ?.removePrefix(PLAYLIST_PREFIX),
                        onRemoveFromPlaylist = { entry ->
                            val id = listing?.url
                                ?.takeIf { it.startsWith(PLAYLIST_PREFIX) }
                                ?.removePrefix(PLAYLIST_PREFIX)
                            if (id != null) {
                                PlaylistStore.removeTrack(context, id, entry.url)
                                playlists = PlaylistStore.all(context)
                                toast("Removed from playlist")
                            }
                        },
                        onBatchRemoveFromPlaylist = { entries ->
                            val id = listing?.url
                                ?.takeIf { it.startsWith(PLAYLIST_PREFIX) }
                                ?.removePrefix(PLAYLIST_PREFIX)
                            if (id != null) {
                                PlaylistStore.removeTracks(context, id, entries.map { it.url })
                                playlists = PlaylistStore.all(context)
                                toast("${entries.size} removed from playlist")
                            }
                        },
                        onBatchMoveToPlaylist = { entries ->
                            listing?.let { current ->
                                addTracks = entries.map { favoriteFrom(current, it) }
                                pickerExclude = current.url
                                    .takeIf { it.startsWith(PLAYLIST_PREFIX) }
                                    ?.removePrefix(PLAYLIST_PREFIX)
                                moveFromFavorites = current.url == FAVORITES_URL
                                showPlaylistPicker = true
                            }
                        },
                        isFavorites = listing?.url == FAVORITES_URL,
                        onRemoveFavorite = { entry ->
                            FavoritesStore.remove(context, entry.url)
                            favorites = FavoritesStore.all(context)
                            toast("Removed from favorites")
                        },
                        onBatchRemoveFavorite = { entries ->
                            entries.forEach { FavoritesStore.remove(context, it.url) }
                            favorites = FavoritesStore.all(context)
                            toast("${entries.size} removed from favorites")
                        },
                        currentUrl = playback.url,
                        shuffleOn = shuffleState,
                        onToggleShuffle = {
                            shuffleState = !shuffleState
                            com.fader.mobil.core.FaderPlayer.setShuffle(shuffleState)
                        },
                        onPlayFolder = {
                            listing?.let { current ->
                                if (current.tracks.isNotEmpty()) {
                                    val index = if (shuffleState) {
                                        kotlin.random.Random.nextInt(current.tracks.size)
                                    } else {
                                        0
                                    }
                                    playTrack(index)
                                }
                            }
                        },
                        onClearFavorites = { showClearFavorites = true },
                        focusUrl = focusTrackUrl,
                        focusFolderUrl = focusFolderUrl,
                        onFocusDone = {
                            focusTrackUrl = null
                            focusFolderUrl = null
                        },
                        favoriteUrls = remember(favorites) { favorites.map { it.url }.toSet() },
                        onToggleFavorite = { entry: TrackEntry -> toggleFavorite(entry, listing!!) },
                        onBack = { stack = stack.dropLast(1) },
                        onOpenFolder = { folder ->
                            val target = if (top.remote) folder else LocalLibrary.redirect(folder)
                            stack = stack + NavFolder(target.url, target.name, top.remote)
                        },
                        onPlayTrack = { index -> playTrack(index) },
                        onAddToPlaylist = {
                            listing?.let { current ->
                                addTracks = current.tracks.map { favoriteFrom(current, it) }
                                pickerExclude = null
                                showPlaylistPicker = true
                            }
                        },
                        onPlayRandom = {
                            listing?.let { current ->
                                if (current.tracks.isNotEmpty()) {
                                    playTrack(kotlin.random.Random.nextInt(current.tracks.size))
                                }
                            }
                        },
                        onRefresh = { refreshToken++ },
                        onQueueEnd = { entries ->
                            listing?.let { current ->
                                val items = entries.map { favoriteFrom(current, it) }
                                userQueue = QueueStore.addAll(context, items)
                                com.fader.mobil.core.FaderPlayer.addToUserQueue(items.map { it.url })
                                queueVersion++
                                toast("${entries.size} added to queue")
                            }
                        },
                        onAddTrackToPlaylist = { entry ->
                            listing?.let { current ->
                                addTracks = listOf(favoriteFrom(current, entry))
                                pickerExclude = null
                                showPlaylistPicker = true
                            }
                        },
                        onBatchFavorite = { entries ->
                            listing?.let { current ->
                                FavoritesStore.addAll(
                                    context,
                                    entries.map { favoriteFrom(current, it) },
                                )
                                favorites = FavoritesStore.all(context)
                                val playlistId = current.url
                                    .takeIf { it.startsWith(PLAYLIST_PREFIX) }
                                    ?.removePrefix(PLAYLIST_PREFIX)
                                if (playlistId != null) {
                                    PlaylistStore.removeTracks(context, playlistId, entries.map { it.url })
                                    playlists = PlaylistStore.all(context)
                                    toast("${entries.size} moved to favorites")
                                } else {
                                    toast("${entries.size} added to favorites")
                                }
                            }
                        },
                        onBatchAddToPlaylist = { entries ->
                            listing?.let { current ->
                                addTracks = entries.map { favoriteFrom(current, it) }
                                showPlaylistPicker = true
                            }
                        },
                    )

                    else -> when (tab) {
                        Tab.Library -> LibraryScreen(
                            sources = sources,
                            favoritesCount = favorites.size,
                            playlistsCount = playlists.size,
                            onOpenFavorites = {
                                stack = listOf(NavFolder(FAVORITES_URL, "Favorites", remote = false))
                            },
                            onOpenPlaylists = {
                                stack = listOf(NavFolder(PLAYLISTS_URL, "Playlists", remote = false))
                            },
                            onNewPlaylist = {
                                playlistName = ""
                                showNewPlaylist = true
                            },
                            onOpenSearch = {
                                searchQuery = ""
                                searchResults = emptyList()
                                showSearch = true
                            },
                        ) { source ->
                            when (source.kind) {
                                SourceKind.Local -> {
                                    if (granted && songCount >= 0) {
                                        val target = LocalLibrary.musicRoot()
                                        if (target.url == LocalLibrary.ROOT) {
                                            toast("No music folder found on this phone")
                                        } else {
                                            stack = listOf(NavFolder(target.url, target.name, remote = false))
                                        }
                                    } else if (!granted) {
                                        permissionLauncher.launch(permission)
                                    }
                                }

                                SourceKind.WebDav -> {
                                    val saved = savedShare
                                    if (!remoteConnected && saved != null) {
                                        scope.launch {
                                            val result = RemoteLibrary.connect(saved.url, saved.user, saved.password)
                                            if (result.isSuccess) {
                                                remoteConnected = true
                                                RemoteLibrary.rootUrl?.let { root ->
                                                    stack = listOf(NavFolder(root, RemoteLibrary.hostLabel ?: "Share", remote = true))
                                                }
                                            } else {
                                                toast(describeConnectError(result.exceptionOrNull() ?: Exception()))
                                                showAddSource = true
                                            }
                                        }
                                    } else if (remoteConnected && RemoteLibrary.rootUrl != null) {
                                        stack = listOf(
                                            NavFolder(
                                                RemoteLibrary.rootUrl!!,
                                                RemoteLibrary.hostLabel ?: "Share",
                                                remote = true,
                                            ),
                                        )
                                    } else {
                                        showAddSource = true
                                    }
                                }
                            }
                        }

                        Tab.Queue -> {
                            QueueScreen(
                                entries = userQueue,
                                currentUrl = playback.url,
                                favoriteUrls = remember(favorites) { favorites.map { it.url }.toSet() },
                                onPlay = { index ->
                                    com.fader.mobil.core.FaderPlayer.setUserQueue(userQueue)
                                    com.fader.mobil.core.FaderPlayer.playUserQueue(index)
                                    queueVersion++
                                },
                                onRemove = { index ->
                                    userQueue = QueueStore.removeAt(context, index)
                                    com.fader.mobil.core.FaderPlayer.removeUserQueueAt(index)
                                    queueVersion++
                                },
                                onToggleFavorite = { favorite -> toggleFavoriteItem(favorite) },
                                onAddToPlaylist = { items ->
                                    addTracks = items
                                    pickerExclude = null
                                    moveFromFavorites = false
                                    showPlaylistPicker = true
                                },
                                onClear = {
                                    QueueStore.clear(context)
                                    userQueue = emptyList()
                                    com.fader.mobil.core.FaderPlayer.setUserQueue(emptyList())
                                    queueVersion++
                                },
                            )
                        }

                        Tab.Settings -> SettingsScreen(
                            onAddSource = {
                                connectError = null
                                showAddSource = true
                            },

                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = playback.title != null && !expanded,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
            ) {
                val title = playback.title
                if (title != null) {
                    Column {
                        MiniPlayer(
                            track = currentTrack(title, playback),
                            isPlaying = playback.isPlaying,
                            positionMs = playback.positionMs,
                            durationMs = playback.durationMs,
                            onToggle = { FaderPlayer.toggle() },
                            onPrevious = { FaderPlayer.previous() },
                            onNext = { FaderPlayer.next() },
                            onOpen = { expanded = true },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            FaderTabBar(tab) { selected ->
                // Tapping the active Library tab again goes back to the home screen.
                if (selected == Tab.Library && tab == Tab.Library) stack = emptyList()
                tab = selected
                showAddSource = false
            }
        }

        if (showTrackInfo) {
            val name = com.fader.mobil.core.FaderPlayer.currentName
            val size = com.fader.mobil.core.FaderPlayer.currentSize
            val duration = com.fader.mobil.core.FaderPlayer.durationMs
            val format = name.substringAfterLast('.', "audio").uppercase()
            val bitrate = if (size > 0 && duration > 0) (size * 8 / duration).toInt() else 0
            AlertDialog(
                onDismissRequest = { showTrackInfo = false },
                title = { Text("Info", color = TextPrimary) },
                containerColor = Surface1,
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoLine("Title", playback.title.orEmpty())
                        InfoLine("Format", format)
                        InfoLine("Bitrate", if (bitrate > 0) "$bitrate kbps" else "Unknown")
                        InfoLine(
                            "Size",
                            if (size > 0) String.format("%.1f MB", size / 1048576.0) else "Unknown",
                        )
                        InfoLine("Duration", com.fader.mobil.ui.formatMs(playback.durationMs.toLong()))
                        InfoLine("Source", playingListing?.name ?: "Unknown")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTrackInfo = false }) {
                        Text("Close", color = Accent)
                    }
                },
            )
        }

        if (showClearFavorites) {
            AlertDialog(
                onDismissRequest = { showClearFavorites = false },
                title = { Text("Clear favorites?", color = TextPrimary) },
                containerColor = Surface1,
                text = {
                    Text(
                        "Removes every song from Favorites. Playlists and the library are not touched.",
                        color = com.fader.mobil.ui.theme.TextSecondary,
                        fontSize = 14.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        FavoritesStore.clear(context)
                        favorites = emptyList()
                        showClearFavorites = false
                        toast("Favorites cleared")
                    }) {
                        Text("Clear", color = androidx.compose.ui.graphics.Color(0xFFFF6B6B))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearFavorites = false }) {
                        Text("Cancel", color = com.fader.mobil.ui.theme.TextSecondary)
                    }
                },
            )
        }

        if (showPlaylistPicker) {
            val tracks = addTracks
            AlertDialog(
                onDismissRequest = {
                    showPlaylistPicker = false
                    addTracks = null
                    pickerExclude = null
                    moveFromFavorites = false
                },
                title = { Text("Add to playlist", color = TextPrimary) },
                containerColor = Surface1,
                text = {
                    Column {
                        val choices = playlists.filter { it.id != pickerExclude }
                        if (choices.isEmpty()) {
                            Text(
                                "No other playlists.",
                                color = com.fader.mobil.ui.theme.TextSecondary,
                                fontSize = 14.sp,
                            )
                        }
                        choices.take(6).forEach { playlist ->
                            TextButton(onClick = {
                                val selected = tracks
                                if (!selected.isNullOrEmpty()) {
                                    PlaylistStore.add(context, playlist.id, selected)
                                    if (moveFromFavorites) {
                                        selected.forEach { FavoritesStore.remove(context, it.url) }
                                        favorites = FavoritesStore.all(context)
                                    } else {
                                        pickerExclude?.let { source ->
                                            PlaylistStore.removeTracks(context, source, selected.map { it.url })
                                        }
                                    }
                                    playlists = PlaylistStore.all(context)
                                    toast("Moved to ${playlist.name}")
                                }
                                showPlaylistPicker = false
                                addTracks = null
                                pickerExclude = null
                                moveFromFavorites = false
                            }) { Text(playlist.name, color = Accent, fontSize = 16.sp) }
                        }
                        TextButton(onClick = {
                            showPlaylistPicker = false
                            playlistName = ""
                            showNewPlaylist = true
                        }) { Text("New playlist…", color = Accent, fontSize = 16.sp) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showPlaylistPicker = false
                        addTracks = null
                        pickerExclude = null
                        moveFromFavorites = false
                    }) { Text("Cancel", color = com.fader.mobil.ui.theme.TextSecondary) }
                },
            )
        }

        val managed = managePlaylist
        if (managed != null) {
            AlertDialog(
                onDismissRequest = { managePlaylist = null },
                title = { Text(managed.name, color = TextPrimary) },
                containerColor = Surface1,
                text = {
                    Column {
                        OutlinedTextField(
                            value = manageName,
                            onValueChange = { manageName = it },
                            singleLine = true,
                            label = { Text("Name") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = com.fader.mobil.ui.theme.Separator,
                                focusedLabelColor = Accent,
                                unfocusedLabelColor = com.fader.mobil.ui.theme.TextSecondary,
                                cursorColor = Accent,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            PlaylistStore.delete(context, managed.id)
                            playlists = PlaylistStore.all(context)
                            val playlistUrl = PLAYLIST_PREFIX + managed.id
                            if (stack.lastOrNull()?.url == playlistUrl) stack = stack.dropLast(1)
                            managePlaylist = null
                            toast("Playlist deleted")
                        }) {
                            Text("Delete playlist", color = androidx.compose.ui.graphics.Color(0xFFFF6B6B))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = manageName.isNotBlank(),
                        onClick = {
                            PlaylistStore.rename(context, managed.id, manageName)
                            playlists = PlaylistStore.all(context)
                            managePlaylist = null
                            toast("Playlist renamed")
                        },
                    ) { Text("Save", color = Accent) }
                },
                dismissButton = {
                    TextButton(onClick = { managePlaylist = null }) {
                        Text("Cancel", color = com.fader.mobil.ui.theme.TextSecondary)
                    }
                },
            )
        }

        if (showNewPlaylist) {
            AlertDialog(
                onDismissRequest = {
                    showNewPlaylist = false
                    addTracks = null
                },
                title = { Text("New playlist", color = TextPrimary) },
                containerColor = Surface1,
                text = {
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        singleLine = true,
                        label = { Text("Name") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = com.fader.mobil.ui.theme.Separator,
                            focusedLabelColor = Accent,
                            unfocusedLabelColor = com.fader.mobil.ui.theme.TextSecondary,
                            cursorColor = Accent,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = playlistName.isNotBlank(),
                        onClick = {
                            val created = PlaylistStore.create(context, playlistName)
                            addTracks?.let { selected ->
                                PlaylistStore.add(context, created.id, selected)
                                if (moveFromFavorites) {
                                    selected.forEach { FavoritesStore.remove(context, it.url) }
                                    favorites = FavoritesStore.all(context)
                                } else {
                                    pickerExclude?.let { source ->
                                        PlaylistStore.removeTracks(context, source, selected.map { it.url })
                                    }
                                }
                            }
                            playlists = PlaylistStore.all(context)
                            moveFromFavorites = false
                            showNewPlaylist = false
                            addTracks = null
                            playlistName = ""
                            toast("Playlist created")
                        },
                    ) { Text("Create", color = Accent) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showNewPlaylist = false
                        addTracks = null
                    }) { Text("Cancel", color = com.fader.mobil.ui.theme.TextSecondary) }
                },
            )
        }

        AnimatedVisibility(
            visible = expanded && playback.title != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            val nowTitle = playback.title
            if (nowTitle != null) {
                Box(Modifier.fillMaxSize()) {
                    PlayerScreen(
                        title = nowTitle,
                        artist = playback.artist.ifEmpty { "Unknown artist" },
                        album = playback.album,
                        sourceLabel = when {
                            playingListing == null -> "Now playing"
                            playingListing?.url == FAVORITES_URL -> "Favorites"
                            playingListing?.url == "search://" -> "Search"
                            else -> playingListing?.name ?: "Now playing"
                        },
                        nextTitle = com.fader.mobil.core.FaderPlayer.nextTitle,
                        isFavorite = favorites.any { it.url == playback.url },
                        isShuffle = shuffleState,
                        repeat = com.fader.mobil.core.FaderPlayer.repeatMode,
                        onOpenSource = {
                            expanded = false
                            // Whatever the source, leave the current tab (Settings,
                            // Queue, ...) so the list is actually visible.
                            tab = Tab.Library
                            val container = com.fader.mobil.core.FaderPlayer.currentContainer
                            when {
                                com.fader.mobil.core.FaderPlayer.inUserQueue -> {
                                    tab = Tab.Queue
                                }
                                playingListing?.url == "search://" -> {
                                    tab = Tab.Library
                                    showSearch = true
                                }
                                com.fader.mobil.core.FaderPlayer.playingExternal && container != null ->
                                    openContainer(container)
                                playingPath.isNotEmpty() -> {
                                    tab = Tab.Library
                                    stack = playingPath
                                }
                                container != null -> openContainer(container)
                                else -> {
                                    val saved = UiState.source(context)
                                    if (saved != null) {
                                        tab = Tab.Library
                                        stack = listOf(NavFolder(saved.url, saved.name, saved.remote))
                                    } else {
                                        tab = Tab.Library
                                        toast("Source list unavailable for this track")
                                    }
                                }
                            }
                        },
                        onToggleFavorite = {
                            val url = playback.url
                            if (url != null && playback.title != null) {
                                toggleFavorite(
                                    TrackEntry(
                                        name = playback.title ?: "",
                                        title = playback.title ?: "",
                                        artist = playback.artist,
                                        album = playback.album,
                                        durationMs = playback.durationMs.toLong(),
                                        url = url,
                                        artKey = playback.artKey,
                                        artUri = playback.artUri,
                                    ),
                                    playingListing ?: Listing(
                                        url = "",
                                        name = "Player",
                                        folders = emptyList(),
                                        tracks = emptyList(),
                                        remote = url.startsWith("http://") || url.startsWith("https://"),
                                    ),
                                )
                            }
                        },
                        onToggleShuffle = {
                            shuffleState = !shuffleState
                            com.fader.mobil.core.FaderPlayer.setShuffle(shuffleState)
                        },
                        onCycleRepeat = { com.fader.mobil.core.FaderPlayer.cycleRepeat() },
                        onAddToPlaylist = {
                            val url = playback.url
                            if (url != null) {
                                addTracks = listOf(
                                    Favorite(
                                        url = url,
                                        title = playback.title.orEmpty(),
                                        artist = playback.artist,
                                        album = playback.album,
                                        folderUrl = playingListing?.url.orEmpty(),
                                        folderName = playingListing?.name.orEmpty(),
                                        remote = url.startsWith("http://") || url.startsWith("https://"),
                                    ),
                                )
                                pickerExclude = null
                                moveFromFavorites = false
                                showPlaylistPicker = true
                            }
                        },
                        onInfo = { showTrackInfo = true },
                        isPlaying = playback.isPlaying,
                        positionMs = playback.positionMs,
                        durationMs = playback.durationMs,
                        onClose = { closePlayer() },
                        onToggle = { FaderPlayer.toggle() },
                        onNext = { FaderPlayer.next() },
                        onPrevious = { FaderPlayer.previous() },
                        onSeek = { fraction ->
                            FaderPlayer.seekTo((playback.durationMs * fraction).toInt())
                        },
                        artKey = playback.artKey,
                        artUri = playback.artUri,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = com.fader.mobil.ui.theme.TextSecondary, fontSize = 13.sp)
        Text(
            value,
            color = TextPrimary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun LoadingPane(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
            Spacer(Modifier.height(14.dp))
            Text(message, color = TextSecondary, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ErrorPane(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                message,
                color = TextSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) {
                Text("Back", color = Accent, fontSize = 16.sp)
            }
        }
    }
}

private fun describeConnectError(failure: Throwable): String {
    val raw = failure.message.orEmpty()
    return when {
        raw.contains("405") ->
            "HTTP 405: this address does not accept WebDAV. Use the WebDAV Server port (Synology: 5006), not the DSM port (5001)."

        raw.contains("401") -> "HTTP 401: wrong username or password."
        raw.contains("403") -> "HTTP 403: this account has no access to that folder."
        raw.contains("404") -> "HTTP 404: folder not found — check the path in the URL."
        raw.contains("SSL", ignoreCase = true) || raw.contains("certificate", ignoreCase = true) ->
            "TLS problem: the certificate is not trusted. Try the DDNS name with a valid certificate."

        raw.isNotBlank() -> raw
        else -> "Connection failed. Check the URL and the credentials."
    }
}

private fun currentTrack(title: String, playback: PlaybackState): Track = Track(
    id = "now",
    title = title,
    artist = playback.artist.ifEmpty { "Unknown artist" },
    album = playback.album,
    duration = formatMs(playback.durationMs.toLong()),
    art = (title.hashCode() and 0x7fffffff) % 8 + 1,
    artKey = playback.artKey,
    artUri = playback.artUri,
)
