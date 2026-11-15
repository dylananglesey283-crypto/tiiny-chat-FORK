package io.github.jenziner.nisaba.search

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import io.github.jenziner.nisaba.core.search.SearchEngine
import io.github.jenziner.nisaba.settings.AppSettings

/** Gewählte Suchmaschine und wie sie zustande kam (für die Anzeige). */
data class EngineChoice(val engine: SearchEngine, val explanation: String)

/**
 * Wahl der Suchmaschine. Bei "Automatic" wird der Standardbrowser ermittelt und
 * dessen Werks-Suchmaschine verwendet. Die im Browser tatsächlich eingestellte
 * Suchmaschine kann eine andere App unter Android nicht auslesen.
 */
object EngineChoices {
    private data class Known(val name: String, val engine: SearchEngine?, val factory: String)

    /** Paketname → Browser und dessen Werks-Suchmaschine. `null` = nicht unterstützt (z. B. Google). */
    private val browsers = mapOf(
        "app.vanadium.browser" to Known("Vanadium", SearchEngine.DUCKDUCKGO, "DuckDuckGo"),
        "com.duckduckgo.mobile.android" to Known("DuckDuckGo Browser", SearchEngine.DUCKDUCKGO, "DuckDuckGo"),
        "com.brave.browser" to Known("Brave", SearchEngine.BRAVE, "Brave Search"),
        "com.microsoft.emmx" to Known("Microsoft Edge", SearchEngine.BING, "Bing"),
        "com.android.chrome" to Known("Chrome", null, "Google"),
        "org.mozilla.firefox" to Known("Firefox", null, "Google"),
        "org.mozilla.fennec_fdroid" to Known("Fennec", null, "Google"),
        "org.mozilla.focus" to Known("Firefox Focus", null, "Google"),
        "io.github.forkmaintainers.iceraven" to Known("Iceraven", null, "Google"),
        "us.spotco.fennec_dos" to Known("Mull", SearchEngine.DUCKDUCKGO, "DuckDuckGo"),
        "com.sec.android.app.sbrowser" to Known("Samsung Internet", null, "Google"),
        "com.opera.browser" to Known("Opera", null, "Google"),
    )

    fun resolve(context: Context, setting: String): EngineChoice {
        SearchEngine.byId(setting)?.let { return EngineChoice(it, "${it.label} (chosen in Settings)") }
        return auto(context)
    }

    fun auto(context: Context): EngineChoice {
        val pkg = defaultBrowser(context)
        val known = pkg?.let { browsers[it] }
        return when {
            pkg == null -> EngineChoice(SearchEngine.DUCKDUCKGO, "No default browser found → DuckDuckGo")
            known == null -> EngineChoice(SearchEngine.DUCKDUCKGO, "Default browser $pkg unknown → DuckDuckGo")
            known.engine == null -> EngineChoice(
                SearchEngine.DUCKDUCKGO,
                "${known.name}: factory default is ${known.factory}, which cannot be used without a browser → DuckDuckGo",
            )
            else -> EngineChoice(known.engine, "${known.name} → ${known.factory} (factory default of the browser)")
        }
    }

    /** Paketname des Standardbrowsers, sofern Android ihn meldet. */
    fun defaultBrowser(context: Context): String? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
        val info = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        }.getOrNull() ?: return null
        val pkg = info.activityInfo?.packageName ?: return null
        // "android" = Auswahldialog, d. h. kein Standardbrowser festgelegt.
        return pkg.takeIf { it != "android" && !it.endsWith(".resolver") }
    }

    val choices: List<Pair<String, String>> =
        listOf(AppSettings.SEARCH_AUTO to "Automatic") + SearchEngine.entries.map { it.id to it.label }
}
