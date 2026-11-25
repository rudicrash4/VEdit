package id.vedit.editor.preview

import android.view.SurfaceView
import id.vedit.editor.domain.AspectPreset
import id.vedit.editor.domain.CanvasSettings
import id.vedit.editor.domain.Clip
import id.vedit.editor.domain.FitMode
import id.vedit.editor.domain.MediaSource
import id.vedit.editor.domain.MediaType
import id.vedit.editor.domain.Project
import id.vedit.editor.domain.Track
import id.vedit.editor.domain.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewControllerTest {
    private val s = 1_000_000L

    private class FakeEngine(val cb: EngineCallbacks, val fallback: Boolean) : PreviewEngine {
        override val name = if (fallback) "exo" else "composition"
        override var isPlaying = false
        override var positionUs = 0L
        var loaded: Pair<Project, Long>? = null
        var released = false
        val seeks = ArrayList<Long>()
        var played = 0
        override fun attach(surface: SurfaceView?) {}
        override fun load(project: Project, startPositionUs: Long) { loaded = project to startPositionUs }
        override fun seekTo(us: Long) { seeks += us; positionUs = us }
        override fun play() { played++; isPlaying = true }
        override fun pause() { isPlaying = false }
        override fun release() { released = true }
    }

    private val engines = ArrayList<FakeEngine>()
    private fun controller() = PreviewController { cb, fb -> FakeEngine(cb, fb).also { engines += it } }

    private val src = MediaSource("m", "content://x", MediaType.VIDEO, "x.mp4", 20 * s, 1280, 720, true)
    private fun project(withClip: Boolean = true) = Project.create("p", "t", 0).copy(
        sources = listOf(src),
        tracks = listOf(
            Track("v1", TrackType.VIDEO, if (withClip) listOf(Clip("c", "m", 0, 0, 10 * s)) else emptyList()),
            Track("a1", TrackType.AUDIO),
        ),
    )

    @Test
    fun emptyProjectCreatesNoEngineAndShowsNoContent() {
        val c = controller()
        c.update(project(withClip = false), 0)
        assertTrue(engines.isEmpty())
        assertFalse(c.ui.hasContent)
    }

    @Test
    fun loadingThenReadyAppliesWantedPosition() {
        val c = controller()
        c.update(project(), 3 * s)
        val e = engines.single()
        assertEquals(3 * s, e.loaded!!.second)
        assertTrue(c.ui.loading)
        assertFalse(c.ui.ready)
        c.seekTo(4 * s) // saat belum siap: hanya diingat
        assertTrue(e.seeks.isEmpty())
        e.cb.onReady()
        assertTrue(c.ui.ready)
        assertFalse(c.ui.loading)
        assertEquals(listOf(4 * s), e.seeks)
        c.seekTo(6 * s)
        assertEquals(listOf(4 * s, 6 * s), e.seeks)
    }

    @Test
    fun seekIsClampedToProjectDuration() {
        val c = controller()
        c.update(project(), 0)
        engines.single().cb.onReady()
        c.seekTo(999 * s)
        assertEquals(10 * s, engines.single().seeks.last())
    }

    @Test
    fun compositionErrorFallsBackToExoOnceThenShowsError() {
        val c = controller()
        c.update(project(), 0)
        engines[0].cb.onError("boom")
        assertEquals(2, engines.size)
        assertTrue(engines[1].fallback)
        assertTrue(c.ui.notice.contains("boom"))
        assertEquals("", c.ui.error)
        engines[1].cb.onError("lagi")
        assertEquals(2, engines.size) // tidak ada mesin ketiga
        assertEquals("lagi", c.ui.error)
        assertFalse(c.ui.loading)
    }

    @Test
    fun rebuildReleasesPreviousEngine() {
        val c = controller()
        c.update(project(), 0)
        c.update(project().copy(canvas = CanvasSettings(AspectPreset.R9_16)), 2 * s)
        assertTrue(engines[0].released)
        assertFalse(engines[1].released)
        assertEquals(2 * s, engines[1].loaded!!.second)
    }

    @Test
    fun unchangedStructureDoesNotRebuildPlayerOnlyFollowsPosition() {
        val c = controller()
        c.update(project(), 0)
        engines[0].cb.onReady()
        c.update(project().copy(name = "rename", modifiedAtMs = 5), 3 * s)
        assertEquals(1, engines.size)
        assertFalse(engines[0].released)
        assertEquals(1, c.ui.buildCount)
        assertEquals(3 * s, engines[0].seeks.last())
    }

    @Test
    fun canvasChangeTriggersRebuildAndBuildTimeIsMeasured() {
        var now = 1000L
        val c = PreviewController({ now }) { cb, fb -> FakeEngine(cb, fb).also { engines += it } }
        c.update(project(), 0)
        now = 1450L
        engines[0].cb.onReady()
        assertEquals(450L, c.ui.lastBuildMs)
        c.update(project().copy(canvas = CanvasSettings(fit = FitMode.FILL)), 0)
        assertEquals(2, engines.size)
        assertEquals(2, c.ui.buildCount)
    }

    @Test
    fun playRestartsFromBeginningWhenAtEnd() {
        val c = controller()
        c.update(project(), 0)
        val e = engines.single()
        e.cb.onReady()
        e.positionUs = 10 * s
        c.play()
        assertEquals(0L, e.seeks.last())
        assertEquals(1, e.played)
    }

    @Test
    fun playIgnoredUntilReady() {
        val c = controller()
        c.update(project(), 0)
        c.play()
        assertEquals(0, engines.single().played)
    }

    @Test
    fun playingStateFollowsEngineCallbacks() {
        val c = controller()
        c.update(project(), 0)
        val e = engines.single()
        e.cb.onReady()
        e.cb.onPlayingChanged(true)
        assertTrue(c.ui.isPlaying)
        e.cb.onEnded()
        assertFalse(c.ui.isPlaying)
    }

    @Test
    fun releaseStopsEverythingAndIgnoresLaterUpdates() {
        val c = controller()
        c.update(project(), 0)
        c.release()
        assertTrue(engines.single().released)
        c.update(project(), 0)
        assertEquals(1, engines.size)
        engines.single().cb.onError("terlambat") // callback setelah release tidak boleh membuat mesin baru
        assertEquals(1, engines.size)
    }
}
