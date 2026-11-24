package com.directrouter.app.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.directrouter.app.DirectRouterApp
import com.directrouter.app.host.ServiceStatus
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DirectVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var connectivityManager: ConnectivityManager? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var activeWifiNetwork: Network? = null

    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnelThread: Thread? = null
    private var connectJob: Job? = null
    private var statsJob: Job? = null

    private val stopping = AtomicBoolean(false)
    private var lastNotificationUpdateMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnectAndStop(preserveError = false)
                return START_NOT_STICKY
            }

            ACTION_CONNECT, null -> {
                startForegroundCompat(
                    buildNotification(
                        title = "DirectRouter VPN",
                        text = "Conectando túnel VPN...",
                    ),
                )
                val requestedIp = intent?.getStringExtra(EXTRA_HOST_IP)
                val requestedPort = intent?.getIntExtra(EXTRA_HOST_PORT, -1) ?: -1
                startVpn(requestedIp, requestedPort)
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked by system/user")
        disconnectAndStop(preserveError = false)
        super.onRevoke()
    }

    override fun onDestroy() {
        val preserveError = ClientVpnStateHolder.state.value.status == ServiceStatus.ERROR
        teardownVpn(preserveError = preserveError)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startVpn(requestedIp: String?, requestedPort: Int) {
        val currentStatus = ClientVpnStateHolder.state.value.status
        if (currentStatus == ServiceStatus.STARTING || currentStatus == ServiceStatus.RUNNING) {
            return
        }

        stopping.set(false)
        connectJob?.cancel()
        connectJob = serviceScope.launch {
            try {
                val app = application as DirectRouterApp
                val savedSettings = app.settingsRepository.getCurrentSettings()
                val hostIp = requestedIp?.trim()?.takeIf { it.isNotEmpty() }
                    ?: savedSettings.clientHostIp.trim().ifEmpty { DEFAULT_HOST_IP }
                val hostPort = if (requestedPort in 1..65535) {
                    requestedPort
                } else {
                    savedSettings.clientHostPort
                }

                val initialWifi = findActiveWifiNetwork()
                activeWifiNetwork = initialWifi
                val initialWifiLabel = describeNetwork(initialWifi)

                ClientVpnStateHolder.update {
                    ClientVpnState(
                        status = ServiceStatus.STARTING,
                        hostIp = hostIp,
                        hostPort = hostPort,
                        wifiConnected = initialWifi != null,
                        wifiNetworkLabel = initialWifiLabel,
                        errorMessage = null,
                    )
                }

                registerWifiNetworkCallback()

                // Pin outbound sockets from this app (hev-socks5-tunnel) to Wi-Fi Direct
                // even when Android considers the Wi-Fi Direct network "without internet".
                if (initialWifi != null) {
                    bindProcessToWifi(initialWifi)
                }

                val builder = Builder()
                    .setSession("DirectRouter VPN")
                    .setMtu(TUN_MTU)
                    .addAddress(TUN_IPV4_ADDRESS, 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(DNS_PRIMARY)
                    .addDnsServer(DNS_SECONDARY)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                // Prevent routing loop: hev-socks5-tunnel runs inside our package.
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Unable to exclude self package from VPN", e)
                }

                if (initialWifi != null) {
                    builder.setUnderlyingNetworks(arrayOf(initialWifi))
                }

                val pfd = builder.establish()
                if (pfd == null) {
                    failAndStop("Permiso VPN no concedido o interfaz TUN no disponible.")
                    return@launch
                }
                tunInterface = pfd

                if (initialWifi != null) {
                    try {
                        setUnderlyingNetworks(arrayOf(initialWifi))
                    } catch (e: Exception) {
                        Log.w(TAG, "setUnderlyingNetworks failed after establish", e)
                    }
                }

                val configFile = writeHevConfig(hostIp, hostPort)
                val tunFd = pfd.fd

                val startedAt = System.currentTimeMillis()
                ClientVpnStateHolder.update { current ->
                    current.copy(
                        status = ServiceStatus.RUNNING,
                        hostIp = hostIp,
                        hostPort = hostPort,
                        wifiConnected = activeWifiNetwork != null,
                        wifiNetworkLabel = describeNetwork(activeWifiNetwork),
                        startedAtEpochMs = startedAt,
                        errorMessage = null,
                    )
                }
                updateNotification(force = true)

                val worker = Thread(
                    {
                        val ok = HevTunnelBridge.start(configFile.absolutePath, tunFd)
                        if (!ok && !stopping.get()) {
                            serviceScope.launch {
                                if (!stopping.get()) {
                                    failAndStop("No se pudo iniciar el motor nativo hev-socks5-tunnel.")
                                }
                            }
                        }
                    },
                    "hev-socks5-tunnel",
                ).apply {
                    isDaemon = true
                    start()
                }
                tunnelThread = worker

                startStatsPolling()
            } catch (e: Exception) {
                Log.e(TAG, "Error establishing DirectVpnService", e)
                failAndStop(e.message ?: "Error al iniciar el túnel VPN.")
            }
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive && !stopping.get()) {
                val stats = HevTunnelBridge.getStats()
                val txPackets = stats.getOrElse(0) { 0L }
                val txBytes = stats.getOrElse(1) { 0L }
                val rxPackets = stats.getOrElse(2) { 0L }
                val rxBytes = stats.getOrElse(3) { 0L }

                ClientVpnStateHolder.update { current ->
                    current.copy(
                        txPackets = txPackets,
                        txBytes = txBytes,
                        rxPackets = rxPackets,
                        rxBytes = rxBytes,
                    )
                }
                updateNotification(force = false)
                delay(1000L)
            }
        }
    }

    private fun writeHevConfig(hostIp: String, hostPort: Int): File {
        val sanitizedIp = hostIp
            .replace("\r", "")
            .replace("\n", "")
            .replace("'", "")
            .replace("\"", "")
            .trim()
        val yaml = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $TUN_MTU")
            appendLine("  ipv4: $TUN_IPV4_ADDRESS")
            appendLine("socks5:")
            appendLine("  port: $hostPort")
            appendLine("  address: $sanitizedIp")
            appendLine("  udp: 'udp'")
            appendLine("misc:")
            appendLine("  task-stack-size: 81920")
            appendLine("  log-level: warn")
        }
        val configFile = File(filesDir, HEV_CONFIG_FILENAME)
        configFile.writeText(yaml)
        return configFile
    }

    @Suppress("DEPRECATION")
    private fun findActiveWifiNetwork(): Network? {
        val cm = connectivityManager ?: return null
        return try {
            cm.allNetworks.firstOrNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying active Wi-Fi networks", e)
            null
        }
    }

    private fun registerWifiNetworkCallback() {
        val cm = connectivityManager ?: return
        if (wifiNetworkCallback != null) return

        // Explicitly remove NET_CAPABILITY_INTERNET so Android notifies us of Wi-Fi Direct
        // networks that lack direct internet access / NET_CAPABILITY_VALIDATED.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

                activeWifiNetwork = network
                bindProcessToWifi(network)
                if (tunInterface != null) {
                    try {
                        setUnderlyingNetworks(arrayOf(network))
                    } catch (e: Exception) {
                        Log.w(TAG, "setUnderlyingNetworks(wifi) failed", e)
                    }
                }
                val label = describeNetwork(network)
                ClientVpnStateHolder.update { current ->
                    current.copy(
                        wifiConnected = true,
                        wifiNetworkLabel = label,
                    )
                }
            }

            override fun onLost(network: Network) {
                if (activeWifiNetwork != network) return
                val fallbackWifi = findActiveWifiNetwork()
                activeWifiNetwork = fallbackWifi
                bindProcessToWifi(fallbackWifi)
                if (tunInterface != null) {
                    try {
                        setUnderlyingNetworks(fallbackWifi?.let { arrayOf(it) })
                    } catch (e: Exception) {
                        Log.w(TAG, "setUnderlyingNetworks onLost update failed", e)
                    }
                }
                ClientVpnStateHolder.update { current ->
                    current.copy(
                        wifiConnected = fallbackWifi != null,
                        wifiNetworkLabel = describeNetwork(fallbackWifi),
                    )
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            wifiNetworkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register Wi-Fi NetworkCallback", e)
        }
    }

    private fun unregisterWifiNetworkCallback() {
        val cm = connectivityManager
        val cb = wifiNetworkCallback
        wifiNetworkCallback = null
        if (cm != null && cb != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister Wi-Fi NetworkCallback", e)
            }
        }
    }

    private fun bindProcessToWifi(network: Network?) {
        try {
            connectivityManager?.bindProcessToNetwork(network)
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed", e)
        }
    }

    private fun describeNetwork(network: Network?): String {
        if (network == null) return ""
        val cm = connectivityManager ?: return "Wi-Fi"
        return try {
            val linkProps = cm.getLinkProperties(network)
            val iface = linkProps?.interfaceName?.takeIf { it.isNotBlank() }
            val ipv4 = linkProps?.linkAddresses
                ?.mapNotNull { it.address?.hostAddress }
                ?.firstOrNull { !it.contains(':') }
            when {
                iface != null && ipv4 != null -> "Wi-Fi ($iface • $ipv4)"
                ipv4 != null -> "Wi-Fi ($ipv4)"
                iface != null -> "Wi-Fi ($iface)"
                else -> "Wi-Fi"
            }
        } catch (_: Exception) {
            "Wi-Fi"
        }
    }

    private fun failAndStop(message: String) {
        Log.e(TAG, "DirectVpnService error: $message")
        ClientVpnStateHolder.update { current ->
            current.copy(
                status = ServiceStatus.ERROR,
                errorMessage = message,
            )
        }
        disconnectAndStop(preserveError = true)
    }

    private fun disconnectAndStop(preserveError: Boolean) {
        teardownVpn(preserveError = preserveError)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardownVpn(preserveError: Boolean) {
        stopping.set(true)
        connectJob?.cancel()
        connectJob = null
        statsJob?.cancel()
        statsJob = null

        try {
            if (HevTunnelBridge.isRunning() || tunnelThread != null) {
                HevTunnelBridge.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping HevTunnelBridge", e)
        }
        tunnelThread = null

        try {
            tunInterface?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing TUN interface", e)
        }
        tunInterface = null

        unregisterWifiNetworkCallback()
        bindProcessToWifi(null)
        activeWifiNetwork = null

        if (!preserveError) {
            ClientVpnStateHolder.reset()
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DirectRouter VPN",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Estado del túnel VPN Cliente (hev-socks5-tunnel)"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
                return
            } catch (e: Exception) {
                Log.w(TAG, "SPECIAL_USE FGS startForeground failed, falling back", e)
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

        val state = ClientVpnStateHolder.state.value
        if (state.status != ServiceStatus.RUNNING && state.status != ServiceStatus.STARTING) {
            return
        }

        val title = "DirectRouter VPN • ${state.hostIp}:${state.hostPort}"
        val text = "↓ ${formatBytesCompact(state.rxBytes)}  ↑ ${formatBytesCompact(state.txBytes)}"
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent().apply {
            setClassName(this@DirectVpnService, "com.directrouter.app.MainActivity")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = Intent(this, DirectVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            11,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Desconectar",
                disconnectPendingIntent,
            )
            .build()
    }

    private fun formatBytesCompact(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe >= 1024L * 1024L * 1024L ->
                String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
            safe >= 1024L * 1024L ->
                String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
            safe >= 1024L ->
                String.format(Locale.US, "%.0f KB", safe / 1024.0)
            else -> "$safe B"
        }
    }

    companion object {
        private const val TAG = "DirectVpnService"
        private const val CHANNEL_ID = "directrouter_vpn"
        private const val NOTIFICATION_ID = 1002
        private const val NOTIFICATION_THROTTLE_MS = 2000L

        private const val DEFAULT_HOST_IP = "192.168.49.1"
        private const val TUN_MTU = 8500
        private const val TUN_IPV4_ADDRESS = "198.18.0.1"
        private const val DNS_PRIMARY = "1.1.1.1"
        private const val DNS_SECONDARY = "8.8.8.8"
        private const val HEV_CONFIG_FILENAME = "hev_tunnel.yml"

        const val ACTION_CONNECT = "com.directrouter.app.action.VPN_CONNECT"
        const val ACTION_DISCONNECT = "com.directrouter.app.action.VPN_DISCONNECT"
        const val EXTRA_HOST_IP = "extra_host_ip"
        const val EXTRA_HOST_PORT = "extra_host_port"

        fun connect(context: Context, hostIp: String, hostPort: Int) {
            val intent = Intent(context, DirectVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_HOST_IP, hostIp)
                putExtra(EXTRA_HOST_PORT, hostPort)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, DirectVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            try {
                context.startService(intent)
            } catch (_: IllegalStateException) {
                context.stopService(Intent(context, DirectVpnService::class.java))
            }
        }
    }
}
