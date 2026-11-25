package id.vedit.editor.data

import id.vedit.editor.domain.Crop
import id.vedit.editor.domain.EditHistory
import id.vedit.editor.domain.Edit
import id.vedit.editor.domain.MediaSource
import id.vedit.editor.domain.MediaType
import id.vedit.editor.domain.PreviewPlan
import id.vedit.editor.domain.Project
import id.vedit.editor.domain.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alur edit lengkap lewat EditHistory: bukti fungsional (bukan sekadar tombol) bahwa edit tersimpan di model. */
class EditorFlowTest {
    private val s = 1_000_000L
    private val video = MediaSource("m1", "content://v", MediaType.VIDEO, "v.mp4", 10 * s, 1920, 1080, true)
    private val image = MediaSource("m3", "content://i", MediaType.IMAGE, "i.jpg", 5 * s, 800, 600)

    private fun step(h: EditHistory, e: Edit): Project {
        assertTrue("edit gagal: $e", e is Edit.Ok)
        val p = (e as Edit.Ok).project
        assertEquals(emptyList<String>(), p.validate())
        h.apply(p)
        return p
    }

    @Test
    fun fullEditFlowUndoRedoAndPersistence() {
        val initial = Project.create("p", "Flow", 0).copy(sources = listOf(video, image))
        val h = EditHistory(initial)
        step(h, TimelineEngine.addClip(h.current, "v1", "m1", "c1"))
        step(h, TimelineEngine.addClip(h.current, "v1", "m3", "c2"))
        step(h, TimelineEngine.split(h.current, "c1", 4 * s, "c1b"))
        assertEquals(3, h.current.tracks[0].clips.size)
        step(h, TimelineEngine.duplicate(h.current, "c2", "c2d"))
        step(h, TimelineEngine.reorder(h.current, "c2", 0))
        assertEquals("c2", h.current.tracks[0].clips.minByOrNull { it.startUs }!!.id)
        step(h, TimelineEngine.setRotation(h.current, "c1", 90))
        step(h, TimelineEngine.setCrop(h.current, "c1b", Crop(0.1f, 0f, 0.1f, 0f)))
        step(h, TimelineEngine.trimEnd(h.current, "c1b", h.current.clip("c1b")!!.startUs + 3 * s))
        step(h, TimelineEngine.delete(h.current, "c2d", ripple = true))
        assertNull(h.current.clip("c2d"))

        // Rotasi, crop dan trim tersimpan di model dan lolos round trip JSON.
        val codec = ProjectCodec()
        val back = codec.decode(codec.encode(h.current)).getOrThrow()
        assertEquals(h.current.tracks, back.tracks)
        assertEquals(90, back.clip("c1")!!.rotationDeg)
        assertNotNull(back.clip("c1b")!!.crop)

        // Setiap edit mengubah struktur preview (memicu pembangunan ulang); undo mengembalikannya.
        val final = h.current
        var count = 0
        while (h.undo() != null) count++
        assertEquals(9, count)
        assertEquals(initial, h.current)
        assertFalse(h.canUndo)
        while (h.redo() != null) { /* ulang semuanya */ }
        assertEquals(final, h.current)
        assertTrue(PreviewPlan.structureChanged(initial, final))
    }

    @Test
    fun rotationAndCropChangeStructureButPlayheadClampedAfterShrink() {
        val p0 = Project.create("p", "x", 0).copy(sources = listOf(video))
        val p1 = (TimelineEngine.addClip(p0, "v1", "m1", "c1") as Edit.Ok).project
        val p2 = (TimelineEngine.setRotation(p1, "c1", 180) as Edit.Ok).project
        assertTrue(PreviewPlan.structureChanged(p1, p2))
        val p3 = (TimelineEngine.trimEnd(p2, "c1", 2 * s) as Edit.Ok).project
        assertEquals(2 * s, PreviewPlan.clampPlayhead(9 * s, p3.durationUs))
    }
}
