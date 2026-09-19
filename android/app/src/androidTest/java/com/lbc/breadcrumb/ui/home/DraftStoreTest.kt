package com.lbc.breadcrumb.ui.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The draft on disk. Its own preferences file: the real draft is never touched. */
@RunWith(AndroidJUnit4::class)
class DraftStoreTest {

    private lateinit var context: Context
    private lateinit var store: DraftStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences(PREFS)
        store = DraftStore(context, PREFS)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(PREFS)
    }

    @Test
    fun aDraftOutlivesTheStoreThatWroteIt() {
        store.save(Draft(text = "Ask Priya about the flat"))

        // as a fresh launch would find it
        assertEquals("Ask Priya about the flat", DraftStore(context, PREFS).load().text)
    }

    @Test
    fun aFileWhoseGrantDidNotLastIsDroppedNotKept() {
        // a URI this app holds no lasting grant for: it could not be read at Keep
        store.save(Draft(text = "the menu", attachments = listOf(DraftFile("content://media/external/images/media/1"))))

        val loaded = DraftStore(context, PREFS).load()

        assertEquals("the menu", loaded.text)
        assertEquals(emptyList<DraftFile>(), loaded.attachments)
    }

    @Test
    fun anEmptiedDraftIsGone() {
        store.save(Draft(text = "something"))
        store.save(Draft())

        assertEquals(Draft(), DraftStore(context, PREFS).load())
    }

    private companion object {
        const val PREFS = "draft-test"
    }
}
