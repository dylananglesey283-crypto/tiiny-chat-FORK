package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.chat.ChatOptions
import io.github.jenziner.nisaba.core.chat.ChatSession
import io.github.jenziner.nisaba.core.chat.DocumentBudget
import io.github.jenziner.nisaba.core.chat.DocumentContent
import io.github.jenziner.nisaba.core.chat.DocumentLoad
import io.github.jenziner.nisaba.core.chat.DocumentSource
import io.github.jenziner.nisaba.core.chat.RequestBuilder
import io.github.jenziner.nisaba.core.chat.SendResult
import io.github.jenziner.nisaba.core.chat.SessionError
import io.github.jenziner.nisaba.core.docs.DocumentText
import io.github.jenziner.nisaba.core.docs.Extracted
import io.github.jenziner.nisaba.core.format.ChatOrigin
import io.github.jenziner.nisaba.core.store.DeletionPlan
import io.github.jenziner.nisaba.core.store.DocumentNames
import io.github.jenziner.nisaba.core.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tests für Version 0.4: Dokumente, chat.json v2, project.json v3, Dokumentgrenze. */
class G_V04Test {

    private val chatV1 = """{"format":"nisaba-chat","formatVersion":1,"chatId":"c-1","projectId":"%s","title":"Alt","titleSource":"auto","createdAt":"t"}"""

    @Test
    fun `G1 project json v2 wird gelesen, beim Speichern auf v3 migriert`() {
        val f = Fixture()
        val v2 = """{"format":"nisaba-project","formatVersion":2,"projectId":"p-2","name":"Zwei","createdAt":"t",""" +
            """"settings":{"reasoning":{"mode":"on","effort":"high"},"webSearch":true}}"""
        f.storage.put("zwei/project.json", v2)
        val ref = f.store.listProjects(f.storeId).ok().items.single { it.project.projectId == "p-2" }
        assertEquals(2, ref.sourceVersion)
        assertEquals(null, ref.project.settings.documentLimitTokens)
        assertTrue(ref.project.settings.webSearch)
        assertEquals(v2, f.storage.text("zwei/project.json"))
        val saved = f.store.updateProjectSettings(ref, ref.project.settings.copy(documentLimitTokens = 50_000)).ok()
        assertEquals(3, saved.sourceVersion)
        val text = f.storage.text("zwei/project.json")!!
        assertTrue(text.contains("\"formatVersion\": 3") && text.contains("\"documentLimitTokens\": 50000"))
    }

    @Test
    fun `G2 chat json v1 wird gelesen und erst bei Aenderung der Auswahl auf v2 migriert`() {
        val f = Fixture()
        val dir = "${f.project.dir}/chats/alt"
        f.storage.put("$dir/chat.json", chatV1.format(f.project.project.projectId))
        val chat = f.store.listChats(f.project).ok().items.single()
        assertEquals(emptyList(), chat.chat.documents)
        assertEquals(ChatOrigin.PROJECT, chat.chat.origin)
        assertEquals(chatV1.format(f.project.project.projectId), f.storage.text("$dir/chat.json"))
        val upd = f.store.updateChatDocuments(chat, listOf("a.md", "a.md", "b.txt")).ok()
        assertEquals(listOf("a.md", "b.txt"), upd.chat.documents)
        val text = f.storage.text("$dir/chat.json")!!
        assertTrue(text.contains("\"formatVersion\": 2") && text.contains("\"origin\": \"project\""))
        assertEquals(listOf("a.md", "b.txt"), f.store.listChats(f.project).ok().items.single().chat.documents)
    }

    @Test
    fun `G3 chat json v2 mit unbekanntem Feld gilt als beschaedigt und bleibt unveraendert`() {
        val f = Fixture()
        val bad = """{"format":"nisaba-chat","formatVersion":2,"chatId":"c","projectId":"${f.project.project.projectId}",""" +
            """"title":"t","titleSource":"auto","createdAt":"t","origin":"project","documents":[],"farbe":"rot"}"""
        f.storage.put("${f.project.dir}/chats/x/chat.json", bad)
        val l = f.store.listChats(f.project).ok()
        assertTrue(l.problems.single().detail.contains("damaged"))
        assertEquals(bad, f.storage.text("${f.project.dir}/chats/x/chat.json"))
    }

    @Test
    fun `G4 Dokumente anlegen, gleicher Name ersetzt nie, Loeschen nur einzeln`() {
        val f = Fixture()
        val a = f.store.addDocument(f.project, "Bericht.md", "eins".toByteArray()).ok()
        val b = f.store.addDocument(f.project, "Bericht.md", "zwei".toByteArray()).ok()
        val c = f.store.addDocument(f.project, "../../böse/.versteckt", "drei".toByteArray()).ok()
        assertEquals("Bericht.md", a.name)
        assertEquals("Bericht (2).md", b.name)
        assertEquals("versteckt", c.name)
        assertEquals("eins", f.storage.text("${f.project.dir}/files/Bericht.md"))
        val list = f.store.listDocuments(f.project).ok().items
        assertEquals(listOf("Bericht (2).md", "Bericht.md", "versteckt"), list.map { it.name })
        assertEquals(4L, list.first().size)
        assertIs<StoreResult.Ok<Unit>>(f.store.deleteDocument(a))
        assertEquals(listOf("Bericht (2).md", "versteckt"), f.store.listDocuments(f.project).ok().items.map { it.name })
        assertEquals("zwei", f.storage.text("${f.project.dir}/files/Bericht (2).md"))
    }

    @Test
    fun `G5 Projekt loeschen erst, wenn alle Dokumente geloescht sind`() {
        val f = Fixture()
        val d = f.store.addDocument(f.project, "x.txt", "x".toByteArray()).ok()
        val plan = assertIs<DeletionPlan.Blocked>(f.store.planProjectDeletion(f.project).ok())
        assertTrue(plan.reasons.single().contains("Delete all documents first"))
        assertIs<StoreResult.Conflict>(f.store.deleteProject(f.project))
        f.store.deleteDocument(d).ok()
        assertEquals(DeletionPlan.Allowed(0), f.store.planProjectDeletion(f.project).ok())
    }

    @Test
    fun `G6 Textgewinnung - Text, Windows-1252, DOCX, nicht unterstuetzte Typen`() {
        val dt = DocumentText(pdf = null)
        assertEquals(Extracted.Text("Grüezi\nwohl"), dt.extract("a.md", "Grüezi\r\nwohl".toByteArray()))
        assertEquals(Extracted.Text("Grüezi"), dt.extract("a.csv", "Grüezi".toByteArray(charset("windows-1252"))))
        assertIs<Extracted.Failed>(dt.extract("a.txt", byteArrayOf(1, 0, 2)))
        assertIs<Extracted.Failed>(dt.extract("a.txt", "  \n ".toByteArray()))
        assertIs<Extracted.Unsupported>(dt.extract("bild.png", byteArrayOf(1)))
        assertIs<Extracted.Unsupported>(dt.extract("a.pdf", byteArrayOf(1)))
        val xml = """<?xml version="1.0"?><w:document xmlns:w="x"><w:body>""" +
            """<w:p><w:r><w:t>Titel &amp; mehr</w:t></w:r></w:p>""" +
            """<w:p><w:r><w:t xml:space="preserve">Eins </w:t></w:r><w:r><w:tab/><w:t>zwei</w:t></w:r></w:p>""" +
            """<w:tbl><w:tr><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""" +
            """</w:body></w:document>"""
        val docx = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write("x".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("word/document.xml")); z.write(xml.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val t = assertIs<Extracted.Text>(dt.extract("brief.DOCX", docx)).text
        assertEquals("Titel & mehr\nEins \tzwei\nA\tB", t.replace(" \n", "\n").let { it })
        assertIs<Extracted.Failed>(dt.extract("kaputt.docx", "kein zip".toByteArray()))
    }

    @Test
    fun `G7 Dokumente stehen als eigener Abschnitt in der einzigen system-Nachricht`() {
        val sys = RequestBuilder.system(
            io.github.jenziner.nisaba.core.chat.RequestParts(
                "App-Regel", null, emptyList(), null,
                listOf(DocumentContent("a.md", "Inhalt A"), DocumentContent("b.txt", "Inhalt B")),
            ),
        )!!
        assertTrue(sys.startsWith("App-Regel\n\n# Dokumente"))
        assertTrue(sys.indexOf("## Dokument: a.md\n\nInhalt A") < sys.indexOf("## Dokument: b.txt\n\nInhalt B"))
    }

    private class MapSource(val docs: Map<String, String>) : DocumentSource {
        val loads = mutableListOf<List<String>>()
        override suspend fun load(names: List<String>): DocumentLoad {
            loads += names
            val missing = names.firstOrNull { it !in docs }
            return if (missing != null) DocumentLoad.Failed(missing, "no longer exists") else DocumentLoad.Ok(names.map { DocumentContent(it, docs.getValue(it)) })
        }
    }

    @Test
    fun `G8 aktive Dokumente gehen mit, Auswahl wird beim ersten Senden in chat json gespeichert`() = runTest {
        val f = Fixture()
        val src = MapSource(mapOf("a.md" to "Geheimnis-ABC"))
        val chat = ChatSession.newProjectChat(
            f.env, f.api, f.store, f.project, { "" }, { true }, Dispatchers.Unconfined,
            options = { ChatOptions(documentLimitTokens = 1000) }, documents = src,
        )
        chat.setDocuments(listOf("a.md")).ok()
        assertIs<SendResult.Done>(chat.send("Frage", MODEL))
        assertTrue(f.api.systemPrompts.single()!!.contains("Geheimnis-ABC"))
        assertEquals(listOf("a.md"), f.store.listChats(f.project).ok().items.single().chat.documents)
        // Dokumenttext wird nicht im Verlauf gespeichert.
        assertTrue(!f.historyText(chat).contains("Geheimnis-ABC"))
        // Abwählen wird sofort gespeichert.
        chat.setDocuments(emptyList()).ok()
        assertEquals(emptyList(), f.store.listChats(f.project).ok().items.single().chat.documents)
        chat.send("Noch eine", MODEL)
        assertTrue(f.api.systemPrompts.last() == null)
    }

    @Test
    fun `G9 zu grosse oder fehlende Dokumente - nichts gespeichert, nichts gesendet`() = runTest {
        val f = Fixture()
        val src = MapSource(mapOf("gross.txt" to "x".repeat(3000)))
        val chat = ChatSession.newProjectChat(
            f.env, f.api, f.store, f.project, { "" }, { true }, Dispatchers.Unconfined,
            options = { ChatOptions(documentLimitTokens = 500) }, documents = src,
        )
        chat.setDocuments(listOf("gross.txt")).ok()
        val r = assertIs<SendResult.Rejected>(chat.send("Frage", MODEL))
        assertTrue(r.reason.contains("limit 500"))
        assertIs<SessionError.Documents>(chat.state.value.lastError)
        assertEquals(0, f.api.requests.size)
        assertEquals(emptyList(), f.store.listChats(f.project).ok().items)
        chat.setDocuments(listOf("weg.md")).ok()
        assertTrue(assertIs<SendResult.Rejected>(chat.send("Frage", MODEL)).reason.contains("weg.md"))
        assertEquals(0, f.api.requests.size)
    }

    @Test
    fun `G10 wirksame Dokumentgrenze`() {
        assertEquals(16_384, DocumentBudget.limit(32_768, 50_000, 100_000))
        assertEquals(50_000, DocumentBudget.limit(null, 50_000, 100_000))
        assertEquals(100_000, DocumentBudget.limit(null, null, 100_000))
        assertEquals("100'000", io.github.jenziner.nisaba.core.chat.swissInt(100_000))
    }

    @Test
    fun `G11 Dateinamen werden bereinigt und nummeriert`() {
        assertEquals("a.pdf", DocumentNames.sanitize("C:\\Users\\x\\a.pdf"))
        assertEquals("document", DocumentNames.sanitize("..."))
        assertEquals("b (3).txt", DocumentNames.numbered("b.txt", 3))
        assertEquals("README (2)", DocumentNames.numbered("README", 2))
        assertTrue(DocumentNames.sanitize("x".repeat(300) + ".docx").let { it.length <= 120 && it.endsWith(".docx") })
    }
}
