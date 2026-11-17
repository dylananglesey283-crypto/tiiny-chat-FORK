package io.github.jenziner.nisaba.core.net

/**
 * Pure helpers for finding the Tiiny on the local network and for the smart-plug power-on URL.
 * No Android dependency; the app layer does the actual sockets.
 */
object LanTools {
    /** True for a usable `http://` or `https://` address with a host part. */
    fun isHttpUrl(text: String): Boolean {
        val t = text.trim()
        val rest = when {
            t.startsWith("http://", ignoreCase = true) -> t.substring(7)
            t.startsWith("https://", ignoreCase = true) -> t.substring(8)
            else -> return false
        }
        return rest.isNotEmpty() && !rest.startsWith("/") && rest.none { it.isWhitespace() }
    }

    /**
     * Other hosts to probe: the /24 around [ip] (a larger network is cut down to the /24 that holds
     * this device, to keep a scan quick). Excludes network, broadcast and [ip] itself.
     */
    fun scanHosts(ip: ByteArray): List<String> {
        require(ip.size == 4) { "IPv4 only" }
        val own = ip[3].toInt() and 0xff
        val a = ip[0].toInt() and 0xff; val b = ip[1].toInt() and 0xff; val c = ip[2].toInt() and 0xff
        return (1..254).filter { it != own }.map { "$a.$b.$c.$it" }
    }

    /**
     * Does a `GET /v1/models` answer look like an OpenAI-compatible model list? Deliberately loose
     * (the Tiiny's exact JSON is not pinned here); a plain web page or router login does not match.
     */
    fun looksLikeModelList(body: String): Boolean =
        body.contains("\"data\"") && body.contains("\"id\"")

    /** Pulls the first model id out of a model-list body, for display. */
    fun firstModelId(body: String): String? =
        Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
}
