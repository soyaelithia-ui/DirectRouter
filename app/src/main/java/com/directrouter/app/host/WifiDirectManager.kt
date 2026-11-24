package com.directrouter.app.host

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.directrouter.app.data.RouterSettings
import com.directrouter.app.data.WifiBand
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class WifiDirectInfo(
    val ssid: String,
    val passphrase: String,
    val ipAddress: String = DEFAULT_GROUP_OWNER_IP,
    val connectedClients: Int = 0,
) {
    companion object {
        const val DEFAULT_GROUP_OWNER_IP = "192.168.49.1"
    }
}

class WifiDirectManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val wifiP2pManager: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var activeSettings: RouterSettings? = null
    private var onGroupUpdatedCallback: ((WifiDirectInfo) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    private var lastKnownSsid: String = ""
    private var lastKnownPassphrase: String = ""
    private var lastKnownIp: String = WifiDirectInfo.DEFAULT_GROUP_OWNER_IP
    private var lastKnownClients: Int = 0

    private var queryJob: Job? = null
    private val isActive = AtomicBoolean(false)

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isActive.get()) return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                    )
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.w(TAG, "Wi-Fi P2P state disabled while active")
                        onErrorCallback?.invoke("Wi-Fi Direct está desactivado. Activa el Wi-Fi del teléfono.")
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    scheduleInfoRefresh(retries = 4, initialDelayMs = 150L)
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    Log.d(TAG, "Wi-Fi P2P local device state updated")
                }
            }
        }
    }

    fun hasRequiredPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    suspend fun startGroup(
        settings: RouterSettings,
        onGroupUpdated: (WifiDirectInfo) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        val manager = wifiP2pManager
        if (manager == null) {
            onError("Este dispositivo no soporta Wi-Fi Direct (WifiP2pManager no disponible).")
            return false
        }

        if (!hasRequiredPermission()) {
            val missing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "Dispositivos cercanos (NEARBY_WIFI_DEVICES)"
            } else {
                "Ubicación precisa (ACCESS_FINE_LOCATION)"
            }
            onError("Falta el permiso requerido: $missing.")
            return false
        }

        if (wifiManager != null && !wifiManager.isWifiEnabled) {
            onError("El Wi-Fi está apagado. Enciende el Wi-Fi para iniciar Wi-Fi Direct.")
            return false
        }

        activeSettings = settings
        onGroupUpdatedCallback = onGroupUpdated
        onErrorCallback = onError
        lastKnownSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) settings.fullSsid else ""
        lastKnownPassphrase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) settings.passphrase else ""
        lastKnownIp = WifiDirectInfo.DEFAULT_GROUP_OWNER_IP
        lastKnownClients = 0

        isActive.set(true)
        registerReceiverIfNeeded()

        val ch = ensureChannel(manager)
        if (ch == null) {
            isActive.set(false)
            unregisterReceiverSafely()
            onError("No se pudo inicializar el canal Wi-Fi Direct.")
            return false
        }

        // Pre-clean any stale group to avoid WifiP2pManager.BUSY on quick restarts.
        val hadRemovedGroup = removeGroupSuspend(manager, ch)
        if (hadRemovedGroup) {
            delay(350L)
        }

        var result = createGroupAttempt(manager, ch, settings, forceAutoBand = false)

        // Retry once on BUSY or if 5GHz band is unsupported by hardware.
        if (result == WifiP2pManager.BUSY) {
            Log.w(TAG, "createGroup returned BUSY, retrying after delay...")
            removeGroupSuspend(manager, ch)
            delay(750L)
            result = createGroupAttempt(manager, ch, settings, forceAutoBand = false)
        } else if (result != RESULT_SUCCESS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            settings.wifiBand != WifiBand.AUTO
        ) {
            Log.w(TAG, "createGroup failed with band=${settings.wifiBand} (code=$result), retrying with AUTO band")
            delay(350L)
            result = createGroupAttempt(manager, ch, settings, forceAutoBand = true)
        }

        if (result != RESULT_SUCCESS) {
            isActive.set(false)
            val reason = formatP2pError(result)
            onError("No se pudo crear el grupo Wi-Fi Direct ($reason).")
            return false
        }

        // Emit immediate placeholder on API 29+ while querying actual system group & GO IP.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            onGroupUpdated(
                WifiDirectInfo(
                    ssid = settings.fullSsid,
                    passphrase = settings.passphrase,
                    ipAddress = WifiDirectInfo.DEFAULT_GROUP_OWNER_IP,
                    connectedClients = 0,
                ),
            )
        }

        scheduleInfoRefresh(retries = 10, initialDelayMs = 250L)
        return true
    }

    fun stopGroup() {
        isActive.set(false)
        queryJob?.cancel()
        queryJob = null
        unregisterReceiverSafely()

        val manager = wifiP2pManager
        val ch = channel
        channel = null

        if (manager != null && ch != null) {
            val closed = AtomicBoolean(false)
            val closeOnce = {
                if (closed.compareAndSet(false, true)) {
                    closeChannelSafely(ch)
                }
            }
            try {
                manager.removeGroup(
                    ch,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.d(TAG, "Wi-Fi Direct group removed cleanly")
                            closeOnce()
                        }

                        override fun onFailure(reason: Int) {
                            Log.d(TAG, "removeGroup on teardown returned reason=$reason")
                            closeOnce()
                        }
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "Exception removing Wi-Fi Direct group", e)
                closeOnce()
            }
            scope.launch {
                delay(1000L)
                closeOnce()
            }
        } else if (ch != null) {
            closeChannelSafely(ch)
        }

        onGroupUpdatedCallback = null
        onErrorCallback = null
    }

    private fun ensureChannel(manager: WifiP2pManager): WifiP2pManager.Channel? {
        channel?.let { return it }
        val initialized = manager.initialize(
            appContext,
            Looper.getMainLooper(),
        ) {
            Log.w(TAG, "WifiP2pManager channel disconnected")
            channel = null
        }
        channel = initialized
        return initialized
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            appContext,
            p2pReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterReceiverSafely() {
        if (!receiverRegistered) return
        try {
            appContext.unregisterReceiver(p2pReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered
        } finally {
            receiverRegistered = false
        }
    }

    private suspend fun removeGroupSuspend(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
    ): Boolean {
        return withTimeoutOrNull(2500L) {
            suspendCancellableCoroutine { cont ->
                try {
                    manager.removeGroup(
                        ch,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onFailure(reason: Int) {
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "removeGroup pre-cleanup threw", e)
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false
    }

    @SuppressLint("MissingPermission")
    private suspend fun createGroupAttempt(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        settings: RouterSettings,
        forceAutoBand: Boolean,
    ): Int {
        if (!hasRequiredPermission()) return WifiP2pManager.ERROR

        return withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine { cont ->
                val listener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (cont.isActive) cont.resume(RESULT_SUCCESS)
                    }

                    override fun onFailure(reason: Int) {
                        if (cont.isActive) cont.resume(reason)
                    }
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val band = if (forceAutoBand) {
                            WifiP2pConfig.GROUP_OWNER_BAND_AUTO
                        } else {
                            when (settings.wifiBand) {
                                WifiBand.AUTO -> WifiP2pConfig.GROUP_OWNER_BAND_AUTO
                                WifiBand.BAND_2GHZ -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
                                WifiBand.BAND_5GHZ -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                            }
                        }
                        val config = WifiP2pConfig.Builder()
                            .setNetworkName(settings.fullSsid)
                            .setPassphrase(settings.passphrase)
                            .setGroupOperatingBand(band)
                            .enablePersistentMode(true)
                            .build()
                        manager.createGroup(ch, config, listener)
                    } else {
                        manager.createGroup(ch, listener)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException calling createGroup", e)
                    if (cont.isActive) cont.resume(WifiP2pManager.ERROR)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Invalid WifiP2pConfig parameters", e)
                    if (cont.isActive) cont.resume(WifiP2pManager.ERROR)
                }
            }
        } ?: WifiP2pManager.BUSY
    }

    private fun scheduleInfoRefresh(retries: Int, initialDelayMs: Long) {
        queryJob?.cancel()
        queryJob = scope.launch {
            delay(initialDelayMs)
            repeat(retries) { attempt ->
                if (!isActive.get()) return@launch
                val populated = queryGroupAndConnectionInfo()
                if (populated) return@launch
                delay(400L + attempt * 100L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun queryGroupAndConnectionInfo(): Boolean {
        val manager = wifiP2pManager ?: return false
        val ch = channel ?: return false
        if (!hasRequiredPermission() || !isActive.get()) return false

        val group: WifiP2pGroup? = withTimeoutOrNull(2000L) {
            suspendCancellableCoroutine { cont ->
                try {
                    manager.requestGroupInfo(ch) { g ->
                        if (cont.isActive) cont.resume(g)
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "requestGroupInfo SecurityException", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        val info: WifiP2pInfo? = withTimeoutOrNull(2000L) {
            suspendCancellableCoroutine { cont ->
                try {
                    manager.requestConnectionInfo(ch) { connInfo ->
                        if (cont.isActive) cont.resume(connInfo)
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "requestConnectionInfo SecurityException", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        val ssid = group?.networkName?.takeIf { it.isNotBlank() }
            ?: lastKnownSsid.takeIf { it.isNotBlank() }
            ?: activeSettings?.fullSsid.orEmpty()
        val passphrase = group?.passphrase?.takeIf { it.isNotBlank() }
            ?: lastKnownPassphrase.takeIf { it.isNotBlank() }
            ?: activeSettings?.passphrase.orEmpty()
        val ip = info?.groupOwnerAddress?.hostAddress?.takeIf { it.isNotBlank() }
            ?: lastKnownIp.ifBlank { WifiDirectInfo.DEFAULT_GROUP_OWNER_IP }
        val clients = group?.clientList?.size ?: lastKnownClients

        if (ssid.isNotBlank()) lastKnownSsid = ssid
        if (passphrase.isNotBlank()) lastKnownPassphrase = passphrase
        if (ip.isNotBlank()) lastKnownIp = ip
        lastKnownClients = clients

        if (ssid.isNotBlank() && passphrase.isNotBlank()) {
            onGroupUpdatedCallback?.invoke(
                WifiDirectInfo(
                    ssid = ssid,
                    passphrase = passphrase,
                    ipAddress = ip,
                    connectedClients = clients,
                ),
            )
            return group != null && !group.networkName.isNullOrBlank()
        }
        return false
    }

    private fun closeChannelSafely(ch: WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                ch.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing WifiP2pManager channel", e)
            }
        }
    }

    private fun formatP2pError(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct no soportado"
        WifiP2pManager.BUSY -> "Sistema Wi-Fi ocupado, intenta de nuevo"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "Sin solicitudes de servicio"
        else -> "Error interno ($reason). Verifica que Wi-Fi y Ubicación estén activos"
    }

    companion object {
        private const val TAG = "WifiDirectManager"
        private const val RESULT_SUCCESS = -1
    }
}
