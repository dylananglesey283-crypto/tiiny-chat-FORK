package io.github.jenziner.nisaba.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.jenziner.nisaba.BuildConfig
import io.github.jenziner.nisaba.Nav
import io.github.jenziner.nisaba.core.api.ModelsResult
import io.github.jenziner.nisaba.core.diagnostics.StorageProbe
import io.github.jenziner.nisaba.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun DiagnosticsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var netText by remember { mutableStateOf("") }
    var apiText by remember { mutableStateOf("Not checked yet.") }
    var probeText by remember { mutableStateOf("Not checked yet.") }
    val logLines by g.log.lines.collectAsState()
    var logCopied by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        val s = g.settings.current
        val host = s.baseUrl.trim().toHttpUrlOrNull()?.host
        netText = withContext(Dispatchers.IO) {
            val all = g.paths.list(host)
            buildString {
                appendLine("Base address: ${s.baseUrl}")
                appendLine("Network path setting: ${s.networkMode}")
                appendLine("Chosen path: ${g.paths.select(s.networkMode, host).description}")
                appendLine("Networks (${all.size}):")
                all.forEach { appendLine("  – ${it.label}") }
                if (all.isEmpty()) {
                    appendLine("  (none)")
                    appendLine("Hint: Android reports no network. On GrapheneOS check the \"Network\" permission for Tiiny Chat.")
                }
            }.trimEnd()
        }
    }

    val report = buildString {
        appendLine("Tiiny Chat ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · API ${Build.VERSION.SDK_INT}")
        appendLine("API key stored: ${if (g.keys.hasKey()) "yes" else "no"}")
        appendLine("Streaming: ${if (g.settings.current.streaming) "on" else "off"}")
        appendLine("Web search: ${g.searchEngine().explanation}")
        appendLine("Default browser: ${io.github.jenziner.nisaba.search.EngineChoices.defaultBrowser(ctx) ?: "none / not reported"}")
        appendLine("Local network permission: ${if (!g.localNet.enforced) "not needed" else if (g.localNet.granted()) "granted" else "NOT granted"}")
        appendLine(); appendLine("[Network]"); appendLine(netText)
        appendLine(); appendLine("[Model server]"); appendLine(apiText)
        appendLine(); appendLine("[Storage]"); appendLine(probeText)
        appendLine(); appendLine("[Connection log]"); append(g.log.asText())
    }

    Page("Diagnostics", onBack = { nav.pop() }) { pad ->
        ScrollColumn(pad) {
            SectionTitle("Network")
            SelectionContainer { Text(netText) }
            OutlinedButton(onClick = { refresh++ }) { Text("Reread") }

            SectionTitle("Model server")
            LocalNetworkPermission(always = true)
            Hint("Shows each request in detail: network path, addresses, connection phases and answer. The key is never shown.")
            SelectionContainer { Text(apiText) }
            Button(enabled = !running, onClick = {
                running = true; apiText = "Checking …"
                scope.launch {
                    val s = g.settings.current
                    val base = s.baseUrl.trim().trimEnd('/')
                    val host = base.toHttpUrlOrNull()?.host
                    val parts = mutableListOf<String>()
                    // Vergleich ohne Schlüssel: Gerätebeschreibung, laut device.json des Tiiny auf Port 39218.
                    if (host != null) parts += "1) Device description (without key)\n" +
                        g.api.trace("http://${if (':' in host) "[$host]" else host}:39218/device.json", sendKey = false)
                    parts += "2) Model list (with key)\n" + g.api.trace("$base/models", sendKey = true)
                    val t0 = System.nanoTime()
                    val r = g.api.listModels("diagnostics")
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    parts += "3) Model list as in chat · $ms ms\n" + when (r) {
                        is ModelsResult.Ok -> "OK, ${r.models.size} model(s): " + r.models.joinToString { it.id + (it.contextWindow?.let { c -> " (context $c)" } ?: "") + " · thinking: " + capabilities(it.thinking) }
                        is ModelsResult.Err -> "ERROR – ${Texts.api(r.error)}"
                    }
                    apiText = parts.joinToString("\n\n")
                    running = false
                    refresh++
                    if (r is ModelsResult.Ok) g.refreshModels("diagnostics")
                }
            }) { Text("Check model server") }

            SectionTitle("Project storage")
            Hint("The check creates its own test folder and removes it again. Existing data is not changed.")
            SelectionContainer { Text(probeText) }
            Button(enabled = !running && g.storage() != null, onClick = {
                running = true; probeText = "Checking …"
                scope.launch {
                    val st = g.storage()
                    probeText = if (st == null) "No folder selected." else
                        withContext(Dispatchers.IO) { StorageProbe(st.first, g.env).run().asText() }.trimEnd()
                    running = false
                }
            }) { Text("Check storage") }

            SectionTitle("Connection log")
            Hint("Last ${logLines.size} events since the app started: checks, requests, network changes. No key, no chat content.")
            SelectionContainer { Text(logLines.takeLast(40).joinToString("\n").ifEmpty { "(empty)" }, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
            Row {
                OutlinedButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Tiiny Chat connection log", g.log.asText()))
                    logCopied = true
                }) { Text("Copy log") }
            }
            if (logCopied) Hint("Copied.")

            SectionTitle("Report")
            Row {
                Button(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Tiiny Chat diagnostics", report))
                    copied = true
                }) { Text("Copy report") }
            }
            if (copied) Hint("Copied. The report contains no API key.")
        }
    }
}
