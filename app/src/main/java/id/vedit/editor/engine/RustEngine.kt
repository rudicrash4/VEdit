package id.vedit.editor.engine

import id.vedit.editor.domain.Edit
import id.vedit.editor.domain.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Engine berbasis Rust (sumber kebenaran state proyek ada di sisi native; [project] hanyalah snapshot).
 * Tidak thread-safe sendiri: dipanggil dari satu thread (main), sisi native memakai kunci per sesi.
 */
class RustEngine private constructor(private val handle: Long, initial: Project, canUndo0: Boolean, canRedo0: Boolean) : EditorEngine {
    override val name get() = "Rust"
    override var project: Project = initial
        private set
    override var canUndo: Boolean = canUndo0
        private set
    override var canRedo: Boolean = canRedo0
        private set
    private var closed = false

    override fun apply(op: EditOp, nowMs: Long): Edit {
        check(!closed) { "Engine sudah ditutup" }
        val r = parse(NativeBridge.nativeApply(handle, opToJson(op).toString(), nowMs))
        if (!r.ok) return Edit.Err(r.message)
        absorb(r.obj)
        return Edit.Ok(project, r.obj["selected"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content)
    }

    override fun undo(): Project? {
        if (!canUndo) return null
        absorb(parse(NativeBridge.nativeUndo(handle)).obj)
        return project
    }

    override fun redo(): Project? {
        if (!canRedo) return null
        absorb(parse(NativeBridge.nativeRedo(handle)).obj)
        return project
    }

    override fun close() {
        if (!closed) {
            closed = true
            NativeBridge.nativeClose(handle)
        }
    }

    private fun absorb(o: JsonObject) {
        project = JSON.decodeFromJsonElement(Project.serializer(), o["project"]!!)
        canUndo = o["canUndo"]!!.jsonPrimitive.boolean
        canRedo = o["canRedo"]!!.jsonPrimitive.boolean
    }

    private class Reply(val obj: JsonObject) {
        val ok: Boolean get() = obj["ok"]?.jsonPrimitive?.boolean == true
        val message: String get() = obj["message"]?.jsonPrimitive?.content ?: "Galat engine native"
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private fun parse(text: String): Reply = Reply(JSON.parseToJsonElement(text).jsonObject)

        /** Membuka sesi dari proyek (schema terkini). Mengembalikan galat teks bila native menolak. */
        fun open(project: Project): Result<RustEngine> = runCatching {
            val reply = parse(NativeBridge.nativeOpen(JSON.encodeToString(Project.serializer(), project)))
            if (!reply.ok) error(reply.message)
            val handle = reply.obj["handle"]!!.jsonPrimitive.long
            val snap = parse(NativeBridge.nativeSnapshot(handle))
            val p = JSON.decodeFromJsonElement(Project.serializer(), snap.obj["project"]!!)
            RustEngine(handle, p, snap.obj["canUndo"]!!.jsonPrimitive.boolean, snap.obj["canRedo"]!!.jsonPrimitive.boolean)
        }

        fun opToJson(op: EditOp): JsonObject = when (op) {
            is EditOp.AddClip -> buildJsonObject {
                put("op", "addClip"); put("trackId", op.trackId); put("sourceId", op.sourceId); put("newClipId", op.newClipId)
                if (op.startUs != null) put("startUs", op.startUs)
            }
            is EditOp.Split -> buildJsonObject { put("op", "split"); put("clipId", op.clipId); put("atUs", op.atUs); put("newClipId", op.newClipId) }
            is EditOp.TrimStart -> buildJsonObject { put("op", "trimStart"); put("clipId", op.clipId); put("newStartUs", op.newStartUs) }
            is EditOp.TrimEnd -> buildJsonObject { put("op", "trimEnd"); put("clipId", op.clipId); put("newEndUs", op.newEndUs) }
            is EditOp.Delete -> buildJsonObject { put("op", "delete"); put("clipId", op.clipId); put("ripple", op.ripple) }
            is EditOp.Duplicate -> buildJsonObject { put("op", "duplicate"); put("clipId", op.clipId); put("newClipId", op.newClipId) }
            is EditOp.Move -> buildJsonObject { put("op", "move"); put("clipId", op.clipId); put("newStartUs", op.newStartUs) }
            is EditOp.Reorder -> buildJsonObject { put("op", "reorder"); put("clipId", op.clipId); put("newIndex", op.newIndex) }
            is EditOp.SetRotation -> buildJsonObject { put("op", "setRotation"); put("clipId", op.clipId); put("degrees", op.degrees) }
            is EditOp.SetCrop -> buildJsonObject {
                put("op", "setCrop"); put("clipId", op.clipId)
                put("crop", op.crop?.let { JSON.encodeToJsonElement(id.vedit.editor.domain.Crop.serializer(), it) } ?: JsonNull)
            }
            is EditOp.SetCanvas -> buildJsonObject {
                put("op", "setCanvas"); put("aspect", op.canvas.aspect.name); put("fit", op.canvas.fit.name)
            }
            is EditOp.AddSources -> buildJsonObject {
                put("op", "addSources")
                put("sources", JsonArray(op.sources.map { JSON.encodeToJsonElement(id.vedit.editor.domain.MediaSource.serializer(), it) }))
            }
        }
    }
}

/** Memilih engine: Rust bila pustaka native termuat dan menerima proyek; bila tidak, Kotlin (dengan alasan). */
object EngineFactory {
    data class Created(val engine: EditorEngine, val fallbackReason: String?)

    fun create(project: Project, preferRust: Boolean = true): Created {
        if (preferRust) {
            if (NativeBridge.isAvailable) {
                val r = RustEngine.open(project)
                r.getOrNull()?.let { return Created(it, null) }
                return Created(KotlinEngine(project), "Engine Rust menolak proyek: ${r.exceptionOrNull()?.message}")
            }
            return Created(KotlinEngine(project), "Pustaka native tidak termuat: ${NativeBridge.loadError}")
        }
        return Created(KotlinEngine(project), null)
    }
}
