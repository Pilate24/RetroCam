package com.retrocam.app.remaster

import com.retrocam.app.remaster.model.RemasterAdjustments
import com.retrocam.app.remaster.model.RemasterHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemasterHistoryTest {

    @Test
    fun testInitialHistoryState() {
        val history = RemasterHistory()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(RemasterAdjustments.DEFAULT, history.current)
    }

    @Test
    fun testPushAndUndoRedo() {
        val history = RemasterHistory()

        val state1 = RemasterAdjustments(exposure = 0.2f)
        val state2 = RemasterAdjustments(exposure = 0.4f, contrast = 0.1f)

        history.push(state1)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(state1, history.current)

        history.push(state2)
        assertEquals(state2, history.current)

        // Undo to state1
        val undone = history.undo()
        assertEquals(state1, undone)
        assertEquals(state1, history.current)
        assertTrue(history.canUndo)
        assertTrue(history.canRedo)

        // Undo to initial
        val undone2 = history.undo()
        assertEquals(RemasterAdjustments.DEFAULT, undone2)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)

        // Redo to state1
        val redone = history.redo()
        assertEquals(state1, redone)
        assertTrue(history.canUndo)
        assertTrue(history.canRedo)

        // Redo to state2
        val redone2 = history.redo()
        assertEquals(state2, redone2)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun testNewPushClearsRedo() {
        val history = RemasterHistory()
        val s1 = RemasterAdjustments(exposure = 0.1f)
        val s2 = RemasterAdjustments(exposure = 0.2f)
        val s3 = RemasterAdjustments(exposure = 0.3f)

        history.push(s1)
        history.push(s2)
        history.undo() // now at s1, redo has s2
        assertTrue(history.canRedo)

        history.push(s3) // clears redo
        assertFalse(history.canRedo)
        assertEquals(s3, history.current)
    }

    @Test
    fun testMaxCapacityLimit() {
        val capacity = 5
        val history = RemasterHistory(maxCapacity = capacity)

        for (i in 1..10) {
            history.push(RemasterAdjustments(exposure = i.toFloat()))
        }

        var undoCount = 0
        while (history.canUndo) {
            history.undo()
            undoCount++
        }

        assertEquals(capacity, undoCount)
    }

    @Test
    fun testReset() {
        val history = RemasterHistory()
        history.push(RemasterAdjustments(exposure = 0.5f))
        history.push(RemasterAdjustments(exposure = 1.0f))

        history.reset()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(RemasterAdjustments.DEFAULT, history.current)
    }
}
