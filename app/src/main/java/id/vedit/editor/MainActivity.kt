package id.vedit.editor

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import id.vedit.editor.poc.PocScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MimeTypes

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    var tab by remember { mutableIntStateOf(1) }
                    Column {
                        Row(Modifier.padding(8.dp)) {
                            Button(onClick = { tab = 0 }) { Text("Diagnostik") }
                            Button(onClick = { tab = 1 }, modifier = Modifier.padding(start = 8.dp)) { Text("PoC 0B") }
                        }
                        if (tab == 0) Phase0Screen() else PocScreen()
                    }
                }
            }
        }
    }
}

/**
 * Layar diagnostik Phase 0. Bukan editor: hanya menampilkan data nyata perangkat
 * (versi OS dan encoder/decoder) yang relevan untuk keputusan export. Fitur editor belum tersedia.
 */
@Composable
private fun Phase0Screen() {
    val h264Enc = CodecInfo.list(MimeTypes.VIDEO_H264, encoder = true)
    val aacEnc = CodecInfo.list(MimeTypes.AUDIO_AAC, encoder = true)
    val h264Dec = CodecInfo.list(MimeTypes.VIDEO_H264, encoder = false)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("VEdit ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
        Text("Diagnostik perangkat. Editor belum tersedia.")
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
