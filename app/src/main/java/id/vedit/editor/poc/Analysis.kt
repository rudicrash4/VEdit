package id.vedit.editor.poc

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Logika verifikasi murni (tanpa Android) sehingga bisa diuji unit. */
object Analysis {
    /** Toleransi durasi: 8% atau 300 ms, mana yang lebih besar (granularitas frame/container). */
    fun durationTolerance(expectedMs: Long): Long = max(300L, (expectedMs * 0.08).toLong())

    fun durationOk(expectedMs: Long, actualMs: Long): Boolean =
        abs(actualMs - expectedMs) <= durationTolerance(expectedMs)

    fun ratioOk(actual: Double, expected: Double, relTolerance: Double = 0.15): Boolean =
        expected > 0 && abs(actual - expected) <= expected * relTolerance

    /** RMS campuran yang diharapkan dari dua sumber tak berkorelasi, dibobot lama masing-masing. */
    fun expectedMixRms(rmsA: Double, secA: Double, rmsB: Double, secB: Double, secMix: Double): Double {
        require(secMix > 0)
        return sqrt((rmsA * rmsA * secA + rmsB * rmsB * secB) / secMix)
    }

    /** Rata-rata selisih luma absolut (0..255) pada region [x0,x1) x [y0,y1) dari dua bitmap ARGB berukuran sama. */
    fun meanAbsLumaDiff(a: IntArray, b: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        require(a.size == w * h && b.size == w * h) { "ukuran bitmap tidak sama" }
        var sum = 0.0
        var n = 0
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(h)) {
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(w)) {
                sum += abs(luma(a[y * w + x]) - luma(b[y * w + x]))
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun luma(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val bl = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * bl
    }

    /** Dua lapisan terlihat bila area PiP jauh berbeda dari video latar sendirian, sedangkan area acuan hampir sama. */
    fun pipLayersVisible(diffPipRegion: Double, diffReferenceRegion: Double): Boolean =
        diffPipRegion > 8.0 && diffPipRegion > 3.0 * max(diffReferenceRegion, 1.0)

    fun isMonotonic(timesUs: List<Long>): Boolean = timesUs.zipWithNext().all { (a, b) -> b >= a }

    fun gaps(timesUs: List<Long>): List<Long> = timesUs.sorted().zipWithNext { a, b -> b - a }

    fun maxGapUs(timesUs: List<Long>): Long = gaps(timesUs).maxOrNull() ?: 0L

    fun medianGapUs(timesUs: List<Long>): Long {
        val g = gaps(timesUs).sorted()
        return if (g.isEmpty()) 0L else g[g.size / 2]
    }

    /**
     * Pada kecepatan minimum `minSpeed`, jeda antar-frame keluaran paling banyak 1/minSpeed kali jeda normal.
     * Jeda lebih besar (dengan kelonggaran `slack`) menandakan lompatan/frame hilang.
     */
    fun rampGapOk(maxGapUs: Long, baselineMedianGapUs: Long, minSpeed: Float, slack: Double = 1.6): Boolean =
        baselineMedianGapUs > 0 && maxGapUs <= baselineMedianGapUs / minSpeed * slack

    /** Selisih akhir track audio vs video; >150 ms dianggap perlu dicurigai (bukan bukti lip-sync). */
    fun avEndDeltaMs(audioEndUs: Long, videoEndUs: Long): Long = abs(audioEndUs - videoEndUs) / 1000

    const val AV_DELTA_LIMIT_MS = 150L

    enum class PreviewVerdict { CONSISTENT, DIVERGENT, UNKNOWN }

    fun previewVerdict(previewMs: Long?, exportMs: Long?): PreviewVerdict = when {
        previewMs == null || previewMs <= 0 || exportMs == null -> PreviewVerdict.UNKNOWN
        durationOk(exportMs, previewMs) -> PreviewVerdict.CONSISTENT
        else -> PreviewVerdict.DIVERGENT
    }

    /** Pesan penyebab yang bisa ditindaklanjuti; `message == null` sering berarti checkState tanpa pesan. */
    fun describeThrowable(t: Throwable): String {
        val msg = t.message
        return if (msg.isNullOrBlank()) {
            "${t::class.java.simpleName} tanpa pesan (kemungkinan checkState/precondition di library); stack teratas: ${t.stackTrace.firstOrNull()}"
        } else {
            "${t::class.java.simpleName}: $msg"
        }
    }
}
