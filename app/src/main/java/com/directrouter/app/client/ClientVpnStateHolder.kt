package com.directrouter.app.client

import com.directrouter.app.host.ServiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ClientVpnState(
    val status: ServiceStatus = ServiceStatus.STOPPED,
    val hostIp: String = "192.168.49.1",
    val hostPort: Int = 8080,
    val wifiConnected: Boolean = false,
    val wifiNetworkLabel: String = "",
    val txPackets: Long = 0L,
    val txBytes: Long = 0L,
    val rxPackets: Long = 0L,
    val rxBytes: Long = 0L,
    val startedAtEpochMs: Long = 0L,
    val errorMessage: String? = null,
)

object ClientVpnStateHolder {
    private val _state = MutableStateFlow(ClientVpnState())
    val state: StateFlow<ClientVpnState> = _state.asStateFlow()

    fun update(transform: (ClientVpnState) -> ClientVpnState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = ClientVpnState()
    }
}
