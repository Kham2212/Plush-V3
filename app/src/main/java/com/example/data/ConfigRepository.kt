package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.RemoteConfigPayload
import com.example.model.RemoteServerEntry
import com.example.model.VmessProfile
import com.example.vpn.VmessUriParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CachedVpnConfig(
    val remoteUrl: String,
    val localBypass: Boolean,
    val servers: List<RemoteServerEntry>,
    val selectedServerId: String,
    val selectedUri: String,
    val selectedProfile: VmessProfile?,
    val lastSyncEpochMs: Long,
)

class ConfigRepository(
    context: Context,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadCachedConfig(): CachedVpnConfig {
        val remoteUrl = getRemoteConfigUrl()
        val lastSync = getLastSyncEpochMs()
        val savedSelectedId = prefs.getString(KEY_SELECTED_SERVER_ID, null).orEmpty()
        val fallbackUri = getFallbackVmessUri()
        val fallbackProfile = VmessUriParser.parse(fallbackUri).getOrNull()
        val savedLocalBypass = prefs.getBoolean(KEY_LOCAL_BYPASS, true)

        val cachedJson = prefs.getString(KEY_CACHED_REMOTE_JSON, null)
        val parsedRemote: RemoteConfigPayload? = if (!cachedJson.isNullOrBlank()) {
            VmessUriParser.parseRemoteConfigJson(cachedJson).getOrNull()
        } else {
            null
        }

        if (parsedRemote != null && parsedRemote.servers.isNotEmpty()) {
            val servers = if (savedSelectedId == MANUAL_SERVER_ID && fallbackProfile != null) {
                val manualEntry = RemoteServerEntry(
                    id = MANUAL_SERVER_ID,
                    name = "${fallbackProfile.remarks} (Manual)",
                    uri = fallbackUri,
                    enabled = true,
                    profile = fallbackProfile,
                )
                listOf(manualEntry) + parsedRemote.servers
            } else {
                parsedRemote.servers
            }

            val selectedServer = servers.firstOrNull { it.id == savedSelectedId } ?: servers.first()
            return CachedVpnConfig(
                remoteUrl = remoteUrl,
                localBypass = parsedRemote.localBypass,
                servers = servers,
                selectedServerId = selectedServer.id,
                selectedUri = selectedServer.uri,
                selectedProfile = selectedServer.profile ?: VmessUriParser.parse(selectedServer.uri).getOrNull(),
                lastSyncEpochMs = lastSync,
            )
        }

        val fallbackEntry = RemoteServerEntry(
            id = FALLBACK_SERVER_ID,
            name = fallbackProfile?.remarks ?: "Fallback Node",
            uri = fallbackUri,
            enabled = true,
            profile = fallbackProfile,
        )
        return CachedVpnConfig(
            remoteUrl = remoteUrl,
            localBypass = savedLocalBypass,
            servers = listOf(fallbackEntry),
            selectedServerId = fallbackEntry.id,
            selectedUri = fallbackUri,
            selectedProfile = fallbackProfile,
            lastSyncEpochMs = lastSync,
        )
    }

    fun getRemoteConfigUrl(): String {
        return prefs.getString(KEY_REMOTE_CONFIG_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: VmessUriParser.DEFAULT_REMOTE_CONFIG_URL
    }

    fun getLastSyncEpochMs(): Long {
        return prefs.getLong(KEY_LAST_SYNC_EPOCH_MS, 0L)
    }

    private fun getFallbackVmessUri(): String {
        val saved = prefs.getString(KEY_ACTIVE_VMESS_URI, null)?.trim()
        return if (!saved.isNullOrEmpty() && VmessUriParser.parse(saved).isSuccess) {
            saved
        } else {
            VmessUriParser.DEFAULT_VMESS_URI
        }
    }

    fun selectServer(serverId: String): CachedVpnConfig = synchronized(selectionLock) {
        val current = loadCachedConfig()
        val chosen = current.servers.firstOrNull { it.id == serverId } ?: return@synchronized current
        prefs.edit()
            .putString(KEY_SELECTED_SERVER_ID, chosen.id)
            .putString(KEY_ACTIVE_VMESS_URI, chosen.uri)
            .apply()
        loadCachedConfig()
    }

    fun saveManualConfig(remoteUrl: String, vmessUri: String): Result<CachedVpnConfig> {
        val cleanUrl = remoteUrl.trim()
        if (cleanUrl.isNotEmpty() && !isValidHttpUrl(cleanUrl)) {
            return Result.failure(IllegalArgumentException("Remote URL must start with http:// or https://"))
        }

        val cleanUri = vmessUri.trim()
        val parsed = VmessUriParser.parse(cleanUri)
        if (parsed.isFailure) {
            return Result.failure(parsed.exceptionOrNull() ?: IllegalArgumentException("Invalid VMess URI"))
        }

        val current = loadCachedConfig()
        val normalizedNewUri = parsed.getOrThrow().rawUri
        val matchedServer = current.servers.firstOrNull { it.uri == normalizedNewUri }

        prefs.edit().apply {
            if (cleanUrl.isNotEmpty()) {
                putString(KEY_REMOTE_CONFIG_URL, cleanUrl)
            }
            putString(KEY_ACTIVE_VMESS_URI, normalizedNewUri)
            if (matchedServer != null) {
                putString(KEY_SELECTED_SERVER_ID, matchedServer.id)
            } else {
                putString(KEY_SELECTED_SERVER_ID, MANUAL_SERVER_ID)
            }
            apply()
        }

        return Result.success(loadCachedConfig())
    }

    fun resetToDefault(): CachedVpnConfig {
        prefs.edit()
            .remove(KEY_CACHED_REMOTE_JSON)
            .remove(KEY_SELECTED_SERVER_ID)
            .putBoolean(KEY_LOCAL_BYPASS, true)
            .putString(KEY_ACTIVE_VMESS_URI, VmessUriParser.DEFAULT_VMESS_URI)
            .putString(KEY_REMOTE_CONFIG_URL, VmessUriParser.DEFAULT_REMOTE_CONFIG_URL)
            .putLong(KEY_LAST_SYNC_EPOCH_MS, 0L)
            .apply()
        return loadCachedConfig()
    }

    suspend fun syncFromRemote(urlOverride: String? = null): Result<CachedVpnConfig> =
        withContext(Dispatchers.IO) {
            val targetUrl = (urlOverride ?: getRemoteConfigUrl()).trim()
            if (!isValidHttpUrl(targetUrl)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Remote config URL must start with http:// or https://"),
                )
            }

            try {
                val request = Request.Builder()
                    .url(targetUrl)
                    .get()
                    .header("Accept", "application/json, text/plain")
                    .build()

                val bodyText = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} ${response.message}")
                    }
                    response.body?.string().orEmpty()
                }

                val parsedResult = VmessUriParser.parseRemoteConfigJson(bodyText)
                if (parsedResult.isFailure) {
                    return@withContext Result.failure(
                        parsedResult.exceptionOrNull() ?: IllegalArgumentException("Invalid remote config JSON"),
                    )
                }

                val payload = parsedResult.getOrThrow()
                synchronized(selectionLock) {
                    val currentSelectedId = prefs.getString(KEY_SELECTED_SERVER_ID, null)
                    val nextSelected = payload.servers.firstOrNull { it.id == currentSelectedId }
                        ?: payload.servers.first()

                    val now = System.currentTimeMillis()
                    prefs.edit()
                        .putString(KEY_REMOTE_CONFIG_URL, targetUrl)
                        .putString(KEY_CACHED_REMOTE_JSON, bodyText.trim())
                        .putBoolean(KEY_LOCAL_BYPASS, payload.localBypass)
                        .putString(KEY_SELECTED_SERVER_ID, nextSelected.id)
                        .putString(KEY_ACTIVE_VMESS_URI, nextSelected.uri)
                        .putLong(KEY_LAST_SYNC_EPOCH_MS, now)
                        .apply()

                    Result.success(loadCachedConfig())
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun isValidHttpUrl(url: String): Boolean {
        return url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true)
    }

    companion object {
        // Selection and refresh both write the same preference keys.
        private val selectionLock = Any()
        private const val PREFS_NAME = "pulsevpn_prefs"
        private const val KEY_ACTIVE_VMESS_URI = "active_vmess_uri"
        private const val KEY_REMOTE_CONFIG_URL = "remote_config_url"
        private const val KEY_CACHED_REMOTE_JSON = "cached_remote_json"
        private const val KEY_SELECTED_SERVER_ID = "selected_server_id"
        private const val KEY_LOCAL_BYPASS = "local_bypass"
        private const val KEY_LAST_SYNC_EPOCH_MS = "last_sync_epoch_ms"

        const val MANUAL_SERVER_ID = "manual-custom"
        const val FALLBACK_SERVER_ID = "local-fallback"

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
