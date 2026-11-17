package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.net.LanTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Test group J: network discovery and smart-plug URL helpers. */
class J_LanToolsTest {
    @Test
    fun `J1 http urls are accepted`() {
        for (u in listOf("http://192.168.1.20/rpc/Switch.Set?id=0&on=true", "https://plug.local/on", " http://10.0.0.5/relay/0?turn=on "))
            assertTrue(LanTools.isHttpUrl(u), u)
    }

    @Test
    fun `J2 other strings are rejected`() {
        for (u in listOf("", "192.168.1.20", "ftp://x/y", "http://", "http:///path", "http://a b/c"))
            assertFalse(LanTools.isHttpUrl(u), u)
    }

    @Test
    fun `J3 scan hosts skip self network and broadcast`() {
        val hosts = LanTools.scanHosts(byteArrayOf(192.toByte(), 168.toByte(), 1, 50))
        assertEquals(253, hosts.size)
        assertFalse("192.168.1.50" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
        assertTrue("192.168.1.1" in hosts && "192.168.1.254" in hosts)
    }

    @Test
    fun `J4 model list detection`() {
        val body = """{"object":"list","data":[{"id":"qwen3-30b","object":"model"}]}"""
        assertTrue(LanTools.looksLikeModelList(body))
        assertEquals("qwen3-30b", LanTools.firstModelId(body))
        assertFalse(LanTools.looksLikeModelList("<html><title>Router login</title></html>"))
    }
}
