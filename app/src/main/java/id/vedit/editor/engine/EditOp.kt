package id.vedit.editor.engine

import id.vedit.editor.domain.CanvasSettings
import id.vedit.editor.domain.Crop
import id.vedit.editor.domain.MediaSource

/** Perintah edit. Kontrak yang sama dikirim ke engine Rust (JSON) dan ke implementasi Kotlin. */
sealed interface EditOp {
    data class AddClip(val trackId: String, val sourceId: String, val newClipId: String, val startUs: Long? = null) : EditOp
    data class Split(val clipId: String, val atUs: Long, val newClipId: String) : EditOp
    data class TrimStart(val clipId: String, val newStartUs: Long) : EditOp
    data class TrimEnd(val clipId: String, val newEndUs: Long) : EditOp
    data class Delete(val clipId: String, val ripple: Boolean = false) : EditOp
    data class Duplicate(val clipId: String, val newClipId: String) : EditOp
    data class Move(val clipId: String, val newStartUs: Long) : EditOp
    data class Reorder(val clipId: String, val newIndex: Int) : EditOp
    data class SetRotation(val clipId: String, val degrees: Int) : EditOp
    data class SetCrop(val clipId: String, val crop: Crop?) : EditOp
    data class SetCanvas(val canvas: CanvasSettings) : EditOp
    data class AddSources(val sources: List<MediaSource>) : EditOp
}
