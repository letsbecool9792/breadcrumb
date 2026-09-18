package com.lbc.breadcrumb.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How each tile first comes into the mosaic, and that nothing plays twice. */
class MosaicEntrancesTest {

    @Test
    fun `what the app opened on rises into place`() {
        val entrances = MosaicEntrances()
        entrances.settle(listOf("a", "b"))

        assertEquals(Entrance.RISE, entrances.entranceFor("a"))
        assertEquals(Entrance.RISE, entrances.entranceFor("b"))
    }

    @Test
    fun `a save made while the mosaic is up drops in`() {
        val entrances = MosaicEntrances()
        entrances.settle(listOf("a"))
        entrances.settle(listOf("new", "a"))

        assertEquals(Entrance.DROP, entrances.entranceFor("new"))
    }

    @Test
    fun `a tile already shown has no entrance, however often it is drawn again`() {
        // a search and back, or a scroll away and back
        val entrances = MosaicEntrances()
        entrances.settle(listOf("a"))
        entrances.shown("a")
        entrances.settle(listOf("a"))

        assertEquals(Entrance.NONE, entrances.entranceFor("a"))
    }

    @Test
    fun `a memory brought back by Undo drops in as new`() {
        val entrances = MosaicEntrances()
        entrances.settle(listOf("a", "b"))
        entrances.shown("a")

        entrances.settle(listOf("b"))       // deleted
        entrances.settle(listOf("a", "b"))  // undone

        assertEquals(Entrance.DROP, entrances.entranceFor("a"))
    }

    @Test
    fun `an app opened with nothing kept drops in its first save`() {
        val entrances = MosaicEntrances()
        entrances.settle(emptyList())
        entrances.settle(listOf("first"))

        assertEquals(Entrance.DROP, entrances.entranceFor("first"))
    }

    @Test
    fun `only the opening staggers`() {
        val entrances = MosaicEntrances(openedAt = 1_000)

        assertTrue(entrances.opening(now = 2_000))
        assertFalse("a tile reached by scrolling later just fades up", entrances.opening(now = 9_000))
    }
}
