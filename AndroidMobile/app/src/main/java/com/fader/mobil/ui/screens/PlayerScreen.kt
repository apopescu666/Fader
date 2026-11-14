package com.fader.mobil.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fader.mobil.ui.components.Artwork
import com.fader.mobil.ui.components.FaderIcon
import com.fader.mobil.ui.components.FIcon
import com.fader.mobil.ui.components.described
import com.fader.mobil.ui.formatMs
import com.fader.mobil.ui.theme.Accent
import com.fader.mobil.ui.theme.AccentInk
import com.fader.mobil.ui.theme.FavoriteRed
import com.fader.mobil.ui.theme.Bg
import com.fader.mobil.ui.theme.TextPrimary
import com.fader.mobil.ui.theme.TextSecondary

@Composable
fun PlayerScreen(
    title: String,
    artist: String,
    album: String,
    sourceLabel: String,
    nextTitle: String?,
    isPlaying: Boolean,
    positionMs: Int,
    durationMs: Int,
    isFavorite: Boolean,
    isShuffle: Boolean,
    repeat: Int,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onOpenSource: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onInfo: () -> Unit,
    artKey: String? = null,
    artUri: String? = null,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(fraction0(positionMs, durationMs)) }
    var menuOpen by remember { mutableStateOf(false) }
    var artColor by remember(artKey) { mutableStateOf<Color?>(null) }
    LaunchedEffect(artKey) {
        val key = artKey ?: return@LaunchedEffect
        repeat(12) {
            val colour = com.fader.mobil.ui.components.ArtworkLoader.dominantColor(key)
            if (colour != null) {
                artColor = colour
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(150)
        }
    }

    val fraction = fraction0(positionMs, durationMs)

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to (artColor ?: Color(0xFF123A38)),
                    0.55f to Color(0xFF0A0F12),
                    1f to Bg,
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(44.dp).described("Close player")) {
                    FaderIcon(FIcon.ChevronDown, size = 22.dp, tint = Accent)
                }
                Text(
                    sourceLabel.uppercase(),
                    Modifier.weight(1f),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    letterSpacing = 0.9.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(44.dp).described("More options"),
                    ) {
                        FaderIcon(FIcon.More, size = 20.dp, tint = Accent)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Add to playlist") },
                            onClick = {
                                menuOpen = false
                                onAddToPlaylist()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Info") },
                            onClick = {
                                menuOpen = false
                                onInfo()
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .shadow(26.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp)),
                ) {
                    Artwork(
                        index = 1,
                        size = 320.dp,
                        corner = 24.dp,
                        artKey = artKey,
                        artUri = artUri,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                title,
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                artist,
                color = TextSecondary,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                album,
                color = TextSecondary.copy(alpha = 0.75f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(24.dp))
            val shownFraction = if (dragging) dragFraction else fraction
            val shownPosition = if (dragging) (durationMs * dragFraction).toInt() else positionMs
            SeekBar(
                fraction = shownFraction,
                onSeekStart = {
                    dragging = true
                    dragFraction = fraction
                },
                onSeekPreview = { dragFraction = it },
                onSeekCommit = {
                    dragging = false
                    onSeek(dragFraction)
                },
            )

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(shownPosition.toLong()), color = TextSecondary, fontSize = 12.sp)
                Text(
                    "-" + formatMs((durationMs - shownPosition).coerceAtLeast(0).toLong()),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(18.dp))
            Controls(
                isPlaying = isPlaying,
                isShuffle = isShuffle,
                repeat = repeat,
                onToggle = onToggle,
                onNext = onNext,
                onPrevious = onPrevious,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                if (nextTitle.isNullOrBlank()) " " else "Next · $nextTitle",
                Modifier.fillMaxWidth(),
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onOpenSource,
                    modifier = Modifier.size(64.dp).described("Go to the list this song plays from"),
                ) {
                    FaderIcon(FIcon.Target, size = 32.dp, tint = TextSecondary)
                }
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(64.dp).described(
                        if (isFavorite) "Remove from favorites" else "Add to favorites",
                    ),
                ) {
                    FaderIcon(
                        FIcon.Heart,
                        size = 32.dp,
                        tint = if (isFavorite) FavoriteRed else TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun fraction0(positionMs: Int, durationMs: Int): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

@Composable
private fun SeekBar(
    fraction: Float,
    onSeekStart: () -> Unit,
    onSeekPreview: (Float) -> Unit,
    onSeekCommit: () -> Unit,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onSeekStart()
                        onSeekPreview((offset.x / size.width).coerceIn(0f, 1f))
                    },
                    onHorizontalDrag = { change, _ ->
                        onSeekPreview((change.position.x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onSeekCommit() },
                    onDragCancel = { onSeekCommit() },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeekStart()
                    onSeekPreview((offset.x / size.width).coerceIn(0f, 1f))
                    onSeekCommit()
                }
            }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
    ) {
        val centerY = size.height / 2f
        val radius = 5f
        drawRoundRect(
            color = Color(0x29FFFFFF),
            topLeft = Offset(0f, centerY - radius / 2f),
            size = Size(size.width, radius),
            cornerRadius = CornerRadius(radius / 2f),
        )
        val filled = size.width * fraction
        if (filled > 0f) {
            drawRoundRect(
                color = Accent,
                topLeft = Offset(0f, centerY - radius / 2f),
                size = Size(filled, radius),
                cornerRadius = CornerRadius(radius / 2f),
            )
        }
        drawCircle(
            color = Color.White,
            radius = 7.dp.toPx(),
            center = Offset(filled.coerceIn(7f, size.width - 7f), centerY),
        )
    }
}

@Composable
private fun Controls(
    isPlaying: Boolean,
    isShuffle: Boolean,
    repeat: Int,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onToggleShuffle,
            modifier = Modifier.described(if (isShuffle) "Shuffle on" else "Shuffle off"),
        ) {
            FaderIcon(
                FIcon.Shuffle,
                size = 24.dp,
                tint = if (isShuffle) Accent else TextSecondary,
            )
        }
        IconButton(onClick = onPrevious, modifier = Modifier.described("Previous track")) {
            FaderIcon(FIcon.Previous, size = 30.dp, tint = TextPrimary)
        }
        Box(
            Modifier
                .size(78.dp)
                .clip(CircleShape)
                .background(Accent)
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (isPlaying) "Pause" else "Play",
                    onClick = onToggle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            FaderIcon(
                if (isPlaying) FIcon.Pause else FIcon.Play,
                size = 36.dp,
                tint = AccentInk,
            )
        }
        IconButton(onClick = onNext, modifier = Modifier.described("Next track")) {
            FaderIcon(FIcon.Next, size = 30.dp, tint = TextPrimary)
        }
        Box(contentAlignment = Alignment.Center) {
            IconButton(
                onClick = onCycleRepeat,
                modifier = Modifier.described(
                    when (repeat) {
                        2 -> "Repeat one"
                        1 -> "Repeat all"
                        else -> "Repeat off"
                    },
                ),
            ) {
                FaderIcon(
                    FIcon.Repeat,
                    size = 24.dp,
                    tint = if (repeat == 0) TextSecondary else Accent,
                )
            }
            if (repeat == 2) {
                Text(
                    "1",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 6.dp),
                    color = Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
