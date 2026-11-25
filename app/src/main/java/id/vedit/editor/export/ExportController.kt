package id.vedit.editor.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import id.vedit.editor.domain.ExportNaming
import id.vedit.editor.domain.Project
import id.vedit.editor.preview.CompositionFactory
import java.io.File
import java.util.concurrent.Executors

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val percent: Int) : ExportState
    data class Done(val where: String) : ExportState
    data class Failed(val reason: String) : ExportState
}

/**
 * Export MP4 nyata memakai komposisi yang SAMA dengan preview (CompositionFactory).
 * Hasil ditulis ke folder app lalu (API 29+) disalin ke Movies/VEdit lewat MediaStore. Harus dipanggil dari main thread.
 */
@OptIn(UnstableApi::class)
class ExportController(private val ctx: Context) {
    var state: ExportState by mutableStateOf(ExportState.Idle)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var transformer: Transformer? = null
    private var released = false
    private val holder = ProgressHolder()
    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) state = ExportState.Running(holder.progress)
            main.postDelayed(this, 400)
        }
    }

    val isRunning: Boolean get() = state is ExportState.Running

    fun start(project: Project) {
        if (isRunning || released) return
        val composition = CompositionFactory.build(project)
        if (composition == null) {
            state = ExportState.Failed("Tidak ada konten di timeline untuk diexport.")
            return
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        dir.mkdirs()
        val file = File(dir, ExportNaming.fileName(project.name, System.currentTimeMillis()))
        state = ExportState.Running(0)
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                finishPolling()
                if (released) return
                if (!file.exists() || file.length() == 0L) {
                    state = ExportState.Failed("File hasil kosong.")
                    return
                }
                io.execute {
                    val where = runCatching { publish(file) }.getOrElse { file.absolutePath }
                    main.post { if (!released) state = ExportState.Done(where) }
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                finishPolling()
                file.delete()
                if (!released) state = ExportState.Failed("${exportException.errorCodeName}: ${exportException.message}")
            }
        }
        try {
            val t = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(listener)
                .build()
            transformer = t
            main.postDelayed(poll, 400)
            t.start(composition, file.absolutePath)
        } catch (e: Exception) {
            finishPolling()
            state = ExportState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun cancel() {
        transformer?.cancel()
        finishPolling()
        if (isRunning) state = ExportState.Idle
    }

    fun dismiss() {
        if (!isRunning) state = ExportState.Idle
    }

    fun release() {
        released = true
        transformer?.cancel()
        finishPolling()
        io.shutdown()
    }

    private fun finishPolling() {
        main.removeCallbacks(poll)
        transformer = null
    }

    private fun publish(file: File): String {
        if (Build.VERSION.SDK_INT < 29) return file.absolutePath
        return publishQ(file)
    }

    @RequiresApi(29)
    private fun publishQ(file: File): String {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VEdit")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return file.absolutePath
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return file.absolutePath
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        file.delete()
        return "Movies/VEdit/${file.name}"
    }
}
