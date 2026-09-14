package com.lbc.breadcrumb.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrRulesTest {

    @Test
    fun `a screenshot is read at full size`() {
        assertEquals(1, OcrRules.sampleSize(1080, 2400))
        // a long scrolling capture still fits
        assertEquals(1, OcrRules.sampleSize(1080, 10_000))
    }

    @Test
    fun `a 12 MP photo fits exactly and is not shrunk`() {
        assertEquals(1, OcrRules.sampleSize(4000, 3000))
    }

    @Test
    fun `a larger image is halved until it fits`() {
        // 50 MP: halving once still leaves 12.5 MP
        assertEquals(4, OcrRules.sampleSize(8160, 6120))
        assertEquals(2, OcrRules.sampleSize(1440, 10_000))
    }

    @Test
    fun `a huge image cannot overflow the pixel count`() {
        // 900 MP, which overflows Int if multiplied as one; /8 still leaves 14 MP
        assertEquals(16, OcrRules.sampleSize(30_000, 30_000))
    }

    @Test
    fun `rotations follow the EXIF orientation tag`() {
        assertEquals(0, OcrRules.rotationDegrees(1))   // normal
        assertEquals(90, OcrRules.rotationDegrees(6))  // phone held sideways
        assertEquals(180, OcrRules.rotationDegrees(3))
        assertEquals(270, OcrRules.rotationDegrees(8))
    }

    @Test
    fun `mirrored orientations keep only their rotation`() {
        assertEquals(0, OcrRules.rotationDegrees(2))
        assertEquals(180, OcrRules.rotationDegrees(4))
        assertEquals(270, OcrRules.rotationDegrees(5))
        assertEquals(90, OcrRules.rotationDegrees(7))
    }

    @Test
    fun `a missing or unknown orientation means upright`() {
        assertEquals(0, OcrRules.rotationDegrees(0))
        assertEquals(0, OcrRules.rotationDegrees(42))
    }

    @Test
    fun `lines are trimmed and kept in order`() {
        assertEquals(
            "Qualcomm\nSoftware Engineering Intern\nApply by April 30",
            OcrRules.clean("  Qualcomm \nSoftware Engineering Intern\n\n Apply by April 30\n"),
        )
    }

    @Test
    fun `lines with no letter or digit are dropped`() {
        assertEquals("Menu\n1 of 3", OcrRules.clean("|\nMenu\n•\n— —\n1 of 3\n..."))
    }

    @Test
    fun `a line keeps its punctuation when it has real text`() {
        assertEquals("Naru's — book 2 weeks ahead!", OcrRules.clean("Naru's — book 2 weeks ahead!"))
    }

    @Test
    fun `nothing readable cleans to empty`() {
        assertEquals("", OcrRules.clean(null))
        assertEquals("", OcrRules.clean(""))
        assertEquals("", OcrRules.clean(" \n | \n "))
    }
}
