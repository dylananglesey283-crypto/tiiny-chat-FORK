package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.diagnostics.StorageProbe
import io.github.jenziner.nisaba.core.storage.StorageError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Die Speicherprüfung verändert nichts ausserhalb ihres eigenen Prüfordners. */
class ProbeTest {
    @Test
    fun `Pruefung besteht und laesst vorhandene Dateien unveraendert`() {
        val s = FakeStorage()
        s.put("eigenes/notiz.txt", "bleibt")
        s.put("nisaba-store.json", "kaputt")
        val before = s.allFiles()
        val report = StorageProbe(s, TestEnv()).run()
        assertTrue(report.passed, report.asText())
        assertTrue(report.cleanedUp)
        assertEquals(before, s.allFiles())
        assertFalse(s.exists(report.probeDir!!))
    }

    @Test
    fun `belegter Pruefordnername fuehrt zum Abbruch ohne Aenderung`() {
        val s = FakeStorage()
        val env = TestEnv()
        s.put("nisaba-probe-20261113-140500/fremd.txt", "fremd")
        val before = s.allFiles()
        val report = StorageProbe(s, env).run()
        assertFalse(report.passed)
        assertEquals(before, s.allFiles())
    }

    @Test
    fun `nicht erreichbarer Speicher wird gemeldet`() {
        val s = FakeStorage().apply { unavailable = true }
        val report = StorageProbe(s, TestEnv()).run()
        assertFalse(report.passed)
        assertEquals(1, report.steps.size)
        assertTrue(s.writeLog.isEmpty())
    }

    @Test
    fun `Namensaenderung durch den Anbieter wird erkannt`() {
        val s = FakeStorage()
        s.fault = { op, p -> if (op == FakeStorage.Op.CREATE_FILE && p.name.startsWith("prüfung")) StorageError.NameChanged(p.name, "x (1)") else null }
        val report = StorageProbe(s, TestEnv()).run()
        assertFalse(report.passed)
        assertTrue(report.asText().contains("name changed"))
    }
}
