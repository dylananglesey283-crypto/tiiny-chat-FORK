package io.github.jenziner.nisaba

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.jenziner.nisaba.core.api.ThinkingInfo
import io.github.jenziner.nisaba.core.chat.ChatOptions
import io.github.jenziner.nisaba.core.chat.ReasoningResolution
import io.github.jenziner.nisaba.core.chat.ReasoningResolver
import io.github.jenziner.nisaba.core.format.ProjectSettings
import io.github.jenziner.nisaba.core.format.ReasoningSetting
import io.github.jenziner.nisaba.core.search.SearchOutcome
import io.github.jenziner.nisaba.core.search.HtmlSearch
import io.github.jenziner.nisaba.core.chat.DocumentBudget
import io.github.jenziner.nisaba.core.format.Message
import io.github.jenziner.nisaba.docs.Documents
import io.github.jenziner.nisaba.search.EngineChoice
import io.github.jenziner.nisaba.search.EngineChoices
import io.github.jenziner.nisaba.core.search.WebSearch
import io.github.jenziner.nisaba.net.ConnectionLog
import io.github.jenziner.nisaba.core.Env
import io.github.jenziner.nisaba.core.api.ApiError
import io.github.jenziner.nisaba.core.api.ModelInfo
import io.github.jenziner.nisaba.core.api.ModelsResult
import io.github.jenziner.nisaba.core.chat.ChatSession
import io.github.jenziner.nisaba.core.store.ChatRef
import io.github.jenziner.nisaba.core.store.OpenedChat
import io.github.jenziner.nisaba.core.store.ProjectRef
import io.github.jenziner.nisaba.core.store.ProjectStore
import io.github.jenziner.nisaba.net.ConfiguredApi
import io.github.jenziner.nisaba.net.LocalNetworkAccess
import io.github.jenziner.nisaba.net.NetworkPaths
import io.github.jenziner.nisaba.settings.KeyVault
import io.github.jenziner.nisaba.settings.SettingsRepository
import io.github.jenziner.nisaba.core.storage.FileStorageBackend
import io.github.jenziner.nisaba.storage.SafStorageBackend
import io.github.jenziner.nisaba.core.storage.StorageBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class NisabaApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        io.github.jenziner.nisaba.background.AnswerService.createChannel(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as NisabaApp).graph

/** Zustand der Modellverbindung; getrennt vom Speicherzustand. */
sealed interface ModelStatus {
    data object Unknown : ModelStatus
    data object Checking : ModelStatus
    data class Ok(val models: List<ModelInfo>, val path: String?) : ModelStatus
    data class Err(val error: ApiError, val path: String?) : ModelStatus
}

/** Eine offene Chat-Sitzung mit Entwurf und laufender Anfrage. */
class OpenSession(val key: String, val session: ChatSession, val project: ProjectRef?) {
    val draft = MutableStateFlow("")

    /** Kurzer Hinweis für die Oberfläche, z. B. warum nicht gesendet wurde. */
    val notice = MutableStateFlow<String?>(null)
    @Volatile var job: Job? = null
}

/**
 * Prozessweiter Zustand. Chat-Sitzungen leben nur im Arbeitsspeicher und
 * überstehen Bildschirmwechsel, nicht aber das Prozessende. Gespeichert wird
 * ausschliesslich über den Projekt-Speicher im gewählten Ordner.
 */
class AppGraph(private val app: Application) {
    val env: Env = Env.System
    val settings = SettingsRepository(app)
    val keys = KeyVault(app)
    val paths = NetworkPaths(app)
    val localNet = LocalNetworkAccess(app)
    val log = ConnectionLog()
    val api = ConfiguredApi(settings, keys, paths, localNet, log)

    /** Für Sendevorgänge: überdauert Bildschirmwechsel. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var storeFor: Pair<String, Pair<StorageBackend, ProjectStore>>? = null

    /** Projektdokumente (Hochladen, Textgewinnung, Laden für Anfragen). */
    val documents = Documents(app) { storage()?.second }

    /** Speicher des aktuell gewählten Ordners, oder null. */
    fun storage(): Pair<StorageBackend, ProjectStore>? {
        val uri = settings.current.folder ?: return null
        val key = uri.toString()
        storeFor?.let { if (it.first == key) return it.second }
        // file: = app-private folder (Android TV without a folder picker); otherwise a SAF tree.
        val backend: StorageBackend =
            if (uri.scheme == "file") FileStorageBackend(java.io.File(uri.path ?: return null))
            else SafStorageBackend(app.contentResolver, uri)
        val pair = backend to ProjectStore(backend, env)
        storeFor = key to pair
        return pair
    }

    private val _model = MutableStateFlow<ModelStatus>(ModelStatus.Unknown)
    val modelStatus: StateFlow<ModelStatus> = _model

    private var refreshing = false

    /**
     * Prüft die Verbindung (GET /models). [quiet] = Hintergrundprüfung:
     * der angezeigte Zustand wechselt nicht auf „wird geprüft“.
     */
    fun refreshModels(reason: String = "manual", quiet: Boolean = false) {
        if (refreshing) return
        refreshing = true
        if (!quiet) _model.value = ModelStatus.Checking
        scope.launch {
            val r = try { api.listModels(reason) } finally { refreshing = false }
            _model.value = when (r) {
                is ModelsResult.Ok -> {
                    // Ist noch kein Modell gewählt und genau eines verfügbar, wird es übernommen.
                    if (settings.current.model == null && r.models.size == 1) {
                        settings.update { it.copy(model = r.models.single().id) }
                    }
                    ModelStatus.Ok(r.models, api.lastPath.value)
                }
                is ModelsResult.Err -> ModelStatus.Err(r.error, api.lastPath.value)
            }
        }
    }

    // ------------------------------------------------- Verbindungsüberwachung

    private var monitor: Job? = null

    /**
     * Solange die App sichtbar ist: regelmässige Hintergrundprüfung (60 s wenn
     * verbunden, bei Fehler 5/10/20/40/60 s) und sofortige Prüfung, wenn Android
     * eine Netzänderung meldet. Gestartet/gestoppt in MainActivity.onStart/onStop.
     */
    fun startMonitor() {
        if (monitor?.isActive == true) return
        log.add("monitor start")
        monitor = scope.launch {
            var failures = 0
            while (true) {
                val wait = if (_model.value is ModelStatus.Err) {
                    listOf(5, 10, 20, 40, 60)[failures.coerceAtMost(4)].also { failures++ }
                } else {
                    failures = 0; 60
                }
                delay(wait * 1000L)
                if (runningCount() == 0) refreshModels("monitor", quiet = true)
            }
        }
    }

    fun stopMonitor() {
        monitor?.cancel(); monitor = null
        log.add("monitor stop")
    }

    init {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .clearCapabilities()
            .build()
        runCatching {
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = netEvent("available", network, cm)
                override fun onLost(network: Network) = netEvent("lost", network, cm)
            })
        }.onFailure { log.add("network callback not registered: ${it.javaClass.simpleName}") }
    }

    private fun netEvent(what: String, n: Network, cm: ConnectivityManager) {
        val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull()
        val kind = when {
            caps == null -> "?"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "USB"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "other"
        }
        val iface = runCatching { cm.getLinkProperties(n)?.interfaceName }.getOrNull()
        log.add("network $what: $kind${iface?.let { " ($it)" } ?: ""}")
        scope.launch {
            delay(1500) // Netz kurz stabilisieren lassen
            if (monitor?.isActive == true && runningCount() == 0) refreshModels("network $what", quiet = true)
        }
    }

    fun thinkingInfo(): ThinkingInfo? {
        val m = settings.current.model ?: return null
        return (modelStatus.value as? ModelStatus.Ok)?.models?.firstOrNull { it.id == m }?.thinking
    }

    fun resolveReasoning(setting: ReasoningSetting): ReasoningResolution =
        ReasoningResolver.resolve(setting, thinkingInfo())

    // ------------------------------------------------------- Projekt-Einstellungen

    /** Zuletzt gelesene Einstellungen je Projekt-ID; gilt für alle offenen Chats des Projekts. */
    private val projectSettings = mutableMapOf<String, ProjectSettings>()

    fun noteProject(ref: ProjectRef) { projectSettings[ref.project.projectId] = ref.project.settings }

    fun settingsFor(project: ProjectRef?): Pair<ReasoningSetting, Boolean> {
        if (project == null) return settings.current.reasoning to settings.current.webSearch
        val ps = projectSettings[project.project.projectId] ?: project.project.settings
        return ps.reasoning to ps.webSearch
    }

    /** Aktuell wirksame Suchmaschine mit Begründung (Einstellung oder Standardbrowser). */
    fun searchEngine(): EngineChoice = EngineChoices.resolve(app, settings.current.searchEngine)

    fun webSearch(): WebSearch {
        val choice = searchEngine()
        val lang = app.resources.configuration.locales.let { l ->
            (0 until l.size()).map { l[it].toLanguageTag() }.distinct().take(3)
                .mapIndexed { i, t -> if (i == 0) t else "$t;q=${"%.1f".format(java.util.Locale.ROOT, 0.9 - i * 0.1)}" }
                .joinToString(",").ifEmpty { "en" }
        }
        val search = HtmlSearch(choice.engine, api.searchClient, acceptLanguage = lang)
        return object : WebSearch {
            override suspend fun search(query: String): SearchOutcome {
                val t0 = System.nanoTime()
                val r = search.search(query)
                log.add(
                    "Web search ${choice.engine.label} " +
                        when (r) { is SearchOutcome.Ok -> "OK ${r.results.size} result(s)"; is SearchOutcome.Failed -> "ERROR ${r.detail}" } +
                        " · ${(System.nanoTime() - t0) / 1_000_000} ms",
                )
                return r
            }
        }
    }

    /**
     * Wirksame Dokumentgrenze für ein Projekt: halbe Kontextgrösse, wenn das Modell
     * sie meldet; sonst Projektwert; sonst App-Wert.
     */
    fun documentLimit(project: ProjectRef): Int {
        val ps = projectSettings[project.project.projectId] ?: project.project.settings
        return DocumentBudget.limit(contextWindow(), ps.documentLimitTokens, settings.current.documentLimitTokens)
    }

    private fun optionsFor(project: ProjectRef?): () -> ChatOptions = {
        val (reasoning, web) = settingsFor(project)
        ChatOptions(
            thinking = resolveReasoning(reasoning).params,
            webSearch = if (web) webSearch() else null,
            documentLimitTokens = project?.let { documentLimit(it) },
        )
    }

    fun contextWindow(): Int? {
        val m = settings.current.model ?: return null
        return (modelStatus.value as? ModelStatus.Ok)?.models?.firstOrNull { it.id == m }?.contextWindow
    }

    // ------------------------------------------------------------ Sitzungen

    private val sessions = mutableMapOf<String, OpenSession>()

    fun session(key: String): OpenSession? = sessions[key]

    private val _adHoc = MutableStateFlow<OpenSession?>(null)
    val adHocFlow: StateFlow<OpenSession?> = _adHoc
    val adHoc: OpenSession? get() = _adHoc.value

    private val appInstructions = { settings.current.appInstructions }
    private val streaming = { settings.current.streaming }

    fun startAdHoc(): OpenSession = adHoc ?: OpenSession(
        "adhoc-" + UUID.randomUUID(),
        ChatSession.adHoc(env, api, appInstructions, streaming, options = optionsFor(null)), null,
    ).also { _adHoc.value = it; sessions[it.key] = it }

    fun discardAdHoc() {
        adHoc?.job?.cancel()
        adHoc?.let { sessions.remove(it.key) }
        _adHoc.value = null
    }

    /** Verwirft den Ad-hoc-Chat und beginnt sofort einen neuen. */
    fun renewAdHoc(): OpenSession { discardAdHoc(); return startAdHoc() }

    fun newProjectChat(store: ProjectStore, project: ProjectRef): OpenSession {
        val key = "neu-" + UUID.randomUUID()
        noteProject(project)
        val s = ChatSession.newProjectChat(
            env, api, store, project, appInstructions, streaming, options = optionsFor(project),
            documents = documents.sourceFor(project),
        )
        return OpenSession(key, s, project).also { sessions[key] = it }
    }

    /** Bereits offene Sitzung zu einem gespeicherten Chat (z. B. mit laufender Antwort). */
    fun existingFor(ref: ChatRef): OpenSession? =
        sessions.values.firstOrNull { it.session.chatRef?.chat?.chatId == ref.chat.chatId }

    fun openProjectChat(store: ProjectStore, opened: OpenedChat): OpenSession {
        existingFor(opened.ref)?.let { return it }
        val key = "chat-" + opened.ref.chat.chatId
        val s = ChatSession.openProjectChat(
            env, api, store, opened, appInstructions, streaming, options = optionsFor(opened.ref.project),
            documents = documents.sourceFor(opened.ref.project),
        )
        return OpenSession(key, s, opened.ref.project).also { sessions[key] = it }
    }

    /**
     * Speichert den aktuellen Ad-hoc-Chat als neuen Chat im Projekt [project]
     * (ohne Projektanweisungen) und beginnt auf der Startseite einen neuen
     * Ad-hoc-Chat. Liefert die Sitzung des gespeicherten Chats oder einen Fehlertext.
     */
    suspend fun saveAdHocTo(store: ProjectStore, project: ProjectRef, messages: List<Message>): Pair<OpenSession?, String?> {
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.importAdHoc(project, messages) }
        return when (r) {
            is ProjectStore.BeginResult.Started -> {
                val opened = openProjectChat(store, r.opened)
                renewAdHoc()
                opened to null
            }
            is ProjectStore.BeginResult.Failed -> null to io.github.jenziner.nisaba.ui.Texts.store(r.failure)
            is ProjectStore.BeginResult.SoulUnreadable -> null to r.soul.detail
        }
    }

    /** Leere, nie gesendete Projekt-Chats werden beim Verlassen verworfen. */
    fun releaseIfEmpty(key: String) {
        val s = sessions[key] ?: return
        if (s.project != null && s.session.chatRef == null && s.job?.isActive != true) {
            sessions.remove(key)
        }
    }

    fun forget(ref: ChatRef) {
        sessions.entries.removeAll { it.value.session.chatRef?.chat?.chatId == ref.chat.chatId }
    }

    /** Nach dem Löschen eines Projekts: alle Sitzungen dieses Projekts schliessen. */
    fun forgetProject(projectId: String) {
        sessions.entries.removeAll { it.value.project?.project?.projectId == projectId }
        projectSettings.remove(projectId)
    }

    fun runningFor(projectId: String): Int =
        sessions.values.count { it.project?.project?.projectId == projectId && it.job?.isActive == true }

    fun runningCount(): Int = sessions.values.count { it.job?.isActive == true }

    // ------------------------------------------------------ Background (1.0)

    /** Notification permission already requested in this process. */
    var askedNotifications = false

    private val _running = MutableStateFlow(0)

    /** Number of running requests; drives [io.github.jenziner.nisaba.background.AnswerService]. */
    val running: StateFlow<Int> = _running

    /**
     * Registers a request started by the user and keeps the app running in the
     * background until it ends (foreground service with notification).
     */
    fun runProtected(job: Job) {
        _running.update { it + 1 }
        job.invokeOnCompletion { _running.update { n -> (n - 1).coerceAtLeast(0) } }
        io.github.jenziner.nisaba.background.AnswerService.start(app)
    }

    /** "Stop" in the notification: cancels all running requests (partial answers are kept as usual). */
    fun cancelAllRequests() {
        sessions.values.forEach { it.job?.cancel() }
    }
}
