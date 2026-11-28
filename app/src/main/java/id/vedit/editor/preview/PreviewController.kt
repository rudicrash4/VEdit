package id.vedit.editor.preview

import android.content.Context
import android.content.SharedPreferences
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.vedit.editor.domain.PreviewPlan
import id.vedit.editor.domain.Project

/** Mesin preview. Urutan cadangan: V2 -> COMPOSITION -> EXO. */
enum class EngineKind(val label: String) { V2("V2"), COMPOSITION("Composition"), EXO("Exo") }

data class PreviewUiState(
    val hasContent: Boolean = false,
    val loading: Boolean = false,
    val ready: Boolean = false,
    val isPlaying: Boolean = false,
    val engineName: String = "",
    val preferred: EngineKind = EngineKind.COMPOSITION,
    val stats: String = "",
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
    private val preferred0: EngineKind = EngineKind.COMPOSITION,
    private val onPreferredChanged: (EngineKind) -> Unit = {},
    private val factory: (EngineCallbacks, EngineKind) -> PreviewEngine,
) : EngineCallbacks {
    var ui by mutableStateOf(PreviewUiState(preferred = preferred0))
        private set

    private var preferred = preferred0
    private var current = preferred0

    private var engine: PreviewEngine? = null
    private var host: FrameLayout? = null
    private var project: Project? = null
    private var wantedPositionUs = 0L
    private var released = false
    private var buildStartMs = 0L

    val positionUs: Long get() = engine?.positionUs ?: wantedPositionUs

    fun attachHost(view: FrameLayout) {
        host = view
        engine?.attach(view)
    }

    fun detachHost(view: FrameLayout) {
        if (host === view) {
            engine?.attach(null)
            host = null
        }
    }

    /** Pilihan pengguna (disimpan lewat [onPreferredChanged]); membangun ulang preview dengan mesin itu. */
    fun setPreferred(kind: EngineKind) {
        if (released) return
        preferred = kind
        current = kind
        onPreferredChanged(kind)
        ui = ui.copy(preferred = kind, notice = "")
        if (project != null && PreviewPlan.hasPlayableContent(project!!)) start()
    }

    fun cycleEngine() = setPreferred(EngineKind.values()[(preferred.ordinal + 1) % EngineKind.values().size])

    /** Cuplikan statistik mesin aktif untuk panel diagnostik. */
    fun refreshStats() {
        val st = engine?.stats ?: ""
        if (st != ui.stats) ui = ui.copy(stats = st)
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
        val e = factory(this, current)
        engine = e
        e.attach(host)
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
        host = null
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
        val next = EngineKind.values().firstOrNull { it.ordinal > current.ordinal }
        if (next != null && project?.tracks?.any { it.clips.isNotEmpty() } == true) {
            val note = if (next == EngineKind.EXO) " Hanya track video berurutan, tanpa celah/audio tambahan/efek." else ""
            ui = ui.copy(notice = "${current.label} gagal ($message). Beralih ke ${next.label}.$note")
            current = next
            start()
        } else {
            ui = ui.copy(loading = false, ready = false, isPlaying = false, error = message)
        }
    }
}

private const val PREFS = "vedit_prefs"
private const val KEY_ENGINE = "preview_engine"

/** Mesin awal = V2 (pilihan terakhir pengguna diingat). V2 BELUM terbukti lebih baik di perangkat; lihat docs/PREVIEW_V2_*.md. */
fun defaultPreviewController(context: Context): PreviewController {
    val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val saved = runCatching { EngineKind.valueOf(prefs.getString(KEY_ENGINE, null) ?: "") }.getOrDefault(EngineKind.V2)
    return PreviewController(
        preferred0 = saved,
        onPreferredChanged = { prefs.edit().putString(KEY_ENGINE, it.name).apply() },
    ) { cb, kind ->
        when (kind) {
            EngineKind.V2 -> id.vedit.editor.preview.v2.V2Engine(context, cb)
            EngineKind.COMPOSITION -> CompositionEngine(context, cb)
            EngineKind.EXO -> ExoEngine(context, cb)
        }
    }
}
