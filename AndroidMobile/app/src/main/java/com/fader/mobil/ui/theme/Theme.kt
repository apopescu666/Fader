package com.fader.mobil.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Bg = Color(0xFF0A0A0C)
val Surface1 = Color(0xFF1C1C1E)
val Surface2 = Color(0xFF2C2C2E)
val TextPrimary = Color(0xFFFFFFFF)
var TextSecondary = Color(0xCFEBEBF5.toInt())
    private set
var TextTertiary = Color(0x9EEBEBF5.toInt())
    private set
val Accent = Color(0xFF4FD1C5)
val AccentInk = Color(0xFF062321)
/** Hearts turn red when the song is in Favorites. */
val FavoriteRed = Color(0xFFE53945)
val CardStroke = Color(0x14FFFFFF)

var Separator = Color(0x38FFFFFF)
    private set
val Success = Color(0xFF34C759)
val FieldBg = Color(0xFF26262A)

private val FaderColors = darkColorScheme(
    primary = Accent,
    onPrimary = AccentInk,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    outline = Separator,
)

private val FaderShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun FaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FaderColors,
        shapes = FaderShapes,
        content = content,
    )
}
