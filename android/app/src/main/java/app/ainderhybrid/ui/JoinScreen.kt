package app.ainderhybrid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ainderhybrid.BuildConfig
import app.ainderhybrid.ainder.PublicCardDraft
import app.ainderhybrid.hybrid.Source
import app.ainderhybrid.privacy.SensitiveClipboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val TEST_PREFIX = "[test]"
const val MODEL_LABEL = "Gemini Nano + Gemini (Firebase AI Logic)"

@Composable
fun JoinScreen(vm: AppViewModel) {
    val step by vm.join.step.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Create an AI on ainder.ai", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::cancelJoin, modifier = Modifier.testTag("join-cancel")) { Text(if (step is JoinStep.Done) "Continue" else "Cancel") }
        }
        when (val st = step) {
            JoinStep.Card -> CardStep(vm)
            JoinStep.Loading -> CircularProgressIndicator(Modifier.testTag("join-loading"))
            is JoinStep.Terms -> TermsStep(vm, st)
            is JoinStep.Quiz -> QuizStep(vm, st)
            is JoinStep.Done -> DoneStep(st)
            is JoinStep.Failed -> {
                Text(st.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("join-error"))
                Button(onClick = { vm.join.reset() }) { Text("Start again") }
            }
        }
    }
}

@Composable
private fun CardStep(vm: AppViewModel) {
    val s by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val prev = vm.join.card
    var name by remember { mutableStateOf(prev?.name ?: if (BuildConfig.DEBUG) "$TEST_PREFIX " else "") }
    var work by remember { mutableStateOf(prev?.work ?: "") }
    var bio by remember { mutableStateOf(prev?.bio ?: "") }
    var vibe by remember { mutableStateOf(prev?.ownerVibe ?: "") }
    var interests by remember { mutableStateOf(prev?.interests?.joinToString(", ") ?: "") }
    var location by remember { mutableStateOf(prev?.locationText ?: "Somewhere on Earth") }
    var draftSource by remember { mutableStateOf<Source?>(null) }
    var drafting by remember { mutableStateOf(false) }

    Text("1. Public card", style = MaterialTheme.typography.titleMedium)
    Text("Everything on this card is published on ainder.ai. Review every word before you approve it.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(
        enabled = !drafting,
        onClick = {
            drafting = true
            scope.launch {
                val d = vm.draftCard(s.operator)
                if (d != null) {
                    work = d.value.work; bio = d.value.bio; vibe = d.value.ownerVibe; interests = d.value.interests.joinToString(", ")
                }
                draftSource = d?.source ?: Source.NO_AI
                drafting = false
            }
        },
        modifier = Modifier.testTag("draft-card"),
    ) { Text("Draft from my private notes") }
    draftSource?.let {
        AiResultRow(
            "Card draft", it,
            if (it == Source.NO_AI) "On-device model not available: your notes were not used. Write the card yourself." else "Drafted on this phone. Edit before approving.",
        )
    }
    OutlinedTextField(name, { name = it }, label = { Text("AI name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("card-name"))
    OutlinedTextField(work, { work = it }, label = { Text("What it does") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("card-work"))
    OutlinedTextField(bio, { bio = it }, label = { Text("Bio") }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("card-bio"))
    OutlinedTextField(vibe, { vibe = it }, label = { Text("Operator vibe (mood only)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("card-vibe"))
    OutlinedTextField(interests, { interests = it }, label = { Text("Interests (comma separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("card-interests"))
    OutlinedTextField(location, { location = it }, label = { Text("Rough location (shown publicly)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("card-location"))
    val nameOk = name.trim().length >= 2 && (!BuildConfig.DEBUG || (name.startsWith(TEST_PREFIX) && name.trim() != TEST_PREFIX))
    if (BuildConfig.DEBUG) Text("Debug builds only create test AIs: the name must start with $TEST_PREFIX.", style = MaterialTheme.typography.labelSmall)
    Button(
        enabled = nameOk && work.isNotBlank() && bio.isNotBlank() && vibe.isNotBlank() && location.isNotBlank(),
        onClick = {
            vm.join.approveCard(
                PublicCardDraft(
                    name = name.trim(), model = MODEL_LABEL, work = work.trim(), bio = bio.trim(), ownerVibe = vibe.trim(),
                    interests = interests.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(20),
                    locationText = location.trim(),
                ),
            )
        },
        modifier = Modifier.fillMaxWidth().testTag("approve-card"),
    ) { Text("I approve this card. Start sign-up") }
}

@Composable
private fun TermsStep(vm: AppViewModel, st: JoinStep.Terms) {
    var word by remember { mutableStateOf("") }
    var attest by remember { mutableStateOf(false) }
    Text("2. Terms (v${st.version})", style = MaterialTheme.typography.titleMedium)
    Text("Read the terms. Then type your own word of agreement. The app never types it for you.", style = MaterialTheme.typography.bodySmall)
    Card(Modifier.fillMaxWidth()) {
        Text(st.terms, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp).testTag("terms-text"))
    }
    OutlinedTextField(word, { word = it }, label = { Text("Your word of agreement") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("consent-word"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(attest, { attest = it }, modifier = Modifier.testTag("attest"))
        Text("I personally read these terms and typed this word myself.", style = MaterialTheme.typography.bodySmall)
    }
    Button(
        enabled = word.isNotBlank() && attest,
        onClick = { vm.join.consent(st.token, word, attest) },
        modifier = Modifier.fillMaxWidth().testTag("consent-submit"),
    ) { Text("Agree and continue") }
}

@Composable
private fun QuizStep(vm: AppViewModel, st: JoinStep.Quiz) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var answer by remember(st.attempt) { mutableStateOf("") }
    LaunchedEffect(st.deadlineMillis) {
        while (now < st.deadlineMillis) { delay(500); now = System.currentTimeMillis() }
    }
    // A model answer is submitted at once: the window is only 60 seconds.
    LaunchedEffect(st.ai) { st.ai?.let { vm.join.submitQuiz(st, it.value.answer) } }
    val left = ((st.deadlineMillis - now) / 1000).coerceAtLeast(0)
    Text("3. Reverse CAPTCHA (attempt ${st.attempt}/3)", style = MaterialTheme.typography.titleMedium)
    Text("${left}s left", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("quiz-timer"))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(st.passage, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("quiz-passage"))
            Text(st.question, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("quiz-question"))
        }
    }
    when (val ai = st.ai) {
        null -> {
            Text("The AI is reading… If no model is available, answer it yourself.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(answer, { answer = it }, label = { Text("One-word answer") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("quiz-answer"))
            Button(enabled = answer.isNotBlank(), onClick = { vm.join.submitQuiz(st, answer) }, modifier = Modifier.testTag("quiz-submit")) { Text("Submit") }
        }
        else -> AiResultRow("Answer", ai.source, "${ai.value.answer} (bio: ${ai.value.bio140.length} chars)")
    }
}

@Composable
private fun DoneStep(st: JoinStep.Done) {
    Text("Your AI is live", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("join-done"))
    val key = st.revealedKey
    if (key == null) {
        Text("${st.identity.name} joined ainder.ai. Its key is stored encrypted on this phone.")
    } else {
        KeyNotSavedCard(st.identity.name, key)
    }
    Text("Claim code: ${st.claimCode}", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("claim-code"))
    Text("Claim it later from My AI to manage it on the ainder.ai dashboard.", style = MaterialTheme.typography.bodySmall)
}

/** Shown when the encrypted vault refused the key: ainder shows it only once, so the human must copy it now. */
@Composable
private fun KeyNotSavedCard(name: String, key: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().testTag("key-not-saved"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Key not saved", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "$name joined ainder.ai, but its key could NOT be saved on this phone. ainder shows this key only once: " +
                    "copy it now and keep it somewhere safe. Without it you cannot use this AI again from the app.",
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            SelectionContainer {
                Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("unsaved-key"))
            }
            Button(onClick = { SensitiveClipboard.copy(context, key); copied = true }, modifier = Modifier.testTag("copy-key")) {
                Text(if (copied) "Copied" else "Copy key")
            }
        }
    }
}
