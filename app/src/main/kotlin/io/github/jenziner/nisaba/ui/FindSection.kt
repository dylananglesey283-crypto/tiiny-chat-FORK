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
 * Calls the saved smart-plug URL, then checks the connection every 5 s for about two minutes so
 * the dashboard turns green once the Tiiny has booted. Returns the message to show.
 */
suspend fun powerOnAndWatch(g: AppGraph, url: String): String {
    val r = g.plug.turnOn(url)
    if (r.isFailure) return "Not switched on: ${r.exceptionOrNull()?.message}"
    repeat(24) {
        delay(5000)
        g.refreshModels("power-on", quiet = true)
        delay(1500)
        if (g.modelStatus.value is ModelStatus.Ok) return "The Tiiny is on and answering."
    }
    return "The plug switched on, but the Tiiny is not answering yet. It may still be booting."
}

/** Find the Tiiny on the network and power it on through a smart plug. */
@Composable
fun FindSection(showPermission: Boolean = true) {
    val g = LocalContext.current.graph
    val s by g.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var found by remember { mutableStateOf<List<FoundDevice>?>(null) }
    var applied by remember { mutableStateOf<String?>(null) }
    var urlInput by remember { mutableStateOf(s.powerOnUrl) }
    var powerMsg by remember { mutableStateOf<String?>(null) }
    var powering by remember { mutableStateOf(false) }

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
        if (list.isEmpty()) Hint("Nothing found. Is the Tiiny on and on the same Wi-Fi? On a TV set Network path to System network.")
        list.forEach { d ->
            Row(
                Modifier.fillMaxWidth().focusClickable {
                    g.settings.update { it.copy(baseUrl = d.baseUrl, networkMode = NetworkMode.SYSTEM) }
                    g.refreshModels("find")
                    applied = "Using ${d.baseUrl} over the system network."
                }.padding(12.dp),
            ) {
                Column {
                    Text(d.host)
                    Hint(d.model?.let { "Model: $it" } ?: if (d.needsKey) "Answers, but needs an API key" else "OpenAI-compatible server")
                }
            }
        }
        if (list.isNotEmpty()) Hint("Select a device to use it.")
    }
    applied?.let { Hint(it) }

    SectionTitle("Power on the Tiiny (smart plug)")
    Hint("The Tiiny has no Ethernet, so Wake-on-LAN is not possible. Plug its charger into a Wi-Fi smart plug with a local web API and save the plug's \"turn on\" address here. The Tiiny boots when it gets power.")
    Hint("Shelly Plus / Gen2+ plug: http://<plug-ip>/rpc/Switch.Set?id=0&on=true   ·   Shelly Gen1: http://<plug-ip>/relay/0?turn=on   ·   Tasmota: http://<plug-ip>/cm?cmnd=Power%20On")
    OutlinedTextField(
        value = urlInput, onValueChange = { urlInput = it }, singleLine = true,
        label = { Text("Power-on address (http://…)") }, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        isError = urlInput.isNotBlank() && !LanTools.isHttpUrl(urlInput),
    )
    Row {
        TextButton(enabled = urlInput.trim() != s.powerOnUrl, onClick = { g.settings.update { it.copy(powerOnUrl = urlInput.trim()) } }) { Text("Save") }
        Button(enabled = !powering && LanTools.isHttpUrl(urlInput), onClick = {
            powering = true; powerMsg = "Sending …"
            g.settings.update { it.copy(powerOnUrl = urlInput.trim()) }
            scope.launch { powerMsg = powerOnAndWatch(g, urlInput); powering = false }
        }) { Ico(io.github.jenziner.nisaba.R.drawable.ic_power, null); Text("  Power on") }
    }
    Hint("Only switches ON. Shut the Tiiny down on the device itself; cutting its power can damage its storage.")
    powerMsg?.let { Hint(it) }
}
