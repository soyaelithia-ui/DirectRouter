package com.directrouter.app.host

import com.directrouter.app.proxy.TrafficSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ServiceStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
}

data class HostServiceState(
    val status: ServiceStatus = ServiceStatus.STOPPED,
    val ssid: String = "",
    val passphrase: String = "",
    val ipAddress: String = "192.168.49.1",
    val port: Int = 8080,
    val connectedWifiClients: Int = 0,
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val errorMessage: String? = null,
)

object HostStateHolder {
    private val _state = MutableStateFlow(HostServiceState())
    val state: StateFlow<HostServiceState> = _state.asStateFlow()

    fun update(transform: (HostServiceState) -> HostServiceState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = HostServiceState()
    }
}
