package io.github.jenziner.nisaba.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import io.github.jenziner.nisaba.settings.NetworkMode
import java.net.Inet4Address
import java.net.InetAddress

/** Beschreibung eines vom System gemeldeten Netzes, für Auswahl und Diagnose. */
data class NetInfo(
    val network: Network,
    val transports: List<String>,
    val iface: String?,
    val addresses: List<String>,
    val isDefault: Boolean,
    /** Ziel-Host liegt im Adressbereich dieses Netzes. */
    val containsHost: Boolean,
    val hasInternet: Boolean,
) {
    val label: String
        get() = buildString {
            append(transports.joinToString("+").ifEmpty { "unknown" })
            iface?.let { append(" ($it)") }
            if (addresses.isNotEmpty()) append(" ").append(addresses.joinToString(", "))
            if (isDefault) append(" [default]")
        }
}

sealed interface PathSelection {
    /** Anfragen werden an genau dieses Netz gebunden. */
    data class Bound(val info: NetInfo) : PathSelection

    /** Das System wählt das Netz (Standardnetz). [default] dient nur der Anzeige. */
    data class System(val default: NetInfo?) : PathSelection

    /** Verlangter Weg nicht vorhanden. Es wird bewusst nicht ausgewichen. */
    data class Unavailable(val reason: String) : PathSelection

    val description: String
        get() = when (this) {
            is Bound -> "bound to ${info.label}"
            is System -> "system default" + (default?.let { ": ${it.label}" } ?: ": no default network")
            is Unavailable -> "unavailable: $reason"
        }
}

/**
 * Netzwahl für den Modellserver.
 *
 * USB-Modus: gewählt wird ein Netz mit Transport USB oder Ethernet oder ein
 * Netz (ausser WLAN/Mobilfunk), dessen Adressbereich den Ziel-Host enthält.
 * Ob Android die USB-Verbindung zum Tiiny überhaupt als eigenes Netz meldet,
 * ist auf dem Gerät noch nicht nachgewiesen; die Diagnose zeigt alle Netze.
 */
class NetworkPaths(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    fun list(host: String?): List<NetInfo> {
        val target = host?.let { h -> runCatching { InetAddress.getByName(h) }.getOrNull()?.takeIf { isLiteral(h) } }
        val def = cm.activeNetwork
        return cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            val lp: LinkProperties? = cm.getLinkProperties(n)
            NetInfo(
                network = n,
                transports = transports(caps),
                iface = lp?.interfaceName,
                addresses = lp?.linkAddresses?.map { it.toString() }.orEmpty(),
                isDefault = n == def,
                containsHost = target != null && lp?.linkAddresses?.any { la ->
                    sameSubnet(la.address, la.prefixLength, target)
                } == true,
                hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            )
        }
    }

    fun select(mode: NetworkMode, host: String?): PathSelection {
        val all = list(host)
        return when (mode) {
            NetworkMode.SYSTEM -> PathSelection.System(all.firstOrNull { it.isDefault })
            NetworkMode.USB -> {
                val notWireless = all.filter { "Wi-Fi" !in it.transports && "Cellular" !in it.transports }
                val pick = notWireless.firstOrNull { it.containsHost }
                    ?: notWireless.firstOrNull { "USB" in it.transports || "Ethernet" in it.transports }
                if (pick != null) {
                    PathSelection.Bound(pick)
                } else {
                    PathSelection.Unavailable(
                        "No USB or Ethernet network found" +
                            (if (all.isEmpty()) "" else " (reported: ${all.joinToString("; ") { it.label }})"),
                    )
                }
            }
        }
    }

    private fun transports(c: NetworkCapabilities): List<String> = buildList {
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Cellular")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_USB)) add("USB")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
    }

    private fun isLiteral(h: String) = h.all { it.isDigit() || it == '.' } || h.contains(':')

    private fun sameSubnet(a: InetAddress, prefix: Int, b: InetAddress): Boolean {
        if (a !is Inet4Address || b !is Inet4Address || prefix !in 1..32) return false
        val x = a.address.fold(0) { acc, v -> (acc shl 8) or (v.toInt() and 0xff) }
        val y = b.address.fold(0) { acc, v -> (acc shl 8) or (v.toInt() and 0xff) }
        val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
        return (x and mask) == (y and mask)
    }
}
