package com.fader.mobil.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Line icons drawn with the canvas so the app needs no icon dependency. */
enum class FIcon {
    Play, Pause, Previous, Next,
    Search, ChevronLeft, ChevronRight, ChevronDown, More,
    Plus, Close, Check, Delete, Folder, Server, Phone, Cloud,
    Albums, Queue, Sliders, Sort,
    Shuffle, Repeat, Volume, Airplay, List, Heart, Note, Lines, Target,
}

@Composable
fun FaderIcon(
    icon: FIcon,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
) {
    Canvas(modifier.size(size)) { drawIcon(icon, tint) }
}

private fun DrawScope.drawIcon(icon: FIcon, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = w * 0.075f
    val line = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun p(x: Float, y: Float) = Offset(x * w, y * h)

    when (icon) {
        FIcon.Play -> {
            val path = Path().apply {
                moveTo(0.36f * w, 0.16f * h)
                lineTo(0.82f * w, 0.5f * h)
                lineTo(0.36f * w, 0.84f * h)
                close()
            }
            drawPath(path, color)
        }

        FIcon.Pause -> {
            drawRoundRect(color, p(0.30f, 0.18f), Size(0.14f * w, 0.64f * h), CornerRadius(0.045f * w))
            drawRoundRect(color, p(0.56f, 0.18f), Size(0.14f * w, 0.64f * h), CornerRadius(0.045f * w))
        }

        FIcon.Previous -> {
            val path = Path().apply {
                moveTo(0.72f * w, 0.18f * h)
                lineTo(0.36f * w, 0.5f * h)
                lineTo(0.72f * w, 0.82f * h)
                close()
            }
            drawPath(path, color)
            drawRoundRect(color, p(0.26f, 0.18f), Size(0.075f * w, 0.64f * h), CornerRadius(0.03f * w))
        }

        FIcon.Next -> {
            val path = Path().apply {
                moveTo(0.28f * w, 0.18f * h)
                lineTo(0.64f * w, 0.5f * h)
                lineTo(0.28f * w, 0.82f * h)
                close()
            }
            drawPath(path, color)
            drawRoundRect(color, p(0.665f, 0.18f), Size(0.075f * w, 0.64f * h), CornerRadius(0.03f * w))
        }

        FIcon.Search -> {
            drawCircle(color, 0.27f * w, p(0.43f, 0.43f), style = line)
            drawLine(color, p(0.63f, 0.63f), p(0.84f, 0.84f), stroke, StrokeCap.Round)
        }

        FIcon.ChevronLeft -> {
            drawPath(Path().apply {
                moveTo(0.62f * w, 0.18f * h); lineTo(0.34f * w, 0.5f * h); lineTo(0.62f * w, 0.82f * h)
            }, color, style = line)
        }

        FIcon.ChevronRight -> {
            drawPath(Path().apply {
                moveTo(0.38f * w, 0.18f * h); lineTo(0.66f * w, 0.5f * h); lineTo(0.38f * w, 0.82f * h)
            }, color, style = line)
        }

        FIcon.ChevronDown -> {
            drawPath(Path().apply {
                moveTo(0.22f * w, 0.36f * h); lineTo(0.5f * w, 0.64f * h); lineTo(0.78f * w, 0.36f * h)
            }, color, style = line)
        }

        FIcon.More -> {
            for (x in listOf(0.22f, 0.5f, 0.78f)) drawCircle(color, 0.075f * w, p(x, 0.5f))
        }

        FIcon.Plus -> {
            drawLine(color, p(0.5f, 0.2f), p(0.5f, 0.8f), stroke, StrokeCap.Round)
            drawLine(color, p(0.2f, 0.5f), p(0.8f, 0.5f), stroke, StrokeCap.Round)
        }

        FIcon.Delete -> {
            drawRoundRect(color, p(0.32f, 0.28f), Size(0.36f * w, 0.54f * h), CornerRadius(0.05f * w), style = line)
            drawLine(color, p(0.24f, 0.24f), p(0.76f, 0.24f), stroke, StrokeCap.Round)
            drawLine(color, p(0.42f, 0.16f), p(0.58f, 0.16f), stroke, StrokeCap.Round)
            drawLine(color, p(0.44f, 0.4f), p(0.44f, 0.7f), stroke, StrokeCap.Round)
            drawLine(color, p(0.56f, 0.4f), p(0.56f, 0.7f), stroke, StrokeCap.Round)
        }

        FIcon.Check -> {
            drawPath(Path().apply {
                moveTo(0.22f * w, 0.52f * h)
                lineTo(0.42f * w, 0.72f * h)
                lineTo(0.78f * w, 0.3f * h)
            }, color, style = line)
        }

        FIcon.Close -> {
            drawLine(color, p(0.26f, 0.26f), p(0.74f, 0.74f), stroke, StrokeCap.Round)
            drawLine(color, p(0.74f, 0.26f), p(0.26f, 0.74f), stroke, StrokeCap.Round)
        }

        FIcon.Folder -> {
            drawRoundRect(color, p(0.12f, 0.3f), Size(0.76f * w, 0.46f * h), CornerRadius(0.08f * w), style = line)
            drawRoundRect(color, p(0.12f, 0.18f), Size(0.36f * w, 0.2f * h), CornerRadius(0.06f * w), style = line)
        }

        FIcon.Server -> {
            drawRoundRect(color, p(0.15f, 0.14f), Size(0.7f * w, 0.3f * h), CornerRadius(0.07f * w), style = line)
            drawRoundRect(color, p(0.15f, 0.56f), Size(0.7f * w, 0.3f * h), CornerRadius(0.07f * w), style = line)
            drawCircle(color, 0.045f * w, p(0.3f, 0.29f))
            drawCircle(color, 0.045f * w, p(0.3f, 0.71f))
        }

        FIcon.Phone -> {
            drawRoundRect(color, p(0.31f, 0.12f), Size(0.38f * w, 0.76f * h), CornerRadius(0.1f * w), style = line)
            drawLine(color, p(0.43f, 0.79f), p(0.57f, 0.79f), stroke, StrokeCap.Round)
        }

        FIcon.Cloud -> {
            drawCircle(color, 0.15f * w, p(0.33f, 0.57f))
            drawCircle(color, 0.2f * w, p(0.51f, 0.47f))
            drawCircle(color, 0.15f * w, p(0.68f, 0.59f))
            drawRoundRect(color, p(0.33f, 0.57f), Size(0.35f * w, 0.15f * h), CornerRadius(0.07f * w))
        }

        FIcon.Albums -> {
            drawRoundRect(color, p(0.3f, 0.12f), Size(0.56f * w, 0.56f * h), CornerRadius(0.11f * w), style = line)
            drawRoundRect(color, p(0.14f, 0.32f), Size(0.56f * w, 0.56f * h), CornerRadius(0.11f * w), style = line)
        }

        FIcon.Target -> {
            val center = p(0.46f, 0.44f)
            drawCircle(color, 0.30f * w, center, style = line)
            drawCircle(color, 0.13f * w, center, style = line)
            // Arrow into the middle, head at the top right.
            drawLine(color, p(0.44f, 0.50f), p(0.80f, 0.20f), stroke, StrokeCap.Round)
            val head = Path().apply {
                moveTo(0.83f * w, 0.15f * h)
                lineTo(0.62f * w, 0.22f * h)
                lineTo(0.78f * w, 0.36f * h)
                close()
            }
            drawPath(head, color)
            // Stand.
            drawLine(color, p(0.40f, 0.74f), p(0.52f, 0.74f), stroke, StrokeCap.Round)
            drawLine(color, p(0.40f, 0.74f), p(0.30f, 0.86f), stroke, StrokeCap.Round)
            drawLine(color, p(0.52f, 0.74f), p(0.62f, 0.86f), stroke, StrokeCap.Round)
            drawLine(color, p(0.30f, 0.86f), p(0.62f, 0.86f), stroke, StrokeCap.Round)
        }

        FIcon.Lines -> {
            drawLine(color, p(0.18f, 0.28f), p(0.82f, 0.28f), stroke, StrokeCap.Round)
            drawLine(color, p(0.18f, 0.5f), p(0.82f, 0.5f), stroke, StrokeCap.Round)
            drawLine(color, p(0.18f, 0.72f), p(0.82f, 0.72f), stroke, StrokeCap.Round)
        }

        // Playlist: a short list plus a music note.
        FIcon.List -> {
            drawLine(color, p(0.08f, 0.3f), p(0.44f, 0.3f), stroke, StrokeCap.Round)
            drawLine(color, p(0.08f, 0.5f), p(0.44f, 0.5f), stroke, StrokeCap.Round)
            drawLine(color, p(0.08f, 0.7f), p(0.34f, 0.7f), stroke, StrokeCap.Round)
            drawLine(color, p(0.66f, 0.22f), p(0.66f, 0.68f), stroke, StrokeCap.Round)
            drawCircle(color, 0.085f * w, p(0.575f, 0.705f))
            drawLine(color, p(0.66f, 0.22f), p(0.82f, 0.17f), stroke, StrokeCap.Round)
        }

        // Queue: a short list plus a play triangle.
        FIcon.Queue -> {
            drawLine(color, p(0.08f, 0.24f), p(0.44f, 0.24f), stroke, StrokeCap.Round)
            drawLine(color, p(0.08f, 0.5f), p(0.44f, 0.5f), stroke, StrokeCap.Round)
            drawLine(color, p(0.08f, 0.76f), p(0.44f, 0.76f), stroke, StrokeCap.Round)
            val play = Path().apply {
                moveTo(0.56f * w, 0.2f * h)
                lineTo(0.94f * w, 0.5f * h)
                lineTo(0.56f * w, 0.8f * h)
                close()
            }
            drawPath(play, color)
        }

        FIcon.Sliders -> {
            drawLine(color, p(0.16f, 0.32f), p(0.84f, 0.32f), stroke, StrokeCap.Round)
            drawCircle(color, 0.09f * w, p(0.63f, 0.32f))
            drawLine(color, p(0.16f, 0.68f), p(0.84f, 0.68f), stroke, StrokeCap.Round)
            drawCircle(color, 0.09f * w, p(0.37f, 0.68f))
        }

        FIcon.Sort -> {
            drawLine(color, p(0.35f, 0.2f), p(0.35f, 0.8f), stroke, StrokeCap.Round)
            drawPath(Path().apply {
                moveTo(0.22f * w, 0.32f * h); lineTo(0.35f * w, 0.18f * h); lineTo(0.48f * w, 0.32f * h)
            }, color, style = line)
            drawLine(color, p(0.65f, 0.8f), p(0.65f, 0.2f), stroke, StrokeCap.Round)
            drawPath(Path().apply {
                moveTo(0.52f * w, 0.68f * h); lineTo(0.65f * w, 0.82f * h); lineTo(0.78f * w, 0.68f * h)
            }, color, style = line)
        }

        FIcon.Shuffle -> {
            val a = Path().apply {
                moveTo(0.14f * w, 0.3f * h)
                cubicTo(0.45f * w, 0.3f * h, 0.55f * w, 0.7f * h, 0.8f * w, 0.7f * h)
            }
            drawPath(a, color, style = line)
            val b = Path().apply {
                moveTo(0.14f * w, 0.7f * h)
                cubicTo(0.32f * w, 0.7f * h, 0.4f * w, 0.42f * h, 0.52f * w, 0.34f * h)
            }
            drawPath(b, color, style = line)
            drawPath(Path().apply {
                moveTo(0.68f * w, 0.58f * h); lineTo(0.84f * w, 0.7f * h); lineTo(0.68f * w, 0.82f * h)
            }, color, style = line)
            drawPath(Path().apply {
                moveTo(0.5f * w, 0.2f * h); lineTo(0.66f * w, 0.34f * h); lineTo(0.5f * w, 0.44f * h)
            }, color, style = line)
        }

        FIcon.Repeat -> {
            drawArc(
                color = color,
                startAngle = 210f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = p(0.2f, 0.2f),
                size = Size(0.6f * w, 0.6f * h),
                style = line,
            )
            drawPath(Path().apply {
                moveTo(0.5f * w, 0.06f * h)
                lineTo(0.66f * w, 0.2f * h)
                lineTo(0.46f * w, 0.24f * h)
                close()
            }, color)
        }

        FIcon.Volume -> {
            val body = Path().apply {
                moveTo(0.12f * w, 0.38f * h)
                lineTo(0.28f * w, 0.38f * h)
                lineTo(0.46f * w, 0.2f * h)
                lineTo(0.46f * w, 0.8f * h)
                lineTo(0.28f * w, 0.62f * h)
                lineTo(0.12f * w, 0.62f * h)
                close()
            }
            drawPath(body, color)
            drawArc(color, -50f, 100f, false, p(0.42f, 0.28f), Size(0.3f * w, 0.44f * h), style = line)
        }

        FIcon.Airplay -> {
            drawRoundRect(color, p(0.12f, 0.16f), Size(0.76f * w, 0.5f * h), CornerRadius(0.08f * w), style = line)
            val tri = Path().apply {
                moveTo(0.5f * w, 0.6f * h)
                lineTo(0.76f * w, 0.88f * h)
                lineTo(0.24f * w, 0.88f * h)
                close()
            }
            drawPath(tri, color)
        }

        FIcon.Heart -> {
            val heart = Path().apply {
                moveTo(0.5f * w, 0.82f * h)
                cubicTo(0.1f * w, 0.55f * h, 0.14f * w, 0.2f * h, 0.34f * w, 0.2f * h)
                cubicTo(0.44f * w, 0.2f * h, 0.5f * w, 0.28f * h, 0.5f * w, 0.32f * h)
                cubicTo(0.5f * w, 0.28f * h, 0.56f * w, 0.2f * h, 0.66f * w, 0.2f * h)
                cubicTo(0.86f * w, 0.2f * h, 0.9f * w, 0.55f * h, 0.5f * w, 0.82f * h)
                close()
            }
            drawPath(heart, color)
        }

        FIcon.Note -> {
            drawCircle(color, 0.11f * w, p(0.38f, 0.74f))
            drawLine(color, p(0.48f, 0.72f), p(0.48f, 0.24f), stroke, StrokeCap.Round)
            drawPath(Path().apply {
                moveTo(0.48f * w, 0.24f * h)
                lineTo(0.76f * w, 0.32f * h)
                lineTo(0.76f * w, 0.44f * h)
                lineTo(0.48f * w, 0.36f * h)
                close()
            }, color)
        }
    }
}
