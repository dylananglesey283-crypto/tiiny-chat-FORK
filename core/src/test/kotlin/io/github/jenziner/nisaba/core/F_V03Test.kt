package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.api.ChatRequest
import io.github.jenziner.nisaba.core.api.ChatTurn
import io.github.jenziner.nisaba.core.api.ModelsResult
import io.github.jenziner.nisaba.core.api.OpenAiWire
import io.github.jenziner.nisaba.core.api.ThinkingInfo
import io.github.jenziner.nisaba.core.api.ThinkingParams
import io.github.jenziner.nisaba.core.chat.ChatOptions
import io.github.jenziner.nisaba.core.chat.ChatSession
import io.github.jenziner.nisaba.core.chat.ReasoningResolver
import io.github.jenziner.nisaba.core.chat.RequestBuilder
import io.github.jenziner.nisaba.core.chat.SendResult
import io.github.jenziner.nisaba.core.chat.SessionError
import io.github.jenziner.nisaba.core.format.ProjectSettings
import io.github.jenziner.nisaba.core.format.ReasoningMode
import io.github.jenziner.nisaba.core.format.ReasoningSetting
import io.github.jenziner.nisaba.core.search.SearchOutcome
import io.github.jenziner.nisaba.core.search.SearchQuery
import io.github.jenziner.nisaba.core.search.HtmlSearch
import io.github.jenziner.nisaba.core.search.SearchEngine
import io.github.jenziner.nisaba.core.search.WebResult
import io.github.jenziner.nisaba.core.search.WebSearch
import io.github.jenziner.nisaba.core.store.DeletionPlan
import io.github.jenziner.nisaba.core.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests für Version 0.3: project.json v2, Projekt löschen, Reasoning, Websuche. */
class F_V03Test {
    private val v1 = """{"format":"nisaba-project","formatVersion":1,"projectId":"p-1","name":"Alt","createdAt":"t"}"""

    @Test
    fun `F1 project json v1 wird gelesen und nicht umgeschrieben`() {
        val f = Fixture()
        f.storage.put("alt/project.json", v1)
        val ref = f.store.listProjects(f.storeId).ok().items.single { it.project.projectId == "p-1" }
        assertEquals(1, ref.sourceVersion)
        assertEquals(ProjectSettings.DEFAULT, ref.project.settings)
        assertEquals(v1, f.storage.text("alt/project.json"))
    }

    @Test
    fun `F2 neue Projekte werden in der aktuellen Version (ab 0_4 v3) angelegt`() {
        val f = Fixture()
        val text = f.storage.text("${f.project.dir}/project.json")!!
        assertTrue(text.contains("\"formatVersion\": 3"))
        assertTrue(text.contains("\"settings\""))
        assertTrue(text.contains("\"documentLimitTokens\": null"))
        assertEquals(3, f.project.sourceVersion)
    }

    @Test
    fun `F3 Einstellungen speichern migriert v1 auf die aktuelle Version und bleibt lesbar`() {
        val f = Fixture()
        f.storage.put("alt/project.json", v1)
        val ref = f.store.listProjects(f.storeId).ok().items.single { it.project.projectId == "p-1" }
        val s = ProjectSettings(ReasoningSetting(ReasoningMode.ON, "high"), webSearch = true)
        val saved = f.store.updateProjectSettings(ref, s).ok()
        assertEquals(3, saved.sourceVersion)
        val again = ProjectStoreFresh(f).single { it.project.projectId == "p-1" }
        assertEquals(s, again.project.settings)
        assertEquals("Alt", again.project.name)
        assertEquals("p-1", again.project.projectId)
    }

    @Test
    fun `F4 v2 mit unbekanntem Feld gilt als beschaedigt und bleibt unveraendert`() {
        val f = Fixture()
        val bad = """{"format":"nisaba-project","formatVersion":2,"projectId":"q","name":"n","createdAt":"t",""" +
            """"settings":{"reasoning":{"mode":"on","effort":null},"webSearch":false,"farbe":"rot"}}"""
        f.storage.put("bad/project.json", bad)
        val problems = f.store.listProjects(f.storeId).ok().problems
        assertTrue(problems.single { it.path.toString() == "bad/project.json" }.detail.contains("damaged"))
        assertEquals(bad, f.storage.text("bad/project.json"))
    }

    @Test
    fun `F5 Einstellungen nach fremder Aenderung werden nicht gespeichert`() {
        val f = Fixture()
        val path = "${f.project.dir}/project.json"
        val changed = f.storage.text(path)!!.replace("Mein Projekt", "Extern geändert")
        f.storage.put(path, changed)
        val r = f.store.updateProjectSettings(f.project, ProjectSettings.DEFAULT.copy(webSearch = true))
        assertIs<StoreResult.Conflict>(r)
        assertEquals(changed, f.storage.text(path))
    }

    @Test
    fun `F6 Projekt mit Chats wird nach Pruefung geloescht`() = runTest {
        val f = Fixture()
        f.newChat().send("Hallo", MODEL)
        f.storage.put("${f.project.dir}/SOUL.md", "Regel")
        val plan = f.store.planProjectDeletion(f.project).ok()
        assertEquals(DeletionPlan.Allowed(1), plan)
        assertIs<StoreResult.Ok<Unit>>(f.store.deleteProject(f.project))
        assertTrue(f.storage.allFiles().keys.none { it.startsWith("${f.project.dir}/") })
        assertTrue(f.storage.exists("nisaba-store.json"))
    }

    @Test
    fun `F7 Dateien in files oder fremde Eintraege sperren das Loeschen`() = runTest {
        val f = Fixture()
        f.newChat().send("Hallo", MODEL)
        f.storage.put("${f.project.dir}/files/geheim.pdf", "x")
        f.storage.put("${f.project.dir}/notizen.txt", "y")
        val chatDir = f.store.listChats(f.project).ok().items.single().dir
        f.storage.put("$chatDir/fremd.bin", "z")
        val plan = assertIs<DeletionPlan.Blocked>(f.store.planProjectDeletion(f.project).ok())
        assertEquals(3, plan.reasons.size)
        assertIs<StoreResult.Conflict>(f.store.deleteProject(f.project))
        assertEquals("x", f.storage.text("${f.project.dir}/files/geheim.pdf"))
        assertEquals("y", f.storage.text("${f.project.dir}/notizen.txt"))
    }

    @Test
    fun `F8 Loeschen prueft die Projektidentitaet`() {
        val f = Fixture()
        val path = "${f.project.dir}/project.json"
        f.storage.put(path, f.storage.text(path)!!.replace(f.project.project.projectId, "anderes-projekt"))
        assertIs<StoreResult.IdentityMismatch>(f.store.deleteProject(f.project))
        assertTrue(f.storage.exists(path))
    }

    @Test
    fun `F9 Vorrangregel steht zwischen App- und Projektanweisungen`() = runTest {
        val f = Fixture()
        f.storage.put(f.soulPath, "Projekt")
        f.newChat(appInstructions = "App").send("Hallo", MODEL)
        val sys = f.api.systemPrompts.single()!!
        assertTrue(sys.indexOf("App") < sys.indexOf(RequestBuilder.PRECEDENCE))
        assertTrue(sys.indexOf(RequestBuilder.PRECEDENCE) < sys.indexOf("Projekt\n").coerceAtLeast(sys.lastIndexOf("Projekt")))
    }

    @Test
    fun `F10 Reasoning-Felder werden nur gesendet wenn gesetzt`() {
        val base = ChatRequest("m", listOf(ChatTurn("user", "x")), stream = false)
        val plain = OpenAiWire.encodeRequest(base)
        assertFalse(plain.contains("thinking_enabled") || plain.contains("reasoning_effort"))
        val on = OpenAiWire.encodeRequest(base.copy(thinking = ThinkingParams(true, "medium")))
        assertTrue(on.contains("\"thinking_enabled\":true"))
        assertTrue(on.contains("\"reasoning_effort\":\"medium\""))
    }

    @Test
    fun `F11 Denk-Faehigkeiten aus der Modellliste`() {
        val body = """{"data":[{"id":"a","thinking":{"supported":true,"toggleable":true,"enabled_by_default":true,""" +
            """"levels":["low","medium","high"],"default_level":"medium"}},{"id":"b","thinking":false},{"id":"c"}]}"""
        val models = OpenAiWire.parseModels(body)!!
        assertEquals(ThinkingInfo(true, true, true, listOf("low", "medium", "high"), "medium"), models[0].thinking)
        assertEquals(false, models[1].thinking?.supported)
        assertNull(models[2].thinking)
    }

    @Test
    fun `F12 Reasoning wird nie geraten`() {
        val full = ThinkingInfo(true, true, true, listOf("low", "high"), "low")
        assertNull(ReasoningResolver.resolve(ReasoningSetting.DEFAULT, full).params)
        assertEquals(ThinkingParams(false, null), ReasoningResolver.resolve(ReasoningSetting(ReasoningMode.OFF, null), full).params)
        assertEquals(ThinkingParams(true, "high"), ReasoningResolver.resolve(ReasoningSetting(ReasoningMode.ON, "high"), full).params)
        val r = ReasoningResolver.resolve(ReasoningSetting(ReasoningMode.ON, "max"), full)
        assertEquals(ThinkingParams(true, null), r.params)
        assertTrue(r.note!!.contains("max"))
        val unknown = ReasoningResolver.resolve(ReasoningSetting(ReasoningMode.OFF, null), null)
        assertNull(unknown.params)
        assertTrue(unknown.note != null)
        val fixed = ReasoningResolver.resolve(ReasoningSetting(ReasoningMode.OFF, null), ThinkingInfo(true, false, true, emptyList(), null))
        assertNull(fixed.params)
    }

    @Test
    fun `F13 Direktsuche - Anfrage an die Suchmaschine und Fehlerfaelle`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val html = javaClass.getResource("/search/ddg.html")!!.readText()
            server.enqueue(MockResponse().setBody(html))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody("<html><body><div class=anomaly-modal>captcha</div></body></html>"))
            val s = HtmlSearch(SearchEngine.DUCKDUCKGO, OkHttpClient(), baseUrl = server.url("/html/"))
            val ok = assertIs<SearchOutcome.Ok>(s.search("Wetter Bern"))
            assertEquals(5, ok.results.size)
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("q=Wetter+Bern", req.body.readUtf8())
            assertTrue(req.getHeader("User-Agent")!!.contains("Mobile"))
            assertTrue(assertIs<SearchOutcome.Failed>(s.search("x")).detail.contains("429"))
            assertTrue(assertIs<SearchOutcome.Failed>(s.search("x")).detail.contains("captcha"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `F14 Websuche fliesst nur in die Anfrage ein, nicht in die Ablage`() = runTest {
        val f = Fixture()
        val search = object : WebSearch {
            var queries = mutableListOf<String>()
            override suspend fun search(query: String): SearchOutcome {
                queries += query
                return SearchOutcome.Ok(listOf(WebResult("Treffer", "https://example.org/t", "Auszug-XYZ")))
            }
        }
        val chat = ChatSession.newProjectChat(
            f.env, f.api, f.store, f.project, { "" }, { true }, Dispatchers.Unconfined,
            options = { ChatOptions(ThinkingParams(true, null), search) },
        )
        assertIs<SendResult.Done>(chat.send("Was gibt es Neues?", MODEL))
        assertEquals(listOf("Was gibt es Neues?"), search.queries)
        val sys = f.api.systemPrompts.single()!!
        assertTrue(sys.startsWith(RequestBuilder.WEB_HEADING))
        assertTrue(sys.contains("[1] Treffer\nhttps://example.org/t\nAuszug-XYZ"))
        assertEquals(ThinkingParams(true, null), f.api.requests.single().thinking)
        assertEquals("system", f.api.requests.single().messages.first().role)
        assertTrue(f.storage.allFiles().values.none { it.contains("Auszug-XYZ") })
        assertEquals("https://example.org/t", chat.state.value.messages.last().web!!.results.single().url)
    }

    @Test
    fun `F15 fehlgeschlagene Websuche stellt keine Modellanfrage, Nachricht bleibt gespeichert`() = runTest {
        val f = Fixture()
        val failing = object : WebSearch {
            override suspend fun search(query: String) = SearchOutcome.Failed("DuckDuckGo not reachable")
        }
        var useSearch = true
        val chat = ChatSession.newProjectChat(
            f.env, f.api, f.store, f.project, { "" }, { true }, Dispatchers.Unconfined,
            options = { ChatOptions(webSearch = if (useSearch) failing else null) },
        )
        assertIs<SendResult.SearchFailed>(chat.send("Frage", MODEL))
        assertEquals(0, f.api.requests.size)
        assertIs<SessionError.Search>(chat.state.value.lastError)
        assertTrue(f.historyText(chat).contains("Frage"))
        assertTrue(chat.state.value.retryableUserMessage != null)
        useSearch = false
        assertIs<SendResult.Done>(chat.retry(MODEL))
        assertEquals(1, f.api.requests.size)
    }

    @Test
    fun `F16 Suchbegriff wird gekuerzt`() {
        assertEquals("a b", SearchQuery.from("  a \n\n b "))
        val long = (1..200).joinToString(" ") { "wort$it" }
        assertTrue(SearchQuery.from(long).length <= SearchQuery.MAX_CHARS)
    }

    private fun ProjectStoreFresh(f: Fixture) =
        io.github.jenziner.nisaba.core.store.ProjectStore(f.storage, TestEnv()).listProjects(f.storeId).ok().items
}
