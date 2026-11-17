package io.github.jenziner.nisaba.core

import io.github.jenziner.nisaba.core.net.LanTools
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test group J: discovery and Wake-on-LAN helpers. */
class J_LanToolsTest {
    private val mac = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte())

    @Test
    fun `J1 mac formats are accepted`() {
        for (s in listOf("01:23:45:67:89:AB", "01-23-45-67-89-ab", "0123.4567.89ab", "0123456789AB", " 01:23:45:67:89:AB "))
            assertContentEquals(mac, LanTools.parseMac(s), s)
    }

    @Test
    fun `J2 bad macs are rejected`() {
        for (s in listOf("", "01:23:45:67:89", "01:23:45:67:89:AB:CD", "GG:23:45:67:89:AB", "hello"))
            assertNull(LanTools.parseMac(s), s)
    }

    @Test
    fun `J3 format round trip`() {
        assertEquals("01:23:45:67:89:AB", LanTools.formatMac(mac))
    }

    @Test
    fun `J4 magic packet layout`() {
        val p = LanTools.magicPacket(mac)
        assertEquals(102, p.size)
        assertTrue(p.take(6).all { it == 0xFF.toByte() })
        for (r in 0 until 16) assertContentEquals(mac, p.copyOfRange(6 + r * 6, 12 + r * 6))
    }

    @Test
    fun `J5 scan hosts skip self network and broadcast`() {
        val hosts = LanTools.scanHosts(byteArrayOf(192.toByte(), 168.toByte(), 1, 50))
        assertEquals(253, hosts.size)
        assertFalse("192.168.1.50" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
        assertTrue("192.168.1.1" in hosts && "192.168.1.254" in hosts)
        assertEquals("192.168.1.255", LanTools.broadcast24(byteArrayOf(192.toByte(), 168.toByte(), 1, 50)))
    }

    @Test
    fun `J6 model list detection`() {
        val body = """{"object":"list","data":[{"id":"qwen3-30b","object":"model"}]}"""
        assertTrue(LanTools.looksLikeModelList(body))
        assertEquals("qwen3-30b", LanTools.firstModelId(body))
        assertFalse(LanTools.looksLikeModelList("<html><title>Router login</title></html>"))
    }
}
