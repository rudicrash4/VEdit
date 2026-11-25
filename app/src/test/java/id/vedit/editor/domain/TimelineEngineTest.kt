package id.vedit.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEngineTest {
    private val s = 1_000_000L
    private val video = MediaSource("m1", "content://v1", MediaType.VIDEO, "v1.mp4", 10 * s, 1920, 1080, true)
    private val audio = MediaSource("m2", "content://a1", MediaType.AUDIO, "a1.mp3", 8 * s, hasAudio = true)
    private val image = MediaSource("m3", "content://i1", MediaType.IMAGE, "i1.jpg", DEFAULT_IMAGE_DURATION_US, 800, 600)

    private fun base(): Project = Project.create("p", "Test", 0).copy(sources = listOf(video, audio, image))

    private fun ok(e: Edit): Project {
        assertTrue("diharapkan Ok tetapi: $e", e is Edit.Ok)
        return (e as Edit.Ok).project.also { assertEquals(emptyList<String>(), it.validate()) }
    }

    private fun err(e: Edit): String {
        assertTrue("diharapkan Err tetapi: $e", e is Edit.Err)
        return (e as Edit.Err).reason
    }

    private fun withTwo(): Project {
        var p = ok(TimelineEngine.addClip(base(), "v1", "m1", "c1"))
        p = ok(TimelineEngine.addClip(p, "v1", "m1", "c2"))
        return p
    }

    @Test
    fun addAppendsAtTrackEndAndRejectsWrongTrackType() {
        val p = withTwo()
        assertEquals(0L, p.clip("c1")!!.startUs)
        assertEquals(10 * s, p.clip("c2")!!.startUs)
        assertEquals(20 * s, p.durationUs)
        err(TimelineEngine.addClip(p, "a1", "m1", "x"))
        err(TimelineEngine.addClip(p, "v1", "m2", "x"))
        ok(TimelineEngine.addClip(p, "a1", "m2", "a"))
        err(TimelineEngine.addClip(p, "v1", "m1", "x", startUs = 5 * s))
    }

    @Test
    fun imageClipUsesDefaultDurationAndMayBeExtended() {
        var p = ok(TimelineEngine.addClip(base(), "v1", "m3", "i"))
        assertEquals(5 * s, p.clip("i")!!.durationUs)
        p = ok(TimelineEngine.trimEnd(p, "i", 9 * s))
        assertEquals(9 * s, p.clip("i")!!.durationUs)
    }

    @Test
    fun splitKeepsContiguityAndSourceCoverage() {
        val p = ok(TimelineEngine.split(withTwo(), "c1", 4 * s, "c1b"))
        val l = p.clip("c1")!!
        val r = p.clip("c1b")!!
        assertEquals(4 * s, l.durationUs)
        assertEquals(l.endUs, r.startUs)
        assertEquals(l.sourceOutUs, r.sourceInUs)
        assertEquals(10 * s, r.sourceOutUs)
        assertEquals(20 * s, p.durationUs) // total tidak berubah
    }

    @Test
    fun splitHonorsSpeedMapping() {
        var p = ok(TimelineEngine.addClip(base(), "v1", "m1", "c"))
        val fast = p.clip("c")!!.copy(speed = 2f) // 10 dtk sumber -> 5 dtk timeline
        p = p.copy(tracks = p.tracks.map { t -> t.copy(clips = t.clips.map { if (it.id == "c") fast else it }) })
        p = ok(TimelineEngine.split(p, "c", 1 * s, "c2"))
        assertEquals(2 * s, p.clip("c2")!!.sourceInUs) // 1 dtk timeline = 2 dtk sumber
        assertEquals(1 * s, p.clip("c")!!.durationUs)
        assertEquals(4 * s, p.clip("c2")!!.durationUs)
    }

    @Test
    fun splitRejectsEdgesAndOutside() {
        val p = withTwo()
        err(TimelineEngine.split(p, "c1", 50_000, "x"))
        err(TimelineEngine.split(p, "c1", 10 * s - 50_000, "x"))
        err(TimelineEngine.split(p, "c1", 11 * s, "x"))
        err(TimelineEngine.split(p, "nope", 5 * s, "x"))
    }

    @Test
    fun trimStartAndEnd() {
        var p = withTwo()
        p = ok(TimelineEngine.trimEnd(p, "c1", 7 * s))
        assertEquals(7 * s, p.clip("c1")!!.durationUs)
        // celah di timeline lalu c2 trimStart maju: start dan sourceIn ikut bergeser
        p = ok(TimelineEngine.trimStart(p, "c2", 12 * s))
        val c2 = p.clip("c2")!!
        assertEquals(12 * s, c2.startUs)
        assertEquals(2 * s, c2.sourceInUs)
        assertEquals(8 * s, c2.durationUs)
        // perpanjang kembali ke kiri sampai batas sumber
        p = ok(TimelineEngine.trimStart(p, "c2", 10 * s))
        assertEquals(0L, p.clip("c2")!!.sourceInUs)
        err(TimelineEngine.trimStart(p, "c2", 9 * s)) // sumber habis
    }

    @Test
    fun trimRejectsOverlapSourceOverrunAndTooShort() {
        val p = withTwo()
        err(TimelineEngine.trimEnd(p, "c1", 11 * s)) // melewati media sumber
        val shortened = ok(TimelineEngine.trimEnd(p, "c1", 5 * s))
        err(TimelineEngine.trimEnd(shortened, "c1", 5 * s + 10 * s)) // melewati sumber & c2
        err(TimelineEngine.trimStart(p, "c1", 10 * s - 50_000))
        err(TimelineEngine.trimEnd(p, "c1", 50_000))
        err(TimelineEngine.trimStart(p, "c2", 5 * s)) // menimpa c1 (sumber tersedia? in<0 -> ditolak)
    }

    @Test
    fun deleteWithAndWithoutRipple() {
        val p = ok(TimelineEngine.addClip(withTwo(), "v1", "m1", "c3"))
        val gap = ok(TimelineEngine.delete(p, "c2"))
        assertNull(gap.clip("c2"))
        assertEquals(20 * s, gap.clip("c3")!!.startUs)
        val rip = ok(TimelineEngine.delete(p, "c2", ripple = true))
        assertEquals(10 * s, rip.clip("c3")!!.startUs)
        assertEquals(20 * s, rip.durationUs)
    }

    @Test
    fun duplicateInsertsAfterAndShiftsFollowing() {
        val p = ok(TimelineEngine.duplicate(withTwo(), "c1", "c1copy"))
        assertEquals(10 * s, p.clip("c1copy")!!.startUs)
        assertEquals(20 * s, p.clip("c2")!!.startUs)
        assertEquals(30 * s, p.durationUs)
        assertEquals(p.clip("c1")!!.sourceInUs, p.clip("c1copy")!!.sourceInUs)
    }

    @Test
    fun reorderPacksClipsBackToBack() {
        var p = ok(TimelineEngine.split(withTwo(), "c1", 4 * s, "c1b")) // c1(4) c1b(6) c2(10)
        p = ok(TimelineEngine.delete(p, "c1b")) // celah 4..10
        p = ok(TimelineEngine.reorder(p, "c2", 0))
        val order = p.tracks[0].clips.map { it.id }
        assertEquals(listOf("c2", "c1"), order)
        assertEquals(0L, p.clip("c2")!!.startUs)
        assertEquals(10 * s, p.clip("c1")!!.startUs)
        assertEquals(14 * s, p.durationUs)
        // indeks di luar jangkauan dijepit
        p = ok(TimelineEngine.reorder(p, "c2", 99))
        assertEquals(listOf("c1", "c2"), p.tracks[0].clips.map { it.id })
    }

    @Test
    fun moveRejectsOverlap() {
        val p = withTwo()
        err(TimelineEngine.move(p, "c1", 5 * s))
        val moved = ok(TimelineEngine.move(ok(TimelineEngine.delete(p, "c2")), "c1", 3 * s))
        assertEquals(3 * s, moved.clip("c1")!!.startUs)
        err(TimelineEngine.move(p, "c1", -1))
    }

    @Test
    fun rotationAndCrop() {
        var p = withTwo()
        p = ok(TimelineEngine.setRotation(p, "c1", 450))
        assertEquals(90, p.clip("c1")!!.rotationDeg)
        p = ok(TimelineEngine.setRotation(p, "c1", -90))
        assertEquals(270, p.clip("c1")!!.rotationDeg)
        err(TimelineEngine.setRotation(p, "c1", 45))
        val crop = Crop.centered(1f, 16f / 9f)
        assertEquals(0f, crop.top, 1e-6f)
        assertEquals((1f - 9f / 16f) / 2f, crop.left, 1e-5f)
        p = ok(TimelineEngine.setCrop(p, "c1", crop))
        assertNotNull(p.clip("c1")!!.crop)
        p = ok(TimelineEngine.setCrop(p, "c1", null))
        assertNull(p.clip("c1")!!.crop)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cropRejectsDegenerateArea() {
        Crop(left = 0.6f, right = 0.6f)
    }

    @Test
    fun clipAtIsHalfOpen() {
        val p = withTwo()
        assertEquals("c1", TimelineEngine.clipAt(p, "v1", 0)!!.id)
        assertEquals("c2", TimelineEngine.clipAt(p, "v1", 10 * s)!!.id)
        assertNull(TimelineEngine.clipAt(p, "v1", 20 * s))
    }

    @Test
    fun validateCatchesBrokenProject() {
        val p = withTwo()
        val broken = p.copy(tracks = p.tracks.map { t -> t.copy(clips = t.clips.map { if (it.id == "c2") it.copy(startUs = 5 * s) else it }) })
        assertTrue(broken.validate().any { it.contains("tumpang tindih") })
        val noSource = p.copy(sources = emptyList())
        assertTrue(noSource.validate().any { it.contains("sumber") })
    }
}
