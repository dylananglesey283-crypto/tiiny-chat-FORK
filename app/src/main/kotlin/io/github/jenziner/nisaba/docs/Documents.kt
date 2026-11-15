package io.github.jenziner.nisaba.docs

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.jenziner.nisaba.core.chat.DocumentContent
import io.github.jenziner.nisaba.core.chat.DocumentLoad
import io.github.jenziner.nisaba.core.chat.DocumentSource
import io.github.jenziner.nisaba.core.docs.DocumentText
import io.github.jenziner.nisaba.core.docs.Extracted
import io.github.jenziner.nisaba.core.docs.PdfText
import io.github.jenziner.nisaba.core.storage.sha256Hex
import io.github.jenziner.nisaba.core.store.DocumentRef
import io.github.jenziner.nisaba.core.store.ProjectRef
import io.github.jenziner.nisaba.core.store.ProjectStore
import io.github.jenziner.nisaba.core.store.StoreResult
import io.github.jenziner.nisaba.ui.Texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF-Text über `PdfRenderer.Page.getTextContents()` (ab Android 15 / API 35).
 * PdfRenderer braucht einen Dateideskriptor; dafür wird das PDF kurz in den
 * app-privaten Cache geschrieben und sofort wieder gelöscht.
 * Gescannte PDFs ohne Textebene liefern keinen Text (keine Texterkennung).
 */
class AndroidPdfText(private val context: Context) : PdfText {
    override fun extract(bytes: ByteArray): Extracted {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return Extracted.Unsupported("PDF text extraction needs Android 15 or newer")
        }
        val tmp = File.createTempFile("pdf-", ".pdf", context.cacheDir)
        return try {
            tmp.writeBytes(bytes)
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    val sb = StringBuilder()
                    for (i in 0 until r.pageCount) {
                        r.openPage(i).use { p ->
                            val text = p.textContents.joinToString("\n") { it.text }
                            if (text.isNotBlank()) {
                                if (sb.isNotEmpty()) sb.append("\n\n")
                                sb.append("[Page ${i + 1}]\n").append(text.trim())
                            }
                        }
                    }
                    if (sb.isEmpty()) Extracted.Failed("No text layer found (scanned PDF? OCR is not supported)")
                    else Extracted.Text(sb.toString())
                }
            }
        } catch (e: SecurityException) {
            Extracted.Failed("PDF is password protected")
        } catch (e: Exception) {
            Extracted.Failed("PDF could not be read: ${e.javaClass.simpleName}")
        } finally {
            tmp.delete()
        }
    }
}

/** Vorschau eines Dokuments für die Projekt-Einstellungen. */
data class DocumentInfo(val ref: DocumentRef, val tokens: Int?, val problem: String?)

/**
 * Projektdokumente: Hochladen, Textgewinnung mit Zwischenspeicher im
 * Arbeitsspeicher (nach Inhalt-Hash) und Laden für Anfragen. Gespeichert
 * wird nur das Original in `files/`; der gewonnene Text wird nie geschrieben.
 */
class Documents(private val context: Context, private val storeOf: () -> ProjectStore?) {
    private val text = DocumentText(AndroidPdfText(context))
    private val cache = object : LinkedHashMap<String, Extracted>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Extracted>?) = size > 32
    }

    private fun extractCached(name: String, bytes: ByteArray): Extracted {
        val key = name.substringAfterLast('.').lowercase() + ":" + sha256Hex(bytes)
        synchronized(cache) { cache[key]?.let { return it } }
        val r = text.extract(name, bytes)
        synchronized(cache) { cache[key] = r }
        return r
    }

    sealed interface Upload {
        data class Ok(val ref: DocumentRef, val tokens: Int) : Upload
        data class Failed(val reason: String) : Upload
    }

    /**
     * Liest die gewählte Datei, prüft Grösse und Textgewinnung und legt sie erst
     * dann in `files/` ab. Nicht lesbare Dateien werden nicht gespeichert.
     */
    suspend fun upload(project: ProjectRef, uri: Uri): Upload = withContext(Dispatchers.IO) {
        val store = storeOf() ?: return@withContext Upload.Failed("No project storage")
        val resolver = context.contentResolver
        var name = "document"
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if ((size ?: 0) > DocumentText.MAX_UPLOAD_BYTES) return@withContext Upload.Failed(tooLarge())
        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > DocumentText.MAX_UPLOAD_BYTES) return@withContext Upload.Failed(tooLarge())
                }
                out.toByteArray()
            } ?: return@withContext Upload.Failed("File could not be opened")
        } catch (e: Exception) {
            return@withContext Upload.Failed("File could not be read: ${e.javaClass.simpleName}")
        }
        val tokens = when (val x = extractCached(name, bytes)) {
            is Extracted.Text -> DocumentText.estimateTokens(x.text)
            is Extracted.Unsupported -> return@withContext Upload.Failed(x.reason)
            is Extracted.Failed -> return@withContext Upload.Failed(x.reason)
        }
        when (val r = store.addDocument(project, name, bytes)) {
            is StoreResult.Ok -> Upload.Ok(r.value, tokens)
            else -> Upload.Failed("Not saved: ${Texts.store(r)}")
        }
    }

    private fun tooLarge() = "File is larger than ${DocumentText.MAX_UPLOAD_BYTES / 1024 / 1024} MB"

    suspend fun list(project: ProjectRef): StoreResult<List<DocumentRef>> = withContext(Dispatchers.IO) {
        val store = storeOf() ?: return@withContext StoreResult.Conflict("No project storage")
        when (val r = store.listDocuments(project)) {
            is StoreResult.Ok -> StoreResult.Ok(r.value.items)
            else -> @Suppress("UNCHECKED_CAST") (r as StoreResult<List<DocumentRef>>)
        }
    }

    /** Liest jedes Dokument und schätzt die Tokens (für die Projekt-Einstellungen). */
    suspend fun inspect(docs: List<DocumentRef>): List<DocumentInfo> = withContext(Dispatchers.IO) {
        val store = storeOf() ?: return@withContext docs.map { DocumentInfo(it, null, "No project storage") }
        docs.map { d ->
            when (val b = store.readDocument(d)) {
                is StoreResult.Ok -> when (val x = extractCached(d.name, b.value)) {
                    is Extracted.Text -> DocumentInfo(d, DocumentText.estimateTokens(x.text), null)
                    is Extracted.Unsupported -> DocumentInfo(d, null, x.reason)
                    is Extracted.Failed -> DocumentInfo(d, null, x.reason)
                }
                else -> DocumentInfo(d, null, Texts.store(b))
            }
        }
    }

    suspend fun delete(doc: DocumentRef): StoreResult<Unit> = withContext(Dispatchers.IO) {
        storeOf()?.deleteDocument(doc) ?: StoreResult.Conflict("No project storage")
    }

    /** Quelle für eine Chat-Sitzung: liest die aktiven Dokumente bei jeder Anfrage neu aus `files/`. */
    fun sourceFor(project: ProjectRef): DocumentSource = DocumentSource { names ->
        withContext(Dispatchers.IO) {
            val store = storeOf() ?: return@withContext DocumentLoad.Failed(names.first(), "no project storage")
            val present = when (val l = store.listDocuments(project)) {
                is StoreResult.Ok -> l.value.items.associateBy { it.name }
                else -> return@withContext DocumentLoad.Failed(names.first(), Texts.store(l))
            }
            val out = mutableListOf<DocumentContent>()
            for (n in names) {
                val ref = present[n] ?: return@withContext DocumentLoad.Failed(n, "no longer exists in the project")
                val bytes = when (val b = store.readDocument(ref)) {
                    is StoreResult.Ok -> b.value
                    else -> return@withContext DocumentLoad.Failed(n, Texts.store(b))
                }
                when (val x = extractCached(n, bytes)) {
                    is Extracted.Text -> out += DocumentContent(n, x.text)
                    is Extracted.Unsupported -> return@withContext DocumentLoad.Failed(n, x.reason)
                    is Extracted.Failed -> return@withContext DocumentLoad.Failed(n, x.reason)
                }
            }
            DocumentLoad.Ok(out)
        }
    }
}
