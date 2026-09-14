package com.lbc.breadcrumb.ui.capture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lbc.breadcrumb.ui.theme.CrumbBright
import com.lbc.breadcrumb.ui.theme.CrumbDim
import com.lbc.breadcrumb.ui.theme.CrumbFaint
import com.lbc.breadcrumb.ui.theme.CrumbMid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CrumbBounce = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f)

/**
 * The crumb trail from the logo. On a save the brightest crumb drops in with a
 * bounce and the trail behind it settles, staggered as in the design; in every
 * other state the trail sits dim and still.
 *
 * Geometry is authored on a 46-unit square, as in the design board, and scaled.
 */
@Composable
fun CrumbMark(play: Boolean, size: Dp = 46.dp) {
    val lead = remember { Animatable(0f) }
    // the trail behind the lead crumb, nearest first
    val trail = remember { List(3) { Animatable(0f) } }

    LaunchedEffect(play) {
        if (!play) return@LaunchedEffect
        launch { lead.animateTo(1f, tween(700, easing = CrumbBounce)) }
        listOf(90L, 160L, 220L).forEachIndexed { i, wait ->
            launch {
                delay(wait)
                trail[i].animateTo(1f, tween(420))
            }
        }
    }

    Canvas(Modifier.size(size)) {
        val u = this.size.width / 46f

        fun crumb(cx: Float, cy: Float, r: Float, color: Color, alpha: Float, scale: Float, dy: Float = 0f) {
            drawCircle(
                color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = r * u * scale,
                center = Offset(cx * u, cy * u + dy * u),
            )
        }

        if (!play) {
            crumb(8f, 37f, 3.2f, CrumbFaint, 0.35f, 1f)
            crumb(17f, 30f, 4.4f, CrumbDim, 0.35f, 1f)
            crumb(27.5f, 21f, 5.8f, CrumbMid, 0.35f, 1f)
            crumb(37f, 10f, 7f, CrumbBright, 0.35f, 1f)
            return@Canvas
        }

        val t0 = trail[0].value
        val t1 = trail[1].value
        val t2 = trail[2].value
        crumb(8f, 37f, 3.2f, CrumbFaint, t2, 0.5f + 0.5f * t2)
        crumb(17f, 30f, 4.4f, CrumbDim, t1, 0.5f + 0.5f * t1)
        crumb(27.5f, 21f, 5.8f, CrumbMid, t0, 0.5f + 0.5f * t0)

        val p = lead.value
        crumb(37f, 10f, 7f, CrumbBright, alpha = p * 2.2f, scale = 0.4f + 0.6f * p, dy = -26f * (1f - p))
    }
}
