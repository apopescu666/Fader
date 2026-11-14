package com.fader.mobil.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fader.mobil.core.LocalLibrary
import com.fader.mobil.core.SearchHit
import com.fader.mobil.data.Track
import com.fader.mobil.ui.components.FaderIcon
import com.fader.mobil.ui.components.FIcon
import com.fader.mobil.ui.components.SearchInput
import com.fader.mobil.ui.components.TrackRow
import com.fader.mobil.ui.components.described
import com.fader.mobil.ui.theme.Accent
import com.fader.mobil.ui.theme.TextSecondary

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<SearchHit>,
    loading: Boolean,
    favoriteUrls: Set<String>,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onToggleFavorite: (SearchHit) -> Unit,
    onOpenFolder: (SearchHit) -> Unit,
    onAddToPlaylist: (SearchHit) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp).described("Back")) {
                FaderIcon(FIcon.ChevronLeft, size = 20.dp, tint = Accent)
            }
            SearchInput(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Search all libraries",
                autoFocus = true,
            )
        }

        Spacer(Modifier.height(10.dp))

        when {
            query.trim().length < 2 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Type at least two characters",
                        color = TextSecondary,
                        fontSize = 15.sp,
                    )
                }
            }

            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
                }
            }

            results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No results for \"$query\"", color = TextSecondary, fontSize = 15.sp)
                }
            }

            else -> {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        if (results.size == 1) "1 result" else "${results.size} results",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                    itemsIndexed(results, key = { index, hit -> "s:$index:${hit.url}" }) { index, hit ->
                        val title = hit.name.substringBeforeLast('.')
                        val source = if (hit.remote) "WebDAV" else "On this phone"
                        val artUri = if (hit.remote) "remote:" + hit.url else LocalLibrary.art(hit.url)?.uri
                        TrackRow(
                            track = Track(
                                id = hit.url,
                                title = title,
                                artist = hit.folderName.ifBlank { source },
                                album = source,
                                duration = "",
                                art = (hit.url.hashCode() and 0x7fffffff) % 8 + 1,
                                artKey = "track:" + hit.url,
                                artUri = artUri,
                            ),
                            onClick = { onPlay(index) },
                            showDivider = index != results.lastIndex,
                            isFavorite = favoriteUrls.contains(hit.url),
                            onToggleFavorite = { onToggleFavorite(hit) },
                            onLongClick = { onAddToPlaylist(hit) },
                            trailing = {
                                IconButton(
                                    onClick = { onOpenFolder(hit) },
                                    modifier = Modifier.size(40.dp).described("Open containing folder"),
                                ) {
                                    FaderIcon(FIcon.Folder, size = 18.dp, tint = TextSecondary)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
