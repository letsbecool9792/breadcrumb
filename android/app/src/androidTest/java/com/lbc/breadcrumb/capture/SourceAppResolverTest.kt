package com.lbc.breadcrumb.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceAppResolverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = SourceAppResolver(context)

    /**
     * Proves label resolution works for a visible app. It does NOT prove the
     * `<queries>` declaration works: Settings is force-queryable, visible to
     * every app with or without it. No app is both guaranteed installed and
     * visible only through that declaration, so it cannot be tested portably.
     * It was verified on-device instead, with `dumpsys package queries` showing
     * Chrome, WhatsApp and Gmail visible once it was added.
     */
    @Test
    fun aVisibleAppResolvesToItsName() {
        val source = resolver.resolve("com.android.settings")

        assertNotNull(source)
        assertEquals("com.android.settings", source!!.packageName)
        assertNotNull("launchable apps must be visible to resolve a name", source.label)
        assertFalse(source.label!!.startsWith("com."))
    }

    @Test
    fun anUnknownPackageKeepsItsPackageButHasNoName() {
        val source = resolver.resolve("com.example.definitely.not.installed")

        assertEquals("com.example.definitely.not.installed", source!!.packageName)
        assertNull(source.label)
    }

    @Test
    fun systemSurfacesAndBreadcrumbItselfAreNotSources() {
        assertNull(resolver.resolve("com.android.systemui"))
        assertNull(resolver.resolve(context.packageName))
        assertNull(resolver.resolve(null))
    }
}
