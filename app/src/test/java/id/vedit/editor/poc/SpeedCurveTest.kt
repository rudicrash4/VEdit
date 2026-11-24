package id.vedit.editor.poc

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedCurveTest {
    private val second = 1_000_000L

    @Test
    fun constantSpeedDuration() {
        assertEquals(10 * second, outputDurationUs(constantSegments(5 * second, 0.5f)))
        assertEquals(2_500_000L, outputDurationUs(constantSegments(5 * second, 2f)))
        assertEquals(5 * second, outputDurationUs(constantSegments(5 * second, 1f)))
    }

    @Test
    fun linearRampMatchesAnalyticIntegral() {
        // speed(t) = 1 + 3t/T, durasi = (T/3) ln 4
        val curve = SpeedCurve(listOf(SpeedPoint(0, 1f), SpeedPoint(5 * second, 4f)))
        val actual = outputDurationUs(curve.toSegments(5 * second, 50_000))
        val exact = (5 * second / 3.0) * Math.log(4.0)
        assertEquals(exact, actual.toDouble(), exact * 0.01)
    }

    @Test
    fun speedInterpolationAndClamping() {
        val curve = SpeedCurve(listOf(SpeedPoint(second, 1f), SpeedPoint(3 * second, 3f)))
        assertEquals(1f, curve.speedAt(0), 1e-6f)
        assertEquals(2f, curve.speedAt(2 * second), 1e-6f)
        assertEquals(3f, curve.speedAt(10 * second), 1e-6f)
    }

    @Test
    fun segmentsCoverWholeDurationWithoutGaps() {
        val curve = SpeedCurve(listOf(SpeedPoint(0, 1f), SpeedPoint(2 * second, 2f)))
        val segs = curve.toSegments(2_100_000, 250_000)
        assertEquals(0L, segs.first().startUs)
        assertEquals(2_100_000L, segs.last().endUs)
        for (i in 1 until segs.size) assertEquals(segs[i - 1].endUs, segs[i].startUs)
    }

    @Test
    fun providerFollowsMediaContract() {
        val p = SteppedSpeedProvider(
            listOf(SpeedSegment(0, 1000, 1f), SpeedSegment(1000, 2000, 2f), SpeedSegment(2000, 3000, 4f)),
        )
        assertEquals(1f, p.getSpeed(0), 0f)
        assertEquals(2f, p.getSpeed(1000), 0f)
        assertEquals(4f, p.getSpeed(2999), 0f)
        assertEquals(1000L, p.getNextSpeedChangeTimeUs(0))
        assertEquals(2000L, p.getNextSpeedChangeTimeUs(1000))
        assertEquals(C.TIME_UNSET, p.getNextSpeedChangeTimeUs(2000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveSpeed() {
        SpeedCurve(listOf(SpeedPoint(0, 0f)))
    }

    @Test
    fun rampDurationBetweenFastestAndSlowestBounds() {
        val curve = SpeedCurve(listOf(SpeedPoint(0, 1f), SpeedPoint(5 * second, 4f), SpeedPoint(10 * second, 0.5f)))
        val d = outputDurationUs(curve.toSegments(10 * second, 250_000))
        assertTrue(d > 10 * second / 4 && d < 10 * second * 2)
    }

    @Test
    fun providerBoundariesAreHalfOpenAndConsistentWithDuration() {
        val curve = SpeedCurve(listOf(SpeedPoint(0, 1f), SpeedPoint(5 * second, 4f), SpeedPoint(10 * second, 0.5f)))
        val segs = curve.toSegments(10 * second, 250_000)
        val p = SteppedSpeedProvider(segs)
        // Tepat di batas segmen: kecepatan segmen BARU berlaku, dan batas berikutnya adalah akhir segmen itu
        for (i in 1 until segs.size) {
            assertEquals(segs[i].speed, p.getSpeed(segs[i].startUs), 0f)
            assertEquals(segs[i - 1].speed, p.getSpeed(segs[i].startUs - 1), 0f)
        }
        // Integrasi lewat provider (loncat antar batas) harus sama dengan outputDurationUs
        var t = 0L
        var out = 0.0
        while (t < 10 * second) {
            val next = p.getNextSpeedChangeTimeUs(t).let { if (it == C.TIME_UNSET) 10 * second else it }
            out += (next - t) / p.getSpeed(t).toDouble()
            t = next
        }
        assertEquals(outputDurationUs(segs).toDouble(), out, 5.0)
    }

    @Test
    fun slowRampEndsSlowerThanItStarts() {
        val curve = SpeedCurve(listOf(SpeedPoint(0, 4f), SpeedPoint(5 * second, 0.5f)))
        val segs = curve.toSegments(5 * second, 500_000)
        assertTrue(segs.first().speed > segs.last().speed)
        assertTrue(segs.zipWithNext().all { (a, b) -> b.speed <= a.speed })
    }

    @Test
    fun timelineDurationOfSequenceIsSumOfPresentationDurations() {
        // Dua klip: 5 dtk @1x dan 5 dtk @2x -> 5 + 2.5 dtk di timeline
        val total = outputDurationUs(constantSegments(5 * second, 1f)) + outputDurationUs(constantSegments(5 * second, 2f))
        assertEquals(7_500_000L, total)
    }
}
