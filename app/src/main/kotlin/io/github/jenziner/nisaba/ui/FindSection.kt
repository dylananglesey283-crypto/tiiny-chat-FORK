package io.github.jenziner.nisaba.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jenziner.nisaba.AppGraph
import io.github.jenziner.nisaba.ModelStatus
import io.github.jenziner.nisaba.Nav
import io.github.jenziner.nisaba.core.net.LanTools
import io.github.jenziner.nisaba.graph
import io.github.jenziner.nisaba.net.FoundDevice
import io.github.jenziner.nisaba.settings.NetworkMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Own screen for "Find Tiiny" (reachable from the TV dashboard). */
@Composable
fun FindScreen(nav: Nav) {
    Page("Find Tiiny", onBack = { nav.pop() }) { pad -> ScrollColumn(pad) { FindSection() } }
}

/**
 * Sends the Wake-on-LAN packet, then checks the connection every 5 s for about a minute so the
 * dashboard turns green once the Tiiny is up. Returns the message to show.
 */
suspend fun wakeAndWatch(g: AppGraph, mac: String): String {
    val r = g.wol.send(mac)
    val msg = r.fold({ "$it. The Tiiny can take a minute to start." }, { "Not sent: ${it.message}" })
    if (r.isSuccess) {
        repeat(12) {
            delay(5000)
            g.refreshModels("wake", quiet = true)
            delay(1500)
            if (g.modelStatus.value is ModelStatus.Ok) return "The Tiiny is awake and answering."
        }
        return "Packet sent, but the Tiiny is not answering yet. Is Wake-on-LAN switched on for it?"
    }
    return msg
}

/** Find the Tiiny on the network and wake it with Wake-on-LAN. */
@Composable
fun FindSection(showPermission: Boolean = true) {
    val g = LocalContext.current.graph
    val s by g.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var found by remember { mutableStateOf<List<FoundDevice>?>(null) }
    var applied by remember { mutableStateOf<String?>(null) }
    var macInput by remember { mutableStateOf(s.wolMac) }
    var wolMsg by remember { mutableStateOf<String?>(null) }
    var waking by remember { mutableStateOf(false) }

    SectionTitle("Find the Tiiny")
    if (showPermission) LocalNetworkPermission()
    Hint("Searches this network (Wi-Fi or Ethernet) for a Tiiny on port 80. Only a model-list request is sent, never your API key.")
    Button(enabled = !scanning, onClick = {
        scanning = true; found = null; applied = null; progress = 0
        scope.launch {
            val r = g.discovery.scan { done, total -> progress = done * 100 / total }
            found = r.sortedBy { it.host }
            scanning = false
        }
    }) { Ico(io.github.jenziner.nisaba.R.drawable.ic_search, null); Text(if (scanning) "  Searching … $progress%" else "  Search network") }

    found?.let { list ->
        if (list.isEmpty()) Hint("Nothing found. Is the Tiiny on and on the same network? On a TV set Network path to System network.")
        list.forEach { d ->
            Row(
                Modifier.fillMaxWidth().focusClickable {
                    g.settings.update {
                        it.copy(
                            baseUrl = d.baseUrl, networkMode = NetworkMode.SYSTEM,
                            wolMac = if (it.wolMac.isBlank() && d.mac != null) d.mac else it.wolMac,
                        )
                    }
                    if (macInput.isBlank() && d.mac != null) macInput = d.mac
                    g.refreshModels("find")
                    applied = "Using ${d.baseUrl} over the system network."
                }.padding(12.dp),
            ) {
                Column {
                    Text(d.host)
                    Hint(
                        (d.model?.let { "Model: $it" } ?: if (d.needsKey) "Answers, but needs an API key" else "OpenAI-compatible server") +
                            (d.mac?.let { " · $it" } ?: ""),
                    )
                }
            }
        }
        if (list.isNotEmpty()) Hint("Select a device to use it.")
    }
    applied?.let { Hint(it) }

    SectionTitle("Wake the Tiiny (Wake-on-LAN)")
    Hint("Needs the Tiiny's MAC address (shown on the device or in your router) and Wake-on-LAN switched on for it. It usually only works over Ethernet, not Wi-Fi.")
    OutlinedTextField(
        value = macInput, onValueChange = { macInput = it }, singleLine = true,
        label = { Text("MAC address, e.g. AA:BB:CC:DD:EE:FF") }, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        isError = macInput.isNotBlank() && LanTools.parseMac(macInput) == null,
    )
    Row {
        TextButton(enabled = macInput.trim() != s.wolMac, onClick = { g.settings.update { it.copy(wolMac = macInput.trim()) } }) { Text("Save") }
        Button(enabled = !waking && LanTools.parseMac(macInput) != null, onClick = {
            waking = true; wolMsg = "Sending …"
            g.settings.update { it.copy(wolMac = macInput.trim()) }
            scope.launch { wolMsg = wakeAndWatch(g, macInput); waking = false }
        }) { Ico(io.github.jenziner.nisaba.R.drawable.ic_power, null); Text("  Wake Tiiny") }
    }
    wolMsg?.let { Hint(it) }
}
