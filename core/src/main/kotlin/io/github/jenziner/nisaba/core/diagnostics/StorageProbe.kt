package io.github.jenziner.nisaba.core.diagnostics

import io.github.jenziner.nisaba.core.Env
import io.github.jenziner.nisaba.core.storage.ReadOutcome
import io.github.jenziner.nisaba.core.storage.RelPath
import io.github.jenziner.nisaba.core.storage.SResult
import io.github.jenziner.nisaba.core.storage.SafeReplace
import io.github.jenziner.nisaba.core.storage.StorageBackend
import io.github.jenziner.nisaba.core.storage.StorageError
import io.github.jenziner.nisaba.core.storage.WriteOutcome
import io.github.jenziner.nisaba.core.storage.sha256Hex
import io.github.jenziner.nisaba.core.store.ProjectStore
import io.github.jenziner.nisaba.core.store.StoreStatus
import java.time.format.DateTimeFormatter

/**
 * Speicherprüfung für einen gewählten Ordner.
 *
 * Arbeitet ausschliesslich in einem eigenen, neu angelegten Prüfordner
 * `nisaba-probe-<Zeitstempel>`. Existiert dieser Name bereits, bricht die
 * Prüfung ab, ohne etwas zu verändern. Vorhandene Dateien im gewählten
 * Ordner werden nur gelesen (Ordnerliste, Status von nisaba-store.json),
 * nie verändert oder gelöscht. Am Ende wird nur der eigene Prüfordner
 * gelöscht.
 */
class StorageProbe(private val storage: StorageBackend, private val env: Env) {

    enum class Verdict { OK, ERROR, NOTE }

    data class Step(val name: String, val verdict: Verdict, val detail: String, val millis: Long)

    data class Report(val steps: List<Step>, val probeDir: String?, val cleanedUp: Boolean) {
        val passed: Boolean get() = steps.none { it.verdict == Verdict.ERROR }
        fun asText(): String = buildString {
            appendLine("Tiiny Chat storage check")
            appendLine("Probe folder: ${probeDir ?: "-"} (removed: ${if (cleanedUp) "yes" else "no"})")
            for (s in steps) appendLine("[${s.verdict}] ${s.name}: ${s.detail} (${s.millis} ms)")
            appendLine("Overall: ${if (passed) "passed" else "NOT passed"}")
        }
    }

    private val steps = mutableListOf<Step>()

    private inline fun step(name: String, block: () -> Pair<Verdict, String>): Verdict {
        val t0 = System.nanoTime()
        val (v, d) = try {
            block()
        } catch (e: Exception) {
            Verdict.ERROR to "Exception ${e.javaClass.simpleName}: ${e.message}"
        }
        steps += Step(name, v, d, (System.nanoTime() - t0) / 1_000_000)
        return v
    }

    private fun <T> SResult<T>.describe(): String = when (this) {
        is SResult.Ok -> "ok"
        is SResult.Err -> describe(error)
    }

    private fun describe(e: StorageError): String = when (e) {
        StorageError.Unavailable -> "not reachable"
        StorageError.PermissionDenied -> "permission denied"
        StorageError.NotFound -> "not found"
        StorageError.AlreadyExists -> "already exists"
        is StorageError.NameChanged -> "name changed: '${e.requested}' -> '${e.actual}'"
        is StorageError.Io -> "I/O error: ${e.message}"
    }

    fun run(): Report {
        steps.clear()
        val ok = Verdict.OK
        val err = Verdict.ERROR

        if (step("Root folder reachable") {
                val r = storage.checkRoot(); (if (r is SResult.Ok) ok else err) to r.describe()
            } == err
        ) return Report(steps.toList(), null, cleanedUp = false)

        step("Content of the selected folder (read only)") {
            when (val r = storage.list(RelPath.ROOT)) {
                is SResult.Ok -> Verdict.NOTE to "${r.value.size} entries"
                is SResult.Err -> err to describe(r.error)
            }
        }
        step("Status of nisaba-store.json (read only)") {
            Verdict.NOTE to when (val s = ProjectStore(storage, env).openStore()) {
                is StoreStatus.Ready -> "present, storeId ${s.storeId}"
                is StoreStatus.NotInitialized -> if (s.isEmpty) "absent, folder empty" else "absent, folder not empty"
                is StoreStatus.Invalid -> "invalid: ${s.detail}"
                is StoreStatus.Failure -> "read error: ${describe(s.error)}"
            }
        }

        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(env.now().atZone(env.zone))
        val dir = RelPath.of("nisaba-probe-$stamp")
        if (step("Create own probe folder") {
                val r = storage.createDirectory(dir); (if (r is SResult.Ok) ok else err) to "$dir: ${r.describe()}"
            } == err
        ) return Report(steps.toList(), null, cleanedUp = false)

        val safe = SafeReplace(storage) { env.newId().replace("-", "").take(8) }
        val a = dir.child("probe.json")
        val v1 = """{"probe":1,"text":"Grüezi – äöü €"}""".toByteArray()
        val v2 = """{"probe":2}""".toByteArray()

        step("Write new file and read back") {
            val w = safe.createNew(a, v1)
            val r = safe.read(a)
            if (w == WriteOutcome.Written && r is ReadOutcome.Found && r.bytes.contentEquals(v1)) ok to "${v1.size} bytes identical"
            else err to "write: $w, read: $r"
        }
        step("Existing file is not overwritten") {
            val r = storage.createFile(a, v2)
            val back = storage.read(a)
            val unchanged = back is SResult.Ok && back.value.contentEquals(v1)
            if (r is SResult.Err && r.error == StorageError.AlreadyExists && unchanged) ok to "rejected, content unchanged"
            else err to "createFile: ${r.describe()}, content unchanged: $unchanged"
        }
        step("Safe replace") {
            val w = safe.replace(a, v2, sha256Hex(v1))
            val back = safe.read(a)
            val names = (storage.list(dir) as? SResult.Ok)?.value?.map { it.name }
            if (w == WriteOutcome.Written && back is ReadOutcome.Found && back.bytes.contentEquals(v2) && names == listOf("probe.json")) {
                ok to "replaced, no helper files left"
            } else err to "result: $w, folder: $names"
        }
        step("Conflict on stale state") {
            val w = safe.replace(a, v1, sha256Hex(v1))
            val back = storage.read(a)
            if (w is WriteOutcome.Conflict && back is SResult.Ok && back.value.contentEquals(v2)) ok to "detected, nothing written"
            else err to "result: $w"
        }
        step("Rename onto an existing name is rejected") {
            val b = dir.child("andere.json")
            storage.createFile(b, v1)
            val r = storage.rename(a, "andere.json")
            val bothThere = storage.stat(a).let { it is SResult.Ok && it.value != null } &&
                storage.read(b).let { it is SResult.Ok && it.value.contentEquals(v1) }
            if (r is SResult.Err && r.error == StorageError.AlreadyExists && bothThere) ok to "rejected, both files unchanged"
            else err to "rename: ${r.describe()}, both unchanged: $bothThere"
        }
        step("Umlauts in file name") {
            val u = dir.child("prüfung-äöü.json")
            val r = storage.createFile(u, v1)
            val listed = (storage.list(dir) as? SResult.Ok)?.value?.any { it.name == u.name } == true
            if (r is SResult.Ok && listed) ok to "name unchanged" else err to "${r.describe()}, listed: $listed"
        }
        step("Larger file (1 MiB)") {
            val big = ByteArray(1 shl 20) { (it * 31).toByte() }
            val p = dir.child("gross.bin")
            val w = safe.createNew(p, big)
            val back = storage.read(p)
            if (w == WriteOutcome.Written && back is SResult.Ok && back.value.contentEquals(big)) ok to "identical" else err to "$w"
        }

        var cleaned = false
        step("Remove own probe folder") {
            val r = storage.delete(dir)
            val gone = storage.stat(dir).let { it is SResult.Ok && it.value == null }
            cleaned = gone
            if (r is SResult.Ok && gone) ok to "removed" else err to "${r.describe()}, removed: $gone"
        }
        return Report(steps.toList(), dir.toString(), cleaned)
    }
}
