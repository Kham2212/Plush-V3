package com.example.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassRoutesCalculatorTest {

    private fun isRoutedThroughTunnel(ip: String, routes: List<CidrRoute>): Boolean {
        val target = BypassRoutesCalculator.ipv4ToLong(ip)
        return routes.any { route ->
            val (start, end) = BypassRoutesCalculator.cidrToRange("${route.address}/${route.prefixLength}")
            target in start..end
        }
    }

    @Test
    fun `excludes private and local ipv4 ranges while routing public ips`() {
        val routes = BypassRoutesCalculator.computeTunnelIpv4Routes()

        // Local & private ranges MUST bypass VPN tunnel
        assertFalse(isRoutedThroughTunnel("127.0.0.1", routes))
        assertFalse(isRoutedThroughTunnel("10.0.0.1", routes))
        assertFalse(isRoutedThroughTunnel("10.255.255.254", routes))
        assertFalse(isRoutedThroughTunnel("172.16.0.1", routes))
        assertFalse(isRoutedThroughTunnel("172.31.255.254", routes))
        assertFalse(isRoutedThroughTunnel("192.168.1.1", routes))
        assertFalse(isRoutedThroughTunnel("192.168.0.100", routes))
        assertFalse(isRoutedThroughTunnel("169.254.10.5", routes))
        assertFalse(isRoutedThroughTunnel("224.0.0.1", routes))
        assertFalse(isRoutedThroughTunnel("255.255.255.255", routes))

        // Public internet IPs & boundaries adjacent to private ranges MUST enter tunnel
        assertTrue(isRoutedThroughTunnel("1.1.1.1", routes))
        assertTrue(isRoutedThroughTunnel("8.8.8.8", routes))
        assertTrue(isRoutedThroughTunnel("192.0.0.9", routes))
        assertTrue(isRoutedThroughTunnel("192.0.0.10", routes))
        assertTrue(isRoutedThroughTunnel("9.255.255.255", routes))
        assertTrue(isRoutedThroughTunnel("11.0.0.1", routes))
        assertTrue(isRoutedThroughTunnel("172.15.255.255", routes))
        assertTrue(isRoutedThroughTunnel("172.32.0.1", routes))
        assertTrue(isRoutedThroughTunnel("192.167.255.255", routes))
        assertTrue(isRoutedThroughTunnel("192.169.0.1", routes))
    }
}
