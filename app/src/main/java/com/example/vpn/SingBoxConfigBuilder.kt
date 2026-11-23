package com.example.vpn

import com.example.model.RoutingAction
import com.example.model.RoutingMatch
import com.example.model.RoutingRule
import com.example.model.VlessProfile
import com.example.model.VmessProfile
import com.example.model.VpnProfile
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfigBuilder {

    private const val DIRECT_TAG = "direct"
    private const val BOOTSTRAP_DNS_TAG = "dns-direct"
    private const val DOH_TAG = "doh-remote"
    private val LOCAL_CIDRS = BypassRoutesCalculator.DEFAULT_BYPASSED_CIDRS + listOf(
        "::1/128",
        "fc00::/7",
        "fe80::/10",
        "ff00::/8",
    )

    fun buildConfigJson(
        profile: VpnProfile,
        localBypass: Boolean = true,
        modern: Boolean = false,
        routingRules: List<RoutingRule> = emptyList(),
    ): String {
        val proxyTag = when (profile) {
            is VmessProfile -> "vmess-out"
            is VlessProfile -> "vless-out"
        }
        val root = JSONObject()

        root.put(
            "log",
            JSONObject().apply {
                put("disabled", false)
                put("level", "info")
                put("timestamp", true)
            },
        )

        root.put("dns", buildDnsObject(profile, proxyTag, modern))
        root.put("inbounds", JSONArray().apply { put(buildTunInbound(localBypass, modern)) })
        root.put(
            "outbounds",
            JSONArray().apply {
                put(
                    when (profile) {
                        is VmessProfile -> buildVmessOutbound(profile)
                        is VlessProfile -> buildVlessOutbound(profile)
                    }.apply {
                        put("tag", proxyTag)
                        if (modern && !isIpLiteral(profile.address)) {
                            // Resolve the proxy before opening the connection that carries DoH.
                            put("domain_resolver", BOOTSTRAP_DNS_TAG)
                        }
                    },
                )
                put(
                    JSONObject().apply {
                        put("type", DIRECT_TAG)
                        put("tag", DIRECT_TAG)
                    },
                )
            },
        )
        root.put("route", buildRouteObject(proxyTag, localBypass, modern, routingRules))

        return root.toString(2)
    }

    private fun buildDnsObject(profile: VpnProfile, proxyTag: String, modern: Boolean): JSONObject {
        val dohServer = JSONObject().apply {
            put("tag", DOH_TAG)
            if (modern) {
                put("type", "https")
                put("server", "1.1.1.1")
                put("path", "/dns-query")
            } else {
                put("address", "https://1.1.1.1/dns-query")
                put("strategy", "ipv4_only")
            }
            put("detour", proxyTag)
        }

        val directServer = JSONObject().apply {
            put("tag", BOOTSTRAP_DNS_TAG)
            if (modern) {
                put("type", "udp")
                put("server", "8.8.8.8")
            } else {
                put("address", "8.8.8.8")
                put("strategy", "ipv4_only")
                put("detour", DIRECT_TAG)
            }
        }

        val dnsRules = JSONArray().apply {
            // Legacy cores use a DNS rule to resolve the proxy's own hostname.
            if (!modern && !isIpLiteral(profile.address)) {
                put(
                    JSONObject().apply {
                        put("domain", JSONArray().apply { put(profile.address) })
                        put("server", BOOTSTRAP_DNS_TAG)
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
            put("final", DOH_TAG)
            put("strategy", if (modern) "prefer_ipv4" else "ipv4_only")
        }
    }

    private fun buildTunInbound(localBypass: Boolean, modern: Boolean): JSONObject {
        return JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("address", JSONArray().apply {
                put("172.19.0.1/30")
                if (modern) put("fdfe:dcba:9876::1/126")
            })
            put("mtu", 1400)
            put("auto_route", true)
            put("strict_route", false)
            put("stack", "system")
            if (!modern) put("sniff", true)
            if (localBypass) {
                put("route_exclude_address", JSONArray(LOCAL_CIDRS))
            }
        }
    }

    private fun buildVmessOutbound(profile: VmessProfile): JSONObject {
        return JSONObject().apply {
            put("type", "vmess")
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

    private fun buildRouteObject(
        proxyTag: String,
        localBypass: Boolean,
        modern: Boolean,
        routingRules: List<RoutingRule>,
    ): JSONObject {
        val rules = JSONArray().apply {
            put(JSONObject().put("port", 53).put("action", "hijack-dns"))
            if (modern) put(JSONObject().put("action", "sniff"))
            put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
            for (candidate in routingRules) {
                if (!candidate.enabled) continue
                val rule = candidate.validated() ?: continue
                val values = rule.value.split(", ")
                fun addRule(matcher: JSONObject) {
                    when (rule.action) {
                        RoutingAction.BLOCK -> matcher.put("action", "reject")
                        RoutingAction.PROXY, RoutingAction.DIRECT -> {
                            if (modern) matcher.put("action", "route")
                            matcher.put("outbound", if (rule.action == RoutingAction.PROXY) proxyTag else DIRECT_TAG)
                        }
                    }
                    put(matcher)
                }

                when (rule.match) {
                    RoutingMatch.DOMAIN -> addRule(JSONObject().put("domain_suffix", JSONArray(values)))
                    RoutingMatch.IP -> addRule(JSONObject().put("ip_cidr", JSONArray(values)))
                    RoutingMatch.PORT -> addRule(JSONObject().put("port", rule.value.toInt()))
                    RoutingMatch.UDP_PORT -> addRule(JSONObject().put("network", "udp").put("port", rule.value.toInt()))
                    RoutingMatch.DOMAIN_OR_IP -> {
                        // Emit two adjacent matches so either condition can select the action.
                        val (cidrs, domains) = values.partition { '/' in it }
                        if (domains.isNotEmpty()) addRule(JSONObject().put("domain_suffix", JSONArray(domains)))
                        if (cidrs.isNotEmpty()) addRule(JSONObject().put("ip_cidr", JSONArray(cidrs)))
                    }
                }
            }
            if (localBypass) {
                put(
                    JSONObject().apply {
                        put("ip_cidr", JSONArray(LOCAL_CIDRS))
                        if (modern) put("action", "route")
                        put("outbound", DIRECT_TAG)
                    },
                )
            }
        }

        return JSONObject().apply {
            // Libbox protects its own sockets; inbound traffic to the proxy IP/host still uses final.
            put("auto_detect_interface", true)
            if (modern) put("default_domain_resolver", DOH_TAG)
            put("rules", rules)
            put("final", proxyTag)
        }
    }

    private fun isIpLiteral(host: String): Boolean {
        val ipv4Regex = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        return ipv4Regex.matches(host) || host.contains(':')
    }
}
