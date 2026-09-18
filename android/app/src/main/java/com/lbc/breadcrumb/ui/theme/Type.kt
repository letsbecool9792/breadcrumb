package com.lbc.breadcrumb.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.lbc.breadcrumb.R

// The design canvas's faces, bundled (res/font, licences in assets/licenses --
// all three are OFL). One face per job, and the split is the point:
//
//   Serif -- what a thing is called: titles, the wordmark, a note set as a quote.
//   Sans  -- anything the user saved: captions, notes, the model's summaries.
//   Mono  -- chrome: counts, dates, source apps, match fragments.
//
// Mono chrome around saved words is what reads as a tool rather than a notes
// app; the serif gives the archive its voice.

/** Instrument Serif, the serif partner of the canvas's Instrument Sans. Regular and italic only. */
val Serif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

/**
 * Instrument Sans: one variable file serving every weight used here. Setting
 * a variable font's weight is still experimental in Compose; the fallback if
 * it ever breaks is the same file at its default weight, not a missing font.
 */
@OptIn(ExperimentalTextApi::class)
val Sans = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(
            R.font.instrument_sans,
            weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    }
)

/** IBM Plex Mono, as drawn on the canvas. */
val Mono = FontFamily(
    Font(R.font.ibm_plex_mono, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

private val material = Typography()

private fun TextStyle.inSerif() = copy(fontFamily = Serif, fontWeight = FontWeight.Normal)
private fun TextStyle.inSans() = copy(fontFamily = Sans)

/**
 * Material's scale in the brand faces, so any Text that does not name a style
 * -- the capture sheet's, the debug list's -- is set in Instrument Sans, and
 * Material's display and headline sizes in the serif.
 */
val Typography = Typography(
    displayLarge = material.displayLarge.inSerif(),
    displayMedium = material.displayMedium.inSerif(),
    displaySmall = material.displaySmall.inSerif(),
    headlineLarge = material.headlineLarge.inSerif(),
    headlineMedium = material.headlineMedium.inSerif(),
    headlineSmall = material.headlineSmall.inSerif(),
    titleLarge = material.titleLarge.inSans(),
    titleMedium = material.titleMedium.inSans(),
    titleSmall = material.titleSmall.inSans(),
    bodyLarge = material.bodyLarge.inSans(),
    bodyMedium = material.bodyMedium.inSans(),
    bodySmall = material.bodySmall.inSans(),
    labelLarge = material.labelLarge.inSans(),
    labelMedium = material.labelMedium.inSans(),
    labelSmall = material.labelSmall.inSans(),
)
