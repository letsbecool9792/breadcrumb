package com.lbc.breadcrumb.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lbc.breadcrumb.ui.theme.Bone
import com.lbc.breadcrumb.ui.theme.InkOutline
import com.lbc.breadcrumb.ui.theme.Mono
import com.lbc.breadcrumb.ui.theme.Sans
import com.lbc.breadcrumb.ui.theme.Serif

// A Text given a style takes nothing from the theme's, so every style here
// names its face. One helper per face keeps that from being forgotten.

internal val MonoFamily = Mono

/**
 * The app's voice, and a memory once it is opened: the wordmark, a detail's
 * title, a note read in full. Never a title among others on the page -- those
 * are [sans], whatever kind of memory they belong to.
 */
internal fun serif(size: TextUnit, color: Color = Bone, lineHeight: TextUnit = size * 1.15f) =
    TextStyle(fontFamily = Serif, fontSize = size, lineHeight = lineHeight, color = color)

/** Everything on the page: tile and row titles, captions, notes, the model's summaries. */
internal fun sans(
    size: TextUnit,
    color: Color = Bone,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
) = TextStyle(fontFamily = Sans, fontSize = size, fontWeight = weight, lineHeight = lineHeight, color = color)

/** Chrome: counts, dates, sources, match fragments. */
internal fun monoStyle(size: TextUnit, color: Color) = TextStyle(fontFamily = Mono, fontSize = size, color = color)

/** "2d · chrome" under a tile or a result. */
internal val metaStyle = monoStyle(10.sp, InkOutline).copy(letterSpacing = 0.3.sp)

/** What the screen says when there is nothing to show: one line in the serif, and a mono hint. */
@Composable
internal fun Quiet(title: String, hint: String) {
    Column(Modifier.padding(horizontal = 26.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, style = serif(22.sp))
        Text(hint, style = monoStyle(11.sp, InkOutline).copy(lineHeight = 18.sp))
    }
}
