package id.vedit.editor.domain

import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

enum class MediaType { VIDEO, IMAGE, AUDIO }

enum class TrackType { VIDEO, AUDIO }

/** Referensi media. File asli tidak pernah diubah. Untuk gambar, durasi = durasi bawaan di timeline. */
@Serializable
data class MediaSource(
    val id: String,
    val uri: String,
    val type: MediaType,
    val displayName: String,
    val durationUs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val hasAudio: Boolean = false,
)

/** Pecahan sisi yang dipotong (0..1) relatif terhadap frame sumber. */
@Serializable
data class Crop(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    init {
        require(left >= 0f && top >= 0f && right >= 0f && bottom >= 0f) { "Crop tidak boleh negatif" }
        require(left + right <= 0.9f && top + bottom <= 0.9f) { "Crop menyisakan area terlalu kecil" }
    }

    companion object {
        /** Crop tengah ke rasio target (lebar/tinggi) dari frame berasio sumber. */
        fun centered(targetAspect: Float, sourceAspect: Float): Crop {
            require(targetAspect > 0f && sourceAspect > 0f)
            return if (targetAspect < sourceAspect) {
                val side = (1f - targetAspect / sourceAspect) / 2f
                Crop(left = side, right = side)
            } else {
                val side = (1f - sourceAspect / targetAspect) / 2f
                Crop(top = side, bottom = side)
            }
        }
    }
}

@Serializable
data class Clip(
    val id: String,
    val sourceId: String,
    /** Posisi awal di timeline (us). */
    val startUs: Long,
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val rotationDeg: Int = 0,
    val crop: Crop? = null,
) {
    /** Durasi di timeline setelah pemetaan kecepatan. */
    val durationUs: Long get() = ((sourceOutUs - sourceInUs) / speed.toDouble()).roundToLong()
    val endUs: Long get() = startUs + durationUs

    /** Waktu timeline -> waktu sumber (kecepatan konstan). */
    fun timelineToSourceUs(timelineUs: Long): Long = sourceInUs + ((timelineUs - startUs) * speed.toDouble()).roundToLong()
}

@Serializable
data class Track(val id: String, val type: TrackType, val clips: List<Clip> = emptyList())

@Serializable
data class Project(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val id: String,
    val name: String,
    val sources: List<MediaSource> = emptyList(),
    val tracks: List<Track>,
    val createdAtMs: Long,
    val modifiedAtMs: Long,
    val canvas: CanvasSettings = CanvasSettings(),
) {
    val durationUs: Long get() = tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.endUs } ?: 0L } ?: 0L

    fun source(id: String): MediaSource? = sources.firstOrNull { it.id == id }

    fun clip(id: String): Clip? = tracks.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id == id } }

    fun trackOf(clipId: String): Track? = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } }

    /** Daftar pelanggaran invarian; kosong = valid. */
    fun validate(): List<String> {
        val errors = ArrayList<String>()
        for (t in tracks) {
            val sorted = t.clips.sortedBy { it.startUs }
            sorted.forEachIndexed { i, c ->
                val src = source(c.sourceId)
                if (src == null) errors += "Klip ${c.id}: sumber ${c.sourceId} tidak ada"
                if (c.startUs < 0) errors += "Klip ${c.id}: start negatif"
                if (c.sourceInUs < 0 || c.sourceOutUs <= c.sourceInUs) errors += "Klip ${c.id}: in/out tidak valid"
                if (src != null && src.type != MediaType.IMAGE && c.sourceOutUs > src.durationUs + EPS_US) errors += "Klip ${c.id}: out melewati durasi sumber"
                if (!(c.speed > 0f && c.speed.isFinite())) errors += "Klip ${c.id}: kecepatan tidak valid"
                if (c.rotationDeg % 90 != 0 || c.rotationDeg !in 0..270) errors += "Klip ${c.id}: rotasi tidak valid"
                if (i > 0 && sorted[i - 1].endUs > c.startUs + EPS_US) errors += "Klip ${c.id}: tumpang tindih dengan ${sorted[i - 1].id}"
            }
        }
        return errors
    }

    companion object {
        const val CURRENT_SCHEMA = 2
        const val VIDEO_TRACK = "v1"
        const val AUDIO_TRACK = "a1"

        fun create(id: String, name: String, nowMs: Long) = Project(
            id = id, name = name,
            tracks = listOf(Track(VIDEO_TRACK, TrackType.VIDEO), Track(AUDIO_TRACK, TrackType.AUDIO)),
            createdAtMs = nowMs, modifiedAtMs = nowMs,
        )
    }
}
