package com.neko7ina.syncclipboard.net

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只有局域网地址才需要 Android 17 的本地网络权限，公网地址不应被拦下。 */
class LocalNetworkAddressTest {
    @Test
    fun `private and link local addresses need the permission`() {
        assertTrue(LocalNetworkAccess.isLocalAddress(InetAddress.getByName("192.168.1.10")))
        assertTrue(LocalNetworkAccess.isLocalAddress(InetAddress.getByName("10.0.0.5")))
        assertTrue(LocalNetworkAccess.isLocalAddress(InetAddress.getByName("172.16.3.4")))
        assertTrue(LocalNetworkAccess.isLocalAddress(InetAddress.getByName("169.254.1.1")))
        assertTrue(LocalNetworkAccess.isLocalAddress(InetAddress.getByName("fd00::1")))
        assertTrue(LocalNetworkAccess.isLocalAddress(InetAddress.getByName("fe80::1")))
    }

    @Test
    fun `public addresses and loopback stay outside the permission`() {
        assertFalse(LocalNetworkAccess.isLocalAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(LocalNetworkAccess.isLocalAddress(InetAddress.getByName("203.0.113.10")))
        assertFalse(LocalNetworkAccess.isLocalAddress(InetAddress.getByName("172.32.0.1")))
        assertFalse(LocalNetworkAccess.isLocalAddress(InetAddress.getByName("2001:4860:4860::8888")))
        assertFalse(LocalNetworkAccess.isLocalAddress(InetAddress.getByName("127.0.0.1")))
    }
}
