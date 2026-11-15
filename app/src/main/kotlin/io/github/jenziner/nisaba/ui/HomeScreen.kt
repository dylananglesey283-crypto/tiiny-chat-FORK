package io.github.jenziner.nisaba.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jenziner.nisaba.ModelStatus
import io.github.jenziner.nisaba.Nav
import io.github.jenziner.nisaba.R
import io.github.jenziner.nisaba.Screen
import io.github.jenziner.nisaba.core.store.Listing
import io.github.jenziner.nisaba.core.store.ProjectRef
import io.github.jenziner.nisaba.core.store.StoreResult
import io.github.jenziner.nisaba.core.store.StoreStatus
import io.github.jenziner.nisaba.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Startansicht: Ad-hoc-Chat, Menü mit Projekten, Einstellungen und Diagnose. */
@Composable
fun HomeScreen(nav: Nav) {
    val g = LocalContext.current.graph
    val adHoc by g.adHocFlow.collectAsState()
    val model by g.modelStatus.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { if (model == ModelStatus.Unknown) g.refreshModels("start") }
    LaunchedEffect(adHoc) { if (adHoc == null) g.startAdHoc() }

    fun go(s: Screen) { scope.launch { drawer.close() }; nav.push(s) }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(open = drawer.isOpen, go = ::go)
            }
        },
    ) {
        val os = adHoc
        if (os != null) {
            // key: neuer Ad-hoc-Chat = neue Ansicht (Zustand wie Scrollposition zurücksetzen)
            androidx.compose.runtime.key(os.key) {
                ChatScreen(nav, os.key, onMenu = { scope.launch { drawer.open() } })
            }
        }
    }
}

@Composable
private fun DrawerContent(open: Boolean, go: (Screen) -> Unit) {
    val g = LocalContext.current.graph
    val settings by g.settings.state.collectAsState()
    val model by g.modelStatus.collectAsState()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<StoreStatus?>(null) }
    var projects by remember { mutableStateOf<StoreResult<Listing<ProjectRef>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var confirmInit by remember { mutableStateOf(false) }

    LaunchedEffect(settings.folder, reload, open) {
        if (!open) return@LaunchedEffect
        val st = g.storage()
        if (st == null) { status = null; projects = null; return@LaunchedEffect }
        val (s, p) = withContext(Dispatchers.IO) {
            val s = st.second.openStore()
            s to (if (s is StoreStatus.Ready) st.second.listProjects(s.storeId) else null)
        }
        status = s; projects = p
        (p as? StoreResult.Ok)?.value?.items?.forEach { g.noteProject(it) }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Wordmark(26) }
        // Modellstatus: Punkt, Modell, Neu prüfen
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = when (model) {
                is ModelStatus.Ok -> Color(0xFF2E7D32)
                is ModelStatus.Err -> MaterialTheme.colorScheme.error
                ModelStatus.Checking -> Color(0xFFF9A825)
                ModelStatus.Unknown -> MaterialTheme.colorScheme.outline
            }
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                settings.model?.substringAfterLast('/') ?: "No model",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconBtn(R.drawable.ic_refresh, "Check connection", enabled = model != ModelStatus.Checking) { g.refreshModels("manual") }
        }
        if (model is ModelStatus.Err) Box(Modifier.padding(horizontal = 20.dp)) { Hint(Texts.model(model, settings.model)) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Projects", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (status is StoreStatus.Ready) IconBtn(R.drawable.ic_add, "New project") { showNew = true }
            IconBtn(R.drawable.ic_refresh, "Reload projects") { reload++ }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            message?.let { ErrorText(it) }
            val s = status
            when {
                settings.folder == null -> {
                    Hint("No project folder.")
                    TextButton(onClick = { go(Screen.Settings) }) { Ico(R.drawable.ic_folder, null); Text("  Choose folder") }
                }
                s == null -> Hint("Checking …")
                s is StoreStatus.Failure -> ErrorText(Texts.storeStatus(s))
                s is StoreStatus.Invalid -> ErrorText(Texts.storeStatus(s))
                s is StoreStatus.NotInitialized -> {
                    Hint(Texts.storeStatus(s))
                    TextButton(onClick = { confirmInit = true }) { Ico(R.drawable.ic_folder, null); Text("  Set up folder") }
                    if (confirmInit) Confirm(
                        title = "Set up folder",
                        text = if (s.isEmpty) "The file nisaba-store.json is created in the selected folder."
                        else "The folder is not empty. Existing files stay unchanged; only nisaba-store.json is added.",
                        confirm = "Set up",
                        onConfirm = {
                            confirmInit = false
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { g.storage()?.second?.initializeStore() }
                                message = if (r is StoreStatus.Ready) null else r?.let { "Setup failed: ${Texts.storeStatus(it)}" }
                                reload++
                            }
                        },
                        onDismiss = { confirmInit = false },
                    )
                }
                s is StoreStatus.Ready -> when (val p = projects) {
                    null -> Hint("Reading …")
                    is StoreResult.Ok -> {
                        if (p.value.items.isEmpty()) Hint("No projects found")
                        p.value.problems.forEach { ErrorText("Skipped: ${it.path} – ${it.detail}") }
                    }
                    else -> ErrorText(Texts.store(p))
                }
            }
        }
        (projects as? StoreResult.Ok)?.value?.items?.sortedBy { it.project.name.lowercase() }?.forEach { ref ->
            Row(
                Modifier.fillMaxWidth().clickable { go(Screen.Project(ref)) }.padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(R.drawable.ic_folder, null, MaterialTheme.colorScheme.onSurfaceVariant, size = 20)
                Spacer(Modifier.width(12.dp))
                Text(ref.project.name, Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconBtn(R.drawable.ic_settings, "Project settings") { go(Screen.ProjectSettings(ref)) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        DrawerRow(R.drawable.ic_settings, "Settings") { go(Screen.Settings) }
        DrawerRow(R.drawable.ic_bug_report, "Diagnostics") { go(Screen.Diagnostics) }
    }

    if (showNew) NewProjectDialog(
        onDismiss = { showNew = false },
        onCreate = { name ->
            showNew = false
            val s = status as? StoreStatus.Ready ?: return@NewProjectDialog
            scope.launch {
                val r = withContext(Dispatchers.IO) { g.storage()?.second?.createProject(s.storeId, name) }
                message = if (r is StoreResult.Ok || r == null) null else "Project not created: ${Texts.store(r)}"
                reload++
            }
        },
    )
}

@Composable
private fun DrawerRow(icon: Int, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Ico(icon, null)
        Text(text)
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
