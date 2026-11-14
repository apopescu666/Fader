package com.fader.mobil.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fader.mobil.core.FolderEntry
import com.fader.mobil.core.Listing
import com.fader.mobil.core.LocalLibrary
import com.fader.mobil.core.RemoteLibrary
import com.fader.mobil.core.TrackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.fader.mobil.data.Track
import com.fader.mobil.ui.components.DividerLine
import com.fader.mobil.ui.components.Artwork
import com.fader.mobil.ui.components.FaderIcon
import com.fader.mobil.ui.components.FIcon
import com.fader.mobil.ui.components.IconPill
import com.fader.mobil.ui.components.described
import com.fader.mobil.ui.components.SearchInput
import com.fader.mobil.ui.components.TrackRow
import com.fader.mobil.ui.formatMs
import com.fader.mobil.ui.theme.Accent
import com.fader.mobil.ui.theme.FavoriteRed
import com.fader.mobil.ui.theme.Surface1
import com.fader.mobil.ui.theme.Surface2
import com.fader.mobil.ui.theme.TextPrimary
import com.fader.mobil.ui.theme.TextSecondary
import com.fader.mobil.ui.theme.TextTertiary

private enum class SortOrder(val label: String) {
    Default("Default order"),
    NameAsc("Name A–Z"),
    NameDesc("Name Z–A"),
}

@Composable
fun BrowseScreen(
    listing: Listing,
    currentUrl: String? = null,
    favoriteUrls: Set<String> = emptySet(),
    onBack: () -> Unit,
    onOpenFolder: (FolderEntry) -> Unit,
    onPlayTrack: (Int) -> Unit,
    onToggleFavorite: ((TrackEntry) -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onAddTrackToPlaylist: ((TrackEntry) -> Unit)? = null,
    onBatchFavorite: ((List<TrackEntry>) -> Unit)? = null,
    onBatchAddToPlaylist: ((List<TrackEntry>) -> Unit)? = null,
    playlistId: String? = null,
    onRemoveFromPlaylist: ((TrackEntry) -> Unit)? = null,
    onBatchRemoveFromPlaylist: ((List<TrackEntry>) -> Unit)? = null,
    onBatchMoveToPlaylist: ((List<TrackEntry>) -> Unit)? = null,
    isFavorites: Boolean = false,
    onRemoveFavorite: ((TrackEntry) -> Unit)? = null,
    onBatchRemoveFavorite: ((List<TrackEntry>) -> Unit)? = null,
    onPlayRandom: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onQueueEnd: ((List<TrackEntry>) -> Unit)? = null,
    focusUrl: String? = null,
    focusFolderUrl: String? = null,
    onFocusDone: (() -> Unit)? = null,
    onClearFavorites: (() -> Unit)? = null,
    shuffleOn: Boolean = false,
    onToggleShuffle: (() -> Unit)? = null,
    onPlayFolder: (() -> Unit)? = null,
) {
    var highlightUrl by remember(listing.url) { mutableStateOf<String?>(null) }
    var sort by remember(listing.url) { mutableStateOf(SortOrder.Default) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var selection by remember(listing.url) { mutableStateOf<Set<String>>(emptySet()) }
    val selecting = selection.isNotEmpty()
    val selectedEntries = listing.tracks.filter { it.url in selection }

    fun toggleSelection(url: String) {
        selection = if (url in selection) selection - url else selection + url
    }

    androidx.activity.compose.BackHandler(enabled = selecting) { selection = emptySet() }

    val ordered = remember(listing.tracks, sort, shuffleOn) {
        val indexed = listing.tracks.withIndex().toList()
        val sorted = when (sort) {
            SortOrder.Default -> indexed
            SortOrder.NameAsc -> indexed.sortedBy { it.value.title.lowercase() }
            SortOrder.NameDesc -> indexed.sortedByDescending { it.value.title.lowercase() }
        }
        if (shuffleOn) sorted.shuffled() else sorted
    }
    val tracks = remember(ordered) { ordered.map { it.value.toUi() } }
    val originalIndex = remember(ordered) { ordered.map { it.index } }
    val listState = rememberLazyListState()
    var query by remember(listing.url) { mutableStateOf("") }
    val visible = remember(tracks, query) {
        if (query.isBlank()) {
            tracks.withIndex().toList()
        } else {
            tracks.withIndex().filter { (_, track) ->
                track.title.contains(query, ignoreCase = true) ||
                    track.artist.contains(query, ignoreCase = true) ||
                    track.album.contains(query, ignoreCase = true)
            }
        }
    }
    LaunchedEffect(listing.url, tracks.size, currentUrl, shuffleOn, sort) {
        val focusPending = focusUrl != null &&
            (focusFolderUrl == null || focusFolderUrl == listing.url)
        if (selecting || focusPending) return@LaunchedEffect
        val index = tracks.indexOfFirst { it.id == currentUrl }
        if (index >= 0) {
            listState.animateScrollToItem(listing.folders.size + index)
        }
    }

    LaunchedEffect(listing.url, focusUrl, focusFolderUrl, tracks.size) {
        val target = focusUrl ?: return@LaunchedEffect
        if (focusFolderUrl != null && listing.url != focusFolderUrl) return@LaunchedEffect
        val targetName = fileName(target)
        val index = tracks.indexOfFirst { track ->
            track.id == target || fileName(track.id) == targetName
        }
        if (index < 0) {
            // Nothing to focus here: release the flag so centring works again.
            onFocusDone?.invoke()
            return@LaunchedEffect
        }
        // Highlight first: scrolling can be interrupted by another animation.
        highlightUrl = tracks[index].id
        listState.animateScrollToItem(listing.folders.size + index)
        kotlinx.coroutines.delay(2600)
        highlightUrl = null
        onFocusDone?.invoke()
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                IconButton(
                    onClick = { selection = emptySet() },
                    modifier = Modifier.size(44.dp).described("Clear selection"),
                ) {
                    FaderIcon(FIcon.Close, size = 20.dp, tint = Accent)
                }
                Text(
                    "${selection.size} selected",
                    Modifier.weight(1f),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                if (!isFavorites) {
                    IconButton(
                        onClick = {
                            onBatchFavorite?.invoke(selectedEntries)
                            selection = emptySet()
                        },
                        modifier = Modifier.size(44.dp).described("Add selection to favorites"),
                    ) {
                        FaderIcon(FIcon.Heart, size = 20.dp, tint = FavoriteRed)
                    }
                }
                IconButton(
                    onClick = {
                        onQueueEnd?.invoke(selectedEntries)
                        selection = emptySet()
                    },
                    modifier = Modifier.size(44.dp).described("Add selection to the queue"),
                ) {
                    FaderIcon(FIcon.Queue, size = 20.dp, tint = Accent)
                }
                IconButton(
                    onClick = {
                        if (playlistId != null || isFavorites) {
                            onBatchMoveToPlaylist?.invoke(selectedEntries)
                        } else {
                            onBatchAddToPlaylist?.invoke(selectedEntries)
                        }
                        selection = emptySet()
                    },
                    modifier = Modifier.size(44.dp).described(
                        if (playlistId != null) "Move selection to a playlist" else "Add selection to a playlist",
                    ),
                ) {
                    FaderIcon(FIcon.List, size = 20.dp, tint = Accent)
                }
                if (playlistId != null || isFavorites) {
                    IconButton(
                        onClick = {
                            if (isFavorites) {
                                onBatchRemoveFavorite?.invoke(selectedEntries)
                            } else {
                                onBatchRemoveFromPlaylist?.invoke(selectedEntries)
                            }
                            selection = emptySet()
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        FaderIcon(FIcon.Delete, size = 20.dp, tint = Accent)
                    }
                }
            } else {
                IconButton(onClick = onBack, modifier = Modifier.size(44.dp).described("Back")) {
                    FaderIcon(FIcon.ChevronLeft, size = 20.dp, tint = Accent)
                }
                val context = androidx.compose.ui.platform.LocalContext.current
                val sourceLine = remember(listing.url, listing.remote, isFavorites, playlistId) {
                    when {
                        isFavorites -> "Favorites"
                        playlistId != null -> "Playlist"
                        listing.remote -> {
                            val host = runCatching {
                                java.net.URI(
                                    com.fader.mobil.core.WebDavCredentials.load(context)?.url.orEmpty(),
                                ).host
                            }.getOrNull()
                            (host ?: "NAS") + " · " + shortPath(listing.url)
                        }
                        else -> "This phone · " + shortPath(listing.url)
                    }
                }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        listing.name,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        sourceLine,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(
                        onClick = { moreMenuOpen = true },
                        modifier = Modifier.size(44.dp).described("More options"),
                    ) {
                        FaderIcon(FIcon.More, size = 20.dp, tint = Accent)
                    }
                    DropdownMenu(
                        expanded = moreMenuOpen,
                        onDismissRequest = { moreMenuOpen = false },
                    ) {
                        if (listing.tracks.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Play random song") },
                                onClick = {
                                    moreMenuOpen = false
                                    onPlayRandom?.invoke()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Add folder to playlist") },
                            onClick = {
                                moreMenuOpen = false
                                onAddToPlaylist?.invoke()
                            },
                        )
                        if (isFavorites && onClearFavorites != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Clear favorites",
                                        color = androidx.compose.ui.graphics.Color(0xFFFF6B6B),
                                    )
                                },
                                onClick = {
                                    moreMenuOpen = false
                                    onClearFavorites.invoke()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Refresh") },
                            onClick = {
                                moreMenuOpen = false
                                onRefresh?.invoke()
                            },
                        )
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolderCover(listing)
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        listing.tracks.isNotEmpty() -> "${listing.tracks.size} songs"
                        listing.folders.isNotEmpty() -> "${listing.folders.size} folders"
                        else -> "0 songs"
                    },
                    color = TextSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.End,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onToggleShuffle != null) {
                    IconPill(
                        icon = FIcon.Shuffle,
                        contentDescription = if (shuffleOn) "Turn shuffle off" else "Turn shuffle on",
                        active = shuffleOn,
                        onClick = { onToggleShuffle.invoke() },
                    )
                    Spacer(Modifier.width(34.dp))
                }
                if (onPlayFolder != null) {
                    IconPill(FIcon.Play, if (shuffleOn) "Play shuffled" else "Play folder") {
                        onPlayFolder.invoke()
                    }
                    Spacer(Modifier.width(34.dp))
                }
                Box {
                    IconPill(FIcon.Sort, "Sort songs") { sortMenuOpen = true }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        SortOrder.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label,
                                        color = if (sort == option) Accent else TextPrimary,
                                    )
                                },
                                onClick = {
                                    sort = option
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            SearchInput(query, { query = it }, "Search in this folder")
            Spacer(Modifier.height(10.dp))
        }

        if (listing.folders.isEmpty() && visible.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (query.isNotBlank()) "No matches" else "No audio in this folder",
                        color = TextSecondary,
                        fontSize = 15.sp,
                    )
                    if (query.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Try a different word",
                            color = TextTertiary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        } else {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    itemsIndexed(
                        listing.folders,
                        key = { index, folder -> "f:$index:${folder.url}" },
                    ) { index, folder ->
                        FolderRow(folder) { onOpenFolder(folder) }
                        if (tracks.isNotEmpty() || folder != listing.folders.last()) {
                            DividerLine(insetStart = 62.dp)
                        }
                    }
                    itemsIndexed(
                        visible,
                        key = { index, item -> "t:$index:${item.value.id}" },
                    ) { rowIndex, indexed ->
                        val index = rowIndex
                        val track = indexed.value
                        TrackRow(
                            track = track,
                            onClick = {
                                if (selecting) {
                                    toggleSelection(track.id)
                                } else {
                                    // Leave the search so the folder shows and marks
                                    // the song the user just picked.
                                    if (query.isNotBlank()) query = ""
                                    onPlayTrack(
                                        originalIndex.getOrElse(indexed.index) { indexed.index },
                                    )
                                }
                            },
                            showDivider = index != visible.lastIndex,
                            isCurrent = track.id == currentUrl,
                            isFavorite = favoriteUrls.contains(track.id),
                            selected = track.id in selection,
                            highlighted = track.id == highlightUrl,
                            onToggleFavorite = onToggleFavorite?.let { toggle ->
                                {
                                    val original = originalIndex.getOrElse(indexed.index) {
                                        indexed.index
                                    }
                                    toggle(listing.tracks[original])
                                }
                            },
                            onLongClick = { toggleSelection(track.id) },
                            onSwipeLeft = if (selecting) {
                                null
                            } else if (playlistId != null) {
                                onRemoveFromPlaylist?.let { remove ->
                                    {
                                        val original = originalIndex.getOrElse(indexed.index) {
                                            indexed.index
                                        }
                                        remove(listing.tracks[original])
                                    }
                                }
                            } else {
                                onAddTrackToPlaylist?.let { add ->
                                    {
                                        val original = originalIndex.getOrElse(indexed.index) {
                                            indexed.index
                                        }
                                        add(listing.tracks[original])
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Last two path segments, for the source line under the folder title. */
private fun shortPath(url: String): String {
    val decoded = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
    val parts = decoded.substringBefore('?').split('/').filter { it.isNotBlank() }
    return "/" + parts.takeLast(2).joinToString("/")
}

private fun isSystemFolderName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.startsWith("#") || lower.startsWith("@") || lower.startsWith(".") ||
        lower == "recycle" || lower == "recycler"
}

@Composable
private fun FolderCover(listing: Listing) {
    // Pick from the folder's own tracks synchronously, so the real cover shows
    // immediately instead of flashing the app icon first.
    val directPick = remember(listing.url, listing.tracks.size) {
        if (listing.tracks.isEmpty()) {
            null
        } else {
            listing.tracks
                .filter { it.artUri != null || it.artKey != null }
                .randomOrNull()
        }
    }
    var artKey by remember(listing.url) { mutableStateOf(directPick?.artKey) }
    var artUri by remember(listing.url) { mutableStateOf(directPick?.artUri) }
    var seed by remember(listing.url) {
        mutableStateOf(directPick?.artKey?.hashCode()?.let { (it and 0x7fffffff) % 8 + 1 } ?: 1)
    }
    LaunchedEffect(listing.url, listing.tracks.size, listing.folders.size) {
        if (listing.tracks.isNotEmpty()) {
            return@LaunchedEffect
        }
        val pick = run {
            // A folder that only holds subfolders borrows art from the first real
            // child that has some; NAS system folders like #recycle are skipped.
            listing.folders
                .filterNot { isSystemFolderName(it.name) }
                .take(5)
                .firstNotNullOfOrNull { folder ->
                    val child = runCatching {
                        if (folder.url.startsWith("http")) {
                            RemoteLibrary.listing(folder.url, folder.name)
                        } else {
                            withContext(Dispatchers.IO) {
                                LocalLibrary.listing(folder.url, folder.name)
                            }
                        }
                    }.getOrNull()
                    child?.tracks?.firstOrNull { it.artUri != null || it.artKey != null }
                }
        }
        artKey = pick?.artKey
        artUri = pick?.artUri
        seed = pick?.artKey?.hashCode()?.let { (it and 0x7fffffff) % 8 + 1 } ?: 1
    }

    if (artKey == null && artUri == null) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.fader.mobil.R.drawable.ic_launcher_bg),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(13.dp)),
        )
    } else {
        Artwork(
            index = seed,
            size = 64.dp,
            corner = 13.dp,
            artKey = artKey,
            artUri = artUri,
        )
    }
}

@Composable
private fun FolderRow(folder: FolderEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = "Open ${folder.name}", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            FaderIcon(FIcon.Folder, size = 20.dp, tint = Accent)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            folder.name,
            Modifier.weight(1f),
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FaderIcon(FIcon.ChevronRight, size = 14.dp, tint = TextSecondary)
    }
}

private fun fileName(value: String): String {
    val raw = value.substringAfterLast('/')
    return if (raw.contains('%')) {
        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    } else {
        raw
    }
}

private fun TrackEntry.toUi(): Track = Track(
    id = url,
    title = title,
    artist = artist,
    album = album,
    duration = if (durationMs > 0) formatMs(durationMs) else "",
    art = (url.hashCode() and 0x7fffffff) % 8 + 1,
    artKey = artKey,
    artUri = artUri,
)

@Composable
private fun remember2(listing: Listing): List<Track> =
    androidx.compose.runtime.remember(listing.url, listing.tracks) { listing.tracks.map { it.toUi() } }
