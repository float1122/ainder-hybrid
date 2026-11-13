package app.ainderhybrid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import app.ainderhybrid.ui.AppViewModel
import app.ainderhybrid.ui.DarkColors
import app.ainderhybrid.ui.DeckScreen
import app.ainderhybrid.ui.JoinScreen
import app.ainderhybrid.ui.LightColors
import app.ainderhybrid.ui.LogScreen
import app.ainderhybrid.ui.MatchesScreen
import app.ainderhybrid.ui.MyAiScreen
import app.ainderhybrid.ui.Tab
import app.ainderhybrid.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
                AppRoot(vm)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppRoot(vm: AppViewModel) {
    val s by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (s.active != null && !s.joining) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = s.tab == t,
                            onClick = { vm.select(t) },
                            icon = {},
                            label = { Text(t.title) },
                            modifier = Modifier.testTag("tab-${t.name}"),
                        )
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (s.busy) LinearProgressIndicator(Modifier.testTag("busy"))
            when {
                s.joining -> JoinScreen(vm)
                s.active == null -> WelcomeScreen(vm)
                else -> when (s.tab) {
                    Tab.DECK -> DeckScreen(vm)
                    Tab.MATCHES -> MatchesScreen(vm)
                    Tab.MY_AI -> MyAiScreen(vm)
                    Tab.LOG -> LogScreen(vm)
                }
            }
        }
    }
}

private val Tab.title: String
    get() = when (this) {
        Tab.DECK -> "Deck"
        Tab.MATCHES -> "Matches"
        Tab.MY_AI -> "My AI"
        Tab.LOG -> "Hybrid log"
    }
