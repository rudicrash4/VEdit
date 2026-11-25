package id.vedit.editor

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MimeTypes
import id.vedit.editor.data.ProjectStore
import id.vedit.editor.poc.PocScreen
import id.vedit.editor.ui.ActiveEditor
import id.vedit.editor.ui.EditorScreen
import id.vedit.editor.ui.HomeScreen
import java.io.File

private sealed interface Screen {
    data object Home : Screen
    data class Editor(val projectId: String) : Screen
    data object Tools : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB6F36B), onPrimary = Color(0xFF17200D), secondary = Color(0xFF8ED8FF), background = Color(0xFF090B10), surface = Color(0xFF12161E), surfaceVariant = Color(0xFF202632))) {
                Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().safeDrawingPadding()) { Root() } }
            }
        }
    }

    override fun onStop() {
        ActiveEditor.pause?.invoke()
        ActiveEditor.flush?.invoke()
        super.onStop()
    }
}

@Composable
private fun Root() {
    val context = LocalContext.current
    val store = remember { ProjectStore(File(context.filesDir, "projects")) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    when (val s = screen) {
        Screen.Home -> HomeScreen(store, onOpen = { screen = Screen.Editor(it) }, onTools = { screen = Screen.Tools })
        is Screen.Editor -> EditorScreen(store, s.projectId, onBack = { screen = Screen.Home })
        Screen.Tools -> {
            BackHandler { screen = Screen.Home }
            var tab by remember { mutableStateOf(1) }
            Column {
                Row(Modifier.padding(8.dp)) {
                    Button(onClick = { screen = Screen.Home }) { Text("< Beranda") }
                    Button(onClick = { tab = 0 }, modifier = Modifier.padding(start = 8.dp)) { Text("Diagnostik") }
                    Button(onClick = { tab = 1 }, modifier = Modifier.padding(start = 8.dp)) { Text("PoC 0B") }
                }
                if (tab == 0) Phase0Screen() else PocScreen()
            }
        }
    }
}

/** Layar diagnostik perangkat (Phase 0): data nyata encoder/decoder. */
@Composable
private fun Phase0Screen() {
    val h264Enc = CodecInfo.list(MimeTypes.VIDEO_H264, encoder = true)
    val aacEnc = CodecInfo.list(MimeTypes.AUDIO_AAC, encoder = true)
    val h264Dec = CodecInfo.list(MimeTypes.VIDEO_H264, encoder = false)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("VEdit ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
        Text("Android API ${Build.VERSION.SDK_INT} (${Build.MODEL})")
        Section("Encoder H.264", h264Enc)
        Section("Encoder AAC", aacEnc)
        Section("Decoder H.264", h264Dec)
    }
}

@Composable
private fun Section(title: String, items: List<CodecSummary>) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    if (items.isEmpty()) Text("Tidak ditemukan")
    items.forEach {
        Text("${it.name} | ${when (it.isHardware) { true -> "hardware"; false -> "software"; null -> "tidak diketahui" }} | maks instance: ${it.maxInstances ?: "?"}")
    }
}
