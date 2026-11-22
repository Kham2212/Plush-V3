package com.example.vpn

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
class VmessUriParserTest {

    @Test
    fun `parses valid vmess ws tls uri and builds sing-box json with local bypass toggle`() {
        val rawJson = """
            {
              "v": "2",
              "ps": "Frankfurt Edge",
              "add": "fra.example.net",
              "port": "443",
              "id": "b831381d-6324-4d53-ad4f-8cda48b30811",
              "aid": 0,
              "scy": "auto",
              "net": "ws",
              "host": "cdn.example.net",
              "path": "/ray",
              "tls": "tls",
              "sni": "cdn.example.net"
            }
        """.trimIndent()
        val encoded = VmessUriParser.encodeBase64(rawJson.toByteArray())
        val result = VmessUriParser.parse("vmess://$encoded")

        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()
        assertEquals("Frankfurt Edge", profile.remarks)
        assertEquals("fra.example.net", profile.address)
        assertEquals(443, profile.port)
        assertEquals("/ray", profile.wsPath)
        assertEquals("cdn.example.net", profile.wsHost)
        assertTrue(profile.tlsEnabled)

        val singBoxBypassOn = JSONObject(SingBoxConfigBuilder.buildConfigJson(profile, localBypass = true))
        val tunBypassOn = singBoxBypassOn.getJSONArray("inbounds").getJSONObject(0)
        assertTrue(tunBypassOn.has("route_exclude_address"))

        val singBoxBypassOff = JSONObject(SingBoxConfigBuilder.buildConfigJson(profile, localBypass = false))
        val tunBypassOff = singBoxBypassOff.getJSONArray("inbounds").getJSONObject(0)
        assertFalse(tunBypassOff.has("route_exclude_address"))
    }

    @Test
    fun `parses remote github json filtering enabled servers and local_bypass`() {
        val remoteJson = """
            {
              "local_bypass": false,
              "servers": [
                {
                  "id": "uk-disabled",
                  "name": "Disabled Node",
                  "uri": "${VmessUriParser.DEFAULT_VMESS_URI}",
                  "enabled": false
                },
                {
                  "id": "uk-1",
                  "name": "UK Free VMess 1",
                  "uri": "${VmessUriParser.DEFAULT_VMESS_URI}",
                  "enabled": true
                }
              ]
            }
        """.trimIndent()

        val result = VmessUriParser.parseRemoteConfigJson(remoteJson)
        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertFalse(payload.localBypass)
        assertEquals(1, payload.servers.size)
        assertEquals("uk-1", payload.servers[0].id)
        assertEquals("UK Free VMess 1", payload.servers[0].name)
    }

    @Test
    fun `default vmess uri parses cleanly`() {
        val parsed = VmessUriParser.parse(VmessUriParser.DEFAULT_VMESS_URI)
        assertTrue(parsed.isSuccess)
        assertEquals("ws", parsed.getOrThrow().network)
        assertTrue(parsed.getOrThrow().tlsEnabled)
    }

    @Test
    fun `rejects non-vmess uri`() {
        val result = VmessUriParser.parse("vless://something")
        assertFalse(result.isSuccess)
    }
}
