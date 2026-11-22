package com.example.model

sealed class VpnConnectionState {
    data object Disconnected : VpnConnectionState()
    data object Connecting : VpnConnectionState()
    data object Connected : VpnConnectionState()
    data class Error(val message: String) : VpnConnectionState()
}

data class TrafficSpeed(
    val downloadBytesPerSec: Long = 0L,
    val uploadBytesPerSec: Long = 0L,
) {
    companion object {
        val ZERO = TrafficSpeed(0L, 0L)
    }
}

data class VmessProfile(
    val rawUri: String,
    val remarks: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val alterId: Int = 0,
    val security: String = "auto",
    val network: String = "ws",
    val wsPath: String = "/",
    val wsHost: String = "",
    val tlsEnabled: Boolean = true,
    val sni: String = "",
    val alpn: List<String> = emptyList(),
)

data class RemoteServerEntry(
    val id: String,
    val name: String,
    val uri: String,
    val enabled: Boolean,
    val profile: VmessProfile? = null,
)

data class RemoteConfigPayload(
    val localBypass: Boolean,
    val servers: List<RemoteServerEntry>,
)

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data object Syncing : SyncStatus()
    data class Success(val message: String) : SyncStatus()
    data class Fallback(val message: String) : SyncStatus()
}

data class LibboxAvailability(
    val isBundled: Boolean,
    val versionLabel: String,
)
