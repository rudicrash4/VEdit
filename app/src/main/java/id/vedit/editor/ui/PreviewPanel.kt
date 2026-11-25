package id.vedit.editor.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import id.vedit.editor.domain.formatTime
import id.vedit.editor.preview.PreviewController

/** Panel preview nyata: SurfaceView 16:9 yang dirender pemutar Media3, kontrol putar/jeda, status muat/galat. */
@Composable
fun PreviewPanel(controller: PreviewController, playheadUs: Long, durationUs: Long, maxVideoHeight: Dp, aspect: Float = 16f / 9f) {
    val ui = controller.ui
    Column(Modifier.fillMaxWidth()) {
        // Lebar video dibatasi tinggi maksimum agar tidak memenuhi layar pada landscape; tetap 16:9 dan di tengah.
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val w = minOf(maxWidth, maxVideoHeight * aspect)
        Box(Modifier.width(w).aspectRatio(aspect).background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(factory = { ctx -> SurfaceView(ctx).also { controller.attachSurface(it) } }, modifier = Modifier.matchParentSize())
            when {
                !ui.hasContent -> Text("Tambahkan media ke timeline untuk melihat preview", color = Color.White, style = MaterialTheme.typography.bodySmall)
                ui.error.isNotEmpty() -> Text("Preview gagal: ${ui.error}", color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                ui.loading -> CircularProgressIndicator(color = Color.White)
            }
        }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Button(
                enabled = ui.hasContent && ui.ready,
                onClick = { if (ui.isPlaying) controller.pause() else controller.play() },
            ) { Text(if (ui.isPlaying) "Jeda" else "Putar") }
            Text(
                "  ${formatTime(playheadUs)} / ${formatTime(durationUs)}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (ui.engineName.isNotEmpty()) Text(
                ui.engineName + if (ui.lastBuildMs >= 0) " | siap ${ui.lastBuildMs} ms (#${ui.buildCount})" else "",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (ui.notice.isNotEmpty()) Text(ui.notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
