package id.vedit.editor.preview

import id.vedit.editor.domain.OutputQuality
import android.content.Context
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import id.vedit.editor.domain.Project
import id.vedit.editor.poc.Analysis

/** Preview utama: CompositionPlayer memutar komposisi yang sama dengan definisi export (track video + audio, celah, gambar). */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class CompositionEngine(private val context: Context, private val cb: EngineCallbacks) : PreviewEngine {
    override val name = "CompositionPlayer"
    private var player: CompositionPlayer? = null
    private var surface: SurfaceView? = null
    private var startUs = 0L
    private var ready = false

    override val isPlaying: Boolean get() = player?.isPlaying == true
    override val positionUs: Long get() = (player?.currentPosition ?: 0L) * 1000L

    override fun attach(surface: SurfaceView?) {
        this.surface = surface
        player?.setVideoSurfaceView(surface)
    }

    override fun load(project: Project, startPositionUs: Long) {
        releasePlayer()
        startUs = startPositionUs
        val composition = try {
            CompositionFactory.build(project, OutputQuality.PREVIEW)
        } catch (t: Throwable) {
            cb.onError("Gagal menyusun komposisi: ${Analysis.describeThrowable(t)}")
            return
        }
        if (composition == null) {
            cb.onError("Tidak ada media yang bisa diputar")
            return
        }
        try {
            val p = CompositionPlayer.Builder(context).build()
            player = p
            ready = false
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> if (!ready) {
                            ready = true
                            p.seekTo(startUs / 1000)
                            cb.onReady()
                        }
                        Player.STATE_ENDED -> cb.onEnded()
                        else -> Unit
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) = cb.onPlayingChanged(isPlaying)

                override fun onPlayerError(error: PlaybackException) {
                    cb.onError("${error.errorCodeName}: ${error.message}")
                }
            })
            p.setVideoSurfaceView(surface)
            p.setComposition(composition)
            p.prepare()
        } catch (t: Throwable) {
            releasePlayer()
            cb.onError("Gagal menyiapkan player: ${Analysis.describeThrowable(t)}")
        }
    }

    override fun seekTo(us: Long) {
        player?.seekTo(us / 1000)
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { it.setVideoSurfaceView(null) }
            runCatching { it.release() }
        }
        player = null
        ready = false
    }

    override fun release() = releasePlayer()
}
