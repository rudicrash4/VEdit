package id.vedit.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.vedit.editor.data.ProjectStore
import id.vedit.editor.domain.Project
import id.vedit.editor.domain.formatTime
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
fun HomeScreen(store: ProjectStore, onOpen: (String) -> Unit, onTools: () -> Unit) {
    var projects by remember { mutableStateOf(store.list()) }
    var toDelete by remember { mutableStateOf<Project?>(null) }
    Column(Modifier.fillMaxSize().background(Color(0xFF090B10)).padding(horizontal = 18.dp, vertical = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("VEDIT STUDIO", style = MaterialTheme.typography.labelLarge, color = Color(0xFF9CA3AF))
                Text("Karya kamu", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            }
            TextButton(onClick = onTools) { Text("Diagnostik") }
        }
        Text("Mulai mengedit", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF171B24))) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Proyek video baru", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("Gabungkan video, foto, dan audio.", color = Color(0xFF9CA3AF), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                Button(onClick = {
                    val p = Project.create(UUID.randomUUID().toString(), "Proyek ${projects.size + 1}", System.currentTimeMillis())
                    store.save(p)
                    onOpen(p.id)
                }, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB6F36B), contentColor = Color(0xFF17200D))) { Text("+ Buat") }
            }
        }
        Text("Proyek terbaru", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Belum ada proyek. Buat proyek pertamamu.", color = Color(0xFF9CA3AF)) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(projects, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(p.id) }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF171B24))) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.background(Color(0xFF292F3B), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) { Text("▶", color = Color(0xFFB6F36B)) }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(p.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                Text("${formatTime(p.durationUs)}  •  ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.modifiedAtMs))}", color = Color(0xFF9CA3AF), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { toDelete = p }) { Text("Hapus", color = Color(0xFFFF8A80)) }
                        }
                    }
                }
            }
        }
    }
    toDelete?.let { p ->
        AlertDialog(onDismissRequest = { toDelete = null }, title = { Text("Hapus proyek?") }, text = { Text("\"${p.name}\" akan dihapus dari aplikasi. File media asli tidak disentuh.") },
            confirmButton = { TextButton(onClick = { store.delete(p.id); projects = store.list(); toDelete = null }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Batal") } })
    }
}
