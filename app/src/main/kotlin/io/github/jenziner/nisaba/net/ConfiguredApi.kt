package io.github.jenziner.nisaba.net

import io.github.jenziner.nisaba.core.api.ApiError
import io.github.jenziner.nisaba.core.api.ChatApi
import io.github.jenziner.nisaba.core.api.ChatEvent
import io.github.jenziner.nisaba.core.api.ChatOutcome
import io.github.jenziner.nisaba.core.api.ChatRequest
import io.github.jenziner.nisaba.core.api.ModelsResult
import io.github.jenziner.nisaba.core.api.TiinyHttpApi
import io.github.jenziner.nisaba.settings.KeyVault
import io.github.jenziner.nisaba.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * [ChatApi] mit den jeweils aktuellen Einstellungen. Wählt bei jeder Anfrage
 * den Netzweg neu und meldet den tatsächlich verwendeten Weg in [lastPath].
 */
class ConfiguredApi(
    private val settings: SettingsRepository,
    private val keys: KeyVault,
    private val paths: NetworkPaths,
    private val local: LocalNetworkAccess,
    private val log: ConnectionLog,
) : ChatApi {
    private val _lastPath = MutableStateFlow<String?>(null)
    val lastPath: StateFlow<String?> = _lastPath

    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Tiiny bricht laut Drittquelle nach etwa 10 Minuten mit 504 ab.
        .readTimeout(11, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Keine stillen Wiederholungen: eine Anfrage wird höchstens einmal gesendet.
        .retryOnConnectionFailure(false)
        // 0.3: keine ruhenden Keep-alive-Verbindungen wiederverwenden. Schliesst das
        // Tiiny (oder der USB-Link) eine ruhende Verbindung, schlug die nächste Anfrage
        // auf dieser toten Verbindung fehl, weil OkHttp ohne Wiederholung nicht neu
        // verbindet. Ohne Pool wird jede Anfrage frisch verbunden (im LAN vernachlässigbar).
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    /**
     * Client für die Websuche: immer über das Systemstandardnetz (WLAN oder
     * Mobilfunk), nie an das USB-Netz zum Tiiny gebunden (dort gibt es kein Internet).
     * Ohne Authorization-Header; der Tiiny-Schlüssel geht nie an eine Suchmaschine.
     */
    val searchClient: OkHttpClient = base.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private sealed interface Prepared {
        data class Ready(val api: TiinyHttpApi, val path: String, val client: OkHttpClient) : Prepared
        data class Fail(val error: ApiError) : Prepared
    }

    private suspend fun prepare(): Prepared = withContext(Dispatchers.IO) {
        val s = settings.current
        val url = s.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: return@withContext Prepared.Fail(ApiError.Protocol("Invalid API address: ${s.baseUrl}"))
        if (local.isLocalTarget(url.host) == true && !local.granted()) {
            _lastPath.value = "not sent: local network access not allowed"
            return@withContext Prepared.Fail(ApiError.Connection(LOCAL_DENIED))
        }
        val sel = paths.select(s.networkMode, url.host)
        _lastPath.value = sel.description
        val client = when (sel) {
            is PathSelection.Unavailable -> return@withContext Prepared.Fail(
                ApiError.Connection("${sel.reason}. Deliberately no fallback to Wi-Fi."),
            )
            is PathSelection.System -> base
            is PathSelection.Bound -> base.newBuilder()
                .socketFactory(sel.info.network.socketFactory)
                .dns(object : Dns {
                    override fun lookup(hostname: String) = sel.info.network.getAllByName(hostname).toList()
                })
                .build()
        }
        Prepared.Ready(TiinyHttpApi(url.toString(), { keys.get() }, client), sel.description, client)
    }

    /**
     * Diagnose: eine einzelne GET-Anfrage mit vollständigem Ablaufprotokoll
     * (Netzweg, Kopfzeilen, Verbindungsphasen, Antwort). Der Schlüssel wird nie
     * ausgegeben, nur ob er gesendet wurde und wie lang er ist.
     */
    suspend fun trace(target: String, sendKey: Boolean): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        fun line(t: String) { out.appendLine(t) }
        val url = target.toHttpUrlOrNull() ?: return@withContext "Invalid address: $target"
        val t0 = System.nanoTime()
        fun ms() = "${(System.nanoTime() - t0) / 1_000_000} ms"
        line("GET $url")
        val isLocal = local.isLocalTarget(url.host)
        line("Target in local network: ${when (isLocal) { true -> "yes"; false -> "no"; null -> "unknown (name)" }}")
        line("Permission local network: ${if (!local.enforced) "not needed (before Android 17)" else if (local.granted()) "granted" else "NOT granted"}")
        if (isLocal == true && !local.granted()) {
            line("Not sent: $LOCAL_DENIED")
            return@withContext out.toString().trimEnd()
        }
        val sel = paths.select(settings.current.networkMode, url.host)
        line("Network path (${settings.current.networkMode}): ${sel.description}")
        val client = when (sel) {
            is PathSelection.Unavailable -> { line("Not sent: ${sel.reason}"); return@withContext out.toString().trimEnd() }
            is PathSelection.System -> base
            is PathSelection.Bound -> base.newBuilder()
                .socketFactory(sel.info.network.socketFactory)
                .dns(object : Dns { override fun lookup(hostname: String) = sel.info.network.getAllByName(hostname).toList() })
                .build()
        }.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
                    line("[${ms()}] Address: ${inetAddressList.joinToString { it.hostAddress ?: "?" }}")
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
                    line("[${ms()}] Connecting to ${inetSocketAddress.address?.hostAddress}:${inetSocketAddress.port} (Proxy: ${proxy.type()})")
                override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
                    line("[${ms()}] Connected (${protocol ?: "?"})")
                override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) =
                    line("[${ms()}] Connect failed: ${ioe.javaClass.simpleName}: ${ioe.message}")
                override fun connectionAcquired(call: Call, connection: Connection) {
                    val s = connection.socket()
                    line("[${ms()}] Socket ${s.localAddress?.hostAddress}:${s.localPort} → ${s.inetAddress?.hostAddress}:${s.port}")
                }
                override fun requestHeadersEnd(call: Call, request: Request) = line("[${ms()}] Request sent")
                override fun responseHeadersStart(call: Call) = line("[${ms()}] Response starts")
            })
            .build()
        val key = if (sendKey) keys.get()?.takeIf { it.isNotBlank() } else null
        val req = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (key != null) header("Authorization", "Bearer $key")
        }.build()
        line("Headers: Accept: application/json" + if (key != null) "; Authorization: Bearer ••• (${key.length} chars)" else "; no Authorization")
        try {
            client.newCall(req).execute().use { r ->
                line("[${ms()}] HTTP ${r.code} ${r.message}".trimEnd())
                r.headers.forEach { (n, v) -> if (!n.equals("set-cookie", true)) line("  $n: $v") }
                val body = r.body.source().let { src -> src.request(2048); src.buffer.clone().readUtf8() }
                line("Body (start): " + body.take(600).replace("\n", " "))
            }
        } catch (e: IOException) {
            line("[${ms()}] ERROR: ${e.javaClass.simpleName}: ${e.message}")
            hint(e.message.orEmpty())?.let { line("Hint: $it") }
        }
        out.toString().trimEnd()
    }

    private fun hint(msg: String): String? = when {
        "EPERM" in msg -> "The app may not open connections. On GrapheneOS check the \"Network\" permission for Tiiny Chat."
        "after" in msg && "ms" in msg -> "Timeout while connecting. Check: \"Local network\" permission, network path, reachability in the browser."
        else -> null
    }

    /**
     * Modellliste. Bei einem Verbindungsfehler wird genau einmal nach einer
     * Sekunde wiederholt (GET, ohne Nebenwirkung); beides steht im Protokoll.
     */
    override suspend fun listModels(): ModelsResult = listModels(reason = "check")

    suspend fun listModels(reason: String): ModelsResult {
        val first = listOnce(reason)
        if (first !is ModelsResult.Err || !(first.error is ApiError.Connection || first.error is ApiError.Timeout)) return first
        if ((first.error as? ApiError.Connection)?.detail == LOCAL_DENIED) return first
        delay(1000)
        return listOnce("$reason, retry")
    }

    private suspend fun listOnce(reason: String): ModelsResult {
        val t0 = System.nanoTime()
        val r = when (val p = prepare()) {
            is Prepared.Fail -> ModelsResult.Err(p.error)
            is Prepared.Ready -> p.api.listModels()
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        log.add(
            "GET /models ($reason) " + when (r) {
                is ModelsResult.Ok -> "OK ${r.models.size} model(s)"
                is ModelsResult.Err -> "ERROR ${short(r.error)}"
            } + " · $ms ms · ${_lastPath.value ?: "-"}",
        )
        return r
    }

    override suspend fun chat(request: ChatRequest, onEvent: (ChatEvent) -> Unit): ChatOutcome {
        val t0 = System.nanoTime()
        val r = when (val p = prepare()) {
            is Prepared.Fail -> ChatOutcome.Failed(p.error)
            is Prepared.Ready -> p.api.chat(request, onEvent)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        log.add(
            "POST /chat/completions (stream ${request.stream}) " + when (r) {
                is ChatOutcome.Complete -> "OK"
                is ChatOutcome.Incomplete -> "INCOMPLETE ${r.reason}"
                is ChatOutcome.Failed -> "ERROR ${short(r.error)}"
            } + " · $ms ms · ${_lastPath.value ?: "-"}",
        )
        return r
    }

    private fun short(e: ApiError): String = when (e) {
        is ApiError.Connection -> "connection: ${e.detail}"
        is ApiError.Timeout -> "timeout: ${e.detail}"
        is ApiError.Auth -> "auth HTTP ${e.code}"
        is ApiError.Gateway -> "gateway HTTP ${e.code}"
        is ApiError.ModelUnavailable -> "model unavailable"
        is ApiError.ContextLimit -> "context limit"
        is ApiError.Server -> "server HTTP ${e.code}"
        is ApiError.Protocol -> "protocol: ${e.detail}"
    }

    companion object {
        const val LOCAL_DENIED =
            "Android does not allow Tiiny Chat to access the local network (permission \"Local network\" / \"Nearby devices\" missing)."
    }
}
