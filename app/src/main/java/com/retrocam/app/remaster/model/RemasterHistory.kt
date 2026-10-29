package com.retrocam.app.remaster.model

/**
 * Manages an undo/redo stack for [RemasterAdjustments].
 *
 * Keeps up to [maxCapacity] undo steps to balance memory and user control.
 */
class RemasterHistory(
    initialState: RemasterAdjustments = RemasterAdjustments.DEFAULT,
    private val maxCapacity: Int = 30
) {
    private val undoStack = ArrayDeque<RemasterAdjustments>()
    private val redoStack = ArrayDeque<RemasterAdjustments>()
    var current: RemasterAdjustments = initialState
        private set

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    /**
     * Records a new state. Clears redo history.
     */
    fun push(newState: RemasterAdjustments) {
        if (newState == current) return
        undoStack.addLast(current)
        if (undoStack.size > maxCapacity) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        current = newState
    }

    /**
     * Reverts to the previous state, moving the current state onto the redo stack.
     * Returns the newly active state, or null if cannot undo.
     */
    fun undo(): RemasterAdjustments? {
        if (!canUndo) return null
        val previous = undoStack.removeLast()
        redoStack.addLast(current)
        current = previous
        return current
    }

    /**
     * Reapplies a previously undone state.
     * Returns the newly active state, or null if cannot redo.
     */
    fun redo(): RemasterAdjustments? {
        if (!canRedo) return null
        val next = redoStack.removeLast()
        undoStack.addLast(current)
        current = next
        return current
    }

    /**
     * Clears all history and resets to default or the given initial state.
     */
    fun reset(state: RemasterAdjustments = RemasterAdjustments.DEFAULT) {
        undoStack.clear()
        redoStack.clear()
        current = state
    }
}
