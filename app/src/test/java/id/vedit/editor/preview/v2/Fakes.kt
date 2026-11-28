package id.vedit.editor.preview.v2

import id.vedit.editor.domain.Clip
import id.vedit.editor.domain.MediaSource
import id.vedit.editor.domain.MediaType
import id.vedit.editor.domain.Project
import id.vedit.editor.domain.Track
import id.vedit.editor.domain.TrackType

const val S = 1_000_000L

class VClock : Clock {
    var ms = 0L
    override fun nowMs() = ms
}

/** Inti perilaku pemain palsu: state diturunkan dari jam virtual. */
class FakeCore(private val id: String, private val clock: VClock, private val events: MutableList<String>) {
    var prepareMsFor: (Segment) -> Long = { 0L }
    var failures = 0
    var skewUs = 0L
    var lastSeg: Segment? = null
    var volume = 1f
    var visible = false
    var prepareCount = 0

    private var base = SlotState.IDLE
    private var readyAt = 0L
    private var err: String? = null
    private var playWhen = false
    private var basePos = 0L
    private var baseMs = 0L
    private var bufferUntil = 0L

    val state: SlotState
        get() {
            if (base == SlotState.PREPARING && clock.ms >= readyAt) {
                if (failures > 0) {
                    failures--
                    err = "decoder"
                    base = SlotState.ERROR
                } else {
                    base = SlotState.READY
                }
            }
            if (base != SlotState.READY) return base
            if (clock.ms < bufferUntil) return SlotState.BUFFERING
            return if (playWhen) SlotState.PLAYING else SlotState.READY
        }
    val error: String? get() = err
    val sourcePositionUs: Long
        get() {
            state
            val speed = (lastSeg?.speed ?: 1f).toDouble()
            return if (base == SlotState.READY && playWhen && clock.ms >= bufferUntil) {
                basePos + skewUs + Math.round((clock.ms - maxOf(baseMs, bufferUntil)) * 1000 * speed)
            } else {
                basePos + skewUs
            }
        }

    private fun e(s: String) { events += "${clock.ms}:$id:$s" }

    fun prepare(seg: Segment, start: Long) {
        prepareCount++
        lastSeg = seg
        volume = seg.volume
        base = SlotState.PREPARING
        err = null
        readyAt = clock.ms + prepareMsFor(seg)
        basePos = start
        baseMs = clock.ms
        playWhen = false
        bufferUntil = 0
        skewUs = 0
        e("prepare:${seg.kind}:$start")
    }

    fun play() { if (!playWhen) { basePos = sourcePositionUs - skewUs; baseMs = clock.ms; playWhen = true; e("play") } }
    fun pause() { if (playWhen) { basePos = sourcePositionUs - skewUs; playWhen = false; e("pause") } }
    fun seek(us: Long) { state; basePos = us; baseMs = clock.ms; skewUs = 0; bufferUntil = clock.ms + 20; e("seek:$us") }
    fun clear() { base = SlotState.IDLE; playWhen = false; lastSeg = null; e("clear") }
    fun bufferFor(ms: Long) { basePos = sourcePositionUs - skewUs; baseMs = clock.ms; bufferUntil = clock.ms + ms; e("stall:$ms") }
}

class FakeVisual(id: String, clock: VClock, val events: MutableList<String>) : VisualSlot {
    val core = FakeCore(id, clock, events)
    private val id0 = id
    private val clock0 = clock
    override val state get() = core.state
    override val error get() = core.error
    override val sourcePositionUs get() = core.sourcePositionUs
    override fun prepare(seg: Segment, source: MediaSource, startSourceUs: Long) = core.prepare(seg, startSourceUs)
    override fun play() = core.play()
    override fun pause() = core.pause()
    override fun seekToSource(us: Long) = core.seek(us)
    override fun setVisible(visible: Boolean) {
        if (core.visible != visible) events += "${clock0.ms}:$id0:visible=$visible"
        core.visible = visible
    }
    override fun clear() = core.clear()
    override fun release() = core.clear()
}

class FakeAudio(id: String, clock: VClock, val events: MutableList<String>) : AudioSlot {
    val core = FakeCore(id, clock, events)
    override val state get() = core.state
    override val error get() = core.error
    override val sourcePositionUs get() = core.sourcePositionUs
    override fun prepare(seg: Segment, source: MediaSource, startSourceUs: Long) = core.prepare(seg, startSourceUs)
    override fun play() = core.play()
    override fun pause() = core.pause()
    override fun seekToSource(us: Long) = core.seek(us)
    override fun setVolume(volume: Float) { core.volume = volume }
    override fun clear() = core.clear()
    override fun release() = core.clear()
}

class Recorder : SessionListener {
    var ready = 0
    var ended = 0
    val errors = ArrayList<String>()
    val playing = ArrayList<Boolean>()
    override fun onReady() { ready++ }
    override fun onPlayingChanged(playing: Boolean) { this.playing += playing }
    override fun onEnded() { ended++ }
    override fun onError(message: String) { errors += message }
}

// ---- pembangun proyek uji
fun vsrc(id: String, durS: Long = 20) = MediaSource(id, "content://t/$id", MediaType.VIDEO, "$id.mp4", durS * S, 1280, 720, true)
fun isrc(id: String) = MediaSource(id, "content://t/$id", MediaType.IMAGE, "$id.jpg", 5 * S, 1000, 1000, false)
fun asrc(id: String, durS: Long = 60) = MediaSource(id, "content://t/$id", MediaType.AUDIO, "$id.m4a", durS * S, 0, 0, true)

fun clip(id: String, src: String, startS: Double, inS: Double, outS: Double, speed: Float = 1f, vol: Float = 1f) =
    Clip(id, src, (startS * S).toLong(), (inS * S).toLong(), (outS * S).toLong(), speed, vol)

fun project(sources: List<MediaSource>, video: List<Clip>, audio: List<Clip> = emptyList()) = Project(
    id = "p", name = "t", sources = sources,
    tracks = listOf(Track("v1", TrackType.VIDEO, video), Track("a1", TrackType.AUDIO, audio)),
    createdAtMs = 0, modifiedAtMs = 0,
)

/** Rangkaian uji: sesi + dua slot visual + dua slot audio + jam virtual. */
class Harness(val project: Project, preload: Boolean = true, provider: PlanProvider = KotlinPlanProvider) {
    val clock = VClock()
    val events = ArrayList<String>()
    val vs = arrayOf(FakeVisual("V0", clock, events), FakeVisual("V1", clock, events))
    val au = arrayOf(FakeAudio("A0", clock, events), FakeAudio("A1", clock, events))
    val rec = Recorder()
    val plan = provider.plan(project)
    val session = TimelineSession(clock, arrayOf<VisualSlot>(vs[0], vs[1]), arrayOf<AudioSlot>(au[0], au[1]), rec, preload) { events += "${clock.ms}:session:$it" }
    val violations = ArrayList<String>()
    private var lastPos = 0L

    fun load(startS: Double = 0.0, play: Boolean = true) = session.load(project, plan, (startS * S).toLong(), play)

    /** Jalankan [ms] dengan langkah [step]; cek invarian setiap langkah. */
    fun run(ms: Long, step: Long = 16, checks: Boolean = true) {
        var left = ms
        while (left > 0) {
            val d = minOf(step, left)
            clock.ms += d
            left -= d
            session.tick()
            if (checks) check()
        }
    }

    /** Invarian: posisi tidak mundur saat berjalan; di segmen VIDEO yang berjalan, ada slot terlihat yang PLAYING/BUFFERING. */
    private fun check() {
        val pos = session.positionUs
        if (session.playing && pos + 1000 < lastPos) violations += "posisi mundur $lastPos -> $pos @${clock.ms}"
        lastPos = pos
        if (session.playing) {
            val i = PlaybackPlan.indexAt(plan.video, pos)
            val seg = i?.let { plan.video[it] }
            if (seg?.kind == SegKind.VIDEO && pos > seg.startUs + 100_000 && pos < seg.endUs - 100_000) {
                val ok = vs.any { it.core.visible && (it.state == SlotState.PLAYING || it.state == SlotState.BUFFERING) }
                if (!ok) violations += "video berhenti (tidak ada slot terlihat yang diputar) @${clock.ms} pos=${pos / 1000}ms"
            }
            if (seg?.kind == SegKind.GAP && vs.any { it.core.visible }) violations += "slot terlihat saat celah @${clock.ms}"
        }
    }

    fun forget() { lastPos = 0 }
    fun visibleSlot(): FakeVisual? = vs.firstOrNull { it.core.visible }
    fun idx(prefix: String) = events.indexOfFirst { it.contains(prefix) }
}
