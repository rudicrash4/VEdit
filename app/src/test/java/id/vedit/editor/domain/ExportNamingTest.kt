package id.vedit.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportNamingTest {
    @Test
    fun sanitizesUnsafeCharacters() {
        val n = ExportNaming.fileName("Liburan/Bali: \"2026\"?", 0)
        assertTrue(n.endsWith(".mp4"))
        assertFalse(n.contains('/') || n.contains(':') || n.contains('"') || n.contains(' '))
        assertEquals("VEdit_Liburan_Bali___2026_19700101_000000.mp4", n)
    }

    @Test
    fun emptyOrSymbolOnlyNameFallsBackAndLongNamesAreCut() {
        assertTrue(ExportNaming.fileName("///", 0).startsWith("VEdit_Proyek_"))
        assertTrue(ExportNaming.fileName("x".repeat(200), 0).length < 80)
    }
}
