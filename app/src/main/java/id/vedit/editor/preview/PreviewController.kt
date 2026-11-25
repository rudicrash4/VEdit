package id.vedit.editor.preview

import android.content.Context
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.vedit.editor.domain.PreviewPlan
import id.vedit.editor.domain.Project

data class PreviewUiState(
    val hasContent: Boolean = false,
    val loading: Boolean = false,
    val ready: Boolean = false,
    val isPlaying: Boolean = false,
    val engineName: String = "",
    val notice: String = "",
    val error: String = "",
    /** Waktu (ms) dari memulai pembangunan hingga siap, terukur di perangkat; -1 = belum ada. */
    val lastBuildMs: Long = -1,
    val buildCount: Int = 0,
)

/**
 * Pemilik pemutar preview: membangun mesin dari proyek, menjaga posisi, dan beralih ke ExoPlayer jika
 * CompositionPlayer gagal. Semua dipanggil dari main thread. `release()` wajib saat layar ditutup.
 */
class PreviewController(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /** (callbacks, pakaiCadangan) -> mesin. Diinjeksi agar controller bisa diuji tanpa perangkat. */
    private val factory: (EngineCallbacks, Boolean) -> PreviewEngine,
) : EngineCallbacks {
    var ui by mutableStateOf(PreviewUiState())
        private set

    private var engine: PreviewEngine? = null
    private var surface: SurfaceView? = null
    private var project: Project? = null
    private var usingFallback = false
    private var wantedPositionUs = 0L
    private var released = false
    private var buildStartMs = 0L

    val positionUs: Long get() = engine?.positionUs ?: wantedPositionUs

    fun attachSurface(view: SurfaceView) {
        surface = view
        engine?.attach(view)
    }

    /** Dipanggil saat struktur proyek berubah. Playback dihentikan; posisi dipertahankan. */
    fun update(project: Project, positionUs: Long) {
        if (released) return
        val unchanged = engine != null && ui.error.isEmpty() && !PreviewPlan.structureChanged(this.project, project)
        this.project = project
        if (unchanged) {
            // Struktur sama: tidak perlu membangun ulang pemutar (mahal); cukup ikuti posisi.
            seekTo(positionUs)
            return
        }
        wantedPositionUs = PreviewPlan.clampPlayhead(positionUs, project.durationUs)
        if (!PreviewPlan.hasPlayableContent(project)) {
            engine?.release()
            engine = null
            ui = PreviewUiState(hasContent = false)
            return
        }
        start()
    }

    private fun start() {
        val p = project ?: return
        engine?.release()
        val e = factory(this, usingFallback)
        engine = e
        e.attach(surface)
        buildStartMs = clockMs()
        ui = ui.copy(hasContent = true, loading = true, ready = false, isPlaying = false, engineName = e.name, error = "", buildCount = ui.buildCount + 1)
        e.load(p, wantedPositionUs)
    }

    fun seekTo(us: Long) {
        val p = project ?: return
        wantedPositionUs = PreviewPlan.clampPlayhead(us, p.durationUs)
        if (ui.ready) engine?.seekTo(wantedPositionUs)
    }

    fun play() {
        val e = engine ?: return
        if (!ui.ready) return
        val dur = project?.durationUs ?: 0L
        if (e.positionUs >= dur - 50_000L) e.seekTo(0L)
        e.play()
    }

    fun pause() {
        engine?.pause()
    }

    fun release() {
        released = true
        engine?.release()
        engine = null
        surface = null
    }

    // --- EngineCallbacks (main thread)
    override fun onReady() {
        ui = ui.copy(loading = false, ready = true, error = "", lastBuildMs = clockMs() - buildStartMs)
        engine?.seekTo(wantedPositionUs)
    }

    override fun onEnded() {
        ui = ui.copy(isPlaying = false)
    }

    override fun onPlayingChanged(playing: Boolean) {
        ui = ui.copy(isPlaying = playing)
    }

    override fun onError(message: String) {
        if (released) return
        if (!usingFallback && project?.tracks?.any { it.clips.isNotEmpty() } == true) {
            usingFallback = true
            ui = ui.copy(notice = "CompositionPlayer gagal ($message). Memakai ExoPlayer: hanya track video berurutan, tanpa celah/audio tambahan/efek.")
            start()
        } else {
            ui = ui.copy(loading = false, ready = false, isPlaying = false, error = message)
        }
    }
}

fun defaultPreviewController(context: Context): PreviewController =
    PreviewController { cb, fallback -> if (fallback) ExoEngine(context, cb) else CompositionEngine(context, cb) }
