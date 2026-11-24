package com.directrouter.app.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.directrouter.app.DirectRouterApp
import com.directrouter.app.proxy.HybridProxyServer
import com.directrouter.app.proxy.TrafficSnapshot
import com.directrouter.app.proxy.TrafficStatsTracker
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HostRouterService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var startupJob: Job? = null
    private var statsJob: Job? = null

    private var statsTracker: TrafficStatsTracker? = null
    private var proxyServer: HybridProxyServer? = null
    private var wifiDirectManager: WifiDirectManager? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastNotificationUpdateMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRouter(preserveError = false)
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                startForegroundCompat(
                    buildNotification(
                        title = "DirectRouter Host",
                        text = "Iniciando router Wi-Fi Direct...",
                    ),
                )
                startRouter()
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        val currentStatus = HostStateHolder.state.value.status
        val preserveError = currentStatus == ServiceStatus.ERROR
        teardownComponents(preserveError = preserveError)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startRouter() {
        val currentStatus = HostStateHolder.state.value.status
        if (currentStatus == ServiceStatus.STARTING || currentStatus == ServiceStatus.RUNNING) {
            return
        }

        startupJob?.cancel()
        startupJob = serviceScope.launch {
            try {
                HostStateHolder.update {
                    HostServiceState(
                        status = ServiceStatus.STARTING,
                        errorMessage = null,
                    )
                }

                val app = application as DirectRouterApp
                val settings = app.settingsRepository.getCurrentSettings()

                HostStateHolder.update {
                    it.copy(
                        status = ServiceStatus.STARTING,
                        ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) settings.fullSsid else "",
                        passphrase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) settings.passphrase else "",
                        ipAddress = WifiDirectInfo.DEFAULT_GROUP_OWNER_IP,
                        port = settings.proxyPort,
                        errorMessage = null,
                    )
                }

                acquireLocks(keepCpuAwake = settings.keepCpuAwake)

                val tracker = TrafficStatsTracker()
                tracker.reset()
                tracker.start(serviceScope)
                statsTracker = tracker

                val server = HybridProxyServer(tracker)
                proxyServer = server
                server.start(port = settings.proxyPort, bindAddress = "0.0.0.0")
                val boundPort = server.boundPort.takeIf { it > 0 } ?: settings.proxyPort

                HostStateHolder.update { it.copy(port = boundPort) }

                startStatsCollection(tracker)

                val wfdManager = WifiDirectManager(this@HostRouterService, serviceScope)
                wifiDirectManager = wfdManager

                val started = wfdManager.startGroup(
                    settings = settings,
                    onGroupUpdated = { info ->
                        HostStateHolder.update { current ->
                            current.copy(
                                status = ServiceStatus.RUNNING,
                                ssid = info.ssid,
                                passphrase = info.passphrase,
                                ipAddress = info.ipAddress,
                                port = boundPort,
                                connectedWifiClients = info.connectedClients,
                                errorMessage = null,
                            )
                        }
                        updateNotification(force = true)
                    },
                    onError = { errorMsg ->
                        failAndStop(errorMsg)
                    },
                )

                if (!started && HostStateHolder.state.value.status != ServiceStatus.ERROR) {
                    failAndStop("No se pudo iniciar el grupo Wi-Fi Direct.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting HostRouterService", e)
                failAndStop(e.message ?: "Error al iniciar el servidor proxy o Wi-Fi Direct.")
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
        Log.e(TAG, "HostRouterService error: $message")
        HostStateHolder.update {
            it.copy(
                status = ServiceStatus.ERROR,
                errorMessage = message,
            )
        }
        stopRouter(preserveError = true)
    }

    private fun stopRouter(preserveError: Boolean) {
        teardownComponents(preserveError = preserveError)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardownComponents(preserveError: Boolean) {
        startupJob?.cancel()
        startupJob = null
        statsJob?.cancel()
        statsJob = null

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

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DirectRouter Host",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Estado del router Wi-Fi Direct y proxy local"
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

        val title = if (state.ssid.isNotBlank()) {
            "DirectRouter • ${state.ssid}"
        } else {
            "DirectRouter Host"
        }
        val speedLine = formatSpeedSummary(state.traffic)
        val text = "${state.ipAddress}:${state.port} • $speedLine"

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent().apply {
            setClassName(this@HostRouterService, "com.directrouter.app.MainActivity")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, HostRouterService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Apagar",
                stopPendingIntent,
            )
            .build()
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
        private const val CHANNEL_ID = "directrouter_host"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_THROTTLE_MS = 2000L

        const val ACTION_START = "com.directrouter.app.action.HOST_START"
        const val ACTION_STOP = "com.directrouter.app.action.HOST_STOP"

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
    }
}
