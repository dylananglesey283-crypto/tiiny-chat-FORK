package io.github.jenziner.nisaba.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Uhr und Zufallsquelle austauschbar, damit Tests deterministisch sind. */
interface Env {
    fun now(): Instant
    fun newId(): String
    val zone: ZoneId

    companion object {
        val System: Env = object : Env {
            override fun now(): Instant = Instant.now()
            override fun newId(): String = UUID.randomUUID().toString()
            override val zone: ZoneId = ZoneId.systemDefault()
        }
    }
}

fun Env.timestamp(): String = DateTimeFormatter.ISO_INSTANT.format(now())

object Titles {
    const val MAX_LENGTH = 60

    /**
     * Titel lokal aus der ersten Nutzernachricht: Leerraum und Zeilenumbrüche
     * zu einzelnen Leerzeichen, auf [MAX_LENGTH] Zeichen an einer Wortgrenze
     * gekürzt, mit "…" markiert. Keine Modellanfrage.
     */
    fun fromFirstMessage(text: String, maxLength: Int = MAX_LENGTH): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return "Neuer Chat"
        if (normalized.codePointCount(0, normalized.length) <= maxLength) return normalized
        val cut = normalized.substring(0, normalized.offsetByCodePoints(0, maxLength - 1))
        val nextIsSpace = normalized[cut.length] == ' '
        val lastSpace = cut.lastIndexOf(' ')
        val base = when {
            nextIsSpace -> cut
            lastSpace >= maxLength / 2 -> cut.substring(0, lastSpace)
            else -> cut
        }
        return base.trimEnd(' ', ',', ';', ':', '-', '.') + "…"
    }

    /** Manuell eingegebener Titel; leer ist nicht erlaubt. */
    fun normalizeManual(text: String): String? =
        text.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }
}

object FolderNames {
    private val umlauts = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue", 'ß' to "ss", 'é' to "e", 'è' to "e", 'à' to "a")

    /** Ordnername einmalig aus dem Projektnamen abgeleitet; später nicht nachgeführt. */
    fun projectSlug(name: String): String {
        val sb = StringBuilder()
        for (ch in name.lowercase()) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> sb.append(ch)
                umlauts.containsKey(ch) -> sb.append(umlauts.getValue(ch))
                else -> if (sb.isNotEmpty() && sb.last() != '-') sb.append('-')
            }
        }
        val slug = sb.toString().trim('-').take(40).trim('-')
        return slug.ifEmpty { "projekt" }
    }

    private val chatStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    /** z. B. chat-20261113-1405-a1b2; der Ordnername ist nicht die Identität. */
    fun chatFolder(env: Env, suffix: String): String =
        "chat-" + chatStamp.format(env.now().atZone(env.zone)) + "-" + suffix
}
