package io.github.jenziner.nisaba.settings

import android.content.Context
import android.net.Uri
import io.github.jenziner.nisaba.core.format.ReasoningMode
import io.github.jenziner.nisaba.core.format.ReasoningSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Netzweg zum Modellserver. Kein stiller Wechsel zwischen den Wegen. */
enum class NetworkMode {
    /** Nur ein USB-/Ethernet-Netz verwenden; fehlt es, schlägt die Anfrage fehl. */
    USB,

    /** Vom System gewähltes Standardnetz (in der Regel WLAN). */
    SYSTEM,
}

data class AppSettings(
    val baseUrl: String,
    val model: String?,
    val appInstructions: String,
    val streaming: Boolean,
    val networkMode: NetworkMode,
    val folder: Uri?,
    /** Reasoning im Ad-hoc-Chat (Projekte haben eigene Einstellungen in project.json). */
    val reasoning: ReasoningSetting = ReasoningSetting.DEFAULT,
    /** Websuche im Ad-hoc-Chat. */
    val webSearch: Boolean = false,
    /** Suchmaschine: [SEARCH_AUTO] oder eine ID aus SearchEngine (z. B. "duckduckgo"). */
    val searchEngine: String = SEARCH_AUTO,
    /**
     * Standard-Dokumentgrenze in geschätzten Tokens für Projekte ohne eigenen Wert.
     * Gilt nur, wenn das Modell keine Kontextgrösse meldet.
     */
    val documentLimitTokens: Int = DEFAULT_DOCUMENT_LIMIT,
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://172.19.178.109/v1"
        const val SEARCH_AUTO = "auto"
        const val DEFAULT_DOCUMENT_LIMIT = 100_000
    }
}

/**
 * App-Einstellungen im app-privaten Speicher. Hier liegen nur Konfiguration und
 * die URI der Ordnerfreigabe, keine Projektdaten. Der API-Schlüssel liegt
 * verschlüsselt separat (siehe [KeyVault]).
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("einstellungen", Context.MODE_PRIVATE).also {
        // 0.4: SearXNG ist ausgebaut; eine in 0.3 eingetragene Adresse wird entfernt.
        if (it.contains(K_SEARX_OLD)) it.edit().remove(K_SEARX_OLD).apply()
    }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private fun load() = AppSettings(
        baseUrl = prefs.getString(K_BASE, null) ?: AppSettings.DEFAULT_BASE_URL,
        model = prefs.getString(K_MODEL, null),
        appInstructions = prefs.getString(K_APP, null) ?: "",
        streaming = prefs.getBoolean(K_STREAM, false),
        networkMode = prefs.getString(K_NET, null)?.let { runCatching { NetworkMode.valueOf(it) }.getOrNull() } ?: NetworkMode.USB,
        folder = prefs.getString(K_FOLDER, null)?.let(Uri::parse),
        reasoning = ReasoningSetting(
            mode = prefs.getString(K_R_MODE, null)?.let { m -> ReasoningMode.entries.firstOrNull { it.name == m } } ?: ReasoningMode.MODEL_DEFAULT,
            effort = prefs.getString(K_R_EFFORT, null),
        ),
        webSearch = prefs.getBoolean(K_WEB, false),
        searchEngine = prefs.getString(K_ENGINE, null) ?: AppSettings.SEARCH_AUTO,
        documentLimitTokens = prefs.getInt(K_DOC_LIMIT, AppSettings.DEFAULT_DOCUMENT_LIMIT).takeIf { it > 0 } ?: AppSettings.DEFAULT_DOCUMENT_LIMIT,
    )

    fun update(f: (AppSettings) -> AppSettings) {
        val n = f(_state.value)
        prefs.edit()
            .putString(K_BASE, n.baseUrl)
            .putString(K_MODEL, n.model)
            .putString(K_APP, n.appInstructions)
            .putBoolean(K_STREAM, n.streaming)
            .putString(K_NET, n.networkMode.name)
            .putString(K_FOLDER, n.folder?.toString())
            .putString(K_R_MODE, n.reasoning.mode.name)
            .putString(K_R_EFFORT, n.reasoning.effort)
            .putBoolean(K_WEB, n.webSearch)
            .putString(K_ENGINE, n.searchEngine)
            .putInt(K_DOC_LIMIT, n.documentLimitTokens)
            .apply()
        _state.value = n
    }

    private companion object {
        const val K_BASE = "basisadresse"
        const val K_MODEL = "modell"
        const val K_APP = "app_anweisungen"
        const val K_STREAM = "streaming"
        const val K_NET = "netzweg"
        const val K_FOLDER = "projektordner"
        const val K_R_MODE = "reasoning_modus"
        const val K_R_EFFORT = "reasoning_stufe"
        const val K_WEB = "websuche"
        const val K_SEARX_OLD = "searxng_adresse"
        const val K_ENGINE = "suchmaschine"
        const val K_DOC_LIMIT = "dokumentgrenze_tokens"
    }
}
