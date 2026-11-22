package com.example.vpn

import com.example.model.VlessProfile
import com.example.model.VpnProfile
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

internal fun parseVpnUri(uri: String): Result<VpnProfile> =
    if (uri.trimStart().startsWith("vless://", ignoreCase = true)) {
        VlessUriParser.parse(uri)
    } else {
        VmessUriParser.parse(uri)
    }

object VlessUriParser {
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
    private val shortIdPattern = Regex("(?:[0-9a-fA-F]{2}){0,8}")

    fun parse(uri: String): Result<VlessProfile> = runCatching {
        val rawUri = uri.trim()
        val parsed = try {
            URI(rawUri)
        } catch (_: URISyntaxException) {
            // URI exceptions echo the full link, including the server credentials.
            throw IllegalArgumentException("Malformed VLESS URI")
        }
        require(parsed.scheme.equals("vless", ignoreCase = true)) { "URI must start with vless://" }
        val uuid = parsed.userInfo.orEmpty()
        require(uuidPattern.matches(uuid)) { "VLESS URI has an invalid UUID" }
        val address = parsed.host?.removeSurrounding("[", "]").orEmpty()
        require(address.isNotEmpty()) { "VLESS URI is missing a valid server address" }
        require(parsed.port in 1..65535) { "VLESS URI has an invalid port" }

        val query = parsed.rawQuery.orEmpty().split('&').associate {
            decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=', ""))
        }
        val encryption = query["encryption"].orEmpty().lowercase().ifBlank { "none" }
        require(encryption == "none") { "This core supports only VLESS encryption=none" }
        val network = query["type"].orEmpty().ifBlank { query["network"].orEmpty() }.lowercase().ifBlank { "tcp" }
        require(network in setOf("tcp", "raw", "ws", "http", "h2", "grpc", "httpupgrade")) {
            "Unsupported VLESS transport"
        }
        val security = query["security"].orEmpty().lowercase().ifBlank { "none" }
        require(security in setOf("none", "tls", "reality")) { "Unsupported VLESS security mode" }
        val flow = query["flow"].orEmpty()
        require(flow.isEmpty() || flow == "xtls-rprx-vision") { "Unsupported VLESS flow" }
        require(flow.isEmpty() || (network in setOf("tcp", "raw") && security != "none")) {
            "VLESS Vision requires TCP with TLS or REALITY"
        }
        val publicKey = query["pbk"].orEmpty()
        val shortId = query["sid"].orEmpty()
        if (security == "reality") {
            require(publicKey.isNotEmpty() && shortIdPattern.matches(shortId)) {
                "VLESS REALITY requires a public key and valid short ID"
            }
        }

        VlessProfile(
            rawUri = rawUri,
            remarks = parsed.fragment.orEmpty().trim().ifBlank { "$address:${parsed.port}" },
            address = address,
            port = parsed.port,
            uuid = uuid,
            encryption = encryption,
            flow = flow,
            network = network,
            security = security,
            path = query["path"].orEmpty(),
            host = query["host"].orEmpty(),
            sni = query["sni"].orEmpty(),
            fingerprint = query["fp"].orEmpty(),
            publicKey = publicKey,
            shortId = shortId,
            serviceName = query["servicename"].orEmpty(),
        )
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
}
