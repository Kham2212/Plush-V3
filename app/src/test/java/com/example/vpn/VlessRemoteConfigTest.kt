package com.example.vpn

import com.example.model.MaintenanceConfig
import com.example.model.VlessProfile
import com.example.model.VmessProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VlessRemoteConfigTest {

    @Test
    fun `top level maintenance defaults off and can be updated without servers`() {
        val notice = RemoteConfigParser.parse(
            """{"maintenance":true,"maintenance_title":"  Scheduled work ","maintenance_message":" Back soon. "}""",
        ).getOrThrow()
        assertEquals(MaintenanceConfig(true, "Scheduled work", "Back soon."), notice.maintenance)
        assertTrue(notice.servers.isEmpty())
        assertFalse(RemoteConfigParser.parse("""{"maintenance":false}""").getOrThrow().maintenance.enabled)
        assertFalse(RemoteConfigParser.parse("""["$WS_LINK"]""").getOrThrow().maintenance.enabled)
        assertTrue(RemoteConfigParser.parse("""{"servers":[]}""").isFailure)
    }

    @Test
    fun `mixed legacy list keeps labels ids ordering and disabled filtering`() {
        val vmess = VmessUriParser.DEFAULT_VMESS_URI
        val result = RemoteConfigParser.parse(
            """
            {
              "local_bypass": false,
              "servers": [
                {"id":"disabled","enabled":false,"config":"$WS_LINK"},
                {"id":"broken","link":"vless://invalid"},
                {"id":"new","name":"New Edge","config":{"link":"$WS_LINK"}},
                {"id":"old","name":"Old Edge","uri":"$vmess"},
                {"id":"old","name":"Duplicate","uri":"$vmess"}
              ]
            }
            """.trimIndent(),
        ).getOrThrow()
        assertFalse(result.localBypass)
        assertEquals(listOf("new", "old"), result.servers.map { it.id })
        assertEquals(listOf("New Edge", "Old Edge"), result.servers.map { it.name })
        assertTrue(result.servers[0].profile is VlessProfile)
        assertTrue(result.servers[1].profile is VmessProfile)
    }

    @Test
    fun `array wrappers discover links under arbitrary keys with stable ids`() {
        val payload = RemoteConfigParser.parse("""[{"items":[{"endpoint":"$WS_LINK"}]}]""").getOrThrow()
        val rewrapped = RemoteConfigParser.parse("""{"another":[{"data":"$WS_LINK"}]}""").getOrThrow()
        assertTrue(payload.localBypass)
        assertEquals(payload.servers.single().id, rewrapped.servers.single().id)
        assertEquals("Edge + West", payload.servers.single().name)
        assertTrue(RemoteConfigParser.parse("""[{"enabled":false,"data":"$WS_LINK"}]""").isFailure)
    }

    @Test
    fun `WS TLS outbound uses decoded fields and shares Cloudflare DoH with VMess`() {
        val profile = VlessUriParser.parse(WS_LINK).getOrThrow()
        assertEquals("none", profile.encryption)
        assertEquals("edge.example.net", profile.address)
        assertEquals(UUID, profile.uuid)

        for (modern in listOf(false, true)) {
            val config = JSONObject(SingBoxConfigBuilder.buildConfigJson(profile, modern = modern))
            val outbound = config.getJSONArray("outbounds").getJSONObject(0)
            val transport = outbound.getJSONObject("transport")
            val tls = outbound.getJSONObject("tls")
            assertEquals("vless", outbound.getString("type"))
            assertEquals(443, outbound.getInt("server_port"))
            assertFalse(outbound.has("encryption"))
            assertEquals("ws", transport.getString("type"))
            assertEquals("/tunnel?token=a+b", transport.getString("path"))
            assertEquals("cdn.example.net", transport.getJSONObject("headers").getString("Host"))
            assertTrue(tls.getBoolean("enabled"))
            assertEquals("tls.example.net", tls.getString("server_name"))
            assertFalse(tls.optBoolean("insecure"))

            val vmessProfile = VmessUriParser.parse(VmessUriParser.DEFAULT_VMESS_URI).getOrThrow()
            val vmess = JSONObject(SingBoxConfigBuilder.buildConfigJson(vmessProfile, modern = modern))
            val vmessOutbound = vmess.getJSONArray("outbounds").getJSONObject(0)
            assertEquals("vmess", vmessOutbound.getString("type"))
            assertEquals("ws", vmessOutbound.getJSONObject("transport").getString("type"))
            assertTrue(vmessOutbound.getJSONObject("tls").getBoolean("enabled"))

            val doh = config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
            val vmessDoh = vmess.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
            assertEquals("vmess-out", vmessDoh.getString("detour"))
            assertEquals("vmess-out", vmess.getJSONObject("route").getString("final"))
            assertEquals("vless-out", outbound.getString("tag"))
            assertEquals(outbound.getString("tag"), doh.getString("detour"))
            assertEquals(outbound.getString("tag"), config.getJSONObject("route").getString("final"))
            assertEquals(
                if (modern) "1.1.1.1" else "https://1.1.1.1/dns-query",
                doh.getString(if (modern) "server" else "address"),
            )
        }
    }

    @Test
    fun `TCP REALITY Vision outbound maps fingerprint key short id and IPv6`() {
        val link = "vless://$UUID@[2001:db8::1]:8443?network=tcp&flow=xtls-rprx-vision" +
            "&security=reality&sni=example.net&fp=firefox&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=abcd"
        val config = JSONObject(SingBoxConfigBuilder.buildConfigJson(parseVpnUri(link).getOrThrow()))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("2001:db8::1", outbound.getString("server"))
        assertEquals("xtls-rprx-vision", outbound.getString("flow"))
        assertFalse(outbound.has("transport"))
        val tls = outbound.getJSONObject("tls")
        assertEquals("firefox", tls.getJSONObject("utls").getString("fingerprint"))
        val reality = tls.getJSONObject("reality")
        assertTrue(reality.getBoolean("enabled"))
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", reality.getString("public_key"))
        assertEquals("abcd", reality.getString("short_id"))
    }

    @Test
    fun `modern proxy bootstrap is separate from tunneled app DNS`() {
        val profile = VlessUriParser.parse(WS_LINK).getOrThrow()
        val config = JSONObject(SingBoxConfigBuilder.buildConfigJson(profile, modern = true))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        val dns = config.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")
        val doh = servers.getJSONObject(0)
        val bootstrap = servers.getJSONObject(1)
        assertEquals(doh.getString("tag"), dns.getString("final"))
        assertEquals("/dns-query", doh.getString("path"))
        assertEquals(bootstrap.getString("tag"), outbound.getString("domain_resolver"))
        assertFalse(bootstrap.has("detour"))
        assertEquals(0, dns.getJSONArray("rules").length())
    }

    @Test
    fun `DNS has priority and only LAN routes are sent direct when bypass is enabled`() {
        val profiles = listOf(WS_LINK, "vless://$UUID@[2001:db8::1]:443").map {
            parseVpnUri(it).getOrThrow()
        }
        for (profile in profiles) for (modern in listOf(false, true)) for (localBypass in listOf(false, true)) {
            val config = JSONObject(SingBoxConfigBuilder.buildConfigJson(profile, localBypass, modern))
            val tun = config.getJSONArray("inbounds").getJSONObject(0)
            val route = config.getJSONObject("route")
            val rules = route.getJSONArray("rules")
            val firstRule = rules.getJSONObject(0)
            assertEquals(53, firstRule.getInt("port"))
            assertEquals("hijack-dns", firstRule.getString("action"))
            val directRules = (0 until rules.length()).map { rules.getJSONObject(it) }.filter {
                it.optString("outbound") == "direct"
            }
            if (localBypass) {
                val direct = directRules.single()
                assertFalse(direct.has("domain"))
                assertEquals(tun.getJSONArray("route_exclude_address").toString(), direct.getJSONArray("ip_cidr").toString())
                assertFalse(direct.getJSONArray("ip_cidr").toString().contains(profile.address))
            } else {
                assertTrue(directRules.isEmpty())
                assertFalse(tun.has("route_exclude_address"))
            }
        }
    }

    private companion object {
        const val UUID = "b831381d-6324-4d53-ad4f-8cda48b30811"
        const val WS_LINK = "vless://$UUID@edge.example.net:443?type=ws&security=tls" +
            "&path=%2Ftunnel%3Ftoken%3Da+b&host=cdn.example.net&sni=tls.example.net#Edge%20%2B%20West"
    }
}
