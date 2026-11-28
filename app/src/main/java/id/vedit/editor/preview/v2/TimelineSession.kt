package id.vedit.editor.preview.v2

import id.vedit.editor.domain.MediaSource
import id.vedit.editor.domain.Project
import kotlin.math.abs
import kotlin.math.max

/** Statistik untuk diagnostik/perbandingan di perangkat (bukan klaim performa). */
data class SessionStats(
    val transitions: Int = 0,
    val lastTransitionWaitMs: Long = 0,
    val maxTransitionWaitMs: Long = 0,
    val stalls: Int = 0,
    val recoveries: Int = 0,
    val driftFixes: Int = 0,
    val timeToReadyMs: Long = -1,
)

/**
 * Pengendali timeline V2 (murni Kotlin, tanpa Android). Tugasnya: memetakan waktu timeline ke segmen rencana
 * (dari Rust), menyiapkan segmen berikutnya di slot kedua (ping-pong) SEBELUM batas, menukar slot di batas tanpa
 * membuat ulang pemain, menjaga jam timeline, menyinkronkan audio tambahan, dan memulihkan galat.
 *
 * Jam timeline: saat segmen VIDEO diputar, posisi pemain video adalah master (t = start + (src - srcIn)/speed);
 * saat gambar/celah/ekor, jam dinding yang berjalan. Jam ditahan selama pemain video BUFFERING atau menunggu
 * slot berikutnya siap. Semua metode dipanggil dari main thread; [tick] dipanggil berkala (~16-33 ms).
 */
class TimelineSession(
    private val clock: Clock,
    private val visual: Array<VisualSlot>,
    private val audio: Array<AudioSlot>,
    private val listener: SessionListener,
    private val preloadEnabledInitially: Boolean = true,
    private val log: (String) -> Unit = { PreviewLog.log("session", it) },
) {
    init {
        require(visual.size == 2 && audio.size == 2) { "Dua slot visual dan dua slot audio" }
    }

    var stats = SessionStats()
        private set
    var playing = false
        private set
    val isReleased: Boolean get() = released

    private var preloadEnabled = preloadEnabledInitially
    private var released = false
    private var project: Project? = null
    private var plan: PlaybackPlan? = null

    private var posUs = 0L
    private var anchorMs = 0L
    private var anchorUs = 0L
    private var running = false
    private var readyFired = false
    private var loadStartMs = 0L

    private var curIdx = -1
    private var curSlot: VisualSlot? = null
    private var preIdx = -1
    private var preSlot: VisualSlot? = null
    private var pendingIdx = -1
    private var pendingSince = 0L
    private var pendingRetries = 0
    private var pendingStartSrc = 0L
    private var pendingT = 0L
    private var errorBudget = MAX_ERRORS

    private var aCurIdx = -1
    private var aCurSlot: AudioSlot? = null
    private var aPreIdx = -1
    private var aPreSlot: AudioSlot? = null
    private var aSeekRequested = false
    private var lastDriftMs = Long.MIN_VALUE / 2

    val positionUs: Long
        get() = if (plan == null) 0L else computeT(clock.nowMs()).coerceIn(0L, plan!!.durationUs)

    // ------------------------------------------------------------------ API

    /** Memuat rencana baru (dipakai juga untuk rebuild setelah edit). Mempertahankan [autoPlay] sebagai niat putar. */
    fun load(project: Project, plan: PlaybackPlan, startUs: Long, autoPlay: Boolean) {
        if (released) return
        val now = clock.nowMs()
        log("load start=${startUs / 1000}ms video=${plan.video.size} audio=${plan.audio.size} dur=${plan.durationUs / 1000}ms")
        resetSlots()
        this.project = project
        this.plan = plan
        readyFired = false
        loadStartMs = now
        errorBudget = MAX_ERRORS
        preloadEnabled = preloadEnabledInitially
        setPlaying(autoPlay)
        enter(startUs.coerceIn(0L, plan.durationUs), now)
        tick()
    }

    fun play() {
        val p = plan ?: return
        if (released) return
        val now = clock.nowMs()
        if (p.durationUs > 0 && posUs >= p.durationUs) enter(0L, now)
        setPlaying(true)
        if (pendingIdx < 0) {
            startCurrent()
            setAnchor(now, posUs)
        }
        tick()
    }

    fun pause() {
        if (released || plan == null) return
        val now = clock.nowMs()
        posUs = computeT(now)
        setPlaying(false)
        running = false
        curSlot?.pause()
        aCurSlot?.pause()
        log("pause at ${posUs / 1000}ms")
    }

    fun seekTo(us: Long) {
        val p = plan ?: return
        if (released) return
        val t = us.coerceIn(0L, p.durationUs)
        log("seek -> ${t / 1000}ms")
        enter(t, clock.nowMs())
        aSeekRequested = true
        tick()
    }

    fun release() {
        if (released) return
        released = true
        resetSlots()
        plan = null
        project = null
        log("release")
    }

    // ------------------------------------------------------------------ penempatan awal / seek

    private fun enter(t: Long, now: Long) {
        val p = plan ?: return
        val segs = p.video
        val i = PlaybackPlan.indexAt(segs, t) ?: segs.size
        val seg = segs.getOrNull(i)
        posUs = t
        running = false
        if (seg == null || seg.kind == SegKind.GAP) {
            dropPending()
            leaveVisual()
            curIdx = i
            setAnchor(now, t)
            fireReady(now)
            preloadNext()
            return
        }
        val src = if (seg.kind == SegKind.VIDEO) seg.sourcePosUs(t) else 0L
        if (i == curIdx && curSlot != null && pendingIdx < 0) { // seek di dalam segmen yang sama
            if (seg.kind == SegKind.VIDEO && abs(curSlot!!.sourcePositionUs - src) > SEEK_TOLERANCE_US) curSlot!!.seekToSource(src)
            setAnchor(now, t)
            return
        }
        dropPendingIfOther(i)
        curSlot?.pause() // bingkai terakhir tetap tampil sampai slot baru siap
        val slot = if (preIdx == i && preSlot != null) {
            preSlot!!.also { if (seg.kind == SegKind.VIDEO) it.seekToSource(src) }
        } else {
            visual.first { it !== curSlot }.also { it.clear(); it.prepare(seg, sourceOf(seg), src) }
        }
        preSlot = slot
        preIdx = i
        startPending(i, t, src, now)
    }

    private fun startPending(i: Int, t: Long, src: Long, now: Long) {
        pendingIdx = i
        pendingSince = now
        pendingRetries = 0
        pendingStartSrc = src
        pendingT = t
        running = false
        log("pending seg#$i ${plan!!.video[i].kind}")
    }

    private fun dropPending() {
        pendingIdx = -1
    }

    private fun dropPendingIfOther(i: Int) {
        if (pendingIdx >= 0 && pendingIdx != i) pendingIdx = -1
        if (preIdx >= 0 && preIdx != i) {
            preSlot?.clear()
            preSlot = null
            preIdx = -1
        }
    }

    private fun leaveVisual() {
        curSlot?.let {
            it.setVisible(false)
            it.pause()
            it.clear()
        }
        curSlot = null
    }

    // ------------------------------------------------------------------ tick

    fun tick() {
        if (released) return
        val p = plan ?: return
        val now = clock.nowMs()

        if (pendingIdx >= 0) handlePending(now)
        if (pendingIdx < 0) {
            checkPreloadHealth()
            if (curSlot?.state == SlotState.ERROR) {
                recoverCurrent(now)
            } else {
                updateRunning(now)
                if (running) advance(p, now)
            }
        }
        syncAudio(p, now)
    }

    private fun updateRunning(now: Long) {
        val seg = plan!!.video.getOrNull(curIdx)
        val slot = curSlot
        val buffering = seg?.kind == SegKind.VIDEO && slot?.state == SlotState.BUFFERING
        val should = playing && !buffering
        if (should != running) {
            if (should) setAnchor(now, posUs) else posUs = computeT(now)
            if (buffering && playing) {
                stats = stats.copy(stalls = stats.stalls + 1)
                log("buffering at ${posUs / 1000}ms seg#$curIdx")
            }
            running = should
        }
        if (running && seg?.kind == SegKind.VIDEO && slot?.state == SlotState.READY) slot.play()
    }

    private fun advance(p: PlaybackPlan, now: Long) {
        var t = max(computeT(now), posUs)
        var guard = 0
        while (guard++ < 16) {
            val seg = p.video.getOrNull(curIdx)
            val slot = curSlot
            val done = if (seg == null) {
                t >= p.durationUs
            } else {
                t >= seg.endUs || (seg.kind == SegKind.VIDEO && slot != null &&
                    (slot.state == SlotState.ENDED || slot.sourcePositionUs >= seg.srcOutUs))
            }
            if (!done) break
            if (seg == null) {
                finish()
                return
            }
            val next = curIdx + 1
            val nseg = p.video.getOrNull(next)
            when {
                nseg == null -> { // ekor setelah segmen visual terakhir
                    if (t >= p.durationUs) {
                        finish()
                        return
                    }
                    leaveVisual()
                    curIdx = next
                    t = max(t, seg.endUs)
                    setAnchor(now, t)
                }
                nseg.kind == SegKind.GAP -> {
                    log("boundary seg#$curIdx -> gap")
                    leaveVisual()
                    curIdx = next
                    t = nseg.startUs
                    setAnchor(now, t)
                    preloadNext()
                }
                else -> {
                    val src = if (nseg.kind == SegKind.VIDEO) nseg.srcInUs else 0L
                    curSlot?.pause()
                    if (preIdx != next || preSlot == null) {
                        val free = visual.first { it !== curSlot }
                        free.clear()
                        free.prepare(nseg, sourceOf(nseg), src)
                        preSlot = free
                        preIdx = next
                    }
                    startPending(next, nseg.startUs, src, now)
                    posUs = nseg.startUs
                    handlePending(now)
                    if (pendingIdx >= 0) return // menunggu slot siap; jam ditahan
                    t = nseg.startUs
                }
            }
        }
        posUs = t
        setAnchor(now, t)
    }

    private fun handlePending(now: Long) {
        val slot = preSlot ?: run { dropPending(); return }
        when {
            slot.state == SlotState.READY || slot.state == SlotState.PLAYING -> commit(now)
            slot.state == SlotState.ERROR || now - pendingSince > PENDING_TIMEOUT_MS -> {
                val why = slot.error ?: "timeout"
                if (pendingRetries >= 1 || errorBudget <= 0) {
                    fail("Segmen #$pendingIdx gagal disiapkan: $why")
                } else {
                    pendingRetries++
                    errorBudget--
                    stats = stats.copy(recoveries = stats.recoveries + 1)
                    log("retry prepare seg#$pendingIdx ($why)")
                    val seg = plan!!.video[pendingIdx]
                    slot.clear()
                    slot.prepare(seg, sourceOf(seg), pendingStartSrc)
                    pendingSince = now
                }
            }
        }
    }

    private fun commit(now: Long) {
        val i = pendingIdx
        val seg = plan!!.video[i]
        val slot = preSlot!!
        val old = curSlot
        val wait = now - pendingSince
        slot.setVisible(true)
        if (old != null && old !== slot) {
            old.setVisible(false)
            old.pause()
            old.clear()
        }
        curSlot = slot
        curIdx = i
        preSlot = null
        preIdx = -1
        pendingIdx = -1
        posUs = pendingT
        if (playing && seg.kind == SegKind.VIDEO) slot.play()
        setAnchor(now, pendingT)
        running = playing
        stats = stats.copy(
            transitions = stats.transitions + 1,
            lastTransitionWaitMs = wait,
            maxTransitionWaitMs = max(stats.maxTransitionWaitMs, wait),
        )
        log("commit seg#$i ${seg.kind} wait=${wait}ms at ${pendingT / 1000}ms")
        fireReady(now)
        preloadNext()
    }

    private fun fireReady(now: Long) {
        if (readyFired) return
        readyFired = true
        stats = stats.copy(timeToReadyMs = now - loadStartMs)
        log("ready after ${now - loadStartMs}ms")
        listener.onReady()
    }

    private fun preloadNext() {
        val p = plan ?: return
        if (!preloadEnabled || pendingIdx >= 0) return
        val n = (curIdx + 1 until p.video.size).firstOrNull { p.video[it].kind != SegKind.GAP }
        if (n == null) {
            preSlot?.clear()
            preSlot = null
            preIdx = -1
            return
        }
        if (preIdx == n) return
        val seg = p.video[n]
        val free = visual.first { it !== curSlot }
        free.clear()
        free.prepare(seg, sourceOf(seg), if (seg.kind == SegKind.VIDEO) seg.srcInUs else 0L)
        preSlot = free
        preIdx = n
        log("preload seg#$n ${seg.kind}")
    }

    private fun checkPreloadHealth() {
        val s = preSlot ?: return
        if (pendingIdx < 0 && s.state == SlotState.ERROR) {
            log("preload gagal (${s.error}); preload dimatikan, lanjut berurutan")
            preloadEnabled = false
            s.clear()
            preSlot = null
            preIdx = -1
        }
    }

    private fun recoverCurrent(now: Long) {
        val slot = curSlot ?: return
        if (errorBudget <= 0) {
            fail("Pemutar video galat: ${slot.error}")
            return
        }
        errorBudget--
        stats = stats.copy(recoveries = stats.recoveries + 1)
        val t = computeT(now)
        log("recover cur seg#$curIdx at ${t / 1000}ms (${slot.error})")
        curIdx = -1
        leaveVisual()
        enter(t, now)
    }

    private fun finish() {
        log("ended")
        curSlot?.pause() // bingkai terakhir tetap tampil
        aCurSlot?.let { it.pause(); it.clear() }
        aCurSlot = null
        aCurIdx = -1
        posUs = plan?.durationUs ?: 0L
        running = false
        curIdx = plan?.video?.size ?: 0
        setPlaying(false)
        listener.onEnded()
    }

    private fun fail(msg: String) {
        log("FAIL $msg")
        running = false
        setPlaying(false)
        curSlot?.pause()
        aCurSlot?.pause()
        listener.onError(msg)
    }

    // ------------------------------------------------------------------ audio tambahan

    private fun syncAudio(p: PlaybackPlan, now: Long) {
        val a = p.audio
        if (a.isEmpty()) return
        val t = posUs
        val di = PlaybackPlan.indexAt(a, t)?.takeIf { a[it].kind == SegKind.AUDIO } ?: -1
        if (di != aCurIdx) {
            aCurSlot?.let { it.pause(); if (it !== aPreSlot) it.clear() }
            aCurSlot = null
            if (di >= 0 && aPreIdx == di && aPreSlot != null) {
                aCurSlot = aPreSlot
                aPreSlot = null
                aPreIdx = -1
                aSeekRequested = true
            } else if (di >= 0) {
                val s = audio.first { it !== aPreSlot }
                val seg = a[di]
                s.clear()
                s.prepare(seg, sourceOf(seg), seg.sourcePosUs(t))
                aCurSlot = s
            }
            aCurSlot?.setVolume(a.getOrNull(di)?.volume ?: 1f)
            aCurIdx = di
            log("audio seg#$di")
        }
        val s = aCurSlot
        val seg = a.getOrNull(aCurIdx)
        if (s != null && seg != null) {
            val expected = seg.sourcePosUs(t)
            if (!running) {
                if (s.state == SlotState.PLAYING) s.pause()
            } else {
                if (aSeekRequested && s.state != SlotState.ERROR) {
                    s.seekToSource(expected)
                    aSeekRequested = false
                }
                when (s.state) {
                    SlotState.READY -> {
                        if (abs(s.sourcePositionUs - expected) > DRIFT_US) s.seekToSource(expected)
                        s.play()
                    }
                    SlotState.PLAYING -> if (abs(s.sourcePositionUs - expected) > DRIFT_US && now - lastDriftMs > DRIFT_COOLDOWN_MS) {
                        log("drift audio ${(s.sourcePositionUs - expected) / 1000}ms -> reseek")
                        s.seekToSource(expected)
                        lastDriftMs = now
                        stats = stats.copy(driftFixes = stats.driftFixes + 1)
                    }
                    else -> Unit
                }
            }
        }
        // praload audio berikutnya
        val from = if (aCurIdx >= 0) aCurIdx + 1 else (PlaybackPlan.indexAt(a, t) ?: a.size)
        val nxt = (from until a.size).firstOrNull { a[it].kind == SegKind.AUDIO } ?: -1
        if (nxt != aPreIdx) {
            aPreSlot?.clear()
            aPreSlot = null
            aPreIdx = -1
            if (nxt >= 0) {
                val free = audio.first { it !== aCurSlot }
                val seg2 = a[nxt]
                free.clear()
                free.prepare(seg2, sourceOf(seg2), seg2.srcInUs)
                free.setVolume(seg2.volume)
                aPreSlot = free
                aPreIdx = nxt
            }
        }
    }

    // ------------------------------------------------------------------ util

    private fun startCurrent() {
        val seg = plan?.video?.getOrNull(curIdx) ?: return
        if (seg.kind == SegKind.VIDEO) curSlot?.play()
    }

    private fun setPlaying(v: Boolean) {
        if (playing != v) {
            playing = v
            listener.onPlayingChanged(v)
        }
    }

    private fun setAnchor(now: Long, t: Long) {
        anchorMs = now
        anchorUs = t
    }

    private fun computeT(now: Long): Long {
        val p = plan ?: return 0L
        if (!running) return posUs
        val seg = p.video.getOrNull(curIdx)
        val slot = curSlot
        return if (seg != null && seg.kind == SegKind.VIDEO && slot?.state == SlotState.PLAYING) {
            seg.timelinePosUs(slot.sourcePositionUs).coerceIn(seg.startUs, seg.endUs)
        } else {
            anchorUs + (now - anchorMs) * 1000
        }
    }

    private fun sourceOf(seg: Segment): MediaSource =
        requireNotNull(project?.source(seg.sourceId)) { "Sumber ${seg.sourceId} tidak ada" }

    private fun resetSlots() {
        visual.forEach { it.setVisible(false); it.pause(); it.clear() }
        audio.forEach { it.pause(); it.clear() }
        curIdx = -1; curSlot = null; preIdx = -1; preSlot = null; pendingIdx = -1
        aCurIdx = -1; aCurSlot = null; aPreIdx = -1; aPreSlot = null
        running = false
    }

    companion object {
        const val PENDING_TIMEOUT_MS = 4_000L
        const val DRIFT_US = 120_000L
        const val DRIFT_COOLDOWN_MS = 1_000L
        const val MAX_ERRORS = 3
        const val SEEK_TOLERANCE_US = 40_000L
    }
}
