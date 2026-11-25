package id.vedit.editor.domain

import id.vedit.editor.data.ProjectCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasTest {
    private fun dims(a: AspectPreset, q: OutputQuality) = CanvasSpec.size(a, q.shortSide).let { it.width to it.height }

    @Test
    fun allPresetsProduceExpectedExportDimensions() {
        assertEquals(1280 to 720, dims(AspectPreset.R16_9, OutputQuality.P720))
        assertEquals(720 to 1280, dims(AspectPreset.R9_16, OutputQuality.P720))
        assertEquals(720 to 720, dims(AspectPreset.R1_1, OutputQuality.P720))
        assertEquals(720 to 900, dims(AspectPreset.R4_5, OutputQuality.P720))
        // arsitektur siap 1080p (belum diaktifkan untuk export)
        assertEquals(1920 to 1080, dims(AspectPreset.R16_9, OutputQuality.P1080))
        assertEquals(1080 to 1920, dims(AspectPreset.R9_16, OutputQuality.P1080))
        assertEquals(1080 to 1080, dims(AspectPreset.R1_1, OutputQuality.P1080))
        assertEquals(1080 to 1350, dims(AspectPreset.R4_5, OutputQuality.P1080))
    }

    @Test
    fun allDimensionsAreEvenAndMatchRatio() {
        for (a in AspectPreset.values()) for (q in OutputQuality.values()) {
            val s = CanvasSpec.size(a, q.shortSide)
            assertTrue("$a $q", s.width % 2 == 0 && s.height % 2 == 0)
            assertEquals("$a $q", a.ratio.toDouble(), s.width.toDouble() / s.height, 0.01)
        }
    }

    @Test
    fun previewIsLowerThanExportButSameAspect() {
        for (a in AspectPreset.values()) {
            val pv = CanvasSpec.size(a, OutputQuality.PREVIEW.shortSide)
            val ex = CanvasSpec.size(a, OutputQuality.P720.shortSide)
            assertTrue(pv.width * pv.height < ex.width * ex.height)
            assertEquals(pv.width.toDouble() / pv.height, ex.width.toDouble() / ex.height, 0.01)
        }
    }

    @Test
    fun exportPlanFollowsProjectCanvasAndRejectsUnprovenQuality() {
        val p = Project.create("p", "x", 0).copy(canvas = CanvasSettings(AspectPreset.R9_16))
        assertEquals(CanvasSize(720, 1280), ExportPlan.sizeFor(p))
        val threw = runCatching { ExportPlan.sizeFor(p, OutputQuality.P1080) }.isFailure
        assertTrue(threw)
    }

    @Test
    fun newProjectDefaultsTo16x9Fit() {
        assertEquals(CanvasSettings(AspectPreset.R16_9, FitMode.FIT), Project.create("p", "x", 0).canvas)
    }

    @Test
    fun canvasSurvivesJsonRoundTripAndUndoRedo() {
        val base = Project.create("p", "x", 0)
        val p = (TimelineEngine.setCanvas(base, CanvasSettings(AspectPreset.R4_5, FitMode.FILL)) as Edit.Ok).project
        val codec = ProjectCodec()
        assertEquals(CanvasSettings(AspectPreset.R4_5, FitMode.FILL), codec.decode(codec.encode(p)).getOrThrow().canvas)
        val h = EditHistory(base)
        h.apply(p)
        assertEquals(AspectPreset.R16_9, h.undo()!!.canvas.aspect)
        assertEquals(AspectPreset.R4_5, h.redo()!!.canvas.aspect)
    }

    @Test
    fun v1ProjectWithoutCanvasMigratesToDefault() {
        val v1 = """{"schemaVersion":1,"id":"old","name":"Lama","sources":[],"tracks":[{"id":"v1","type":"VIDEO","clips":[]},{"id":"a1","type":"AUDIO","clips":[]}],"createdAtMs":1,"modifiedAtMs":2}"""
        val p = ProjectCodec().decode(v1).getOrThrow()
        assertEquals(CanvasSettings(), p.canvas)
        assertEquals("Lama", p.name)
        assertEquals(Project.CURRENT_SCHEMA, p.schemaVersion)
        // simpan ulang lalu baca: kanvas ikut tersimpan
        val again = ProjectCodec().decode(ProjectCodec().encode(p)).getOrThrow()
        assertEquals(p.canvas, again.canvas)
    }

    @Test
    fun canvasChangeCountsAsPreviewStructureChange() {
        val a = Project.create("p", "x", 0)
        assertTrue(PreviewPlan.structureChanged(a, a.copy(canvas = CanvasSettings(AspectPreset.R1_1))))
    }
}
