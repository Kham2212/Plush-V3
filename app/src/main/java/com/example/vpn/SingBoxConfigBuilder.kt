package com.example.vpn

import com.example.model.VlessProfile
import com.example.model.VmessProfile
import com.example.model.VpnProfile
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfigBuilder {

    val PRIVATE_IPV4_CIDRS = listOf(
        "10.0.0.0/8",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/4",
        "240.0.0.0/4",
    )

    val PRIVATE_IPV6_CIDRS = listOf(
        "::1/128",
        "fc00::/7",
        "fe80::/10",
        "ff00::/8",
    )

    fun buildConfigJson(
        profile: VpnProfile,
        localBypass: Boolean = true,
        modern: Boolean = false,
    ): String {
        val root = JSONObject()

        root.put(
            "log",
            JSONObject().apply {
                put("disabled", false)
                put("level", "info")
                put("timestamp", true)
            },
        )

        root.put("dns", buildDnsObject(profile, modern))
        root.put("inbounds", JSONArray().apply { put(buildTunInbound(localBypass, modern)) })
        root.put(
            "outbounds",
            JSONArray().apply {
                put(
                    when (profile) {
                        is VmessProfile -> buildVmessOutbound(profile)
                        is VlessProfile -> buildVlessOutbound(profile)
                    },
                )
                put(
                    JSONObject().apply {
                        put("type", "direct")
                        put("tag", "direct")
                    },
                )
            },
        )
        root.put("route", buildRouteObject(profile, localBypass, modern))

        return root.toString(2)
    }

    private fun buildDnsObject(profile: VpnProfile, modern: Boolean): JSONObject {
        val dohServer = JSONObject().apply {
            put("tag", "doh-remote")
            if (modern) {
                put("type", "https")
                put("server", "1.1.1.1")
            } else {
                put("address", "https://1.1.1.1/dns-query")
                put("address_resolver", "dns-direct")
                put("strategy", "ipv4_only")
            }
            put("detour", "vmess-out")
        }

        val directServer = JSONObject().apply {
            put("tag", "dns-direct")
            if (modern) {
                put("type", "udp")
                put("server", "8.8.8.8")
            } else {
                put("address", "8.8.8.8")
                put("strategy", "ipv4_only")
                put("detour", "direct")
            }
        }

        val dnsRules = JSONArray().apply {
            if (!isIpLiteral(profile.address)) {
                put(
                    JSONObject().apply {
                        put("domain", JSONArray().apply { put(profile.address) })
                        if (modern) put("action", "route")
                    put("server", "dns-direct")
                    },
                )
            }
        }

        return JSONObject().apply {
            put(
                "servers",
                JSONArray().apply {
                    put(dohServer)
                    put(directServer)
                },
            )
            put("rules", dnsRules)
            put("final", "doh-remote")
            put("strategy", "ipv4_only")
        }
    }

    private fun buildTunInbound(localBypass: Boolean, modern: Boolean): JSONObject {
        return JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("address", JSONArray().apply { put("172.19.0.1/30") })
            put("mtu", 1400)
            put("auto_route", true)
            put("strict_route", false)
            put("stack", "system")
            if (!modern) put("sniff", true)
            if (localBypass) {
                val routeExcludes = JSONArray().apply {
                    PRIVATE_IPV4_CIDRS.forEach { put(it) }
                    PRIVATE_IPV6_CIDRS.forEach { put(it) }
                }
                put("route_exclude_address", routeExcludes)
            }
        }
    }

    private fun buildVmessOutbound(profile: VmessProfile): JSONObject {
        return JSONObject().apply {
            put("type", "vmess")
            put("tag", "vmess-out")
            put("server", profile.address)
            put("server_port", profile.port)
            put("uuid", profile.uuid)
            put("alter_id", profile.alterId)
            put("security", profile.security)

            if (profile.network == "ws") {
                put(
                    "transport",
                    JSONObject().apply {
                        put("type", "ws")
                        put("path", profile.wsPath)
                        val hostHeader = profile.wsHost.ifBlank { profile.address }
                        put("headers", JSONObject().apply { put("Host", hostHeader) })
                    },
                )
            }

            if (profile.tlsEnabled) {
                put(
                    "tls",
                    JSONObject().apply {
                        put("enabled", true)
                        put("server_name", profile.sni.ifBlank { profile.address })
                        put("insecure", false)
                        if (profile.alpn.isNotEmpty()) {
                            put("alpn", JSONArray(profile.alpn))
                        }
                    },
                )
            }
        }
    }

    private fun buildVlessOutbound(profile: VlessProfile): JSONObject = JSONObject().apply {
        // Keep the existing proxy tag so both protocols use the same DNS and routing.
        put("tag", "vmess-out")
        put("type", "vless")
        put("server", profile.address)
        put("server_port", profile.port)
        put("uuid", profile.uuid)
        // sing-box VLESS has no encryption field; the parser accepts only encryption=none.
        if (profile.flow.isNotEmpty()) put("flow", profile.flow)

        val path = "/" + profile.path.removePrefix("/")
        val transport = when (profile.network) {
            "ws" -> JSONObject().put("type", "ws").put("path", path).apply {
                if (profile.host.isNotBlank()) put("headers", JSONObject().put("Host", profile.host))
            }
            "http", "h2" -> JSONObject().put("type", "http").put("path", path).apply {
                if (profile.host.isNotBlank()) put("host", JSONArray(profile.host.split(',').map { it.trim() }))
            }
            "httpupgrade" -> JSONObject().put("type", "httpupgrade").put("path", path).apply {
                if (profile.host.isNotBlank()) put("host", profile.host)
            }
            "grpc" -> JSONObject().put("type", "grpc").put("service_name", profile.serviceName.ifBlank {
                profile.path.removePrefix("/")
            })
            else -> null // Plain TCP uses no transport object.
        }
        if (transport != null) put("transport", transport)

        if (profile.tlsEnabled) {
            put("tls", JSONObject().apply {
                put("enabled", true)
                put("server_name", profile.sni.ifBlank { profile.address })
                if (profile.fingerprint.isNotEmpty() || profile.security == "reality") {
                    put("utls", JSONObject().put("enabled", true)
                        .put("fingerprint", profile.fingerprint.ifBlank { "chrome" }))
                }
                if (profile.security == "reality") {
                    put("reality", JSONObject().put("enabled", true)
                        .put("public_key", profile.publicKey).put("short_id", profile.shortId))
                }
            })
        }
    }

    private fun buildRouteObject(profile: VpnProfile, localBypass: Boolean, modern: Boolean): JSONObject {
        val rules = JSONArray().apply {
            if (modern) put(JSONObject().put("action", "sniff"))
            put(
                JSONObject().apply {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                },
            )
            if (localBypass) {
                put(
                    JSONObject().apply {
                        put("ip_is_private", true)
                        put("outbound", "direct")
                    },
                )
            }
            if (isIpLiteral(profile.address)) {
                val prefixLength = if (profile is VlessProfile && profile.address.contains(':')) 128 else 32
                put(
                    JSONObject().apply {
                        put("ip_cidr", JSONArray().put("${profile.address}/$prefixLength"))
                        put("outbound", "direct")
                    },
                )
            } else {
                put(
                    JSONObject().apply {
                        put("domain", JSONArray().apply { put(profile.address) })
                        put("outbound", "direct")
                    },
                )
            }
        }

        return JSONObject().apply {
            put("auto_detect_interface", true)
            if (modern) put("default_domain_resolver", "dns-direct")
            put("rules", rules)
            put("final", "vmess-out")
        }
    }

    private fun isIpLiteral(host: String): Boolean {
        val ipv4Regex = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        return ipv4Regex.matches(host) || host.contains(':')
    }
}
