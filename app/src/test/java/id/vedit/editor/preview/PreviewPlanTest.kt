package id.vedit.editor.preview

import id.vedit.editor.domain.Clip
import id.vedit.editor.domain.Edit
import id.vedit.editor.domain.MediaSource
import id.vedit.editor.domain.MediaType
import id.vedit.editor.domain.PreviewPlan
import id.vedit.editor.domain.PreviewPlan.Item
import id.vedit.editor.domain.Project
import id.vedit.editor.domain.TimelineEngine
import id.vedit.editor.domain.Track
import id.vedit.editor.domain.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPlanTest {
    private val s = 1_000_000L
    private val src = MediaSource("m", "content://x", MediaType.VIDEO, "x.mp4", 20 * s, 1280, 720, true)
    private fun clip(id: String, start: Long, len: Long) = Clip(id, "m", start * s, 0, len * s)
    private fun project(vararg clips: Clip) =
        Project.create("p", "t", 0).copy(sources = listOf(src), tracks = listOf(Track("v1", TrackType.VIDEO, clips.toList()), Track("a1", TrackType.AUDIO)))

    @Test
    fun sequenceItemsInsertsLeadingAndInnerGapsButNoTrailingGap() {
        val items = PreviewPlan.sequenceItems(project(clip("b", 8, 4), clip("a", 2, 3)).tracks[0])
        assertEquals(
            listOf("Gap:2000000", "a", "Gap:3000000", "b"),
            items.map { if (it is Item.Gap) "Gap:${it.durationUs}" else (it as Item.ClipItem).clip.id },
        )
    }

    @Test
    fun contiguousClipsProduceNoGapsAndTotalLengthMatchesTrackEnd() {
        val track = project(clip("a", 0, 5), clip("b", 5, 5)).tracks[0]
        val items = PreviewPlan.sequenceItems(track)
        assertTrue(items.none { it is Item.Gap })
        val total = items.sumOf { if (it is Item.ClipItem) it.clip.durationUs else (it as Item.Gap).durationUs }
        assertEquals(10 * s, total)
    }

    @Test
    fun sequenceDurationEqualsProjectDurationWithGaps() {
        val p = project(clip("a", 1, 2), clip("b", 6, 2))
        val items = PreviewPlan.sequenceItems(p.tracks[0])
        val total = items.sumOf { if (it is Item.ClipItem) it.clip.durationUs else (it as Item.Gap).durationUs }
        assertEquals(p.durationUs, total)
    }

    @Test
    fun locateMovesBetweenClipsAtHalfOpenBoundaries() {
        val clips = project(clip("a", 0, 5), clip("b", 5, 5)).tracks[0].clips
        assertEquals(PreviewPlan.Located(0, 0, false, false), PreviewPlan.locate(clips, 0))
        assertEquals(PreviewPlan.Located(0, 5 * s - 1, false, false), PreviewPlan.locate(clips, 5 * s - 1))
        // tepat di batas: sudah klip berikutnya
        assertEquals(PreviewPlan.Located(1, 0, false, false), PreviewPlan.locate(clips, 5 * s))
        assertEquals(PreviewPlan.Located(1, 4 * s, false, false), PreviewPlan.locate(clips, 9 * s))
    }

    @Test
    fun locateInGapPointsToNextClipAndPastEndIsAtEnd() {
        val clips = project(clip("a", 0, 2), clip("b", 6, 2)).tracks[0].clips
        assertEquals(PreviewPlan.Located(1, 0, true, false), PreviewPlan.locate(clips, 3 * s))
        assertEquals(PreviewPlan.Located(1, 2 * s, false, true), PreviewPlan.locate(clips, 100 * s))
        assertEquals(PreviewPlan.Located(0, 0, false, false), PreviewPlan.locate(clips, -5))
        assertNull(PreviewPlan.locate(emptyList(), 0))
    }

    @Test
    fun locateAndToTimelineAreInverse() {
        val clips = project(clip("a", 0, 3), clip("b", 4, 3)).tracks[0].clips
        for (t in listOf(0L, 1 * s, 2_999_999L, 4 * s, 6 * s + 500_000)) {
            val loc = PreviewPlan.locate(clips, t)!!
            assertFalse(loc.inGap)
            assertEquals(t, PreviewPlan.toTimelineUs(clips, loc.index, loc.offsetUs))
        }
    }

    @Test
    fun splitAndTrimChangeThePreviewStructure() {
        val before = project(clip("a", 0, 10))
        val split = (TimelineEngine.split(before, "a", 4 * s, "b") as Edit.Ok).project
        assertTrue(PreviewPlan.structureChanged(before, split))
        val items = PreviewPlan.sequenceItems(split.tracks[0])
        assertEquals(2, items.size)
        val trimmed = (TimelineEngine.trimEnd(before, "a", 6 * s) as Edit.Ok).project
        assertTrue(PreviewPlan.structureChanged(before, trimmed))
        assertEquals(6 * s, trimmed.durationUs)
    }

    @Test
    fun reorderChangesStructureAndOrderOfPlayback() {
        val a = clip("a", 0, 3)
        val b = clip("b", 3, 5)
        val before = project(a, b)
        val after = (TimelineEngine.reorder(before, "b", 0) as Edit.Ok).project
        assertTrue(PreviewPlan.structureChanged(before, after))
        assertEquals(listOf("b", "a"), PreviewPlan.sequenceItems(after.tracks[0]).map { (it as Item.ClipItem).clip.id })
    }

    @Test
    fun renameOrTimestampDoesNotRebuildPreview() {
        val p = project(clip("a", 0, 3))
        assertFalse(PreviewPlan.structureChanged(p, p.copy(name = "lain", modifiedAtMs = 99)))
        assertTrue(PreviewPlan.structureChanged(null, p))
    }

    @Test
    fun rotationAndCropChangeStructure() {
        val p = project(clip("a", 0, 3))
        val rotated = (TimelineEngine.setRotation(p, "a", 90) as Edit.Ok).project
        assertTrue(PreviewPlan.structureChanged(p, rotated))
    }

    @Test
    fun playableContentAndClamping() {
        assertFalse(PreviewPlan.hasPlayableContent(Project.create("p", "t", 0)))
        assertTrue(PreviewPlan.hasPlayableContent(project(clip("a", 0, 3))))
        assertEquals(0L, PreviewPlan.clampPlayhead(-5, 10))
        assertEquals(10L, PreviewPlan.clampPlayhead(50, 10))
        val c = clip("a", 2, 3)
        assertTrue(PreviewPlan.playheadInside(c, 2 * s))
        assertFalse(PreviewPlan.playheadInside(c, 5 * s))
    }
}
