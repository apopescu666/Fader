package com.fader.mobil.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fader.mobil.data.Source
import com.fader.mobil.data.SourceKind
import com.fader.mobil.data.Track
import com.fader.mobil.ui.theme.Accent
import com.fader.mobil.ui.formatMs
import com.fader.mobil.ui.theme.AccentInk
import com.fader.mobil.ui.theme.FieldBg
import com.fader.mobil.ui.theme.Separator
import com.fader.mobil.ui.theme.Surface1
import com.fader.mobil.ui.theme.Surface2
import com.fader.mobil.ui.theme.TextPrimary
import com.fader.mobil.ui.theme.FavoriteRed
import com.fader.mobil.ui.theme.TextSecondary
import com.fader.mobil.ui.theme.TextTertiary

/** Adds a TalkBack label to an icon-only control. */
fun Modifier.described(label: String): Modifier =
    this.semantics { contentDescription = label }

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = TextPrimary,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        lineHeight = 41.sp,
    )
}

@Composable
fun SearchField(placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(FieldBg)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FaderIcon(FIcon.Search, size = 17.dp, tint = TextTertiary)
        Spacer(Modifier.width(7.dp))
        Text(placeholder, color = TextTertiary, fontSize = 17.sp)
    }
}

/** Three animated bars marking the track that is playing. */
@Composable
fun NowPlayingBars(tint: Color = Accent, height: Dp = 16.dp) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "bars")
    val slow: androidx.compose.animation.core.InfiniteRepeatableSpec<Float> =
        androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween<Float>(
                520,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        )
    val fast: androidx.compose.animation.core.InfiniteRepeatableSpec<Float> =
        androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween<Float>(
                360,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        )
    val h1 by transition.animateFloat(0.35f, 1f, slow, label = "h1")
    val h2 by transition.animateFloat(0.6f, 1f, fast, label = "h2")
    val h3 by transition.animateFloat(0.3f, 0.85f, slow, label = "h3")

    Row(
        Modifier.height(height),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(h1, h2, h3).forEach { factor ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(height * factor)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint),
            )
        }
    }
}

@Composable
fun SearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    autoFocus: Boolean = false,
) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(FieldBg)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FaderIcon(FIcon.Search, size = 17.dp, tint = TextTertiary)
        Spacer(Modifier.width(7.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, color = TextTertiary, fontSize = 16.sp)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 16.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(32.dp)) {
                FaderIcon(FIcon.Close, size = 14.dp, tint = TextSecondary)
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 26.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.9.sp,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                text = action,
                color = Accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = onAction != null) { onAction?.invoke() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun FaderCard(
    modifier: Modifier = Modifier,
    corner: androidx.compose.ui.unit.Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(corner),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, com.fader.mobil.ui.theme.CardStroke),
        modifier = modifier,
    ) {
        Column(content = content)
    }
}

@Composable
fun DividerLine(insetStart: androidx.compose.ui.unit.Dp = 14.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = insetStart)
            .height(1.dp)
            .background(Separator),
    )
}

private fun iconFor(kind: SourceKind): FIcon = when (kind) {
    SourceKind.Local -> FIcon.Phone
    SourceKind.WebDav -> FIcon.Cloud
}

private fun gradientFor(kind: SourceKind): Brush = when (kind) {
    SourceKind.Local -> Brush.linearGradient(listOf(Color(0xFF5AA9FF), Color(0xFF0A6CD6)))
    SourceKind.WebDav -> Brush.linearGradient(listOf(Color(0xFF4FD1C5), Color(0xFF17998F)))
}

private fun inkFor(kind: SourceKind): Color = when (kind) {
    SourceKind.WebDav -> AccentInk
    else -> Color.White
}

@Composable
fun SourceRow(source: Source, onClick: () -> Unit, showDivider: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = "Open ${source.name}", role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(gradientFor(source.kind)),
            contentAlignment = Alignment.Center,
        ) {
            FaderIcon(iconFor(source.kind), size = 20.dp, tint = inkFor(source.kind))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(source.name, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(source.detail, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FaderIcon(FIcon.ChevronRight, size = 14.dp, tint = TextTertiary)
    }
    if (showDivider) DividerLine(insetStart = 62.dp)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    showDivider: Boolean = true,
    isCurrent: Boolean = false,
    isFavorite: Boolean = false,
    selected: Boolean = false,
    highlighted: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val maxSwipe = with(density) { 148.dp.toPx() }
    val trigger = with(density) { 92.dp.toPx() }
    val offsetX = remember(track.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxWidth()) {
        if (onSwipeLeft != null) {
            val progress = (-offsetX.value / maxSwipe).coerceIn(0f, 1f)
            Row(
                Modifier
                    .matchParentSize()
                    .background(Accent.copy(alpha = 0.92f * progress))
                    .padding(end = 18.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FaderIcon(FIcon.List, size = 18.dp, tint = AccentInk.copy(alpha = progress))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Playlist",
                    color = AccentInk.copy(alpha = progress),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .heightIn(min = 68.dp)
                .background(
                when {
                    selected -> Accent.copy(alpha = 0.16f)
                    highlighted -> Accent.copy(alpha = 0.22f)
                    // The playing row stays visibly marked, not just for 2.5 s.
                    isCurrent -> Accent.copy(alpha = 0.13f)
                    else -> Color.Transparent
                },
            )
                .then(
                    if (onLongClick != null) {
                        Modifier.combinedClickable(
                            onClickLabel = "Play ${track.title}",
                            role = Role.Button,
                            onLongClickLabel = "Select ${track.title}",
                            onLongClick = onLongClick,
                            onClick = onClick,
                        )
                    } else {
                        Modifier.clickable(
                            onClickLabel = "Play ${track.title}",
                            role = Role.Button,
                            onClick = onClick,
                        )
                    },
                )
                .then(
                    if (onSwipeLeft != null) {
                        Modifier.pointerInput(track.id) {
                            detectHorizontalDragGestures(
                                onDragStart = { },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    val next = (offsetX.value + dragAmount).coerceIn(-maxSwipe, 0f)
                                    scope.launch { offsetX.snapTo(next) }
                                },
                                onDragEnd = {
                                    if (offsetX.value < -trigger) {
                                        scope.launch {
                                            offsetX.animateTo(0f)
                                            onSwipeLeft()
                                        }
                                    } else {
                                        scope.launch { offsetX.animateTo(0f) }
                                    }
                                },
                                onDragCancel = { scope.launch { offsetX.animateTo(0f) } },
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track.art, size = 56.dp, corner = 10.dp, artKey = track.artKey, artUri = track.artUri)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    color = if (isCurrent) Accent else TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (isCurrent) "Playing · ${track.artist}" else "${track.artist} · ${track.album}",
                    color = if (isCurrent) Accent.copy(alpha = 0.8f) else TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (onToggleFavorite != null) {
                val heartScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isFavorite) 1.15f else 1f,
                    animationSpec = androidx.compose.animation.core.spring(
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
                    ),
                    label = "heart",
                )
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .size(40.dp)
                        .described(if (isFavorite) "Remove from favorites" else "Add to favorites"),
                ) {
                    FaderIcon(
                        FIcon.Heart,
                        size = 18.dp,
                        tint = if (isFavorite) FavoriteRed else TextTertiary,
                        modifier = Modifier.scale(heartScale),
                    )
                }
            }
            when {
                selected -> {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        FaderIcon(FIcon.Check, size = 14.dp, tint = AccentInk)
                    }
                    Spacer(Modifier.width(8.dp))
                }

                trailing != null -> trailing()

                isCurrent -> {
                    NowPlayingBars()
                    Spacer(Modifier.width(10.dp))
                }

                else -> {
                    Text(track.duration, color = TextTertiary, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
    if (showDivider) DividerLine(insetStart = 78.dp)
}

@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    positionMs: Int,
    durationMs: Int,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        color = Surface1.copy(alpha = 0.98f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClickLabel = "Open now playing", onClick = onOpen),
    ) {
        Column {
        Row(
            Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track.art, size = 44.dp, corner = 9.dp, artKey = track.artKey, artUri = track.artUri)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${track.artist} · ${track.album}", color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(
                onClick = onPrevious,
                modifier = Modifier.size(40.dp).described("Previous track"),
            ) {
                FaderIcon(FIcon.Previous, size = 18.dp, tint = TextPrimary)
            }
            IconButton(
                onClick = onToggle,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Accent)
                    .described(if (isPlaying) "Pause" else "Play"),
            ) {
                FaderIcon(
                    if (isPlaying) FIcon.Pause else FIcon.Play,
                    size = 16.dp,
                    tint = AccentInk,
                )
            }
            IconButton(
                onClick = onNext,
                modifier = Modifier.size(40.dp).described("Next track"),
            ) {
                FaderIcon(FIcon.Next, size = 18.dp, tint = TextPrimary)
            }
        }
        val fraction = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Surface2),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(2.dp)
                    .background(Accent),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatMs(positionMs.toLong()), color = TextTertiary, fontSize = 10.sp)
            Text(formatMs(durationMs.toLong()), color = TextTertiary, fontSize = 10.sp)
        }
        }
    }
}

enum class Tab(val label: String, val icon: FIcon) {
    Library("Library", FIcon.Albums),
    Queue("Queue", FIcon.Lines),
    Settings("Settings", FIcon.Sliders),
}

@Composable
fun FaderTabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(
        containerColor = Color(0xFA121214),
        tonalElevation = 0.dp,
        modifier = Modifier.height(64.dp),
    ) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { FaderIcon(tab.icon, size = 24.dp) },
                label = { Text(tab.label, fontSize = 10.sp, fontWeight = FontWeight.Medium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Accent,
                    selectedTextColor = Accent,
                    unselectedIconColor = TextTertiary,
                    unselectedTextColor = TextTertiary,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (active) Accent else Surface1,
        contentColor = if (active) AccentInk else TextSecondary,
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier
            .height(34.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

@Composable
fun IconPill(
    icon: FIcon,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        color = if (active) Accent.copy(alpha = 0.18f) else Surface1,
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier
            .size(34.dp)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            FaderIcon(icon, size = 16.dp, tint = if (active) Accent else TextSecondary)
        }
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(24.dp),
        color = TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
}
