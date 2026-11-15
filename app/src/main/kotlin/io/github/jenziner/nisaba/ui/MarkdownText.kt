package io.github.jenziner.nisaba.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.jenziner.nisaba.core.markdown.Align
import io.github.jenziner.nisaba.core.markdown.Block
import io.github.jenziner.nisaba.core.markdown.Inline
import io.github.jenziner.nisaba.core.markdown.Markdown

/**
 * Darstellung einer Modellantwort als Standard-Markdown (Überschriften, Absätze,
 * fett/kursiv/durchgestrichen, Inline-Code, Codeblöcke, Listen, Zitate, Linien,
 * Tabellen, Links). Kein HTML, keine Bilder. Gespeichert wird immer der Rohtext.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { Markdown.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { BlockView(it) }
    }
}

@Composable
private fun BlockView(b: Block) {
    val typo = MaterialTheme.typography
    when (b) {
        is Block.Heading -> Text(
            inline(b.inlines),
            style = when (b.level) {
                1 -> typo.titleLarge
                2 -> typo.titleMedium
                else -> typo.titleSmall
            },
            modifier = Modifier.padding(top = 4.dp),
        )
        is Block.Paragraph -> Text(inline(b.inlines), style = typo.bodyMedium)
        is Block.Code -> Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Text(
                b.text,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                style = typo.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = false,
            )
        }
        is Block.Quote -> Row(Modifier.width(IntrinsicSize.Max)) {
            Surface(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.width(3.dp).fillMaxHeight()) {}
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                b.blocks.forEach { BlockView(it) }
            }
        }
        is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.items.forEachIndexed { i, item ->
                Row {
                    Text(
                        if (b.ordered) "${b.start + i}." else "•",
                        style = typo.bodyMedium,
                        modifier = Modifier.widthIn(min = 20.dp).padding(end = 6.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { item.forEach { BlockView(it) } }
                }
            }
        }
        Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
        is Block.Table -> TableView(b)
    }
}

@Composable
private fun TableView(t: Block.Table) {
    val cols = t.header.size
    val style = MaterialTheme.typography.bodySmall
    Surface(shape = MaterialTheme.shapes.small, color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp)) {
            val all = listOf(t.header) + t.rows
            all.forEachIndexed { r, row ->
                Row {
                    for (c in 0 until cols) {
                        val cell = row.getOrNull(c).orEmpty()
                        Text(
                            inline(cell),
                            style = if (r == 0) style.copy(fontWeight = FontWeight.SemiBold) else style,
                            textAlign = when (t.align.getOrNull(c)) { Align.CENTER -> TextAlign.Center; Align.END -> TextAlign.End; else -> TextAlign.Start },
                            modifier = Modifier.width(140.dp).padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
                if (r == 0) HorizontalDivider(Modifier.width((140 * cols).dp))
            }
        }
    }
}

@Composable
private fun inline(list: List<Inline>): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return remember(list, codeBg) {
        buildAnnotatedString {
            fun add(items: List<Inline>) {
                for (i in items) when (i) {
                    is Inline.Text -> append(i.text)
                    is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { add(i.children) }
                    is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { add(i.children) }
                    is Inline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { add(i.children) }
                    is Inline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(i.text) }
                    is Inline.Link -> {
                        val safe = i.url.startsWith("https://") || i.url.startsWith("http://") || i.url.startsWith("mailto:")
                        if (safe) withLink(LinkAnnotation.Url(i.url, link)) { add(i.children) } else add(i.children)
                    }
                    Inline.Break -> append('\n')
                }
            }
            add(list)
        }
    }
}
