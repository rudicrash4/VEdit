package id.vedit.editor.domain

/**
 * Logika sinkronisasi preview yang murni (tanpa Android) sehingga bisa diuji unit:
 * urutan item per track (klip + celah), pemetaan playhead <-> klip, dan keputusan kapan preview dibangun ulang.
 */
object PreviewPlan {
    sealed interface Item {
        data class ClipItem(val clip: Clip) : Item
        data class Gap(val durationUs: Long) : Item
    }

    /** Celah lebih kecil dari ini dianggap tidak ada (galat pembulatan konversi kecepatan). */
    const val MIN_GAP_US = 1_000L

    /** Klip berurutan dengan celah eksplisit di antaranya dan di depan klip pertama; tanpa celah di belakang. */
    fun sequenceItems(track: Track): List<Item> {
        val out = ArrayList<Item>()
        var cursor = 0L
        for (c in track.clips.sortedBy { it.startUs }) {
            val gap = c.startUs - cursor
            if (gap >= MIN_GAP_US) out += Item.Gap(gap)
            out += Item.ClipItem(c)
            cursor = maxOf(cursor, c.endUs)
        }
        return out
    }

    data class Located(val index: Int, val offsetUs: Long, val inGap: Boolean, val atEnd: Boolean)

    /**
     * Memetakan waktu timeline ke (indeks klip, offset di dalam klip) pada daftar klip terurut.
     * Di dalam celah: klip berikutnya dengan offset 0 (inGap = true). Setelah klip terakhir: akhir klip terakhir (atEnd).
     * Batas klip setengah-terbuka: tepat di akhir klip A = awal klip B berikutnya.
     */
    fun locate(sortedClips: List<Clip>, timeUs: Long): Located? {
        if (sortedClips.isEmpty()) return null
        val t = timeUs.coerceAtLeast(0L)
        for ((i, c) in sortedClips.withIndex()) {
            if (t < c.startUs) return Located(i, 0L, inGap = true, atEnd = false)
            if (t < c.endUs) return Located(i, t - c.startUs, inGap = false, atEnd = false)
        }
        val last = sortedClips.last()
        return Located(sortedClips.lastIndex, last.durationUs, inGap = false, atEnd = true)
    }

    /** Kebalikan [locate]: posisi di klip ke-`index` -> waktu timeline. */
    fun toTimelineUs(sortedClips: List<Clip>, index: Int, offsetUs: Long): Long =
        sortedClips[index.coerceIn(0, sortedClips.lastIndex)].startUs + offsetUs

    /** Preview perlu dibangun ulang hanya bila isi track/sumber berubah (bukan nama atau waktu modifikasi). */
    fun structureChanged(old: Project?, new: Project): Boolean =
        old == null || old.tracks != new.tracks || old.sources != new.sources

    fun hasPlayableContent(p: Project): Boolean = p.durationUs > 0 && p.tracks.any { t -> t.clips.isNotEmpty() }

    fun clampPlayhead(us: Long, durationUs: Long): Long = us.coerceIn(0L, durationUs.coerceAtLeast(0L))

    /** Apakah playhead berada di dalam klip (setengah-terbuka)? Dipakai untuk "pilih klip -> preview menampilkan klip itu". */
    fun playheadInside(clip: Clip, playheadUs: Long): Boolean = playheadUs >= clip.startUs && playheadUs < clip.endUs
}
