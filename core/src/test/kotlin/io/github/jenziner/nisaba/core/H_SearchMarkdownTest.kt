package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.markdown.Align
import io.github.jenziner.nisaba.core.markdown.Block
import io.github.jenziner.nisaba.core.markdown.Inline
import io.github.jenziner.nisaba.core.markdown.Markdown
import io.github.jenziner.nisaba.core.search.HtmlSearch
import io.github.jenziner.nisaba.core.search.SearchEngine
import io.github.jenziner.nisaba.core.search.SearchOutcome
import kotlin.test.Test
import kotlinx.coroutines.asCoroutineDispatcher
import okio.buffer
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tests für 0.4: Direktsuche (gespeicherte echte Ergebnisseiten vom 15.11.2026) und Markdown. */
class H_SearchMarkdownTest {
    private fun page(n: String) = javaClass.getResource("/search/$n.html")!!.readText()

    private fun check(engine: SearchEngine, file: String) {
        val ok = assertIs<SearchOutcome.Ok>(HtmlSearch.evaluate(engine, page(file), 5))
        assertEquals(5, ok.results.size, "$engine")
        assertTrue(ok.results.all { it.url.startsWith("https://") && it.title.isNotBlank() }, "$engine ${ok.results}")
        assertTrue(ok.results.count { it.snippet.isNotBlank() } >= 3, "$engine snippets ${ok.results}")
        assertTrue(ok.results.any { it.url.contains("tiiny") }, "$engine ${ok.results}")
        assertTrue(ok.results.none { it.url.contains("duckduckgo.com") || it.url.contains("bing.com/ck") || it.url.contains("r.search.yahoo.com") })
    }

    @Test fun `H1 DuckDuckGo-Ergebnisseite`() = check(SearchEngine.DUCKDUCKGO, "ddg")
    @Test fun `H2 Bing-Ergebnisseite`() = check(SearchEngine.BING, "bing")
    @Test fun `H3 Brave-Ergebnisseite`() = check(SearchEngine.BRAVE, "brave")
    @Test fun `H4 Yahoo-Ergebnisseite`() = check(SearchEngine.YAHOO, "yahoo")

    @Test
    fun `H5 Weiterleitungen werden aufgeloest, leere und Captcha-Seiten sind Fehler`() {
        assertEquals("https://a.ch/x?y=1", HtmlSearch.ddgUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fa.ch%2Fx%3Fy%3D1&rut=abc"))
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("https://b.ch/p".toByteArray())
        assertEquals("https://b.ch/p", HtmlSearch.bingUrl("https://www.bing.com/ck/a?!&&p=x&u=a1$b64&ntb=1"))
        assertEquals("https://c.ch/", HtmlSearch.yahooUrl("https://r.search.yahoo.com/_ylt=x/RV=2/RE=1/RO=10/RU=https%3a%2f%2fc.ch%2f/RK=2/RS=z-"))
        assertTrue(assertIs<SearchOutcome.Failed>(HtmlSearch.evaluate(SearchEngine.BING, "<html></html>", 5)).detail.contains("no readable results"))
        assertTrue(assertIs<SearchOutcome.Failed>(HtmlSearch.evaluate(SearchEngine.BRAVE, "<p>Please solve the captcha</p>", 5)).detail.contains("captcha"))
    }

    @Test
    fun `H6 Markdown - Ueberschriften, Hervorhebungen, Code, Links`() {
        val b = Markdown.parse("# Titel\n\nText mit **fett**, *kursiv*, _auch_, ~~weg~~ und `x*y`.\nZweite Zeile [Link](https://a.ch) und https://b.ch.\n\n## Teil")
        assertEquals(Block.Heading(1, listOf(Inline.Text("Titel"))), b[0])
        val p = assertIs<Block.Paragraph>(b[1]).inlines
        assertTrue(Inline.Bold(listOf(Inline.Text("fett"))) in p)
        assertTrue(Inline.Italic(listOf(Inline.Text("kursiv"))) in p)
        assertTrue(Inline.Italic(listOf(Inline.Text("auch"))) in p)
        assertTrue(Inline.Strike(listOf(Inline.Text("weg"))) in p)
        assertTrue(Inline.Code("x*y") in p)
        assertTrue(Inline.Break in p)
        assertTrue(Inline.Link(listOf(Inline.Text("Link")), "https://a.ch") in p)
        assertTrue(Inline.Link(listOf(Inline.Text("https://b.ch")), "https://b.ch") in p)
        assertEquals(Block.Heading(2, listOf(Inline.Text("Teil"))), b[2])
    }

    @Test
    fun `H7 Markdown - Listen, verschachtelt und nummeriert`() {
        val b = Markdown.parse("- eins\n- zwei\n  - zwei.a\n  - zwei.b\n- drei\n\n3. c\n4. d")
        val l = assertIs<Block.ListBlock>(b[0])
        assertEquals(false, l.ordered)
        assertEquals(3, l.items.size)
        val nested = assertIs<Block.ListBlock>(l.items[1][1])
        assertEquals(2, nested.items.size)
        val o = assertIs<Block.ListBlock>(b[1])
        assertEquals(true, o.ordered)
        assertEquals(3, o.start)
        assertEquals(2, o.items.size)
    }

    @Test
    fun `H8 Markdown - Codeblock, Zitat, Trennlinie, Tabelle, Unbekanntes bleibt Text`() {
        val md = "```kotlin\nval a = 1 // **nicht fett**\n```\n\n> zitiert\n> weiter\n\n---\n\n| A | B |\n|:--|--:|\n| 1 | **2** |\n\nsnake_case_name und 2*3*4 bleiben."
        val b = Markdown.parse(md)
        assertEquals(Block.Code("kotlin", "val a = 1 // **nicht fett**"), b[0])
        assertIs<Block.Quote>(b[1])
        assertEquals(Block.Rule, b[2])
        val t = assertIs<Block.Table>(b[3])
        assertEquals(listOf(Align.START, Align.END), t.align)
        assertEquals(listOf(Inline.Bold(listOf(Inline.Text("2")))), t.rows.single()[1])
        val last = assertIs<Block.Paragraph>(b[4])
        assertEquals("snake_case_name und 2*3*4 bleiben.", Markdown.plain(last.inlines))
    }

    @Test
    fun `H9 Markdown verliert keinen Text bei unvollstaendiger Auszeichnung (Streaming)`() {
        val partial = "Das ist **halb fertig und `code"
        assertEquals(partial, Markdown.plain(assertIs<Block.Paragraph>(Markdown.parse(partial).single()).inlines))
        val fence = Markdown.parse("```\nnoch offen")
        assertEquals(Block.Code("", "noch offen"), fence.single())
    }

    /**
     * Regressionstest zum Absturz in 0.4.0 (NetworkOnMainThreadException): Die Antwort
     * der Suchmaschine darf nie auf dem aufrufenden Thread gelesen werden. Der Aufruf
     * erfolgt hier von einem eigenen Thread ("caller", entspricht dem Hauptthread);
     * jedes Lesen des Antwortkörpers wird mit seinem Thread protokolliert.
     */
    @Test
    fun `H10 Suchantwort wird nicht auf dem aufrufenden Thread gelesen`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(page("ddg")))
        server.start()
        val readers = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val client = okhttp3.OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val resp = chain.proceed(chain.request())
            val src = object : okio.ForwardingSource(resp.body.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    readers += Thread.currentThread().name
                    return super.read(sink, byteCount)
                }
            }
            resp.newBuilder().body((src as okio.Source).buffer().let { b ->
                object : okhttp3.ResponseBody() {
                    override fun contentType() = resp.body.contentType()
                    override fun contentLength() = resp.body.contentLength()
                    override fun source() = b
                }
            }).build()
        }.build()
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "caller") }
        try {
            val search = HtmlSearch(SearchEngine.DUCKDUCKGO, client, baseUrl = server.url("/html/"))
            val outcome = kotlinx.coroutines.runBlocking(caller.asCoroutineDispatcher()) { search.search("tiiny") }
            assertIs<SearchOutcome.Ok>(outcome)
            assertTrue(readers.isNotEmpty(), "Antwortkörper wurde gelesen")
            assertTrue(readers.none { it.startsWith("caller") }, "gelesen auf: $readers")
        } finally {
            caller.shutdownNow(); server.shutdown()
        }
    }
}
