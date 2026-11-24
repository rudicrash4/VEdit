package id.vedit.editor.poc

import id.vedit.editor.poc.Analysis.PreviewVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisTest {
    @Test
    fun durationToleranceHasFloorAndRelativePart() {
        assertEquals(300L, Analysis.durationTolerance(2_500))
        assertEquals(800L, Analysis.durationTolerance(10_000))
    }

    @Test
    fun durationOkAcceptsObservedDeviceOverheadButRejectsRealErrors() {
        // Nilai nyata CPH2591: 10000 -> 10077, 2500 -> 2577, ramp 2624 -> 2717
        assertTrue(Analysis.durationOk(10_000, 10_077))
        assertTrue(Analysis.durationOk(2_500, 2_577))
        assertTrue(Analysis.durationOk(2_624, 2_717))
        // Kecepatan salah (2x terbaca sebagai 1x) harus ditolak
        assertFalse(Analysis.durationOk(2_500, 5_062))
        assertFalse(Analysis.durationOk(10_000, 5_000))
    }

    @Test
    fun volumeRatioCheck() {
        assertTrue(Analysis.ratioOk(0.31, 0.3))
        assertFalse(Analysis.ratioOk(1.0, 0.3))
        assertFalse(Analysis.ratioOk(0.0, 0.0))
    }

    @Test
    fun mixRmsIsEnergyWeightedByDuration() {
        // Dua sumber sama panjang dengan RMS 0.3 dan 0.4 -> 0.5
        assertEquals(0.5, Analysis.expectedMixRms(0.3, 5.0, 0.4, 5.0, 5.0), 1e-9)
        // Sumber kedua hanya separuh durasi mix
        assertEquals(Math.sqrt((0.09 * 5 + 0.16 * 2.5) / 5), Analysis.expectedMixRms(0.3, 5.0, 0.4, 2.5, 5.0), 1e-9)
    }

    @Test
    fun lumaDiffOnRegion() {
        val w = 10
        val h = 10
        val a = IntArray(w * h) { 0xFF000000.toInt() }
        val b = a.copyOf()
        // Ubah kotak kanan bawah 4x4 menjadi putih
        for (y in 6 until 10) for (x in 6 until 10) b[y * w + x] = 0xFFFFFFFF.toInt()
        assertEquals(255.0, Analysis.meanAbsLumaDiff(a, b, w, h, 6, 6, 10, 10), 0.5)
        assertEquals(0.0, Analysis.meanAbsLumaDiff(a, b, w, h, 0, 0, 4, 4), 1e-9)
        assertTrue(Analysis.pipLayersVisible(255.0, 0.0))
        assertFalse(Analysis.pipLayersVisible(5.0, 0.0))
        assertFalse(Analysis.pipLayersVisible(20.0, 15.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun lumaDiffRejectsMismatchedBitmaps() {
        Analysis.meanAbsLumaDiff(IntArray(4), IntArray(9), 2, 2, 0, 0, 2, 2)
    }

    @Test
    fun frameGapsDetectJumpsAndOrdering() {
        val smooth = listOf(0L, 33_000, 66_000, 100_000, 133_000)
        assertEquals(33_000L, Analysis.medianGapUs(smooth))
        assertTrue(Analysis.isMonotonic(smooth))
        assertFalse(Analysis.isMonotonic(listOf(0L, 40_000, 20_000)))
        // Pada kecepatan min 0.5x jeda normal boleh dua kali lipat (x kelonggaran), tidak lebih
        assertTrue(Analysis.rampGapOk(70_000, 33_000, 0.5f))
        assertFalse(Analysis.rampGapOk(400_000, 33_000, 0.5f))
        assertFalse(Analysis.rampGapOk(10, 0, 0.5f))
    }

    @Test
    fun avDeltaAndPreviewVerdict() {
        assertEquals(40L, Analysis.avEndDeltaMs(5_040_000, 5_000_000))
        assertEquals(PreviewVerdict.CONSISTENT, Analysis.previewVerdict(10_050, 10_077))
        assertEquals(PreviewVerdict.DIVERGENT, Analysis.previewVerdict(5_000, 10_077))
        assertEquals(PreviewVerdict.UNKNOWN, Analysis.previewVerdict(null, 10_077))
        assertEquals(PreviewVerdict.UNKNOWN, Analysis.previewVerdict(-9223372036854775807L, 10_077))
    }

    @Test
    fun throwableWithoutMessageIsExplainedNotSwallowed() {
        val d = Analysis.describeThrowable(IllegalStateException())
        assertTrue(d.contains("IllegalStateException"))
        assertTrue(d.contains("tanpa pesan"))
        assertEquals("IllegalArgumentException: x", Analysis.describeThrowable(IllegalArgumentException("x")))
    }
}
