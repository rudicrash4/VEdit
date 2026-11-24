package id.vedit.editor.poc

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.sqrt

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

/** Hasil pengukuran file export; dipakai analisis lintas-eksperimen. */
private class Metrics(
    val durationMs: Long,
    val rms: Double?,
    val frame: Bitmap?,
    val videoTimesUs: List<Long>,
    val audioEndUs: Long?,
    val videoEndUs: Long?,
)

private class Probe(
    val durationMs: Long,
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
    private val metrics = ConcurrentHashMap<String, Metrics>()
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
        if (cancelled) {
            io.shutdown()
            return
        }
        if (i >= experiments.size) {
            analyze()
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
            set(i, results[i].copy(status = Status.FAIL, details = "Gagal menyiapkan: ${Analysis.describeThrowable(t)}"))
            next()
        }
    }

    // ---------------------------------------------------------------- export

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
                        set(i, r)
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
        val base = ExperimentResult(e.id, e.title, e.what, Status.FAIL, e.expectedMs)
        val p = try {
            probe(file)
        } catch (t: Throwable) {
            return base.copy(details = "File tidak bisa dibuka: ${Analysis.describeThrowable(t)}")
        }
        val notes = ArrayList<String>()
        var ok = true
        val hasVideo = p.mimes.contains("video/avc")
        val hasAudio = p.mimes.contains("audio/mp4a-latm")
        if (e.expectVideo && !hasVideo) { ok = false; notes += "video H.264 tidak ada (mime: ${p.mimes})" }
        if (e.expectAudio && !hasAudio) { ok = false; notes += "audio AAC tidak ada (mime: ${p.mimes})" }
        e.expectedMs?.let {
            if (!Analysis.durationOk(it, p.durationMs)) {
                ok = false
                notes += "durasi menyimpang ${p.durationMs - it} ms (toleransi ${Analysis.durationTolerance(it)} ms)"
            }
        }
        val videoTimes = if (hasVideo) runCatching { sampleTimesUs(file, "video/") }.getOrDefault(emptyList()) else emptyList()
        val audioTimes = if (hasAudio) runCatching { sampleTimesUs(file, "audio/") }.getOrDefault(emptyList()) else emptyList()
        val rms = if (hasAudio) runCatching { audioRms(file) }.getOrNull() else null
        val vEnd = videoTimes.maxOrNull()
        val aEnd = audioTimes.maxOrNull()
        if (vEnd != null && aEnd != null) notes += "selisih akhir audio-video ${Analysis.avEndDeltaMs(aEnd, vEnd)} ms"
        if (videoTimes.size > 1) {
            notes += "frame=${videoTimes.size}, jeda maks ${Analysis.maxGapUs(videoTimes) / 1000} ms, monoton=${Analysis.isMonotonic(videoTimes.sorted())}"
        }
        if (rms != null) notes += "RMS audio=%.4f".format(rms)
        notes += "${p.width}x${p.height}, ${p.sizeBytes / 1024} KB, tracks=${p.mimes}"
        metrics[e.id] = Metrics(p.durationMs, rms, p.frame, videoTimes, aEnd, vEnd)
        return base.copy(
            status = if (ok) Status.PASS else Status.FAIL,
            actualMs = p.durationMs,
            details = notes.joinToString("; "),
            thumbnail = p.frame,
        )
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
            val frame = if (mimes.any { it.startsWith("video/") }) {
                // Waktu frame sama untuk semua export (1 dtk, atau tengah jika lebih pendek) agar bisa dibandingkan piksel demi piksel.
                val atUs = if (dur >= 2000) 1_000_000L else max(dur, 0) * 500L
                r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?.let { Bitmap.createScaledBitmap(it, 320, max(1, 320 * it.height / max(1, it.width)), true) }
            } else {
                null
            }
            return Probe(dur, mimes, w, h, file.length(), frame)
        } finally {
            r.release()
        }
    }

    /** Timestamp presentasi semua sample sebuah track (mikrodetik). */
    private fun sampleTimesUs(file: File, mimePrefix: String): List<Long> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val idx = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true
            } ?: return emptyList()
            ex.selectTrack(idx)
            val out = ArrayList<Long>()
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                out += t
                if (!ex.advance()) break
            }
            return out
        } finally {
            ex.release()
        }
    }

    /** RMS ternormalisasi (0..1) dari audio hasil dekode PCM 16-bit seluruh track audio. */
    private fun audioRms(file: File): Double? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            val idx = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(idx)
            val fmt = ex.getTrackFormat(idx)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var sumSq = 0.0
            var n = 0L
            var inDone = false
            var outDone = false
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (!outDone && SystemClock.elapsedRealtime() < deadline) {
                if (!inDone) {
                    val ib = codec.dequeueInputBuffer(10_000)
                    if (ib >= 0) {
                        val buf = codec.getInputBuffer(ib)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ib, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, 10_000)
                if (ob >= 0) {
                    val out = codec.getOutputBuffer(ob)!!
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                    while (sb.hasRemaining()) {
                        val v = sb.get().toDouble()
                        sumSq += v * v
                        n++
                    }
                    codec.releaseOutputBuffer(ob, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            return if (n == 0L) null else sqrt(sumSq / n) / 32768.0
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    // --------------------------------------------------------------- preview

    @OptIn(UnstableApi::class, ExperimentalApi::class)
    private fun runPreview(i: Int, e: Experiment, next: () -> Unit) {
        var player: CompositionPlayer? = null
        var finished = false
        val token = Any()
        fun finish(r: ExperimentResult) {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(token)
            runCatching { player?.release() }
            set(i, r)
            next()
        }
        val wd = Runnable {
            finish(results[i].copy(status = Status.FAIL, details = "CompositionPlayer tidak mencapai STATE_READY dalam 20 dtk (tanpa Surface)"))
        }
        fun cancelWd() = main.removeCallbacksAndMessages(token)
        try {
            val p = CompositionPlayer.Builder(ctx).build()
            player = p
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        cancelWd()
                        val previewMs = p.duration
                        val exportMs = results.firstOrNull { it.id == e.comparedWith }?.actualMs
                        val verdict = Analysis.previewVerdict(previewMs, exportMs)
                        val detail = "Durasi preview $previewMs ms vs export ${exportMs ?: "?"} ms" +
                            (if (exportMs != null) " (selisih ${previewMs - exportMs} ms)" else "") +
                            ". Ini hanya membandingkan DURASI; kesamaan visual/audio dinilai lewat panel preview manual."
                        finish(
                            results[i].copy(
                                status = when (verdict) {
                                    Analysis.PreviewVerdict.CONSISTENT -> Status.PASS
                                    Analysis.PreviewVerdict.DIVERGENT -> Status.FAIL
                                    Analysis.PreviewVerdict.UNKNOWN -> Status.INFO
                                },
                                expectedMs = exportMs, actualMs = previewMs, details = detail,
                            ),
                        )
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    cancelWd()
                    finish(results[i].copy(status = Status.FAIL, details = "PlaybackException ${error.errorCodeName}: ${error.message}"))
                }
            })
            main.postAtTime(wd, token, SystemClock.uptimeMillis() + 20_000)
            p.setComposition(e.build())
            p.prepare()
        } catch (t: Throwable) {
            finish(results[i].copy(status = Status.FAIL, details = "Gagal menyiapkan preview: ${Analysis.describeThrowable(t)}"))
        }
    }

    // -------------------------------------------------------------- analysis

    /** Analisis lintas-eksperimen. Ambang bersifat heuristik; kegagalan ambang = INFO (cek manual), bukan klaim rusak. */
    private fun analyze() {
        val out = ArrayList<ExperimentResult>()
        fun add(id: String, title: String, status: Status, details: String, thumb: Bitmap? = null) {
            out += ExperimentResult(id, title, "Analisis otomatis atas file export", status, details = details, thumbnail = thumb)
        }

        // 1. PiP: dua lapisan benar-benar tampil
        val pip = metrics["pip"]?.frame
        val solo = metrics["speed_1.0"]?.frame
        if (pip != null && solo != null && pip.width == solo.width && pip.height == solo.height) {
            val w = pip.width
            val h = pip.height
            val a = IntArray(w * h).also { pip.getPixels(it, 0, w, 0, 0, w, h) }
            val b = IntArray(w * h).also { solo.getPixels(it, 0, w, 0, 0, w, h) }
            val corner = Analysis.meanAbsLumaDiff(a, b, w, h, (w * 0.62).toInt(), (h * 0.62).toInt(), w, h)
            val ref = Analysis.meanAbsLumaDiff(a, b, w, h, 0, 0, (w * 0.38).toInt(), (h * 0.38).toInt())
            val ok = Analysis.pipLayersVisible(corner, ref)
            add("an_pip", "E1. PiP menampilkan dua lapisan", if (ok) Status.PASS else Status.INFO,
                "Selisih luma area PiP (kanan bawah) vs klip 1 sendirian = %.1f; area acuan (kiri atas) = %.1f. ".format(corner, ref) +
                    if (ok) "Area PiP berbeda nyata sementara latar identik: lapisan kedua terbukti ada." else
                        "Tidak terbukti otomatis (jika Video 1 dan 2 isinya mirip, selisih bisa kecil). Periksa thumbnail secara visual.",
                pip)
        } else {
            add("an_pip", "E1. PiP menampilkan dua lapisan", Status.SKIPPED, "Frame pembanding tidak tersedia")
        }

        // 2. Volume video
        val rv = metrics["speed_1.0"]?.rms
        val rv5 = metrics["vol_v"]?.rms
        if (rv != null && rv5 != null && rv > 0) {
            val ratio = rv5 / rv
            val ok = Analysis.ratioOk(ratio, 0.5)
            add("an_vol_v", "E2. Volume audio video 50% terkontrol", if (ok) Status.PASS else Status.INFO, "Rasio RMS = %.3f (diharapkan 0.50 +-15%%)".format(ratio))
        } else {
            add("an_vol_v", "E2. Volume audio video 50% terkontrol", Status.SKIPPED, "RMS tidak tersedia (video tanpa audio?)")
        }

        // 3. Volume audio tambahan, 4. mixing
        val ra1 = metrics["vol_a_1.0"]?.rms
        val ra3 = metrics["vol_a_0.3"]?.rms
        if (ra1 != null && ra3 != null && ra1 > 0) {
            val ratio = ra3 / ra1
            val ok = Analysis.ratioOk(ratio, 0.3)
            add("an_vol_a", "E3. Volume audio tambahan 30% terkontrol", if (ok) Status.PASS else Status.INFO, "Rasio RMS = %.3f (diharapkan 0.30 +-15%%)".format(ratio))
            val mix = metrics["audio_mix"]
            val vSec = (metrics["speed_1.0"]?.durationMs ?: 0L) / 1000.0
            val aSec = (metrics["vol_a_0.3"]?.durationMs ?: 0L) / 1000.0
            val mixSec = (mix?.durationMs ?: 0L) / 1000.0
            if (mix?.rms != null && rv != null && mixSec > 0) {
                val expected = Analysis.expectedMixRms(rv, vSec, ra3, aSec, mixSec)
                val ok2 = Analysis.ratioOk(mix.rms, expected, 0.35)
                val vOnlyGap = mix.rms > rv * 1.02
                add("an_mix", "E4. Audio asli + tambahan benar-benar tercampur",
                    if (ok2 && vOnlyGap) Status.PASS else Status.INFO,
                    "RMS campuran %.4f; prediksi dari komponen %.4f (+-35%%); RMS audio video saja %.4f. ".format(mix.rms, expected, rv) +
                        "Asumsi sumber tak berkorelasi; konten berkorelasi (mis. file yang sama) bisa menyimpang. Kejelasan campuran tetap perlu didengar.")
            } else {
                add("an_mix", "E4. Audio asli + tambahan benar-benar tercampur", Status.SKIPPED, "RMS campuran tidak tersedia")
            }
        } else {
            add("an_vol_a", "E3. Volume audio tambahan 30% terkontrol", Status.SKIPPED, "Tidak ada file audio tambahan atau RMS tidak tersedia")
            add("an_mix", "E4. Audio asli + tambahan benar-benar tercampur", Status.SKIPPED, "Tidak ada file audio tambahan")
        }

        // 5. Kontinuitas speed ramp
        val ramp = metrics["ramp"]?.videoTimesUs
        val base = metrics["speed_1.0"]?.videoTimesUs
        if (ramp != null && base != null && ramp.size > 2 && base.size > 2) {
            val med = Analysis.medianGapUs(base)
            val max = Analysis.maxGapUs(ramp)
            val mono = Analysis.isMonotonic(ramp.sorted())
            val ok = mono && Analysis.rampGapOk(max, med, Experiments.RAMP_MIN_SPEED)
            add("an_ramp", "E5. Speed ramp tanpa lompatan frame", if (ok) Status.PASS else Status.INFO,
                "Jeda frame maks ${max / 1000} ms; jeda median 1x = ${med / 1000} ms; batas pada 0.5x = ${(med / Experiments.RAMP_MIN_SPEED * 1.6 / 1000).toLong()} ms. " +
                    "Ini mengukur kontinuitas timestamp, bukan kehalusan visual atau pitch audio.")
        } else {
            add("an_ramp", "E5. Speed ramp tanpa lompatan frame", Status.SKIPPED, "Timestamp frame tidak tersedia")
        }

        // 6. Sinkron audio-video (selisih akhir track)
        val deltas = experiments.mapNotNull { ex ->
            val m = metrics[ex.id] ?: return@mapNotNull null
            if (m.audioEndUs != null && m.videoEndUs != null) ex.id to Analysis.avEndDeltaMs(m.audioEndUs, m.videoEndUs) else null
        }
        if (deltas.isNotEmpty()) {
            val worst = deltas.maxByOrNull { it.second }!!
            val ok = worst.second <= Analysis.AV_DELTA_LIMIT_MS
            add("an_sync", "E6. Akhir track audio dan video selaras", if (ok) Status.PASS else Status.INFO,
                "Selisih terbesar ${worst.second} ms pada '${worst.first}' (batas ${Analysis.AV_DELTA_LIMIT_MS} ms) dari ${deltas.size} export. " +
                    "Ini BUKAN bukti lip-sync; sinkron bicara/gambar harus ditonton.")
        }

        results += out
        notifyChange()
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
