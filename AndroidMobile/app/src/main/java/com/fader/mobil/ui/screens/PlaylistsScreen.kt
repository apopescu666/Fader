package com.fader.mobil.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fader.mobil.data.Playlist
import com.fader.mobil.ui.components.FaderCard
import com.fader.mobil.ui.components.FaderIcon
import com.fader.mobil.ui.components.FIcon
import com.fader.mobil.ui.components.ScreenTitle
import com.fader.mobil.ui.components.described
import com.fader.mobil.ui.theme.Accent
import com.fader.mobil.ui.theme.Surface2
import com.fader.mobil.ui.theme.TextPrimary
import com.fader.mobil.ui.theme.TextSecondary

/** All playlists, one row each, opened like folders. */
@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    onBack: () -> Unit,
    onOpen: (Playlist) -> Unit,
    onNew: () -> Unit,
    onManage: (Playlist) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp).described("Back")) {
                FaderIcon(FIcon.ChevronLeft, size = 20.dp, tint = Accent)
            }
            Text(
                "Playlists",
                Modifier.weight(1f),
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = onNew, modifier = Modifier.size(44.dp).described("New playlist")) {
                FaderIcon(FIcon.Plus, size = 20.dp, tint = Accent)
            }
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            ScreenTitle("Playlists")
            Text(
                if (playlists.size == 1) "1 playlist" else "${playlists.size} playlists",
                color = TextSecondary,
                fontSize = 15.sp,
            )
        }

        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 20.dp),
        ) {
            if (playlists.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(48.dp))
                    Text(
                        "No playlists yet. Tap + to create one.",
                        color = TextSecondary,
                        fontSize = 15.sp,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(playlists, key = { it.id }) { playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            onClick = { onOpen(playlist) },
                            onLongClick = { onManage(playlist) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    FaderCard(corner = 12.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Open ${playlist.name}",
                    onLongClickLabel = "Rename or delete ${playlist.name}",
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
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
                FaderIcon(FIcon.Note, size = 18.dp, tint = Accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (playlist.tracks.size == 1) "1 song" else "${playlist.tracks.size} songs",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
            FaderIcon(FIcon.ChevronRight, size = 14.dp, tint = TextSecondary)
        }
    }
}
