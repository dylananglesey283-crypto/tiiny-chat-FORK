package io.github.jenziner.nisaba.net

import android.content.Context
import android.net.ConnectivityManager
import io.github.jenziner.nisaba.core.net.LanTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.URL

/** A host that answered like a Tiiny (OpenAI-compatible `/v1/models` on port 80). */
data class FoundDevice(
    val host: String,
    val baseUrl: String,
    val model: String?,
    /** True when the host answered 401/403 JSON: it is probably the Tiiny, but needs the API key. */
    val needsKey: Boolean,
)

/**
 * Looks for the Tiiny on the local network: probes every other address of the device's own /24
 * with `GET http://<host>/v1/models` (short timeouts, a few dozen at once). Sends no API key and
 * nothing but that one request. Needs the local network permission on Android 17.
 */
class Discovery(private val context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** IPv4 address of the default network (Wi-Fi or Ethernet), or null when offline. */
    fun ownAddress(): ByteArray? {
        val n = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(n) ?: return null
        return lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()?.address
    }

    suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<FoundDevice> {
        val own = ownAddress() ?: return emptyList()
        val hosts = LanTools.scanHosts(own)
        val gate = Semaphore(48)
        var done = 0
        return withContext(Dispatchers.IO) {
            coroutineScope {
                hosts.map { h ->
                    async {
                        val r = gate.withPermit { probe(h) }
                        synchronized(this@Discovery) { done++; onProgress(done, hosts.size) }
                        r
                    }
                }.awaitAll().filterNotNull()
            }
        }
    }

    private fun probe(host: String): FoundDevice? = try {
        val c = URL("http://$host/v1/models").openConnection() as HttpURLConnection
        c.connectTimeout = 400
        c.readTimeout = 800
        c.instanceFollowRedirects = false
        try {
            val code = c.responseCode
            val type = c.contentType.orEmpty()
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readNBytes(16_384).toString(Charsets.UTF_8) }.orEmpty()
            when {
                code in 200..299 && LanTools.looksLikeModelList(body) ->
                    FoundDevice(host, "http://$host/v1", LanTools.firstModelId(body), needsKey = false)
                (code == 401 || code == 403) && type.contains("json", ignoreCase = true) ->
                    FoundDevice(host, "http://$host/v1", null, needsKey = true)
                else -> null
            }
        } finally { c.disconnect() }
    } catch (_: Exception) { null }
}
