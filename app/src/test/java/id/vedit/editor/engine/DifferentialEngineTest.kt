package id.vedit.editor.engine

import id.vedit.editor.domain.AspectPreset
import id.vedit.editor.domain.CanvasSettings
import id.vedit.editor.domain.Crop
import id.vedit.editor.domain.Edit
import id.vedit.editor.domain.FitMode
import id.vedit.editor.domain.MediaSource
import id.vedit.editor.domain.MediaType
import id.vedit.editor.domain.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * Smoke test native + differential test: engine Rust vs engine Kotlin lama harus identik pada urutan operasi acak
 * (proyek hasil, pesan galat, undo/redo). Berjalan di JVM host (pustaka host dibuild Gradle); BUKAN uji di perangkat.
 */
class DifferentialEngineTest {
    private val s = 1_000_000L

    @Before
    fun requireNativeOrSkipLocally() {
        val available = NativeBridge.isAvailable
        if (System.getenv("VEDIT_REQUIRE_NATIVE") == "1") {
            assertTrue("Pustaka native wajib termuat di CI: ${NativeBridge.loadError}", available)
        }
        assumeTrue("Pustaka native tidak ada (build lokal tanpa cargo)", available)
    }

    private fun base(): Project = Project.create("p", "Diff Ü", 0).copy(
        sources = listOf(
            MediaSource("v1", "content://v1", MediaType.VIDEO, "v1.mp4", 10 * s, 1920, 1080, true),
            MediaSource("v2", "content://v2", MediaType.VIDEO, "v2.mp4", 7 * s + 333_333, 1080, 1920, true),
            MediaSource("a1", "content://a1", MediaType.AUDIO, "a1.mp3", 8 * s, hasAudio = true),
            MediaSource("i1", "content://i1", MediaType.IMAGE, "i1.jpg", 5 * s, 800, 600),
        ),
    )

    @Test
    fun nativeLoadsAndApiVersionMatches() {
        assertEquals(NativeBridge.EXPECTED_API, NativeBridge.nativeApiVersion())
        val r = RustEngine.open(base()).getOrThrow()
        assertEquals(base(), r.project)
        r.close()
    }

    @Test
    fun openRejectsInvalidProjectWithoutCrashing() {
        val bad = base().let { p ->
            p.copy(tracks = listOf(p.tracks[0].copy(clips = listOf(id.vedit.editor.domain.Clip("c", "missing", 0, 0, s))), p.tracks[1]))
        }
        assertTrue(RustEngine.open(bad).isFailure)
        assertTrue(NativeBridge.nativeOpen("{ not json").contains("\"ok\":false"))
        assertTrue(NativeBridge.nativeSnapshot(123456789L).contains("bad_handle"))
    }

    @Test
    fun canvasSizesMatchKotlinSpec() {
        for (a in AspectPreset.values()) for (q in id.vedit.editor.domain.OutputQuality.values()) {
            val k = id.vedit.editor.domain.CanvasSpec.size(a, q.shortSide)
            val r = NativeBridge.nativeCanvasSize(a.name, "FIT", q.name)
            assertTrue("$a $q: $r", r.contains("\"width\":${k.width}") && r.contains("\"height\":${k.height}"))
        }
    }

    private fun randomOp(rnd: Random, p: Project, n: Int): EditOp {
        val clips = p.tracks.flatMap { it.clips }
        fun clipId() = if (clips.isEmpty() || rnd.nextInt(12) == 0) "ghost" else clips[rnd.nextInt(clips.size)].id
        fun t(): Long = (rnd.nextInt(30) * 500_000L) + rnd.nextInt(3) * 123_457L - (if (rnd.nextInt(10) == 0) 2 * s else 0)
        val track = if (rnd.nextInt(4) == 0) "a1" else "v1"
        val srcs = listOf("v1", "v2", "a1", "i1", "nope")
        return when (rnd.nextInt(13)) {
            0, 1 -> EditOp.AddClip(track, srcs[rnd.nextInt(srcs.size)], "n$n", if (rnd.nextBoolean()) null else t())
            2, 3 -> EditOp.Split(clipId(), t(), "n$n")
            4 -> EditOp.TrimStart(clipId(), t())
            5 -> EditOp.TrimEnd(clipId(), t())
            6 -> EditOp.Delete(clipId(), rnd.nextBoolean())
            7 -> EditOp.Duplicate(clipId(), "n$n")
            8 -> EditOp.Move(clipId(), t())
            9 -> EditOp.Reorder(clipId(), rnd.nextInt(6) - 2)
            10 -> EditOp.SetRotation(clipId(), listOf(90, 180, 270, -90, 450, 45)[rnd.nextInt(6)])
            11 -> EditOp.SetCrop(clipId(), if (rnd.nextBoolean()) null else Crop(0.1f * rnd.nextInt(4), 0f, 0.05f, 0.1f))
            else -> EditOp.SetCanvas(CanvasSettings(AspectPreset.values()[rnd.nextInt(4)], FitMode.values()[rnd.nextInt(2)]))
        }
    }

    @Test
    fun randomSequencesBehaveIdenticallyInRustAndKotlin() {
        var okCount = 0
        var errCount = 0
        for (seed in 1..40) {
            val rnd = Random(seed.toLong())
            val k = KotlinEngine(base())
            val r = RustEngine.open(base()).getOrThrow()
            for (i in 0 until 120) {
                val roll = rnd.nextInt(10)
                if (roll == 0) {
                    assertEquals("undo seed=$seed i=$i", k.undo(), r.undo())
                } else if (roll == 1) {
                    assertEquals("redo seed=$seed i=$i", k.redo(), r.redo())
                } else {
                    val op = randomOp(rnd, k.project, i)
                    val now = 1000L + i
                    val a = k.apply(op, now)
                    val b = r.apply(op, now)
                    val ctx = "seed=$seed i=$i op=$op"
                    when (a) {
                        is Edit.Ok -> {
                            okCount++
                            assertTrue("Rust seharusnya Ok: $ctx -> $b", b is Edit.Ok)
                            assertEquals(ctx, a.selectedClipId, (b as Edit.Ok).selectedClipId)
                        }
                        is Edit.Err -> {
                            errCount++
                            assertTrue("Rust seharusnya Err: $ctx", b is Edit.Err)
                            assertEquals(ctx, a.reason, (b as Edit.Err).reason)
                        }
                    }
                }
                assertEquals("project seed=$seed i=$i", k.project, r.project)
                assertEquals(k.canUndo, r.canUndo)
                assertEquals(k.canRedo, r.canRedo)
                assertEquals(emptyList<String>(), r.project.validate())
            }
            r.close()
        }
        // Pastikan generator benar-benar menguji kedua jalur (sukses dan penolakan), bukan hanya satu.
        assertTrue("terlalu sedikit Ok: $okCount", okCount > 500)
        assertTrue("terlalu sedikit Err: $errCount", errCount > 200)
    }

    @Test
    fun speedAndVolumeSurviveRoundTripThroughRust() {
        val p = base()
        val withSpeed = p.copy(
            tracks = listOf(
                p.tracks[0].copy(clips = listOf(id.vedit.editor.domain.Clip("c", "v1", 0, 1 * s, 9 * s, speed = 1.5f, volume = 0.25f, rotationDeg = 90, crop = Crop(0.1f, 0.2f, 0.3f, 0.4f)))),
                p.tracks[1],
            ),
        )
        val r = RustEngine.open(withSpeed).getOrThrow()
        assertEquals(withSpeed, r.project)
        val k = KotlinEngine(withSpeed)
        val op = EditOp.Split("c", 2 * s, "d")
        k.apply(op, 5)
        r.apply(op, 5)
        assertEquals(k.project, r.project)
        r.close()
    }
}
