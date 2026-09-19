package com.lbc.breadcrumb.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRulesTest {

    @Test
    fun aRealTextLayerIsTakenAsTheDocument() {
        val page = "Data Structures, week 6: balanced trees. AVL rotations and red-black invariants."

        assertTrue(PdfRules.hasTextLayer(page, pages = 1))
    }

    @Test
    fun aScanWithOnlyAPageNumberHasNoTextLayer() {
        // what a scanned document's text layer usually holds
        assertFalse(PdfRules.hasTextLayer("1\n\n2\n\n3", pages = 3))
        assertFalse(PdfRules.hasTextLayer("", pages = 1))
    }

    @Test
    fun aLongDocumentIsJudgedByItsFirstPagesNotAllOfThem() {
        // three pages' worth is enough, however many pages were read
        val text = "a".repeat(PdfRules.MIN_CHARS_PER_PAGE * 3)

        assertTrue(PdfRules.hasTextLayer(text, pages = 60))
    }

    @Test
    fun aShortOneLinePdfStillHasATextLayer() {
        // found on the phone: a 36-character line was taken for a scan and misread by OCR
        assertTrue(PdfRules.hasTextLayer("Invoice 4471-B for ACME Widgets, due 30 April", pages = 1))
    }

    @Test
    fun aThinLayerIsKeptOverOcrThatFoundNoMore() {
        val layer = "Boarding pass BLR to DEL"
        // OCR is close, never exact: it must not win a tie
        assertEquals(layer, PdfRules.better(layer, "Boarding pass BLR to DEl."))
    }

    @Test
    fun ocrWinsWhenTheLayerHeldOnlyAStamp() {
        val scan = "Lease agreement between the owner and the tenant of Flat 4B, Koramangala"

        assertEquals(scan, PdfRules.better("Page 1", scan))
        assertEquals(scan, PdfRules.better("", scan))
    }

    @Test
    fun withNoLayerAtAllEvenAShortOcrReadingIsKept() {
        assertEquals("Receipt 42", PdfRules.better("", "Receipt 42"))
    }

    @Test
    fun noPagesMeansNoTextLayer() {
        assertFalse(PdfRules.hasTextLayer("plenty of text here, really", pages = 0))
    }

    @Test
    fun anA4PageRendersWithItsLongSideAtTheTarget() {
        // A4 is 595 x 842 points
        val (width, height) = PdfRules.renderSize(595, 842)

        assertEquals(PdfRules.OCR_LONG_SIDE_PX, height)
        assertEquals(1413, width)
    }

    @Test
    fun aSquarePageIsKeptWithinThePixelBudget() {
        val (width, height) = PdfRules.renderSize(1000, 1000)

        assertTrue(width.toLong() * height <= PdfRules.OCR_MAX_PIXELS)
        assertEquals(width, height)
    }

    @Test
    fun aPageWithNoSizeRendersAsNothing() {
        assertEquals(0 to 0, PdfRules.renderSize(0, 842))
    }

    @Test
    fun cleanTrimsLinesCollapsesSpacesAndSeparatesPages() {
        val cleaned = PdfRules.clean(listOf("  Week 6   notes \n\n\n  AVL\ttrees ", "   \n", "Page two"))

        assertEquals("Week 6 notes\nAVL trees\n\nPage two", cleaned)
    }

    @Test
    fun cleanDropsLinesWithNoLetterOrDigit() {
        assertEquals("Heading\nBody", PdfRules.clean(listOf("Heading\n• — |\nBody")))
    }

    @Test
    fun cleanCutsALongDocumentAtAWord() {
        val cleaned = PdfRules.clean(listOf("alpha beta gamma delta"), maxChars = 13)

        assertEquals("alpha beta", cleaned)
    }
}
