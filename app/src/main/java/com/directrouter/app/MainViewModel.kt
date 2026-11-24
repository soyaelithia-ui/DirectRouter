package com.directrouter.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.directrouter.app.client.ClientVpnState
import com.directrouter.app.client.ClientVpnStateHolder
import com.directrouter.app.client.DirectVpnService
import com.directrouter.app.data.AppThemeMode
import com.directrouter.app.data.BindMode
import com.directrouter.app.data.HostShareMode
import com.directrouter.app.data.RouterSettings
import com.directrouter.app.data.SettingsRepository
import com.directrouter.app.data.ValidationUtils
import com.directrouter.app.data.WifiBand
import com.directrouter.app.host.DetectedInterface
import com.directrouter.app.host.HostRouterService
import com.directrouter.app.host.HostServiceState
import com.directrouter.app.host.HostStateHolder
import com.directrouter.app.host.NetworkInterfaceScanner
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ConnectionProbeState {
    data object Idle : ConnectionProbeState
    data object Probing : ConnectionProbeState
    data class Success(val latencyMs: Long) : ConnectionProbeState
    data class Failure(val message: String) : ConnectionProbeState
}

class MainViewModel(
    private val appContext: Context,
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
    private var pendingClientAuth: Pair<String, String>? = null

    fun startHostRouter(context: Context) {
        HostRouterService.start(context.applicationContext)
    }

    fun stopHostRouter(context: Context) {
        HostRouterService.stop(context.applicationContext)
    }

    fun restartHostRouter(context: Context) {
        HostRouterService.restart(context.applicationContext)
    }

    fun connectClientVpn(context: Context, hostIp: String, hostPort: Int) {
        val cleanIp = hostIp.trim()
        if (!ValidationUtils.isValidHostIp(cleanIp) || !ValidationUtils.isValidPort(hostPort)) {
            return
        }
        viewModelScope.launch {
            settingsRepository.updateClientEndpoint(cleanIp, hostPort)
        }
        val authOverride = pendingClientAuth
        if (authOverride != null) {
            DirectVpnService.connect(
                context = context.applicationContext,
                hostIp = cleanIp,
                hostPort = hostPort,
                authUsername = authOverride.first,
                authPassword = authOverride.second,
            )
        } else {
            DirectVpnService.connect(context.applicationContext, cleanIp, hostPort)
        }
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
                    val wifiNetwork = findActiveWifiNetwork()
                    val socket = runCatching {
                        wifiNetwork?.socketFactory?.createSocket()
                    }.getOrNull() ?: Socket()
                    socket.use { s ->
                        s.connect(InetSocketAddress(cleanIp, hostPort), PROBE_TIMEOUT_MS)
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

    @Suppress("DEPRECATION")
    private fun findActiveWifiNetwork(): Network? {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            cm.allNetworks.firstOrNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } ?: cm.activeNetwork?.takeIf { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@takeIf false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrNull()
    }

    fun clearProbeState() {
        probeJob?.cancel()
        _probeState.value = ConnectionProbeState.Idle
    }

    fun getActiveLocalInterfaces(): List<DetectedInterface> {
        return NetworkInterfaceScanner.scanTetheringIps()
    }

    fun isActiveLocalIp(ip: String): Boolean {
        return NetworkInterfaceScanner.isActiveLocalIpv4(ip.trim())
    }

    fun updateHostShareMode(mode: HostShareMode) {
        viewModelScope.launch {
            settingsRepository.updateHostShareMode(mode)
        }
    }

    fun updateProxyPort(port: Int): Boolean {
        if (!ValidationUtils.isValidPort(port)) return false
        viewModelScope.launch {
            settingsRepository.updateProxyPort(port)
        }
        return true
    }

    fun generateRandomProxyPort(): Int {
        val current = settings.value.proxyPort
        var candidate = ValidationUtils.generateRandomPort()
        if (candidate == current) {
            candidate = ValidationUtils.generateRandomPort()
        }
        viewModelScope.launch {
            settingsRepository.updateProxyPort(candidate)
        }
        return candidate
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

    fun updateThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            settingsRepository.updateThemeMode(mode)
        }
    }

    fun cycleThemeMode() {
        val next = when (settings.value.themeMode) {
            AppThemeMode.SYSTEM -> AppThemeMode.DARK
            AppThemeMode.LIGHT -> AppThemeMode.DARK
            AppThemeMode.DARK -> AppThemeMode.AMOLED
            AppThemeMode.AMOLED -> AppThemeMode.LIGHT
        }
        updateThemeMode(next)
    }

    fun updateDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateDynamicColor(enabled)
        }
    }

    fun updateBindMode(mode: BindMode) {
        viewModelScope.launch {
            settingsRepository.updateBindMode(mode)
        }
    }

    fun updateCustomBindIp(ip: String): Boolean {
        val trimmed = ip.trim()
        if (trimmed.isNotEmpty() && !ValidationUtils.isStrictIpv4(trimmed)) {
            return false
        }
        viewModelScope.launch {
            settingsRepository.updateCustomBindIp(trimmed)
        }
        return true
    }

    fun updateBindConfig(mode: BindMode, customIp: String): Boolean {
        val trimmed = customIp.trim()
        if (mode == BindMode.SPECIFIC_IP && !ValidationUtils.isStrictIpv4(trimmed)) {
            return false
        }
        if (trimmed.isNotEmpty() && !ValidationUtils.isStrictIpv4(trimmed)) {
            return false
        }
        viewModelScope.launch {
            settingsRepository.updateBindConfig(mode, trimmed)
        }
        return true
    }

    fun updateBlockLocalhostAccess(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateBlockLocalhostAccess(enabled)
        }
    }

    fun updateMaxConnections(value: Int): Boolean {
        if (!ValidationUtils.isValidMaxConnections(value)) return false
        viewModelScope.launch {
            settingsRepository.updateMaxConnections(value)
        }
        return true
    }

    fun updateProxyAuth(enabled: Boolean, username: String, password: String): Boolean {
        val trimmedUser = username.trim()
        val trimmedPass = password.trim()
        if (!ValidationUtils.isValidProxyAuthCredential(trimmedUser)) return false
        if (enabled && !ValidationUtils.isValidProxyAuthCredential(trimmedPass)) return false
        if (trimmedPass.isNotEmpty() && !ValidationUtils.isValidProxyAuthCredential(trimmedPass)) return false
        viewModelScope.launch {
            settingsRepository.updateProxyAuth(enabled, trimmedUser, trimmedPass)
        }
        return true
    }

    fun updateProxyAuthEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateProxyAuthEnabled(enabled)
        }
    }

    fun generateProxyAuthPassword(): String {
        val generated = ValidationUtils.generateSecurePassword(12)
        viewModelScope.launch {
            settingsRepository.updateProxyAuthPassword(generated)
        }
        return generated
    }

    fun updateClientAuth(username: String, password: String) {
        val trimmedUser = username.trim()
        val trimmedPass = password.trim()
        if (trimmedUser.isNotEmpty() && !ValidationUtils.isValidProxyAuthCredential(trimmedUser)) {
            return
        }
        if (trimmedPass.isNotEmpty() && !ValidationUtils.isValidProxyAuthCredential(trimmedPass)) {
            return
        }
        pendingClientAuth = Pair(trimmedUser, trimmedPass)
        viewModelScope.launch {
            settingsRepository.updateClientAuth(trimmedUser, trimmedPass)
        }
    }

    fun updateNotifShowLiveSpeed(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateNotifShowLiveSpeed(enabled)
        }
    }

    fun updateNotifPrivacyMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateNotifPrivacyMode(enabled)
        }
    }

    fun updateNotifShowStopAction(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateNotifShowStopAction(enabled)
        }
    }

    fun updateNotifClientAlerts(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateNotifClientAlerts(enabled)
        }
    }

    fun updateNotifAlertSound(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateNotifAlertSound(enabled)
        }
    }

    fun updateNotifAlertVibrate(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateNotifAlertVibrate(enabled)
        }
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 2_000

        fun factory(application: Application): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as DirectRouterApp
                    return MainViewModel(app.applicationContext, app.settingsRepository) as T
                }
            }
        }
    }
}
