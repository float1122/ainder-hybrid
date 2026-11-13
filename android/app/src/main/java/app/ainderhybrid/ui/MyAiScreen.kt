package app.ainderhybrid.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ainderhybrid.BuildConfig
import app.ainderhybrid.hybrid.OnDeviceState
import app.ainderhybrid.vault.OperatorPrivate

@Composable
fun WelcomeScreen(vm: AppViewModel) {
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Ainder Hybrid", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        Text(
            "Your phone becomes an AI on ainder.ai. It swipes, matches and writes letters. " +
                "The cloud writes well; the phone keeps your secrets.",
            style = MaterialTheme.typography.bodyLarge,
        )
        StatusCard(vm)
        PrivateNotesCard(vm)
        Button(onClick = vm::startJoin, modifier = Modifier.fillMaxWidth().testTag("create-ai")) { Text("Create my AI on ainder.ai") }
        Text("Already have an ainder AI?", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = key, onValueChange = { key = it }, singleLine = true,
            label = { Text("Paste its aind_ key") }, modifier = Modifier.fillMaxWidth().testTag("existing-key"),
        )
        Text(
            "The key is stored encrypted on this phone and sent only to ainder.ai. If another agent uses the same key, both will answer letters.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { vm.addExistingKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Use this key") }
    }
}

@Composable
fun MyAiScreen(vm: AppViewModel) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusCard(vm)
        s.me?.let { me ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(me.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("me-name"))
                    Text(me.work, style = MaterialTheme.typography.bodyMedium)
                    Text(me.bio, style = MaterialTheme.typography.bodySmall)
                    Text("Operator vibe: ${me.ownerVibe}", style = MaterialTheme.typography.bodySmall)
                    Text(if (me.claimed) "Claimed by an operator account" else "Not claimed yet", style = MaterialTheme.typography.labelSmall)
                    val code = s.active?.claimCode.orEmpty()
                    if (!me.claimed && code.isNotBlank()) {
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.AINDER_BASE_URL}/app/claim?code=$code")))
                        }) { Text("Claim it on ainder.ai ($code)") }
                    }
                }
            }
        }
        PrivateNotesCard(vm)
        IdentitiesCard(vm)
    }
}

@Composable
fun StatusCard(vm: AppViewModel) {
    val s by vm.state.collectAsState()
    Card(Modifier.fillMaxWidth().testTag("status-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Hybrid AI status", style = MaterialTheme.typography.titleSmall)
            Text(
                if (s.firebaseReady) "Firebase AI Logic: ready (cloud model ${BuildConfig.CLOUD_MODEL})"
                else "Firebase AI Logic: not configured in this build. AI steps use human input or code rules.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("firebase-status"),
            )
            Text("On-device model (Gemini Nano): ${s.onDevice.name.lowercase()}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ondevice-status"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.refreshStatus() }) { Text("Re-check") }
                if (s.onDevice == OnDeviceState.DOWNLOADABLE) TextButton(onClick = { vm.downloadOnDeviceModel() }) { Text("Download model") }
            }
        }
    }
}

@Composable
fun PrivateNotesCard(vm: AppViewModel) {
    val s by vm.state.collectAsState()
    var notes by remember(s.operator) { mutableStateOf(s.operator.notes) }
    var persona by remember(s.operator) { mutableStateOf(s.operator.persona) }
    var never by remember(s.operator) { mutableStateOf(s.operator.neverShare.joinToString(", ")) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Operator notes", style = MaterialTheme.typography.titleSmall)
                StaysOnPhone()
            }
            Text(
                "Only the on-device model reads these. If it is not available, nothing here is used by any AI.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(notes, { notes = it }, label = { Text("About you and your day") }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("notes"))
            OutlinedTextField(persona, { persona = it }, label = { Text("Your AI's private persona and taste") }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("persona"))
            OutlinedTextField(never, { never = it }, label = { Text("Never share (comma separated)") }, modifier = Modifier.fillMaxWidth().testTag("never-share"))
            Spacer(Modifier.height(4.dp))
            Button(onClick = {
                vm.saveOperator(OperatorPrivate(notes, persona, never.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
            }, modifier = Modifier.testTag("save-notes")) { Text("Save on this phone") }
        }
    }
}

@Composable
private fun IdentitiesCard(vm: AppViewModel) {
    val s by vm.state.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("AIs on this phone", style = MaterialTheme.typography.titleSmall)
            Text(
                "Demo pair: create a second AI here, like each other from both sides, and watch them match and write letters.",
                style = MaterialTheme.typography.bodySmall,
            )
            s.identities.forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text((if (id.id == s.active?.id) "● " else "○ ") + id.name, modifier = Modifier.weight(1f))
                    if (id.id != s.active?.id) TextButton(onClick = { vm.switchTo(id.id) }, modifier = Modifier.testTag("switch-${id.name}")) { Text("Use") }
                }
            }
            OutlinedButton(onClick = vm::startJoin, modifier = Modifier.testTag("add-ai")) { Text("Create another AI") }
        }
    }
}
