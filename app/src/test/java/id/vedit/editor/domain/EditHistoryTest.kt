package id.vedit.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    private fun p(name: String) = Project.create("p", name, 0)

    @Test
    fun undoRedoRoundTrip() {
        val h = EditHistory(p("a"))
        h.apply(p("b"))
        h.apply(p("c"))
        assertEquals("b", h.undo()!!.name)
        assertEquals("a", h.undo()!!.name)
        assertNull(h.undo())
        assertEquals("b", h.redo()!!.name)
        assertEquals("c", h.redo()!!.name)
        assertNull(h.redo())
    }

    @Test
    fun newEditClearsRedoAndNoOpIsIgnored() {
        val h = EditHistory(p("a"))
        h.apply(p("b"))
        h.undo()
        assertTrue(h.canRedo)
        h.apply(p("a")) // sama dengan current: tidak mengubah apa pun
        assertTrue(h.canRedo)
        h.apply(p("x"))
        assertFalse(h.canRedo)
        assertEquals("x", h.current.name)
    }

    @Test
    fun limitDropsOldestSnapshots() {
        val h = EditHistory(p("0"), limit = 3)
        for (i in 1..6) h.apply(p("$i"))
        var count = 0
        while (h.undo() != null) count++
        assertEquals(3, count)
        assertEquals("3", h.current.name)
    }
}
