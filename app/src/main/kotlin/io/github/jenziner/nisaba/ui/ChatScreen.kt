package io.github.jenziner.nisaba.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.mutableIntStateOf
import io.github.jenziner.nisaba.Screen
import io.github.jenziner.nisaba.core.chat.DocumentBudget
import io.github.jenziner.nisaba.core.chat.DocumentLoad
import io.github.jenziner.nisaba.core.chat.swissInt
import io.github.jenziner.nisaba.core.store.ProjectRef
import io.github.jenziner.nisaba.core.store.ProjectStore
import io.github.jenziner.nisaba.core.store.StoreStatus
import io.github.jenziner.nisaba.docs.Documents
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jenziner.nisaba.ModelStatus
import io.github.jenziner.nisaba.Nav
import io.github.jenziner.nisaba.OpenSession
import io.github.jenziner.nisaba.R
import io.github.jenziner.nisaba.core.chat.ContextEstimate
import io.github.jenziner.nisaba.core.chat.LiveAnswer
import io.github.jenziner.nisaba.core.chat.PersistState
import io.github.jenziner.nisaba.core.chat.SendResult
import io.github.jenziner.nisaba.core.chat.SessionMessage
import io.github.jenziner.nisaba.core.format.MessageStatus
import io.github.jenziner.nisaba.core.format.ReasoningMode
import io.github.jenziner.nisaba.core.format.Role
import io.github.jenziner.nisaba.core.format.SoulState
import io.github.jenziner.nisaba.core.search.WebContext
import io.github.jenziner.nisaba.core.storage.SResult
import io.github.jenziner.nisaba.core.store.StoreResult
import io.github.jenziner.nisaba.graph
import io.github.jenziner.nisaba.background.AnswerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat-Ansicht. [onMenu] != null: Startansicht (Ad-hoc-Chat) mit Menü-Symbol,
 * Wortmarke, „In Projekt speichern“ und „Verwerfen“ statt Zurück.
 * Antworten des Modells werden als Markdown dargestellt, eigene Nachrichten als Text.
 */
@Composable
fun ChatScreen(nav: Nav, key: String, onMenu: (() -> Unit)? = null) {
    val g = LocalContext.current.graph
    val os = g.session(key)
    if (os == null) {
        Page("Chat", onBack = { nav.pop() }) { pad -> ScrollColumn(pad) { Hint("This chat is no longer open.") } }
        return
    }
    val st by os.session.state.collectAsState()
    val draft by os.draft.collectAsState()
    val notice by os.notice.collectAsState()
    val settings by g.settings.state.collectAsState()
    val model by g.modelStatus.collectAsState()
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    var saveTo by remember { mutableStateOf(false) }
    // Geschätzte Tokens der aktiven Dokumente (für die Kontextanzeige); null = unbekannt.
    var docTokens by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(key) { onDispose { g.releaseIfEmpty(key) } }

    // Speicher überwachen: Erreichbarkeit melden; beim Wiederverbinden speichert die Sitzung selbst nach.
    if (st.isProject) LaunchedEffect(key) {
        while (true) {
            val ok = withContext(Dispatchers.IO) { g.storage()?.first?.checkRoot() is SResult.Ok }
            val cur = os.session.state.value
            if (ok != cur.storageReachable || (ok && cur.hasPending)) os.session.setStorageReachable(ok)
            delay(3000)
        }
    }

    val selected = settings.model
    val modelProblem: String? = when {
        selected == null -> "No model selected (Settings)."
        model is ModelStatus.Ok && (model as ModelStatus.Ok).models.none { it.id == selected } ->
            "The selected model is not available on the server."
        else -> null
    }
    val busy = st.requestRunning || os.job?.isActive == true
    val canSubmit = draft.isNotBlank() && st.canSend && !busy && modelProblem == null

    // 1.0: the background notification needs POST_NOTIFICATIONS. Asked at most once per app start;
    // answers continue in the background either way (only the notification stays hidden).
    val ctx = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        g.log.add("notification permission: " + if (ok) "granted" else "denied")
    }
    fun askNotifications() {
        if (g.askedNotifications || AnswerService.notificationsAllowed(ctx)) return
        g.askedNotifications = true
        notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    fun launchSend(text: String?) {
        val m = selected ?: return
        if (text != null) os.draft.value = ""
        os.notice.value = null
        os.job = g.scope.launch {
            val r = if (text != null) os.session.send(text, m) else os.session.retry(m)
            if (text != null && (r is SendResult.Rejected || r is SendResult.NotSaved)) {
                // Eingabe nie zerstören: Text zurückgeben, inzwischen Getipptes anhängen.
                val now = os.draft.value
                os.draft.value = if (now.isBlank()) text else text + "\n\n" + now
            }
            os.notice.value = when (r) {
                is SendResult.Rejected -> "Not sent: ${r.reason}"
                is SendResult.HeldPending -> r.detail
                else -> null
            }
        }.also { g.runProtected(it) }
        askNotifications()
    }

    val home = onMenu != null
    val title = st.title ?: if (st.isProject) "New chat" else "Tiiny Chat"
    Page(
        title = title,
        onBack = if (home) null else ({ nav.pop() }),
        navigationIcon = onMenu?.let { m -> { IconBtn(R.drawable.ic_menu, "Menu", onClick = m) } },
        titleContent = if (home) ({ Wordmark() }) else null,
        onTitleClick = if (st.isProject && os.session.chatRef != null) ({ rename = true }) else null,
        actions = {
            if (!st.isProject) {
                IconBtn(R.drawable.ic_edit_square, "Save to project", enabled = st.messages.isNotEmpty() && !busy) { saveTo = true }
                IconBtn(R.drawable.ic_delete, "Discard chat", enabled = st.discardNeedsConfirmation) { confirmNew = true }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp)) {
                    if (selected != null && draft.isNotBlank()) {
                        val lvl = remember(draft, st.messages.size, selected, model, docTokens) {
                            os.session.contextLevel(draft, selected, g.contextWindow(), docTokens ?: 0)
                        }
                        when (lvl) {
                            is ContextEstimate.Level.Near -> Hint("Context almost full (est. ${lvl.estimate} of ${lvl.limit} tokens).")
                            is ContextEstimate.Level.Over -> ErrorText("Context probably too long (est. ${lvl.estimate} of ${lvl.limit} tokens). Nothing is truncated.")
                            else -> {}
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { os.draft.value = it },
                            modifier = Modifier.weight(1f).heightIn(max = 200.dp),
                            placeholder = { Text("Message") },
                        )
                        Box(Modifier.padding(start = 8.dp)) {
                            if (st.requestRunning) {
                                OutlinedIconButton(onClick = { os.job?.cancel() }) { Ico(R.drawable.ic_stop, "Stop") }
                            } else {
                                FilledIconButton(enabled = canSubmit, onClick = { launchSend(draft) }) { Ico(R.drawable.ic_send, "Send") }
                            }
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            StatusBlock(os, modelProblem, notice, model, selected) { docTokens = it }
            val list = rememberLazyListState()
            val count = st.messages.size + (if (st.live != null) 1 else 0)
            LaunchedEffect(count, st.live?.content?.length) { if (count > 0) list.scrollToItem(count - 1) }
            run {
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(st.messages, key = { it.message.id }) { MessageView(it, st.isProject) }
                    st.live?.let { live -> item(key = "live") { LiveView(live) } }
                    if (st.retryableUserMessage != null && !busy) item(key = "retry") {
                        OutlinedButton(enabled = st.canSend && modelProblem == null, onClick = { launchSend(null) }) {
                            Ico(R.drawable.ic_refresh, null); Text("  Request answer")
                        }
                    }
                }
            }
        }
    }

    if (rename) RenameDialog(st.title.orEmpty(), onDismiss = { rename = false }) { t ->
        rename = false
        scope.launch {
            val r = os.session.rename(t)
            os.notice.value = if (r is StoreResult.Ok) null else "Title not changed: ${Texts.store(r)}"
        }
    }
    if (confirmNew) Confirm(
        title = "Discard this chat?",
        text = "It is not saved and will be lost." + if (st.requestRunning) " The running answer is stopped." else "",
        confirm = "Discard",
        onConfirm = { confirmNew = false; g.renewAdHoc() },
        onDismiss = { confirmNew = false },
    )
    if (saveTo) SaveToProjectDialog(onDismiss = { saveTo = false }) { store, project ->
        saveTo = false
        if (os.session.state.value.requestRunning) { os.notice.value = "Wait until the answer is finished."; return@SaveToProjectDialog }
        val msgs = os.session.state.value.messages.map { it.message }
        scope.launch {
            val (opened, err) = g.saveAdHocTo(store, project, msgs)
            if (opened != null) nav.push(Screen.Chat(opened.key)) else os.notice.value = "Not saved: $err"
        }
    }
}

/** Auswahl des Projekts, in das ein Ad-hoc-Chat verschoben wird. */
@Composable
private fun SaveToProjectDialog(onDismiss: () -> Unit, onPick: (ProjectStore, ProjectRef) -> Unit) {
    val g = LocalContext.current.graph
    var projects by remember { mutableStateOf<List<ProjectRef>?>(null) }
    var store by remember { mutableStateOf<ProjectStore?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val st = g.storage()
        if (st == null) { error = "No project folder selected (Settings)."; return@LaunchedEffect }
        val r = withContext(Dispatchers.IO) {
            when (val s = st.second.openStore()) {
                is StoreStatus.Ready -> st.second.listProjects(s.storeId)
                else -> null.also { error = Texts.storeStatus(s) }
            }
        }
        when (r) {
            null -> {}
            is StoreResult.Ok -> { store = st.second; projects = r.value.items.sortedBy { it.project.name.lowercase() } }
            else -> error = Texts.store(r)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Ico(R.drawable.ic_edit_square, null) },
        title = { Text("Save to project") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Hint("The chat moves into the chosen project and is no longer on the start page. It keeps no project instructions.")
                error?.let { ErrorText(it) }
                val list = projects
                when {
                    list == null && error == null -> Hint("Loading …")
                    list != null && list.isEmpty() -> Hint("No projects yet. Create one from the menu first.")
                    list != null -> list.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { store?.let { onPick(it, p) } }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Ico(R.drawable.ic_folder, null)
                            Text("  ${p.project.name}")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusBlock(
    os: OpenSession, modelProblem: String?, notice: String?, model: ModelStatus, selected: String?,
    onDocTokens: (Int?) -> Unit,
) {
    val g = LocalContext.current.graph
    val st by os.session.state.collectAsState()
    val scope = rememberCoroutineScope()
    val project = os.project
    var available by remember { mutableStateOf<List<String>?>(null) }
    var docReload by remember { mutableIntStateOf(0) }
    var docBusy by remember { mutableStateOf(false) }
    var docMsg by remember { mutableStateOf<String?>(null) }
    var docTokens by remember { mutableStateOf<Int?>(null) }

    if (project != null) {
        LaunchedEffect(project.project.projectId, docReload) {
            available = (g.documents.list(project) as? StoreResult.Ok)?.value?.map { it.name }
        }
        LaunchedEffect(st.documents, docReload) {
            docTokens = if (st.documents.isEmpty()) 0 else when (val r = g.documents.sourceFor(project).load(st.documents)) {
                is DocumentLoad.Ok -> DocumentBudget.tokens(r.documents)
                is DocumentLoad.Failed -> null
            }
            onDocTokens(docTokens)
        }
    }
    fun toggle(name: String, on: Boolean) {
        val next = if (on) st.documents + name else st.documents - name
        scope.launch {
            val r = os.session.setDocuments(next)
            docMsg = if (r is StoreResult.Ok) null else "Selection not saved: ${Texts.store(r)}"
        }
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && project != null) {
            docBusy = true; docMsg = "Uploading …"
            scope.launch {
                when (val r = g.documents.upload(project, uri)) {
                    is Documents.Upload.Ok -> {
                        val s = os.session.setDocuments(os.session.state.value.documents + r.ref.name)
                        docMsg = if (s is StoreResult.Ok) null else "Uploaded, but selection not saved: ${Texts.store(s)}"
                    }
                    is Documents.Upload.Failed -> docMsg = "Not uploaded: ${r.reason}"
                }
                docBusy = false
                docReload++
            }
        }
    }
    val (reasoning, web) = g.settingsFor(os.project)
    val res = remember(reasoning, model) { g.resolveReasoning(reasoning) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        LocalNetworkPermission()
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (project != null) {
                IconBtn(R.drawable.ic_attach_file, "Upload document", enabled = !docBusy && !st.requestRunning) { upload.launch(arrayOf("*/*")) }
            }
            when {
                !st.isProject -> {}
                st.persist == PersistState.Saved && st.storageReachable -> StatusChip(R.drawable.ic_check, "Saved")
                st.persist == PersistState.Draft && st.storageReachable -> StatusChip(R.drawable.ic_edit_note, "New")
                else -> StatusChip(R.drawable.ic_error, Texts.persist(st.persist, st.storageReachable), warn = true)
            }
            if (model is ModelStatus.Err) StatusChip(R.drawable.ic_error, "Offline", warn = true)
            if (reasoning.mode != ReasoningMode.MODEL_DEFAULT) {
                StatusChip(R.drawable.ic_psychology, Texts.reasoning(reasoning), warn = res.note != null)
            }
            if (web) StatusChip(R.drawable.ic_travel_explore, "Web")
            if (st.isProject) os.session.soulSnapshot?.let {
                if (it.state != SoulState.MISSING) StatusChip(R.drawable.ic_edit_note, "Instructions ${it.takenAt.take(10)}")
            }
            if (project != null) {
                val names = (available.orEmpty() + st.documents).distinct()
                names.forEach { n ->
                    val active = n in st.documents
                    val missing = available != null && n !in available!!
                    FilterChip(
                        selected = active,
                        enabled = !st.requestRunning,
                        onClick = { toggle(n, !active) },
                        label = { Text(if (missing) "$n · missing" else n, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Ico(if (active) R.drawable.ic_check else R.drawable.ic_description, null, size = 16) },
                        colors = if (missing) FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) else FilterChipDefaults.filterChipColors(),
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
            }
        }
        if (project != null && st.documents.isNotEmpty()) {
            val limit = g.documentLimit(project)
            docTokens?.let { t ->
                if (t > limit) ErrorText("Active documents ≈ ${swissInt(t)} tokens, limit ${swissInt(limit)}. Deactivate documents to send.")
                else Hint("Active documents ≈ ${swissInt(t)} of ${swissInt(limit)} tokens")
            }
        }
        docMsg?.let { if (it == "Uploading …") Hint(it) else ErrorText(it) }
        res.note?.let { Hint("Reasoning: $it") }
        if (model is ModelStatus.Err) Hint(Texts.model(model, selected))
        modelProblem?.let { ErrorText(it) }
        st.lastError?.let { ErrorText(Texts.session(it)) }
        notice?.let { ErrorText(it) }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun MessageView(m: SessionMessage, isProject: Boolean) {
    val isUser = m.message.role == Role.USER
    var showReasoning by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    val label = buildList {
        when (m.message.status) {
            MessageStatus.INCOMPLETE -> add("incomplete")
            MessageStatus.FAILED -> add("failed")
            MessageStatus.COMPLETE -> {}
        }
        if (isProject && !m.saved) add("not saved yet")
        if (!isUser) m.message.model?.let { add(it.substringAfterLast('/')) }
    }.joinToString(" · ")
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(Modifier.fillMaxWidth(if (isUser) 0.85f else 1f)) {
            if (isUser) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    SelectionContainer { Text(m.message.content, Modifier.padding(10.dp)) }
                }
            } else {
                if (showReasoning) m.reasoning?.let { ReasoningBox(it) }
                SelectionContainer {
                    if (m.message.content.isEmpty()) Text("(no answer)") else MarkdownText(m.message.content)
                }
                if (showSources) m.web?.let { Sources(it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isUser && m.reasoning != null) {
                    IconBtn(R.drawable.ic_psychology, if (showReasoning) "Hide reasoning" else "Show reasoning (not saved)",
                        tint = if (showReasoning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) { showReasoning = !showReasoning }
                }
                if (!isUser && m.web != null) {
                    IconBtn(R.drawable.ic_travel_explore, if (showSources) "Hide sources" else "Show web sources",
                        tint = if (showSources) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) { showSources = !showSources }
                }
                if (label.isNotEmpty()) Hint(label)
            }
        }
    }
}

@Composable
private fun ReasoningBox(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        SelectionContainer {
            Text(text, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Sources(w: WebContext) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Hint("Web search: \"${w.query}\"" + if (w.results.isEmpty()) " · no results" else "")
        w.results.forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("[${i + 1}] ", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f)) {
                    Text(r.title.ifEmpty { r.url }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                    Text(r.url, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Ico(R.drawable.ic_open_in_new, "Open", MaterialTheme.colorScheme.onSurfaceVariant, size = 16)
            }
        }
    }
}

@Composable
private fun LiveView(live: LiveAnswer) {
    var showReasoning by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        live.searching?.let { q ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Ico(R.drawable.ic_travel_explore, "Searching", MaterialTheme.colorScheme.onSurfaceVariant, size = 16)
                Hint("  \"$q\"")
            }
        }
        if (live.reasoning.isNotEmpty()) {
            TextButton(onClick = { showReasoning = !showReasoning }) {
                Ico(R.drawable.ic_psychology, null, size = 18)
                Text(if (showReasoning) "  Hide" else "  Thinking … (not saved)")
            }
            if (showReasoning) ReasoningBox(live.reasoning)
        }
        if (live.content.isNotEmpty()) SelectionContainer { MarkdownText(live.content) } else if (live.searching == null) Hint("…")
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = { OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = t.isNotBlank() && t != current, onClick = { onSave(t.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
