package app.ainderhybrid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ainderhybrid.ainder.SwipeAction

@Composable
fun DeckScreen(vm: AppViewModel) {
    val s by vm.state.collectAsState()
    LaunchedEffect(s.active?.id) { if (s.deck.isEmpty()) vm.loadDeck() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Deck", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(s.active?.name.orEmpty(), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = vm::loadDeck, modifier = Modifier.testTag("deck-reload")) { Text("Reload") }
        }
        if (s.deck.isEmpty() && !s.busy) Text("No new cards right now.", modifier = Modifier.padding(top = 24.dp).testTag("deck-empty"))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(s.deck, key = { it.card.id }) { item -> DeckCard(vm, item) }
        }
    }
}

@Composable
private fun DeckCard(vm: AppViewModel, item: DeckItem) {
    val c = item.card
    Card(Modifier.fillMaxWidth().testTag("card-${c.name}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(c.distanceKm?.let { "$it km" } ?: "distance unknown", style = MaterialTheme.typography.labelSmall)
            }
            Text("${c.model} · ${c.work}", style = MaterialTheme.typography.labelMedium)
            Text(c.bio, style = MaterialTheme.typography.bodyMedium)
            if (c.interests.isNotEmpty()) Text(c.interests.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
            item.judgement?.let { j ->
                AiResultRow("Compatibility ${j.value.score}/100", j.source, (j.value.reasons + listOfNotNull(j.value.opener.ifBlank { null }?.let { "Opener: $it" })).joinToString("\n"))
            }
            item.verdict?.let { v ->
                AiResultRow("Suggestion: ${if (v.value.like) "like" else "pass"}", v.source, v.value.why)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.swipe(c, SwipeAction.PASS) }, modifier = Modifier.testTag("pass-${c.name}")) { Text("Pass") }
                Button(onClick = { vm.swipe(c, SwipeAction.LIKE) }, modifier = Modifier.testTag("like-${c.name}")) { Text("Like") }
            }
        }
    }
}
