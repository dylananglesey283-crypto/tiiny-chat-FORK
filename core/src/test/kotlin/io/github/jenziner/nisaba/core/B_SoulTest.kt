package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.chat.RequestBuilder
import io.github.jenziner.nisaba.core.chat.SendResult
import io.github.jenziner.nisaba.core.format.SoulState
import io.github.jenziner.nisaba.core.storage.StorageError
import io.github.jenziner.nisaba.core.store.SoulRead
import io.github.jenziner.nisaba.core.store.StoreResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Testgruppe B: SOUL.md und unveränderlicher Snapshot. */
class B_SoulTest {

    @Test
    fun `B1 Snapshot entsteht erst beim ersten Senden`() = runTest {
        val f = Fixture()
        f.storage.put(f.soulPath, "Sei knapp.")
        f.storage.writeLog.clear()
        val chat = f.newChat()
        assertEquals(emptyList(), f.storage.writeLog, "Öffnen eines leeren Chats legt nichts an")
        chat.send("Hallo", MODEL)
        val snap = f.storage.text("${chat.chatRef!!.dir}/soul-snapshot.json")!!
        assertTrue(snap.contains("\"state\": \"present\""))
        assertTrue(snap.contains("Sei knapp."))
        assertEquals("# Projektanweisungen\n\nSei knapp.", f.api.systemPrompts.single())
    }

    @Test
    fun `B2 Aenderung vor dem ersten Senden wird beruecksichtigt`() = runTest {
        val f = Fixture()
        f.storage.put(f.soulPath, "Alt")
        val chat = f.newChat()
        f.storage.put(f.soulPath, "Neu")
        chat.send("Hallo", MODEL)
        assertEquals("# Projektanweisungen\n\nNeu", f.api.systemPrompts.single())
    }

    @Test
    fun `B3 Aenderung nach dem ersten Senden beeinflusst den Chat nicht`() = runTest {
        val f = Fixture()
        f.storage.put(f.soulPath, "Alt")
        val chat = f.newChat()
        chat.send("Eins", MODEL)
        val loaded = f.store.readSoul(f.project)
        f.store.writeSoul(f.project, loaded, "Neu").ok()
        chat.send("Zwei", MODEL)
        assertEquals(listOf("# Projektanweisungen\n\nAlt", "# Projektanweisungen\n\nAlt"), f.api.systemPrompts)
    }

    @Test
    fun `B4 neuer Chat verwendet den geaenderten Inhalt`() = runTest {
        val f = Fixture()
        f.storage.put(f.soulPath, "Alt")
        f.newChat().send("Eins", MODEL)
        f.store.writeSoul(f.project, f.store.readSoul(f.project), "Neu").ok()
        f.newChat().send("Zwei", MODEL)
        assertEquals(listOf("# Projektanweisungen\n\nAlt", "# Projektanweisungen\n\nNeu"), f.api.systemPrompts)
    }

    @Test
    fun `B5 fehlende und leere SOUL md werden unterschieden`() = runTest {
        val f = Fixture()
        val missing = f.newChat().also { it.send("ohne", MODEL) }
        f.storage.put(f.soulPath, "  \n")
        val empty = f.newChat().also { it.send("leer", MODEL) }
        assertEquals(SoulState.MISSING, missing.snapshotForTest!!.state)
        assertNull(missing.snapshotForTest!!.content)
        assertEquals(SoulState.EMPTY, empty.snapshotForTest!!.state)
        assertEquals("  \n", empty.snapshotForTest!!.content)
        assertEquals(listOf(null, null), f.api.systemPrompts)
    }

    @Test
    fun `B6 Lesefehler ist keine fehlende Datei und blockiert das Senden`() = runTest {
        val f = Fixture()
        f.storage.put(f.soulPath, "Inhalt")
        f.storage.fault = { op, p -> if (op == FakeStorage.Op.READ && p.name == "SOUL.md") StorageError.Io("Lesefehler") else null }
        assertIs<SoulRead.Error>(f.store.readSoul(f.project))
        f.storage.writeLog.clear()
        val r = f.newChat().send("Hallo", MODEL)
        assertIs<SendResult.NotSaved>(r)
        assertEquals(0, f.api.requests.size)
        assertEquals(emptyList(), f.storage.writeLog)
    }

    @Test
    fun `B6 ungueltiges UTF-8 in SOUL md ist ein Lesefehler`() {
        val f = Fixture()
        f.storage.putBytes(f.soulPath, byteArrayOf(0xC3.toByte(), 0x28))
        assertIs<SoulRead.Error>(f.store.readSoul(f.project))
    }

    @Test
    fun `B6 Snapshot kann nicht gespeichert werden - keine Modellanfrage`() = runTest {
        val f = Fixture()
        f.storage.fault = { op, p -> if (op == FakeStorage.Op.CREATE_FILE && p.name == "soul-snapshot.json") StorageError.Io("voll") else null }
        val r = f.newChat().send("Hallo", MODEL)
        assertIs<SendResult.NotSaved>(r)
        assertEquals(0, f.api.requests.size)
        assertEquals(emptyList(), f.store.listChats(f.project).ok().items, "angefangener Ordner wurde entfernt")
    }

    @Test
    fun `B7 Neustart erhaelt den Snapshot`() = runTest {
        val f = Fixture()
        f.storage.put(f.soulPath, "Alt")
        val chat = f.newChat().also { it.send("Eins", MODEL) }
        f.store.writeSoul(f.project, f.store.readSoul(f.project), "Neu").ok()
        val reopened = f.reopen(chat.chatRef!!.chat.chatId)
        reopened.send("Zwei", MODEL)
        assertEquals("# Projektanweisungen\n\nAlt", f.api.systemPrompts.last())
    }

    @Test
    fun `B8 Chat ohne Anweisungen bleibt ohne, auch wenn SOUL md spaeter entsteht`() = runTest {
        val f = Fixture()
        val chat = f.newChat().also { it.send("Eins", MODEL) }
        f.store.writeSoul(f.project, SoulRead.Missing, "Neu").ok()
        chat.send("Zwei", MODEL)
        f.reopen(chat.chatRef!!.chat.chatId).send("Drei", MODEL)
        assertEquals(listOf(null, null, null), f.api.systemPrompts)
    }

    @Test
    fun `B9 Snapshot-Datei wird nie ueberschrieben`() = runTest {
        val f = Fixture()
        f.storage.put(f.soulPath, "Alt")
        val chat = f.newChat().also { it.send("Eins", MODEL) }
        val path = "${chat.chatRef!!.dir}/soul-snapshot.json"
        val before = f.storage.text(path)
        chat.send("Zwei", MODEL)
        f.reopen(chat.chatRef!!.chat.chatId).send("Drei", MODEL)
        assertEquals(before, f.storage.text(path))
        assertFalse(f.storage.writeLog.any { it.contains("soul-snapshot.json") && !it.startsWith("create") })
    }

    @Test
    fun `B10 Editor speichert nicht ueber fremde Aenderung`() {
        val f = Fixture()
        f.storage.put(f.soulPath, "Alt")
        val loaded = f.store.readSoul(f.project)
        f.storage.put(f.soulPath, "Von aussen geändert")
        assertIs<StoreResult.Conflict>(f.store.writeSoul(f.project, loaded, "Mein Text"))
        assertEquals("Von aussen geändert", f.storage.text(f.soulPath))
    }

    @Test
    fun `B10 Editor speichert nicht nach Lesefehler`() {
        val f = Fixture()
        assertIs<StoreResult.Conflict>(f.store.writeSoul(f.project, SoulRead.Error("x", null), "Text"))
        assertNull(f.storage.text(f.soulPath))
    }

    @Test
    fun `B11 App-Anweisungen und Snapshot bleiben getrennte Teile`() = runTest {
        val f = Fixture()
        f.storage.put(f.soulPath, "Projekt-Regel")
        f.newChat(appInstructions = "Antworte auf Deutsch.").send("Hallo", MODEL)
        assertEquals("Antworte auf Deutsch.\n\n# Projektanweisungen\n\n${RequestBuilder.PRECEDENCE}\n\nProjekt-Regel", f.api.systemPrompts.single())
    }

    @Test
    fun `B12 unterbrochenes Ersetzen von SOUL md gilt nicht als fehlend`() = runTest {
        val f = Fixture()
        f.storage.put(f.soulPath, "Alt")
        // Zwischenstand nach Schritt 3 des sicheren Ersetzens: SOUL.md beiseitegelegt, neue Fassung als tmp.
        f.storage.writeLog.clear()
        f.storage.renameThenFail = null
        f.storage.put(f.soulPath + ".tmp-abc", "Neu")
        f.storage.put(f.soulPath + ".bak-abc", "Alt")
        f.storage.delete(io.github.jenziner.nisaba.core.storage.RelPath(f.soulPath.split("/")))
        val chat = f.newChat()
        chat.send("Frage", MODEL)
        assertEquals(SoulState.PRESENT, chat.snapshotForTest!!.state)
        assertEquals("Neu", chat.snapshotForTest!!.content)
    }
}
