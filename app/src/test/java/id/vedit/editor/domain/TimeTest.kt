package id.vedit.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeTest {
    @Test
    fun frameRoundTripHasNoDriftAtNtscRates() {
        // 30000/1001 dan 24000/1001, 100 ribu frame (puluhan menit): frame -> us -> frame harus identik
        for ((n, d) in listOf(30000 to 1001, 24000 to 1001, 60 to 1, 25 to 1)) {
            for (f in listOf(0L, 1L, 2L, 29L, 1000L, 54_321L, 100_000L)) {
                assertEquals("fps $n/$d frame $f", f, usToFrame(framesToUs(f, n, d), n, d))
            }
        }
    }

    @Test
    fun knownConversions() {
        assertEquals(1_000_000L, framesToUs(30, 30, 1))
        assertEquals(33_367L, framesToUs(1, 30000, 1001)) // 33366.67 dibulatkan
        assertEquals(30L, usToFrame(1_000_000L, 30, 1))
    }

    @Test
    fun formatTime() {
        assertEquals("0:00.0", formatTime(0))
        assertEquals("1:05.5", formatTime(65_500_000))
        assertEquals("0:00.0", formatTime(-5))
    }
}
