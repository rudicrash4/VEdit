package id.vedit.editor.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.vedit.editor.export.ExportController
import id.vedit.editor.export.ExportState
import id.vedit.editor.data.Autosaver
import id.vedit.editor.data.HandlerScheduler
import id.vedit.editor.data.MediaImporter
import id.vedit.editor.data.ProjectStore
import id.vedit.editor.domain.AspectPreset
import id.vedit.editor.domain.Crop
import id.vedit.editor.domain.ExportPlan
import id.vedit.editor.domain.FitMode
import id.vedit.editor.domain.Edit
import id.vedit.editor.engine.EditOp
import id.vedit.editor.engine.EngineFactory
import id.vedit.editor.domain.MediaType
import id.vedit.editor.domain.Project
import id.vedit.editor.domain.US_PER_SECOND
import id.vedit.editor.domain.formatTime
import id.vedit.editor.domain.PreviewPlan
import id.vedit.editor.preview.defaultPreviewController
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.Executors

/** Hook agar Activity bisa menyimpan segera saat aplikasi berhenti (autosave dengan debounce bisa tertunda). */
object ActiveEditor {
    var flush: (() -> Unit)? = null
    var pause: (() -> Unit)? = null
}

private val CROP_PRESETS = listOf<Pair<String, Float?>>("Tanpa crop" to null, "1:1" to 1f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)

@Composable
fun EditorScreen(store: ProjectStore, projectId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val loaded = remember(projectId) { store.load(projectId) }
    val first = loaded.getOrNull()
    if (first == null) {
        Column(Modifier.padding(16.dp)) {
            Text("Proyek tidak bisa dibuka: ${loaded.exceptionOrNull()?.message}")
            Button(onClick = onBack) { Text("Kembali") }
        }
        return
    }

    // Engine edit = pemilik tunggal state proyek (Rust bila pustaka native termuat, Kotlin sebagai cadangan).
    val created = remember(projectId) { EngineFactory.create(first.project) }
    val engine = created.engine
    DisposableEffect(projectId) { onDispose { engine.close() } }
    var project by remember(projectId) { mutableStateOf(engine.project) }
    var selectedId by remember(projectId) { mutableStateOf<String?>(null) }
    var playheadUs by remember(projectId) { mutableLongStateOf(0L) }
    var dpPerSecond by remember(projectId) { mutableFloatStateOf(60f) }
    var message by remember(projectId) { mutableStateOf(if (first.recoveredFromBackup) "Proyek dipulihkan dari cadangan terakhir." else "") }
    var importing by remember(projectId) { mutableStateOf(false) }
    var cropIndex by remember(projectId) { mutableStateOf(0) }

    val autosaver = remember(projectId) { Autosaver(HandlerScheduler(), 800) { store.save(it) } }
    val preview = remember(projectId) { defaultPreviewController(context) }
    val importer = remember { MediaImporter(context.applicationContext) }
    val io = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(projectId) {
        ActiveEditor.flush = { autosaver.flushNow() }
        ActiveEditor.pause = { preview.pause() }
        onDispose {
            autosaver.flushNow()
            ActiveEditor.flush = null
            ActiveEditor.pause = null
            preview.release()
            io.shutdown()
        }
    }

    fun seek(us: Long) {
        val t = PreviewPlan.clampPlayhead(us, project.durationUs)
        playheadUs = t
        preview.seekTo(t)
    }

    fun selectClip(id: String?) {
        selectedId = id
        // Memilih klip memindah playhead ke awal klip bila playhead belum di dalamnya, sehingga preview menampilkan klip itu.
        val c = id?.let { project.clip(it) }
        if (c != null && !PreviewPlan.playheadInside(c, playheadUs)) seek(c.startUs)
    }

    // Bangun ulang preview bila struktur proyek berubah (split/trim/urutan/impor/undo), dengan debounce.
    LaunchedEffect(project.tracks, project.sources, project.canvas) {
        delay(300)
        preview.update(project, playheadUs)
    }

    // Playhead mengikuti pemutar saat memutar (dan sekali lagi saat berhenti agar posisi akhir tercatat).
    LaunchedEffect(preview) {
        var was = false
        while (true) {
            delay(100)
            val playing = preview.ui.isPlaying
            if (playing || was) playheadUs = PreviewPlan.clampPlayhead(preview.positionUs, engine.project.durationUs)
            was = playing
        }
    }
    BackHandler { autosaver.flushNow(); onBack() }

    fun commit(op: EditOp) {
        when (val edit = engine.apply(op, System.currentTimeMillis())) {
            is Edit.Ok -> {
                project = engine.project
                selectedId = edit.selectedClipId
                message = ""
                autosaver.onChanged(engine.project)
            }
            is Edit.Err -> message = edit.reason
        }
    }

    fun newId() = UUID.randomUUID().toString()

    fun importFrom(uris: List<Uri>, type: MediaType) {
        if (uris.isEmpty()) return
        importing = true
        io.execute {
            val r = importer.import(uris, type)
            main.post {
                importing = false
                if (r.sources.isNotEmpty()) {
                    commit(EditOp.AddSources(r.sources))
                }
                message = if (r.failures.isEmpty()) "${r.sources.size} media diimpor." else "Gagal: " + r.failures.joinToString("; ")
            }
        }
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importFrom(it, MediaType.VIDEO) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importFrom(it, MediaType.IMAGE) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importFrom(it, MediaType.AUDIO) }

    val selected = selectedId?.let { project.clip(it) }
    val exporter = remember(projectId) { ExportController(context) }
    DisposableEffect(projectId) { onDispose { exporter.release() } }

    @Composable
    fun Header() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { autosaver.flushNow(); onBack() }) { Text("< Kembali") }
            Text(project.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(enabled = engine.canUndo, onClick = { engine.undo()?.let { project = it; selectedId = null; autosaver.onChanged(it) } }) { Text("Undo") }
            TextButton(enabled = engine.canRedo, onClick = { engine.redo()?.let { project = it; selectedId = null; autosaver.onChanged(it) } }) { Text("Redo") }
            Button(
                enabled = !exporter.isRunning && project.durationUs > 0,
                onClick = { preview.pause(); exporter.start(engine.project) },
            ) { Text("Export") }
        }
        Text(
            "Engine edit: ${engine.name}" + (created.fallbackReason?.let { " (cadangan: $it)" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
        )
        when (val st = exporter.state) {
            is ExportState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.weight(1f))
                Text("  ${st.percent}%", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { exporter.cancel() }) { Text("Batal") }
            }
            is ExportState.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Export selesai (${st.size}): ${st.where}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { exporter.dismiss() }) { Text("OK") }
            }
            is ExportState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Export gagal: ${st.reason}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { exporter.dismiss() }) { Text("OK") }
            }
            ExportState.Idle -> {}
        }
    }

    @Composable
    fun PreviewBlock(maxVideoHeight: Dp) {
        PreviewPanel(preview, playheadUs, project.durationUs, maxVideoHeight, project.canvas.aspect.ratio)
        if (selected != null) {
            val src = project.source(selected.sourceId)
            Text(
                "Terpilih: ${src?.displayName} | ${formatTime(selected.durationUs)} | rotasi ${selected.rotationDeg}" +
                    (selected.crop?.let { " | crop" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    @Composable
    fun CanvasBlock() {
        Text("Kanvas proyek (rasio hasil export)", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AspectPreset.values().forEach { a ->
                val on = project.canvas.aspect == a
                val set = { commit(EditOp.SetCanvas(project.canvas.copy(aspect = a))) }
                if (on) Button(onClick = set) { Text(a.label) } else OutlinedButton(onClick = set) { Text(a.label) }
            }
            FitMode.values().forEach { f ->
                val on = project.canvas.fit == f
                val set = { commit(EditOp.SetCanvas(project.canvas.copy(fit = f))) }
                val label = if (f == FitMode.FIT) "Fit" else "Fill"
                if (on) Button(onClick = set) { Text(label) } else OutlinedButton(onClick = set) { Text(label) }
            }
        }
        val size = ExportPlan.sizeFor(project)
        Text("Export: ${size.width}x${size.height} (720p). 1080p belum diaktifkan.", style = MaterialTheme.typography.bodySmall)
    }

    @Composable
    fun TimelineBlock() {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { seek(0) }) { Text("|<") }
            OutlinedButton(onClick = { seek(playheadUs - US_PER_SECOND) }) { Text("-1s") }
            OutlinedButton(onClick = { seek(playheadUs + US_PER_SECOND) }) { Text("+1s") }
            OutlinedButton(onClick = { seek(project.durationUs) }) { Text(">|") }
            OutlinedButton(onClick = { dpPerSecond = (dpPerSecond * 0.7f).coerceAtLeast(10f) }) { Text("Zoom -") }
            OutlinedButton(onClick = { dpPerSecond = (dpPerSecond * 1.4f).coerceAtMost(400f) }) { Text("Zoom +") }
        }
        Timeline(
            project = project, selectedClipId = selectedId, playheadUs = playheadUs, dpPerSecond = dpPerSecond,
            onSelect = { selectClip(it) }, onSeek = { seek(it) },
        )
    }

    @Composable
    fun ToolsBlock() {
        // Alat edit untuk klip terpilih
        Text("Alat edit", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val id = selectedId
            val enabled = id != null && selected != null
            Button(enabled = enabled, onClick = { commit(EditOp.Split(id!!, playheadUs, newId())) }) { Text("Split") }
            Button(enabled = enabled, onClick = { commit(EditOp.TrimStart(id!!, playheadUs)) }) { Text("Trim awal") }
            Button(enabled = enabled, onClick = { commit(EditOp.TrimEnd(id!!, playheadUs)) }) { Text("Trim akhir") }
            Button(enabled = enabled, onClick = { commit(EditOp.Duplicate(id!!, newId())) }) { Text("Duplikat") }
            Button(enabled = enabled, onClick = { commit(EditOp.Delete(id!!, ripple = false)) }) { Text("Hapus") }
            Button(enabled = enabled, onClick = { commit(EditOp.Delete(id!!, ripple = true)) }) { Text("Hapus+rapatkan") }
            Button(enabled = enabled, onClick = {
                val order = project.trackOf(id!!)!!.clips.sortedBy { it.startUs }
                commit(EditOp.Reorder(id, order.indexOfFirst { it.id == id } - 1))
            }) { Text("Geser kiri") }
            Button(enabled = enabled, onClick = {
                val order = project.trackOf(id!!)!!.clips.sortedBy { it.startUs }
                commit(EditOp.Reorder(id, order.indexOfFirst { it.id == id } + 1))
            }) { Text("Geser kanan") }
            Button(enabled = enabled, onClick = { commit(EditOp.SetRotation(id!!, selected!!.rotationDeg + 90)) }) { Text("Rotasi 90") }
            Button(
                enabled = enabled && project.source(selected!!.sourceId)?.type != MediaType.AUDIO,
                onClick = {
                    cropIndex = (cropIndex + 1) % CROP_PRESETS.size
                    val (_, aspect) = CROP_PRESETS[cropIndex]
                    val src = project.source(selected!!.sourceId)!!
                    val srcAspect = if (src.height > 0) src.width.toFloat() / src.height else 1f
                    commit(EditOp.SetCrop(id!!, aspect?.let { Crop.centered(it, srcAspect) }))
                },
            ) { Text("Crop: ${CROP_PRESETS[cropIndex].first}") }
        }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

    }

    @Composable
    fun MediaBlock() {
        // Media bin
        Text("Media proyek", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(enabled = !importing, onClick = { pickVideo.launch(arrayOf("video/*")) }) { Text("+ Video") }
            OutlinedButton(enabled = !importing, onClick = { pickImage.launch(arrayOf("image/*")) }) { Text("+ Gambar") }
            OutlinedButton(enabled = !importing, onClick = { pickAudio.launch(arrayOf("audio/*")) }) { Text("+ Audio") }
        }
        if (importing) Text("Mengimpor...", style = MaterialTheme.typography.bodySmall)
        if (project.sources.isEmpty()) Text("Belum ada media. Impor lalu ketuk 'Ke timeline'.", style = MaterialTheme.typography.bodySmall)
        project.sources.forEach { src ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(src.displayName, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${src.type} | ${formatTime(src.durationUs)}" + if (src.width > 0) " | ${src.width}x${src.height}" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = {
                    val track = if (src.type == MediaType.AUDIO) Project.AUDIO_TRACK else Project.VIDEO_TRACK
                    commit(EditOp.AddClip(track, src.id, newId()))
                }) { Text("Ke timeline") }
            }
        }
    }

    // Layout editor: dark studio, preview sebagai fokus utama, timeline dan tool dalam panel terpisah.
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF090B10))) {
        val boxH = this.maxHeight
        val wide = this.maxWidth >= 700.dp && this.maxWidth > boxH
        @Composable
        fun Panel(content: @Composable () -> Unit) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151A23)),
                modifier = Modifier.fillMaxWidth(),
            ) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1.35f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Header()
                    Panel { PreviewBlock((boxH * 0.48f).coerceAtLeast(150.dp)) }
                    Panel { CanvasBlock() }
                    Panel { TimelineBlock() }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Panel { ToolsBlock() }
                    Panel { MediaBlock() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header()
                Panel { PreviewBlock((boxH * 0.34f).coerceAtLeast(130.dp)) }
                Panel { CanvasBlock() }
                Panel { TimelineBlock() }
                Panel { ToolsBlock() }
                Panel { MediaBlock() }
            }
        }
    }
}
