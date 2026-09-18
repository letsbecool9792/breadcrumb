package com.lbc.breadcrumb.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** When the large name hands over to the slim strip as the mosaic scrolls. */
class MastheadTest {

    private val height = 200

    @Test
    fun `the strip stays hidden while most of the masthead is in view`() {
        assertEquals(0f, mastheadCollapse(0, 0, height))
        assertEquals(0f, mastheadCollapse(0, 90, height))
    }

    @Test
    fun `the strip arrives over the masthead's last stretch`() {
        val halfway = mastheadCollapse(0, 135, height)

        assertEquals(0.5f, halfway, 0.01f)
        assertEquals(1f, mastheadCollapse(0, 180, height))
    }

    @Test
    fun `once the grid is past the masthead the strip is fully there`() {
        assertEquals(1f, mastheadCollapse(3, 0, height))
    }

    @Test
    fun `before the masthead has been measured nothing collapses`() {
        assertEquals(0f, mastheadCollapse(0, 50, 0))
    }
}
