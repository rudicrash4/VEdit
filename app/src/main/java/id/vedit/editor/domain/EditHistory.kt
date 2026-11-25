package id.vedit.editor.domain

/** Undo/redo berbasis snapshot (Project immutable, jadi snapshot murah dibagi struktur). */
class EditHistory(initial: Project, private val limit: Int = 100) {
    var current: Project = initial
        private set
    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun apply(next: Project) {
        if (next == current) return
        undoStack.addLast(current)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        current = next
    }

    fun undo(): Project? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        current = prev
        return current
    }

    fun redo(): Project? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        current = next
        return current
    }
}
