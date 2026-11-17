package io.github.jenziner.nisaba.core.net

/**
 * Pure helpers for finding the Tiiny on the local network and waking it (Wake-on-LAN).
 * No Android dependency; the app layer does the actual sockets.
 */
object LanTools {
    /** Parses `AA:BB:CC:DD:EE:FF`, `AA-BB-…`, `AABB.CCDD.EEFF` or `AABBCCDDEEFF`. Null if invalid. */
    fun parseMac(text: String): ByteArray? {
        val hex = text.trim().replace(Regex("[:\\-.\\s]"), "")
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun formatMac(mac: ByteArray): String = mac.joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    /** The Wake-on-LAN magic packet: 6 × 0xFF followed by the MAC address 16 times (102 bytes). */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "MAC must have 6 bytes" }
        val out = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) out[i] = 0xFF.toByte()
        for (r in 0 until 16) mac.copyInto(out, 6 + r * 6)
        return out
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

    /** Broadcast address of the /24 around [ip], e.g. 192.168.1.255. */
    fun broadcast24(ip: ByteArray): String {
        require(ip.size == 4) { "IPv4 only" }
        return "${ip[0].toInt() and 0xff}.${ip[1].toInt() and 0xff}.${ip[2].toInt() and 0xff}.255"
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
