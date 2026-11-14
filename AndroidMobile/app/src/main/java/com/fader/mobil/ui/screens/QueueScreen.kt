package com.fader.mobil.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fader.mobil.core.LocalLibrary
import com.fader.mobil.core.RemoteLibrary
import com.fader.mobil.data.Favorite
import com.fader.mobil.data.Track
import com.fader.mobil.ui.components.FaderIcon
import com.fader.mobil.ui.components.FIcon
import com.fader.mobil.ui.components.ScreenTitle
import com.fader.mobil.ui.components.TrackRow
import com.fader.mobil.ui.components.described
import com.fader.mobil.ui.theme.Accent
import com.fader.mobil.ui.theme.FavoriteRed
import com.fader.mobil.ui.theme.Surface1
import com.fader.mobil.ui.theme.TextPrimary
import com.fader.mobil.ui.theme.TextSecondary

@Composable
fun QueueScreen(
    entries: List<Favorite>,
    currentUrl: String?,
    favoriteUrls: Set<String>,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onToggleFavorite: (Favorite) -> Unit,
    onAddToPlaylist: (List<Favorite>) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selecting = selection.isNotEmpty()
    val selected = entries.filter { it.url in selection }

    fun toggle(url: String) {
        selection = if (url in selection) selection - url else selection + url
    }

    androidx.activity.compose.BackHandler(enabled = selecting) { selection = emptySet() }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        if (selecting) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                IconButton(
                    onClick = {
                        selected.forEach(onToggleFavorite)
                        selection = emptySet()
                    },
                    modifier = Modifier.size(44.dp).described("Add selection to favorites"),
                ) {
                    FaderIcon(FIcon.Heart, size = 20.dp, tint = FavoriteRed)
                }
                IconButton(
                    onClick = {
                        onAddToPlaylist(selected)
                        selection = emptySet()
                    },
                    modifier = Modifier.size(44.dp).described("Add selection to a playlist"),
                ) {
                    FaderIcon(FIcon.List, size = 20.dp, tint = Accent)
                }
                IconButton(
                    onClick = {
                        selected.mapNotNull { item -> entries.indexOf(item) }
                            .sortedDescending()
                            .forEach { index -> onRemove(index) }
                        selection = emptySet()
                    },
                    modifier = Modifier.size(44.dp).described("Remove selection from the queue"),
                ) {
                    FaderIcon(FIcon.Delete, size = 20.dp, tint = Accent)
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScreenTitle("Queue")
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (entries.isEmpty()) "Nothing queued" else "${entries.size} songs",
                Modifier.weight(1f),
                color = TextSecondary,
                fontSize = 15.sp,
            )
            if (entries.isNotEmpty() && !selecting) {
                Text(
                    "Clear",
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onClear() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Long-press songs and add them with the queue button.",
                    color = TextSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val tracks = entries.map { entry ->
                val art = if (entry.remote) RemoteLibrary.art(entry.url) else LocalLibrary.art(entry.url)
                Track(
                    id = entry.url,
                    title = entry.title,
                    artist = entry.artist,
                    album = entry.album,
                    duration = "",
                    art = (entry.url.hashCode() and 0x7fffffff) % 8 + 1,
                    artKey = art?.key,
                    artUri = art?.uri,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 2.dp)) {
                    itemsIndexed(tracks, key = { index, track -> "q:$index:${track.id}" }) { index, track ->
                        val favorite = entries[index]
                        TrackRow(
                            track = track,
                            onClick = {
                                if (selecting) toggle(track.id) else onPlay(index)
                            },
                            onLongClick = { toggle(track.id) },
                            showDivider = index != tracks.lastIndex,
                            isCurrent = currentUrl != null && track.id == currentUrl,
                            isFavorite = favoriteUrls.contains(track.id),
                            selected = track.id in selection,
                            onToggleFavorite = { onToggleFavorite(favorite) },
                            onSwipeLeft = {
                                onRemove(index)
                                selection = selection - track.id
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
