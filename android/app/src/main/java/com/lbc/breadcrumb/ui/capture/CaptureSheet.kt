package com.lbc.breadcrumb.ui.capture

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lbc.breadcrumb.capture.CaptureUiState
import com.lbc.breadcrumb.ui.theme.CaptureScrim
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SheetEnter = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val SheetCorner = 24.dp
private const val SCRIM_ALPHA = 0.62f

/**
 * Design board 4: a sheet over whatever app the save came from.
 *
 * It stays until dismissed -- Done, swipe down, tap outside, or back. The one
 * exception is undo, which closes the sheet on its own once "Removed" has been
 * seen, since nothing is left to act on. None of the dismissals work while
 * [CaptureUiState.Working]: closing the activity then would revoke a shared
 * file's read grant mid-copy.
 *
 * The scrim and the sheet are driven by one transition, so they always arrive
 * and leave together. (The dim that once slid away after the sheet was the
 * system's cross-task close animation, not this; see
 * CaptureActivity.finishInvisibly.)
 */
@Composable
fun CaptureSheet(
    state: CaptureUiState,
    /** A note the person is writing about what they saved; empty for none. */
    note: String,
    onNoteChange: (String) -> Unit,
    onUndo: () -> Unit,
    onFinished: () -> Unit,
) {
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val transition = rememberTransition(visibility, label = "capture-sheet")
    val shown by transition.animateFloat(
        transitionSpec = {
            if (targetState) tween(420, easing = SheetEnter) else tween(240, easing = FastOutLinearInEasing)
        },
        label = "shown",
    ) { if (it) 1f else 0f }

    val dismissible = state !is CaptureUiState.Working
    fun dismiss() {
        if (dismissible) visibility.targetState = false
    }

    // Finish only after the exit has played out AND a fully clear frame has
    // reached the screen. Finishing on the last animation tick could leave the
    // window's final presented frame still dimmed.
    LaunchedEffect(visibility.isIdle, visibility.currentState) {
        if (visibility.isIdle && !visibility.currentState && !visibility.targetState) {
            withFrameNanos { }
            withFrameNanos { }
            onFinished()
        }
    }

    LaunchedEffect(state) {
        if (state is CaptureUiState.Undone) {
            delay(900)
            dismiss()
        }
    }

    BackHandler { dismiss() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = shown }
                .background(CaptureScrim.copy(alpha = SCRIM_ALPHA))
                .pointerInput(dismissible) { detectTapGestures { dismiss() } },
        )

        SheetSurface(
            shown = { shown },
            modifier = Modifier.align(Alignment.BottomCenter),
            onSwipedAway = ::dismiss,
        ) {
            CaptureSheetContent(
                state = state,
                note = note,
                onNoteChange = onNoteChange,
                onUndo = onUndo,
                onDone = ::dismiss,
            )
        }
    }
}

@Composable
private fun SheetSurface(
    shown: () -> Float,
    modifier: Modifier,
    onSwipedAway: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val swipeThreshold = with(LocalDensity.current) { 96.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }

    Column(
        modifier
            .fillMaxWidth()
            // Read inside the layer block so the slide never recomposes the content.
            .graphicsLayer { translationY = size.height * (1f - shown()) + drag }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> drag = (drag + delta).coerceAtLeast(0f) },
                onDragStopped = {
                    if (drag > swipeThreshold) {
                        onSwipedAway()
                    } else {
                        scope.launch { animate(drag, 0f) { value, _ -> drag = value } }
                    }
                },
            )
            // Without a pointer handler covering the whole sheet, taps on its
            // empty areas would fall through to the scrim and dismiss it.
            .pointerInput(Unit) { detectTapGestures { } }
            .clip(RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner))
            .background(colors.surface)
            .drawBehind { drawTopEdge(colors.outlineVariant) }
            .navigationBarsPadding()
            // rides up on the keyboard while a note is written; the nav bar's
            // share of the keyboard's height is already taken, so not twice
            .imePadding()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 14.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.outlineVariant),
            )
        }
        content()
    }
}

/**
 * The design has a hairline along the sheet's top edge only: the two corner
 * arcs and the line between them, inset by half the stroke so it sits exactly
 * inside the sheet's rounded clip.
 */
private fun DrawScope.drawTopEdge(color: Color) {
    val stroke = 1.dp.toPx()
    val half = stroke / 2
    val r = SheetCorner.toPx() - half
    val path = Path().apply {
        moveTo(half, half + r)
        arcTo(Rect(half, half, half + 2 * r, half + 2 * r), 180f, 90f, false)
        lineTo(size.width - half - r, half)
        arcTo(Rect(size.width - half - 2 * r, half, size.width - half, half + 2 * r), 270f, 90f, false)
    }
    drawPath(path, color, style = Stroke(width = stroke))
}
