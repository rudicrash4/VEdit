package id.vedit.editor.preview.v2

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import id.vedit.editor.domain.Project
import id.vedit.editor.poc.Analysis
import id.vedit.editor.preview.EngineCallbacks
import id.vedit.editor.preview.PreviewEngine

/**
 * Preview Engine V2: jadwal timeline dari core Rust (atau Kotlin bila native tidak ada), dekode oleh ExoPlayer,
 * orkestrasi [TimelineSession]. Dua slot visual ping-pong + dua slot audio; slot dipertahankan antar load.
 */
class V2Engine(
    private val context: Context,
    private val cb: EngineCallbacks,
    private val provider: PlanProvider = PlanProviders.default(),
) : PreviewEngine {
    override val name = "V2 (${provider.name}+ExoPlayer)"

    private val main = Handler(Looper.getMainLooper())
    private val cfg = SlotConfig(provider)
    private var host: FrameLayout? = null
    private var visual: Array<VisualSlot>? = null
    private var audio: Array<AudioSlot>? = null
    private var session: TimelineSession? = null
    private var project: Project? = null
    private var released = false

    private val listener = object : SessionListener {
        override fun onReady() = cb.onReady()
        override fun onPlayingChanged(playing: Boolean) = cb.onPlayingChanged(playing)
        override fun onEnded() = cb.onEnded()
        override fun onError(message: String) = cb.onError(message)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            session?.tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    override val isPlaying: Boolean get() = session?.playing == true
    override val positionUs: Long get() = session?.positionUs ?: 0L
    override val stats: String
        get() {
            val s = session?.stats ?: return ""
            return "trans=${s.transitions} lastWait=${s.lastTransitionWaitMs}ms maxWait=${s.maxTransitionWaitMs}ms " +
                "ready=${s.timeToReadyMs}ms stall=${s.stalls} recover=${s.recoveries} drift=${s.driftFixes} " +
                "drop=${PreviewLog.dropped} codecErr=${PreviewLog.codecErrors} underrun=${PreviewLog.audioUnderruns}"
        }

    override fun attach(host: FrameLayout?) {
        if (host === this.host) return
        val resumeAt = session?.positionUs ?: 0L
        val p = project
        teardown()
        this.host = host
        PreviewLog.log("v2", "attach host=${host != null}")
        if (host != null && p != null && !released) load(p, resumeAt)
    }

    override fun load(project: Project, startPositionUs: Long) {
        if (released) return
        this.project = project
        val h = host
        if (h == null) {
            PreviewLog.log("v2", "load ditunda: host belum terpasang")
            return
        }
        try {
            val plan = try {
                provider.plan(project)
            } catch (t: Throwable) {
                PreviewLog.log("v2", "plan ${provider.name} gagal (${t.javaClass.simpleName}); pakai Kotlin")
                KotlinPlanProvider.plan(project)
            }
            cfg.fit = project.canvas.fit
            val s = session ?: createSession(h)
            s.load(project, plan, startPositionUs, autoPlay = false)
        } catch (t: Throwable) {
            cb.onError("Gagal menyiapkan V2: ${Analysis.describeThrowable(t)}")
        }
    }

    private fun createSession(h: FrameLayout): TimelineSession {
        val v = arrayOf<VisualSlot>(ExoVisualSlot(context, h, "0", cfg), ExoVisualSlot(context, h, "1", cfg))
        val a = arrayOf<AudioSlot>(ExoAudioSlot(context, "0"), ExoAudioSlot(context, "1"))
        visual = v
        audio = a
        return TimelineSession(Clock { android.os.SystemClock.elapsedRealtime() }, v, a, listener).also {
            session = it
            main.removeCallbacks(ticker)
            main.post(ticker)
        }
    }

    override fun seekTo(us: Long) {
        session?.seekTo(us)
    }

    override fun play() {
        session?.play()
    }

    override fun pause() {
        session?.pause()
    }

    private fun teardown() {
        main.removeCallbacks(ticker)
        session?.release()
        session = null
        visual?.forEach { runCatching { it.release() } }
        audio?.forEach { runCatching { it.release() } }
        visual = null
        audio = null
    }

    override fun release() {
        released = true
        teardown()
        host = null
    }

    companion object {
        const val TICK_MS = 16L
    }
}
