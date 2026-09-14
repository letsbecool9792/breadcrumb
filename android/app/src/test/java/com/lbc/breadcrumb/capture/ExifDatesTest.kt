package com.lbc.breadcrumb.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class ExifDatesTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `parses DateTimeOriginal as wall-clock time in the given zone`() {
        // 2026-04-18T10:12:33Z
        assertEquals(1_776_507_153_000, ExifDates.parse("2026:04:18 10:12:33", utc))
    }

    @Test
    fun `the zone shifts the instant`() {
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        // 10:12:33 in IST is 04:42:33 UTC
        assertEquals(1_776_487_353_000, ExifDates.parse("2026:04:18 10:12:33", ist))
    }

    @Test
    fun `the all-zero placeholder from unset camera clocks is absent, not 1970`() {
        assertNull(ExifDates.parse("0000:00:00 00:00:00", utc))
    }

    @Test
    fun `malformed or empty values are absent`() {
        assertNull(ExifDates.parse(null, utc))
        assertNull(ExifDates.parse("", utc))
        assertNull(ExifDates.parse("2026-04-18 10:12:33", utc))
        assertNull(ExifDates.parse("2026:13:40 99:99:99", utc))
    }
}
