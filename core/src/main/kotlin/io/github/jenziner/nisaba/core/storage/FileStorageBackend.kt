package io.github.jenziner.nisaba.core.storage

import java.io.File
import java.io.IOException

/**
 * [StorageBackend] on a plain directory in the app's own storage.
 *
 * Used on Android TV, where most devices ship without a folder picker (the Storage Access
 * Framework UI), so a project folder cannot be chosen. Same rules as the SAF backend: content is
 * only written into newly created files; replacing goes through SafeReplace in the core.
 */
class FileStorageBackend(private val root: File) : StorageBackend {

    private fun file(path: RelPath): File =
        if (path.segments.isEmpty()) root else File(root, path.segments.joinToString(File.separator))

    private inline fun <T> guard(block: () -> SResult<T>): SResult<T> = try {
        block()
    } catch (e: SecurityException) {
        SResult.Err(StorageError.PermissionDenied)
    } catch (e: IOException) {
        SResult.Err(StorageError.Io(e.javaClass.simpleName + ": " + (e.message ?: "")))
    }

    override fun checkRoot(): SResult<Unit> = guard {
        if (!root.isDirectory || !root.canRead()) SResult.Err(StorageError.Unavailable) else SResult.Ok(Unit)
    }

    override fun list(dir: RelPath): SResult<List<Entry>> = guard {
        val d = file(dir)
        if (!d.isDirectory) return@guard SResult.Err(StorageError.NotFound)
        val items = d.listFiles() ?: return@guard SResult.Err(StorageError.Unavailable)
        SResult.Ok(items.map { Entry(it.name, it.isDirectory, if (it.isDirectory) null else it.length()) })
    }

    override fun stat(path: RelPath): SResult<Entry?> = guard {
        val f = file(path)
        SResult.Ok(if (!f.exists()) null else Entry(f.name, f.isDirectory, if (f.isDirectory) null else f.length()))
    }

    override fun read(path: RelPath): SResult<ByteArray> = guard {
        val f = file(path)
        if (!f.exists()) return@guard SResult.Err(StorageError.NotFound)
        if (f.isDirectory) return@guard SResult.Err(StorageError.Io("Is a folder"))
        SResult.Ok(f.readBytes())
    }

    override fun createFile(path: RelPath, bytes: ByteArray): SResult<Unit> = guard {
        require(path.segments.isNotEmpty()) { "Root folder cannot be created as a file" }
        if (!file(path.parent).isDirectory) return@guard SResult.Err(StorageError.NotFound)
        val f = file(path)
        if (!f.createNewFile()) return@guard SResult.Err(StorageError.AlreadyExists)
        try {
            f.outputStream().use { it.write(bytes); it.flush() }
        } catch (e: Exception) {
            // Remove only the file we just created.
            runCatching { f.delete() }
            throw e
        }
        SResult.Ok(Unit)
    }

    override fun createDirectory(path: RelPath): SResult<Unit> = guard {
        require(path.segments.isNotEmpty()) { "Root folder already exists" }
        if (!file(path.parent).isDirectory) return@guard SResult.Err(StorageError.NotFound)
        val d = file(path)
        if (d.exists()) return@guard SResult.Err(StorageError.AlreadyExists)
        if (!d.mkdir()) return@guard SResult.Err(StorageError.Io("mkdir failed"))
        SResult.Ok(Unit)
    }

    override fun rename(path: RelPath, newName: String): SResult<Unit> = guard {
        val f = file(path)
        if (!f.exists()) return@guard SResult.Err(StorageError.NotFound)
        val target = file(path.sibling(newName))
        if (target.exists()) return@guard SResult.Err(StorageError.AlreadyExists)
        if (!f.renameTo(target)) return@guard SResult.Err(StorageError.Io("rename failed"))
        SResult.Ok(Unit)
    }

    override fun delete(path: RelPath): SResult<Unit> = guard {
        require(path.segments.isNotEmpty()) { "Root folder is never deleted" }
        val f = file(path)
        if (!f.exists()) return@guard SResult.Err(StorageError.NotFound)
        if (!f.deleteRecursively()) return@guard SResult.Err(StorageError.Io("delete failed"))
        SResult.Ok(Unit)
    }
}
