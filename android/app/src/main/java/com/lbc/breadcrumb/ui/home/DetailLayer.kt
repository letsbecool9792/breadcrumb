package com.lbc.breadcrumb.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.lbc.breadcrumb.ui.theme.Ink
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The shared-transition scope the screen runs in, for a picture to fly between
 * its tile and the open sheet. Null outside one -- a preview, a test -- and
 * then pictures simply stay where they are.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The id of the memory open in the sheet, if any: its tile gives up its picture while it is. */
internal val LocalOpenedId = staticCompositionLocalOf<String?> { null }

/** The key a memory's picture travels under between its tile, its result row and its sheet. */
internal fun pictureKey(id: String) = "picture-$id"

/** The sheet's own curve: quick off the mark, settling gently, as things slow as they arrive. */
private val Settle = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private const val OPEN_MS = 420
private const val CLOSE_MS = 300

/** How far down, as a share of its height, a sheet must be pulled for letting go to dismiss it. */
private const val DISMISS_AT = 0.22f

/**
 * A memory's picture where it sits in a tile or a result row. While that
 * memory is open, the picture is lent to the sheet -- it flies up into it,
 * and back down when the sheet closes. [content] draws the picture, given
 * the modifier that makes it travel.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun TravellingPicture(memoryId: String, content: @Composable (Modifier) -> Unit) {
    val shared = LocalSharedTransition.current
    if (shared == null) {
        content(Modifier)
        return
    }
    AnimatedVisibility(
        visible = LocalOpenedId.current != memoryId,
        enter = fadeIn(tween(OPEN_MS)),
        exit = fadeOut(tween(OPEN_MS)),
    ) {
        with(shared) {
            content(Modifier.sharedElement(rememberSharedContentState(pictureKey(memoryId)), this@AnimatedVisibility))
        }
    }
}

/**
 * The same picture, in the open sheet. The other end of [TravellingPicture].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.travelling(memoryId: String, scope: AnimatedVisibilityScope?): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    scope ?: return this
    return with(shared) { this@travelling.sharedElement(rememberSharedContentState(pictureKey(memoryId)), scope) }
}

/**
 * A sheet over the screen, drawn in the screen's own composition -- unlike
 * Material's, which lives in a window of its own -- so a picture can travel
 * into it from its tile.
 *
 * Never the whole screen: at most [maxFraction] of it, the mosaic or list
 * it was opened from dimmed but in sight above. Dismissed by Back, by a tap
 * on what it dims, or by dragging it down -- from its grabber, or from the
 * top of its content once that is scrolled back up.
 *
 * @param content given the visibility scope that pictures travel within.
 */
@Composable
internal fun <T : Any> SheetLayer(
    item: T?,
    onDismiss: () -> Unit,
    maxFraction: Float = 0.86f,
    content: @Composable ColumnScope.(item: T, scope: AnimatedVisibilityScope) -> Unit,
) {
    // the item being closed stays drawn while the sheet leaves
    var shown by remember { mutableStateOf(item) }
    if (item != null) shown = item

    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

    AnimatedVisibility(visible = item != null, enter = EnterTransition.None, exit = ExitTransition.None) {
        val current = shown ?: return@AnimatedVisibility
        val visibility = this
        val scope = rememberCoroutineScope()
        // how far the sheet has been dragged down, in pixels
        var drag by remember { mutableFloatStateOf(0f) }
        var sheetHeight by remember { mutableIntStateOf(1) }

        BackHandler(onBack = onDismiss)

        // a tap under the thumb as a pull crosses the point where letting go dismisses
        val haptics = LocalHapticFeedback.current
        LaunchedEffect(Unit) {
            snapshotFlow { drag > sheetHeight * DISMISS_AT }
                .distinctUntilChanged()
                .drop(1)
                .collect { past -> if (past) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
        }

        fun release(velocity: Float) {
            if (drag > sheetHeight * DISMISS_AT || velocity > 1_600f) {
                onDismiss()
            } else {
                scope.launch { animate(drag, 0f, animationSpec = spring(stiffness = 500f)) { value, _ -> drag = value } }
            }
        }

        // Content scrolled to its top hands a further pull down to the sheet;
        // a push back up is the sheet's first, until it is home.
        val handOff = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < 0 && drag > 0f) {
                        val used = maxOf(available.y, -drag)
                        drag += used
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0 && source == NestedScrollSource.UserInput) {
                        drag += available.y
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (drag > 0f) {
                        release(available.y)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            // what the sheet dims, and closes when tapped; it lightens as the sheet is pulled away
            Box(
                Modifier
                    .fillMaxSize()
                    .animateEnterExit(enter = fadeIn(tween(OPEN_MS)), exit = fadeOut(tween(CLOSE_MS)))
                    .graphicsLayer { alpha = 1f - (drag / sheetHeight).coerceIn(0f, 1f) * 0.8f }
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = windowHeight * maxFraction)
                    .animateEnterExit(
                        enter = slideInVertically(tween(OPEN_MS, easing = Settle)) { it },
                        exit = slideOutVertically(tween(CLOSE_MS, easing = Settle)) { it },
                    )
                    .offset { IntOffset(0, drag.roundToInt()) }
                    .onSizeChanged { sheetHeight = it.height.coerceAtLeast(1) }
                    .nestedScroll(handOff)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta -> drag = (drag + delta).coerceAtLeast(0f) },
                        onDragStopped = { velocity -> release(velocity) },
                    )
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    // swallow taps, so one on the sheet does not reach the scrim beneath
                    .pointerInput(Unit) { detectTapGestures { } }
                    .background(Ink),
            ) {
                content(current, visibility)
            }
        }
    }
}
