package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.api.ApiError
import io.github.jenziner.nisaba.core.api.ChatApi
import io.github.jenziner.nisaba.core.api.ChatEvent
import io.github.jenziner.nisaba.core.api.ChatOutcome
import io.github.jenziner.nisaba.core.api.ChatRequest
import io.github.jenziner.nisaba.core.api.ModelInfo
import io.github.jenziner.nisaba.core.api.ModelsResult
import io.github.jenziner.nisaba.core.chat.ChatSession
import io.github.jenziner.nisaba.core.store.ProjectRef
import io.github.jenziner.nisaba.core.store.ProjectStore
import io.github.jenziner.nisaba.core.store.StoreResult
import io.github.jenziner.nisaba.core.store.StoreStatus
import kotlinx.coroutines.Dispatchers

/** Simulierter Modellserver. Ersetzt keinen Test mit dem echten Tiiny. */
class FakeApi : ChatApi {
    val requests = mutableListOf<ChatRequest>()
    var modelsCalls = 0

    /** Wird während der Anfrage ausgeführt, z. B. um den Speicher zu trennen. */
    var during: (() -> Unit)? = null
    var next: MutableList<ChatOutcome> = mutableListOf()

    override suspend fun listModels(): ModelsResult {
        modelsCalls++
        return ModelsResult.Ok(listOf(ModelInfo("Qwen/Qwen3.8-27B")))
    }

    override suspend fun chat(request: ChatRequest, onEvent: (ChatEvent) -> Unit): ChatOutcome {
        requests += request
        during?.invoke()
        val outcome = if (next.isNotEmpty()) next.removeAt(0) else ChatOutcome.Complete("Antwort ${requests.size}", "/model/model.q8_0.gguf")
        if (outcome.content.isNotEmpty()) onEvent(ChatEvent.ContentDelta(outcome.content))
        return outcome
    }

    fun failNext(error: ApiError = ApiError.Connection("weg")) {
        next += ChatOutcome.Failed(error)
    }

    val systemPrompts: List<String?> get() = requests.map { r -> r.messages.firstOrNull { it.role == "system" }?.content }
}

const val MODEL = "Qwen/Qwen3.8-27B"

class Fixture {
    val storage = FakeStorage()
    val env = TestEnv()
    val api = FakeApi()
    var store = ProjectStore(storage, env)
    val storeId: String
    val project: ProjectRef

    init {
        storeId = (store.initializeStore() as StoreStatus.Ready).storeId
        project = (store.createProject(storeId, "Mein Projekt") as StoreResult.Ok).value
    }

    val soulPath get() = "${project.dir}/SOUL.md"

    fun newChat(appInstructions: String = "") = ChatSession.newProjectChat(
        env, api, store, project, { appInstructions }, { true }, Dispatchers.Unconfined,
    )

    fun adHoc() = ChatSession.adHoc(env, api, { "" }, { true }, Dispatchers.Unconfined)

    /** Simuliert App-Neustart: neue Store-Instanz, Chat von der Platte öffnen. */
    fun reopen(chatId: String): ChatSession {
        store = ProjectStore(storage, env)
        val listing = (store.listChats(project) as StoreResult.Ok).value
        val ref = listing.items.single { it.chat.chatId == chatId }
        val opened = (store.openChat(ref) as StoreResult.Ok).value
        return ChatSession.openProjectChat(env, api, store, opened, { "" }, { true }, Dispatchers.Unconfined)
    }

    fun historyText(session: ChatSession) = storage.text("${session.chatRef!!.dir}/history.json")!!
}

fun <T> StoreResult<T>.ok(): T = (this as? StoreResult.Ok)?.value ?: error("Erwartet Ok, war $this")
