package com.lbc.breadcrumb.open

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.OriginalStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * "Open original" hands another app a file only Breadcrumb can read (step 4.5).
 * On the device, because what matters is the platform's FileProvider honouring
 * the manifest: that a stored original goes out as a readable content URI with
 * a one-off grant, and that nothing outside the originals folder goes at all.
 *
 * Writes one file of its own into the real originals folder -- the provider
 * serves nothing else -- under a name no memory can have, and removes it after.
 */
@RunWith(AndroidJUnit4::class)
class OriginalsProviderTest {

    private lateinit var context: Context
    private lateinit var original: File
    private val bytes = ByteArray(2048) { (it * 7).toByte() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // memory ids are UUIDs; this can never be one
        original = File(context.filesDir, "originals/breadcrumb-test-provider.png").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    @After
    fun tearDown() {
        original.delete()
    }

    @Test
    fun aStoredOriginalGoesOutAsAReadableContentUri() {
        val intent = Originals.viewIntent(context, OriginalAction.ViewFile(original, "image/png"))
        val uri = intent.data!!

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.originals", uri.authority)
        assertEquals("image/png", intent.type)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        // the file's bytes, read the way the receiving app reads them
        val read = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(bytes, read)
    }

    @Test
    fun theGrantIsForReadingThisOneIntentOnly() {
        val intent = Originals.viewIntent(context, OriginalAction.ViewFile(original, "image/png"))

        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    @Test
    fun nothingOutsideTheOriginalsFolderCanBeHandedOut() {
        val database = context.getDatabasePath("breadcrumb.db")
        val cached = File(context.cacheDir, "breadcrumb-test-outside.png").apply { writeBytes(bytes) }
        try {
            for (file in listOf(database, cached)) {
                try {
                    FileProvider.getUriForFile(context, Originals.authority(context), file)
                    fail("$file must not be reachable through the originals provider")
                } catch (_: IllegalArgumentException) {
                    // what FileProvider throws for a path outside its configured roots
                }
            }
        } finally {
            cached.delete()
        }
    }

    @Test
    fun aMemoryIsOpenedThroughTheRealStore() {
        val memory = Memory(
            id = "breadcrumb-test-provider",
            type = MemoryType.IMAGE,
            localUri = OriginalStore(context).uriFor(original),
        )

        val action = Originals.actionFor(context, memory) as OriginalAction.ViewFile

        assertEquals(original.canonicalFile, action.file.canonicalFile)
        assertEquals("image/png", action.mimeType)
    }
}
