package com.example.vpn

import com.example.model.MaintenanceConfig
import com.example.model.RemoteConfigPayload
import com.example.model.RemoteServerEntry
import com.example.model.VlessProfile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

internal object RemoteConfigParser {
    private val maintenanceKeys = setOf("maintenance", "maintenance_title", "maintenance_message")

    fun readMaintenance(root: JSONObject?): MaintenanceConfig = MaintenanceConfig(
        enabled = root?.opt("maintenance") == true,
        title = (root?.opt("maintenance_title") as? String).orEmpty().trim(),
        message = (root?.opt("maintenance_message") as? String).orEmpty().trim(),
    )

    fun parse(rawBody: String): Result<RemoteConfigPayload> = runCatching {
        val body = rawBody.trim().removePrefix("\uFEFF").trimStart()
        require(body.isNotEmpty()) { "Remote config response is empty" }

        if (body.startsWith("vmess://", ignoreCase = true)) {
            val uri = requireNotNull(VmessUriParser.extractFirstVmessUri(body)) { "Invalid vmess:// URI" }
            val profile = VmessUriParser.parse(uri).getOrThrow()
            return@runCatching RemoteConfigPayload(
                localBypass = true,
                servers = listOf(RemoteServerEntry("remote-single", profile.remarks, profile.rawUri, true, profile)),
            )
        }

        val root = try {
            val tokenizer = JSONTokener(body)
            val value = tokenizer.nextValue()
            require((value is JSONObject || value is JSONArray) && tokenizer.nextClean() == '\u0000')
            value
        } catch (_: Exception) {
            throw IllegalArgumentException("Malformed remote config JSON")
        }

        val legacyArray = (root as? JSONObject)?.optJSONArray("servers")
        val usedIds = mutableSetOf<String>()
        // Reserve legacy IDs first so an added VLESS entry cannot change an existing VMess selection.
        val legacyEntries = (0 until (legacyArray?.length() ?: 0)).associateWith { index ->
            val obj = legacyArray?.optJSONObject(index) ?: return@associateWith null
            if (!obj.optBoolean("enabled", true)) return@associateWith null
            val profile = VmessUriParser.parse(obj.optString("uri", "").trim()).getOrNull()
                ?: return@associateWith null
            val id = obj.optString("id", "").trim().ifBlank { "server-$index" }
            if (!usedIds.add(id)) return@associateWith null
            val name = obj.optString("name", "").trim().ifBlank { profile.remarks }
            RemoteServerEntry(id, name, profile.rawUri, true, profile.copy(remarks = name))
        }
        val servers = mutableListOf<RemoteServerEntry>()
        val seenVlessUris = mutableSetOf<String>()

        fun visit(value: Any?, id: String = "", name: String = "", isRoot: Boolean = false) {
            when (value) {
                is JSONObject -> {
                    if (!value.optBoolean("enabled", true)) return
                    val rawId = value.opt("id")
                    val objectId = when (rawId) {
                        is String, is Number -> rawId.toString().trim().ifBlank { id }
                        else -> id
                    }
                    val objectName = (value.opt("name") as? String).orEmpty().trim().ifBlank { name }
                    for (key in value.keys()) {
                        if (!isRoot || key !in maintenanceKeys) visit(value.opt(key), objectId, objectName)
                    }
                }
                is JSONArray -> for (index in 0 until value.length()) {
                    if (value === legacyArray) legacyEntries[index]?.let(servers::add)
                    visit(value.opt(index), id, name)
                }
                is String -> for (line in value.lineSequence()) {
                    if (!line.trimStart().startsWith("vless://", ignoreCase = true)) continue
                    val profile: VlessProfile = VlessUriParser.parse(line).getOrNull() ?: continue
                    if (!seenVlessUris.add(profile.rawUri)) continue
                    val serverId = if (id.isNotEmpty() && usedIds.add(id)) {
                        id
                    } else {
                        val baseId = "vless-${UUID.nameUUIDFromBytes(profile.rawUri.toByteArray(Charsets.UTF_8))}"
                        var candidate = baseId
                        var suffix = 1
                        while (!usedIds.add(candidate)) candidate = "$baseId-${suffix++}"
                        candidate
                    }
                    val displayName = name.ifBlank { profile.remarks }
                    servers += RemoteServerEntry(serverId, displayName, profile.rawUri, true, profile.copy(remarks = displayName))
                }
            }
        }

        // The legacy root is a container, not a server entry.
        if (root is JSONObject && legacyArray != null) {
            for (key in root.keys()) if (key !in maintenanceKeys) visit(root.opt(key))
        } else {
            visit(root, isRoot = true)
        }
        val rootObject = root as? JSONObject
        val maintenance = readMaintenance(rootObject)
        require(servers.isNotEmpty() || rootObject?.opt("maintenance") is Boolean) {
            "No enabled valid VMess or VLESS servers found in remote JSON"
        }
        RemoteConfigPayload(rootObject?.optBoolean("local_bypass", true) ?: true, servers, maintenance)
    }
}
