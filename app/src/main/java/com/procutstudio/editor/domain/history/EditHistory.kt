package com.procutstudio.editor.domain.history

import com.procutstudio.editor.domain.model.EditorProject

/** One undoable step: the document before the change, plus a human readable label. */
data class HistoryEntry(
    val label: String,
    val project: EditorProject,
)

/**
 * Snapshot based undo/redo.
 *
 * The editing document is immutable, so a snapshot is just a reference — undo/redo is exact,
 * cannot desynchronise, and costs a pointer per step. The stack is bounded so a long editing
 * session cannot grow without limit, and it is intentionally *not* serialised: undo history is a
 * session concept, while the project itself is persisted after every committed change.
 */
class EditHistory(initial: EditorProject, private val maxDepth: Int = 80) {

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()

    var current: EditorProject = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    /** Records [newProject] as the result of an action described by [label]. */
    fun commit(label: String, newProject: EditorProject): Boolean {
        if (newProject == current) return false
        undoStack.addLast(HistoryEntry(label, current))
        while (undoStack.size > maxDepth) undoStack.removeFirst()
        redoStack.clear()
        current = newProject
        return true
    }

    /** Replaces the document without creating an undo step (used when (re)loading a project). */
    fun reset(project: EditorProject) {
        undoStack.clear()
        redoStack.clear()
        current = project
    }

    /** Applies a change that should not be undoable on its own, e.g. a persisted rename. */
    fun replaceCurrent(project: EditorProject) {
        current = project
    }

    fun undo(): String? {
        val entry = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(HistoryEntry(entry.label, current))
        current = entry.project
        return entry.label
    }

    fun redo(): String? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(HistoryEntry(entry.label, current))
        current = entry.project
        return entry.label
    }
}
