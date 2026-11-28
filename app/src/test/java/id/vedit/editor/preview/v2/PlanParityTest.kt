package id.vedit.editor.preview.v2

import id.vedit.editor.domain.Crop
import id.vedit.editor.domain.FitMode
import id.vedit.editor.engine.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Rust (utama) dan Kotlin (cadangan) harus memberi rencana & geometri yang sama. Dilewati jika .so host tidak ada. */
class PlanParityTest {
    private val v = listOf(vsrc("a"), vsrc("b"), isrc("img"), asrc("m"))
    private val projects = listOf(
        project(v, listOf(clip("c1", "a", 0.0, 0.0, 3.0), clip("c2", "b", 3.0, 5.0, 8.0))),
        project(v, listOf(clip("c1", "a", 0.5, 1.0, 5.0, speed = 2f), clip("c2", "img", 3.0, 0.0, 2.0)), listOf(clip("m1", "m", 1.0, 2.0, 5.0, vol = 0.3f))),
        project(v, emptyList()),
    )

    @Test fun kotlinMemberiGapDanPemetaan() {
        val p = KotlinPlanProvider.plan(projects[1])
        assertEquals(listOf(SegKind.GAP, SegKind.VIDEO, SegKind.GAP, SegKind.IMAGE), p.video.map { it.kind })
        assertEquals(listOf(SegKind.GAP, SegKind.AUDIO), p.audio.map { it.kind })
        val seg = p.video[1]
        assertEquals(seg.srcInUs + 1_000_000, seg.sourcePosUs(seg.startUs + 500_000))
        assertEquals(seg.startUs + 500_000, seg.timelinePosUs(seg.srcInUs + 1_000_000))
    }

    @Test fun rencanaRustSamaDenganKotlin() {
        assumeTrue("libvedit_bridge host tidak tersedia", NativeBridge.isAvailable)
        for (p in projects) assertEquals(KotlinPlanProvider.plan(p), RustPlanProvider.plan(p))
    }

    @Test fun geometriRustSamaDenganKotlin() {
        assumeTrue("libvedit_bridge host tidak tersedia", NativeBridge.isAvailable)
        val crops = listOf(null, Crop(0.1f, 0.2f, 0.3f, 0.1f))
        for (fit in FitMode.values()) for (rot in listOf(0, 90, 180, 270)) for (c in crops) for ((vw, vh) in listOf(360.0 to 640.0, 640.0 to 360.0)) {
            val a = KotlinPlanProvider.layout(vw, vh, 1280.0, 720.0, rot, c, fit)
            val b = RustPlanProvider.layout(vw, vh, 1280.0, 720.0, rot, c, fit)
            for (i in 0 until 6) assertEquals("m[$i] $fit $rot $c", a.m[i], b.m[i], 1e-6)
            for (i in 0 until 4) assertEquals("clip[$i] $fit $rot $c", a.clip[i], b.clip[i], 1e-6)
        }
    }

    @Test fun geometriFitFillBasic() {
        val fit = KotlinPlanProvider.layout(360.0, 640.0, 1280.0, 720.0, 0, null, FitMode.FIT)
        assertEquals(0.28125, fit.m[0], 1e-9) // 360/1280
        val fill = KotlinPlanProvider.layout(360.0, 640.0, 1280.0, 720.0, 0, null, FitMode.FILL)
        assertEquals(640.0 / 720.0, fill.m[0], 1e-9)
        assertTrue(fill.clip[2] - fill.clip[0] <= 360.0 + 1e-9)
    }
}
