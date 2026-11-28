package id.vedit.editor.preview.v2

import id.vedit.editor.domain.Crop
import id.vedit.editor.domain.FitMode
import id.vedit.editor.domain.MediaType
import id.vedit.editor.domain.Project
import id.vedit.editor.domain.TrackType
import id.vedit.editor.engine.NativeBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min

enum class SegKind { VIDEO, IMAGE, GAP, AUDIO }

/** Satu segmen pemutaran di timeline (klip, gambar, atau celah) beserta pemetaan ke waktu sumber. */
data class Segment(
    val kind: SegKind,
    val clipId: String,
    val sourceId: String,
    val startUs: Long,
    val endUs: Long,
    val srcInUs: Long,
    val srcOutUs: Long,
    val speed: Float,
    val volume: Float,
    val rotationDeg: Int,
    val crop: Crop?,
) {
    val durationUs: Long get() = endUs - startUs

    /** Waktu timeline -> posisi sumber (dibatasi ke [srcIn, srcOut]); pembulatan = Math.round seperti Rust. */
    fun sourcePosUs(tUs: Long): Long =
        (srcInUs + Math.round((tUs - startUs).toDouble() * speed.toDouble())).coerceIn(srcInUs, srcOutUs)

    fun timelinePosUs(srcUs: Long): Long = startUs + Math.round((srcUs - srcInUs).toDouble() / speed.toDouble())
}

data class PlaybackPlan(val video: List<Segment>, val audio: List<Segment>, val durationUs: Long) {
    companion object {
        /** Indeks segmen yang memuat t (setengah-terbuka); null bila t di/ setelah akhir. */
        fun indexAt(segs: List<Segment>, tUs: Long): Int? {
            val t = max(0L, tUs)
            val i = segs.indexOfFirst { t < it.endUs }
            return if (i < 0) null else i
        }
    }
}

/** Matriks piksel-sumber -> piksel-view [sx,kx,tx,ky,sy,ty] dan persegi tujuan [l,t,r,b]. */
data class Layout(val m: DoubleArray, val clip: DoubleArray) {
    override fun equals(other: Any?) = other is Layout && m.contentEquals(other.m) && clip.contentEquals(other.clip)
    override fun hashCode() = m.contentHashCode() * 31 + clip.contentHashCode()
}

/** Sumber rencana dan geometri. Rust = utama; Kotlin = cadangan bila pustaka native tidak ada (dan pembanding tes). */
interface PlanProvider {
    val name: String
    fun plan(project: Project): PlaybackPlan
    fun layout(viewW: Double, viewH: Double, srcW: Double, srcH: Double, rotationDeg: Int, crop: Crop?, fit: FitMode): Layout
}

private const val MIN_GAP_US = 1_000L

object KotlinPlanProvider : PlanProvider {
    override val name = "Kotlin"

    override fun plan(project: Project): PlaybackPlan {
        val video = mutableListOf<Segment>()
        val audio = mutableListOf<Segment>()
        for (t in project.tracks) {
            val out = if (t.type == TrackType.VIDEO) video else audio
            var cursor = 0L
            for (c in t.clips.sortedBy { it.startUs }) {
                if (c.startUs - cursor >= MIN_GAP_US) out += gap(cursor, c.startUs)
                val kind = when (project.source(c.sourceId)?.type) {
                    MediaType.IMAGE -> SegKind.IMAGE
                    MediaType.AUDIO -> SegKind.AUDIO
                    else -> SegKind.VIDEO
                }
                out += Segment(kind, c.id, c.sourceId, c.startUs, c.endUs, c.sourceInUs, c.sourceOutUs, c.speed, c.volume, c.rotationDeg, c.crop)
                cursor = max(cursor, c.endUs)
            }
        }
        return PlaybackPlan(video, audio, project.durationUs)
    }

    private fun gap(s: Long, e: Long) = Segment(SegKind.GAP, "", "", s, e, 0, 0, 1f, 1f, 0, null)

    override fun layout(viewW: Double, viewH: Double, srcW: Double, srcH: Double, rotationDeg: Int, crop: Crop?, fit: FitMode): Layout {
        val l = (crop?.left ?: 0f).toDouble()
        val t = (crop?.top ?: 0f).toDouble()
        val r = (crop?.right ?: 0f).toDouble()
        val b = (crop?.bottom ?: 0f).toDouble()
        val cx0 = l * srcW
        val cy0 = t * srcH
        val cw = srcW * (1.0 - l - r)
        val ch = srcH * (1.0 - t - b)
        val rot = ((rotationDeg % 360) + 360) % 360
        val (cos, sin) = when (rot) {
            90 -> 0.0 to 1.0
            180 -> -1.0 to 0.0
            270 -> 0.0 to -1.0
            else -> 1.0 to 0.0
        }
        val (rw, rh) = if (rot == 90 || rot == 270) ch to cw else cw to ch
        val s = if (fit == FitMode.FIT) min(viewW / rw, viewH / rh) else max(viewW / rw, viewH / rh)
        val mx = cx0 + cw / 2.0
        val my = cy0 + ch / 2.0
        val sx = s * cos
        val kx = -s * sin
        val ky = s * sin
        val sy = s * cos
        val tx = viewW / 2.0 - (sx * mx + kx * my)
        val ty = viewH / 2.0 - (ky * mx + sy * my)
        val dw = rw * s
        val dh = rh * s
        return Layout(
            doubleArrayOf(sx, kx, tx, ky, sy, ty),
            doubleArrayOf(max(0.0, viewW / 2 - dw / 2), max(0.0, viewH / 2 - dh / 2), min(viewW, viewW / 2 + dw / 2), min(viewH, viewH / 2 + dh / 2)),
        )
    }
}

/** Rencana dan geometri dari core Rust lewat bridge (JSON). Melempar IllegalStateException bila native menolak. */
object RustPlanProvider : PlanProvider {
    override val name = "Rust"
    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun plan(project: Project): PlaybackPlan {
        val o = JSON.parseToJsonElement(NativeBridge.nativePlan(JSON.encodeToString(Project.serializer(), project))).jsonObject
        check(o["ok"]?.jsonPrimitive?.boolean == true) { o["message"]?.jsonPrimitive?.content ?: "Rencana native ditolak" }
        return PlaybackPlan(segments(o["video"]!!.jsonArray), segments(o["audio"]!!.jsonArray), o["durationUs"]!!.jsonPrimitive.long)
    }

    private fun segments(a: JsonArray): List<Segment> = a.map { e ->
        val s = e.jsonObject
        Segment(
            kind = SegKind.valueOf(s["kind"]!!.jsonPrimitive.content),
            clipId = s["clipId"]!!.jsonPrimitive.content,
            sourceId = s["sourceId"]!!.jsonPrimitive.content,
            startUs = s["startUs"]!!.jsonPrimitive.long,
            endUs = s["endUs"]!!.jsonPrimitive.long,
            srcInUs = s["srcInUs"]!!.jsonPrimitive.long,
            srcOutUs = s["srcOutUs"]!!.jsonPrimitive.long,
            speed = s["speed"]!!.jsonPrimitive.float,
            volume = s["volume"]!!.jsonPrimitive.float,
            rotationDeg = s["rotationDeg"]!!.jsonPrimitive.int,
            crop = s["crop"]?.takeIf { it !is JsonNull }?.let { JSON.decodeFromJsonElement(Crop.serializer(), it) },
        )
    }

    override fun layout(viewW: Double, viewH: Double, srcW: Double, srcH: Double, rotationDeg: Int, crop: Crop?, fit: FitMode): Layout {
        val args = buildJsonObject {
            put("viewW", viewW); put("viewH", viewH); put("srcW", srcW); put("srcH", srcH)
            put("rotationDeg", rotationDeg)
            put("crop", crop?.let { JSON.encodeToJsonElement(Crop.serializer(), it) } ?: JsonNull)
            put("fit", fit.name)
        }
        val o = JSON.parseToJsonElement(NativeBridge.nativeLayout(args.toString())).jsonObject
        check(o["ok"]?.jsonPrimitive?.boolean == true) { o["message"]?.jsonPrimitive?.content ?: "Layout native ditolak" }
        return Layout(
            o["m"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray(),
            o["clip"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray(),
        )
    }
}

object PlanProviders {
    /** Rust bila termuat; selain itu Kotlin. */
    fun default(): PlanProvider = if (NativeBridge.isAvailable) RustPlanProvider else KotlinPlanProvider
}
