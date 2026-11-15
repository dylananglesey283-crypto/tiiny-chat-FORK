package io.github.jenziner.nisaba.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.jenziner.nisaba.core.storage.Entry
import io.github.jenziner.nisaba.core.storage.RelPath
import io.github.jenziner.nisaba.core.storage.SResult
import io.github.jenziner.nisaba.core.storage.StorageBackend
import io.github.jenziner.nisaba.core.storage.StorageError
import java.io.FileNotFoundException

/**
 * [StorageBackend] über das Storage Access Framework.
 *
 * Grundlage ist ausschliesslich der vom Nutzer über ACTION_OPEN_DOCUMENT_TREE
 * freigegebene Ordner ([treeUri]). Pfade werden bei jedem Zugriff über die
 * Anzeigenamen aufgelöst; es gibt keinen Zwischenspeicher, damit ein
 * ausgetauschter oder getrennter Datenträger nicht mit veralteten
 * Dokument-IDs weiterbearbeitet wird.
 *
 * Inhalte werden nur in neu angelegte Dokumente geschrieben (Modus "w" auf
 * einem soeben erzeugten, leeren Dokument). Bestehende Dateien werden nie
 * direkt überschrieben; das Ersetzen übernimmt SafeReplace im Kern.
 *
 * Noch nicht auf dem Gerät verifiziert: Verhalten bei USB-Speicher und bei
 * anderen Dokumentanbietern als dem internen Speicher.
 */
class SafStorageBackend(
    private val resolver: ContentResolver,
    val treeUri: Uri,
) : StorageBackend {

    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private inline fun <T> guard(block: () -> SResult<T>): SResult<T> = try {
        block()
    } catch (e: SecurityException) {
        SResult.Err(if (hasPermission()) StorageError.Io("SecurityException: ${e.message}") else StorageError.PermissionDenied)
    } catch (e: FileNotFoundException) {
        SResult.Err(StorageError.Unavailable)
    } catch (e: IllegalArgumentException) {
        // Anbieter melden einen verschwundenen Ordner teils so.
        SResult.Err(StorageError.Unavailable)
    } catch (e: IllegalStateException) {
        SResult.Err(StorageError.Io("IllegalStateException: ${e.message}"))
    } catch (e: java.io.IOException) {
        SResult.Err(StorageError.Io(e.javaClass.simpleName + ": " + (e.message ?: "")))
    }

    fun hasPermission(): Boolean = resolver.persistedUriPermissions.any {
        it.uri == treeUri && it.isReadPermission && it.isWritePermission
    }

    private data class Child(val docId: String, val name: String, val isDir: Boolean, val size: Long? = null)

    private fun children(parentDocId: String): List<Child>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        val cursor = resolver.query(uri, cols, null, null, null) ?: return null
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Child(c.getString(0), c.getString(1) ?: "", c.getString(2) == Document.MIME_TYPE_DIR, if (c.isNull(3)) null else c.getLong(3)))
                }
            }
        }
    }

    /** Löst einen Pfad auf. `null` = nicht vorhanden. Wirft bei Zugriffsfehlern. */
    private fun resolve(path: RelPath): Child? {
        var current = Child(rootDocId, "", true)
        for (segment in path.segments) {
            if (!current.isDir) return null
            val list = children(current.docId) ?: throw FileNotFoundException("No result for ${current.name}")
            val matches = list.filter { it.name == segment }
            if (matches.size > 1) throw java.io.IOException("Mehrdeutiger Name: $segment")
            current = matches.singleOrNull() ?: return null
        }
        return current
    }

    private fun displayName(uri: Uri): String? {
        val cursor = resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null) ?: return null
        return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    override fun checkRoot(): SResult<Unit> = guard {
        if (!hasPermission()) return@guard SResult.Err(StorageError.PermissionDenied)
        children(rootDocId) ?: return@guard SResult.Err(StorageError.Unavailable)
        SResult.Ok(Unit)
    }

    override fun list(dir: RelPath): SResult<List<Entry>> = guard {
        val d = resolve(dir) ?: return@guard SResult.Err(StorageError.NotFound)
        if (!d.isDir) return@guard SResult.Err(StorageError.NotFound)
        val list = children(d.docId) ?: return@guard SResult.Err(StorageError.Unavailable)
        SResult.Ok(list.map { Entry(it.name, it.isDir, if (it.isDir) null else it.size) })
    }

    override fun stat(path: RelPath): SResult<Entry?> = guard {
        SResult.Ok(resolve(path)?.let { Entry(it.name, it.isDir, if (it.isDir) null else it.size) })
    }

    override fun read(path: RelPath): SResult<ByteArray> = guard {
        val f = resolve(path) ?: return@guard SResult.Err(StorageError.NotFound)
        if (f.isDir) return@guard SResult.Err(StorageError.Io("Ist ein Ordner"))
        val bytes = resolver.openInputStream(docUri(f.docId))?.use { it.readBytes() }
            ?: return@guard SResult.Err(StorageError.Unavailable)
        SResult.Ok(bytes)
    }

    private fun create(path: RelPath, mime: String): SResult<Uri> {
        val parent = resolve(path.parent) ?: return SResult.Err(StorageError.NotFound)
        if (!parent.isDir) return SResult.Err(StorageError.NotFound)
        val existing = children(parent.docId) ?: return SResult.Err(StorageError.Unavailable)
        if (existing.any { it.name == path.name }) return SResult.Err(StorageError.AlreadyExists)
        val uri = DocumentsContract.createDocument(resolver, docUri(parent.docId), mime, path.name)
            ?: return SResult.Err(StorageError.Io("createDocument lieferte null"))
        val actual = displayName(uri)
        if (actual != path.name) {
            // Anbieter hat den Namen eindeutig gemacht (z. B. "x (1)"): wieder entfernen.
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            return SResult.Err(StorageError.NameChanged(path.name, actual ?: "?"))
        }
        return SResult.Ok(uri)
    }

    override fun createFile(path: RelPath, bytes: ByteArray): SResult<Unit> = guard {
        when (val r = create(path, "application/octet-stream")) {
            is SResult.Err -> r
            is SResult.Ok -> {
                try {
                    val out = resolver.openOutputStream(r.value, "w")
                        ?: throw java.io.IOException("openOutputStream lieferte null")
                    out.use { it.write(bytes); it.flush() }
                    SResult.Ok(Unit)
                } catch (e: Exception) {
                    // Nur die soeben selbst angelegte Datei entfernen.
                    runCatching { DocumentsContract.deleteDocument(resolver, r.value) }
                    throw e
                }
            }
        }
    }

    override fun createDirectory(path: RelPath): SResult<Unit> = guard {
        when (val r = create(path, Document.MIME_TYPE_DIR)) {
            is SResult.Err -> r
            is SResult.Ok -> SResult.Ok(Unit)
        }
    }

    override fun rename(path: RelPath, newName: String): SResult<Unit> = guard {
        val f = resolve(path) ?: return@guard SResult.Err(StorageError.NotFound)
        val siblings = children(resolve(path.parent)!!.docId) ?: return@guard SResult.Err(StorageError.Unavailable)
        if (siblings.any { it.name == newName }) return@guard SResult.Err(StorageError.AlreadyExists)
        val newUri = DocumentsContract.renameDocument(resolver, docUri(f.docId), newName)
            ?: docUri(f.docId)
        val actual = displayName(newUri)
        if (actual != newName) return@guard SResult.Err(StorageError.NameChanged(newName, actual ?: "?"))
        SResult.Ok(Unit)
    }

    override fun delete(path: RelPath): SResult<Unit> = guard {
        require(path.segments.isNotEmpty()) { "Root folder is never deleted" }
        val f = resolve(path) ?: return@guard SResult.Err(StorageError.NotFound)
        if (!DocumentsContract.deleteDocument(resolver, docUri(f.docId))) {
            return@guard SResult.Err(StorageError.Io("deleteDocument fehlgeschlagen"))
        }
        SResult.Ok(Unit)
    }

    companion object {
        const val PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
