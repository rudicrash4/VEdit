package id.vedit.editor.poc

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import kotlin.math.min
import kotlin.math.roundToLong

/** Titik kontrol kurva kecepatan: waktu SUMBER (mikrodetik, relatif awal klip) dan kecepatan (1.0 = normal). */
data class SpeedPoint(val timeUs: Long, val speed: Float)

/** Segmen berkecepatan konstan pada waktu sumber [startUs, endUs). */
data class SpeedSegment(val startUs: Long, val endUs: Long, val speed: Float)

/**
 * Kurva kecepatan linear antar titik kontrol (PoC Phase 0B).
 * Murni Kotlin tanpa dependensi Android supaya bisa diuji unit.
 */
class SpeedCurve(points: List<SpeedPoint>) {
    private val pts = points.sortedBy { it.timeUs }

    init {
        require(pts.isNotEmpty()) { "Kurva butuh minimal satu titik" }
        require(pts.all { it.speed.isFinite() && it.speed > 0f }) { "Kecepatan harus > 0" }
    }

    fun speedAt(timeUs: Long): Float {
        if (timeUs <= pts.first().timeUs) return pts.first().speed
        if (timeUs >= pts.last().timeUs) return pts.last().speed
        val i = pts.indexOfLast { it.timeUs <= timeUs }
        val a = pts[i]
        val b = pts[i + 1]
        val f = (timeUs - a.timeUs).toDouble() / (b.timeUs - a.timeUs)
        return (a.speed + (b.speed - a.speed) * f).toFloat()
    }

    /** Diskretisasi menjadi segmen konstan; kecepatan diambil di titik tengah tiap segmen. */
    fun toSegments(durationUs: Long, stepUs: Long): List<SpeedSegment> {
        require(durationUs > 0 && stepUs > 0)
        val out = ArrayList<SpeedSegment>()
        var start = 0L
        while (start < durationUs) {
            val end = min(start + stepUs, durationUs)
            out += SpeedSegment(start, end, speedAt((start + end) / 2))
            start = end
        }
        return out
    }
}

/** Durasi keluaran (timeline) = jumlah panjang_sumber / kecepatan untuk tiap segmen. */
fun outputDurationUs(segments: List<SpeedSegment>): Long =
    segments.sumOf { ((it.endUs - it.startUs) / it.speed.toDouble()).roundToLong() }

fun constantSegments(durationUs: Long, speed: Float): List<SpeedSegment> =
    listOf(SpeedSegment(0L, durationUs, speed))

/** Adaptor ke Media3: kecepatan piecewise-constant berdasarkan waktu sumber. */
@OptIn(UnstableApi::class)
class SteppedSpeedProvider(private val segments: List<SpeedSegment>) : SpeedProvider {
    init {
        require(segments.isNotEmpty())
    }

    override fun getSpeed(timeUs: Long): Float =
        (segments.lastOrNull { it.startUs <= timeUs } ?: segments.first()).speed

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
        segments.firstOrNull { it.startUs > timeUs }?.startUs ?: C.TIME_UNSET
}
