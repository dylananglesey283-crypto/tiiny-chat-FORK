package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.chat.PersistState
import io.github.jenziner.nisaba.core.chat.SendResult
import io.github.jenziner.nisaba.core.format.Codec
import io.github.jenziner.nisaba.core.format.Decoded
import io.github.jenziner.nisaba.core.format.HistoryFile
import io.github.jenziner.nisaba.core.format.Role
import io.github.jenziner.nisaba.core.storage.StorageError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Testgruppe C: Speicherverlust während eines Projekt-Chats. */
class C_StorageLossTest {

    private fun history(f: Fixture, session: io.github.jenziner.nisaba.core.chat.ChatSession): HistoryFile =
        (Codec.decode(HistoryFile.serializer(), HistoryFile.KIND, f.historyText(session).toByteArray()) as Decoded.Ok).value

    @Test
    fun `C1 bis C4 laufende Antwort wird angezeigt, markiert, gesperrt und nach Wiederverbindung gespeichert`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        chat.send("Eins", MODEL)
        f.api.during = { f.storage.unavailable = true } // USB wird während der Antwort getrennt
        val r = chat.send("Zwei", MODEL)
        f.api.during = null

        // C1: Antwort vollständig im Arbeitsspeicher
        assertIs<SendResult.Done>(r)
        val last = chat.state.value.messages.last()
        assertEquals(Role.ASSISTANT, last.message.role)
        assertEquals("Antwort 2", last.message.content)
        // C2: markiert als noch nicht gespeichert
        assertFalse(last.saved)
        assertIs<PersistState.Pending>(chat.state.value.persist)
        // C3: weitere Nachrichten gesperrt
        assertFalse(chat.state.value.canSend)
        assertIs<SendResult.Rejected>(chat.send("Drei", MODEL))
        assertEquals(2, f.api.requests.size)

        // C4: Wiederverbindung mit demselben Speicher
        f.storage.unavailable = false
        chat.setStorageReachable(true)
        assertEquals(PersistState.Saved, chat.state.value.persist)
        assertTrue(chat.state.value.messages.all { it.saved })
        val h = history(f, chat)
        assertEquals(listOf("Eins", "Antwort 1", "Zwei", "Antwort 2"), h.messages.map { it.content })
        assertTrue(chat.state.value.canSend)
    }

    @Test
    fun `C5 falscher Speicher fuehrt nicht zum Schreiben`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        chat.send("Eins", MODEL)
        val original = f.storage.text("nisaba-store.json")!!
        f.api.during = { f.storage.unavailable = true }
        chat.send("Zwei", MODEL)
        f.api.during = null

        // Anderer Speicher mit gleicher Struktur, aber anderer Kennung
        f.storage.unavailable = false
        f.storage.put("nisaba-store.json", original.replace(f.storeId, "ffffffff-0000-4000-8000-000000000000"))
        val before = f.historyText(chat)
        f.storage.writeLog.clear()
        chat.setStorageReachable(true)
        assertIs<PersistState.WrongStore>(chat.state.value.persist)
        assertEquals(before, f.historyText(chat))
        assertEquals(emptyList(), f.storage.writeLog)
        assertFalse(chat.state.value.canSend)

        // Richtiger Speicher wieder da
        f.storage.put("nisaba-store.json", original)
        chat.setStorageReachable(true)
        assertEquals(PersistState.Saved, chat.state.value.persist)
        assertEquals(4, history(f, chat).messages.size)
    }

    @Test
    fun `C5 geaenderte Chat-Datei wird nicht blind ueberschrieben`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        chat.send("Eins", MODEL)
        f.api.during = { f.storage.unavailable = true }
        chat.send("Zwei", MODEL)
        f.api.during = null
        f.storage.unavailable = false
        // Zwischenzeitlich anderswo verändert (z. B. am PC bearbeitet)
        val path = "${chat.chatRef!!.dir}/history.json"
        val changed = f.storage.text(path)!!.replace("Antwort 1", "Am PC geändert")
        f.storage.put(path, changed)

        chat.setStorageReachable(true)
        assertIs<PersistState.Conflict>(chat.state.value.persist)
        assertEquals(changed, f.storage.text(path))
        // Gesprächsstand bleibt im Arbeitsspeicher
        assertEquals(4, chat.state.value.messages.size)
        // Kein automatischer weiterer Versuch
        chat.setStorageReachable(true)
        assertEquals(changed, f.storage.text(path))
    }

    @Test
    fun `C5 andere Chat-Identitaet wird erkannt`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        chat.send("Eins", MODEL)
        f.api.during = { f.storage.unavailable = true }
        chat.send("Zwei", MODEL)
        f.api.during = null
        f.storage.unavailable = false
        val chatPath = "${chat.chatRef!!.dir}/chat.json"
        f.storage.put(chatPath, f.storage.text(chatPath)!!.replace(chat.chatRef!!.chat.chatId, "00000000-dead-4000-8000-000000000000"))
        chat.setStorageReachable(true)
        assertIs<PersistState.Conflict>(chat.state.value.persist)
    }

    @Test
    fun `C6 bestaetigungslos gelandeter Schreibvorgang erzeugt keine Duplikate`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        chat.send("Eins", MODEL)
        // Umbenennen tmp -> history.json gelingt, die Bestätigung geht verloren
        f.api.during = { f.storage.renameThenFail = { _, newName -> newName == "history.json" } }
        chat.send("Zwei", MODEL)
        f.api.during = null
        assertIs<PersistState.Pending>(chat.state.value.persist)
        chat.setStorageReachable(true)
        assertEquals(PersistState.Saved, chat.state.value.persist)
        val h = history(f, chat)
        assertEquals(listOf("Eins", "Antwort 1", "Zwei", "Antwort 2"), h.messages.map { it.content })
        assertEquals(h.messages.map { it.id }.distinct(), h.messages.map { it.id })
        // Danach normal weiter
        chat.send("Drei", MODEL)
        assertEquals(6, history(f, chat).messages.size)
    }

    @Test
    fun `C6 Wiederholung nach Modellfehler erzeugt keine doppelte Nutzernachricht`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        f.api.failNext()
        val r = chat.send("Frage", MODEL)
        assertIs<SendResult.Done>(r)
        assertEquals(1, chat.state.value.messages.size)
        assertEquals(chat.state.value.messages.single().message.id, chat.state.value.retryableUserMessage)
        chat.retry(MODEL)
        chat.retry(MODEL) // nichts mehr offen
        val h = history(f, chat)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), h.messages.map { it.role })
        assertEquals(h.messages[0].id, h.messages[1].replyTo)
        assertEquals(2, f.api.requests.size)
        assertEquals(1, f.api.requests[1].messages.count { it.role == "user" })
    }

    @Test
    fun `C7 Nutzernachricht nicht speicherbar - keine Modellanfrage, nach Wiederverbindung Antwort anfordern`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        chat.send("Eins", MODEL)
        f.storage.fault = { op, p -> if (op == FakeStorage.Op.CREATE_FILE && p.name.startsWith("history.json.tmp")) StorageError.Unavailable else null }
        val r = chat.send("Zwei", MODEL)
        assertIs<SendResult.HeldPending>(r)
        assertEquals(1, f.api.requests.size)
        assertEquals("Zwei", chat.state.value.messages.last().message.content)
        assertFalse(chat.state.value.messages.last().saved)
        f.storage.fault = { _, _ -> null }
        chat.setStorageReachable(true)
        assertEquals(PersistState.Saved, chat.state.value.persist)
        chat.retry(MODEL)
        assertEquals(listOf("Eins", "Antwort 1", "Zwei", "Antwort 2"), history(f, chat).messages.map { it.content })
    }

    @Test
    fun `C8 unvollstaendige Antwort wird als unvollstaendig gespeichert`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        f.api.next += io.github.jenziner.nisaba.core.api.ChatOutcome.Incomplete("Halb", "m", "length")
        chat.send("Frage", MODEL)
        val h = history(f, chat)
        assertEquals(io.github.jenziner.nisaba.core.format.MessageStatus.INCOMPLETE, h.messages.last().status)
        assertEquals(chat.state.value.messages[0].message.id, chat.state.value.retryableUserMessage)
    }
}
