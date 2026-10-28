package com.procutstudio.editor.domain

import com.procutstudio.editor.domain.history.EditHistory
import com.procutstudio.editor.domain.model.EditorProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    private fun project(name: String) = EditorProject.create(name)

    @Test
    fun `commit records an undoable step`() {
        val start = project("Start")
        val history = EditHistory(start)

        assertFalse(history.canUndo)
        assertNull(history.undoLabel)

        val renamed = start.renamed("Renamed")
        assertTrue(history.commit("Rename project", renamed))

        assertTrue(history.canUndo)
        assertEquals("Rename project", history.undoLabel)
        assertEquals(renamed, history.current)
    }

    @Test
    fun `committing an identical document is a no-op`() {
        val start = project("Same")
        val history = EditHistory(start)

        assertFalse(history.commit("Nothing", start.copy()))
        assertFalse(history.canUndo)
    }

    @Test
    fun `undo moves back and redo moves forward`() {
        val start = project("v1")
        val history = EditHistory(start)
        val v2 = start.renamed("v2")
        val v3 = v2.renamed("v3")
        history.commit("Rename v2", v2)
        history.commit("Rename v3", v3)

        assertEquals("Rename v3", history.undo())
        assertEquals(v2, history.current)
        assertEquals("Rename v2", history.undo())
        assertEquals(start, history.current)
        assertFalse(history.canUndo)
        assertNull(history.undo())

        assertEquals("Rename v2", history.redo())
        assertEquals(v2, history.current)
        assertEquals("Rename v3", history.redo())
        assertEquals(v3, history.current)
        assertFalse(history.canRedo)
    }

    @Test
    fun `a new commit clears the redo stack`() {
        val start = project("v1")
        val history = EditHistory(start)
        history.commit("A", start.renamed("v2"))
        history.undo()
        assertTrue(history.canRedo)

        history.commit("B", start.renamed("v3"))

        assertFalse(history.canRedo)
        assertNull(history.redo())
        assertEquals("v3", history.current.name)
    }

    @Test
    fun `history is bounded by the configured depth`() {
        var current = project("v0")
        val history = EditHistory(current, maxDepth = 3)
        repeat(6) { index ->
            val next = current.renamed("v${index + 1}")
            history.commit("step ${index + 1}", next)
            current = next
        }

        var undos = 0
        while (history.undo() != null) undos++
        assertEquals(3, undos)
        // Only the last three steps can be undone; the oldest state is v3.
        assertEquals("v3", history.current.name)
    }

    @Test
    fun `reset drops both stacks`() {
        val start = project("v1")
        val history = EditHistory(start)
        history.commit("A", start.renamed("v2"))

        history.reset(start.renamed("fresh"))

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals("fresh", history.current.name)
    }

    @Test
    fun `replaceCurrent keeps the stacks intact`() {
        val start = project("v1")
        val history = EditHistory(start)
        history.commit("A", start.renamed("v2"))

        history.replaceCurrent(start.renamed("v2-saved"))

        assertEquals("v2-saved", history.current.name)
        assertTrue(history.canUndo)
        history.undo()
        assertEquals("v1", history.current.name)
    }
}
