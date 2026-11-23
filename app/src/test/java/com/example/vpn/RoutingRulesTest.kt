package com.example.vpn

import androidx.test.core.app.ApplicationProvider
import androidx.core.content.edit
import com.example.data.RoutingRulesStore
import com.example.model.RoutingAction
import com.example.model.RoutingMatch
import com.example.model.RoutingPreset
import com.example.model.RoutingRule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoutingRulesTest {
    @Test
    fun `saved rules retain order and enable state through a new store`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val rules = listOf(
            RoutingRule("proxy", RoutingAction.PROXY, RoutingMatch.DOMAIN, "example.com"),
            RoutingRule("direct", RoutingAction.DIRECT, RoutingMatch.IP, "2001:db8::/32", enabled = false),
        )
        RoutingRulesStore(context).save(rules)
        assertEquals(rules, RoutingRulesStore(context).load())

        val edited = listOf(rules.first().copy(value = "other.example", enabled = false))
        RoutingRulesStore(context).save(edited)
        assertEquals(edited, RoutingRulesStore(context).load())
    }

    @Test
    fun `presets are present and off initially and toggles survive reload`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = RoutingRulesStore(context)
        val defaults = RoutingPreset.entries.map { it.rule() }
        assertEquals(defaults, store.load())
        val enabled = defaults.map { it.copy(enabled = true) }
        store.save(enabled)
        assertEquals(enabled, RoutingRulesStore(context).load())
        val edited = defaults.first().copy(action = RoutingAction.BLOCK, value = "10.0.0.0/8", enabled = true)
        store.save(listOf(edited))
        assertEquals(listOf(edited), RoutingRulesStore(context).load())
        store.save(emptyList())
        assertEquals(emptyList<RoutingRule>(), RoutingRulesStore(context).load())
    }

    @Test
    fun `upgrade keeps prior UDP toggle and custom order while adding new presets`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        context.getSharedPreferences("routing_rules", android.content.Context.MODE_PRIVATE).edit {
            putString("rules", """[
              {"id":"preset:block-udp-443","action":"BLOCK","match":"PORT","value":"443","enabled":true},
              {"id":"custom","action":"DIRECT","match":"DOMAIN","value":"example.com","enabled":true}
            ]""")
        }
        val rules = RoutingRulesStore(context).load()
        assertEquals(RoutingPreset.BLOCK_UDP_443.rule(enabled = true), rules.first())
        assertEquals("custom", rules[1].id)
        assertEquals(
            listOf(RoutingPreset.LAN_BYPASS, RoutingPreset.PROXY_GOOGLE, RoutingPreset.BYPASS_CN_PRIVATE_DNS),
            rules.drop(2).map { it.preset },
        )
        assertFalse(rules.drop(2).any { it.enabled })
    }

    @Test
    fun `default presets preserve current routing and enabled UDP block never matches TCP`() {
        val profile = VmessUriParser.parse(VmessUriParser.DEFAULT_VMESS_URI).getOrThrow()
        val defaults = RoutingPreset.entries.map { it.rule() }
        for (modern in listOf(false, true)) for (localBypass in listOf(false, true)) {
            val original = SingBoxConfigBuilder.buildConfigJson(profile, localBypass, modern)
            assertEquals(original, SingBoxConfigBuilder.buildConfigJson(profile, localBypass, modern, defaults))

            val config = JSONObject(SingBoxConfigBuilder.buildConfigJson(
                profile, localBypass, modern, defaults.map { it.copy(enabled = true) },
            ))
            val rules = config.getJSONObject("route").getJSONArray("rules")
            val entries = (0 until rules.length()).map { rules.getJSONObject(it) }
            val udpBlock = entries.single { it.optInt("port") == 443 }
            assertEquals("udp", udpBlock.getString("network"))
            assertEquals("reject", udpBlock.getString("action"))
            val lan = entries.first { it.has("ip_cidr") }
            val ranges = lan.getJSONArray("ip_cidr")
            assertEquals(
                listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fc00::/7", "fe80::/10"),
                (0 until ranges.length()).map { ranges.getString(it) },
            )
            assertEquals("direct", lan.getString("outbound"))
            val google = entries.single { it.optJSONArray("domain_suffix")?.getString(0) == "google.com" }
            val cn = entries.single { it.optJSONArray("domain_suffix")?.getString(0) == "cn" }
            val publicDns = entries.single { it.optJSONArray("ip_cidr")?.getString(0) == "223.5.5.5/32" }
            assertEquals("vmess-out", google.getString("outbound"))
            assertEquals("direct", cn.getString("outbound"))
            assertEquals("direct", publicDns.getString("outbound"))
            assertEquals(entries.indexOf(cn) + 1, entries.indexOf(publicDns))
            assertFalse(config.toString().contains("geosite"))
            assertFalse(config.toString().contains("geoip"))
            assertEquals(
                JSONObject(original).getJSONArray("inbounds").toString(),
                config.getJSONArray("inbounds").toString(),
            )
            assertEquals(
                JSONObject(original).getJSONObject("dns").toString(),
                config.getJSONObject("dns").toString(),
            )
        }
    }

    @Test
    fun `rules respect DNS priority, selected outbound, LAN fallback, and enable state`() {
        val profiles = listOf(
            VmessUriParser.parse(VmessUriParser.DEFAULT_VMESS_URI).getOrThrow(),
            VlessUriParser.parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@edge.example:443").getOrThrow(),
        )
        val custom = listOf(
            RoutingRule("block", RoutingAction.BLOCK, RoutingMatch.PORT, "12345"),
            RoutingRule("off", RoutingAction.DIRECT, RoutingMatch.PORT, "23456", enabled = false),
            RoutingRule("direct", RoutingAction.DIRECT, RoutingMatch.IP, "10.0.0.4"),
            RoutingRule("proxy", RoutingAction.PROXY, RoutingMatch.DOMAIN, "Example.COM."),
            RoutingRule("invalid", RoutingAction.BLOCK, RoutingMatch.PORT, "65536"),
        )
        for (profile in profiles) for (modern in listOf(false, true)) {
            val config = JSONObject(SingBoxConfigBuilder.buildConfigJson(profile, modern = modern, routingRules = custom))
            val route = config.getJSONObject("route")
            val rules = route.getJSONArray("rules").let { json ->
                (0 until json.length()).map { json.getJSONObject(it) }
            }
            assertEquals("hijack-dns", rules.first().getString("action"))
            assertEquals(53, rules.first().getInt("port"))
            assertEquals(listOf(53, 12345), rules.mapNotNull { it.optInt("port").takeIf { p -> p > 0 } })
            val dns = rules.indexOfLast { it.optString("action") == "hijack-dns" }
            assertEquals("reject", rules[dns + 1].getString("action"))
            assertEquals("10.0.0.4/32", rules[dns + 2].getJSONArray("ip_cidr").getString(0))
            assertEquals("direct", rules[dns + 2].getString("outbound"))
            assertEquals("example.com", rules[dns + 3].getJSONArray("domain_suffix").getString(0))
            val proxy = config.getJSONArray("outbounds").getJSONObject(0).getString("tag")
            assertEquals(proxy, rules[dns + 3].getString("outbound"))
            assertEquals(proxy, route.getString("final"))
            assertEquals(proxy, config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"))
            assertEquals("direct", rules.last().getString("outbound"))
            assertFalse(rules.last().has("domain_suffix"))
        }
    }

    @Test
    fun `rules validate hostnames numeric addresses CIDR bounds and ports`() {
        assertEquals("xn--bcher-kva.example", RoutingMatch.DOMAIN.normalize("Bücher.Example."))
        assertNull(RoutingMatch.DOMAIN.normalize("https://example.com"))
        assertNull(RoutingMatch.DOMAIN.normalize("1.1.1.1"))
        assertEquals("2001:db8::1/128", RoutingMatch.IP.normalize("2001:DB8::1"))
        assertNull(RoutingMatch.IP.normalize("192.168.1.1/33"))
        assertNull(RoutingMatch.IP.normalize("256.0.0.1"))
        assertNull(RoutingMatch.IP.normalize("2001:db8::1/129"))
        assertNull(RoutingMatch.IP.normalize("edge.example"))
        assertEquals("443", RoutingMatch.PORT.normalize("00443"))
        assertNull(RoutingMatch.PORT.normalize("0"))
    }
}
