package com.lbc.breadcrumb.ui.home

import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.net.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Test

/** How a ranked answer from the server becomes rows on the phone. */
class SearchJoinTest {

    private fun memory(id: String) = Memory(id = id, type = MemoryType.TEXT, rawText = id)

    @Test
    fun `results keep the server's ranking, not the phone's order`() {
        val held = listOf(memory("a"), memory("b"), memory("c")).associateBy { it.id }

        val rows = HomeViewModel.join(listOf(SearchHit("c"), SearchHit("a"), SearchHit("b")), held)

        assertEquals(listOf("c", "a", "b"), rows.map { it.memory.id })
    }

    @Test
    fun `a memory deleted on the phone is dropped, not shown as something that cannot open`() {
        // deletes do not sync, so the server still ranks what the phone no longer holds
        val rows = HomeViewModel.join(listOf(SearchHit("gone"), SearchHit("kept")), mapOf("kept" to memory("kept")))

        assertEquals(listOf("kept"), rows.map { it.memory.id })
    }

    @Test
    fun `each row carries what the server said about it`() {
        val hit = SearchHit("a", summary = "A note about Naru's")

        assertEquals(hit, HomeViewModel.join(listOf(hit), mapOf("a" to memory("a"))).single().hit)
    }

    @Test
    fun `spacing does not make a different search`() {
        assertEquals("qualcomm intern", HomeViewModel.normalize("  qualcomm \n  intern "))
        assertEquals("", HomeViewModel.normalize("   "))
    }
}
