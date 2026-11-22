package com.example.vpn

import com.example.model.TrafficSpeed
import com.example.model.VpnConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object VpnStateHolder {

    private val _connectionState = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Disconnected)
    val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private val _trafficSpeed = MutableStateFlow(TrafficSpeed.ZERO)
    val trafficSpeed: StateFlow<TrafficSpeed> = _trafficSpeed.asStateFlow()

    private val _activeProfileLabel = MutableStateFlow<String?>(null)
    val activeProfileLabel: StateFlow<String?> = _activeProfileLabel.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun updateState(state: VpnConnectionState, profileLabel: String? = _activeProfileLabel.value) {
        _connectionState.value = state
        _activeProfileLabel.value = profileLabel
        if (state !is VpnConnectionState.Connected) {
            _trafficSpeed.value = TrafficSpeed.ZERO
        }
    }

    fun updateSpeed(downloadBytesPerSec: Long, uploadBytesPerSec: Long) {
        if (_connectionState.value is VpnConnectionState.Connected) {
            _trafficSpeed.value = TrafficSpeed(
                downloadBytesPerSec = downloadBytesPerSec.coerceAtLeast(0L),
                uploadBytesPerSec = uploadBytesPerSec.coerceAtLeast(0L),
            )
        } else {
            _trafficSpeed.value = TrafficSpeed.ZERO
        }
    }

    fun resetSpeed() {
        _trafficSpeed.value = TrafficSpeed.ZERO
    }

    fun appendLog(line: String) {
        val clean = line.trim()
        if (clean.isEmpty()) return
        _logs.update { current ->
            (current + clean).takeLast(40)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
