package id.vedit.editor.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import id.vedit.editor.data.Autosaver
import id.vedit.editor.data.HandlerScheduler
import id.vedit.editor.data.MediaImporter
import id.vedit.editor.data.ProjectStore
import id.vedit.editor.domain.Crop
import id.vedit.editor.domain.Edit
import id.vedit.editor.domain.EditHistory
import id.vedit.editor.domain.MediaType
import id.vedit.editor.domain.Project
import id.vedit.editor.domain.TimelineEngine
import id.vedit.editor.domain.US_PER_SECOND
import id.vedit.editor.domain.formatTime
import java.util.UUID
import java.util.concurrent.Executors

/** Hook agar Activity bisa menyimpan segera saat aplikasi berhenti (autosave dengan debounce bisa tertunda). */
object ActiveEditor {
    var flush: (() -> Unit)? = null
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

    val history = remember(projectId) { EditHistory(first.project) }
    var project by remember(projectId) { mutableStateOf(history.current) }
    var selectedId by remember(projectId) { mutableStateOf<String?>(null) }
    var playheadUs by remember(projectId) { mutableLongStateOf(0L) }
    var dpPerSecond by remember(projectId) { mutableFloatStateOf(60f) }
    var message by remember(projectId) { mutableStateOf(if (first.recoveredFromBackup) "Proyek dipulihkan dari cadangan terakhir." else "") }
    var importing by remember(projectId) { mutableStateOf(false) }
    var cropIndex by remember(projectId) { mutableStateOf(0) }

    val autosaver = remember(projectId) { Autosaver(HandlerScheduler(), 800) { store.save(it) } }
    val importer = remember { MediaImporter(context.applicationContext) }
    val io = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(projectId) {
        ActiveEditor.flush = { autosaver.flushNow() }
        onDispose {
            autosaver.flushNow()
            ActiveEditor.flush = null
            io.shutdown()
        }
    }
    BackHandler { autosaver.flushNow(); onBack() }

    fun publish(p: Project) {
        val stamped = p.copy(modifiedAtMs = System.currentTimeMillis())
        project = stamped
        autosaver.onChanged(stamped)
    }

    fun commit(edit: Edit) {
        when (edit) {
            is Edit.Ok -> {
                val stamped = edit.project.copy(modifiedAtMs = System.currentTimeMillis())
                history.apply(stamped)
                project = history.current
                selectedId = edit.selectedClipId
                message = ""
                autosaver.onChanged(history.current)
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
                    val next = history.current.copy(sources = history.current.sources + r.sources, modifiedAtMs = System.currentTimeMillis())
                    history.apply(next)
                    project = history.current
                    autosaver.onChanged(history.current)
                }
                message = if (r.failures.isEmpty()) "${r.sources.size} media diimpor." else "Gagal: " + r.failures.joinToString("; ")
            }
        }
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importFrom(it, MediaType.VIDEO) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importFrom(it, MediaType.IMAGE) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importFrom(it, MediaType.AUDIO) }

    val selected = selectedId?.let { project.clip(it) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { autosaver.flushNow(); onBack() }) { Text("< Kembali") }
            Text(project.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(enabled = history.canUndo, onClick = { history.undo()?.let { project = it; selectedId = null; autosaver.onChanged(it) } }) { Text("Undo") }
            TextButton(enabled = history.canRedo, onClick = { history.redo()?.let { project = it; selectedId = null; autosaver.onChanged(it) } }) { Text("Redo") }
        }

        // Preview belum ada (sub-tahap 1.3). Area ini menampilkan informasi nyata, bukan gambar palsu.
        Box(
            Modifier.fillMaxWidth().height(120.dp).border(1.dp, MaterialTheme.colorScheme.outline),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Preview video: belum tersedia (sub-tahap berikutnya)", style = MaterialTheme.typography.bodySmall)
                Text("Playhead ${formatTime(playheadUs)} / ${formatTime(project.durationUs)}", style = MaterialTheme.typography.titleSmall)
                if (selected != null) {
                    val src = project.source(selected.sourceId)
                    Text(
                        "Terpilih: ${src?.displayName} | ${formatTime(selected.durationUs)} | rotasi ${selected.rotationDeg}" +
                            (selected.crop?.let { " | crop" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { playheadUs = 0 }) { Text("|<") }
            OutlinedButton(onClick = { playheadUs = (playheadUs - US_PER_SECOND).coerceAtLeast(0) }) { Text("-1s") }
            OutlinedButton(onClick = { playheadUs = (playheadUs + US_PER_SECOND).coerceAtMost(project.durationUs) }) { Text("+1s") }
            OutlinedButton(onClick = { playheadUs = project.durationUs }) { Text(">|") }
            OutlinedButton(onClick = { dpPerSecond = (dpPerSecond * 0.7f).coerceAtLeast(10f) }) { Text("Zoom -") }
            OutlinedButton(onClick = { dpPerSecond = (dpPerSecond * 1.4f).coerceAtMost(400f) }) { Text("Zoom +") }
        }

        Timeline(
            project = project, selectedClipId = selectedId, playheadUs = playheadUs, dpPerSecond = dpPerSecond,
            onSelect = { selectedId = it }, onSeek = { playheadUs = it },
        )

        // Alat edit untuk klip terpilih
        Text("Alat edit", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val id = selectedId
            val enabled = id != null && selected != null
            Button(enabled = enabled, onClick = { commit(TimelineEngine.split(project, id!!, playheadUs, newId())) }) { Text("Split") }
            Button(enabled = enabled, onClick = { commit(TimelineEngine.trimStart(project, id!!, playheadUs)) }) { Text("Trim awal") }
            Button(enabled = enabled, onClick = { commit(TimelineEngine.trimEnd(project, id!!, playheadUs)) }) { Text("Trim akhir") }
            Button(enabled = enabled, onClick = { commit(TimelineEngine.duplicate(project, id!!, newId())) }) { Text("Duplikat") }
            Button(enabled = enabled, onClick = { commit(TimelineEngine.delete(project, id!!, ripple = false)) }) { Text("Hapus") }
            Button(enabled = enabled, onClick = { commit(TimelineEngine.delete(project, id!!, ripple = true)) }) { Text("Hapus+rapatkan") }
            Button(enabled = enabled, onClick = {
                val order = project.trackOf(id!!)!!.clips.sortedBy { it.startUs }
                commit(TimelineEngine.reorder(project, id, order.indexOfFirst { it.id == id } - 1))
            }) { Text("Geser kiri") }
            Button(enabled = enabled, onClick = {
                val order = project.trackOf(id!!)!!.clips.sortedBy { it.startUs }
                commit(TimelineEngine.reorder(project, id, order.indexOfFirst { it.id == id } + 1))
            }) { Text("Geser kanan") }
            Button(enabled = enabled, onClick = { commit(TimelineEngine.setRotation(project, id!!, selected!!.rotationDeg + 90)) }) { Text("Rotasi 90") }
            Button(
                enabled = enabled && project.source(selected!!.sourceId)?.type != MediaType.AUDIO,
                onClick = {
                    cropIndex = (cropIndex + 1) % CROP_PRESETS.size
                    val (_, aspect) = CROP_PRESETS[cropIndex]
                    val src = project.source(selected!!.sourceId)!!
                    val srcAspect = if (src.height > 0) src.width.toFloat() / src.height else 1f
                    commit(TimelineEngine.setCrop(project, id!!, aspect?.let { Crop.centered(it, srcAspect) }))
                },
            ) { Text("Crop: ${CROP_PRESETS[cropIndex].first}") }
        }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

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
                    commit(TimelineEngine.addClip(project, track, src.id, newId()))
                }) { Text("Ke timeline") }
            }
        }
    }
}
