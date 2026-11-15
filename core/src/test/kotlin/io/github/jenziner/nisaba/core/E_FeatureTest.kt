package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.api.ApiError
import io.github.jenziner.nisaba.core.chat.SessionError
import io.github.jenziner.nisaba.core.format.TitleSource
import io.github.jenziner.nisaba.core.storage.StorageError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Testgruppe E: Titel, Fehlerarten. */
class E_FeatureTest {

    @Test
    fun `E1 Titel entsteht lokal ohne zusaetzliche Modellanfrage`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        chat.send("  Wie plane ich\n\neinen   Flug nach Sion?  ", MODEL)
        assertEquals("Wie plane ich einen Flug nach Sion?", chat.state.value.title)
        assertEquals(1, f.api.requests.size)
        assertEquals(0, f.api.modelsCalls)
        assertEquals(TitleSource.AUTO, chat.chatRef!!.chat.titleSource)
    }

    @Test
    fun `E1 Titel wird an einer Wortgrenze gekuerzt`() {
        val t = Titles.fromFirstMessage("Dies ist eine sehr lange erste Nachricht, die deutlich mehr als sechzig Zeichen enthält und gekürzt wird")
        assertTrue(t.length <= Titles.MAX_LENGTH, t)
        assertTrue(t.endsWith("…"))
        assertEquals("Dies ist eine sehr lange erste Nachricht, die deutlich mehr…", t)
    }

    @Test
    fun `E2 manuelle Titelaenderung bleibt erhalten`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        chat.send("Erste Frage", MODEL)
        chat.rename("Mein Titel").ok()
        chat.send("Zweite Frage mit anderem Inhalt", MODEL)
        val reopened = f.reopen(chat.chatRef!!.chat.chatId)
        assertEquals("Mein Titel", reopened.state.value.title)
        assertEquals(TitleSource.MANUAL, reopened.chatRef!!.chat.titleSource)
    }

    @Test
    fun `E3 Modellfehler und Speicherfehler sind unterscheidbar`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        f.api.failNext(ApiError.Auth(401, "missing_token"))
        chat.send("Frage", MODEL)
        assertIs<SessionError.Model>(chat.state.value.lastError)

        val other = f.newChat()
        f.storage.fault = { op, _ -> if (op == FakeStorage.Op.CREATE_DIR) StorageError.Io("voll") else null }
        other.send("Frage", MODEL)
        assertIs<SessionError.Storage>(other.state.value.lastError)
    }

    @Test
    fun `E4 Projektdateien enthalten keine Zugangsdaten`() = runTest {
        // Der API-Key gelangt nur über TiinyHttpApi in einen HTTP-Header (siehe ApiTest).
        // Hier: Weder Sitzung noch Speicher kennen den Key.
        val f = Fixture()
        val secret = "sk-SEHR-GEHEIM-123"
        val chat = f.newChat()
        chat.send("Hallo", MODEL)
        assertTrue(f.storage.allFiles().values.none { it.contains(secret) })
        assertTrue(f.storage.allFiles().keys.none { it.contains("key", ignoreCase = true) })
    }

    @Test
    fun `E5 antwortendes Modell wird pro Antwort gespeichert`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        chat.send("Frage", MODEL)
        assertTrue(f.historyText(chat).contains("\"model\": \"/model/model.q8_0.gguf\""))
    }

    @Test
    fun `E6 Doppelsenden waehrend laufender Anfrage wird abgewiesen`() = runTest {
        val f = Fixture()
        val chat = f.newChat()
        var inner: io.github.jenziner.nisaba.core.chat.SendResult? = null
        f.api.during = {
            // Zweiter Aufruf, während die erste Anfrage läuft
            kotlinx.coroutines.runBlocking { inner = chat.send("Doppelt", MODEL) }
        }
        chat.send("Einmal", MODEL)
        assertIs<io.github.jenziner.nisaba.core.chat.SendResult.Rejected>(inner)
        assertEquals(1, f.api.requests.size)
        assertEquals(listOf("Einmal", "Antwort 1"), chat.state.value.messages.map { it.message.content })
    }
}
