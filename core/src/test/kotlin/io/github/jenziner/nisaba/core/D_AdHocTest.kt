package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.chat.ChatSession
import io.github.jenziner.nisaba.core.chat.PersistState
import io.github.jenziner.nisaba.core.chat.SendResult
import kotlinx.coroutines.test.runTest
import kotlin.reflect.full.memberFunctions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Testgruppe D: Ad-hoc-Chat. */
class D_AdHocTest {

    @Test
    fun `D1 keine dauerhafte Ablage`() = runTest {
        val f = Fixture()
        f.storage.writeLog.clear()
        val chat = f.adHoc()
        assertIs<SendResult.Done>(chat.send("Geheim", MODEL))
        chat.send("Noch etwas", MODEL)
        assertEquals(emptyList(), f.storage.writeLog)
        assertFalse(f.storage.allFiles().values.any { it.contains("Geheim") })
        assertEquals(PersistState.Memory, chat.state.value.persist)
        assertTrue(chat.state.value.messages.none { it.saved })
        assertNull(chat.chatRef)
    }

    @Test
    fun `D2 keine Wiederherstellung - neue Sitzung ist leer`() {
        val f = Fixture()
        assertEquals(emptyList(), f.adHoc().state.value.messages)
    }

    @Test
    fun `D3 Speichern ins Projekt nur auf ausdruecklichen Aufruf und ohne Projektanweisungen`() = runTest {
        val f = Fixture()
        f.storage.put(f.soulPath, "Projekt-Regel")
        val chat = f.adHoc()
        chat.send("Erste Frage", MODEL)
        chat.send("Zweite Frage", MODEL)
        // Der Ad-hoc-Chat selbst hat keine Funktion zum Speichern; er schreibt nie.
        val names = ChatSession::class.memberFunctions.map { it.name.lowercase() }
        assertFalse(names.any { it.contains("move") || it.contains("export") || it.contains("save") || it.contains("convert") })
        assertFalse(f.storage.allFiles().values.any { it.contains("Erste Frage") })
        val msgs = chat.state.value.messages.map { it.message }
        val begun = assertIs<io.github.jenziner.nisaba.core.store.ProjectStore.BeginResult.Started>(f.store.importAdHoc(f.project, msgs))
        assertEquals(io.github.jenziner.nisaba.core.format.ChatOrigin.AD_HOC, begun.opened.ref.chat.origin)
        assertEquals(io.github.jenziner.nisaba.core.format.SoulState.MISSING, begun.opened.snapshot.state)
        assertEquals("Erste Frage", begun.opened.ref.chat.title)
        // Nach dem Speichern: im Projekt vollständig vorhanden, Weiterführen ohne Projektanweisungen.
        val reopened = f.reopen(begun.opened.ref.chat.chatId)
        assertEquals(msgs, reopened.state.value.messages.map { it.message })
        f.api.requests.clear()
        assertIs<SendResult.Done>(reopened.send("Dritte Frage", MODEL))
        assertEquals(listOf<String?>(null), f.api.systemPrompts)
        assertEquals(6, f.store.openChat(reopened.chatRef!!).ok().messages.size)
    }

    @Test
    fun `D4 Verfuegbarwerden eines Speichers veraendert den Chat nicht`() = runTest {
        val f = Fixture()
        val chat = f.adHoc()
        chat.send("Hallo", MODEL)
        val before = chat.state.value
        chat.setStorageReachable(true)
        assertEquals(before, chat.state.value)
        f.storage.writeLog.clear()
        chat.setStorageReachable(false)
        assertEquals(before, chat.state.value)
        assertEquals(emptyList(), f.storage.writeLog)
    }

    @Test
    fun `D5 Verwerfen mit Inhalt braucht Bestaetigung`() = runTest {
        val chat = Fixture().adHoc()
        assertFalse(chat.state.value.discardNeedsConfirmation)
        chat.send("Hallo", MODEL)
        assertTrue(chat.state.value.discardNeedsConfirmation)
    }

    @Test
    fun `D6 Ad-hoc-Anfrage enthaelt keine Projektanweisungen`() = runTest {
        val f = Fixture()
        f.storage.put(f.soulPath, "Projekt-Regel")
        f.adHoc().send("Hallo", MODEL)
        assertEquals(listOf<String?>(null), f.api.systemPrompts)
    }
}
