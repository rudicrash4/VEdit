package id.vedit.editor.preview

import android.widget.FrameLayout
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
    /** Teks diagnostik singkat (kosong bila tidak ada). */
    val stats: String get() = ""

    /** Memasang/melepas wadah tampilan. Mesin membuat view-nya sendiri di dalam [host]. */
    fun attach(host: FrameLayout?)
    /** Memuat komposisi dari proyek (asinkron); `onReady` dipanggil saat siap, lalu posisi awal diterapkan. */
    fun load(project: Project, startPositionUs: Long)
    fun seekTo(us: Long)
    fun play()
    fun pause()
    fun release()
}
