package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.api.ApiError
import io.github.jenziner.nisaba.core.api.ChatEvent
import io.github.jenziner.nisaba.core.api.ChatOutcome
import io.github.jenziner.nisaba.core.api.ChatRequest
import io.github.jenziner.nisaba.core.api.ChatTurn
import io.github.jenziner.nisaba.core.api.ModelsResult
import io.github.jenziner.nisaba.core.api.OpenAiWire
import io.github.jenziner.nisaba.core.api.TiinyHttpApi
import io.github.jenziner.nisaba.core.chat.ContextEstimate
import io.github.jenziner.nisaba.core.chat.RequestBuilder
import io.github.jenziner.nisaba.core.chat.RequestParts
import io.github.jenziner.nisaba.core.format.Message
import io.github.jenziner.nisaba.core.format.MessageStatus
import io.github.jenziner.nisaba.core.format.Role
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Protokolltests gegen einen simulierten Server. Die Antworten sind dem
 * auf dem Gerät des Nutzers beobachteten Format nachgebildet bzw. der
 * Drittquelle entnommen. Sie ersetzen keinen Test mit dem echten Tiiny.
 */
class ApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TiinyHttpApi
    private val key = "sk-SEHR-GEHEIM-123"

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        api = TiinyHttpApi(server.url("/v1").toString(), { key }, client)
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private val req = ChatRequest("Qwen/Qwen3.8-27B", listOf(ChatTurn("user", "Hallo")), stream = true)

    @Test
    fun `Modellliste mit Bearer-Token`() = runTest {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"Qwen/Qwen3.8-27B","object":"model","context_window":262144}]}"""))
        val r = api.listModels()
        assertIs<ModelsResult.Ok>(r)
        assertEquals("Qwen/Qwen3.8-27B", r.models.single().id)
        assertEquals(262144, r.models.single().contextWindow)
        val recorded = server.takeRequest()
        assertEquals("/v1/models", recorded.path)
        assertEquals("Bearer $key", recorded.getHeader("Authorization"))
    }

    @Test
    fun `leere Modellliste wird als leer gemeldet`() = runTest {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[]}"""))
        assertEquals(ModelsResult.Ok(emptyList()), api.listModels())
    }

    @Test
    fun `401 ohne Token ist Authentifizierungsfehler`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"error": "unauthorized", "reason": "missing_token", "message": "Missing bearer token"}""",
            ),
        )
        val r = api.listModels()
        assertIs<ModelsResult.Err>(r)
        assertIs<ApiError.Auth>(r.error)
    }

    @Test
    fun `502 bei gesperrtem Geraet ist Gateway-Fehler`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>502 Bad Gateway</html>"))
        val r = api.chat(req) {}
        assertIs<ChatOutcome.Failed>(r)
        assertIs<ApiError.Gateway>(r.error)
    }

    @Test
    fun `Verbindungsfehler wird erkannt`() = runTest {
        val dead = TiinyHttpApi("http://127.0.0.1:1/v1", { key }, OkHttpClient())
        val r = dead.listModels()
        assertIs<ModelsResult.Err>(r)
        assertIs<ApiError.Connection>(r.error)
    }

    @Test
    fun `nicht-streamende Antwort im bestaetigten Format`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","content":"Verbindung funktioniert","reasoning_content":"Denken"}}],"created":1,"model":"/model/model.q8_0.gguf","object":"chat.completion","timings":{"predicted_n":5}}""",
            ),
        )
        val r = api.chat(req.copy(stream = false)) {}
        assertEquals(ChatOutcome.Complete("Verbindung funktioniert", "/model/model.q8_0.gguf"), r)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("\"role\":\"user\""))
    }

    @Test
    fun `Streaming mit Denkausgabe und usage-Teilstueck ohne choices`() = runTest {
        val sse = listOf(
            """{"model":"m","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"Hmm"}}]}""",
            """{"model":"m","choices":[{"index":0,"delta":{"content":"Hal"}}]}""",
            """{"model":"m","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}""",
            """{"model":"m","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}""",
            "[DONE]",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        val events = mutableListOf<ChatEvent>()
        val r = api.chat(req) { events += it }
        assertEquals(ChatOutcome.Complete("Hallo", "m"), r)
        assertEquals(listOf("Hal", "lo"), events.filterIsInstance<ChatEvent.ContentDelta>().map { it.text })
        assertEquals(listOf("Hmm"), events.filterIsInstance<ChatEvent.ReasoningDelta>().map { it.text })
    }

    @Test
    fun `abgebrochener Stream ist unvollstaendig`() = runTest {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Teil\"}}]}\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        val r = api.chat(req) {}
        assertEquals(ChatOutcome.Incomplete("Teil", null, "stream_ended"), r)
    }

    @Test
    fun `Token-Grenze ergibt unvollstaendige Antwort`() {
        val p = OpenAiWire.StreamParser {}
        p.feedLine("""data: {"choices":[{"delta":{"reasoning_content":"lang"},"finish_reason":"length"}]}""")
        p.feedLine("")
        assertEquals(ChatOutcome.Incomplete("", null, "length"), p.finish())
    }

    @Test
    fun `Fehlerobjekt im Stream wird gemeldet`() {
        val p = OpenAiWire.StreamParser {}
        p.feedLine("""data: {"error":{"message":"the request exceeds the available context size","code":400}}""")
        p.feedLine("")
        val r = p.finish()
        assertIs<ChatOutcome.Failed>(r)
        assertIs<ApiError.ContextLimit>(r.error)
    }

    @Test
    fun `504 ist Zeitueberschreitung, 404 Modell ist nicht verfuegbar`() {
        assertIs<ApiError.Timeout>(OpenAiWire.httpError(504, """{"error":"Upstream model server timed out."}"""))
        assertIs<ApiError.ModelUnavailable>(OpenAiWire.httpError(404, """{"error":{"message":"model not loaded"}}"""))
    }

    @Test
    fun `Anfrage-Aufbau - Reihenfolge, nur vollstaendige Antworten, kein Kuerzen`() {
        fun m(id: String, role: Role, c: String, reply: String? = null, st: MessageStatus = MessageStatus.COMPLETE) =
            Message(id, role, c, "t", reply, st, null)
        val history = listOf(
            m("u1", Role.USER, "Frage 1"),
            m("a1", Role.ASSISTANT, "kaputt", "u1", MessageStatus.FAILED),
            m("a2", Role.ASSISTANT, "Antwort 1", "u1"),
            m("u2", Role.USER, "Frage 2"),
            m("a3", Role.ASSISTANT, "halb", "u2", MessageStatus.INCOMPLETE),
            m("u3", Role.USER, "Frage 3"),
        )
        val r = RequestBuilder.build(RequestParts("", null, history), "m", stream = true)
        assertEquals(
            listOf("user" to "Frage 1", "assistant" to "Antwort 1", "user" to "Frage 2", "user" to "Frage 3"),
            r.messages.map { it.role to it.content },
        )
        assertNull(RequestBuilder.system(RequestParts("  ", null, history)))
    }

    @Test
    fun `Kontextgrenze wird angezeigt, nicht gekuerzt`() {
        val big = ChatRequest("m", listOf(ChatTurn("user", "x".repeat(3000))), true)
        assertIs<ContextEstimate.Level.Over>(ContextEstimate.level(big, 500))
        assertIs<ContextEstimate.Level.Near>(ContextEstimate.level(big, 1200))
        assertIs<ContextEstimate.Level.Unknown>(ContextEstimate.level(big, null))
        assertEquals(1, big.messages.size)
    }
}
