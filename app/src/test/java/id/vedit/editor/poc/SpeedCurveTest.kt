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
}
