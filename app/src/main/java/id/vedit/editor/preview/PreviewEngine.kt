package id.vedit.editor.preview

import android.view.SurfaceView
import id.vedit.editor.domain.Project

interface EngineCallbacks {
    fun onReady()
    fun onEnded()
    fun onPlayingChanged(playing: Boolean)
    fun onError(message: String)
}

/** Mesin pemutar preview. Semua pemanggilan dan callback terjadi di main thread. */
interface PreviewEngine {
    val name: String
    val isPlaying: Boolean
    val positionUs: Long
    fun attach(surface: SurfaceView?)
    /** Memuat komposisi dari proyek (asinkron); `onReady` dipanggil saat siap, lalu posisi awal diterapkan. */
    fun load(project: Project, startPositionUs: Long)
    fun seekTo(us: Long)
    fun play()
    fun pause()
    fun release()
}
