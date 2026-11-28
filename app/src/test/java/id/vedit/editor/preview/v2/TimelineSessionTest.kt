package id.vedit.editor.preview.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 14 skenario regresi dari spesifikasi + kasus galat/stall. Semua berjalan di JVM dengan slot palsu dan jam virtual. */
class TimelineSessionTest {
    private val v = listOf(vsrc("a"), vsrc("b"), isrc("img"), asrc("m"))

    private fun near(expectedS: Double, actualUs: Long, tolMs: Long = 60) =
        assertTrue("diharapkan ~${expectedS}s, dapat ${actualUs / 1000.0}ms", kotlin.math.abs(actualUs - (expectedS * S).toLong()) <= tolMs * 1000)

    private fun two() = project(v, listOf(clip("c1", "a", 0.0, 0.0, 3.0), clip("c2", "b", 3.0, 5.0, 8.0)))

    // 1
    @Test fun satuVideo_diputarSampaiSelesai() {
        val h = Harness(project(v, listOf(clip("c1", "a", 0.0, 0.0, 4.0))))
        h.load()
        assertEquals(1, h.rec.ready)
        h.run(5000)
        assertEquals(1, h.rec.ended)
        assertFalse(h.session.playing)
        near(4.0, h.session.positionUs)
        assertTrue(h.violations.toString(), h.violations.isEmpty())
    }

    // 2 — skenario inti bug: klip pertama -> kedua
    @Test fun duaVideoBerurutan_slotKeduaSiapSebelumBatasDanLangsungDiputar() {
        val h = Harness(two())
        h.load()
        h.run(2900)
        assertTrue("klip 2 harus sudah dipreload sebelum batas", h.idx(":prepare:VIDEO:5000000") in 0..Int.MAX_VALUE)
        val preloadAt = h.events.first { it.contains(":prepare:VIDEO:5000000") }.substringBefore(':').toLong()
        assertTrue("preload terlalu lambat: $preloadAt", preloadAt < 2900)
        h.run(300)
        val vis = h.visibleSlot()!!
        assertEquals(SlotState.PLAYING, vis.state)
        assertEquals(5 * S, vis.core.lastSeg!!.srcInUs)
        assertEquals(2, h.session.stats.transitions)
        assertEquals(0L, h.session.stats.maxTransitionWaitMs)
        near(3.2, h.session.positionUs)
        h.run(4000)
        assertEquals(1, h.rec.ended)
        near(6.0, h.session.positionUs)
        assertTrue(h.violations.toString(), h.violations.isEmpty())
        assertTrue(h.rec.errors.isEmpty())
    }

    // 3
    @Test fun videoLaluGambar_gambarMengikutiDurasiTimeline() {
        val h = Harness(project(v, listOf(clip("c1", "a", 0.0, 0.0, 2.0), clip("c2", "img", 2.0, 0.0, 3.0))))
        h.load()
        h.run(2300)
        assertEquals(SegKind.IMAGE, h.visibleSlot()!!.core.lastSeg!!.kind)
        near(2.3, h.session.positionUs)
        h.run(3000)
        assertEquals(1, h.rec.ended)
        near(5.0, h.session.positionUs)
        assertTrue(h.violations.toString(), h.violations.isEmpty())
    }

    // 4
    @Test fun gambarLaluVideo_videoMulaiDariTitikTrim() {
        val h = Harness(project(v, listOf(clip("c1", "img", 0.0, 0.0, 2.0), clip("c2", "a", 2.0, 1.0, 4.0))))
        h.load()
        h.run(2500)
        val vis = h.visibleSlot()!!
        assertEquals(SegKind.VIDEO, vis.core.lastSeg!!.kind)
        near(1.5, vis.sourcePositionUs)
        near(2.5, h.session.positionUs)
        h.run(2500)
        assertEquals(1, h.rec.ended)
        assertTrue(h.violations.toString(), h.violations.isEmpty())
    }

    // 5
    @Test fun videoDenganAudio_volumKlipDiteruskanKePemain() {
        val h = Harness(project(v, listOf(clip("c1", "a", 0.0, 0.0, 3.0, vol = 0.5f))))
        h.load()
        assertEquals(0.5f, h.vs.first { it.core.visible }.core.volume, 0f)
    }

    // 6
    @Test fun audioTambahan_dipreloadMulaiTepatWaktuDanBerhentiDiAkhir() {
        val h = Harness(project(v, listOf(clip("c1", "a", 0.0, 0.0, 6.0)), listOf(clip("m1", "m", 1.0, 2.0, 5.0, vol = 0.3f))))
        h.load()
        h.run(500)
        assertTrue("audio dipreload sebelum waktunya", h.events.any { it.contains(":A0:prepare:AUDIO:${2 * S}") })
        assertTrue(h.au.none { it.state == SlotState.PLAYING })
        h.run(900) // t=1.4s
        val a = h.au.first { it.state == SlotState.PLAYING }
        near(2.4, a.sourcePositionUs, 150)
        assertEquals(0.3f, a.core.volume, 0f)
        h.run(3000) // t=4.4s: lewat akhir audio (4.0s)
        assertTrue(h.au.none { it.state == SlotState.PLAYING })
        h.run(2000)
        assertEquals(1, h.rec.ended)
    }

    // 7
    @Test fun celahAntarKlip_hitamDenganJamBerjalan() {
        val h = Harness(project(v, listOf(clip("c1", "a", 0.0, 0.0, 2.0), clip("c2", "b", 4.0, 0.0, 2.0))))
        h.load()
        h.run(3000)
        assertNull(h.visibleSlot())
        near(3.0, h.session.positionUs)
        h.run(1300)
        assertNotNull(h.visibleSlot())
        near(4.3, h.session.positionUs)
        h.run(2000)
        assertEquals(1, h.rec.ended)
        assertTrue(h.violations.toString(), h.violations.isEmpty())
    }

    // 8
    @Test fun mulaiDariAwal_siapSebelumPlay() {
        val h = Harness(two())
        h.load(0.0, play = false)
        assertEquals(1, h.rec.ready)
        assertFalse(h.session.playing)
        h.run(500)
        near(0.0, h.session.positionUs)
        h.session.play()
        h.run(1000)
        near(1.0, h.session.positionUs)
    }

    // 9 — pemicu laporan: mulai langsung dari klip kedua berjalan normal
    @Test fun mulaiLangsungDariKlipKedua() {
        val h = Harness(two())
        h.load(3.5, play = false)
        assertEquals(1, h.rec.ready)
        assertTrue("klip 1 tidak boleh disiapkan", h.events.none { it.contains(":prepare:VIDEO:0") })
        assertTrue(h.events.any { it.contains(":prepare:VIDEO:${(5.5 * S).toLong()}") })
        h.session.play()
        h.run(1000)
        near(4.5, h.session.positionUs)
        near(6.5, h.visibleSlot()!!.sourcePositionUs)
        h.run(3000)
        assertEquals(1, h.rec.ended)
        assertTrue(h.violations.toString(), h.violations.isEmpty())
    }

    // 10
    @Test fun seekMelewatiBatasKlip_majuDanMundur() {
        val h = Harness(two())
        h.load()
        h.run(1000)
        h.session.seekTo((4 * S))
        h.forget()
        h.run(100, checks = false)
        h.run(500)
        near(4.5, h.session.positionUs, 120)
        assertEquals(5 * S, h.visibleSlot()!!.core.lastSeg!!.srcInUs)
        assertTrue(h.session.playing)
        h.session.seekTo((1 * S))
        h.forget()
        h.run(150, checks = false)
        h.run(500)
        near(1.65, h.session.positionUs, 160)
        assertEquals(0L, h.visibleSlot()!!.core.lastSeg!!.srcInUs)
        h.run(6000)
        assertEquals(1, h.rec.ended)
        assertTrue(h.violations.toString(), h.violations.isEmpty())
    }

    // 11
    @Test fun pauseLaluResume_posisiTertahan() {
        val h = Harness(two())
        h.load()
        h.run(1000)
        h.session.pause()
        val p = h.session.positionUs
        h.run(1000, checks = false)
        assertEquals(p, h.session.positionUs)
        assertFalse(h.session.playing)
        h.session.play()
        h.run(1000)
        near((p + S) / S.toDouble(), h.session.positionUs, 80)
    }

    // 12
    @Test fun kecepatan_durasiTimelineDanPemetaanSumber() {
        val h = Harness(project(v, listOf(clip("c1", "a", 0.0, 0.0, 4.0, speed = 2f))))
        assertEquals(2 * S, h.plan.durationUs)
        h.load()
        h.run(1000)
        near(2.0, h.visibleSlot()!!.sourcePositionUs)
        h.run(1500)
        assertEquals(1, h.rec.ended)
        near(2.0, h.session.positionUs)
    }

    // 13
    @Test fun trimDanSplit_potonganDariSumberSamaDimulaiDiTitikMasing2() {
        val h = Harness(project(v, listOf(clip("c1", "a", 0.0, 2.0, 5.0), clip("c2", "a", 3.0, 5.0, 8.0))))
        h.load()
        assertTrue(h.events.any { it.contains(":prepare:VIDEO:${2 * S}") })
        h.run(3200)
        assertEquals(5 * S, h.visibleSlot()!!.core.lastSeg!!.srcInUs)
        near(5.2, h.visibleSlot()!!.sourcePositionUs, 100)
        h.run(3000)
        assertEquals(1, h.rec.ended)
        assertTrue(h.violations.toString(), h.violations.isEmpty())
    }

    // 14
    @Test fun rebuildSetelahPerubahanTimeline_melanjutkanDariPosisiTerakhir() {
        val h = Harness(two())
        h.load()
        h.run(1000)
        val pos = h.session.positionUs
        val p2 = project(v, listOf(clip("c1", "a", 0.0, 0.0, 3.0), clip("c2", "b", 3.0, 5.0, 8.0), clip("c3", "a", 6.0, 0.0, 2.0)))
        val plan2 = KotlinPlanProvider.plan(p2)
        h.session.load(p2, plan2, pos, autoPlay = true)
        h.forget()
        h.run(100, checks = false)
        assertEquals(2, h.rec.ready)
        assertTrue(h.session.playing)
        h.run(400)
        near((pos + 500_000) / S.toDouble(), h.session.positionUs, 120)
        h.run(8000)
        assertEquals(1, h.rec.ended)
        near(8.0, h.session.positionUs)
    }

    // ---- stall / galat
    @Test fun slotKeduaTerlambat_menahanJamDiBatas() {
        val h = Harness(two())
        h.vs.forEach { it.core.prepareMsFor = { seg -> if (seg.clipId == "c2") 5000L else 0L } }
        h.load()
        h.run(3500, checks = false)
        assertTrue("jam harus tertahan di batas", h.session.positionUs <= 3 * S + 50_000)
        assertTrue(h.rec.errors.isEmpty())
        h.run(2000, checks = false) // total 5.5s sejak preload (t=0) > 5s -> siap
        assertTrue(h.session.stats.maxTransitionWaitMs > 0)
        assertTrue(h.session.positionUs > 3 * S)
    }

    @Test fun preloadGagal_lanjutBerurutanTanpaGalatFatal() {
        val h = Harness(two())
        h.vs.forEach { it.core.failures = 0 }
        h.load()
        h.run(100)
        val pre = h.vs.first { !it.core.visible }
        pre.core.failures = 1
        pre.clear()
        pre.prepare(h.plan.video[1], vsrc("b"), 5 * S) // memicu ERROR pada slot preload
        h.run(3500)
        assertTrue(h.rec.errors.toString(), h.rec.errors.isEmpty())
        near(3.5, h.session.positionUs, 120)
        h.run(4000)
        assertEquals(1, h.rec.ended)
    }

    @Test fun galatBerulang_melaporkanErrorDanBerhenti() {
        val h = Harness(two())
        h.vs.forEach { it.core.failures = 100 }
        h.load()
        h.run(20_000, checks = false)
        assertTrue(h.rec.errors.isNotEmpty())
        assertFalse(h.session.playing)
    }

    @Test fun bufferingTengahPutar_jamBerhentiLaluLanjut() {
        val h = Harness(two())
        h.load()
        h.run(1000)
        val p = h.session.positionUs
        h.visibleSlot()!!.core.bufferFor(600)
        h.run(300, checks = false)
        assertTrue(h.session.positionUs - p < 60_000)
        h.run(1000, checks = false)
        assertTrue(h.session.positionUs - p > 500_000)
        assertTrue(h.session.stats.stalls >= 1)
    }

    @Test fun driftAudio_diperbaikiDenganSeekTerbatas() {
        val h = Harness(project(v, listOf(clip("c1", "a", 0.0, 0.0, 8.0)), listOf(clip("m1", "m", 0.0, 0.0, 8.0))))
        h.load()
        h.run(1500)
        val a = h.au.first { it.state == SlotState.PLAYING }
        a.core.skewUs = 600_000
        h.run(300)
        assertTrue(h.session.stats.driftFixes >= 1)
        near(h.session.positionUs / S.toDouble(), a.sourcePositionUs, 150)
        assertTrue(h.session.stats.driftFixes <= 2)
    }

    @Test fun releaseMembersihkanSemuaSlot() {
        val h = Harness(two())
        h.load()
        h.run(500)
        h.session.release()
        assertTrue(h.session.isReleased)
        assertTrue(h.vs.all { it.state == SlotState.IDLE })
        h.session.tick() // tidak boleh melempar
    }

    @Test fun tanpaPreload_transisiTetapBekerjaDenganJedaSingkat() {
        val h = Harness(two(), preload = false)
        h.vs.forEach { it.core.prepareMsFor = { 100L } }
        h.load()
        h.run(8000, checks = false)
        assertEquals(1, h.rec.ended)
        assertTrue(h.rec.errors.isEmpty())
        assertTrue(h.session.stats.maxTransitionWaitMs >= 100)
    }
}
