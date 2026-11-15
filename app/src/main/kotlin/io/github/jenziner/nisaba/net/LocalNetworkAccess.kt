package io.github.jenziner.nisaba.net

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Android 17 (API 37) sperrt für Apps mit Ziel-SDK 37 den Zugriff auf das lokale
 * Netz, bis die Laufzeitberechtigung ACCESS_LOCAL_NETWORK erteilt ist. Ohne sie
 * laufen TCP-Verbindungen zu lokalen Adressen in eine Zeitüberschreitung.
 * Quelle: developer.android.com/privacy-and-security/local-network-permission
 */
class LocalNetworkAccess(private val context: Context) {
    val enforced: Boolean get() = Build.VERSION.SDK_INT >= 37

    fun granted(): Boolean = !enforced ||
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Nur Adressliterale werden eingeordnet; Namen gelten als „unbekannt“ (null). */
    fun isLocalTarget(host: String): Boolean? {
        val literal = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || host.contains(':')
        if (host.endsWith(".local")) return true
        if (!literal) return null
        val a = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        if (a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress) return true
        if (a is Inet4Address) {
            val b = a.address
            if ((b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64) return true // 100.64/10
        } else if ((a.address[0].toInt() and 0xfe) == 0xfc) return true // fc00::/7
        return false
    }

    companion object {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
