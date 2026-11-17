package io.github.jenziner.nisaba.net

import android.content.Context
import android.net.ConnectivityManager
import io.github.jenziner.nisaba.core.net.LanTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN: sends the magic packet by UDP broadcast (ports 9 and 7) to 255.255.255.255 and to
 * the broadcast address of the current /24, over the default network.
 *
 * Only works if the Tiiny supports Wake-on-LAN, has it switched on, and sits on the same LAN
 * (normally a wired connection; many devices cannot be woken over Wi-Fi).
 */
class WakeOnLan(context: Context, private val discovery: Discovery) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    suspend fun send(macText: String): Result<String> = withContext(Dispatchers.IO) {
        val mac = LanTools.parseMac(macText) ?: return@withContext Result.failure(IllegalArgumentException("Not a valid MAC address"))
        val packet = LanTools.magicPacket(mac)
        val targets = listOfNotNull("255.255.255.255", discovery.broadcast()).distinct()
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                cm.activeNetwork?.let { runCatching { it.bindSocket(s) } }
                var sent = 0
                for (t in targets) for (port in intArrayOf(9, 7)) {
                    runCatching { s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(t), port)); sent++ }
                }
                if (sent == 0) throw java.io.IOException("No packet could be sent (offline?)")
            }
            "Magic packet sent to ${LanTools.formatMac(mac)}"
        }
    }
}
