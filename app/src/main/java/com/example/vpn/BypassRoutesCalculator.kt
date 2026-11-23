package com.example.vpn

data class CidrRoute(
    val address: String,
    val prefixLength: Int,
)

/**
 * Computes non-overlapping IPv4 CIDR routes covering 0.0.0.0/0 minus local/private/reserved ranges
 * so Android VpnService.Builder routes internet traffic through TUN while bypassing LAN traffic
 * across all Android API levels (24+).
 */
object BypassRoutesCalculator {

    private const val MAX_IPV4 = 0xFFFFFFFFL

    val DEFAULT_BYPASSED_CIDRS = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/3", // covers 224.0.0.0/4 multicast + 240.0.0.0/4 reserved + 255.255.255.255/32
    )

    fun computeTunnelIpv4Routes(excludedCidrs: List<String> = DEFAULT_BYPASSED_CIDRS): List<CidrRoute> {
        val excluded = excludedCidrs
            .map { cidrToRange(it) }
            .sortedBy { it.first }
            .fold(mutableListOf<Pair<Long, Long>>()) { acc, range ->
                if (acc.isEmpty() || range.first > acc.last().second + 1L) {
                    acc.add(range)
                } else {
                    val last = acc.removeAt(acc.lastIndex)
                    acc.add(last.first to maxOf(last.second, range.second))
                }
                acc
            }

        val allowedRanges = mutableListOf<Pair<Long, Long>>()
        var cursor = 0L
        for ((start, end) in excluded) {
            if (cursor < start) {
                allowedRanges.add(cursor to (start - 1L))
            }
            cursor = maxOf(cursor, end + 1L)
        }
        if (cursor <= MAX_IPV4) {
            allowedRanges.add(cursor to MAX_IPV4)
        }

        val routes = mutableListOf<CidrRoute>()
        for ((rangeStart, rangeEnd) in allowedRanges) {
            var current = rangeStart
            while (current <= rangeEnd) {
                var prefix = 32
                while (prefix > 0) {
                    val nextPrefix = prefix - 1
                    val blockSize = 1L shl (32 - nextPrefix)
                    val mask = blockSize - 1L
                    if ((current and mask) == 0L && (current + blockSize - 1L) <= rangeEnd) {
                        prefix = nextPrefix
                    } else {
                        break
                    }
                }
                val blockSize = 1L shl (32 - prefix)
                routes.add(CidrRoute(longToIpv4(current), prefix))
                current += blockSize
            }
        }
        return routes
    }

    internal fun cidrToRange(cidr: String): Pair<Long, Long> {
        val parts = cidr.split('/')
        require(parts.size == 2) { "Invalid CIDR: $cidr" }
        val ip = ipv4ToLong(parts[0])
        val prefix = parts[1].toInt()
        require(prefix in 0..32) { "Invalid prefix: $prefix" }
        val mask = if (prefix == 0) 0L else (MAX_IPV4 shl (32 - prefix)) and MAX_IPV4
        val start = ip and mask
        val end = start or (MAX_IPV4 xor mask)
        return start to end
    }

    internal fun ipv4ToLong(ip: String): Long {
        val octets = ip.trim().split('.')
        require(octets.size == 4) { "Invalid IPv4: $ip" }
        var result = 0L
        for (octet in octets) {
            val value = octet.toLong()
            require(value in 0..255) { "Invalid IPv4 octet: $octet" }
            result = (result shl 8) or value
        }
        return result
    }

    internal fun longToIpv4(ip: Long): String {
        return "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
    }
}
