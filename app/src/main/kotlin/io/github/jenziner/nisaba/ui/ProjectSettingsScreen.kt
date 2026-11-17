package io.github.jenziner.nisaba.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.ui.text.input.KeyboardType
import io.github.jenziner.nisaba.core.chat.swissInt
import io.github.jenziner.nisaba.core.store.DocumentRef
import io.github.jenziner.nisaba.docs.DocumentInfo
import io.github.jenziner.nisaba.docs.Documents
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jenziner.nisaba.Nav
import io.github.jenziner.nisaba.R
import io.github.jenziner.nisaba.Screen
import io.github.jenziner.nisaba.core.format.ProjectSettings
import io.github.jenziner.nisaba.core.store.DeletionPlan
import io.github.jenziner.nisaba.core.store.ProjectRef
import io.github.jenziner.nisaba.core.store.StoreResult
import io.github.jenziner.nisaba.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Projekt-Einstellungen: Projektanweisungen (SOUL.md), Reasoning, Websuche,
 * Dokumente, Dokumentgrenze, Projekt löschen. Einstellungen liegen in
 * project.json (Formatversion 3), Dokumente in files/.
 */
@Composable
fun ProjectSettingsScreen(nav: Nav, initial: ProjectRef) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val model by g.modelStatus.collectAsState()
    val app by g.settings.state.collectAsState()
    var ref by remember { mutableStateOf<ProjectRef?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var plan by remember { mutableStateOf<DeletionPlan?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var docs by remember { mutableStateOf<List<DocumentInfo>?>(null) }
    var docError by remember { mutableStateOf<String?>(null) }
    var docReload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDocs by remember { mutableStateOf<List<DocumentRef>?>(null) }
    var docBusy by remember { mutableStateOf(false) }
    var limitText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initial, docReload) {
        when (val r = g.documents.list(initial)) {
            is StoreResult.Ok -> {
                docError = null
                docs = g.documents.inspect(r.value)
                selected = selected.intersect(r.value.map { it.name }.toSet())
            }
            else -> docError = Texts.store(r)
        }
    }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            docBusy = true
            scope.launch {
                when (val r = g.documents.upload(initial, uri)) {
                    is Documents.Upload.Ok -> docError = null
                    is Documents.Upload.Failed -> docError = "Not uploaded: ${r.reason}"
                }
                docBusy = false
                docReload++
            }
        }
    }

    LaunchedEffect(initial, reload) {
        val st = g.storage()
        val r = if (st == null) null else withContext(Dispatchers.IO) { st.second.reloadProject(initial) }
        when (r) {
            is StoreResult.Ok -> { ref = r.value; loadError = null; g.noteProject(r.value) }
            null -> loadError = "No project folder."
            else -> loadError = Texts.store(r)
        }
    }

    fun save(s: ProjectSettings) {
        val cur = ref ?: return
        if (saving) return
        saving = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { g.storage()?.second?.updateProjectSettings(cur, s) }
            saving = false
            when (r) {
                is StoreResult.Ok -> { ref = r.value; g.noteProject(r.value); message = null }
                null -> message = "No project folder."
                else -> message = "Not saved: ${Texts.store(r)}"
            }
        }
    }

    Page(ref?.project?.name ?: initial.project.name, onBack = { nav.pop() }) { pad ->
        ScrollColumn(pad) {
            loadError?.let { ErrorText(it); IconBtn(R.drawable.ic_refresh, "Retry") { reload++ } }
            val cur = ref ?: return@ScrollColumn
            val s = cur.project.settings
            message?.let { ErrorText(it) }

            Row(
                Modifier.fillMaxWidth().focusClickable { nav.push(Screen.Soul(cur)) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Ico(R.drawable.ic_edit_note, null)
                Column(Modifier.weight(1f)) {
                    Text("Project instructions")
                    Hint("SOUL.md · override the general app instructions where they conflict")
                }
            }

            SectionTitle("Model")
            ReasoningControl(s.reasoning, g.thinkingInfo()) { save(s.copy(reasoning = it)) }
            if (model !is io.github.jenziner.nisaba.ModelStatus.Ok) Hint("Connect to the Tiiny to see the model capabilities.")
            SwitchRow(
                R.drawable.ic_travel_explore, "Web search",
                "Via ${g.searchEngine().engine.label} (Settings → Web search). Results are not saved.",
                s.webSearch, enabled = !saving,
            ) { save(s.copy(webSearch = it)) }
            if (cur.sourceVersion < 3) Hint("project.json is in format ${cur.sourceVersion}; it is converted to format 3 on the first change.")

            SectionTitle("Documents")
            Hint("Documents are stored in the project folder (files/). In a chat you choose which ones are sent with each request. Their text goes to the Tiiny with every request.")
            docError?.let { ErrorText(it) }
            val list = docs
            when {
                list == null && docError == null -> Hint("Loading …")
                list != null && list.isEmpty() -> Hint("No documents.")
                list != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selected.size == list.size,
                            onCheckedChange = { all -> selected = if (all) list.map { it.ref.name }.toSet() else emptySet() },
                        )
                        Text("Select all", Modifier.weight(1f))
                        TextButton(
                            enabled = selected.isNotEmpty() && !docBusy,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            onClick = { confirmDocs = list.filter { it.ref.name in selected }.map { it.ref } },
                        ) { Ico(R.drawable.ic_delete, null); Text(" Delete (${selected.size})") }
                    }
                    list.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = d.ref.name in selected, onCheckedChange = { on ->
                                selected = if (on) selected + d.ref.name else selected - d.ref.name
                            })
                            Column(Modifier.weight(1f)) {
                                Text(d.ref.name)
                                Hint(
                                    listOfNotNull(
                                        d.ref.size?.let { sizeText(it) },
                                        d.tokens?.let { "≈ ${swissInt(it)} tokens" },
                                        d.problem?.let { "not usable: $it" },
                                    ).joinToString(" · "),
                                )
                            }
                            IconBtn(R.drawable.ic_delete, "Delete ${d.ref.name}", enabled = !docBusy) { confirmDocs = listOf(d.ref) }
                        }
                    }
                }
            }
            OutlinedButton(enabled = !docBusy, onClick = { if (!upload.tryLaunch(arrayOf("*/*"))) docError = "This device has no file picker." }) {
                Ico(R.drawable.ic_attach_file, null); Text(if (docBusy) "  Working …" else "  Upload document")
            }
            Hint("Text, Markdown, CSV, JSON, code, PDF (text layer only, no OCR) and DOCX. Max. 25 MB per file.")

            Text("Document limit", Modifier.padding(top = 12.dp))
            val cw = g.contextWindow()
            if (cw != null) {
                Hint("The model reports a context window of ${swissInt(cw)} tokens. Active documents may use at most half: ${swissInt(cw / 2)} tokens. The value below applies only if the model reports no context window.")
            } else {
                Hint("The model reports no context window. Active documents may use at most this many estimated tokens per request (about 3 characters per token). Empty = app default (${swissInt(app.documentLimitTokens)}).")
            }
            val shown = limitText ?: (s.documentLimitTokens?.toString() ?: "")
            OutlinedTextField(
                value = shown, onValueChange = { v -> limitText = v.filter { it.isDigit() }.take(9) }, singleLine = true,
                label = { Text("Project limit (tokens)") }, placeholder = { Text("app default") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            val newLimit = shown.toIntOrNull()?.takeIf { it > 0 }
            TextButton(
                enabled = !saving && limitText != null && newLimit != s.documentLimitTokens && (shown.isEmpty() || newLimit != null),
                onClick = { save(s.copy(documentLimitTokens = newLimit)); limitText = null },
            ) { Text("Apply") }

            SectionTitle("Danger zone")
            val running = g.runningFor(cur.project.projectId)
            OutlinedButton(
                enabled = !deleting,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    if (running > 0) { message = "Answers are still running in this project. Stop them first."; return@OutlinedButton }
                    deleting = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { g.storage()?.second?.planProjectDeletion(cur) }
                        deleting = false
                        when (r) {
                            is StoreResult.Ok -> plan = r.value
                            null -> message = "No project folder."
                            else -> message = "Cannot check: ${Texts.store(r)}"
                        }
                    }
                },
            ) { Ico(R.drawable.ic_delete, null); Text("  Delete project") }
        }
    }

    confirmDocs?.let { toDelete ->
        Confirm(
            title = if (toDelete.size == 1) "Delete document permanently?" else "Delete ${toDelete.size} documents permanently?",
            text = toDelete.take(8).joinToString("\n") { "• ${it.name}" } + (if (toDelete.size > 8) "\n• … ${toDelete.size - 8} more" else "") +
                "\n\nThe files are removed from the project folder. Chats that used them will show them as missing. This cannot be undone.",
            confirm = "Delete",
            onConfirm = {
                confirmDocs = null
                docBusy = true
                scope.launch {
                    val failed = mutableListOf<String>()
                    for (d in toDelete) {
                        val r = g.documents.delete(d)
                        if (r !is StoreResult.Ok) failed += "${d.name}: ${Texts.store(r)}"
                    }
                    docError = if (failed.isEmpty()) null else "Not deleted: " + failed.joinToString("; ")
                    selected = selected - toDelete.map { it.name }.toSet()
                    docBusy = false
                    docReload++
                }
            },
            onDismiss = { confirmDocs = null },
        )
    }

    val cur = ref
    when (val p = plan) {
        null -> {}
        is DeletionPlan.Blocked -> AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text("Deletion blocked") },
            text = {
                Column {
                    Text("Only an empty project can be deleted. Nothing was deleted:")
                    p.reasons.take(10).forEach { Hint("• $it") }
                    if (p.reasons.size > 10) Hint("• … ${p.reasons.size - 10} more")
                }
            },
            confirmButton = { TextButton(onClick = { plan = null }) { Text("OK") } },
        )
        is DeletionPlan.Allowed -> if (cur != null) DeleteDialog(cur, p.chatCount, onDismiss = { plan = null }) {
            plan = null
            deleting = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { g.storage()?.second?.deleteProject(cur) }
                deleting = false
                when (r) {
                    is StoreResult.Ok -> { g.forgetProject(cur.project.projectId); nav.popToRoot() }
                    null -> message = "No project folder."
                    else -> { message = "Not deleted: ${Texts.store(r)}"; reload++ }
                }
            }
        }
    }
}

private fun sizeText(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} kB"
    else -> "%.1f MB".format(java.util.Locale.ROOT, b / 1024.0 / 1024.0)
}

@Composable
private fun DeleteDialog(ref: ProjectRef, chats: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val ok = typed.trim() == ref.project.name
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Ico(R.drawable.ic_delete, null, MaterialTheme.colorScheme.error) },
        title = { Text("Delete project permanently?") },
        text = {
            Column {
                Text("\"${ref.project.name}\" with $chats chat(s), its project instructions and its folder ${ref.dir} will be deleted. This cannot be undone.")
                Hint("Type the project name to confirm.")
                OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
