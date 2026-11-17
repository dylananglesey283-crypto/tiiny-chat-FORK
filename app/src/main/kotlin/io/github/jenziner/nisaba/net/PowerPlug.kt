package io.github.jenziner.nisaba.net

import io.github.jenziner.nisaba.core.net.LanTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Switches on a smart plug by calling a URL the user saved (a plain GET), e.g. a Shelly or Tasmota
 * plug on the local network. The Tiiny has no Ethernet and cannot be woken by Wake-on-LAN, so it
 * is powered on by giving it power: it boots when its USB-C supply comes back.
 *
 * Only ever used to switch ON. Cutting power to switch the Tiiny off is not supported on purpose
 * (an unclean power loss can damage its storage); shut it down on the device itself.
 */
class PowerPlug {
    suspend fun turnOn(url: String): Result<String> = withContext(Dispatchers.IO) {
        if (!LanTools.isHttpUrl(url)) return@withContext Result.failure(IllegalArgumentException("Not a valid http:// address"))
        runCatching {
            val c = URL(url.trim()).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 5000
            try {
                val code = c.responseCode
                if (code !in 200..299) throw IOException("The plug answered HTTP $code")
                "Power-on request sent (HTTP $code)"
            } finally { c.disconnect() }
        }
    }
}
