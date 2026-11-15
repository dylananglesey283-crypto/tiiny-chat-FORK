package io.github.jenziner.nisaba.core.docs

import io.github.jenziner.nisaba.core.format.decodeUtf8Strict
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Ergebnis der Textgewinnung aus einem Dokument. */
sealed interface Extracted {
    data class Text(val text: String) : Extracted

    /** Dateityp wird nicht unterstützt (z. B. Bild ohne Vision-Modell). */
    data class Unsupported(val reason: String) : Extracted

    /** Unterstützter Typ, aber kein Text gewinnbar (z. B. gescanntes PDF, beschädigte Datei). */
    data class Failed(val reason: String) : Extracted
}

/** Textgewinnung aus PDF; auf Android über `PdfRenderer.Page.getTextContents()` (API 35). */
fun interface PdfText {
    fun extract(bytes: ByteArray): Extracted
}

enum class DocumentKind { TEXT, PDF, DOCX, UNSUPPORTED }

/**
 * Textgewinnung für Projektdokumente. Unterstützt: Textformate (UTF-8, sonst
 * Windows-1252), Markdown, CSV, JSON, Quelltext, PDF (über [pdf]) und DOCX
 * (eigener, schlanker Leser für `word/document.xml`). Keine Texterkennung (OCR).
 */
class DocumentText(private val pdf: PdfText?) {

    fun extract(name: String, bytes: ByteArray): Extracted = when (kindOf(name)) {
        DocumentKind.TEXT -> text(bytes)
        DocumentKind.PDF -> pdf?.extract(bytes) ?: Extracted.Unsupported("PDF text extraction is not available")
        DocumentKind.DOCX -> docx(bytes)
        DocumentKind.UNSUPPORTED -> Extracted.Unsupported(
            "File type .${name.substringAfterLast('.', "").lowercase()} is not supported. Supported: text, Markdown, CSV, JSON, code, PDF, DOCX.",
        )
    }.let { r -> if (r is Extracted.Text && r.text.isBlank()) Extracted.Failed("No text found in the document") else r }

    companion object {
        /** Höchstgrösse einer Datei beim Hochladen, in Bytes. */
        const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024

        /** Schutz gegen übergrosse entpackte DOCX-Inhalte. */
        const val MAX_DOCX_XML_BYTES = 50L * 1024 * 1024

        val TEXT_EXTENSIONS = setOf(
            "txt", "text", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
            "log", "html", "htm", "css", "tex", "rst", "adoc", "srt", "vtt",
            "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "rb",
            "php", "swift", "m", "sh", "bash", "zsh", "ps1", "bat", "sql", "r", "lua", "pl", "scala", "dart", "gradle", "properties",
        )

        fun kindOf(name: String): DocumentKind {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext == "pdf" -> DocumentKind.PDF
                ext == "docx" -> DocumentKind.DOCX
                ext in TEXT_EXTENSIONS -> DocumentKind.TEXT
                else -> DocumentKind.UNSUPPORTED
            }
        }

        /** Konservative Schätzung wie bei der Kontextanzeige: ca. 3 Zeichen pro Token. */
        fun estimateTokens(text: String): Int = text.length / 3 + 1

        fun text(bytes: ByteArray): Extracted {
            if (bytes.any { it == 0.toByte() }) return Extracted.Failed("File contains binary data, not text")
            val t = decodeUtf8Strict(bytes) ?: String(bytes, charset("windows-1252"))
            return Extracted.Text(t.replace("\r\n", "\n"))
        }

        /**
         * Liest den Fliesstext aus `word/document.xml`: Absätze (`w:p`) werden zu Zeilen,
         * `w:t` liefert Text, `w:tab` und `w:br` werden zu Tabulator bzw. Zeilenumbruch.
         * Tabellenzellen werden durch Tabulatoren getrennt. Formatierungen gehen verloren.
         */
        fun docx(bytes: ByteArray): Extracted {
            val xml = try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    var found: String? = null
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.name == "word/document.xml") {
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_DOCX_XML_BYTES) return Extracted.Failed("DOCX content is too large")
                                out.write(buf, 0, n)
                            }
                            found = out.toString(Charsets.UTF_8)
                            break
                        }
                    }
                    found
                }
            } catch (e: java.io.IOException) {
                return Extracted.Failed("DOCX file could not be read: ${e.message ?: e.javaClass.simpleName}")
            } catch (e: IllegalArgumentException) {
                return Extracted.Failed("DOCX file could not be read")
            } ?: return Extracted.Failed("Not a DOCX file (word/document.xml missing)")
            return Extracted.Text(docxXmlToText(xml))
        }


        internal fun docxXmlToText(xml: String): String {
            val sb = StringBuilder()
            var cellOpen = false
            var i = 0
            val tag = Regex("""<(/?)([a-zA-Z0-9]+:[a-zA-Z0-9]+|[a-zA-Z0-9]+)([^>]*?)(/?)>""")
            var inText = false
            while (i < xml.length) {
                val m = tag.find(xml, i) ?: break
                if (inText && m.range.first > i) sb.append(unescape(xml.substring(i, m.range.first)))
                val closing = m.groupValues[1] == "/"
                val selfClosing = m.groupValues[4] == "/"
                when (m.groupValues[2]) {
                    "w:t" -> inText = !closing && !selfClosing
                    "w:tab" -> if (!closing) sb.append('\t')
                    "w:br", "w:cr" -> if (!closing) sb.append('\n')
                    "w:p" -> if (closing || selfClosing) { if (!cellOpen) sb.append('\n') else sb.append(' ') }
                    "w:tc" -> if (closing) { while (sb.endsWith(" ")) sb.setLength(sb.length - 1); sb.append('\t'); cellOpen = false } else if (!selfClosing) cellOpen = true
                    "w:tr" -> if (closing) { if (sb.endsWith("\t")) sb.setLength(sb.length - 1); sb.append('\n') }
                }
                i = m.range.last + 1
            }
            return sb.toString().lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        }

        private fun unescape(s: String): String = s
            .replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
            .replace(Regex("&#([0-9]+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}
