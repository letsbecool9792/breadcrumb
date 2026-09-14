package com.lbc.breadcrumb.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceAppsTest {

    private val own = "com.lbc.breadcrumb"

    @Test
    fun `a real app is kept`() {
        assertEquals("com.whatsapp", SourceApps.normalize("com.whatsapp", own))
        assertEquals("com.android.chrome", SourceApps.normalize("  com.android.chrome ", own))
    }

    @Test
    fun `clipboard shares arrive from systemui, which is not a source`() {
        assertNull(SourceApps.normalize("com.android.systemui", own))
    }

    @Test
    fun `the share chooser, the framework and adb are not sources`() {
        assertNull(SourceApps.normalize("com.android.intentresolver", own))
        assertNull(SourceApps.normalize("android", own))
        assertNull(SourceApps.normalize("com.android.shell", own))
    }

    @Test
    fun `breadcrumb is never its own source`() {
        assertNull(SourceApps.normalize(own, own))
    }

    @Test
    fun `a missing referrer is no source`() {
        assertNull(SourceApps.normalize(null, own))
        assertNull(SourceApps.normalize("", own))
        assertNull(SourceApps.normalize("   ", own))
    }
}
