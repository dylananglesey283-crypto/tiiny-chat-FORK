package io.github.jenziner.nisaba.core.api

/**
 * Modellschnittstelle. Umgesetzt ist nur das auf dem Gerät des Nutzers
 * nachgewiesene OpenAI-kompatible Format:
 *  - GET  {base}/models               (bestätigt, Bearer-Token)
 *  - POST {base}/chat/completions     (bestätigt mit stream=false)
 * Streaming (stream=true, Server-Sent Events) ist laut Drittquelle möglich,
 * auf dem Gerät des Nutzers aber noch nicht verifiziert.
 */
data class ChatTurn(val role: String, val content: String)

/**
 * Reasoning-Steuerung laut Tiiny-Entwicklerdoku (dev.tiiny.ai, Inference):
 * `thinking_enabled` für Modelle mit umschaltbarem Denken, `reasoning_effort`
 * mit einer vom Modell gemeldeten Stufe. `null` = Feld wird nicht gesendet.
 */
data class ThinkingParams(val enabled: Boolean?, val effort: String?)

data class ChatRequest(
    val model: String,
    val messages: List<ChatTurn>,
    val stream: Boolean,
    val thinking: ThinkingParams? = null,
)

/**
 * Denk-Fähigkeiten eines Modells aus `GET /models` (Feld `thinking`).
 * Seit TiinyOS 0.1.36 laut Drittquelle ein Objekt; ältere Fassungen melden
 * nur einen Wahrheitswert (dann sind die übrigen Felder `null`).
 */
data class ThinkingInfo(
    val supported: Boolean?,
    val toggleable: Boolean?,
    val enabledByDefault: Boolean?,
    val levels: List<String>,
    val defaultLevel: String?,
)

data class ModelInfo(
    val id: String,
    /** Nur falls vom Server gemeldet (Feld laut Drittquelle, nicht verifiziert). */
    val contextWindow: Int? = null,
    val supportsChat: Boolean? = null,
    val thinking: ThinkingInfo? = null,
)

sealed interface ApiError {
    /** Keine Verbindung (Adresse falsch, Netz weg, Tiiny aus). */
    data class Connection(val detail: String) : ApiError

    /** Zeitüberschreitung beim Verbinden oder Lesen. */
    data class Timeout(val detail: String) : ApiError

    /** 401/403: API-Key fehlt oder ist falsch. */
    data class Auth(val code: Int, val detail: String) : ApiError

    /** 502: bei Tiiny beobachtet, solange das Gerät nach einem Neustart gesperrt ist. */
    data class Gateway(val code: Int, val detail: String) : ApiError

    /** Modell nicht vorhanden oder nicht geladen. */
    data class ModelUnavailable(val detail: String) : ApiError

    /** Kontext zu lang. Die genaue Fehlerform des Tiiny ist nicht verifiziert. */
    data class ContextLimit(val detail: String) : ApiError

    data class Server(val code: Int, val detail: String) : ApiError

    /** Antwort nicht im erwarteten Format. */
    data class Protocol(val detail: String) : ApiError
}

sealed interface ChatEvent {
    data class ContentDelta(val text: String) : ChatEvent

    /** Denkausgabe; wird angezeigt, aber nicht gespeichert. */
    data class ReasoningDelta(val text: String) : ChatEvent
    data class Model(val model: String) : ChatEvent
}

sealed interface ChatOutcome {
    val content: String
    val model: String?

    data class Complete(override val content: String, override val model: String?) : ChatOutcome

    /**
     * Antwort unvollständig: [reason] z. B. "length" (Token-Grenze),
     * "stream_ended" (Verbindung vor Abschluss beendet), "cancelled".
     */
    data class Incomplete(override val content: String, override val model: String?, val reason: String) : ChatOutcome

    data class Failed(val error: ApiError, override val content: String = "", override val model: String? = null) : ChatOutcome
}

sealed interface ModelsResult {
    data class Ok(val models: List<ModelInfo>) : ModelsResult
    data class Err(val error: ApiError) : ModelsResult
}

interface ChatApi {
    suspend fun listModels(): ModelsResult

    /** Ruft [onEvent] für Teilstücke auf; liefert immer ein Ergebnis, wirft nicht. */
    suspend fun chat(request: ChatRequest, onEvent: (ChatEvent) -> Unit): ChatOutcome
}
