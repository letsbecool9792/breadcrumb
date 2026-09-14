package com.lbc.breadcrumb.capture

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object ExifDates {

    /**
     * Parses EXIF `DateTimeOriginal` ("2026:04:18 10:12:33").
     *
     * EXIF stores local wall-clock time with no zone, so the device's zone is
     * the best available guess. Cameras that never had their clock set write
     * an all-zero placeholder; that is treated as absent rather than as 1970.
     */
    fun parse(value: String?, zone: TimeZone = TimeZone.getDefault()): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (v.startsWith("0000")) return null

        val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            timeZone = zone
            isLenient = false
        }
        return try {
            format.parse(v)?.time
        } catch (_: ParseException) {
            null
        }
    }
}
