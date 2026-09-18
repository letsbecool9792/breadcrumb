package com.lbc.breadcrumb.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The walking trail's steps: one crumb at a time, faint to bright, then a rest. */
class CrumbTrailTest {

    @Test
    fun `each crumb is fully lit at its own moment in the lap`() {
        assertEquals(1f, stepLight(0.1f, 0), 1e-4f)
        assertEquals(1f, stepLight(0.3f, 1), 1e-4f)
        assertEquals(1f, stepLight(0.5f, 2), 1e-4f)
        assertEquals(1f, stepLight(0.7f, 3), 1e-4f)
    }

    @Test
    fun `the steps go faint to bright, in order`() {
        // at the second crumb's moment, the first has gone out and the third not yet lit
        assertEquals(0f, stepLight(0.3f, 0), 1e-4f)
        assertEquals(0f, stepLight(0.3f, 2), 1e-4f)
    }

    @Test
    fun `the last fifth of the lap is a rest`() {
        for (i in 0..3) assertEquals("crumb $i during the rest", 0f, stepLight(0.95f, i), 1e-4f)
    }

    @Test
    fun `a step rises and falls rather than blinking`() {
        val rising = stepLight(0.2f, 1)
        assertTrue("half lit on the way up, got $rising", rising in 0.4f..0.6f)
    }
}
