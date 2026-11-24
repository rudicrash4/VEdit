package id.vedit.editor.poc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import kotlinx.coroutines.delay

private class Picked(val uri: Uri, val durationMs: Long)

private fun durationOf(context: Context, uri: Uri): Long = try {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally {
        r.release()
    }
} catch (_: Throwable) {
    0L
}

/** Layar PoC Phase 0B: semua hasil berasal dari export/preview nyata di perangkat. */
@Composable
fun PocScreen() {
    val context = LocalContext.current
    var v1 by remember { mutableStateOf<Picked?>(null) }
    var v2 by remember { mutableStateOf<Picked?>(null) }
    var audio by remember { mutableStateOf<Picked?>(null) }
    var results by remember { mutableStateOf<List<ExperimentResult>>(emptyList()) }
    var runner by remember { mutableStateOf<PocRunner?>(null) }
    var running by remember { mutableStateOf(false) }
    var previewId by remember { mutableStateOf<String?>(null) }
    val experiments = remember(v1, v2, audio) {
        v1?.takeIf { it.durationMs > 0 }?.let { a ->
            val b = v2 ?: a
            Experiments.build(context, PocInputs(a.uri, a.durationMs, b.uri, b.durationMs, audio?.uri, audio?.durationMs ?: 0L))
        }
    }

    val pick1 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) v1 = Picked(u, durationOf(context, u))
    }
    val pick2 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) v2 = Picked(u, durationOf(context, u))
    }
    val pickA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) audio = Picked(u, durationOf(context, u))
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Phase 0B: bukti teknis Media3", style = MaterialTheme.typography.headlineSmall)
        Text("Pilih video (disarankan berisi audio, beberapa detik). Setiap eksperimen mengekspor MP4 sungguhan lalu memeriksanya. Klip dipotong maksimal ${CLIP_MS / 1000} dtk.")
        Row(Modifier.padding(top = 8.dp)) {
            Button(onClick = { pick1.launch(arrayOf("video/*")) }) { Text("Video 1") }
            Button(onClick = { pick2.launch(arrayOf("video/*")) }, modifier = Modifier.padding(start = 8.dp)) { Text("Video 2") }
            Button(onClick = { pickA.launch(arrayOf("audio/*")) }, modifier = Modifier.padding(start = 8.dp)) { Text("Audio") }
        }
        Text("Video 1: ${v1?.let { "${it.durationMs} ms" } ?: "belum dipilih"}")
        Text("Video 2: ${v2?.let { "${it.durationMs} ms" } ?: "belum dipilih (memakai Video 1)"}")
        Text("Audio: ${audio?.let { "${it.durationMs} ms" } ?: "belum dipilih (eksperimen C dilewati)"}")
        Row(Modifier.padding(top = 8.dp)) {
            Button(
                enabled = v1 != null && v1!!.durationMs > 0 && !running,
                onClick = {
                    val a = v1!!
                    val b = v2 ?: a
                    val inputs = PocInputs(a.uri, a.durationMs, b.uri, b.durationMs, audio?.uri, audio?.durationMs ?: 0L)
                    running = true
                    val r = PocRunner(context, Experiments.build(context, inputs)) { list ->
                        results = list
                        if (list.none { it.status == Status.PENDING || it.status == Status.RUNNING }) running = false
                    }
                    runner = r
                    r.start()
                },
            ) { Text(if (running) "Berjalan..." else "Jalankan semua") }
            Button(
                enabled = results.isNotEmpty(),
                modifier = Modifier.padding(start = 8.dp),
                onClick = {
                    val text = runner?.reportText().orEmpty()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("VEdit Phase 0B", text))
                },
            ) { Text("Salin laporan") }
        }
        if (experiments != null) {
            Text("Preview manual (CompositionPlayer + Surface)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Text("Putar komposisi yang sama dengan yang diekspor, lalu bandingkan dengan hasil export. Nilai sendiri: dua lapisan, sinkron, tidak ada lompatan.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 4.dp)) {
                listOf("seq" to "A", "pip" to "PiP", "ramp" to "Ramp", "audio_mix" to "Mix").forEach { (id, label) ->
                    if (experiments.any { it.id == id }) {
                        Button(onClick = { previewId = id }, modifier = Modifier.padding(end = 8.dp)) { Text(label) }
                    }
                }
            }
            val sel = experiments.firstOrNull { it.id == previewId }
            if (sel != null) PreviewPanel(sel) { previewId = null }
        }
        results.forEach { ResultCard(it) }
    }
}

@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
private fun PreviewPanel(experiment: Experiment, onClose: () -> Unit) {
    val context = LocalContext.current
    var info by remember(experiment.id) { mutableStateOf("menyiapkan...") }
    val player = remember(experiment.id) { CompositionPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                info = "Error ${error.errorCodeName}: ${error.message}"
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        try {
            player.setComposition(experiment.build())
            player.prepare()
            player.play()
            while (true) {
                info = "state=${player.playbackState} posisi=${player.currentPosition} ms / durasi=${player.duration} ms"
                delay(250)
            }
        } catch (t: Throwable) {
            info = "Gagal: ${Analysis.describeThrowable(t)}"
        }
    }
    Column(Modifier.padding(top = 8.dp)) {
        Text(experiment.title, style = MaterialTheme.typography.titleSmall)
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
            modifier = Modifier.fillMaxWidth().height(220.dp),
        )
        Text(info, style = MaterialTheme.typography.bodySmall)
        Row {
            Button(onClick = { player.seekTo(0); player.play() }) { Text("Ulangi") }
            Button(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.padding(start = 8.dp)) { Text("Jeda/Putar") }
            Button(onClick = onClose, modifier = Modifier.padding(start = 8.dp)) { Text("Tutup") }
        }
    }
}

@Composable
private fun ResultCard(r: ExperimentResult) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("[${r.status}] ${r.title}", style = MaterialTheme.typography.titleSmall)
        Text(r.what, style = MaterialTheme.typography.bodySmall)
        if (r.expectedMs != null || r.actualMs != null) Text("Diharapkan: ${r.expectedMs ?: "-"} ms | Hasil: ${r.actualMs ?: "-"} ms")
        if (r.details.isNotEmpty()) Text(r.details, style = MaterialTheme.typography.bodySmall)
        r.thumbnail?.let { Image(it.asImageBitmap(), contentDescription = "Frame hasil ${r.id}", modifier = Modifier.width(240.dp).padding(top = 4.dp)) }
    }
}
