package com.example.vpn

import com.example.model.RemoteConfigPayload
import com.example.model.RemoteServerEntry
import com.example.model.VmessProfile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object VmessUriParser {

    const val DEFAULT_REMOTE_CONFIG_URL =
        "https://raw.githubusercontent.com/gam81557-ui/config/refs/heads/main/files.json"

    private const val DEFAULT_JSON_PAYLOAD =
        """{"v":"2","ps":"Pulse Edge (Default)","add":"vpn.example.com","port":"443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","scy":"auto","net":"ws","type":"none","host":"vpn.example.com","path":"/ws","tls":"tls","sni":"vpn.example.com"}"""

    val DEFAULT_VMESS_URI: String by lazy {
        "vmess://" + encodeBase64(DEFAULT_JSON_PAYLOAD.toByteArray(StandardCharsets.UTF_8))
    }

    fun parseRemoteConfigJson(rawBody: String): Result<RemoteConfigPayload> {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Remote config response is empty"))
        }

        // Allow plain vmess:// line fallback if a custom URL returns raw text instead of JSON
        if (trimmed.startsWith("vmess://", ignoreCase = true)) {
            val singleUri = extractFirstVmessUri(trimmed)
                ?: return Result.failure(IllegalArgumentException("Invalid vmess:// URI"))
            val parsed = parse(singleUri).getOrElse { return Result.failure(it) }
            return Result.success(
                RemoteConfigPayload(
                    localBypass = true,
                    servers = listOf(
                        RemoteServerEntry(
                            id = "remote-single",
                            name = parsed.remarks,
                            uri = parsed.rawUri,
                            enabled = true,
                            profile = parsed,
                        ),
                    ),
                ),
            )
        }

        return try {
            val root = JSONObject(trimmed)
            val localBypass = root.optBoolean("local_bypass", true)
            val serversArray = root.optJSONArray("servers")
                ?: return Result.failure(IllegalArgumentException("Remote JSON missing 'servers' array"))

            val parsedServers = mutableListOf<RemoteServerEntry>()
            val seenServerIds = mutableSetOf<String>()
            for (index in 0 until serversArray.length()) {
                val obj = serversArray.optJSONObject(index) ?: continue
                val enabled = obj.optBoolean("enabled", true)
                if (!enabled) continue

                val rawUri = obj.optString("uri", "").trim()
                val profileResult = parse(rawUri)
                if (profileResult.isFailure) continue

                val profile = profileResult.getOrThrow()
                val id = obj.optString("id", "").trim().ifBlank { "server-$index" }
                if (!seenServerIds.add(id)) continue
                val name = obj.optString("name", "").trim().ifBlank { profile.remarks }

                parsedServers.add(
                    RemoteServerEntry(
                        id = id,
                        name = name,
                        uri = profile.rawUri,
                        enabled = true,
                        profile = profile.copy(remarks = name),
                    ),
                )
            }

            if (parsedServers.isEmpty()) {
                Result.failure(IllegalArgumentException("No enabled valid VMess servers found in remote JSON"))
            } else {
                Result.success(
                    RemoteConfigPayload(
                        localBypass = localBypass,
                        servers = parsedServers,
                    ),
                )
            }
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Malformed remote JSON: ${e.message}"))
        }
    }

    fun extractFirstVmessUri(rawBody: String): String? {
        return rawBody
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("vmess://", ignoreCase = true) }
    }

    fun parse(uri: String): Result<VmessProfile> {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("vmess://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("URI must start with vmess://"))
        }

        val encoded = trimmed.substring(8).trim()
        if (encoded.isEmpty()) {
            return Result.failure(IllegalArgumentException("VMess payload is empty"))
        }

        val jsonText = try {
            String(decodeBase64Flexible(encoded), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid Base64 VMess payload: ${e.message}"))
        }

        return try {
            val json = JSONObject(jsonText)
            val address = json.optString("add", "").trim()
            if (address.isEmpty()) {
                return Result.failure(IllegalArgumentException("VMess config missing server address (add)"))
            }

            val port = when (val portRaw = json.opt("port")) {
                is Number -> portRaw.toInt()
                is String -> portRaw.trim().toIntOrNull() ?: -1
                else -> -1
            }
            if (port !in 1..65535) {
                return Result.failure(IllegalArgumentException("VMess config has invalid port: $port"))
            }

            val uuid = json.optString("id", "").trim()
            if (uuid.isEmpty()) {
                return Result.failure(IllegalArgumentException("VMess config missing UUID (id)"))
            }

            val alterId = when (val aidRaw = json.opt("aid")) {
                is Number -> aidRaw.toInt()
                is String -> aidRaw.trim().toIntOrNull() ?: 0
                else -> 0
            }

            val security = json.optString("scy", "").ifBlank {
                json.optString("security", "auto")
            }.ifBlank { "auto" }

            val network = json.optString("net", "ws").ifBlank { "ws" }.lowercase()
            val rawPath = json.optString("path", "/").trim()
            val wsPath = if (rawPath.isEmpty()) "/" else if (rawPath.startsWith("/")) rawPath else "/$rawPath"
            val wsHost = json.optString("host", "").trim().ifBlank { address }
            val tlsRaw = json.optString("tls", "").trim().lowercase()
            val tlsEnabled = tlsRaw == "tls" || tlsRaw == "true" || tlsRaw == "1"
            val sni = json.optString("sni", "").trim().ifBlank { wsHost }
            val alpn = json.optString("alpn", "")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val remarks = json.optString("ps", "").trim().ifBlank { "$address:$port" }

            Result.success(
                VmessProfile(
                    rawUri = "vmess://$encoded",
                    remarks = remarks,
                    address = address,
                    port = port,
                    uuid = uuid,
                    alterId = alterId.coerceAtLeast(0),
                    security = security,
                    network = network,
                    wsPath = wsPath,
                    wsHost = wsHost,
                    tlsEnabled = tlsEnabled,
                    sni = sni,
                    alpn = alpn,
                ),
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Malformed VMess JSON payload: ${e.message}"))
        }
    }

    internal fun encodeBase64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val hasB1 = i + 1 < bytes.size
            val hasB2 = i + 2 < bytes.size
            val b1 = if (hasB1) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) bytes[i + 2].toInt() and 0xFF else 0

            sb.append(alphabet[(b0 ushr 2) and 0x3F])
            sb.append(alphabet[((b0 and 0x03) shl 4) or ((b1 ushr 4) and 0x0F)])
            sb.append(if (hasB1) alphabet[((b1 and 0x0F) shl 2) or ((b2 ushr 6) and 0x03)] else '=')
            sb.append(if (hasB2) alphabet[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    internal fun decodeBase64Flexible(input: String): ByteArray {
        val normalized = input
            .replace('-', '+')
            .replace('_', '/')
            .filterNot { it.isWhitespace() }
            .trimEnd('=')

        val out = ByteArrayOutputStream((normalized.length * 3) / 4)
        var buffer = 0
        var bitsCollected = 0

        for (ch in normalized) {
            val value = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> throw IllegalArgumentException("Unexpected Base64 character: $ch")
            }
            buffer = (buffer shl 6) or value
            bitsCollected += 6
            if (bitsCollected >= 8) {
                bitsCollected -= 8
                out.write((buffer shr bitsCollected) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
