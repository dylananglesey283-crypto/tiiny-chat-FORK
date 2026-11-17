package io.github.jenziner.nisaba.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import io.github.jenziner.nisaba.core.net.LanTools
import io.github.jenziner.nisaba.core.store.Listing
import io.github.jenziner.nisaba.core.store.ProjectRef
import io.github.jenziner.nisaba.core.store.StoreResult
import io.github.jenziner.nisaba.core.store.StoreStatus
import io.github.jenziner.nisaba.graph
import io.github.jenziner.nisaba.settings.NetworkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dashboard, the start screen on a TV: connection, network, storage and activity at a glance,
 * big remote-friendly tiles, and the project list. Everything focusable works with the D-pad.
 */
@Composable
fun DashboardScreen(nav: Nav) {
    val g = LocalContext.current.graph
    val settings by g.settings.state.collectAsState()
    val model by g.modelStatus.collectAsState()
    val running by produceState(0) { while (true) { value = g.runningCount(); delay(1000) } }
    var status by remember { mutableStateOf<StoreStatus?>(null) }
    var projects by remember { mutableStateOf<StoreResult<Listing<ProjectRef>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var waking by remember { mutableStateOf(false) }
    var wakeMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (model == ModelStatus.Unknown) g.refreshModels("dashboard") }
    LaunchedEffect(settings.folder, reload) {
        val st = g.storage()
        if (st == null) { status = null; projects = null; return@LaunchedEffect }
        val (s, p) = withContext(Dispatchers.IO) {
            val s = st.second.openStore()
            s to (if (s is StoreStatus.Ready) st.second.listProjects(s.storeId) else null)
        }
        status = s; projects = p
        (p as? StoreResult.Ok)?.value?.items?.forEach { g.noteProject(it) }
    }

    Page("Dashboard", onBack = null, titleContent = { Wordmark(26) }) { pad ->
        ScrollColumn(pad) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val dot = when (model) {
                    is ModelStatus.Ok -> Color(0xFF2E7D32)
                    is ModelStatus.Err -> MaterialTheme.colorScheme.error
                    ModelStatus.Checking -> Color(0xFFF9A825)
                    ModelStatus.Unknown -> MaterialTheme.colorScheme.outline
                }
                InfoCard(
                    "Model", settings.model?.substringAfterLast('/') ?: "No model",
                    Texts.model(model, settings.model), dot, Modifier.weight(1f),
                )
                InfoCard(
                    "Network",
                    if (settings.networkMode == NetworkMode.USB) "USB connection" else "System network",
                    settings.baseUrl, null, Modifier.weight(1f),
                )
                val items = (projects as? StoreResult.Ok)?.value?.items
                InfoCard(
                    "Storage",
                    when {
                        settings.folder == null -> "No folder"
                        settings.folder?.scheme == "file" -> "App storage"
                        else -> settings.folder?.lastPathSegment ?: "Folder"
                    },
                    when {
                        settings.folder == null -> "Choose one in Settings"
                        items != null -> "${items.size} project(s)"
                        status is StoreStatus.Ready -> "Reading …"
                        status == null -> "Checking …"
                        else -> "Needs attention"
                    },
                    null, Modifier.weight(1f),
                )
                InfoCard(
                    "Activity", if (running == 0) "Idle" else "$running answering",
                    if (running == 0) "No request running" else "Answers keep running in the background",
                    null, Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))
            // Fixed text from the Tiiny Pocket spec sheet; the app cannot read these values from the device.
            InfoCard(
                "Device (spec sheet)", "Tiiny Pocket · runs models up to 120B",
                "80 GB RAM (32 GB SoC + 48 GB dNPU) · 190 TOPS (30 SoC + 160 dNPU) · 30 W TDP · 1 TB NVMe SSD",
                null, Modifier.fillMaxWidth(),
            )

            SectionTitle("Start")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(R.drawable.ic_send, "Chat", enabled = true) { nav.push(Screen.Home) }
                Tile(R.drawable.ic_edit_square, "New chat", enabled = running == 0) { g.renewAdHoc(); nav.push(Screen.Home) }
                Tile(R.drawable.ic_refresh, "Check connection", enabled = model != ModelStatus.Checking) {
                    g.refreshModels("manual"); reload++
                }
                Tile(R.drawable.ic_settings, "Settings", enabled = true) { nav.push(Screen.Settings) }
                Tile(R.drawable.ic_bug_report, "Diagnostics", enabled = true) { nav.push(Screen.Diagnostics) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(R.drawable.ic_search, "Find Tiiny", enabled = true) { nav.push(Screen.Find) }
                Tile(R.drawable.ic_power, "Power on", enabled = !waking && LanTools.isHttpUrl(settings.powerOnUrl)) {
                    waking = true; wakeMsg = "Sending …"
                    scope.launch { wakeMsg = powerOnAndWatch(g, settings.powerOnUrl); waking = false }
                }
            }
            if (settings.powerOnUrl.isBlank()) Hint("Power on needs your smart plug's turn-on address: open Find Tiiny to enter it.")
            wakeMsg?.let { Hint(it) }

            SectionTitle("Projects")
            val list = (projects as? StoreResult.Ok)?.value?.items
            when {
                settings.folder == null -> Hint("No project folder. Open Settings to choose one (or use app storage).")
                status is StoreStatus.NotInitialized -> Hint("The folder is not set up yet. Open the chat menu (Menu button) to set it up.")
                list == null -> Hint("Reading …")
                list.isEmpty() -> Hint("No projects yet. Create one from the chat menu (Menu button).")
                else -> list.sortedBy { it.project.name.lowercase() }.forEach { ref ->
                    Row(
                        Modifier.fillMaxWidth().focusClickable { nav.push(Screen.Project(ref)) }.padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ico(R.drawable.ic_folder, null, MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(ref.project.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoCard(title: String, value: String, sub: String, dot: Color?, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) {
                    Box(Modifier.size(10.dp).background(dot, CircleShape))
                    Spacer(Modifier.width(8.dp))
                }
                Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Tile(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        Modifier.width(150.dp).focusClickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Ico(icon, null, size = 32)
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
