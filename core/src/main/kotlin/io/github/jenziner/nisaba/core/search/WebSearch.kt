package io.github.jenziner.nisaba.core.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ein Suchtreffer, wie er dem Modell übergeben wird. */
data class WebResult(val title: String, val url: String, val snippet: String)

/** Ergebnis einer Websuche für genau eine Modellanfrage. Wird nicht gespeichert. */
data class WebContext(val query: String, val takenAt: String, val results: List<WebResult>)

sealed interface SearchOutcome {
    data class Ok(val results: List<WebResult>) : SearchOutcome
    data class Failed(val detail: String) : SearchOutcome
}

/**
 * Websuche. Das Tiiny selbst bietet keine Websuche an; die Desktop-Software
 * von Tiiny sucht ebenfalls clientseitig, indem sie die Ergebnisseite einer
 * Suchmaschine lädt und liest. Diese App macht dasselbe direkt über HTTP.
 */
interface WebSearch {
    suspend fun search(query: String): SearchOutcome
}

object SearchQuery {
    /** Höchstlänge des Suchbegriffs. Der verwendete Begriff wird in der Oberfläche angezeigt. */
    const val MAX_CHARS = 400

    /** Suchbegriff aus der Nutzernachricht: Leerraum zusammengefasst, höchstens [MAX_CHARS] Zeichen. */
    fun from(text: String): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length <= MAX_CHARS) return clean
        val cut = clean.take(MAX_CHARS)
        val space = cut.lastIndexOf(' ')
        return if (space > MAX_CHARS / 2) cut.substring(0, space) else cut
    }
}

/** Unterstützte Suchmaschinen. Ergebnisseiten werden ohne Browser per HTTP geladen und gelesen. */
enum class SearchEngine(val id: String, val label: String) {
    DUCKDUCKGO("duckduckgo", "DuckDuckGo"),
    BING("bing", "Bing"),
    BRAVE("brave", "Brave Search"),
    YAHOO("yahoo", "Yahoo"),
    ;

    companion object {
        fun byId(id: String?): SearchEngine? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Websuche über die HTML-Ergebnisseite einer Suchmaschine.
 *
 * Kein offizielles API: Die Seitenstruktur kann sich jederzeit ändern, und
 * Suchmaschinen können automatisierte Abfragen mit einer Captcha-Seite
 * beantworten. Beides wird als Fehler gemeldet, nie als "keine Treffer".
 */
class HtmlSearch(
    val engine: SearchEngine,
    private val client: OkHttpClient,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val acceptLanguage: String = "en;q=0.8",
    private val maxResults: Int = 5,
    private val baseUrl: HttpUrl? = null,
) : WebSearch {

    fun request(query: String): Request {
        val b = Request.Builder()
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", acceptLanguage)
        return when (engine) {
            SearchEngine.DUCKDUCKGO -> b.url(base("https://html.duckduckgo.com/html/"))
                .post(FormBody.Builder().add("q", query).build())
                .build()
            SearchEngine.BING -> b.url(base("https://www.bing.com/search").newBuilder().addQueryParameter("q", query).build()).get().build()
            SearchEngine.BRAVE -> b.url(
                base("https://search.brave.com/search").newBuilder().addQueryParameter("q", query).addQueryParameter("source", "web").build(),
            ).get().build()
            SearchEngine.YAHOO -> b.url(base("https://search.yahoo.com/search").newBuilder().addQueryParameter("p", query).build()).get().build()
        }
    }

    private fun base(default: String): HttpUrl = baseUrl ?: default.toHttpUrlOrNull()!!

    override suspend fun search(query: String): SearchOutcome {
        val call = client.newCall(request(query))
        val name = engine.label
        return try {
            val r = call.await()
            // Lesen und Auswerten nie auf dem aufrufenden Thread (unter Android oft der
            // Hauptthread; dort beendet StrictMode die App bei Netzzugriff).
            withContext(Dispatchers.IO) { r.use { resp ->
                val body = resp.body.string()
                when {
                    resp.code == 429 -> SearchOutcome.Failed("$name refused the request (too many requests, HTTP 429). Try again later or choose another search engine.")
                    !resp.isSuccessful -> SearchOutcome.Failed(
                        if (looksBlocked(body)) "$name blocked the request (captcha, HTTP ${resp.code}). Choose another search engine."
                        else "$name answered HTTP ${resp.code}",
                    )
                    else -> evaluate(engine, body, maxResults)
                }
            } }
        } catch (e: SocketTimeoutException) {
            SearchOutcome.Failed("$name timeout")
        } catch (e: IOException) {
            SearchOutcome.Failed("$name not reachable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        /** Mobiler Chrome-Kennzeichner; ohne Browserkennung liefern einige Suchmaschinen keine Ergebnisseite. */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"

        fun evaluate(engine: SearchEngine, html: String, max: Int): SearchOutcome {
            val results = runCatching { parse(engine, html) }.getOrDefault(emptyList())
                .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
                .distinctBy { it.url }
                .take(max)
            return when {
                results.isNotEmpty() -> SearchOutcome.Ok(results)
                looksBlocked(html) -> SearchOutcome.Failed("${engine.label} blocked the request (captcha). Choose another search engine.")
                else -> SearchOutcome.Failed(
                    "${engine.label} returned no readable results (nothing found, or the page format has changed).",
                )
            }
        }

        fun looksBlocked(html: String): Boolean {
            val h = html.lowercase()
            return listOf("captcha", "anomaly-modal", "unusual traffic", "are you a robot", "not a robot", "verify you are human")
                .any { it in h }
        }

        fun parse(engine: SearchEngine, html: String): List<WebResult> {
            val doc = Jsoup.parse(html)
            return when (engine) {
                SearchEngine.DUCKDUCKGO -> doc.select("div.result").filterNot { it.hasClass("result--ad") }.mapNotNull { r ->
                    val a = r.selectFirst("a.result__a") ?: return@mapNotNull null
                    val url = ddgUrl(a.attr("href")) ?: return@mapNotNull null
                    if (url.contains("duckduckgo.com/y.js")) return@mapNotNull null
                    WebResult(clean(a.text()), url, clean(r.selectFirst(".result__snippet")?.text()))
                }
                SearchEngine.BING -> doc.select("li.b_algo").mapNotNull { r ->
                    val h2 = r.selectFirst("h2")
                    val a = h2?.selectFirst("a[href]") ?: r.selectFirst("a[href]") ?: return@mapNotNull null
                    val url = bingUrl(a.attr("href")) ?: return@mapNotNull null
                    val snippet = r.selectFirst(".b_caption p")?.text() ?: r.selectFirst("p")?.text() ?: r.selectFirst(".b_caption")?.text()
                    WebResult(clean(h2?.text() ?: a.text()), url, clean(snippet))
                }
                SearchEngine.BRAVE -> doc.select("[data-type=web]").mapNotNull { r ->
                    val a = r.select("a[href]").firstOrNull { it.attr("href").startsWith("http") } ?: return@mapNotNull null
                    val title = r.selectFirst(".search-snippet-title")?.text() ?: r.selectFirst(".title")?.text() ?: a.text()
                    val snippet = r.selectFirst(".snippet-description")?.text() ?: r.selectFirst(".content")?.text()
                    WebResult(clean(title), a.attr("href"), clean(snippet))
                }
                SearchEngine.YAHOO -> doc.select(".algo").mapNotNull { r ->
                    val a = r.selectFirst("h3 a[href]") ?: return@mapNotNull null
                    val url = yahooUrl(a.attr("href")) ?: return@mapNotNull null
                    val title = a.attr("aria-label").ifBlank { a.ownText().ifBlank { a.text() } }
                    WebResult(clean(title), url, clean(r.selectFirst(".compText")?.text()))
                }
            }
        }

        private fun clean(s: String?): String = s.orEmpty().replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

        /** DuckDuckGo verpackt Ziele teils als `//duckduckgo.com/l/?uddg=<Ziel>`. */
        fun ddgUrl(href: String): String? {
            val abs = if (href.startsWith("//")) "https:$href" else href
            val u = abs.toHttpUrlOrNull() ?: return null
            if (u.host.endsWith("duckduckgo.com") && u.encodedPath.startsWith("/l/")) return u.queryParameter("uddg")
            return abs
        }

        /** Bing verpackt Ziele teils als `/ck/a?…&u=a1<Base64url>`. */
        fun bingUrl(href: String): String? {
            val u = href.toHttpUrlOrNull() ?: return null
            if (u.host.endsWith("bing.com") && u.encodedPath.startsWith("/ck/")) {
                val enc = u.queryParameter("u")?.removePrefix("a1") ?: return null
                return runCatching {
                    String(java.util.Base64.getUrlDecoder().decode(enc.padEnd((enc.length + 3) / 4 * 4, '=')), Charsets.UTF_8)
                }.getOrNull()
            }
            return href
        }

        /** Yahoo verpackt Ziele als `…/RU=<url-kodiertes Ziel>/RK=…`. */
        fun yahooUrl(href: String): String? {
            val m = Regex("/RU=([^/]+)/R[KS]=").find(href) ?: return href.takeIf { !it.contains("r.search.yahoo.com") }
            return runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull()
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { if (cont.isActive) cont.resume(response) else response.close() }
    })
}
