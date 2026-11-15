package io.github.jenziner.nisaba.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Kodierung und Dekodierung des OpenAI-kompatiblen Formats, ohne Netzwerk. */
object OpenAiWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeRequest(r: ChatRequest): String = buildJsonObject {
        put("model", r.model)
        put("stream", r.stream)
        r.thinking?.enabled?.let { put("thinking_enabled", it) }
        r.thinking?.effort?.let { put("reasoning_effort", it) }
        put(
            "messages",
            buildJsonArray {
                r.messages.forEach { m ->
                    add(buildJsonObject { put("role", m.role); put("content", m.content) })
                }
            },
        )
    }.toString()

    fun parseModels(body: String): List<ModelInfo>? {
        val root = parseObject(body) ?: return null
        val data = root["data"] as? JsonArray ?: return null
        return data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            ModelInfo(
                id = id,
                contextWindow = (o["context_window"] as? JsonPrimitive)?.intOrNull,
                supportsChat = (o["supports_chat"] as? JsonPrimitive)?.booleanOrNull,
                thinking = parseThinking(o["thinking"]),
            )
        }
    }

    private fun parseThinking(el: kotlinx.serialization.json.JsonElement?): ThinkingInfo? = when (el) {
        is JsonPrimitive -> el.booleanOrNull?.let { ThinkingInfo(it, null, null, emptyList(), null) }
        is JsonObject -> ThinkingInfo(
            supported = (el["supported"] as? JsonPrimitive)?.booleanOrNull,
            toggleable = (el["toggleable"] as? JsonPrimitive)?.booleanOrNull,
            enabledByDefault = (el["enabled_by_default"] as? JsonPrimitive)?.booleanOrNull,
            levels = (el["levels"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty(),
            defaultLevel = el.str("default_level"),
        )
        else -> null
    }

    /** Denkausgabe einer nicht-streamenden Antwort (`message.reasoning_content`), falls vorhanden. */
    fun parseReasoning(body: String): String? {
        val root = parseObject(body) ?: return null
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        return (choice["message"] as? JsonObject)?.str("reasoning_content")?.takeIf { it.isNotEmpty() }
    }

    /** Nicht-streamende Antwort (auf dem Gerät des Nutzers bestätigtes Format). */
    fun parseCompletion(body: String): ChatOutcome {
        val root = parseObject(body) ?: return ChatOutcome.Failed(ApiError.Protocol("Answer is not a JSON object"))
        errorOf(root)?.let { return ChatOutcome.Failed(it) }
        val model = root.str("model")
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return ChatOutcome.Failed(ApiError.Protocol("Answer without choices"), model = model)
        val message = choice["message"] as? JsonObject
            ?: return ChatOutcome.Failed(ApiError.Protocol("Answer without message"), model = model)
        val content = message.str("content") ?: ""
        return when (val finish = choice.str("finish_reason")) {
            "stop" -> ChatOutcome.Complete(content, model)
            null -> ChatOutcome.Incomplete(content, model, "no_finish_reason")
            else -> ChatOutcome.Incomplete(content, model, finish)
        }
    }

    /**
     * Zustandsbehafteter Parser für Server-Sent Events.
     * Erwartet `data: {...}`-Zeilen und `data: [DONE]`. Ein Teilstück darf
     * `choices` leer haben (z. B. nur `usage`).
     */
    class StreamParser(private val onEvent: (ChatEvent) -> Unit) {
        private val content = StringBuilder()
        private var model: String? = null
        private var finishReason: String? = null
        private var done = false
        private var error: ApiError? = null
        private val dataLines = mutableListOf<String>()

        /** Eine Zeile ohne Zeilenende. */
        fun feedLine(line: String) {
            if (done || error != null) return
            if (line.isEmpty()) { dispatch(); return }
            if (line.startsWith(":")) return
            val (field, value) = line.indexOf(':').let { i ->
                if (i < 0) line to "" else line.substring(0, i) to line.substring(i + 1).removePrefix(" ")
            }
            if (field == "data") dataLines += value
        }

        private fun dispatch() {
            if (dataLines.isEmpty()) return
            val data = dataLines.joinToString("\n")
            dataLines.clear()
            if (data.trim() == "[DONE]") { done = true; return }
            val obj = parseObject(data) ?: run { error = ApiError.Protocol("Invalid chunk in stream"); return }
            errorOf(obj)?.let { error = it; return }
            obj.str("model")?.let { if (model == null) { model = it; onEvent(ChatEvent.Model(it)) } }
            val choices = obj["choices"] as? JsonArray ?: return
            val choice = choices.firstOrNull() as? JsonObject ?: return
            val delta = choice["delta"] as? JsonObject
            delta?.str("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { onEvent(ChatEvent.ReasoningDelta(it)) }
            delta?.str("content")?.takeIf { it.isNotEmpty() }?.let { content.append(it); onEvent(ChatEvent.ContentDelta(it)) }
            choice.str("finish_reason")?.let { finishReason = it }
        }

        /** Am Ende des Datenstroms aufrufen. */
        fun finish(): ChatOutcome {
            dispatch()
            error?.let { return ChatOutcome.Failed(it, content.toString(), model) }
            val text = content.toString()
            return when {
                finishReason == "stop" -> ChatOutcome.Complete(text, model)
                finishReason != null -> ChatOutcome.Incomplete(text, model, finishReason!!)
                else -> ChatOutcome.Incomplete(text, model, if (done) "no_finish_reason" else "stream_ended")
            }
        }

        fun partial(reason: String): ChatOutcome.Incomplete = ChatOutcome.Incomplete(content.toString(), model, reason)
    }

    /** Ordnet HTTP-Fehler zu. Texte aus dem Body werden gekürzt übernommen. */
    fun httpError(code: Int, body: String): ApiError {
        val detail = parseObject(body)?.let { o ->
            val e = o["error"]
            when (e) {
                is JsonObject -> e.str("message") ?: e.toString()
                is JsonPrimitive -> listOfNotNull(e.contentOrNull, o.str("reason"), o.str("message")).joinToString(": ")
                else -> o.str("message") ?: o.str("detail")
            }
        } ?: body.take(300).ifBlank { "HTTP $code" }
        val lower = detail.lowercase()
        return when {
            code == 401 || code == 403 -> ApiError.Auth(code, detail)
            code == 502 || code == 503 -> ApiError.Gateway(code, detail)
            code == 504 -> ApiError.Timeout(detail)
            code == 404 && ("model" in lower || "not loaded" in lower) -> ApiError.ModelUnavailable(detail)
            "context" in lower && ("exceed" in lower || "too long" in lower || "length" in lower) -> ApiError.ContextLimit(detail)
            else -> ApiError.Server(code, detail)
        }
    }

    private fun errorOf(o: JsonObject): ApiError? {
        val e = o["error"] ?: return null
        if (e is JsonNull) return null
        val msg = (e as? JsonObject)?.str("message") ?: (e as? JsonPrimitive)?.contentOrNull ?: e.toString()
        val code = ((e as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull ?: 500
        return httpError(code, buildJsonObject { put("error", buildJsonObject { put("message", msg) }) }.toString())
    }

    private fun parseObject(s: String): JsonObject? =
        runCatching { json.parseToJsonElement(s) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
