package com.fader.mobil.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fader.mobil.data.Source
import com.fader.mobil.ui.components.FaderCard
import com.fader.mobil.ui.components.ScreenTitle
import com.fader.mobil.ui.components.described
import com.fader.mobil.ui.components.SearchField
import com.fader.mobil.ui.components.SectionHeader
import com.fader.mobil.ui.components.SourceRow
import com.fader.mobil.ui.theme.TextPrimary
import com.fader.mobil.ui.theme.TextSecondary

@Composable
fun LibraryScreen(
    sources: List<Source>,
    favoritesCount: Int,
    playlistsCount: Int,
    onOpenFavorites: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onNewPlaylist: () -> Unit,
    onOpenSearch: () -> Unit,
    onSource: (Source) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Library", Modifier.weight(1f))
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.fader.mobil.R.drawable.ic_launcher_bg),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp)),
            )
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { onOpenSearch() }) {
            SearchField("Search songs, albums, artists")
        }

        SectionHeader("Sources")
        FaderCard {
            sources.forEachIndexed { index, source ->
                SourceRow(
                    source = source,
                    onClick = { onSource(source) },
                    showDivider = index != sources.lastIndex,
                )
            }
        }

        SectionHeader("Favorites")
        FaderCard(corner = 12.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clickable(
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = "Open favorites",
                    ) { onOpenFavorites() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(com.fader.mobil.ui.theme.Surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    com.fader.mobil.ui.components.FaderIcon(
                        com.fader.mobil.ui.components.FIcon.Heart,
                        size = 18.dp,
                        tint = com.fader.mobil.ui.theme.Accent,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Favorites",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                    Text(
                        if (favoritesCount == 1) "1 song" else "$favoritesCount songs",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                }
                com.fader.mobil.ui.components.FaderIcon(
                    com.fader.mobil.ui.components.FIcon.ChevronRight,
                    size = 14.dp,
                    tint = TextSecondary,
                )
            }
        }

        SectionHeader("Playlists", playlistsCount.toString())
        FaderCard(corner = 12.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clickable(
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = "Open playlists",
                    ) { onOpenPlaylists() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(com.fader.mobil.ui.theme.Surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    com.fader.mobil.ui.components.FaderIcon(
                        com.fader.mobil.ui.components.FIcon.Note,
                        size = 18.dp,
                        tint = com.fader.mobil.ui.theme.Accent,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Playlists",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                    Text(
                        if (playlistsCount == 1) "1 playlist" else "$playlistsCount playlists",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                }
                androidx.compose.material3.IconButton(
                    onClick = { onNewPlaylist() },
                    modifier = Modifier.size(40.dp).described("New playlist"),
                ) {
                    com.fader.mobil.ui.components.FaderIcon(
                        com.fader.mobil.ui.components.FIcon.Plus,
                        size = 18.dp,
                        tint = com.fader.mobil.ui.theme.Accent,
                    )
                }
                com.fader.mobil.ui.components.FaderIcon(
                    com.fader.mobil.ui.components.FIcon.ChevronRight,
                    size = 14.dp,
                    tint = TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.height(20.dp))
    }
}
