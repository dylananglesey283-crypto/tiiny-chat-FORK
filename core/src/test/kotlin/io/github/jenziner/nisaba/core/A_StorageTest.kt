package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.format.Codec
import io.github.jenziner.nisaba.core.format.HistoryFile
import io.github.jenziner.nisaba.core.format.Message
import io.github.jenziner.nisaba.core.format.MessageStatus
import io.github.jenziner.nisaba.core.format.Role
import io.github.jenziner.nisaba.core.storage.ReadOutcome
import io.github.jenziner.nisaba.core.storage.RelPath
import io.github.jenziner.nisaba.core.storage.SafeReplace
import io.github.jenziner.nisaba.core.storage.StorageError
import io.github.jenziner.nisaba.core.storage.WriteOutcome
import io.github.jenziner.nisaba.core.storage.sha256Hex
import io.github.jenziner.nisaba.core.store.ProjectStore
import io.github.jenziner.nisaba.core.store.StoreResult
import io.github.jenziner.nisaba.core.store.StoreStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Testgruppe A: Projekt-Speicher. */
class A_StorageTest {

    @Test
    fun `A1 Speicherkennung bleibt nach Neustart gleich`() {
        val s = FakeStorage()
        val id = (ProjectStore(s, TestEnv()).initializeStore() as StoreStatus.Ready).storeId
        val again = ProjectStore(s, TestEnv()).openStore()
        assertEquals(StoreStatus.Ready(id), again)
    }

    @Test
    fun `A1 vorhandene Kennungsdatei wird nie ersetzt`() {
        val s = FakeStorage()
        val id = (ProjectStore(s, TestEnv()).initializeStore() as StoreStatus.Ready).storeId
        val second = ProjectStore(s, TestEnv(java.time.Instant.parse("2030-01-01T00:00:00Z"))).initializeStore()
        assertEquals(StoreStatus.Ready(id), second)
    }

    @Test
    fun `A1 nicht eingerichteter Ordner meldet ob er leer ist`() {
        val s = FakeStorage()
        assertEquals(StoreStatus.NotInitialized(isEmpty = true), ProjectStore(s, TestEnv()).openStore())
        s.put("fremd.txt", "x")
        assertEquals(StoreStatus.NotInitialized(isEmpty = false), ProjectStore(s, TestEnv()).openStore())
    }

    @Test
    fun `A2 Projekt schreiben und erneut lesen`() {
        val f = Fixture()
        val listing = ProjectStore(f.storage, TestEnv()).listProjects(f.storeId).ok()
        assertEquals(listOf("Mein Projekt"), listing.items.map { it.project.name })
        assertEquals("mein-projekt", listing.items.single().dir.toString())
        assertTrue(f.storage.exists("mein-projekt/chats"))
        assertTrue(f.storage.exists("mein-projekt/files"))
    }

    @Test
    fun `A2 gleicher Projektname ergibt eigenen Ordner`() {
        val f = Fixture()
        val second = f.store.createProject(f.storeId, "Mein Projekt").ok()
        assertEquals("mein-projekt-2", second.dir.toString())
        assertEquals(2, f.store.listProjects(f.storeId).ok().items.size)
    }

    @Test
    fun `A3 fehlende Berechtigung und nicht erreichbarer Speicher sind unterscheidbar`() {
        val s = FakeStorage()
        s.unavailable = true
        assertEquals(StoreStatus.Failure(StorageError.Unavailable), ProjectStore(s, TestEnv()).openStore())
        s.unavailable = false
        s.permissionDenied = true
        assertEquals(StoreStatus.Failure(StorageError.PermissionDenied), ProjectStore(s, TestEnv()).openStore())
    }

    @Test
    fun `A4 kein stiller Ersatzspeicher bei fehlendem Speicher`() = runTest {
        val f = Fixture()
        f.storage.writeLog.clear()
        f.storage.unavailable = true
        val r = f.store.createProject(f.storeId, "Neu")
        assertEquals(StoreResult.StorageFailure(StorageError.Unavailable), r)
        val chat = f.newChat()
        chat.setStorageReachable(false)
        val send = chat.send("Hallo", MODEL)
        assertIs<io.github.jenziner.nisaba.core.chat.SendResult.Rejected>(send)
        assertEquals(emptyList(), f.storage.writeLog)
        assertEquals(0, f.api.requests.size)
    }

    @Test
    fun `A5 beschaedigte project json wird gemeldet und nicht ueberschrieben`() {
        val f = Fixture()
        f.storage.put("kaputt/project.json", "{ nicht json")
        val listing = f.store.listProjects(f.storeId).ok()
        assertEquals(1, listing.items.size)
        assertEquals("kaputt/project.json", listing.problems.single().path.toString())
        assertEquals("{ nicht json", f.storage.text("kaputt/project.json"))
    }

    @Test
    fun `A5 unbekannte Felder und neuere Formatversion werden nicht gelesen`() {
        val f = Fixture()
        f.storage.put("neu/project.json", """{"format":"nisaba-project","formatVersion":4,"projectId":"x","name":"n","createdAt":"t"}""")
        f.storage.put("extra/project.json", """{"format":"nisaba-project","formatVersion":1,"projectId":"y","name":"n","createdAt":"t","farbe":"rot"}""")
        f.storage.put("fremd/project.json", """{"format":"etwas-anderes","formatVersion":1}""")
        val problems = f.store.listProjects(f.storeId).ok().problems.map { it.path.toString() to it.detail }.toMap()
        assertTrue(problems.getValue("neu/project.json").contains("format version"))
        assertTrue(problems.getValue("extra/project.json").contains("damaged"))
        assertTrue(problems.getValue("fremd/project.json").contains("file kind"))
    }

    @Test
    fun `A5 beschaedigte Kennungsdatei wird nicht ersetzt`() {
        val s = FakeStorage()
        s.put("nisaba-store.json", "kaputt")
        val st = ProjectStore(s, TestEnv())
        assertIs<StoreStatus.Invalid>(st.openStore())
        assertIs<StoreStatus.Invalid>(st.initializeStore())
        assertEquals("kaputt", s.text("nisaba-store.json"))
    }

    @Test
    fun `A5 beschaedigte history json blockiert Oeffnen und Speichern`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        chat.send("Erste Frage", MODEL)
        val path = "${chat.chatRef!!.dir}/history.json"
        f.storage.put(path, "{\"format\":\"nisaba-history\"")
        val listing = f.store.listChats(f.project).ok()
        assertIs<StoreResult.Invalid>(f.store.openChat(listing.items.single()))
        val r = chat.send("Zweite Frage", MODEL)
        assertIs<io.github.jenziner.nisaba.core.chat.SendResult.HeldPending>(r)
        assertIs<io.github.jenziner.nisaba.core.chat.PersistState.Conflict>(chat.state.value.persist)
        assertEquals("{\"format\":\"nisaba-history\"", f.storage.text(path))
    }

    @Test
    fun `A6 Ersetzen laesst die alte Datei bei Fehler unveraendert`() {
        val s = FakeStorage()
        s.put("a.json", "alt")
        val safe = SafeReplace(s) { "op1" }
        s.fault = { op, p -> if (op == FakeStorage.Op.CREATE_FILE && p.name.contains(".tmp-")) StorageError.Io("voll") else null }
        val r = safe.replace(RelPath.of("a.json"), "neu".toByteArray(), sha256Hex("alt".toByteArray()))
        assertIs<WriteOutcome.Failed>(r)
        assertEquals(mapOf("a.json" to "alt"), s.allFiles())
    }

    @Test
    fun `A6 Fehler beim Zuruecklesen wird erkannt und nichts ersetzt`() {
        val s = FakeStorage()
        s.put("a.json", "alt")
        s.corruptReadBackOnce = { it.name.contains(".tmp-") }
        val r = SafeReplace(s) { "op1" }.replace(RelPath.of("a.json"), "neu".toByteArray(), sha256Hex("alt".toByteArray()))
        assertIs<WriteOutcome.Failed>(r)
        assertEquals(mapOf("a.json" to "alt"), s.allFiles())
    }

    @Test
    fun `A6 unterbrochenes Ersetzen wird beim Lesen ohne Datenverlust abgeschlossen`() {
        val s = FakeStorage()
        // Zustand nach Schritt 3: alte Fassung beiseitegelegt, neue vollständig vorhanden.
        s.put("a.json.bak-op1", "alt")
        s.put("a.json.tmp-op1", "neu")
        val r = SafeReplace(s) { "op2" }.read(RelPath.of("a.json"))
        assertIs<ReadOutcome.Found>(r)
        assertEquals("neu", String(r.bytes))
        assertTrue(r.recovered)
        assertEquals("alt", s.text("a.json.bak-op1"))
    }

    @Test
    fun `A6 unklare Zwischendateien fuehren zu keiner Aktion`() {
        val s = FakeStorage()
        s.put("a.json.bak-op1", "alt")
        s.put("a.json.bak-op2", "älter")
        val r = SafeReplace(s) { "op3" }.read(RelPath.of("a.json"))
        assertIs<ReadOutcome.Inconsistent>(r)
        assertEquals(setOf("a.json.bak-op1", "a.json.bak-op2"), s.allFiles().keys)
    }

    @Test
    fun `A6 Konflikt wenn Datei zwischenzeitlich veraendert`() {
        val s = FakeStorage()
        s.put("a.json", "fremd geändert")
        val r = SafeReplace(s) { "op1" }.replace(RelPath.of("a.json"), "neu".toByteArray(), sha256Hex("alt".toByteArray()))
        assertIs<WriteOutcome.Conflict>(r)
        assertEquals(mapOf("a.json" to "fremd geändert"), s.allFiles())
    }

    @Test
    fun `A7 Dateiformat ist stabil und menschenlesbar`() {
        val h = HistoryFile(
            chatId = "c1", revision = 3,
            messages = listOf(Message("m1", Role.USER, "**Hallo**", "2026-11-13T13:05:00Z", null, MessageStatus.COMPLETE, null)),
        )
        val text = String(Codec.encode(HistoryFile.serializer(), h))
        assertTrue(text.contains("\"format\": \"nisaba-history\""))
        assertTrue(text.contains("\"role\": \"user\""))
        assertTrue(text.contains("\"status\": \"complete\""))
        val back = Codec.decode(HistoryFile.serializer(), HistoryFile.KIND, text.toByteArray())
        assertEquals(io.github.jenziner.nisaba.core.format.Decoded.Ok(h), back)
    }

    @Test
    fun `A8 Chat loeschen prueft die Identitaet`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        chat.send("Frage", MODEL)
        val ref = chat.chatRef!!
        f.storage.put("${ref.dir}/chat.json", f.storage.text("${ref.dir}/chat.json")!!.replace(ref.chat.chatId, "anderer"))
        assertIs<StoreResult.IdentityMismatch>(f.store.deleteChat(ref))
        assertTrue(f.storage.exists(ref.dir.toString()))
    }

    @Test
    fun `A8 Chat loeschen entfernt genau den Chat-Ordner`() = runTest {
        val f = Fixture()
        val a = f.newChat().also { it.send("A", MODEL) }
        val b = f.newChat().also { it.send("B", MODEL) }
        assertEquals(StoreResult.Ok(Unit), f.store.deleteChat(a.chatRef!!))
        assertEquals(listOf(b.chatRef!!.chat.chatId), f.store.listChats(f.project).ok().items.map { it.chat.chatId })
    }
}
