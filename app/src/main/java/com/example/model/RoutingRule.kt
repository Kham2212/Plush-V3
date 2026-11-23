package com.example.model

import java.net.IDN
import java.net.InetAddress
import java.util.Locale

enum class RoutingAction { PROXY, DIRECT, BLOCK }

enum class RoutingMatch {
    DOMAIN, IP, PORT, UDP_PORT, DOMAIN_OR_IP;

    /** Returns the normalized match condition, or null if any entry is invalid. */
    fun normalize(input: String): String? {
        if (this == PORT || this == UDP_PORT) {
            return input.trim().takeIf { it.matches(Regex("[0-9]{1,5}")) }
                ?.toIntOrNull()?.takeIf { it in 1..65535 }?.toString()
        }
        return input.split(',').map { entry ->
            val value = entry.trim()
            when (this) {
                DOMAIN -> normalizeDomain(value)
                IP -> normalizeCidr(value)
                DOMAIN_OR_IP -> normalizeCidr(value) ?: normalizeDomain(value)
                PORT, UDP_PORT -> error("Port handled above")
            } ?: return null
        }.distinct().joinToString(", ")
    }

    private fun normalizeDomain(value: String): String? {
        val ascii = runCatching { IDN.toASCII(value.removeSuffix("."), IDN.USE_STD3_ASCII_RULES) }
            .getOrNull()?.lowercase(Locale.ROOT) ?: return null
        return ascii.takeIf { it.length in 1..253 && it.any(Char::isLetter) }
    }

    private fun normalizeCidr(value: String): String? {
        val parts = value.split('/', limit = 2)
        val address = parts[0]
        val bits = if (':' in address) {
            // Check the characters first so this never looks up a hostname.
            if (!address.matches(Regex("[0-9a-fA-F:.]+"))) return null
            if ('.' in address && !isIpv4(address.substringAfterLast(':'))) return null
            if (runCatching { InetAddress.getByName(address) }.getOrNull() == null) return null
            128
        } else {
            if (!isIpv4(address)) return null
            32
        }
        val prefix = parts.getOrNull(1)?.let {
            if (!it.matches(Regex("[0-9]{1,3}"))) return null
            it.toInt().takeIf { n -> n in 0..bits } ?: return null
        } ?: bits
        return "${address.lowercase(Locale.ROOT)}/$prefix"
    }

    private fun isIpv4(address: String): Boolean {
        val octets = address.split('.')
        return octets.size == 4 && octets.all {
            it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255
        }
    }
}

enum class RoutingPreset(
    val id: String,
    val action: RoutingAction,
    val match: RoutingMatch,
    val value: String,
) {
    LAN_BYPASS(
        "preset:lan-bypass",
        RoutingAction.DIRECT,
        RoutingMatch.IP,
        "10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, fc00::/7, fe80::/10",
    ),
    BLOCK_UDP_443("preset:block-udp-443", RoutingAction.BLOCK, RoutingMatch.UDP_PORT, "443"),
    PROXY_GOOGLE(
        "preset:proxy-google",
        RoutingAction.PROXY,
        RoutingMatch.DOMAIN,
        "google.com, google.cn, googleapis.com, gstatic.com, googleusercontent.com, " +
            "youtube.com, youtu.be, ytimg.com, googlevideo.com",
    ),
    BYPASS_CN_PRIVATE_DNS(
        "preset:bypass-cn-private-dns",
        RoutingAction.DIRECT,
        RoutingMatch.DOMAIN_OR_IP,
        "cn, localhost, local, lan, home.arpa, alidns.com, doh.pub, 223.5.5.5/32, 223.6.6.6/32, 2400:3200::1/128",
    );

    fun rule(enabled: Boolean = false) = RoutingRule(id, action, match, value, enabled)
}

data class RoutingRule(
    val id: String,
    val action: RoutingAction,
    val match: RoutingMatch,
    val value: String,
    val enabled: Boolean = true,
) {
    val preset: RoutingPreset? get() = RoutingPreset.entries.firstOrNull { it.id == id }

    fun validated(): RoutingRule? =
        match.normalize(value)?.let { copy(value = it) }
}
