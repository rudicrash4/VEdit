package id.vedit.editor.ui

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import id.vedit.editor.preview.v2.PreviewLog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import id.vedit.editor.domain.formatTime
import id.vedit.editor.preview.PreviewController

@Composable
fun PreviewPanel(controller: PreviewController, playheadUs: Long, durationUs: Long, maxVideoHeight: Dp, aspect: Float = 16f / 9f) {
    val ui = controller.ui
    var showLog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(controller) {
        while (true) {
            controller.refreshStats()
            if (showLog) logText = PreviewLog.lastLines(14).joinToString("\n")
            delay(500)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val w = minOf(maxWidth, maxVideoHeight * aspect)
            Box(Modifier.width(w).aspectRatio(aspect).clip(RoundedCornerShape(18.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { ctx -> FrameLayout(ctx).also { controller.attachHost(it) } },
                    modifier = Modifier.matchParentSize(),
                    onRelease = { controller.detachHost(it) },
                )
                when {
                    !ui.hasContent -> Text("Impor media lalu tambahkan ke timeline", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.bodySmall)
                    ui.error.isNotEmpty() -> Text("Preview gagal: ${ui.error}", color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
                    ui.loading -> CircularProgressIndicator(color = Color(0xFFB6F36B))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = ui.hasContent && ui.ready,
                onClick = { if (ui.isPlaying) controller.pause() else controller.play() },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB6F36B), contentColor = Color(0xFF17200D)),
            ) { Text(if (ui.isPlaying) "Ⅱ" else "▶", style = MaterialTheme.typography.titleMedium) }
            Text("${formatTime(playheadUs)}  /  ${formatTime(durationUs)}", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 12.dp))
            if (ui.engineName.isNotEmpty()) Text(ui.engineName + if (ui.lastBuildMs >= 0) " · ${ui.lastBuildMs} ms" else "", color = Color(0xFF7F8999), style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { controller.cycleEngine() }) { Text("Mesin: ${ui.preferred.label}", style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Sembunyikan log" else "Log", style = MaterialTheme.typography.labelMedium) }
            if (showLog) TextButton(onClick = { clipboard.setText(AnnotatedString(PreviewLog.dump() + "\n" + ui.stats)) }) { Text("Salin", style = MaterialTheme.typography.labelMedium) }
        }
        if (ui.stats.isNotEmpty()) Text(ui.stats, color = Color(0xFF7F8999), style = MaterialTheme.typography.labelSmall)
        if (showLog) Text(logText, color = Color(0xFFB0B8C4), style = MaterialTheme.typography.labelSmall)
        if (ui.notice.isNotEmpty()) Text(ui.notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
