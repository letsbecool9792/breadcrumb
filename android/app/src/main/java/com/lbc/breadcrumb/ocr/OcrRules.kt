package com.lbc.breadcrumb.ocr

/**
 * The judgment calls in reading an image. Pure and Android-free, like
 * [com.lbc.breadcrumb.capture.ClipboardRules], so they are covered by JVM tests.
 */
object OcrRules {

    /**
     * Largest image handed to the recognizer. A 12 MP camera photo fits whole,
     * at ~48 MB as a bitmap. A 50 MP one would take ~200 MB to decode, which
     * is an out-of-memory crash rather than a slow read, so bigger images are
     * halved until they fit.
     *
     * Kept generous on purpose: screenshots are what matters most here, their
     * text is small, and shrinking them is what loses it.
     */
    const val MAX_PIXELS = 12_000_000L

    /** The power-of-two `inSampleSize` that brings an image within [maxPixels]. */
    fun sampleSize(width: Int, height: Int, maxPixels: Long = MAX_PIXELS): Int {
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) {
            sample *= 2
        }
        return sample
    }

    // EXIF orientation values, spelled out so this file needs no Android classes.
    private const val FLIP_VERTICAL = 4
    private const val ROTATE_180 = 3
    private const val TRANSPOSE = 5
    private const val ROTATE_90 = 6
    private const val TRANSVERSE = 7
    private const val ROTATE_270 = 8

    /**
     * Clockwise degrees that turn a decoded image upright, from its EXIF
     * orientation. BitmapFactory ignores the tag, so a photo taken with the
     * phone sideways decodes on its side, and the recognizer reads sideways
     * text poorly. Mirrored orientations keep only their rotation -- the same
     * mapping as androidx ExifInterface's `rotationDegrees`.
     */
    fun rotationDegrees(exifOrientation: Int): Int = when (exifOrientation) {
        ROTATE_90, TRANSVERSE -> 90
        ROTATE_180, FLIP_VERTICAL -> 180
        ROTATE_270, TRANSPOSE -> 270
        else -> 0
    }

    /**
     * Recognized text as it is stored: lines trimmed, and lines with no letter
     * or digit dropped. Those are what a photo with no real text in it tends
     * to produce -- a stray "|" from a table edge, a "•" from a list -- and
     * they only add noise to what gets searched.
     */
    fun clean(recognized: String?): String =
        recognized.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { line -> line.any(Char::isLetterOrDigit) }
            .joinToString("\n")
}
