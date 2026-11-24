package com.directrouter.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.directrouter.app.client.ClientVpnState
import com.directrouter.app.client.ClientVpnStateHolder
import com.directrouter.app.client.DirectVpnService
import com.directrouter.app.data.RouterSettings
import com.directrouter.app.data.SettingsRepository
import com.directrouter.app.data.ValidationUtils
import com.directrouter.app.data.WifiBand
import com.directrouter.app.host.HostRouterService
import com.directrouter.app.host.HostServiceState
import com.directrouter.app.host.HostStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

sealed interface ConnectionProbeState {
    data object Idle : ConnectionProbeState
    data object Probing : ConnectionProbeState
    data class Success(val latencyMs: Long) : ConnectionProbeState
    data class Failure(val message: String) : ConnectionProbeState
}

class MainViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<RouterSettings> = settingsRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = RouterSettings(),
    )

    val hostState: StateFlow<HostServiceState> = HostStateHolder.state

    val clientState: StateFlow<ClientVpnState> = ClientVpnStateHolder.state

    private val _probeState = MutableStateFlow<ConnectionProbeState>(ConnectionProbeState.Idle)
    val probeState: StateFlow<ConnectionProbeState> = _probeState.asStateFlow()

    private var probeJob: Job? = null

    fun startHostRouter(context: Context) {
        HostRouterService.start(context.applicationContext)
    }

    fun stopHostRouter(context: Context) {
        HostRouterService.stop(context.applicationContext)
    }

    fun connectClientVpn(context: Context, hostIp: String, hostPort: Int) {
        val cleanIp = hostIp.trim()
        if (!ValidationUtils.isValidHostIp(cleanIp) || !ValidationUtils.isValidPort(hostPort)) {
            return
        }
        viewModelScope.launch {
            settingsRepository.updateClientEndpoint(cleanIp, hostPort)
        }
        DirectVpnService.connect(context.applicationContext, cleanIp, hostPort)
    }

    fun disconnectClientVpn(context: Context) {
        DirectVpnService.disconnect(context.applicationContext)
    }

    fun saveClientEndpoint(hostIp: String, hostPort: Int): Boolean {
        val cleanIp = hostIp.trim()
        if (!ValidationUtils.isValidHostIp(cleanIp) || !ValidationUtils.isValidPort(hostPort)) {
            return false
        }
        viewModelScope.launch {
            settingsRepository.updateClientEndpoint(cleanIp, hostPort)
        }
        return true
    }

    fun probeHostConnection(hostIp: String, hostPort: Int) {
        val cleanIp = hostIp.trim()
        if (!ValidationUtils.isValidHostIp(cleanIp) || !ValidationUtils.isValidPort(hostPort)) {
            _probeState.value = ConnectionProbeState.Failure("IP / Port invalid")
            return
        }
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            _probeState.value = ConnectionProbeState.Probing
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val startNs = System.nanoTime()
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(cleanIp, hostPort), PROBE_TIMEOUT_MS)
                    }
                    ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                }
            }
            _probeState.value = result.fold(
                onSuccess = { latencyMs -> ConnectionProbeState.Success(latencyMs) },
                onFailure = { error ->
                    ConnectionProbeState.Failure(
                        error.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: error.javaClass.simpleName
                    )
                },
            )
        }
    }

    fun clearProbeState() {
        probeJob?.cancel()
        _probeState.value = ConnectionProbeState.Idle
    }

    fun updateProxyPort(port: Int): Boolean {
        if (!ValidationUtils.isValidPort(port)) return false
        viewModelScope.launch {
            settingsRepository.updateProxyPort(port)
        }
        return true
    }

    fun updateSsidSuffix(suffix: String) {
        viewModelScope.launch {
            settingsRepository.updateSsidSuffix(suffix)
        }
    }

    fun updatePassphrase(passphrase: String): Boolean {
        if (!ValidationUtils.isValidPassphrase(passphrase)) return false
        viewModelScope.launch {
            settingsRepository.updatePassphrase(passphrase)
        }
        return true
    }

    fun regeneratePassphrase(): String {
        val generated = ValidationUtils.generateRandomPassphrase(10)
        viewModelScope.launch {
            settingsRepository.updatePassphrase(generated)
        }
        return generated
    }

    fun updateWifiBand(band: WifiBand) {
        viewModelScope.launch {
            settingsRepository.updateWifiBand(band)
        }
    }

    fun updateKeepCpuAwake(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateKeepCpuAwake(enabled)
        }
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 2_000

        fun factory(application: Application): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as DirectRouterApp
                    return MainViewModel(app.settingsRepository) as T
                }
            }
        }
    }
}
