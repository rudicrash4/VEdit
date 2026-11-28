package id.vedit.editor.preview.v2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import id.vedit.editor.domain.FitMode
import id.vedit.editor.domain.MediaSource
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Konfigurasi bersama slot: penyedia geometri (Rust/Kotlin) dan mode Fit/Fill proyek. */
class SlotConfig(var provider: PlanProvider, var fit: FitMode = FitMode.FIT)

/**
 * Satu slot visual Android: ExoPlayer + TextureView (video) dan ImageView (gambar) dalam satu FrameLayout.
 * Slot tersembunyi memakai alpha kecil (bukan INVISIBLE) agar surface TextureView tetap hidup dan frame pertama
 * bisa dirender sebelum slot ditampilkan. (Perilaku ini PERLU diverifikasi di perangkat.)
 */
@OptIn(UnstableApi::class)
class ExoVisualSlot(
    private val context: Context,
    host: FrameLayout,
    private val id: String,
    private val cfg: SlotConfig,
) : VisualSlot {
    private enum class Mode { NONE, VIDEO, IMAGE }

    private val root = FrameLayout(context)
    private val texture = TextureView(context)
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.MATRIX; visibility = View.GONE }
    private var player: ExoPlayer? = null
    private var mode = Mode.NONE
    private var seg: Segment? = null
    private var visible = false
    private var err: String? = null
    private var everReady = false
    private var firstFrame = false
    private var readySinceMs = -1L
    private var srcW = 0
    private var srcH = 0
    private var bitmap: Bitmap? = null
    private var imgState = SlotState.IDLE
    private var gen = 0

    init {
        val lp = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        root.addView(texture, lp())
        root.addView(image, lp())
        host.addView(root, lp())
        texture.alpha = HIDDEN_ALPHA
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (r - l != or2 - ol || b - t != ob - ot) applyLayout()
        }
    }

    private fun lg(m: String) = PreviewLog.log("slot$id", m)

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context).build().also { p ->
        player = p
        p.setVideoTextureView(texture)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    everReady = true
                    if (readySinceMs < 0) readySinceMs = SystemClock.elapsedRealtime()
                }
                lg("state=${stateName(state)} playWhenReady=${p.playWhenReady}")
            }

            override fun onRenderedFirstFrame() {
                firstFrame = true
                lg("first frame rendered")
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                srcW = (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt()
                srcH = videoSize.height
                lg("video size ${videoSize.width}x${videoSize.height} rot=${videoSize.unappliedRotationDegrees}")
                applyLayout()
            }

            override fun onSurfaceSizeChanged(width: Int, height: Int) = lg("surface ${width}x$height")

            override fun onPlayerError(error: PlaybackException) {
                err = error.errorCodeName
                lg("ERROR ${error.errorCodeName}")
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                PreviewLog.dropped += droppedFrames
                lg("dropped $droppedFrames frames in ${elapsedMs}ms")
            }

            override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
                PreviewLog.codecErrors++
                lg("video codec error ${videoCodecError.javaClass.simpleName}")
            }
        })
    }

    override val state: SlotState
        get() = when (mode) {
            Mode.NONE -> SlotState.IDLE
            Mode.IMAGE -> imgState
            Mode.VIDEO -> videoState()
        }
    override val error: String? get() = err
    override val sourcePositionUs: Long get() = if (mode == Mode.VIDEO) (player?.currentPosition ?: 0L) * 1000L else 0L

    private fun videoState(): SlotState {
        if (err != null) return SlotState.ERROR
        val p = player ?: return SlotState.IDLE
        return when (p.playbackState) {
            Player.STATE_IDLE -> SlotState.PREPARING
            Player.STATE_BUFFERING -> if (everReady) SlotState.BUFFERING else SlotState.PREPARING
            Player.STATE_ENDED -> SlotState.ENDED
            else -> {
                val gate = firstFrame || (readySinceMs >= 0 && SystemClock.elapsedRealtime() - readySinceMs >= FIRST_FRAME_GRACE_MS)
                if (!gate) SlotState.PREPARING else if (p.playWhenReady) SlotState.PLAYING else SlotState.READY
            }
        }
    }

    override fun prepare(seg: Segment, source: MediaSource, startSourceUs: Long) {
        clear()
        this.seg = seg
        err = null
        if (seg.kind == SegKind.IMAGE) {
            mode = Mode.IMAGE
            imgState = SlotState.PREPARING
            decodeImage(source, ++gen)
        } else {
            mode = Mode.VIDEO
            srcW = source.width
            srcH = source.height
            everReady = false
            firstFrame = false
            readySinceMs = -1
            val p = ensurePlayer()
            p.volume = seg.volume.coerceIn(0f, 1f)
            p.setPlaybackSpeed(seg.speed)
            p.playWhenReady = false
            p.setMediaItem(MediaItem.fromUri(source.uri), startSourceUs / 1000)
            p.prepare()
            lg("prepare video seg=${seg.clipId} start=${startSourceUs / 1000}ms speed=${seg.speed}")
        }
        applyVisibility()
    }

    override fun play() {
        if (mode == Mode.VIDEO) player?.playWhenReady = true
    }

    override fun pause() {
        if (mode == Mode.VIDEO) player?.playWhenReady = false
    }

    override fun seekToSource(us: Long) {
        if (mode == Mode.VIDEO) player?.seekTo(us / 1000)
    }

    override fun setVisible(visible: Boolean) {
        this.visible = visible
        applyVisibility()
        if (visible) root.bringToFront()
    }

    override fun clear() {
        gen++
        player?.let {
            it.playWhenReady = false
            it.stop()
            it.clearMediaItems()
        }
        image.setImageBitmap(null)
        bitmap = null
        mode = Mode.NONE
        imgState = SlotState.IDLE
        seg = null
        err = null
        applyVisibility()
    }

    override fun release() {
        clear()
        player?.release()
        player = null
        (root.parent as? ViewGroup)?.removeView(root)
    }

    private fun applyVisibility() {
        when (mode) {
            Mode.IMAGE -> {
                image.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                texture.alpha = HIDDEN_ALPHA
            }
            Mode.VIDEO -> {
                image.visibility = View.GONE
                texture.alpha = if (visible) 1f else HIDDEN_ALPHA
            }
            Mode.NONE -> {
                image.visibility = View.GONE
                texture.alpha = HIDDEN_ALPHA
            }
        }
    }

    private fun decodeImage(source: MediaSource, g: Int) {
        val uri = android.net.Uri.parse(source.uri)
        IMAGE_EXECUTOR.execute {
            var bmp: Bitmap? = null
            var failure: String? = null
            try {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                var b = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                val orientation = resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
                if (b != null) b = applyExif(b, orientation)
                bmp = b
                if (b == null) failure = "decode null"
            } catch (t: Throwable) {
                failure = t.javaClass.simpleName
            }
            Handler(Looper.getMainLooper()).post {
                if (g != gen || mode != Mode.IMAGE) {
                    return@post
                }
                if (bmp == null) {
                    err = failure ?: "decode"
                    imgState = SlotState.ERROR
                    lg("image ERROR $err")
                } else {
                    bitmap = bmp
                    srcW = bmp.width
                    srcH = bmp.height
                    image.setImageBitmap(bmp)
                    imgState = SlotState.READY
                    applyLayout()
                    lg("image ready ${bmp.width}x${bmp.height}")
                }
            }
        }
    }

    private fun applyExif(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun applyLayout() {
        val w = root.width
        val h = root.height
        val s = seg ?: return
        if (w <= 0 || h <= 0 || srcW <= 0 || srcH <= 0) return
        val lay = try {
            cfg.provider.layout(w.toDouble(), h.toDouble(), srcW.toDouble(), srcH.toDouble(), s.rotationDeg, s.crop, cfg.fit)
        } catch (t: Throwable) {
            lg("layout native gagal (${t.javaClass.simpleName}); pakai Kotlin")
            KotlinPlanProvider.layout(w.toDouble(), h.toDouble(), srcW.toDouble(), srcH.toDouble(), s.rotationDeg, s.crop, cfg.fit)
        }
        val m = Matrix()
        m.setValues(
            floatArrayOf(
                lay.m[0].toFloat(), lay.m[1].toFloat(), lay.m[2].toFloat(),
                lay.m[3].toFloat(), lay.m[4].toFloat(), lay.m[5].toFloat(),
                0f, 0f, 1f,
            ),
        )
        val clip = Rect(lay.clip[0].roundToInt(), lay.clip[1].roundToInt(), lay.clip[2].roundToInt(), lay.clip[3].roundToInt())
        if (mode == Mode.IMAGE) {
            image.imageMatrix = m
            image.clipBounds = clip
        } else {
            // TextureView merentangkan frame ke seluruh view; transform = M * S^-1 dengan S = scale(W/vw, H/vh).
            m.preScale(srcW.toFloat() / w, srcH.toFloat() / h)
            texture.setTransform(m)
            texture.clipBounds = clip
        }
    }

    private fun stateName(s: Int) = when (s) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "?"
    }

    companion object {
        const val HIDDEN_ALPHA = 0.01f
        const val FIRST_FRAME_GRACE_MS = 500L
        const val MAX_IMAGE_SIDE = 2048
        private val IMAGE_EXECUTOR = Executors.newSingleThreadExecutor()
    }
}

/** Slot audio tambahan: satu ExoPlayer tanpa tampilan. */
@OptIn(UnstableApi::class)
class ExoAudioSlot(private val context: Context, private val id: String) : AudioSlot {
    private var player: ExoPlayer? = null
    private var active = false
    private var err: String? = null
    private var everReady = false

    private fun lg(m: String) = PreviewLog.log("audio$id", m)

    private fun ensure(): ExoPlayer = player ?: ExoPlayer.Builder(context).build().also { p ->
        player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) everReady = true
                lg("state=$state")
            }

            override fun onPlayerError(error: PlaybackException) {
                err = error.errorCodeName
                lg("ERROR ${error.errorCodeName}")
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                PreviewLog.audioUnderruns++
                lg("underrun ${elapsedSinceLastFeedMs}ms")
            }
        })
    }

    override val state: SlotState
        get() {
            if (!active) return SlotState.IDLE
            if (err != null) return SlotState.ERROR
            val p = player ?: return SlotState.IDLE
            return when (p.playbackState) {
                Player.STATE_IDLE -> SlotState.PREPARING
                Player.STATE_BUFFERING -> if (everReady) SlotState.BUFFERING else SlotState.PREPARING
                Player.STATE_ENDED -> SlotState.ENDED
                else -> if (p.playWhenReady) SlotState.PLAYING else SlotState.READY
            }
        }
    override val error: String? get() = err
    override val sourcePositionUs: Long get() = if (active) (player?.currentPosition ?: 0L) * 1000L else 0L

    override fun prepare(seg: Segment, source: MediaSource, startSourceUs: Long) {
        clear()
        val p = ensure()
        active = true
        err = null
        everReady = false
        p.volume = seg.volume.coerceIn(0f, 1f)
        p.setPlaybackSpeed(seg.speed)
        p.playWhenReady = false
        p.setMediaItem(MediaItem.fromUri(source.uri), startSourceUs / 1000)
        p.prepare()
        lg("prepare start=${startSourceUs / 1000}ms speed=${seg.speed} vol=${seg.volume}")
    }

    override fun play() { if (active) player?.playWhenReady = true }
    override fun pause() { if (active) player?.playWhenReady = false }
    override fun seekToSource(us: Long) { if (active) player?.seekTo(us / 1000) }
    override fun setVolume(volume: Float) { player?.volume = volume.coerceIn(0f, 1f) }

    override fun clear() {
        player?.let {
            it.playWhenReady = false
            it.stop()
            it.clearMediaItems()
        }
        active = false
        err = null
    }

    override fun release() {
        clear()
        player?.release()
        player = null
    }
}
