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

sealed interface VpnProfile {
    val rawUri: String
    val remarks: String
    val address: String
    val port: Int
    val uuid: String
    val tlsEnabled: Boolean
    val sni: String
}

data class VmessProfile(
    override val rawUri: String,
    override val remarks: String,
    override val address: String,
    override val port: Int,
    override val uuid: String,
    val alterId: Int = 0,
    val security: String = "auto",
    val network: String = "ws",
    val wsPath: String = "/",
    val wsHost: String = "",
    override val tlsEnabled: Boolean = true,
    override val sni: String = "",
    val alpn: List<String> = emptyList(),
) : VpnProfile

data class VlessProfile(
    override val rawUri: String,
    override val remarks: String,
    override val address: String,
    override val port: Int,
    override val uuid: String,
    val encryption: String,
    val flow: String,
    val network: String,
    val security: String,
    val path: String,
    val host: String,
    override val sni: String,
    val fingerprint: String,
    val publicKey: String,
    val shortId: String,
    val serviceName: String,
) : VpnProfile {
    override val tlsEnabled: Boolean get() = security != "none"
}

data class RemoteServerEntry(
    val id: String,
    val name: String,
    val uri: String,
    val enabled: Boolean,
    val profile: VpnProfile? = null,
)

data class MaintenanceConfig(
    val enabled: Boolean = false,
    val title: String = "",
    val message: String = "",
)

data class RemoteConfigPayload(
    val localBypass: Boolean,
    val servers: List<RemoteServerEntry>,
    val maintenance: MaintenanceConfig = MaintenanceConfig(),
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
