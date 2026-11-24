package com.directrouter.app.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.directrouter.app.DirectRouterApp
import com.directrouter.app.R
import com.directrouter.app.data.BindMode
import com.directrouter.app.data.HostShareMode
import com.directrouter.app.data.RouterSettings
import com.directrouter.app.data.ValidationUtils
import com.directrouter.app.proxy.HybridProxyServer
import com.directrouter.app.proxy.ProxySecurityPolicy
import com.directrouter.app.proxy.TrafficSnapshot
import com.directrouter.app.proxy.TrafficStatsTracker
import java.net.BindException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class HostRouterService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleMutex = Mutex()

    private var settingsJob: Job? = null
    private var startupJob: Job? = null
    private var statsJob: Job? = null
    private var interfacePollJob: Job? = null

    private var statsTracker: TrafficStatsTracker? = null
    private var proxyServer: HybridProxyServer? = null
    private var wifiDirectManager: WifiDirectManager? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var currentSettings: RouterSettings = RouterSettings()

    private var lastNotificationUpdateMs: Long = 0L
    private var lastReportedClientCount: Int? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startSettingsObservation()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop(preserveError = false)
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                startForegroundCompat(
                    buildNotification(
                        title = if (currentSettings.notifPrivacyMode) {
                            "DirectRouter"
                        } else {
                            "DirectRouter Host"
                        },
                        text = "Reiniciando servidor proxy...",
                        settings = currentSettings,
                    ),
                )
                requestRestart()
                return START_STICKY
            }

            ACTION_START, null -> {
                startForegroundCompat(
                    buildNotification(
                        title = if (currentSettings.notifPrivacyMode) {
                            "DirectRouter"
                        } else {
                            "DirectRouter Host"
                        },
                        text = "Iniciando servidor proxy...",
                        settings = currentSettings,
                    ),
                )
                requestStart()
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        settingsJob?.cancel()
        settingsJob = null
        val currentStatus = HostStateHolder.state.value.status
        val preserveError = currentStatus == ServiceStatus.ERROR
        teardownComponentsSync(preserveError = preserveError)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startSettingsObservation() {
        settingsJob?.cancel()
        val app = application as DirectRouterApp
        settingsJob = serviceScope.launch {
            app.settingsRepository.settingsFlow.collectLatest { newSettings ->
                val prev = currentSettings
                currentSettings = newSettings
                val notifVisualChanged =
                    prev.notifShowLiveSpeed != newSettings.notifShowLiveSpeed ||
                        prev.notifPrivacyMode != newSettings.notifPrivacyMode ||
                        prev.notifShowStopAction != newSettings.notifShowStopAction
                if (notifVisualChanged) {
                    val status = HostStateHolder.state.value.status
                    if (status == ServiceStatus.RUNNING || status == ServiceStatus.STARTING) {
                        updateNotification(force = true)
                    }
                }
            }
        }
    }

    private fun requestStart() {
        val currentStatus = HostStateHolder.state.value.status
        if (currentStatus == ServiceStatus.STARTING || currentStatus == ServiceStatus.RUNNING) {
            return
        }

        startupJob?.cancel()
        startupJob = serviceScope.launch {
            lifecycleMutex.withLock {
                val status = HostStateHolder.state.value.status
                if (status == ServiceStatus.STARTING || status == ServiceStatus.RUNNING) {
                    return@withLock
                }
                startRouterLocked()
            }
        }
    }

    private fun requestRestart() {
        startupJob?.cancel()
        startupJob = serviceScope.launch {
            lifecycleMutex.withLock {
                val hadWifiDirect = wifiDirectManager != null
                teardownComponentsAsync(preserveError = false)
                delay(if (hadWifiDirect) RESTART_WFD_COOLDOWN_MS else RESTART_PROXY_COOLDOWN_MS)
                startRouterLocked()
            }
        }
    }

    private fun requestStop(preserveError: Boolean) {
        startupJob?.cancel()
        startupJob = serviceScope.launch {
            lifecycleMutex.withLock {
                stopRouterLocked(preserveError = preserveError)
            }
        }
    }

    private suspend fun startRouterLocked() {
        try {
            val app = application as DirectRouterApp
            val settings = app.settingsRepository.getCurrentSettings()
            currentSettings = settings

            val shareMode = settings.hostShareMode
            val effectiveAuthEnabled =
                settings.proxyAuthEnabled && settings.proxyAuthPassword.isNotBlank()
            val securityPolicy = ProxySecurityPolicy(
                blockLoopbackAndSelf = settings.blockLocalhostAccess,
                maxConcurrentConnections = settings.maxConnections,
                authEnabled = effectiveAuthEnabled,
                authUsername = settings.proxyAuthUsername,
                authPassword = settings.proxyAuthPassword,
            )

            lastReportedClientCount = null

            HostStateHolder.update {
                HostServiceState(
                    status = ServiceStatus.STARTING,
                    shareMode = shareMode,
                    ssid = if (shareMode == HostShareMode.WIFI_DIRECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        settings.fullSsid
                    } else {
                        ""
                    },
                    passphrase = if (shareMode == HostShareMode.WIFI_DIRECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        settings.passphrase
                    } else {
                        ""
                    },
                    ipAddress = if (shareMode == HostShareMode.NATIVE_TETHERING) {
                        DEFAULT_TETHERING_IP
                    } else {
                        WifiDirectInfo.DEFAULT_GROUP_OWNER_IP
                    },
                    port = settings.proxyPort,
                    localIpAddresses = emptyList(),
                    boundAddress = "",
                    isBoundAllInterfaces = settings.bindMode == BindMode.ALL_INTERFACES,
                    isProxyAuthEnabled = effectiveAuthEnabled,
                    isAntiSsrfEnabled = settings.blockLocalhostAccess,
                    maxConnections = settings.maxConnections,
                    errorMessage = null,
                )
            }
            updateNotification(force = true)

            acquireLocks(keepCpuAwake = settings.keepCpuAwake)

            val tracker = TrafficStatsTracker()
            tracker.reset()
            tracker.start(serviceScope)
            statsTracker = tracker

            if (shareMode == HostShareMode.NATIVE_TETHERING) {
                startNativeTetheringLocked(
                    settings = settings,
                    tracker = tracker,
                    securityPolicy = securityPolicy,
                    effectiveAuthEnabled = effectiveAuthEnabled,
                )
            } else {
                startWifiDirectLocked(
                    settings = settings,
                    tracker = tracker,
                    securityPolicy = securityPolicy,
                    effectiveAuthEnabled = effectiveAuthEnabled,
                )
            }
        } catch (e: CancellationException) {
            teardownComponentsAsync(preserveError = false)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error starting HostRouterService", e)
            failAndStopLocked(e.message ?: "Error al iniciar el servidor proxy o Wi-Fi Direct.")
        }
    }

    private suspend fun startNativeTetheringLocked(
        settings: RouterSettings,
        tracker: TrafficStatsTracker,
        securityPolicy: ProxySecurityPolicy,
        effectiveAuthEnabled: Boolean,
    ) {
        val detected = withContext(Dispatchers.IO) {
            NetworkInterfaceScanner.scanTetheringIps()
        }
        val formattedIps = detected.map { "${it.ipAddress} (${it.name})" }

        val bindResolution = resolveNativeTetheringBind(settings, detected)
        if (bindResolution.errorMessage != null) {
            failAndStopLocked(bindResolution.errorMessage)
            return
        }

        val bindAddress = bindResolution.bindAddress
        val displayIp = bindResolution.displayIp
        val isBoundAll = bindResolution.isBoundAllInterfaces

        val localAddresses = withContext(Dispatchers.IO) {
            NetworkInterfaceScanner.getAllLocalInetAddresses()
        }

        val server = HybridProxyServer(tracker)
        proxyServer = server
        server.start(
            port = settings.proxyPort,
            bindAddress = bindAddress,
            securityPolicy = securityPolicy,
            localAddresses = localAddresses,
        )
        val boundPort = server.boundPort.takeIf { it > 0 } ?: settings.proxyPort

        startStatsCollection(tracker)

        HostStateHolder.update { current ->
            current.copy(
                status = ServiceStatus.RUNNING,
                shareMode = HostShareMode.NATIVE_TETHERING,
                ssid = "",
                passphrase = "",
                ipAddress = displayIp,
                port = boundPort,
                connectedWifiClients = 0,
                localIpAddresses = formattedIps,
                boundAddress = bindAddress,
                isBoundAllInterfaces = isBoundAll,
                isProxyAuthEnabled = effectiveAuthEnabled,
                isAntiSsrfEnabled = settings.blockLocalhostAccess,
                maxConnections = settings.maxConnections,
                errorMessage = null,
            )
        }
        updateNotification(force = true)
        startInterfacePolling(
            boundPort = boundPort,
            bindMode = settings.bindMode,
        )
    }

    private suspend fun startWifiDirectLocked(
        settings: RouterSettings,
        tracker: TrafficStatsTracker,
        securityPolicy: ProxySecurityPolicy,
        effectiveAuthEnabled: Boolean,
    ) {
        val wfdManager = WifiDirectManager(this@HostRouterService, serviceScope)
        wifiDirectManager = wfdManager

        val initialGroupInfo = CompletableDeferred<WifiDirectInfo>()
        var latestGroupInfo: WifiDirectInfo? = null
        var proxyStarted = false
        var startupError: String? = null
        var activeBoundPort = settings.proxyPort

        val started = wfdManager.startGroup(
            settings = settings,
            onGroupUpdated = { info ->
                latestGroupInfo = info
                if (!initialGroupInfo.isCompleted) {
                    initialGroupInfo.complete(info)
                }
                if (proxyStarted) {
                    handleWifiDirectGroupUpdate(
                        info = info,
                        settings = settings,
                        boundPort = activeBoundPort,
                    )
                }
            },
            onError = { errorMsg ->
                if (!proxyStarted) {
                    startupError = errorMsg
                } else {
                    failAndStop(errorMsg)
                }
            },
        )

        if (!started) {
            failAndStopLocked(startupError ?: "No se pudo iniciar el grupo Wi-Fi Direct.")
            return
        }

        val groupInfo = withTimeoutOrNull(WFD_INITIAL_INFO_TIMEOUT_MS) {
            initialGroupInfo.await()
        } ?: latestGroupInfo ?: WifiDirectInfo(
            ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) settings.fullSsid else "",
            passphrase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) settings.passphrase else "",
            ipAddress = WifiDirectInfo.DEFAULT_GROUP_OWNER_IP,
            connectedClients = 0,
        )

        val groupOwnerIp = groupInfo.ipAddress
            .trim()
            .takeIf { ValidationUtils.isStrictIpv4(it) }
            ?: WifiDirectInfo.DEFAULT_GROUP_OWNER_IP

        val bindAddress: String
        val displayIp: String
        val isBoundAll: Boolean

        when (settings.bindMode) {
            BindMode.AUTO_LOCAL -> {
                waitForLocalIpv4(groupOwnerIp, timeoutMs = WFD_INTERFACE_READY_TIMEOUT_MS)
                val resolvedGoIp = latestGroupInfo?.ipAddress
                    ?.trim()
                    ?.takeIf { ValidationUtils.isStrictIpv4(it) }
                    ?: groupOwnerIp
                bindAddress = resolvedGoIp
                displayIp = resolvedGoIp
                isBoundAll = false
            }

            BindMode.SPECIFIC_IP -> {
                val customIp = settings.customBindIp.trim()
                if (customIp == groupOwnerIp) {
                    waitForLocalIpv4(customIp, timeoutMs = WFD_INTERFACE_READY_TIMEOUT_MS)
                }
                val isActive = withContext(Dispatchers.IO) {
                    NetworkInterfaceScanner.isActiveLocalIpv4(customIp)
                }
                if (!isActive) {
                    val detected = withContext(Dispatchers.IO) {
                        NetworkInterfaceScanner.scanTetheringIps()
                    }
                    failAndStopLocked(buildUnavailableIpError(customIp, detected))
                    return
                }
                bindAddress = customIp
                displayIp = customIp
                isBoundAll = false
            }

            BindMode.ALL_INTERFACES -> {
                bindAddress = BIND_ALL_ADDRESS
                displayIp = groupOwnerIp
                isBoundAll = true
            }
        }

        val server = HybridProxyServer(tracker)
        proxyServer = server
        startProxyWithRetry(
            server = server,
            port = settings.proxyPort,
            bindAddress = bindAddress,
            securityPolicy = securityPolicy,
        )

        activeBoundPort = server.boundPort.takeIf { it > 0 } ?: settings.proxyPort
        proxyStarted = true

        startStatsCollection(tracker)

        val currentInfo = latestGroupInfo ?: groupInfo
        val detected = withContext(Dispatchers.IO) {
            NetworkInterfaceScanner.scanTetheringIps()
        }
        val formattedIps = detected.map { "${it.ipAddress} (${it.name})" }
        val finalDisplayIp = if (settings.bindMode == BindMode.SPECIFIC_IP) {
            displayIp
        } else {
            currentInfo.ipAddress.ifBlank { displayIp }
        }

        lastReportedClientCount = currentInfo.connectedClients

        HostStateHolder.update { current ->
            current.copy(
                status = ServiceStatus.RUNNING,
                shareMode = HostShareMode.WIFI_DIRECT,
                ssid = currentInfo.ssid,
                passphrase = currentInfo.passphrase,
                ipAddress = finalDisplayIp,
                port = activeBoundPort,
                connectedWifiClients = currentInfo.connectedClients,
                localIpAddresses = formattedIps,
                boundAddress = bindAddress,
                isBoundAllInterfaces = isBoundAll,
                isProxyAuthEnabled = effectiveAuthEnabled,
                isAntiSsrfEnabled = settings.blockLocalhostAccess,
                maxConnections = settings.maxConnections,
                errorMessage = null,
            )
        }
        updateNotification(force = true)
    }

    private fun handleWifiDirectGroupUpdate(
        info: WifiDirectInfo,
        settings: RouterSettings,
        boundPort: Int,
    ) {
        val previousClients = lastReportedClientCount
        val newClients = info.connectedClients
        lastReportedClientCount = newClients

        HostStateHolder.update { current ->
            val updatedIp = if (settings.bindMode == BindMode.SPECIFIC_IP && current.boundAddress.isNotBlank()) {
                current.boundAddress
            } else {
                info.ipAddress.ifBlank { current.ipAddress }
            }
            current.copy(
                status = ServiceStatus.RUNNING,
                shareMode = HostShareMode.WIFI_DIRECT,
                ssid = info.ssid,
                passphrase = info.passphrase,
                ipAddress = updatedIp,
                port = boundPort,
                connectedWifiClients = newClients,
                errorMessage = null,
            )
        }
        updateNotification(force = true)

        if (previousClients != null && newClients != previousClients) {
            maybeSendClientAlert(
                previousCount = previousClients,
                newCount = newClients,
                ssid = info.ssid,
            )
        }

        serviceScope.launch {
            val detected = withContext(Dispatchers.IO) {
                NetworkInterfaceScanner.scanTetheringIps()
            }
            val formattedIps = detected.map { "${it.ipAddress} (${it.name})" }
            HostStateHolder.update { current ->
                if (current.status != ServiceStatus.RUNNING) return@update current
                current.copy(localIpAddresses = formattedIps)
            }
        }
    }

    private suspend fun startProxyWithRetry(
        server: HybridProxyServer,
        port: Int,
        bindAddress: String,
        securityPolicy: ProxySecurityPolicy,
    ) {
        var lastError: Exception? = null
        val maxAttempts = if (bindAddress == BIND_ALL_ADDRESS) 1 else 4
        for (attempt in 0 until maxAttempts) {
            val localAddresses = withContext(Dispatchers.IO) {
                NetworkInterfaceScanner.getAllLocalInetAddresses()
            }
            try {
                server.start(
                    port = port,
                    bindAddress = bindAddress,
                    securityPolicy = securityPolicy,
                    localAddresses = localAddresses,
                )
                return
            } catch (e: BindException) {
                lastError = e
                if (attempt + 1 < maxAttempts) {
                    delay(BIND_RETRY_DELAY_MS)
                }
            }
        }
        throw lastError ?: BindException("No se pudo enlazar el proxy en $bindAddress:$port")
    }

    private suspend fun waitForLocalIpv4(targetIp: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready = withContext(Dispatchers.IO) {
                NetworkInterfaceScanner.isActiveLocalIpv4(targetIp)
            }
            if (ready) return true
            delay(INTERFACE_READY_POLL_MS)
        }
        return withContext(Dispatchers.IO) {
            NetworkInterfaceScanner.isActiveLocalIpv4(targetIp)
        }
    }

    private data class BindResolution(
        val bindAddress: String = "",
        val displayIp: String = "",
        val isBoundAllInterfaces: Boolean = false,
        val errorMessage: String? = null,
    )

    private suspend fun resolveNativeTetheringBind(
        settings: RouterSettings,
        detected: List<DetectedInterface>,
    ): BindResolution {
        return when (settings.bindMode) {
            BindMode.AUTO_LOCAL -> {
                val primary = detected.firstOrNull()
                if (primary == null) {
                    BindResolution(
                        errorMessage = "No se detectó ninguna interfaz de tethering o red local activa. Activa primero Zona Wi-Fi o Anclaje USB, o cambia a Todas las interfaces (0.0.0.0).",
                    )
                } else {
                    BindResolution(
                        bindAddress = primary.ipAddress,
                        displayIp = primary.ipAddress,
                        isBoundAllInterfaces = false,
                    )
                }
            }

            BindMode.SPECIFIC_IP -> {
                val customIp = settings.customBindIp.trim()
                val isActive = withContext(Dispatchers.IO) {
                    NetworkInterfaceScanner.isActiveLocalIpv4(customIp)
                }
                if (!isActive) {
                    BindResolution(
                        errorMessage = buildUnavailableIpError(customIp, detected),
                    )
                } else {
                    BindResolution(
                        bindAddress = customIp,
                        displayIp = customIp,
                        isBoundAllInterfaces = false,
                    )
                }
            }

            BindMode.ALL_INTERFACES -> {
                BindResolution(
                    bindAddress = BIND_ALL_ADDRESS,
                    displayIp = detected.firstOrNull()?.ipAddress ?: DEFAULT_TETHERING_IP,
                    isBoundAllInterfaces = true,
                )
            }
        }
    }

    private fun buildUnavailableIpError(
        customIp: String,
        detected: List<DetectedInterface>,
    ): String {
        val availableText = if (detected.isEmpty()) {
            "No se detectaron IPs locales activas."
        } else {
            "IPs locales detectadas: ${detected.joinToString(", ") { "${it.ipAddress} (${it.name})" }}."
        }
        return when {
            customIp.isBlank() ->
                "Modo IP específica activo sin una IP configurada. $availableText"

            !ValidationUtils.isStrictIpv4(customIp) ->
                "La IP configurada ($customIp) no es una IPv4 válida. $availableText"

            else ->
                "La IP configurada ($customIp) no está activa en ninguna interfaz local. $availableText"
        }
    }

    private fun startInterfacePolling(
        boundPort: Int,
        bindMode: BindMode,
    ) {
        interfacePollJob?.cancel()
        interfacePollJob = serviceScope.launch {
            while (isActive) {
                delay(INTERFACE_POLL_INTERVAL_MS)
                val state = HostStateHolder.state.value
                if (state.status != ServiceStatus.RUNNING || state.shareMode != HostShareMode.NATIVE_TETHERING) {
                    break
                }
                val detected = withContext(Dispatchers.IO) {
                    NetworkInterfaceScanner.scanTetheringIps()
                }
                val formattedIps = detected.map { "${it.ipAddress} (${it.name})" }
                val updatedDisplayIp = if (bindMode == BindMode.ALL_INTERFACES) {
                    detected.firstOrNull()?.ipAddress ?: DEFAULT_TETHERING_IP
                } else {
                    state.ipAddress
                }
                if (state.ipAddress != updatedDisplayIp || state.localIpAddresses != formattedIps) {
                    HostStateHolder.update { current ->
                        if (current.status != ServiceStatus.RUNNING) return@update current
                        current.copy(
                            ipAddress = updatedDisplayIp,
                            port = boundPort,
                            localIpAddresses = formattedIps,
                        )
                    }
                    updateNotification(force = true)
                }
            }
        }
    }

    private fun startStatsCollection(tracker: TrafficStatsTracker) {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            tracker.snapshot.collectLatest { snapshot ->
                HostStateHolder.update { current ->
                    current.copy(traffic = snapshot)
                }
                updateNotification(force = false)
            }
        }
    }

    private fun failAndStop(message: String) {
        startupJob?.cancel()
        serviceScope.launch {
            lifecycleMutex.withLock {
                failAndStopLocked(message)
            }
        }
    }

    private suspend fun failAndStopLocked(message: String) {
        Log.e(TAG, "HostRouterService error: $message")
        HostStateHolder.update {
            it.copy(
                status = ServiceStatus.ERROR,
                errorMessage = message,
            )
        }
        maybeSendErrorAlert(message)
        stopRouterLocked(preserveError = true)
    }

    private suspend fun stopRouterLocked(preserveError: Boolean) {
        teardownComponentsAsync(preserveError = preserveError)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun teardownComponentsAsync(preserveError: Boolean) {
        statsJob?.cancel()
        statsJob = null
        interfacePollJob?.cancel()
        interfacePollJob = null
        lastReportedClientCount = null

        val wfdToStop = wifiDirectManager
        wifiDirectManager = null
        try {
            wfdToStop?.stopGroup()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping WifiDirectManager", e)
        }

        val serverToStop = proxyServer
        proxyServer = null
        if (serverToStop != null) {
            withContext(Dispatchers.IO) {
                try {
                    serverToStop.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping HybridProxyServer", e)
                }
            }
        }

        val trackerToStop = statsTracker
        statsTracker = null
        try {
            trackerToStop?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TrafficStatsTracker", e)
        }

        releaseLocks()

        if (!preserveError) {
            HostStateHolder.reset()
        }
    }

    private fun teardownComponentsSync(preserveError: Boolean) {
        startupJob?.cancel()
        startupJob = null
        statsJob?.cancel()
        statsJob = null
        interfacePollJob?.cancel()
        interfacePollJob = null
        lastReportedClientCount = null

        try {
            wifiDirectManager?.stopGroup()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping WifiDirectManager", e)
        }
        wifiDirectManager = null

        try {
            proxyServer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping HybridProxyServer", e)
        }
        proxyServer = null

        try {
            statsTracker?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TrafficStatsTracker", e)
        }
        statsTracker = null

        releaseLocks()

        if (!preserveError) {
            HostStateHolder.reset()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks(keepCpuAwake: Boolean) {
        releaseLocks()
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager.createWifiLock(lockMode, "DirectRouter:HostWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire WifiLock", e)
        }

        if (keepCpuAwake) {
            try {
                val powerManager =
                    applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager != null) {
                    wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "DirectRouter:HostWakeLock",
                    ).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to acquire WakeLock", e)
            }
        }
    }

    private fun releaseLocks() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WifiLock", e)
        } finally {
            wifiLock = null
        }

        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock", e)
        } finally {
            wakeLock = null
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alertVibrationPattern = longArrayOf(0L, 180L, 100L, 180L)

        val serviceChannel = NotificationChannel(
            CHANNEL_ID_SERVICE,
            "DirectRouter Host",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Estado del router Wi-Fi Direct y proxy local"
            setShowBadge(false)
        }

        val alertsSoundVibrate = NotificationChannel(
            CHANNEL_ID_ALERTS_SOUND_VIBRATE,
            "Alertas (Sonido y vibración)",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alertas de clientes conectados/desconectados y errores con sonido y vibración"
            setSound(defaultSoundUri, audioAttributes)
            enableVibration(true)
            vibrationPattern = alertVibrationPattern
        }

        val alertsSound = NotificationChannel(
            CHANNEL_ID_ALERTS_SOUND,
            "Alertas (Solo sonido)",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alertas de clientes y errores con sonido"
            setSound(defaultSoundUri, audioAttributes)
            enableVibration(false)
        }

        val alertsVibrate = NotificationChannel(
            CHANNEL_ID_ALERTS_VIBRATE,
            "Alertas (Solo vibración)",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alertas de clientes y errores con vibración"
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = alertVibrationPattern
        }

        val alertsSilent = NotificationChannel(
            CHANNEL_ID_ALERTS_SILENT,
            "Alertas (Silencioso)",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Alertas silenciosas de clientes y errores"
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannels(
            listOf(
                serviceChannel,
                alertsSoundVibrate,
                alertsSound,
                alertsVibrate,
                alertsSilent,
            ),
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "CONNECTED_DEVICE FGS type denied, falling back to SPECIAL_USE", e)
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                    return
                } catch (e2: Exception) {
                    Log.w(TAG, "SPECIAL_USE FGS fallback failed", e2)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Typed startForeground failed, using default", e)
            }
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationUpdateMs < NOTIFICATION_THROTTLE_MS) {
            return
        }
        lastNotificationUpdateMs = now

        val state = HostStateHolder.state.value
        if (state.status != ServiceStatus.RUNNING && state.status != ServiceStatus.STARTING) {
            return
        }

        val settings = currentSettings
        val speedLine = formatSpeedSummary(state.traffic)

        val title: String
        val text: String

        if (settings.notifPrivacyMode) {
            title = if (state.status == ServiceStatus.STARTING) {
                "DirectRouter"
            } else {
                "DirectRouter activo"
            }
            text = when {
                state.status == ServiceStatus.STARTING -> "Iniciando servidor proxy..."
                settings.notifShowLiveSpeed -> "Proxy local activo • $speedLine"
                else -> "Proxy local activo"
            }
        } else {
            title = when {
                state.shareMode == HostShareMode.NATIVE_TETHERING -> "DirectRouter • Tethering Nativo"
                state.ssid.isNotBlank() -> "DirectRouter • ${state.ssid}"
                else -> "DirectRouter Host"
            }
            text = when {
                state.status == ServiceStatus.STARTING -> "Iniciando servidor proxy..."
                settings.notifShowLiveSpeed -> "${state.ipAddress}:${state.port} • $speedLine"
                else -> "${state.ipAddress}:${state.port}"
            }
        }

        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(title, text, settings))
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to update ongoing notification", e)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        settings: RouterSettings = currentSettings,
    ): Notification {
        val openPendingIntent = createOpenAppPendingIntent(requestCode = 0)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_directrouter)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)

        if (settings.notifPrivacyMode) {
            val publicVersion = NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_directrouter)
                .setContentTitle("DirectRouter")
                .setContentText("Servicio activo")
                .setOngoing(true)
                .build()
            builder
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setPublicVersion(publicVersion)
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }

        if (settings.notifShowStopAction) {
            val stopIntent = Intent(this, HostRouterService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Apagar",
                stopPendingIntent,
            )
        }

        return builder.build()
    }

    private fun maybeSendClientAlert(
        previousCount: Int,
        newCount: Int,
        ssid: String,
    ) {
        val settings = currentSettings
        if (!settings.notifClientAlerts) return

        val connected = newCount > previousCount
        val title: String
        val text: String
        if (settings.notifPrivacyMode) {
            title = if (connected) "Nuevo cliente conectado" else "Cliente desconectado"
            text = "Clientes conectados actualmente: $newCount"
        } else {
            title = if (connected) "Cliente conectado" else "Cliente desconectado"
            text = if (connected) {
                val netLabel = ssid.ifBlank { "DirectRouter" }
                "$newCount cliente(s) conectado(s) en $netLabel"
            } else {
                "Quedan $newCount cliente(s) conectado(s)"
            }
        }

        postAlertNotification(
            notificationId = ALERT_CLIENT_NOTIFICATION_ID,
            title = title,
            text = text,
            category = NotificationCompat.CATEGORY_STATUS,
            settings = settings,
        )
    }

    private fun maybeSendErrorAlert(errorMessage: String) {
        val settings = currentSettings
        if (!settings.notifClientAlerts) return

        val text = if (settings.notifPrivacyMode) {
            "El router se detuvo debido a un error. Abre la app para ver detalles."
        } else {
            errorMessage
        }

        postAlertNotification(
            notificationId = ALERT_ERROR_NOTIFICATION_ID,
            title = "Error en DirectRouter",
            text = text,
            category = NotificationCompat.CATEGORY_ERROR,
            settings = settings,
        )
    }

    private fun postAlertNotification(
        notificationId: Int,
        title: String,
        text: String,
        category: String,
        settings: RouterSettings,
    ) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channelId = resolveAlertChannelId(
            sound = settings.notifAlertSound,
            vibrate = settings.notifAlertVibrate,
        )
        val openPendingIntent = createOpenAppPendingIntent(requestCode = notificationId)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_directrouter)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(category)
            .setContentIntent(openPendingIntent)

        if (settings.notifPrivacyMode) {
            val publicVersion = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_stat_directrouter)
                .setContentTitle("DirectRouter")
                .setContentText("Nueva alerta del servicio")
                .build()
            builder
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setPublicVersion(publicVersion)
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }

        try {
            manager.notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted for alert notification", e)
        }
    }

    private fun createOpenAppPendingIntent(requestCode: Int): PendingIntent {
        val openIntent = Intent().apply {
            setClassName(this@HostRouterService, "com.directrouter.app.MainActivity")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            requestCode,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun formatSpeedSummary(snapshot: TrafficSnapshot): String {
        val down = formatRate(snapshot.downloadBytesPerSec)
        val up = formatRate(snapshot.uploadBytesPerSec)
        return "↓ $down  ↑ $up"
    }

    private fun formatRate(bytesPerSec: Long): String {
        val safe = bytesPerSec.coerceAtLeast(0L)
        return when {
            safe >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB/s", safe / (1024.0 * 1024.0))
            safe >= 1024L -> String.format(Locale.US, "%.0f KB/s", safe / 1024.0)
            else -> "$safe B/s"
        }
    }

    companion object {
        private const val TAG = "HostRouterService"

        const val CHANNEL_ID_SERVICE = "directrouter_host"
        const val CHANNEL_ID_ALERTS_SOUND_VIBRATE = "directrouter_alerts_sound_vibrate"
        const val CHANNEL_ID_ALERTS_SOUND = "directrouter_alerts_sound"
        const val CHANNEL_ID_ALERTS_VIBRATE = "directrouter_alerts_vibrate"
        const val CHANNEL_ID_ALERTS_SILENT = "directrouter_alerts_silent"

        private const val NOTIFICATION_ID = 1001
        private const val ALERT_CLIENT_NOTIFICATION_ID = 1003
        private const val ALERT_ERROR_NOTIFICATION_ID = 1004
        private const val NOTIFICATION_THROTTLE_MS = 2000L
        private const val INTERFACE_POLL_INTERVAL_MS = 3000L
        private const val WFD_INITIAL_INFO_TIMEOUT_MS = 4000L
        private const val WFD_INTERFACE_READY_TIMEOUT_MS = 3500L
        private const val INTERFACE_READY_POLL_MS = 150L
        private const val BIND_RETRY_DELAY_MS = 350L
        private const val RESTART_PROXY_COOLDOWN_MS = 100L
        private const val RESTART_WFD_COOLDOWN_MS = 300L
        private const val DEFAULT_TETHERING_IP = "192.168.43.1"
        private const val BIND_ALL_ADDRESS = "0.0.0.0"

        const val ACTION_START = "com.directrouter.app.action.HOST_START"
        const val ACTION_STOP = "com.directrouter.app.action.HOST_STOP"
        const val ACTION_RESTART = "com.directrouter.app.action.RESTART_HOST"

        fun resolveAlertChannelId(sound: Boolean, vibrate: Boolean): String = when {
            sound && vibrate -> CHANNEL_ID_ALERTS_SOUND_VIBRATE
            sound -> CHANNEL_ID_ALERTS_SOUND
            vibrate -> CHANNEL_ID_ALERTS_VIBRATE
            else -> CHANNEL_ID_ALERTS_SILENT
        }

        fun start(context: Context) {
            val intent = Intent(context, HostRouterService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HostRouterService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: IllegalStateException) {
                context.stopService(Intent(context, HostRouterService::class.java))
            }
        }

        fun restart(context: Context) {
            val intent = Intent(context, HostRouterService::class.java).apply {
                action = ACTION_RESTART
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
