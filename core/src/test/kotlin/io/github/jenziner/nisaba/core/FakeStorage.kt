package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.storage.Entry
import io.github.jenziner.nisaba.core.storage.RelPath
import io.github.jenziner.nisaba.core.storage.SResult
import io.github.jenziner.nisaba.core.storage.StorageBackend
import io.github.jenziner.nisaba.core.storage.StorageError
import java.time.Instant
import java.time.ZoneId

/**
 * Speicher im Arbeitsspeicher mit Fehlereinspielung, nur für Tests.
 * Zählt alle Schreiboperationen, damit Tests "nichts geschrieben" prüfen können.
 */
class FakeStorage : StorageBackend {
    enum class Op { CHECK, LIST, STAT, READ, CREATE_FILE, CREATE_DIR, RENAME, DELETE }

    private val files = sortedMapOf<String, ByteArray>()
    private val dirs = sortedSetOf("")

    var unavailable = false
    var permissionDenied = false

    /** Liefert für (Operation, Pfad) einen Fehler oder null. */
    var fault: (Op, RelPath) -> StorageError? = { _, _ -> null }

    /** Simuliert "Schreiben gelingt, Zurücklesen scheitert" für den nächsten passenden Pfad. */
    var corruptReadBackOnce: ((RelPath) -> Boolean)? = null

    /** Umbenennen wird ausgeführt, danach aber ein Fehler gemeldet (Bestätigung verloren). */
    var renameThenFail: ((RelPath, String) -> Boolean)? = null

    val writeLog = mutableListOf<String>()

    private fun key(p: RelPath) = p.segments.joinToString("/")

    private fun gate(op: Op, p: RelPath): StorageError? = when {
        unavailable -> StorageError.Unavailable
        permissionDenied -> StorageError.PermissionDenied
        else -> fault(op, p)
    }

    fun put(path: String, content: String) {
        val segs = path.split("/")
        for (i in 1 until segs.size) dirs += segs.take(i).joinToString("/")
        files[path] = content.toByteArray()
    }

    fun putBytes(path: String, content: ByteArray) {
        val segs = path.split("/")
        for (i in 1 until segs.size) dirs += segs.take(i).joinToString("/")
        files[path] = content
    }

    fun text(path: String): String? = files[path]?.toString(Charsets.UTF_8)
    fun exists(path: String) = path in files || path in dirs
    fun allFiles(): Map<String, String> = files.mapValues { it.value.toString(Charsets.UTF_8) }

    override fun checkRoot(): SResult<Unit> = gate(Op.CHECK, RelPath.ROOT)?.let { SResult.Err(it) } ?: SResult.Ok(Unit)

    override fun list(dir: RelPath): SResult<List<Entry>> {
        gate(Op.LIST, dir)?.let { return SResult.Err(it) }
        val k = key(dir)
        if (k !in dirs) return SResult.Err(StorageError.NotFound)
        val prefix = if (k.isEmpty()) "" else "$k/"
        val children = mutableListOf<Entry>()
        dirs.filter { it.startsWith(prefix) && it != k && !it.removePrefix(prefix).contains('/') && it.isNotEmpty() }
            .forEach { children += Entry(it.removePrefix(prefix), true) }
        files.keys.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .forEach { children += Entry(it.removePrefix(prefix), false, files[it]!!.size.toLong()) }
        return SResult.Ok(children)
    }

    override fun stat(path: RelPath): SResult<Entry?> {
        gate(Op.STAT, path)?.let { return SResult.Err(it) }
        val k = key(path)
        return SResult.Ok(
            when {
                k in files -> Entry(path.name, false, files[k]!!.size.toLong())
                k in dirs -> Entry(path.name, true)
                else -> null
            },
        )
    }

    override fun read(path: RelPath): SResult<ByteArray> {
        gate(Op.READ, path)?.let { return SResult.Err(it) }
        val bytes = files[key(path)] ?: return SResult.Err(StorageError.NotFound)
        corruptReadBackOnce?.let { pred ->
            if (pred(path)) {
                corruptReadBackOnce = null
                return SResult.Err(StorageError.Unavailable)
            }
        }
        return SResult.Ok(bytes.copyOf())
    }

    override fun createFile(path: RelPath, bytes: ByteArray): SResult<Unit> {
        gate(Op.CREATE_FILE, path)?.let { return SResult.Err(it) }
        val k = key(path)
        if (key(path.parent) !in dirs) return SResult.Err(StorageError.NotFound)
        if (k in files || k in dirs) return SResult.Err(StorageError.AlreadyExists)
        files[k] = bytes.copyOf()
        writeLog += "create $k"
        return SResult.Ok(Unit)
    }

    override fun createDirectory(path: RelPath): SResult<Unit> {
        gate(Op.CREATE_DIR, path)?.let { return SResult.Err(it) }
        val k = key(path)
        if (key(path.parent) !in dirs) return SResult.Err(StorageError.NotFound)
        if (k in files || k in dirs) return SResult.Err(StorageError.AlreadyExists)
        dirs += k
        writeLog += "mkdir $k"
        return SResult.Ok(Unit)
    }

    override fun rename(path: RelPath, newName: String): SResult<Unit> {
        gate(Op.RENAME, path)?.let { return SResult.Err(it) }
        val from = key(path)
        val to = key(path.sibling(newName))
        if (to in files || to in dirs) return SResult.Err(StorageError.AlreadyExists)
        val bytes = files.remove(from) ?: return SResult.Err(StorageError.NotFound)
        files[to] = bytes
        writeLog += "rename $from -> $newName"
        renameThenFail?.let { pred ->
            if (pred(path, newName)) {
                renameThenFail = null
                return SResult.Err(StorageError.Unavailable)
            }
        }
        return SResult.Ok(Unit)
    }

    override fun delete(path: RelPath): SResult<Unit> {
        gate(Op.DELETE, path)?.let { return SResult.Err(it) }
        val k = key(path)
        if (k in files) {
            files.remove(k)
        } else if (k in dirs) {
            files.keys.filter { it.startsWith("$k/") }.toList().forEach { files.remove(it) }
            dirs.filter { it == k || it.startsWith("$k/") }.toList().forEach { dirs.remove(it) }
        } else {
            return SResult.Err(StorageError.NotFound)
        }
        writeLog += "delete $k"
        return SResult.Ok(Unit)
    }
}

class TestEnv(start: Instant = Instant.parse("2026-11-13T13:05:00Z")) : Env {
    private var t = start
    private var n = 0
    override fun now(): Instant = t.also { t = t.plusSeconds(1) }
    override fun newId(): String = "%04x0000-0000-4000-8000-%012x".format(++n, n)
    override val zone: ZoneId = ZoneId.of("Europe/Zurich")
}
