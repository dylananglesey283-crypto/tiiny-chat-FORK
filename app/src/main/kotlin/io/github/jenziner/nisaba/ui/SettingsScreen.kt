package io.github.jenziner.nisaba.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jenziner.nisaba.BuildConfig
import io.github.jenziner.nisaba.ModelStatus
import io.github.jenziner.nisaba.Nav
import io.github.jenziner.nisaba.graph
import io.github.jenziner.nisaba.R
import io.github.jenziner.nisaba.core.search.SearchOutcome
import io.github.jenziner.nisaba.settings.NetworkMode
import io.github.jenziner.nisaba.settings.AppSettings
import io.github.jenziner.nisaba.search.EngineChoices
import io.github.jenziner.nisaba.core.search.SearchEngine
import io.github.jenziner.nisaba.core.chat.swissInt
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.settings.state.collectAsState()
    val model by g.modelStatus.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            ctx.contentResolver.takePersistableUriPermission(uri, flags)
            val old = g.settings.current.folder
            g.settings.update { it.copy(folder = uri) }
            if (old != null && old != uri) runCatching { ctx.contentResolver.releasePersistableUriPermission(old, flags) }
        }
    }

    var baseUrl by remember { mutableStateOf(s.baseUrl) }
    var keyInput by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(g.keys.hasKey()) }
    var keyMsg by remember { mutableStateOf<String?>(null) }
    var confirmKeyRemove by remember { mutableStateOf(false) }
    var instr by remember { mutableStateOf(s.appInstructions) }
    var searchMsg by remember { mutableStateOf<String?>(null) }
    var searchTesting by remember { mutableStateOf(false) }
    var docLimit by remember { mutableStateOf(s.documentLimitTokens.toString()) }
    val scope = rememberCoroutineScope()

    Page("Settings", onBack = { nav.pop() }) { pad ->
        ScrollColumn(pad) {
            SectionTitle("Project storage")
            Hint(s.folder?.let { "Selected: ${it.lastPathSegment}" } ?: "No folder selected.")
            Hint("The app only gets access to this folder. All project data lives there only.")
            if (g.runningCount() > 0) ErrorText("Answers are still running. Changing the folder prevents them from being saved (different storage).")
            OutlinedButton(onClick = { picker.launch(s.folder) }) { Ico(R.drawable.ic_folder, null); Text(if (s.folder == null) "  Choose folder" else "  Choose another folder") }

            SectionTitle("Model server")
            LocalNetworkPermission(always = true)
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it }, singleLine = true,
                label = { Text("Base address") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Row {
                TextButton(enabled = baseUrl.trim() != s.baseUrl, onClick = { g.settings.update { it.copy(baseUrl = baseUrl.trim()) } }) { Text("Apply") }
                TextButton(onClick = { baseUrl = io.github.jenziner.nisaba.settings.AppSettings.DEFAULT_BASE_URL }) { Text("Default (USB)") }
            }

            Text("Network path", Modifier.padding(top = 8.dp))
            ModeRow(s.networkMode == NetworkMode.USB, "USB connection", "Use only the USB/Ethernet network. If it is missing, the request fails – no fallback to Wi-Fi.") {
                g.settings.update { it.copy(networkMode = NetworkMode.USB) }
            }
            ModeRow(s.networkMode == NetworkMode.SYSTEM, "System network", "The network chosen by Android (usually Wi-Fi).") {
                g.settings.update { it.copy(networkMode = NetworkMode.SYSTEM) }
            }

            SectionTitle("API key")
            Hint(if (hasKey) "A key is stored (encrypted with the Android Keystore)." else "No key stored.")
            OutlinedTextField(
                value = keyInput, onValueChange = { keyInput = it }, singleLine = true,
                label = { Text(if (hasKey) "New key" else "Key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(enabled = keyInput.isNotBlank(), onClick = {
                    keyMsg = runCatching { g.keys.set(keyInput.trim()) }.fold({ "Saved." }, { "Not saved: ${it.javaClass.simpleName}" })
                    keyInput = ""; hasKey = g.keys.hasKey()
                }) { Text("Save") }
                TextButton(enabled = hasKey, onClick = { confirmKeyRemove = true }) { Text("Remove") }
            }
            keyMsg?.let { Hint(it) }

            SectionTitle("Model")
            Hint(Texts.model(model, s.model))
            OutlinedButton(onClick = { g.refreshModels() }) { Ico(R.drawable.ic_refresh, null); Text("  Load models") }
            (model as? ModelStatus.Ok)?.models?.forEach { m ->
                ModeRow(s.model == m.id, m.id, m.contextWindow?.let { "Context window per server: $it tokens" } ?: "") {
                    g.settings.update { it.copy(model = m.id) }
                }
            }
            if (model !is ModelStatus.Ok && s.model != null) Hint("Last selected: ${s.model}. Availability is checked when loading.")

            SectionTitle("Answers")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Streaming")
                    Hint("Show the answer piece by piece. Turn off if there are problems.")
                }
                Switch(checked = s.streaming, onCheckedChange = { v -> g.settings.update { it.copy(streaming = v) } })
            }

            SectionTitle("Background")
            BackgroundStatus()

            SectionTitle("Ad-hoc chat")
            ReasoningControl(s.reasoning, g.thinkingInfo()) { r -> g.settings.update { it.copy(reasoning = r) } }
            SwitchRow(
                R.drawable.ic_travel_explore, "Web search",
                "Search results are added to the request, not saved.",
                s.webSearch,
            ) { v -> g.settings.update { it.copy(webSearch = v) } }
            Hint("Projects have their own reasoning and web search settings (project settings).")

            SectionTitle("Web search")
            Hint("The app reads the result page of the search engine directly (no browser, no JavaScript). Your search query goes to that search engine via the system network (Wi-Fi or mobile), not via USB. Google cannot be used this way.")
            val auto = remember(s.searchEngine) { EngineChoices.auto(ctx) }
            EngineChoices.choices.forEach { (id, label) ->
                ModeRow(
                    s.searchEngine == id || (id == AppSettings.SEARCH_AUTO && SearchEngine.byId(s.searchEngine) == null),
                    label,
                    if (id == AppSettings.SEARCH_AUTO) auto.explanation else "",
                ) { g.settings.update { it.copy(searchEngine = id) }; searchMsg = null }
            }
            TextButton(enabled = !searchTesting, onClick = {
                searchTesting = true; searchMsg = "Testing ${g.searchEngine().engine.label} …"
                scope.launch {
                    val r = g.webSearch().search("Bern Wetter")
                    searchTesting = false
                    searchMsg = when (r) {
                        is SearchOutcome.Ok -> "OK: ${r.results.size} result(s)" + (r.results.firstOrNull()?.let { " · ${it.url}" } ?: "")
                        is SearchOutcome.Failed -> "Failed: ${r.detail}"
                    }
                }
            }) { Text("Test search") }
            searchMsg?.let { Hint(it) }

            SectionTitle("Documents")
            Hint("Default limit for active documents per request, in estimated tokens (about 3 characters per token). Used only when the model reports no context window; if it does, the limit is half of it. Projects can set their own value.")
            OutlinedTextField(
                value = docLimit, onValueChange = { v -> docLimit = v.filter { it.isDigit() }.take(9) }, singleLine = true,
                label = { Text("Default document limit (tokens)") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            val parsed = docLimit.toIntOrNull()?.takeIf { it > 0 }
            Row {
                TextButton(enabled = parsed != null && parsed != s.documentLimitTokens, onClick = { g.settings.update { it.copy(documentLimitTokens = parsed!!) } }) { Text("Apply") }
                TextButton(onClick = { docLimit = AppSettings.DEFAULT_DOCUMENT_LIMIT.toString() }) { Text("Default (100'000)") }
            }
            g.contextWindow()?.let { Hint("The current model reports ${swissInt(it)} tokens context → limit ${swissInt(it / 2)} tokens.") }

            SectionTitle("General app instructions")
            Hint("Apply to all chats (including ad-hoc). Project instructions take precedence where they conflict. Kept in the app settings, not in the project folder.")
            OutlinedTextField(value = instr, onValueChange = { instr = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
            Button(enabled = instr != s.appInstructions, onClick = { g.settings.update { it.copy(appInstructions = instr) } }) { Text("Save") }

            SectionTitle("Privacy")
            Hint(Texts.VAULT_NOTE)

            SectionTitle("Version")
            Hint("Tiiny Chat ${BuildConfig.VERSION_NAME} · personal client, not an official Tiiny app")
        }
    }

    if (confirmKeyRemove) Confirm(
        title = "Remove key?",
        text = "Without a key, the Tiiny rejects requests.",
        confirm = "Remove",
        onConfirm = { confirmKeyRemove = false; g.keys.clear(); hasKey = g.keys.hasKey(); keyMsg = "Removed." },
        onDismiss = { confirmKeyRemove = false },
    )
}

@Composable
private fun ModeRow(selected: Boolean, title: String, hint: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column { Text(title); if (hint.isNotEmpty()) Hint(hint) }
    }
}
