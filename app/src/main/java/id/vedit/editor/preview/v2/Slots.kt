package id.vedit.editor.preview.v2

import id.vedit.editor.domain.MediaSource

enum class SlotState { IDLE, PREPARING, READY, PLAYING, BUFFERING, ENDED, ERROR }

/** Sumber waktu monotonik (ms). Diinjeksi agar penjadwalan bisa diuji deterministik. */
fun interface Clock {
    fun nowMs(): Long
}

/** Satu "layar" visual: memutar satu klip video atau menampilkan satu gambar. Hanya dipanggil dari main thread. */
interface VisualSlot {
    val state: SlotState
    val error: String?

    /** Posisi dalam waktu SUMBER (mikrodetik). Tidak bermakna untuk gambar. */
    val sourcePositionUs: Long

    /** Memuat segmen dalam keadaan jeda dan tersembunyi; menjadi READY saat frame pertama siap. */
    fun prepare(seg: Segment, source: MediaSource, startSourceUs: Long)
    fun play()
    fun pause()
    fun seekToSource(us: Long)
    fun setVisible(visible: Boolean)

    /** Melepas media tetapi mempertahankan slot (IDLE). */
    fun clear()
    fun release()
}

/** Pemutar audio tambahan (track audio). */
interface AudioSlot {
    val state: SlotState
    val error: String?
    val sourcePositionUs: Long
    fun prepare(seg: Segment, source: MediaSource, startSourceUs: Long)
    fun play()
    fun pause()
    fun seekToSource(us: Long)
    fun setVolume(volume: Float)
    fun clear()
    fun release()
}

interface SessionListener {
    /** Pertama kali tampilan awal siap (atau proyek tanpa visual). */
    fun onReady()
    fun onPlayingChanged(playing: Boolean)
    fun onEnded()
    fun onError(message: String)
}
