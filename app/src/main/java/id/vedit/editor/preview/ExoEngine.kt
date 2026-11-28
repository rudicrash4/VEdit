package id.vedit.editor.preview

import android.content.Context
import android.net.Uri
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import id.vedit.editor.domain.Clip
import id.vedit.editor.domain.MediaType
import id.vedit.editor.domain.PreviewPlan
import id.vedit.editor.domain.Project
import id.vedit.editor.domain.TrackType

/**
 * Preview cadangan bila CompositionPlayer gagal: ExoPlayer memutar klip track video berurutan.
 * Batasan (ditampilkan ke pengguna): tanpa celah, tanpa audio tambahan, tanpa efek/kecepatan/volume per klip.
 */
@OptIn(UnstableApi::class)
class ExoEngine(private val context: Context, private val cb: EngineCallbacks) : PreviewEngine {
    override val name = "ExoPlayer (cadangan)"
    private var player: ExoPlayer? = null
    private val surfaceHost = SurfaceHost()
    private var clips: List<Clip> = emptyList()
    private var startUs = 0L
    private var ready = false

    override val isPlaying: Boolean get() = player?.isPlaying == true

    override val positionUs: Long
        get() {
            val p = player ?: return 0L
            if (clips.isEmpty()) return 0L
            return PreviewPlan.toTimelineUs(clips, p.currentMediaItemIndex, p.currentPosition * 1000L)
        }

    override fun attach(host: FrameLayout?) {
        player?.setVideoSurfaceView(null)
        val sv = surfaceHost.attach(host)
        player?.setVideoSurfaceView(sv)
    }

    override fun load(project: Project, startPositionUs: Long) {
        releasePlayer()
        startUs = startPositionUs
        clips = project.tracks.firstOrNull { it.type == TrackType.VIDEO }?.clips?.sortedBy { it.startUs } ?: emptyList()
        val items = clips.mapNotNull { clip ->
            val src = project.source(clip.sourceId) ?: return@mapNotNull null
            val uri = Uri.parse(src.uri)
            if (src.type == MediaType.IMAGE) {
                MediaItem.Builder().setUri(uri).setImageDurationMs(maxOf(1L, clip.durationUs / 1000)).build()
            } else {
                MediaItem.Builder().setUri(uri).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.sourceInUs / 1000).setEndPositionMs(clip.sourceOutUs / 1000).build(),
                ).build()
            }
        }
        if (items.size != clips.size || items.isEmpty()) {
            cb.onError("Sumber media hilang atau tidak ada klip video")
            return
        }
        try {
            val p = ExoPlayer.Builder(context).build()
            player = p
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> if (!ready) {
                            ready = true
                            seekTo(startUs)
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
            p.setVideoSurfaceView(surfaceHost.view)
            p.setMediaItems(items)
            p.prepare()
        } catch (t: Throwable) {
            releasePlayer()
            cb.onError("Gagal menyiapkan ExoPlayer: ${t::class.java.simpleName}: ${t.message}")
        }
    }

    override fun seekTo(us: Long) {
        val p = player ?: return
        val loc = PreviewPlan.locate(clips, us) ?: return
        p.seekTo(loc.index, loc.offsetUs / 1000)
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun release() {
        releasePlayer()
        surfaceHost.detach()
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { it.setVideoSurfaceView(null) }
            runCatching { it.release() }
        }
        player = null
        ready = false
    }
}
