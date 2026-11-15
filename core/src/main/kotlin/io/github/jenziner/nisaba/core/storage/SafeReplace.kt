package io.github.jenziner.nisaba.core.storage

import java.security.MessageDigest

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

sealed interface WriteOutcome {
    data object Written : WriteOutcome

    /** Die Datei auf dem Speicher entspricht nicht dem zuletzt bekannten Stand. */
    data class Conflict(val reason: String) : WriteOutcome

    /** Schreiben fehlgeschlagen; der bisherige Stand ist unverändert. */
    data class Failed(val error: StorageError) : WriteOutcome

    /**
     * Schreiben unterbrochen nach dem Beiseitelegen der alten Datei.
     * Der Zustand muss beim nächsten Lesen über [SafeReplace.read] erkannt werden.
     * Die Inhalte liegen dann in `.bak-*` und `.tmp-*` desselben Vorgangs.
     */
    data class Interrupted(val error: StorageError) : WriteOutcome
}

sealed interface ReadOutcome {
    data class Found(val bytes: ByteArray, val recovered: Boolean = false) : ReadOutcome
    data object Missing : ReadOutcome
    data class Failed(val error: StorageError) : ReadOutcome

    /** Unklarer Zustand von Zwischendateien; nichts wurde verändert. */
    data class Inconsistent(val detail: String) : ReadOutcome
}

/**
 * Schreibprotokoll zum Ersetzen einer Datei ohne Überschreiben an Ort und Stelle.
 *
 * Ablauf für `name`:
 * 1. Aktuelle Datei lesen und mit dem erwarteten Hash vergleichen (sonst Konflikt).
 * 2. Neuen Inhalt in `name.tmp-<op>` anlegen und zurücklesen (byte-genauer Vergleich).
 * 3. `name` in `name.bak-<op>` umbenennen.
 * 4. `name.tmp-<op>` in `name` umbenennen.
 * 5. `name.bak-<op>` löschen.
 *
 * Ob die Umbenennungen auf einem bestimmten Speicheranbieter atomar sind,
 * ist nicht nachgewiesen. Das Protokoll stellt nur sicher, dass zu jedem
 * Zeitpunkt eine vollständige, zurückgelesene Fassung existiert
 * (entweder `name`, `name.bak-<op>` oder `name.tmp-<op>`).
 */
class SafeReplace(
    private val storage: StorageBackend,
    private val newOpId: () -> String,
) {
    /** Legt eine Datei erstmals an. Bestehende Dateien werden nie überschrieben. */
    fun createNew(path: RelPath, bytes: ByteArray): WriteOutcome {
        when (val r = storage.createFile(path, bytes)) {
            is SResult.Err -> return if (r.error == StorageError.AlreadyExists) {
                WriteOutcome.Conflict("${path.name} already exists")
            } else {
                WriteOutcome.Failed(r.error)
            }
            is SResult.Ok -> Unit
        }
        return when (val back = storage.read(path)) {
            is SResult.Err -> WriteOutcome.Failed(back.error)
            is SResult.Ok -> if (back.value.contentEquals(bytes)) {
                WriteOutcome.Written
            } else {
                WriteOutcome.Failed(StorageError.Io("Read-back content of ${path.name} differs"))
            }
        }
    }

    fun replace(path: RelPath, bytes: ByteArray, expectedSha256: String): WriteOutcome {
        val current = when (val r = read(path)) {
            is ReadOutcome.Found -> r.bytes
            ReadOutcome.Missing -> return WriteOutcome.Conflict("${path.name} fehlt")
            is ReadOutcome.Failed -> return WriteOutcome.Failed(r.error)
            is ReadOutcome.Inconsistent -> return WriteOutcome.Conflict(r.detail)
        }
        if (sha256Hex(current) != expectedSha256) {
            return WriteOutcome.Conflict("${path.name} was changed in the meantime")
        }
        val op = newOpId()
        val tmp = path.sibling("${path.name}.tmp-$op")
        val bak = path.sibling("${path.name}.bak-$op")

        when (val r = storage.createFile(tmp, bytes)) {
            is SResult.Err -> return WriteOutcome.Failed(r.error)
            is SResult.Ok -> Unit
        }
        val back = storage.read(tmp)
        if (back !is SResult.Ok || !back.value.contentEquals(bytes)) {
            storage.delete(tmp)
            return WriteOutcome.Failed(
                (back as? SResult.Err)?.error ?: StorageError.Io("Read-back content differs"),
            )
        }
        when (val r = storage.rename(path, bak.name)) {
            is SResult.Err -> {
                storage.delete(tmp)
                return WriteOutcome.Failed(r.error)
            }
            is SResult.Ok -> Unit
        }
        when (val r = storage.rename(tmp, path.name)) {
            is SResult.Err -> {
                // Alte Fassung zurückholen, falls möglich.
                return if (storage.rename(bak, path.name) is SResult.Ok) {
                    storage.delete(tmp)
                    WriteOutcome.Failed(r.error)
                } else {
                    WriteOutcome.Interrupted(r.error)
                }
            }
            is SResult.Ok -> Unit
        }
        // Ein liegengebliebenes .bak ist harmlos; read() ignoriert es.
        storage.delete(bak)
        return WriteOutcome.Written
    }

    /**
     * Liest `path`. Fehlt die Datei, aber liegen `.bak-<op>` und `.tmp-<op>`
     * desselben Vorgangs vor, wurde ein Ersetzen nach Schritt 3 unterbrochen.
     * Die `.tmp`-Fassung wurde vor Schritt 3 vollständig zurückgelesen; sie wird
     * deshalb als neue Fassung übernommen (Umbenennen, kein Überschreiben).
     * Die `.bak`-Datei bleibt erhalten.
     */
    fun read(path: RelPath): ReadOutcome {
        when (val r = storage.read(path)) {
            is SResult.Ok -> return ReadOutcome.Found(r.value)
            is SResult.Err -> if (r.error != StorageError.NotFound) return ReadOutcome.Failed(r.error)
        }
        val entries = when (val l = storage.list(path.parent)) {
            is SResult.Err -> return ReadOutcome.Failed(l.error)
            is SResult.Ok -> l.value.map { it.name }.toSet()
        }
        val bakPrefix = "${path.name}.bak-"
        val tmpPrefix = "${path.name}.tmp-"
        val ops = entries.filter { it.startsWith(bakPrefix) }.map { it.removePrefix(bakPrefix) }
        if (ops.isEmpty()) return ReadOutcome.Missing
        val complete = ops.filter { "$tmpPrefix$it" in entries }
        if (ops.size != 1 || complete.size != 1) {
            return ReadOutcome.Inconsistent(
                "${path.name} fehlt; Zwischendateien: ${entries.filter { it.startsWith(path.name + ".") }.sorted()}",
            )
        }
        val tmp = path.sibling("$tmpPrefix${complete.single()}")
        return when (val r = storage.rename(tmp, path.name)) {
            is SResult.Err -> ReadOutcome.Failed(r.error)
            is SResult.Ok -> when (val again = storage.read(path)) {
                is SResult.Ok -> ReadOutcome.Found(again.value, recovered = true)
                is SResult.Err -> ReadOutcome.Failed(again.error)
            }
        }
    }
}
