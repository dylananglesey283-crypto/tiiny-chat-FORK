package io.github.jenziner.nisaba.core.markdown

/**
 * Schlanker Markdown-Leser für Modellantworten. Unterstützt nur die
 * üblichen Konventionen: Überschriften, Absätze, Fett, Kursiv,
 * Durchgestrichen, Inline-Code, Codeblöcke, Aufzählungen (auch
 * verschachtelt und nummeriert), Zitate, Trennlinien, Links und einfache
 * Tabellen. Kein HTML, keine Bilder, keine Formeln. Was nicht erkannt
 * wird, bleibt als Text stehen; es geht nie Inhalt verloren.
 */
sealed interface Block {
    data class Heading(val level: Int, val inlines: List<Inline>) : Block
    data class Paragraph(val inlines: List<Inline>) : Block
    data class Code(val language: String, val text: String) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<Block>>) : Block
    data object Rule : Block
    data class Table(val header: List<List<Inline>>, val align: List<Align>, val rows: List<List<List<Inline>>>) : Block
}

enum class Align { START, CENTER, END }

sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Bold(val children: List<Inline>) : Inline
    data class Italic(val children: List<Inline>) : Inline
    data class Strike(val children: List<Inline>) : Inline
    data class Code(val text: String) : Inline
    data class Link(val children: List<Inline>, val url: String) : Inline

    /** Zeilenumbruch innerhalb eines Absatzes. */
    data object Break : Inline
}

object Markdown {
    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})\s*([^`\s]*).*$""")
    private val HEADING = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?[ \t]*#*[ \t]*$""")
    private val RULE = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
    private val QUOTE = Regex("""^ {0,3}> ?(.*)$""")
    private val ITEM = Regex("""^( {0,12})([-*+]|\d{1,9}[.)])([ \t]+(.*))?$""")
    private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")

    fun parse(text: String): List<Block> = blocks(text.replace("\r\n", "\n").replace('\t', ' ').split('\n'))

    private fun blocks(lines: List<String>): List<Block> {
        val out = mutableListOf<Block>()
        var i = 0
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) {
                out += Block.Paragraph(inlines(para.joinToString("\n") { it.trim() }))
                para.clear()
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { flush(); i++; continue }
            FENCE.matchEntire(line)?.let { m ->
                flush()
                val fence = m.groupValues[1]
                val indent = line.indexOfFirst { it != ' ' }
                val body = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    if (t.startsWith(fence.substring(0, 3)) && t.trimEnd().all { it == fence[0] } && t.trimEnd().length >= fence.length) { i++; break }
                    body += l.drop(minOf(indent, l.length - l.trimStart().length))
                    i++
                }
                out += Block.Code(m.groupValues[2], body.joinToString("\n"))
                return@let
            } ?: run {
                when {
                    HEADING.matchEntire(line) != null -> {
                        flush()
                        val m = HEADING.matchEntire(line)!!
                        out += Block.Heading(m.groupValues[1].length, inlines(m.groupValues[2].trim()))
                        i++
                    }
                    RULE.matches(line) && !(para.isNotEmpty() && line.trim().all { it == '-' }) -> {
                        flush(); out += Block.Rule; i++
                    }
                    para.isNotEmpty() && line.trim().let { t -> t.length >= 2 && (t.all { it == '=' } || t.all { it == '-' }) } -> {
                        // Setext-Überschrift
                        val level = if (line.trim()[0] == '=') 1 else 2
                        out += Block.Heading(level, inlines(para.joinToString(" ") { it.trim() }))
                        para.clear(); i++
                    }
                    QUOTE.matches(line) -> {
                        flush()
                        val body = mutableListOf<String>()
                        while (i < lines.size && lines[i].isNotBlank()) {
                            val q = QUOTE.matchEntire(lines[i])
                            body += q?.groupValues?.get(1) ?: lines[i]
                            i++
                        }
                        out += Block.Quote(blocks(body))
                    }
                    ITEM.matches(line) && (para.isEmpty() || startsListInParagraph(line)) -> {
                        flush()
                        i = list(lines, i, out)
                    }
                    line.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains('-') &&
                        cells(line).size == cells(lines[i + 1]).size -> {
                        flush()
                        val header = cells(line)
                        val align = cells(lines[i + 1]).map {
                            val c = it.trim()
                            when {
                                c.startsWith(":") && c.endsWith(":") -> Align.CENTER
                                c.endsWith(":") -> Align.END
                                else -> Align.START
                            }
                        }
                        i += 2
                        val rows = mutableListOf<List<List<Inline>>>()
                        while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                            val c = cells(lines[i])
                            rows += List(header.size) { k -> inlines(c.getOrElse(k) { "" }.trim()) }
                            i++
                        }
                        out += Block.Table(header.map { inlines(it.trim()) }, align, rows)
                    }
                    else -> { para += line; i++ }
                }
            }
        }
        flush()
        return out
    }

    /** Innerhalb eines Absatzes beginnt eine Liste nur mit `-`, `*`, `+` oder `1.`. */
    private fun startsListInParagraph(line: String): Boolean {
        val m = ITEM.matchEntire(line) ?: return false
        if (m.groupValues[4].isBlank()) return false
        val marker = m.groupValues[2]
        return !marker[0].isDigit() || marker.dropLast(1) == "1"
    }

    private fun list(lines: List<String>, from: Int, out: MutableList<Block>): Int {
        val first = ITEM.matchEntire(lines[from])!!
        val baseIndent = first.groupValues[1].length
        val ordered = first.groupValues[2][0].isDigit()
        val start = if (ordered) first.groupValues[2].dropLast(1).toIntOrNull() ?: 1 else 1
        val items = mutableListOf<List<Block>>()
        var i = from
        while (i < lines.size) {
            val m = ITEM.matchEntire(lines[i]) ?: break
            if (m.groupValues[1].length != baseIndent || m.groupValues[2][0].isDigit() != ordered) break
            val contentIndent = baseIndent + m.groupValues[2].length + 1
            val body = mutableListOf(m.groupValues[4])
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) {
                    // Leerzeile: Liste geht weiter, wenn danach eingerückter Inhalt oder ein weiterer Punkt folgt.
                    val next = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: break
                    val ind = lines[next].length - lines[next].trimStart().length
                    val nm = ITEM.matchEntire(lines[next])
                    if (ind >= contentIndent || (ind > baseIndent && nm != null)) { body += ""; i++; continue }
                    break
                }
                val ind = l.length - l.trimStart().length
                val nm = ITEM.matchEntire(l)
                if (nm != null && nm.groupValues[1].length <= baseIndent) break
                if (ind > baseIndent) {
                    body += l.drop(minOf(ind, contentIndent))
                    i++
                    continue
                }
                if (nm == null && !FENCE.matches(l) && !HEADING.matches(l) && !QUOTE.matches(l) && !RULE.matches(l)) {
                    body += l.trim() // Fortsetzungszeile ohne Einrückung
                    i++
                    continue
                }
                break
            }
            items += blocks(body)
            if (i < lines.size && lines[i].isBlank()) {
                val next = (i until lines.size).firstOrNull { lines[it].isNotBlank() } ?: break
                val nm = ITEM.matchEntire(lines[next])
                if (nm != null && nm.groupValues[1].length == baseIndent && nm.groupValues[2][0].isDigit() == ordered) i = next else break
            }
        }
        out += Block.ListBlock(ordered, start, items)
        return i
    }

    private fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var k = 0
        var inCode = false
        while (k < t.length) {
            val c = t[k]
            when {
                c == '\\' && k + 1 < t.length && t[k + 1] == '|' -> { cur.append('|'); k++ }
                c == '`' -> { inCode = !inCode; cur.append(c) }
                c == '|' && !inCode -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            k++
        }
        out += cur.toString()
        return out
    }

    // ------------------------------------------------------------ Inline

    private const val PUNCT = "\\`*_{}[]()#+-.!|~<>\"'"
    private val AUTOLINK = Regex("""https?://[^\s<>()\[\]]*[^\s<>()\[\].,;:!?'"*_~]""")

    fun inlines(text: String): List<Inline> = merge(parseInline(text))

    private fun parseInline(s: String): List<Inline> {
        val out = mutableListOf<Inline>()
        val buf = StringBuilder()
        fun text() { if (buf.isNotEmpty()) { out += Inline.Text(buf.toString()); buf.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in PUNCT -> { buf.append(s[i + 1]); i += 2 }
                c == '\n' -> { text(); out += Inline.Break; i++ }
                c == '`' -> {
                    val run = s.substring(i).takeWhile { it == '`' }.length
                    val close = s.indexOf("`".repeat(run), i + run)
                    if (close > 0) {
                        text()
                        var code = s.substring(i + run, close)
                        if (code.length >= 2 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) code = code.substring(1, code.length - 1)
                        out += Inline.Code(code)
                        i = close + run
                    } else { buf.append("`".repeat(run)); i += run }
                }
                (c == '*' || c == '_') && s.startsWith("$c$c", i) && !s.startsWith("$c$c$c", i) -> {
                    val close = findClose(s, i + 2, "$c$c", c == '_')
                    if (close > 0) { text(); out += Inline.Bold(parseInline(s.substring(i + 2, close))); i = close + 2 }
                    else { buf.append("$c$c"); i += 2 }
                }
                (c == '*' || c == '_') && s.startsWith("$c$c$c", i) -> {
                    val close = findClose(s, i + 3, "$c$c$c", c == '_')
                    if (close > 0) { text(); out += Inline.Bold(listOf(Inline.Italic(parseInline(s.substring(i + 3, close))))); i = close + 3 }
                    else { buf.append("$c$c$c"); i += 3 }
                }
                c == '*' || c == '_' -> {
                    // `_` nur an Wortgrenzen (snake_case), `*` nicht zwischen Ziffern (2*3*4).
                    val prev = if (i == 0) ' ' else s[i - 1]
                    val leftOk = if (c == '_') !prev.isLetterOrDigit() else !prev.isDigit()
                    val close = if (leftOk) findClose(s, i + 1, "$c", c == '_') else -1
                    val rightOk = close > 0 && (c == '_' || close + 1 >= s.length || !s[close + 1].isDigit())
                    if (close > 0 && !rightOk) { buf.append(c); i++; continue }
                    if (close > 0) { text(); out += Inline.Italic(parseInline(s.substring(i + 1, close))); i = close + 1 }
                    else { buf.append(c); i++ }
                }
                c == '~' && s.startsWith("~~", i) -> {
                    val close = findClose(s, i + 2, "~~", false)
                    if (close > 0) { text(); out += Inline.Strike(parseInline(s.substring(i + 2, close))); i = close + 2 }
                    else { buf.append("~~"); i += 2 }
                }
                c == '[' -> {
                    val link = link(s, i)
                    if (link != null) { text(); out += Inline.Link(parseInline(link.first), link.second); i = link.third }
                    else { buf.append(c); i++ }
                }
                c == '<' && s.startsWith("<http", i) -> {
                    val close = s.indexOf('>', i)
                    val url = if (close > 0) s.substring(i + 1, close) else ""
                    if (url.isNotEmpty() && url.none { it.isWhitespace() }) {
                        text(); out += Inline.Link(listOf(Inline.Text(url)), url); i = close + 1
                    } else { buf.append(c); i++ }
                }
                (c == 'h') && (s.startsWith("http://", i) || s.startsWith("https://", i)) && (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    val m = AUTOLINK.find(s, i)
                    if (m != null && m.range.first == i) {
                        text(); out += Inline.Link(listOf(Inline.Text(m.value)), m.value); i = m.range.last + 1
                    } else { buf.append(c); i++ }
                }
                else -> { buf.append(c); i++ }
            }
        }
        text()
        return out
    }

    /** Sucht das schliessende Zeichen; Inhalt darf nicht leer sein und nicht mit Leerraum beginnen oder enden. */
    private fun findClose(s: String, from: Int, delim: String, wordBound: Boolean): Int {
        if (from >= s.length || s[from].isWhitespace()) return -1
        var k = from
        while (true) {
            val idx = s.indexOf(delim, k)
            if (idx < 0) return -1
            val ok = idx > from && !s[idx - 1].isWhitespace() && s[idx - 1] != '\\' &&
                (delim.length > 1 || idx + 1 >= s.length || s[idx + 1] != delim[0]) &&
                (!wordBound || idx + delim.length >= s.length || !s[idx + delim.length].isLetterOrDigit())
            if (ok) return idx
            k = idx + delim.length
        }
    }

    /** `[text](url)` → (text, url, Ende). Titel in Anführungszeichen werden ignoriert. */
    private fun link(s: String, at: Int): Triple<String, String, Int>? {
        var depth = 0
        var k = at
        while (k < s.length) {
            when (s[k]) {
                '\\' -> k++
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) break }
                '\n' -> {}
            }
            k++
        }
        if (k >= s.length || k + 1 >= s.length || s[k + 1] != '(') return null
        val close = s.indexOf(')', k + 2).takeIf { it > 0 } ?: return null
        val inner = s.substring(k + 2, close).trim()
        val url = inner.substringBefore(' ').removePrefix("<").removeSuffix(">")
        if (url.isEmpty() || url.contains('\n')) return null
        return Triple(s.substring(at + 1, k), url, close + 1)
    }

    private fun merge(list: List<Inline>): List<Inline> {
        val out = mutableListOf<Inline>()
        for (x in list) {
            val last = out.lastOrNull()
            if (x is Inline.Text && last is Inline.Text) out[out.size - 1] = Inline.Text(last.text + x.text) else out += x
        }
        return out
    }

    /** Reiner Text ohne Auszeichnung (z. B. zum Kopieren einzelner Teile). */
    fun plain(inlines: List<Inline>): String = buildString {
        for (x in inlines) when (x) {
            is Inline.Text -> append(x.text)
            is Inline.Code -> append(x.text)
            is Inline.Bold -> append(plain(x.children))
            is Inline.Italic -> append(plain(x.children))
            is Inline.Strike -> append(plain(x.children))
            is Inline.Link -> append(plain(x.children))
            Inline.Break -> append('\n')
        }
    }
}
