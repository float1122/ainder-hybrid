package app.ainderhybrid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ainderhybrid.hybrid.Privacy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every AI call: task, where it ran, how long, how many characters left the phone. */
@Composable
fun LogScreen(vm: AppViewModel) {
    val entries by vm.log.collectAsState()
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    val privateCloud = entries.filter { it.task.privacy == Privacy.PRIVATE }.sumOf { it.cloudChars }
    val publicCloud = entries.filter { it.task.privacy == Privacy.PUBLIC }.sumOf { it.cloudChars }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Hybrid log", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Characters sent to the cloud: private tasks $privateCloud · public tasks $publicCloud", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("log-totals"))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(entries) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${fmt.format(Date(e.atMillis))}  ${e.task.label}", style = MaterialTheme.typography.bodyMedium)
                        Text("${e.ms} ms · ${e.cloudChars} chars to cloud${if (e.note.isNotBlank()) " · ${e.note}" else ""}", style = MaterialTheme.typography.labelSmall)
                    }
                    SourceBadge(e.source)
                }
                HorizontalDivider()
            }
        }
    }
}
