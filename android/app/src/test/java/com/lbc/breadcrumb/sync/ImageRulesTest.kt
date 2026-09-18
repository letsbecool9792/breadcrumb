package com.lbc.breadcrumb.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageRulesTest {

    @Test
    fun `a picture already small enough is sent as it is`() {
        assertEquals(1, ImageRules.sampleSize(1_080, 1_440, maxEdge = 1_536))
        assertEquals(1, ImageRules.sampleSize(800, 600, maxEdge = 1_536))
    }

    @Test
    fun `a phone screenshot is halved once`() {
        // 1080x2400: halving leaves a 1200 long edge, still under the cap
        assertEquals(2, ImageRules.sampleSize(1_080, 2_400, maxEdge = 1_536))
    }

    @Test
    fun `a camera photo is halved until it fits`() {
        // 8160 -> 4080 -> 2040 -> 1020: three halvings, since 2040 is still over
        assertEquals(8, ImageRules.sampleSize(8_160, 6_120, maxEdge = 1_536))
        assertEquals(8, ImageRules.sampleSize(12_000, 9_000, maxEdge = 1_536))
    }

    @Test
    fun `shrinking never goes below the cap it is aiming for`() {
        // 3000 halves to 1500, which is under 1536: going further would throw
        // away detail the model is being asked to read
        assertEquals(2, ImageRules.sampleSize(3_000, 2_000, maxEdge = 1_536))
    }
}
