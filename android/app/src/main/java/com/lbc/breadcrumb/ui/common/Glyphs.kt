package com.lbc.breadcrumb.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.lbc.breadcrumb.ui.theme.CrumbBright
import com.lbc.breadcrumb.ui.theme.CrumbDim
import com.lbc.breadcrumb.ui.theme.CrumbFaint
import com.lbc.breadcrumb.ui.theme.CrumbMid

/** A page with a folded corner and a few ruled lines, after the design's PDF tile. */
@Composable
fun DocumentGlyph(modifier: Modifier) {
    val page = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    Canvas(modifier) {
        val fold = size.width * 0.28f
        val corner = CornerRadius(3.dp.toPx())
        drawRoundRect(color = page, size = size, cornerRadius = corner)
        // folded corner, top right
        drawRect(color = ink.copy(alpha = 0.35f), topLeft = Offset(size.width - fold, 0f), size = Size(fold, fold))

        val left = size.width * 0.18f
        val lineH = 2.dp.toPx()
        val widths = listOf(0.44f, 0.64f, 0.58f, 0.64f, 0.36f)
        widths.forEachIndexed { i, w ->
            val y = size.height * (0.36f + i * 0.12f)
            drawRoundRect(
                color = ink,
                topLeft = Offset(left, y),
                size = Size(size.width * w, lineH),
                cornerRadius = CornerRadius(lineH / 2),
            )
        }
    }
}

/** A few ruled lines standing in for a note, as the design's placeholder thumbnails draw one. */
@Composable
fun NoteGlyph(modifier: Modifier) {
    val strong = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val faint = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val lineH = 3.dp.toPx()
        val gap = 4.dp.toPx()
        listOf(0.7f to strong, 1f to faint, 0.88f to faint, 0.94f to faint).forEachIndexed { i, (w, color) ->
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, i * (lineH + gap)),
                size = Size(size.width * w, lineH),
                cornerRadius = CornerRadius(lineH / 2),
            )
        }
    }
}

/** The four crumbs of the logo, faintest to brightest, on the design's 20-unit square. */
private val Crumbs = listOf(
    Triple(Offset(4f, 15.5f), 1.6f, CrumbFaint),
    Triple(Offset(8.4f, 12.4f), 2.1f, CrumbDim),
    Triple(Offset(13.2f, 8.6f), 2.7f, CrumbMid),
    Triple(Offset(17f, 4.2f), 3f, CrumbBright),
)

/** One lap of the trail: every crumb lit once, and a beat of rest. */
private const val LAP_MS = 1_300

/**
 * The crumb trail from the logo: the search field's mark, and its loader.
 *
 * Still, it is the mark. [walking], it is someone following the trail: the
 * crumbs light one after another, faint to bright, like footsteps, and the
 * lap starts again -- what a search looks like while it is out finding things.
 */
@Composable
fun CrumbTrail(modifier: Modifier, walking: Boolean = false) {
    // read while drawing, so a walking trail redraws each frame without recomposing
    val lap: State<Float>? = if (walking) {
        rememberInfiniteTransition(label = "trail").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(LAP_MS, easing = LinearEasing)),
            label = "lap",
        )
    } else {
        null
    }

    Canvas(modifier) {
        val u = size.width / 20f
        val phase = lap?.value
        Crumbs.forEachIndexed { i, (at, radius, color) ->
            val lit = phase?.let { stepLight(it, i) }
            // at rest a walking trail sits dim, and each step lifts one crumb
            val alpha = lit?.let { 0.28f + 0.72f * it } ?: 1f
            val scale = lit?.let { 0.82f + 0.3f * it } ?: 1f
            drawCircle(color.copy(alpha = alpha), radius = radius * u * scale, center = Offset(at.x * u, at.y * u))
        }
    }
}

/**
 * How lit crumb [index] is at [phase] (0..1) of a lap: each of the four
 * steps takes a fifth of the lap, rising and falling around its moment, and
 * the last fifth is a rest before the next lap.
 */
internal fun stepLight(phase: Float, index: Int): Float {
    val step = 1f / 5f
    val peak = step * index + step / 2f
    val distance = kotlin.math.abs(phase - peak) / step
    return (1f - distance).coerceIn(0f, 1f)
}
