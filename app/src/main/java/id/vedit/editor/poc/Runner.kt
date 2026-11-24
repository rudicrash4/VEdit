package id.vedit.editor.poc

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

enum class Status { PENDING, RUNNING, PASS, FAIL, INFO, SKIPPED }

data class ExperimentResult(
    val id: String,
    val title: String,
    val what: String,
    val status: Status,
    val expectedMs: Long? = null,
    val actualMs: Long? = null,
    val details: String = "",
    val thumbnail: Bitmap? = null,
)

private data class Probe(
    val durationMs: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val mimes: List<String>,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val frame: Bitmap?,
)

/** Menjalankan eksperimen berurutan di main thread (Transformer/CompositionPlayer wajib satu thread). */
@OptIn(UnstableApi::class)
class PocRunner(
    context: Context,
    private val experiments: List<Experiment>,
    private val onChange: (List<ExperimentResult>) -> Unit,
) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val outDir = File(ctx.cacheDir, "poc").apply { mkdirs() }
    private val results = experiments.map {
        ExperimentResult(it.id, it.title, it.what, Status.PENDING, it.expectedMs)
    }.toMutableList()
    private var transformer: Transformer? = null
    private var cancelled = false

    fun start() {
        notifyChange()
        runNext(0)
    }

    fun cancel() {
        cancelled = true
        transformer?.cancel()
    }

    private fun notifyChange() = onChange(results.toList())

    private fun set(i: Int, r: ExperimentResult) {
        results[i] = r
        notifyChange()
    }

    private fun runNext(i: Int) {
        if (cancelled || i >= experiments.size) {
            io.shutdown()
            return
        }
        val e = experiments[i]
        set(i, results[i].copy(status = Status.RUNNING))
        val next = { runNext(i + 1) }
        try {
            when (e.kind) {
                Kind.EXPORT -> runExport(i, e, next)
                Kind.PREVIEW_DURATION -> runPreview(i, e, next)
            }
        } catch (t: Throwable) {
            set(i, results[i].copy(status = Status.FAIL, details = "Exception saat menyiapkan: ${t::class.java.simpleName}: ${t.message}"))
            next()
        }
    }

    private fun runExport(i: Int, e: Experiment, next: () -> Unit) {
        val out = File(outDir, "${e.id}.mp4").also { it.delete() }
        var finished = false
        val watchdog = Runnable {
            if (!finished) {
                finished = true
                transformer?.cancel()
                set(i, results[i].copy(status = Status.FAIL, details = "Timeout 180 dtk"))
                next()
            }
        }
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (finished) return
                finished = true
                main.removeCallbacks(watchdog)
                io.execute {
                    val r = evaluate(e, out)
                    main.post {
                        set(i, r.copy(id = e.id, title = e.title, what = e.what))
                        next()
                    }
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                if (finished) return
                finished = true
                main.removeCallbacks(watchdog)
                set(i, results[i].copy(status = Status.FAIL, details = "ExportException ${exportException.errorCodeName}: ${exportException.message}"))
                next()
            }
        }
        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(listener)
            .build()
        transformer = t
        main.postDelayed(watchdog, 180_000)
        t.start(e.build(), out.absolutePath)
    }

    private fun evaluate(e: Experiment, file: File): ExperimentResult {
        val p = try {
            probe(file)
        } catch (t: Throwable) {
            return ExperimentResult(e.id, e.title, e.what, Status.FAIL, e.expectedMs, null, "File tidak bisa dibuka: ${t.message}")
        }
        val notes = ArrayList<String>()
        var ok = true
        if (!p.hasVideo || !p.mimes.contains("video/avc")) { ok = false; notes += "video H.264 tidak ada (mime: ${p.mimes})" }
        if (e.expectAudio && (!p.hasAudio || !p.mimes.contains("audio/mp4a-latm"))) { ok = false; notes += "audio AAC tidak ada (mime: ${p.mimes})" }
        val exp = e.expectedMs
        if (exp != null) {
            val tol = max(300L, (exp * 0.08).toLong())
            val diff = p.durationMs - exp
            if (abs(diff) > tol) { ok = false; notes += "durasi menyimpang ${diff} ms (toleransi $tol ms)" }
        }
        notes += "${p.width}x${p.height}, ${p.sizeBytes / 1024} KB, tracks=${p.mimes}"
        return ExperimentResult(e.id, e.title, e.what, if (ok) Status.PASS else Status.FAIL, exp, p.durationMs, notes.joinToString("; "), p.frame)
    }

    private fun probe(file: File): Probe {
        require(file.exists() && file.length() > 0) { "file kosong/tidak ada" }
        val mimes = ArrayList<String>()
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            for (k in 0 until ex.trackCount) ex.getTrackFormat(k).getString(MediaFormat.KEY_MIME)?.let { mimes += it }
        } finally {
            ex.release()
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val frame = r.getFrameAtTime(minOf(1_000_000L, maxOf(dur, 0) * 500L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { Bitmap.createScaledBitmap(it, 320, max(1, 320 * it.height / max(1, it.width)), true) }
            return Probe(dur, mimes.any { it.startsWith("video/") }, mimes.any { it.startsWith("audio/") }, mimes, w, h, file.length(), frame)
        } finally {
            r.release()
        }
    }

    private fun runPreview(i: Int, e: Experiment, next: () -> Unit) {
        val player = CompositionPlayer.Builder(ctx).build()
        var finished = false
        fun finish(r: ExperimentResult) {
            if (finished) return
            finished = true
            player.release()
            set(i, r)
            next()
        }
        val watchdog = Runnable {
            finish(results[i].copy(status = Status.FAIL, details = "CompositionPlayer tidak mencapai STATE_READY dalam 20 dtk (tanpa Surface)"))
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    main.removeCallbacks(watchdog)
                    val previewMs = player.duration
                    val exportMs = results.firstOrNull { it.id == e.comparedWith }?.actualMs
                    val detail = if (exportMs == null) {
                        "Durasi preview $previewMs ms; export pembanding tidak tersedia"
                    } else {
                        "Durasi preview $previewMs ms vs export $exportMs ms (selisih ${previewMs - exportMs} ms)"
                    }
                    val ok = exportMs != null && abs(previewMs - exportMs) <= max(300L, (exportMs * 0.08).toLong())
                    finish(results[i].copy(status = if (ok) Status.PASS else Status.INFO, expectedMs = exportMs, actualMs = previewMs, details = detail))
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                main.removeCallbacks(watchdog)
                finish(results[i].copy(status = Status.FAIL, details = "PlaybackException ${error.errorCodeName}: ${error.message}"))
            }
        })
        main.postDelayed(watchdog, 20_000)
        player.setComposition(e.build())
        player.prepare()
    }

    fun reportText(): String = buildString {
        appendLine("VEdit Phase 0B report")
        appendLine("Device: ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}")
        results.forEach {
            appendLine("[${it.status}] ${it.title}")
            appendLine("   expected=${it.expectedMs} ms, actual=${it.actualMs} ms")
            appendLine("   ${it.details}")
        }
    }
}
