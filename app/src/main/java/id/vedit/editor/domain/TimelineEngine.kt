package id.vedit.editor.domain

import kotlin.math.roundToLong

sealed interface Edit {
    data class Ok(val project: Project, val selectedClipId: String? = null) : Edit
    data class Err(val reason: String) : Edit
}

/**
 * Mesin timeline murni (tanpa Android/Compose). Semua operasi mengembalikan Project baru atau alasan penolakan.
 *
 * Semantik:
 * - split: klip kiri dan kanan bersambung tanpa celah; titik potong harus >= MIN_CLIP_US dari kedua tepi.
 * - trim: menggeser tepi klip; tidak boleh melewati sumber, klip tetangga, atau menyisakan < MIN_CLIP_US.
 * - delete: ripple=false meninggalkan celah, ripple=true menggeser klip setelahnya ke kiri.
 * - duplicate: salinan ditaruh tepat setelah asli; klip setelahnya digeser ke kanan (insert + ripple).
 * - reorder: klip dipindah ke indeks baru lalu seluruh klip track dirapatkan mulai dari start paling awal (celah hilang).
 * - move: memindah start; ditolak bila tumpang tindih.
 */
object TimelineEngine {
    private fun err(msg: String) = Edit.Err(msg)

    private fun Project.withTrack(track: Track): Project =
        copy(tracks = tracks.map { if (it.id == track.id) track.copy(clips = track.clips.sortedBy { c -> c.startUs }) else it })

    private fun overlaps(track: Track, candidate: Clip): Boolean =
        track.clips.any { it.id != candidate.id && it.startUs < candidate.endUs - EPS_US && candidate.startUs < it.endUs - EPS_US }

    fun addClip(p: Project, trackId: String, sourceId: String, newClipId: String, startUs: Long? = null): Edit {
        val track = p.tracks.firstOrNull { it.id == trackId } ?: return err("Track tidak ada")
        val src = p.source(sourceId) ?: return err("Sumber media tidak ada")
        val ok = when (track.type) {
            TrackType.VIDEO -> src.type == MediaType.VIDEO || src.type == MediaType.IMAGE
            TrackType.AUDIO -> src.type == MediaType.AUDIO
        }
        if (!ok) return err("Tipe media ${src.type} tidak cocok untuk track ${track.type}")
        if (src.durationUs < MIN_CLIP_US) return err("Durasi media terlalu pendek")
        val start = startUs ?: (track.clips.maxOfOrNull { it.endUs } ?: 0L)
        if (start < 0) return err("Posisi negatif")
        val clip = Clip(newClipId, sourceId, start, 0L, src.durationUs)
        if (overlaps(track, clip)) return err("Tumpang tindih dengan klip lain")
        return Edit.Ok(p.withTrack(track.copy(clips = track.clips + clip)), newClipId)
    }

    fun split(p: Project, clipId: String, atUs: Long, newClipId: String): Edit {
        val track = p.trackOf(clipId) ?: return err("Klip tidak ditemukan")
        val clip = p.clip(clipId)!!
        if (atUs - clip.startUs < MIN_CLIP_US || clip.endUs - atUs < MIN_CLIP_US) {
            return err("Titik potong harus minimal 100 ms dari tepi klip")
        }
        val srcAt = clip.timelineToSourceUs(atUs)
        val left = clip.copy(sourceOutUs = srcAt)
        val right = clip.copy(id = newClipId, startUs = left.endUs, sourceInUs = srcAt)
        val clips = track.clips.filter { it.id != clipId } + left + right
        return Edit.Ok(p.withTrack(track.copy(clips = clips)), newClipId)
    }

    fun trimStart(p: Project, clipId: String, newStartUs: Long): Edit {
        val track = p.trackOf(clipId) ?: return err("Klip tidak ditemukan")
        val clip = p.clip(clipId)!!
        if (newStartUs < 0) return err("Posisi negatif")
        val newIn = clip.sourceInUs + ((newStartUs - clip.startUs) * clip.speed.toDouble()).roundToLong()
        if (newIn < 0) return err("Tidak ada sumber lagi di sisi kiri")
        val trimmed = clip.copy(startUs = newStartUs, sourceInUs = newIn)
        if (newIn >= clip.sourceOutUs || trimmed.durationUs < MIN_CLIP_US) return err("Klip akan lebih pendek dari 100 ms")
        if (overlaps(track, trimmed)) return err("Tumpang tindih dengan klip sebelumnya")
        return Edit.Ok(p.withTrack(track.copy(clips = track.clips.map { if (it.id == clipId) trimmed else it })), clipId)
    }

    fun trimEnd(p: Project, clipId: String, newEndUs: Long): Edit {
        val track = p.trackOf(clipId) ?: return err("Klip tidak ditemukan")
        val clip = p.clip(clipId)!!
        val src = p.source(clip.sourceId) ?: return err("Sumber media tidak ada")
        val newOut = clip.sourceOutUs + ((newEndUs - clip.endUs) * clip.speed.toDouble()).roundToLong()
        if (src.type != MediaType.IMAGE && newOut > src.durationUs) return err("Melewati akhir media sumber")
        val trimmed = clip.copy(sourceOutUs = newOut)
        if (newOut <= clip.sourceInUs || trimmed.durationUs < MIN_CLIP_US) return err("Klip akan lebih pendek dari 100 ms")
        if (overlaps(track, trimmed)) return err("Tumpang tindih dengan klip berikutnya")
        return Edit.Ok(p.withTrack(track.copy(clips = track.clips.map { if (it.id == clipId) trimmed else it })), clipId)
    }

    fun delete(p: Project, clipId: String, ripple: Boolean = false): Edit {
        val track = p.trackOf(clipId) ?: return err("Klip tidak ditemukan")
        val clip = p.clip(clipId)!!
        val clips = track.clips.filter { it.id != clipId }.map {
            if (ripple && it.startUs >= clip.endUs - EPS_US) it.copy(startUs = it.startUs - clip.durationUs) else it
        }
        return Edit.Ok(p.withTrack(track.copy(clips = clips)), null)
    }

    fun duplicate(p: Project, clipId: String, newClipId: String): Edit {
        val track = p.trackOf(clipId) ?: return err("Klip tidak ditemukan")
        val clip = p.clip(clipId)!!
        val copy = clip.copy(id = newClipId, startUs = clip.endUs)
        val shifted = track.clips.map { if (it.startUs >= clip.endUs - EPS_US) it.copy(startUs = it.startUs + copy.durationUs) else it }
        return Edit.Ok(p.withTrack(track.copy(clips = shifted + copy)), newClipId)
    }

    fun move(p: Project, clipId: String, newStartUs: Long): Edit {
        val track = p.trackOf(clipId) ?: return err("Klip tidak ditemukan")
        val clip = p.clip(clipId)!!
        if (newStartUs < 0) return err("Posisi negatif")
        val moved = clip.copy(startUs = newStartUs)
        if (overlaps(track, moved)) return err("Tumpang tindih dengan klip lain")
        return Edit.Ok(p.withTrack(track.copy(clips = track.clips.map { if (it.id == clipId) moved else it })), clipId)
    }

    fun reorder(p: Project, clipId: String, newIndex: Int): Edit {
        val track = p.trackOf(clipId) ?: return err("Klip tidak ditemukan")
        val ordered = track.clips.sortedBy { it.startUs }
        val clip = ordered.first { it.id == clipId }
        val rest = ordered.filter { it.id != clipId }.toMutableList()
        rest.add(newIndex.coerceIn(0, rest.size), clip)
        var cursor = ordered.first().startUs
        val packed = rest.map { c -> c.copy(startUs = cursor).also { cursor = it.endUs } }
        return Edit.Ok(p.withTrack(track.copy(clips = packed)), clipId)
    }

    fun setRotation(p: Project, clipId: String, degrees: Int): Edit {
        val track = p.trackOf(clipId) ?: return err("Klip tidak ditemukan")
        if (degrees % 90 != 0) return err("Rotasi harus kelipatan 90")
        val d = Math.floorMod(degrees, 360)
        return Edit.Ok(p.withTrack(track.copy(clips = track.clips.map { if (it.id == clipId) it.copy(rotationDeg = d) else it })), clipId)
    }

    fun setCrop(p: Project, clipId: String, crop: Crop?): Edit {
        val track = p.trackOf(clipId) ?: return err("Klip tidak ditemukan")
        return Edit.Ok(p.withTrack(track.copy(clips = track.clips.map { if (it.id == clipId) it.copy(crop = crop) else it })), clipId)
    }

    /** Klip di track tertentu yang mencakup waktu timeline (setengah-terbuka [start, end)). */
    fun clipAt(p: Project, trackId: String, timeUs: Long): Clip? =
        p.tracks.firstOrNull { it.id == trackId }?.clips?.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs }
}
