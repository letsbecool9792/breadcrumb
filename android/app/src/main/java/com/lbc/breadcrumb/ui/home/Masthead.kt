package com.lbc.breadcrumb.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lbc.breadcrumb.R
import com.lbc.breadcrumb.ui.common.CrumbTrail
import com.lbc.breadcrumb.ui.theme.Ink
import com.lbc.breadcrumb.ui.theme.InkOutline

/**
 * The name, large, at the head of the mosaic -- the one place the app says
 * what it is. It scrolls away with the tiles, and the [CollapsedMasthead]
 * takes its place.
 *
 * @param onLongPress opens the debug list (debug builds only).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Masthead(kept: String, onLongPress: (() -> Unit)?, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.home_wordmark),
            style = serif(58.sp, lineHeight = 58.sp),
            modifier = if (onLongPress == null) Modifier else Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            CrumbTrail(Modifier.size(15.dp))
            Text(kept.uppercase(), style = monoStyle(11.sp, InkOutline).copy(letterSpacing = 1.4.sp))
        }
    }
}

/**
 * The strip the masthead shrinks into once it has scrolled out of sight:
 * the name small in the serif, the count in mono. [shown] runs 0 to 1 as the
 * masthead leaves, so the two hand over rather than cut.
 */
@Composable
internal fun CollapsedMasthead(kept: String, shown: Float, modifier: Modifier = Modifier) {
    Row(
        modifier
            .graphicsLayer {
                alpha = shown
                // settles down into place as it arrives
                translationY = (1f - shown) * -6.dp.toPx()
            }
            .fillMaxWidth()
            .background(Ink)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.home_wordmark), style = serif(22.sp, lineHeight = 22.sp))
        Text(kept, style = monoStyle(11.sp, InkOutline))
    }
}

/**
 * How far the masthead has scrolled away, 0 to 1: nothing while it is fully
 * in view, all of it once the grid has moved past it. The strip only starts
 * to show over the last part, so the two are never both legible.
 */
internal fun mastheadCollapse(firstVisibleIndex: Int, firstVisibleOffsetPx: Int, mastheadHeightPx: Int): Float {
    if (firstVisibleIndex > 0) return 1f
    if (mastheadHeightPx <= 0) return 0f
    val scrolled = firstVisibleOffsetPx.toFloat() / mastheadHeightPx
    return ((scrolled - 0.45f) / 0.45f).coerceIn(0f, 1f)
}
