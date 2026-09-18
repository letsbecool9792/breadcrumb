package com.lbc.breadcrumb.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lbc.breadcrumb.ui.theme.Bone
import com.lbc.breadcrumb.ui.theme.InkOutline

/**
 * The design's split: mono for every piece of chrome -- counts, dates, source
 * apps, match fragments -- and sans for anything the user saved. That split is
 * what reads as a tool rather than a notes app.
 *
 * The system mono stands in for IBM Plex Mono, as on the capture sheet;
 * bundling the brand faces is its own decision.
 */
internal val MonoFamily = FontFamily.Monospace

internal fun monoStyle(size: TextUnit, color: Color) = TextStyle(fontFamily = MonoFamily, fontSize = size, color = color)

/** "2d · chrome" under a tile or a result. */
internal val metaStyle = monoStyle(10.sp, InkOutline).copy(letterSpacing = 0.3.sp)

/** What the screen says when there is nothing to show: one plain line, and a mono hint. */
@Composable
internal fun Quiet(title: String, hint: String) {
    Column(Modifier.padding(horizontal = 26.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, color = Bone))
        Text(hint, style = monoStyle(11.sp, InkOutline).copy(lineHeight = 18.sp))
    }
}
