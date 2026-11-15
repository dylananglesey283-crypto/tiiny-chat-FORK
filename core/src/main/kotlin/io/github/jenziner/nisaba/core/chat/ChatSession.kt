package io.github.jenziner.nisaba.core.chat

import io.github.jenziner.nisaba.core.Env
import io.github.jenziner.nisaba.core.api.ApiError
import io.github.jenziner.nisaba.core.api.ChatApi
import io.github.jenziner.nisaba.core.api.ChatEvent
import io.github.jenziner.nisaba.core.api.ChatOutcome
import io.github.jenziner.nisaba.core.api.ThinkingParams
import io.github.jenziner.nisaba.core.search.SearchOutcome
import io.github.jenziner.nisaba.core.search.SearchQuery
import io.github.jenziner.nisaba.core.search.WebContext
import io.github.jenziner.nisaba.core.search.WebResult
import io.github.jenziner.nisaba.core.search.WebSearch
import io.github.jenziner.nisaba.core.format.Message
import io.github.jenziner.nisaba.core.format.MessageStatus
import io.github.jenziner.nisaba.core.format.Role
import io.github.jenziner.nisaba.core.format.SoulSnapshotFile
import io.github.jenziner.nisaba.core.store.ChatHandle
import io.github.jenziner.nisaba.core.store.ChatRef
import io.github.jenziner.nisaba.core.store.OpenedChat
import io.github.jenziner.nisaba.core.store.ProjectRef
import io.github.jenziner.nisaba.core.store.ProjectStore
import io.github.jenziner.nisaba.core.store.StoreResult
import io.github.jenziner.nisaba.core.timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Eine Nachricht in der Sitzung, mit ihrem Speicherstand. */
data class SessionMessage(
    val message: Message,
    /** Im Ad-hoc-Chat immer false; dort wird nie gespeichert. */
    val saved: Boolean,
    /** Denkausgabe der Antwort; nur im Arbeitsspeicher, wird nie gespeichert. */
    val reasoning: String? = null,
    /** Websuche zu dieser Antwort; nur im Arbeitsspeicher, wird nie gespeichert. */
    val web: WebContext? = null,
)

/** Optionen für eine einzelne Modellanfrage, zum Zeitpunkt der Anfrage ermittelt. */
data class ChatOptions(
    val thinking: ThinkingParams? = null,
    /** `null` = keine Websuche. */
    val webSearch: WebSearch? = null,
    /** Höchstmenge aktiver Dokumente in geschätzten Tokens; `null` = keine Grenze bekannt. */
    val documentLimitTokens: Int? = null,
) {
    companion object {
        val NONE = ChatOptions()
    }
}

/** Ergebnis beim Laden aktiver Dokumente für eine Anfrage. */
sealed interface DocumentLoad {
    data class Ok(val documents: List<DocumentContent>) : DocumentLoad

    /** Mindestens ein Dokument fehlt oder liefert keinen Text; es wird nichts gesendet. */
    data class Failed(val name: String, val reason: String) : DocumentLoad
}

/** Liefert den Text der aktiven Dokumente eines Projekts. */
fun interface DocumentSource {
    suspend fun load(names: List<String>): DocumentLoad
}

/** Ganzzahl mit Schweizer Tausendertrennzeichen, z. B. 100'000. */
fun swissInt(n: Int): String = "%,d".format(java.util.Locale.ROOT, n).replace(',', '\'')

object DocumentBudget {
    /** Geschätzte Tokens des Dokument-Abschnitts in der system-Nachricht. */
    fun tokens(docs: List<DocumentContent>): Int =
        if (docs.isEmpty()) 0 else RequestBuilder.documents(docs).length / 3 + 1

    /**
     * Wirksame Dokumentgrenze: halbe Kontextgrösse, wenn das Modell sie meldet;
     * sonst der Projektwert, sonst der App-Wert.
     */
    fun limit(contextWindow: Int?, projectLimit: Int?, appLimit: Int): Int =
        contextWindow?.takeIf { it > 0 }?.let { it / 2 } ?: projectLimit ?: appLimit
}

/** Speicherzustand eines Chats, sichtbar in der Oberfläche. */
sealed interface PersistState {
    /** Ad-hoc-Chat: nur Arbeitsspeicher. */
    data object Memory : PersistState

    /** Projekt-Chat vor dem ersten Senden: noch keine Dateien. */
    data object Draft : PersistState
    data object Saved : PersistState

    /** Es gibt Inhalte, die noch nicht gespeichert sind ("Noch nicht gespeichert"). */
    data class Pending(val reason: String) : PersistState

    /** Angeschlossen ist ein anderer Projekt-Speicher. */
    data class WrongStore(val detail: String) : PersistState

    /** Datei wurde verändert oder Identität stimmt nicht; keine automatische Speicherung. */
    data class Conflict(val detail: String) : PersistState
}

sealed interface SessionError {
    data class Model(val error: ApiError) : SessionError
    data class Storage(val detail: String) : SessionError

    /** Websuche fehlgeschlagen; es wurde keine Modellanfrage gestellt. */
    data class Search(val detail: String) : SessionError

    /** Aktive Dokumente nicht lesbar oder zu gross; es wurde nichts gesendet. */
    data class Documents(val detail: String) : SessionError
}

data class LiveAnswer(
    val replyTo: String,
    val content: String,
    val reasoning: String,
    /** Suchbegriff, solange die Websuche läuft. */
    val searching: String? = null,
    val web: WebContext? = null,
)

data class SessionState(
    val isProject: Boolean,
    val title: String?,
    val messages: List<SessionMessage>,
    val live: LiveAnswer?,
    val persist: PersistState,
    /** Für diesen Chat aktive Dokumente (Dateinamen in files/), nur Projekt-Chat. */
    val documents: List<String> = emptyList(),
    /** Speicher laut letzter Prüfung der App erreichbar (nur Projekt-Chat). */
    val storageReachable: Boolean,
    val lastError: SessionError?,
) {
    val requestRunning: Boolean get() = live != null

    val hasPending: Boolean get() = isProject && messages.any { !it.saved }

    /** Ad-hoc-Chat mit Inhalt: Verwerfen durch die App braucht eine Bestätigung. */
    val discardNeedsConfirmation: Boolean get() = !isProject && (messages.isNotEmpty() || live != null)

    /** Senden ist nur möglich, wenn nichts läuft und nichts ungespeichert ist. */
    val canSend: Boolean
        get() = !requestRunning && when {
            !isProject -> true
            else -> storageReachable && (persist == PersistState.Draft || persist == PersistState.Saved)
        }

    /** Letzte Nutzernachricht ohne vollständige Antwort; Grundlage für "Antwort anfordern". */
    val retryableUserMessage: String?
        get() {
            val lastUser = messages.lastOrNull { it.message.role == Role.USER } ?: return null
            val answered = messages.any {
                it.message.role == Role.ASSISTANT && it.message.replyTo == lastUser.message.id &&
                    it.message.status == MessageStatus.COMPLETE
            }
            return if (answered) null else lastUser.message.id
        }
}

sealed interface SendResult {
    /** Nachricht gespeichert (bzw. im Ad-hoc-Chat aufgenommen) und Anfrage abgeschlossen. */
    data class Done(val outcome: ChatOutcome) : SendResult

    /** Nicht angenommen; der eingegebene Text gehört weiter ins Eingabefeld. */
    data class Rejected(val reason: String) : SendResult

    /** Speichern vor dem Senden fehlgeschlagen; keine Modellanfrage, Text bleibt im Eingabefeld. */
    data class NotSaved(val detail: String) : SendResult

    /** Nachricht ist aufgenommen, aber noch nicht gespeichert; keine Modellanfrage. */
    data class HeldPending(val detail: String) : SendResult

    /** Nachricht aufgenommen (bzw. gespeichert), Websuche fehlgeschlagen; keine Modellanfrage. */
    data class SearchFailed(val detail: String) : SendResult
}

/**
 * Zustandsmaschine eines Chats (Projekt oder Ad-hoc). Siehe docs/SNAPSHOT-UND-WIEDERVERBINDUNG.md.
 *
 * Nicht threadsicher: Aufrufe kommen aus einem einzigen Dispatcher (UI).
 * Speicherzugriffe laufen auf [io].
 */
class ChatSession private constructor(
    private val env: Env,
    private val api: ChatApi,
    private val store: ProjectStore?,
    private val project: ProjectRef?,
    private val appInstructions: () -> String,
    private val streaming: () -> Boolean,
    private val io: CoroutineDispatcher,
    private val options: () -> ChatOptions,
    private val documentSource: DocumentSource?,
    initial: SessionState,
    private var handle: ChatHandle?,
    private var ref: ChatRef?,
    private var snapshot: SoulSnapshotFile?,
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var busy = false

    /** Verhindert zwei gleichzeitige Speichervorgänge (z. B. Antwortende und Wiederverbindung). */
    private val persistLock = Mutex()

    companion object {
        fun adHoc(
            env: Env,
            api: ChatApi,
            appInstructions: () -> String,
            streaming: () -> Boolean,
            io: CoroutineDispatcher = Dispatchers.IO,
            options: () -> ChatOptions = { ChatOptions.NONE },
        ) = ChatSession(
            env, api, null, null, appInstructions, streaming, io, options, null,
            SessionState(false, null, emptyList(), null, PersistState.Memory, storageReachable = false, lastError = null),
            null, null, null,
        )

        fun newProjectChat(
            env: Env,
            api: ChatApi,
            store: ProjectStore,
            project: ProjectRef,
            appInstructions: () -> String,
            streaming: () -> Boolean,
            io: CoroutineDispatcher = Dispatchers.IO,
            options: () -> ChatOptions = { ChatOptions.NONE },
            documents: DocumentSource? = null,
            initialDocuments: List<String> = emptyList(),
        ) = ChatSession(
            env, api, store, project, appInstructions, streaming, io, options, documents,
            SessionState(
                true, null, emptyList(), null, PersistState.Draft, documents = initialDocuments,
                storageReachable = true, lastError = null,
            ),
            null, null, null,
        )

        fun openProjectChat(
            env: Env,
            api: ChatApi,
            store: ProjectStore,
            opened: OpenedChat,
            appInstructions: () -> String,
            streaming: () -> Boolean,
            io: CoroutineDispatcher = Dispatchers.IO,
            options: () -> ChatOptions = { ChatOptions.NONE },
            documents: DocumentSource? = null,
        ) = ChatSession(
            env, api, store, opened.ref.project, appInstructions, streaming, io, options, documents,
            SessionState(
                true, opened.ref.chat.title, opened.messages.map { SessionMessage(it, saved = true) },
                null, PersistState.Saved, documents = opened.ref.chat.documents,
                storageReachable = true, lastError = null,
            ),
            opened.handle, opened.ref, opened.snapshot,
        )
    }

    val chatRef: ChatRef? get() = ref
    val snapshotForTest: SoulSnapshotFile? get() = snapshot

    /** Von der App gemeldet, wenn sich die Erreichbarkeit des Projekt-Speichers ändert. */
    suspend fun setStorageReachable(reachable: Boolean) {
        if (!_state.value.isProject) return // Ad-hoc bleibt unberührt
        _state.update { it.copy(storageReachable = reachable) }
        if (reachable) reconcile()
    }

    /**
     * Sendet eine neue Nutzernachricht. Im Projekt-Chat wird die Nachricht
     * zuerst gespeichert; erst danach geht die Anfrage an das Modell.
     */
    suspend fun send(text: String, model: String): SendResult {
        val s = _state.value
        if (busy || !s.canSend) return SendResult.Rejected(rejectReason(s))
        if (text.isBlank()) return SendResult.Rejected("Empty message")
        busy = true
        try {
            val docs = when (val d = loadDocuments()) {
                is DocCheck.Ok -> d.docs
                is DocCheck.Rejected -> return SendResult.Rejected(d.reason)
            }
            val user = Message(
                id = env.newId(), role = Role.USER, content = text, createdAt = env.timestamp(),
                status = MessageStatus.COMPLETE,
            )
            if (!s.isProject) {
                _state.update { it.copy(messages = it.messages + SessionMessage(user, false), lastError = null) }
                return answer(user.id, model, docs)
            }
            val h = handle
            if (h == null) {
                val begin = withContext(io + NonCancellable) { store!!.beginChat(project!!, user, s.documents) }
                when (begin) {
                    is ProjectStore.BeginResult.Started -> {
                        handle = begin.opened.handle
                        ref = begin.opened.ref
                        snapshot = begin.opened.snapshot
                        _state.update {
                            it.copy(
                                title = begin.opened.ref.chat.title,
                                messages = listOf(SessionMessage(user, true)),
                                persist = PersistState.Saved,
                                lastError = null,
                            )
                        }
                    }
                    is ProjectStore.BeginResult.SoulUnreadable -> {
                        val d = "SOUL.md could not be read. Nothing was sent."
                        _state.update { it.copy(lastError = SessionError.Storage(d)) }
                        return SendResult.NotSaved(d)
                    }
                    is ProjectStore.BeginResult.Failed -> {
                        val d = "Chat could not be created: ${describe(begin.failure)}. Nothing was sent."
                        _state.update { it.copy(lastError = SessionError.Storage(d)) }
                        return SendResult.NotSaved(d)
                    }
                }
            } else {
                _state.update { it.copy(messages = it.messages + SessionMessage(user, false), lastError = null) }
                if (!persist()) {
                    return SendResult.HeldPending("Message not saved yet. Nothing was sent.")
                }
            }
            return answer(user.id, model, docs)
        } finally {
            busy = false
        }
    }

    /** Fordert erneut eine Antwort auf die letzte unbeantwortete Nutzernachricht an. */
    suspend fun retry(model: String): SendResult {
        val s = _state.value
        val userId = s.retryableUserMessage ?: return SendResult.Rejected("No unanswered message")
        if (busy || !s.canSend) return SendResult.Rejected(rejectReason(s))
        busy = true
        try {
            val docs = when (val d = loadDocuments()) {
                is DocCheck.Ok -> d.docs
                is DocCheck.Rejected -> return SendResult.Rejected(d.reason)
            }
            return answer(userId, model, docs)
        } finally {
            busy = false
        }
    }

    private sealed interface DocCheck {
        data class Ok(val docs: List<DocumentContent>) : DocCheck
        data class Rejected(val reason: String) : DocCheck
    }

    /**
     * Liest die aktiven Dokumente und prüft die Grenze. Schlägt das fehl, wird
     * nichts gespeichert und nichts gesendet; gekürzt wird nie.
     */
    private suspend fun loadDocuments(): DocCheck {
        val names = _state.value.documents
        if (names.isEmpty()) return DocCheck.Ok(emptyList())
        val source = documentSource ?: return DocCheck.Rejected("Documents are not available in this chat")
        val loaded = when (val r = source.load(names)) {
            is DocumentLoad.Ok -> r.documents
            is DocumentLoad.Failed -> {
                val d = "Document \"${r.name}\": ${r.reason}. Nothing was sent."
                _state.update { it.copy(lastError = SessionError.Documents(d)) }
                return DocCheck.Rejected(d)
            }
        }
        val limit = options().documentLimitTokens
        val tokens = DocumentBudget.tokens(loaded)
        if (limit != null && tokens > limit) {
            val d = "Active documents ≈ ${swissInt(tokens)} tokens, limit ${swissInt(limit)}. " +
                "Deactivate documents. Nothing was sent."
            _state.update { it.copy(lastError = SessionError.Documents(d)) }
            return DocCheck.Rejected(d)
        }
        return DocCheck.Ok(loaded)
    }

    /**
     * Ändert die Auswahl aktiver Dokumente. Vor dem ersten Senden nur im
     * Arbeitsspeicher; danach sofort in chat.json gespeichert.
     */
    suspend fun setDocuments(names: List<String>): StoreResult<Unit> {
        val s = _state.value
        if (!s.isProject) return StoreResult.Conflict("Documents are only available in project chats")
        val clean = names.distinct()
        val r = ref
        if (r == null) {
            _state.update { it.copy(documents = clean) }
            return StoreResult.Ok(Unit)
        }
        val result = withContext(io + NonCancellable) { store!!.updateChatDocuments(r, clean) }
        return when (result) {
            is StoreResult.Ok -> {
                ref = result.value
                _state.update { it.copy(documents = result.value.chat.documents) }
                StoreResult.Ok(Unit)
            }
            else -> result.let { @Suppress("UNCHECKED_CAST") (it as StoreResult<Unit>) }
        }
    }

    private suspend fun answer(userId: String, model: String, docs: List<DocumentContent>): SendResult {
        val opts = options()
        val upTo = _state.value.messages.map { it.message }.let { all -> all.subList(0, all.indexOfFirst { it.id == userId } + 1) }
        var web: WebContext? = null
        val search = opts.webSearch
        if (search != null) {
            val query = SearchQuery.from(upTo.last().content)
            _state.update { it.copy(live = LiveAnswer(userId, "", "", searching = query)) }
            val found = try {
                search.search(query)
            } catch (e: CancellationException) {
                _state.update { it.copy(live = null) }
                throw e
            }
            when (found) {
                is SearchOutcome.Ok -> web = WebContext(query, env.timestamp(), found.results)
                is SearchOutcome.Failed -> {
                    _state.update { it.copy(live = null, lastError = SessionError.Search(found.detail)) }
                    return SendResult.SearchFailed(found.detail)
                }
            }
        }
        return SendResult.Done(requestAnswer(userId, model, upTo, web, docs, opts.thinking))
    }

    private suspend fun requestAnswer(
        userId: String,
        model: String,
        upTo: List<Message>,
        web: WebContext?,
        docs: List<DocumentContent>,
        thinking: ThinkingParams?,
    ): ChatOutcome {
        val request = RequestBuilder.build(RequestParts(appInstructions(), snapshot, upTo, web, docs), model, streaming(), thinking)
        _state.update { it.copy(live = LiveAnswer(userId, "", "", web = web)) }
        val outcome: ChatOutcome = try {
            api.chat(request) { ev ->
                _state.update { st ->
                    val l = st.live ?: return@update st
                    when (ev) {
                        is ChatEvent.ContentDelta -> st.copy(live = l.copy(content = l.content + ev.text))
                        is ChatEvent.ReasoningDelta -> st.copy(live = l.copy(reasoning = l.reasoning + ev.text))
                        is ChatEvent.Model -> st
                    }
                }
            }
        } catch (e: CancellationException) {
            val partial = _state.value.live?.content.orEmpty()
            withContext(NonCancellable) { finishAnswer(userId, ChatOutcome.Incomplete(partial, null, "cancelled"), model) }
            throw e
        }
        finishAnswer(userId, outcome, model)
        return outcome
    }

    private suspend fun finishAnswer(userId: String, outcome: ChatOutcome, model: String) {
        val status = when (outcome) {
            is ChatOutcome.Complete -> MessageStatus.COMPLETE
            is ChatOutcome.Incomplete -> MessageStatus.INCOMPLETE
            is ChatOutcome.Failed -> MessageStatus.FAILED
        }
        val error = (outcome as? ChatOutcome.Failed)?.let { SessionError.Model(it.error) }
        // Fehlgeschlagene Anfragen ohne jeden Inhalt werden nicht als Nachricht abgelegt.
        if (outcome is ChatOutcome.Failed && outcome.content.isEmpty()) {
            _state.update { it.copy(live = null, lastError = error) }
            return
        }
        val answer = Message(
            id = env.newId(), role = Role.ASSISTANT, content = outcome.content, createdAt = env.timestamp(),
            replyTo = userId, status = status, model = outcome.model ?: model,
        )
        _state.update {
            val live = it.live
            val extra = SessionMessage(
                answer, false,
                reasoning = live?.reasoning?.takeIf { r -> r.isNotBlank() },
                web = live?.web,
            )
            it.copy(live = null, messages = it.messages + extra, lastError = error)
        }
        if (_state.value.isProject) persist()
    }

    /** Versucht ausstehende Inhalte zu speichern. Liefert true, wenn alles gespeichert ist. */
    suspend fun reconcile(): Boolean {
        val s = _state.value
        if (!s.isProject || handle == null) return true
        if (s.persist is PersistState.Conflict) return false
        if (!s.hasPending) return true
        if (s.requestRunning) return false // nach Abschluss der Antwort wird ohnehin gespeichert
        return persist()
    }

    private suspend fun persist(): Boolean = persistLock.withLock { persistLocked() }

    private suspend fun persistLocked(): Boolean {
        if (!_state.value.hasPending) return true
        val h = handle ?: return false
        val all = _state.value.messages.map { it.message }
        val result = withContext(io + NonCancellable) { store!!.saveHistory(h, all) }
        return when (result) {
            is StoreResult.Ok -> {
                handle = result.value
                val savedIds = all.map { it.id }.toSet()
                _state.update { st ->
                    val msgs = st.messages.map { if (it.message.id in savedIds) it.copy(saved = true) else it }
                    st.copy(
                        messages = msgs,
                        persist = if (msgs.all { it.saved }) PersistState.Saved else PersistState.Pending("More content pending"),
                    )
                }
                _state.value.persist == PersistState.Saved
            }
            is StoreResult.WrongStore -> {
                _state.update { it.copy(persist = PersistState.WrongStore("A different project storage is connected")) }
                false
            }
            is StoreResult.Conflict, is StoreResult.IdentityMismatch, is StoreResult.Invalid -> {
                _state.update { it.copy(persist = PersistState.Conflict(describe(result))) }
                false
            }
            is StoreResult.StorageFailure, is StoreResult.Interrupted -> {
                _state.update { it.copy(persist = PersistState.Pending(describe(result)), storageReachable = false) }
                false
            }
        }
    }

    /**
     * Abschätzung der Kontextgrösse für die nächste Anfrage inklusive Entwurf.
     * Dient nur einem sichtbaren Hinweis; gekürzt wird nie.
     */
    fun contextLevel(draft: String, model: String, contextWindow: Int?, documentTokens: Int = 0): ContextEstimate.Level {
        val msgs = _state.value.messages.map { it.message }
        val withDraft = if (draft.isBlank()) msgs else msgs + Message(
            id = "entwurf", role = Role.USER, content = draft, createdAt = "", status = MessageStatus.COMPLETE,
        )
        val request = RequestBuilder.build(RequestParts(appInstructions(), snapshot, withDraft), model, false)
        return ContextEstimate.level(request, contextWindow, documentTokens)
    }

    /** Projektanweisungen dieses Chats (Snapshot), sobald der Chat angelegt ist. */
    val soulSnapshot: SoulSnapshotFile? get() = snapshot

    /** Manuelle Titeländerung; wird nie automatisch ersetzt. */
    suspend fun rename(newTitle: String): StoreResult<ChatRef> {
        val r = ref ?: return StoreResult.Conflict("Chat has not been created yet")
        val result = withContext(io) { store!!.renameChat(r, newTitle) }
        if (result is StoreResult.Ok) {
            ref = result.value
            _state.update { it.copy(title = result.value.chat.title) }
        }
        return result
    }

    private fun rejectReason(s: SessionState): String = when {
        s.requestRunning || busy -> "A request is already running"
        s.isProject && !s.storageReachable -> "Project storage not reachable"
        s.persist is PersistState.Pending -> "Not saved yet"
        s.persist is PersistState.WrongStore -> "A different project storage is connected"
        s.persist is PersistState.Conflict -> "Conflict while saving"
        else -> "Sending not possible"
    }
}

internal fun describe(r: StoreResult<*>): String = when (r) {
    is StoreResult.Ok -> "ok"
    is StoreResult.StorageFailure -> "Storage error (${r.error})"
    is StoreResult.Invalid -> r.detail
    is StoreResult.Conflict -> r.detail
    is StoreResult.WrongStore -> "Different project storage"
    is StoreResult.IdentityMismatch -> r.detail
    is StoreResult.Interrupted -> "Write interrupted (${r.error})"
}
