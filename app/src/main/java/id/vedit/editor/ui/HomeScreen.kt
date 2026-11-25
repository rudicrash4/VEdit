package id.vedit.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("VEdit", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val p = Project.create(UUID.randomUUID().toString(), "Proyek ${projects.size + 1}", System.currentTimeMillis())
                store.save(p)
                onOpen(p.id)
            }) { Text("Proyek baru") }
            OutlinedButton(onClick = onTools) { Text("Alat PoC / Diagnostik") }
        }
        if (projects.isEmpty()) Text("Belum ada proyek.")
        projects.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatTime(p.durationUs)} | ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.modifiedAtMs))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { toDelete = p }) { Text("Hapus") }
            }
        }
    }
    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Hapus proyek?") },
            text = { Text("\"${p.name}\" akan dihapus dari aplikasi. File media asli tidak disentuh.") },
            confirmButton = {
                TextButton(onClick = { store.delete(p.id); projects = store.list(); toDelete = null }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Batal") } },
        )
    }
}
