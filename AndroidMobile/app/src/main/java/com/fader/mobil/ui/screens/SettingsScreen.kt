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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fader.mobil.ui.components.DividerLine
import com.fader.mobil.ui.components.FaderCard
import com.fader.mobil.ui.components.FaderIcon
import com.fader.mobil.ui.components.FIcon
import com.fader.mobil.ui.components.ScreenTitle
import com.fader.mobil.ui.components.SectionHeader
import com.fader.mobil.ui.theme.Accent
import com.fader.mobil.ui.theme.Success
import com.fader.mobil.ui.theme.Surface2
import com.fader.mobil.ui.theme.TextPrimary
import com.fader.mobil.ui.theme.TextSecondary
import com.fader.mobil.ui.theme.TextTertiary

@Composable
fun SettingsScreen(onAddSource: () -> Unit) {
    val context = LocalContext.current
    val announce = com.fader.mobil.data.UiSettings.announceTrack
    val gapless = com.fader.mobil.data.UiSettings.gapless
    var crossfade by remember { mutableStateOf(com.fader.mobil.data.SettingsStore.fade(context)) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        ScreenTitle("Settings")

        SectionHeader("Accessibility")
        FaderCard {
            ToggleRow("Announce new track", announce) {
                com.fader.mobil.data.UiSettings.setAnnounce(context, it)
            }
        }

        SectionHeader("Playback")
        FaderCard {
            ValueRow(
                label = "Fade between tracks",
                value = if (crossfade == 0) "Off" else "$crossfade s",
                onClick = {
                    val next = com.fader.mobil.core.CrossfadePolicy.next(crossfade)
                    crossfade = next
                    com.fader.mobil.core.FaderPlayer.setCrossfade(next)
                    com.fader.mobil.data.SettingsStore.setFade(context, next)
                },
            )
            DividerLine(insetStart = 14.dp)
            ToggleRow("Gapless playback", gapless) {
                com.fader.mobil.data.UiSettings.setGapless(context, it)
                com.fader.mobil.core.FaderPlayer.setGapless(it)
            }
        }

        SectionHeader("Sources")
        FaderCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Success),
                )
                Spacer(Modifier.width(12.dp))
                Text("WebDAV share", Modifier.weight(1f), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("HTTPS", color = TextSecondary, fontSize = 14.sp)
            }
            DividerLine(insetStart = 14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button, onClickLabel = "Add source") { onAddSource() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    FaderIcon(FIcon.Plus, size = 18.dp, tint = TextPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Text("Add source", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                uncheckedThumbColor = Color(0xFFB0B0B5),
                uncheckedTrackColor = Surface2,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun ValueRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClickLabel = label) { onClick?.invoke() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = TextPrimary, fontSize = 16.sp)
        Text(value, color = TextSecondary, fontSize = 16.sp)
        Spacer(Modifier.width(6.dp))
        FaderIcon(FIcon.ChevronRight, size = 14.dp, tint = TextTertiary)
    }
}
