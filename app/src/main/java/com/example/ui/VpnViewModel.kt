package com.example.ui

import android.app.Application
import android.content.Context
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.CachedVpnConfig
import com.example.data.ConfigRepository
import com.example.model.LibboxAvailability
import com.example.model.MaintenanceConfig
import com.example.model.SyncStatus
import com.example.model.TrafficSpeed
import com.example.model.VlessProfile
import com.example.model.VmessProfile
import com.example.model.VpnConnectionState
import com.example.vpn.LibboxBridge
import com.example.vpn.VmessVpnService
import com.example.vpn.VpnStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerChoice(val id: String, val name: String)

data class VpnUiState(
    val connectionState: VpnConnectionState = VpnConnectionState.Disconnected,
    val trafficSpeed: TrafficSpeed = TrafficSpeed.ZERO,
    val tlsEnabled: Boolean = true,
    val servers: List<ServerChoice> = emptyList(),
    val selectedServerId: String = "",
    val lastSyncEpochMs: Long = 0L,
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val libboxAvailability: LibboxAvailability = LibboxAvailability(false, "Not bundled"),
    val snackbarMessage: String? = null,
    val maintenance: MaintenanceConfig = MaintenanceConfig(),
)

private data class LocalConfigSnapshot(
    val activeUri: String,
    val tlsEnabled: Boolean,
    val localBypass: Boolean,
    val servers: List<ServerChoice>,
    val selectedServerId: String,
    val lastSyncEpochMs: Long,
    val syncStatus: SyncStatus,
    val libboxAvailability: LibboxAvailability,
    val snackbarMessage: String?,
    val maintenance: MaintenanceConfig,
)

class VpnViewModel internal constructor(
    application: Application,
    private val repository: ConfigRepository,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, ConfigRepository(application))

    private val localState = MutableStateFlow(toSnapshot(repository.loadCachedConfig()))

    val uiState: StateFlow<VpnUiState> = combine(
        VpnStateHolder.connectionState,
        VpnStateHolder.trafficSpeed,
        localState,
    ) { connState, speed, local ->
        val effectiveSpeed = if (connState is VpnConnectionState.Connected) speed else TrafficSpeed.ZERO
        VpnUiState(
            connectionState = connState,
            trafficSpeed = effectiveSpeed,
            tlsEnabled = local.tlsEnabled,
            servers = local.servers,
            selectedServerId = local.selectedServerId,
            lastSyncEpochMs = local.lastSyncEpochMs,
            syncStatus = local.syncStatus,
            libboxAvailability = local.libboxAvailability,
            snackbarMessage = local.snackbarMessage,
            maintenance = local.maintenance,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VpnUiState(maintenance = localState.value.maintenance),
    )

    init {
        syncRemoteConfig(isAutoLaunch = true)
    }

    private fun toSnapshot(cached: CachedVpnConfig): LocalConfigSnapshot =
        LocalConfigSnapshot(
            activeUri = cached.selectedUri,
            tlsEnabled = cached.selectedProfile?.tlsEnabled != false,
            localBypass = cached.localBypass,
            servers = serverChoices(cached),
            selectedServerId = cached.selectedServerId,
            lastSyncEpochMs = cached.lastSyncEpochMs,
            syncStatus = SyncStatus.Idle,
            libboxAvailability = LibboxBridge.detectLibbox(),
            snackbarMessage = null,
            maintenance = cached.maintenance,
        )

    fun syncRemoteConfig(isAutoLaunch: Boolean = false) {
        if (localState.value.syncStatus is SyncStatus.Syncing) return
        viewModelScope.launch {
            localState.update { it.copy(syncStatus = SyncStatus.Syncing) }
            val result = repository.syncFromRemote()
            val app = getApplication<Application>()

            if (result.isSuccess) {
                val updated = repository.loadCachedConfig()
                val msg = app.getString(R.string.msg_sync_success)
                localState.update { current ->
                    current.copy(
                        activeUri = updated.selectedUri,
                        tlsEnabled = updated.selectedProfile?.tlsEnabled != false,
                        localBypass = updated.localBypass,
                        servers = serverChoices(updated),
                        selectedServerId = updated.selectedServerId,
                        lastSyncEpochMs = updated.lastSyncEpochMs,
                        maintenance = updated.maintenance,
                        syncStatus = SyncStatus.Success(msg),
                        snackbarMessage = if (isAutoLaunch) null else msg,
                    )
                }
                VpnStateHolder.appendLog(msg)
            } else {
                val reason = result.exceptionOrNull()?.message ?: "Network unavailable"
                val fallbackMsg = app.getString(R.string.msg_sync_fallback)
                localState.update {
                    it.copy(
                        syncStatus = SyncStatus.Fallback(fallbackMsg),
                        snackbarMessage = if (isAutoLaunch) null else fallbackMsg,
                    )
                }
                VpnStateHolder.appendLog("Remote JSON sync failed ($reason); using cached fallback")
            }
        }
    }

    fun selectServer(serverId: String, context: Context) {
        if (serverId == localState.value.selectedServerId) return
        val updated = repository.selectServer(serverId)
        val choices = serverChoices(updated)
        val chosenName = choices.firstOrNull { it.id == updated.selectedServerId }?.name ?: return

        localState.update { current ->
            current.copy(
                activeUri = updated.selectedUri,
                tlsEnabled = updated.selectedProfile?.tlsEnabled != false,
                localBypass = updated.localBypass,
                servers = choices,
                selectedServerId = updated.selectedServerId,
                snackbarMessage = getApplication<Application>().getString(R.string.msg_server_selected, chosenName),
            )
        }
        VpnStateHolder.appendLog("Selected server: $chosenName")

        when (VpnStateHolder.connectionState.value) {
            VpnConnectionState.Connected, VpnConnectionState.Connecting -> startVpnConnection(context)
            else -> Unit
        }
    }

    private fun serverChoices(cached: CachedVpnConfig): List<ServerChoice> =
        cached.servers.mapIndexed { index, server ->
            val profile = server.profile
            val transportValues = when (profile) {
                is VmessProfile -> listOf(profile.wsHost, profile.wsPath)
                is VlessProfile -> listOf(profile.host, profile.path, profile.uuid)
                null -> emptyList()
            }
            val sensitiveValues = (listOfNotNull(profile?.address, profile?.sni) + transportValues)
                .filter { it.isNotBlank() && it != "/" }
            val name = server.name.takeUnless { label ->
                label.isBlank() || "://" in label ||
                    sensitiveValues.any { label.contains(it, ignoreCase = true) }
            } ?: getApplication<Application>().getString(R.string.label_numbered_server, index + 1)
            ServerChoice(server.id, name)
        }

    fun isMaintenanceActive(): Boolean = localState.value.maintenance.enabled

    fun prepareVpnIntent(context: Context): android.content.Intent? {
        return VpnService.prepare(context)
    }

    fun startVpnConnection(context: Context) {
        val snapshot = localState.value
        if (snapshot.maintenance.enabled) return
        val selectedName = snapshot.servers.firstOrNull { it.id == snapshot.selectedServerId }?.name
        VpnStateHolder.updateState(VpnConnectionState.Connecting, selectedName)
        VmessVpnService.startConnect(
            context = context,
            vmessUri = snapshot.activeUri,
            localBypass = snapshot.localBypass,
            serverName = selectedName,
        )
    }

    fun onVpnPermissionDenied() {
        if (isMaintenanceActive()) return
        val msg = getApplication<Application>().getString(R.string.msg_vpn_permission_denied)
        VpnStateHolder.updateState(VpnConnectionState.Error(msg))
    }

    fun disconnectVpn(context: Context) {
        VmessVpnService.stopDisconnect(context)
    }

    fun clearErrorState() {
        if (VpnStateHolder.connectionState.value is VpnConnectionState.Error) {
            VpnStateHolder.updateState(VpnConnectionState.Disconnected)
        }
    }

    fun consumeSnackbar() {
        localState.update { it.copy(snackbarMessage = null) }
    }
}
