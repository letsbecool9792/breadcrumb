package com.lbc.breadcrumb.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * On-device because both failure modes here are silent: a copy that truncates
 * looks like a saved memory, and a delete that escapes its directory looks like
 * nothing at all until something else is missing.
 */
@RunWith(AndroidJUnit4::class)
class OriginalStoreTest {

    private lateinit var context: Context
    private lateinit var sandbox: File
    private lateinit var root: File
    private lateinit var store: OriginalStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // cacheDir, never the real filesDir/originals: tests must not touch saved memories
        sandbox = File(context.cacheDir, "original-store-test").apply { deleteRecursively(); mkdirs() }
        root = File(sandbox, "originals")
        store = OriginalStore(context, root)
    }

    @After
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    private fun sourceFile(bytes: ByteArray, name: String = "source.bin") =
        File(sandbox, name).apply { writeBytes(bytes) }

    @Test
    fun copyIn_copiesEveryByteAndNamesTheFileById() {
        val bytes = Random(7).nextBytes(512_000)
        val source = sourceFile(bytes)

        val stored = store.copyIn(Uri.fromFile(source), id = "abc", extension = "jpg")

        assertEquals("abc.jpg", stored.name)
        assertEquals(root.canonicalFile, stored.parentFile!!.canonicalFile)
        assertArrayEquals(bytes, stored.readBytes())
    }

    @Test
    fun copyIn_ofAnUnreadableSourceLeavesNothingBehind() {
        val missing = Uri.fromFile(File(sandbox, "does-not-exist.jpg"))

        runCatching { store.copyIn(missing, id = "gone", extension = "jpg") }

        assertTrue("copy of a missing source should fail", root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun fileFor_resolvesAStoredOriginal() {
        val stored = store.copyIn(Uri.fromFile(sourceFile(byteArrayOf(1, 2, 3))), "m1", "png")
        val memory = Memory(id = "m1", type = MemoryType.IMAGE, localUri = store.uriFor(stored))

        assertNotNull(store.fileFor(memory))
    }

    @Test
    fun fileFor_refusesAnythingOutsideTheStore() {
        val outside = sourceFile(byteArrayOf(9), name = "outside.png")

        assertNull(store.fileFor(Memory(type = MemoryType.IMAGE, localUri = Uri.fromFile(outside).toString())))
        // the sample memories' made-up paths
        assertNull(store.fileFor(Memory(type = MemoryType.IMAGE, localUri = "file:///sample/internship.png")))
        // traversal out of the store
        val sneaky = Uri.fromFile(File(root, "../outside.png")).toString()
        assertNull(store.fileFor(Memory(type = MemoryType.IMAGE, localUri = sneaky)))
    }

    @Test
    fun delete_neverTouchesAFileOutsideTheStore() {
        val outside = sourceFile(byteArrayOf(9), name = "precious.png")
        val memory = Memory(type = MemoryType.IMAGE, localUri = Uri.fromFile(outside).toString())

        store.delete(memory)

        assertTrue("delete escaped the store", outside.exists())
    }

    @Test
    fun delete_removesTheStoredOriginal() {
        val stored = store.copyIn(Uri.fromFile(sourceFile(byteArrayOf(1))), "m2", "jpg")
        val memory = Memory(id = "m2", type = MemoryType.IMAGE, localUri = store.uriFor(stored))

        store.delete(memory)

        assertFalse(stored.exists())
    }

    @Test
    fun clear_emptiesTheStoreOnly() {
        store.copyIn(Uri.fromFile(sourceFile(byteArrayOf(1))), "a", "jpg")
        store.copyIn(Uri.fromFile(sourceFile(byteArrayOf(2))), "b", "pdf")
        val neighbour = sourceFile(byteArrayOf(3), name = "neighbour.bin")

        store.clear()

        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertTrue(neighbour.exists())
    }
}
