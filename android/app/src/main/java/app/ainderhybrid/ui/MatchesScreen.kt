package app.ainderhybrid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ainderhybrid.ainder.Letter

@Composable
fun MatchesScreen(vm: AppViewModel) {
    val s by vm.state.collectAsState()
    val thread = s.thread
    if (thread != null) return LettersScreen(vm, thread)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Matches", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::loadMatches, modifier = Modifier.testTag("matches-reload")) { Text("Reload") }
        }
        if (s.matches.isEmpty() && !s.busy) Text("No matches yet. Like someone in the deck.", modifier = Modifier.testTag("matches-empty"))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.matches, key = { it.id }) { m ->
                Card(Modifier.fillMaxWidth().clickable { vm.openThread(m) }.testTag("match-${m.partner.name}")) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.partner.name, style = MaterialTheme.typography.titleMedium)
                            Text("Affinity ${m.affinity} · ${m.status}${if (m.meetupRequested) " · meetup requested" else ""}", style = MaterialTheme.typography.labelSmall)
                        }
                        if (m.unread > 0) Text("${m.unread} new", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LettersScreen(vm: AppViewModel, t: ThreadState) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::closeThread, modifier = Modifier.testTag("thread-back")) { Text("< Back") }
                Text(t.match.partner.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("Affinity ${t.match.affinity}", style = MaterialTheme.typography.labelMedium)
            }
        }
        items(t.letters, key = { it.id }) { LetterBubble(it) }
        item {
            t.memory?.let { m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiResultRow("Private memory", m.source, m.value, Modifier.weight(1f))
                    StaysOnPhone()
                }
            }
        }
        item { Composer(vm, t) }
    }
}

@Composable
private fun LetterBubble(l: Letter) {
    val mine = !l.fromPartner
    Column(
        Modifier.fillMaxWidth()
            .background(
                if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp)
            .testTag(if (mine) "letter-mine" else "letter-partner"),
    ) {
        Text(if (mine) "You" else "Them (untrusted)", style = MaterialTheme.typography.labelSmall)
        Text(l.body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Composer(vm: AppViewModel, t: ThreadState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        OutlinedButton(onClick = vm::composeWithAi, modifier = Modifier.testTag("compose-ai")) { Text("Draft a letter with AI") }
        t.composed?.let { c ->
            AiResultRow("Safe brief", c.brief.source, c.brief.value.ifBlank { c.brief.note })
            c.draft?.let { d -> AiResultRow("Letter draft (affinity ${d.value.affinityDelta})", d.source, d.value.reason) }
            c.guard?.let { g ->
                AiResultRow(
                    if (g.value.ok) "Privacy guard: OK" else "Privacy guard: blocked",
                    g.source,
                    (g.value.leaks + listOfNotNull(if (c.rewrites > 0) "Rewritten ${c.rewrites}x after a guard hit" else null)).joinToString("\n"),
                )
            }
        }
        OutlinedTextField(
            value = t.draftText, onValueChange = vm::editDraft, minLines = 4,
            label = { Text("Letter (you can edit it)") }, modifier = Modifier.fillMaxWidth().testTag("letter-text"),
        )
        Button(onClick = vm::sendDraft, enabled = t.draftText.isNotBlank(), modifier = Modifier.testTag("letter-send")) { Text("Send") }
    }
}
