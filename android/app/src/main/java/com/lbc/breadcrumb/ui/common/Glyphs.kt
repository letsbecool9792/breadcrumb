package com.lbc.breadcrumb.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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

/**
 * The crumb trail from the logo, still: the search field's mark. Authored on
 * the design's 20-unit square.
 */
@Composable
fun CrumbTrail(modifier: Modifier) {
    Canvas(modifier) {
        val u = size.width / 20f
        drawCircle(CrumbFaint, radius = 1.6f * u, center = Offset(4f * u, 15.5f * u))
        drawCircle(CrumbDim, radius = 2.1f * u, center = Offset(8.4f * u, 12.4f * u))
        drawCircle(CrumbMid, radius = 2.7f * u, center = Offset(13.2f * u, 8.6f * u))
        drawCircle(CrumbBright, radius = 3f * u, center = Offset(17f * u, 4.2f * u))
    }
}
