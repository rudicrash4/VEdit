package id.vedit.editor.engine

import id.vedit.editor.domain.Edit
import id.vedit.editor.domain.EditHistory
import id.vedit.editor.domain.Project
import id.vedit.editor.domain.TimelineEngine

/**
 * Pemilik tunggal state edit proyek (proyek terkini + undo/redo). UI hanya memegang snapshot [project].
 * Implementasi: [RustEngine] (jalur utama bila pustaka native termuat) dan [KotlinEngine] (cadangan/pembanding).
 * Tidak boleh ada dua timeline yang berubah independen: UI selalu memanggil engine, tidak memodifikasi proyek sendiri.
 */
interface EditorEngine : AutoCloseable {
    val name: String
    val project: Project
    val canUndo: Boolean
    val canRedo: Boolean

    /** Menerapkan perintah. `Ok.selectedClipId` = klip yang sebaiknya terpilih. Ditolak = state tidak berubah. */
    fun apply(op: EditOp, nowMs: Long): Edit
    fun undo(): Project?
    fun redo(): Project?
}

/** Implementasi lama (TimelineEngine + EditHistory) sebagai cadangan dan pembanding differential test. */
class KotlinEngine(initial: Project) : EditorEngine {
    private val history = EditHistory(initial)
    override val name get() = "Kotlin"
    override val project: Project get() = history.current
    override val canUndo get() = history.canUndo
    override val canRedo get() = history.canRedo

    override fun apply(op: EditOp, nowMs: Long): Edit {
        val p = history.current
        val edit = when (op) {
            is EditOp.AddClip -> TimelineEngine.addClip(p, op.trackId, op.sourceId, op.newClipId, op.startUs)
            is EditOp.Split -> TimelineEngine.split(p, op.clipId, op.atUs, op.newClipId)
            is EditOp.TrimStart -> TimelineEngine.trimStart(p, op.clipId, op.newStartUs)
            is EditOp.TrimEnd -> TimelineEngine.trimEnd(p, op.clipId, op.newEndUs)
            is EditOp.Delete -> TimelineEngine.delete(p, op.clipId, op.ripple)
            is EditOp.Duplicate -> TimelineEngine.duplicate(p, op.clipId, op.newClipId)
            is EditOp.Move -> TimelineEngine.move(p, op.clipId, op.newStartUs)
            is EditOp.Reorder -> TimelineEngine.reorder(p, op.clipId, op.newIndex)
            is EditOp.SetRotation -> TimelineEngine.setRotation(p, op.clipId, op.degrees)
            is EditOp.SetCrop -> TimelineEngine.setCrop(p, op.clipId, op.crop)
            is EditOp.SetCanvas -> TimelineEngine.setCanvas(p, op.canvas)
            is EditOp.AddSources -> Edit.Ok(p.copy(sources = p.sources + op.sources))
        }
        if (edit is Edit.Ok) history.apply(edit.project.copy(modifiedAtMs = nowMs))
        return if (edit is Edit.Ok) Edit.Ok(history.current, edit.selectedClipId) else edit
    }

    override fun undo(): Project? = history.undo()
    override fun redo(): Project? = history.redo()
    override fun close() {}
}
