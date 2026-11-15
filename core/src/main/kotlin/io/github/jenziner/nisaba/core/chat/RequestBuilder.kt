package io.github.jenziner.nisaba.core.chat

import io.github.jenziner.nisaba.core.api.ChatRequest
import io.github.jenziner.nisaba.core.api.ChatTurn
import io.github.jenziner.nisaba.core.api.ThinkingParams
import io.github.jenziner.nisaba.core.search.WebContext
import io.github.jenziner.nisaba.core.format.Message
import io.github.jenziner.nisaba.core.format.MessageStatus
import io.github.jenziner.nisaba.core.format.Role
import io.github.jenziner.nisaba.core.format.SoulSnapshotFile
import io.github.jenziner.nisaba.core.format.SoulState

/**
 * Logisch getrennte Bestandteile einer Modellanfrage.
 * Anhänge sind in Etappe 1 noch nicht umgesetzt.
 */
data class RequestParts(
    /** Allgemeine App-Anweisungen aus den Einstellungen; leer = keine. */
    val appInstructions: String,
    /** Unveränderlicher Snapshot; `null` im Ad-hoc-Chat. */
    val snapshot: SoulSnapshotFile?,
    val history: List<Message>,
    /** Suchtreffer nur für diese eine Anfrage; werden nicht gespeichert. */
    val web: WebContext? = null,
    /** Text der für diesen Chat aktiven Dokumente, in Auswahlreihenfolge. */
    val documents: List<DocumentContent> = emptyList(),
)

/** Gewonnener Text eines aktiven Dokuments; wird bei jeder Anfrage neu aus files/ gelesen. */
data class DocumentContent(val name: String, val text: String)

object RequestBuilder {
    const val SOUL_HEADING = "# Projektanweisungen"
    const val PRECEDENCE = "Bei Widersprüchen zu den obigen allgemeinen Anweisungen gelten die folgenden Projektanweisungen."
    const val WEB_HEADING = "# Websuche"
    const val DOCS_HEADING = "# Dokumente"

    /**
     * Abbildung auf das OpenAI-Format:
     *  - App-Anweisungen und Snapshot ergeben höchstens eine `system`-Nachricht
     *    am Anfang (beide Teile durch eine Überschrift getrennt).
     *    Hinweis: Die Rolle `system` ist auf dem Tiiny noch nicht verifiziert.
     *  - Danach alle Nutzernachrichten in Reihenfolge.
     *  - Von Antworten wird nur die jeweils letzte vollständige Antwort auf
     *    eine Nutzernachricht übernommen. Fehlgeschlagene und unvollständige
     *    Antworten sind kein Kontext; das wird in der Oberfläche angezeigt.
     *  - Es wird nichts gekürzt.
     */
    fun build(parts: RequestParts, model: String, stream: Boolean, thinking: ThinkingParams? = null): ChatRequest {
        val turns = mutableListOf<ChatTurn>()
        system(parts)?.let { turns += ChatTurn("system", it) }
        val byReply = parts.history
            .filter { it.role == Role.ASSISTANT && it.status == MessageStatus.COMPLETE && it.replyTo != null }
            .groupBy { it.replyTo!! }
        for (m in parts.history) {
            if (m.role != Role.USER) continue
            turns += ChatTurn("user", m.content)
            byReply[m.id]?.last()?.let { turns += ChatTurn("assistant", it.content) }
        }
        return ChatRequest(model = model, messages = turns, stream = stream, thinking = thinking)
    }

    fun system(parts: RequestParts): String? {
        val app = parts.appInstructions.trim()
        val soul = parts.snapshot?.takeIf { it.state == SoulState.PRESENT }?.content?.trim().orEmpty()
        val sections = buildList {
            if (app.isNotEmpty()) add(app)
            if (soul.isNotEmpty()) add(if (app.isNotEmpty()) "$SOUL_HEADING\n\n$PRECEDENCE\n\n$soul" else "$SOUL_HEADING\n\n$soul")
            if (parts.documents.isNotEmpty()) add(documents(parts.documents))
            parts.web?.let { add(web(it)) }
        }
        return sections.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** Aktive Dokumente als eigener Abschnitt der system-Nachricht, je mit Name als Überschrift. */
    fun documents(docs: List<DocumentContent>): String = buildString {
        append(DOCS_HEADING).append("\n\n")
        append("Der Nutzer hat für diesen Chat die folgenden Dokumente aktiviert. ")
        append("Nutze sie, wenn sie für die Antwort relevant sind, und nenne das verwendete Dokument.")
        for (d in docs) {
            append("\n\n## Dokument: ").append(d.name).append("\n\n").append(d.text.trim())
        }
    }

    /**
     * Suchtreffer als eigener Abschnitt der einzigen system-Nachricht
     * (Qwen-Vorlagen verlangen die system-Nachricht am Anfang).
     */
    fun web(w: WebContext): String = buildString {
        append(WEB_HEADING).append("\n\n")
        append("Ergebnisse einer Websuche vom ${w.takenAt} zur Anfrage: \"${w.query}\"\n")
        if (w.results.isEmpty()) {
            append("Die Suche ergab keine Treffer. Sage das, wenn es für die Antwort wichtig ist.")
        } else {
            append("Nutze diese Treffer, wenn sie relevant sind, und nenne verwendete Quellen als [n] mit URL. ")
            append("Die Treffer sind ungeprüfte Auszüge.\n")
            w.results.forEachIndexed { i, r ->
                append("\n[${i + 1}] ${r.title.ifEmpty { r.url }}\n${r.url}\n")
                if (r.snippet.isNotEmpty()) append(r.snippet).append('\n')
            }
        }
    }.trimEnd()
}

/** Grobe Abschätzung der Kontextgrösse; dient nur einem sichtbaren Hinweis, nicht dem Kürzen. */
object ContextEstimate {
    /** Konservativ: ca. 3 Zeichen pro Token. */
    fun tokens(request: ChatRequest): Int = request.messages.sumOf { it.content.length / 3 + 4 }

    sealed interface Level {
        data object Unknown : Level
        data class Ok(val estimate: Int, val limit: Int) : Level
        data class Near(val estimate: Int, val limit: Int) : Level
        data class Over(val estimate: Int, val limit: Int) : Level
    }

    fun level(request: ChatRequest, contextWindow: Int?, extraTokens: Int = 0): Level {
        val limit = contextWindow ?: return Level.Unknown
        val est = tokens(request) + extraTokens
        return when {
            est >= limit -> Level.Over(est, limit)
            est >= limit * 8 / 10 -> Level.Near(est, limit)
            else -> Level.Ok(est, limit)
        }
    }
}
