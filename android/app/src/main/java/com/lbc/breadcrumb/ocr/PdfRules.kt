package com.lbc.breadcrumb.ocr

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The judgment calls in reading a PDF. Pure and Android-free, like
 * [OcrRules], so they are covered by JVM tests.
 */
object PdfRules {

    /**
     * Most text kept from one PDF. Enough for the first dozen pages of a
     * paper or a set of notes -- what someone remembers a document by -- and
     * small enough that a row stays far from SQLite's 2 MB cursor window and a
     * batch of ten stays a modest request. The server reads the first 4,000
     * characters and embeds 6,000; the word indexes, here and there, take it all.
     */
    const val MAX_CHARS = 20_000

    /** Pages read for their text layer, at most. Reading stops sooner once [MAX_CHARS] is reached. */
    const val MAX_TEXT_PAGES = 60

    /**
     * Pages rendered and read by OCR when there is no text layer -- a scan, a
     * photo saved as a PDF. Each takes about a second, so only the first few:
     * a scanned document is recognised by its opening pages.
     */
    const val MAX_OCR_PAGES = 6

    /**
     * Below this many letters and digits per page, the text layer is taken
     * for missing: a scan often carries a stray page number or a stamp, and
     * nothing else.
     */
    const val MIN_CHARS_PER_PAGE = 40

    /** The long side of a page rendered for OCR: small print stays legible to the recognizer. */
    const val OCR_LONG_SIDE_PX = 2_000

    /** Bounds the bitmap for a very wide or tall page: 2000 x 2000 x 4 bytes is 16 MB. */
    const val OCR_MAX_PIXELS = 4_000_000L

    /** Whether a text layer read from [pages] pages holds enough to stand for the document. */
    fun hasTextLayer(text: String, pages: Int): Boolean {
        if (pages <= 0) return false
        val meaningful = text.count(Char::isLetterOrDigit)
        return meaningful >= MIN_CHARS_PER_PAGE * pages.coerceAtMost(3)
    }

    /**
     * Pixel size to render a page of [widthPt] by [heightPt] points at, for
     * OCR: the long side at [OCR_LONG_SIDE_PX], within [OCR_MAX_PIXELS].
     */
    fun renderSize(widthPt: Int, heightPt: Int): Pair<Int, Int> {
        if (widthPt <= 0 || heightPt <= 0) return 0 to 0
        var scale = OCR_LONG_SIDE_PX.toDouble() / max(widthPt, heightPt)
        val pixels = widthPt * scale * heightPt * scale
        if (pixels > OCR_MAX_PIXELS) scale *= sqrt(OCR_MAX_PIXELS / pixels)
        return (widthPt * scale).roundToInt().coerceAtLeast(1) to (heightPt * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * Text as it is stored: each page's lines trimmed and their runs of spaces
     * collapsed, blank runs within a page dropped, pages a blank line apart,
     * and the whole cut at a word before [maxChars].
     */
    fun clean(pages: List<String>, maxChars: Int = MAX_CHARS): String {
        val joined = pages
            .map { page ->
                page.lineSequence()
                    .map { it.replace(SPACES, " ").trim() }
                    .filter { line -> line.any(Char::isLetterOrDigit) }
                    .joinToString("\n")
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        if (joined.length <= maxChars) return joined
        val cut = joined.substring(0, maxChars)
        val lastBreak = cut.lastIndexOfAny(charArrayOf(' ', '\n'))
        return (if (lastBreak > maxChars / 2) cut.substring(0, lastBreak) else cut).trimEnd()
    }

    private val SPACES = Regex("""[ \t ]+""")
}
