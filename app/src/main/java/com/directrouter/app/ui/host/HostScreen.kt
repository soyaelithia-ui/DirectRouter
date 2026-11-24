package com.directrouter.app.ui.host

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.directrouter.app.data.HostShareMode
import com.directrouter.app.data.RouterSettings
import com.directrouter.app.data.ValidationUtils
import com.directrouter.app.host.HostServiceState
import com.directrouter.app.host.ServiceStatus
import com.directrouter.app.ui.theme.RouterStatusColors
import com.directrouter.app.ui.theme.tr
import com.directrouter.app.ui.util.Formatters
import com.directrouter.app.ui.util.QrCodeGenerator
import kotlinx.coroutines.delay

internal fun extractIpFromInterfaceEntry(entry: String): String {
    return entry.trim().substringBefore(" ").trim()
}

private fun openTetheringSettings(context: Context) {
    val opened = runCatching {
        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}

private data class HostEnvironmentCheck(
    val missingWifiDirectPermission: Boolean,
    val missingNotificationPermission: Boolean,
    val wifiEnabled: Boolean,
    val locationServicesEnabled: Boolean,
) {
    val needsLocationServices: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !locationServicesEnabled

    fun hasAnyIssue(shareMode: HostShareMode): Boolean {
        return when (shareMode) {
            HostShareMode.WIFI_DIRECT ->
                missingWifiDirectPermission ||
                    missingNotificationPermission ||
                    !wifiEnabled ||
                    needsLocationServices

            HostShareMode.NATIVE_TETHERING ->
                missingNotificationPermission
        }
    }
}

private fun inspectHostEnvironment(context: Context): HostEnvironmentCheck {
    val missingWifiDirect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ) != PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) != PackageManager.PERMISSION_GRANTED
    }

    val missingNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    } else {
        false
    }

    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val wifiEnabled = runCatching { wifiManager?.isWifiEnabled ?: true }.getOrDefault(true)

    val locationEnabled = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager?.isLocationEnabled ?: true
            } else {
                @Suppress("DEPRECATION")
                (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) ||
                    (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true)
            }
        }.getOrDefault(true)
    } else {
        true
    }

    return HostEnvironmentCheck(
        missingWifiDirectPermission = missingWifiDirect,
        missingNotificationPermission = missingNotifications,
        wifiEnabled = wifiEnabled,
        locationServicesEnabled = locationEnabled,
    )
}

private fun requiredRuntimePermissions(shareMode: HostShareMode): Array<String> {
    return when (shareMode) {
        HostShareMode.NATIVE_TETHERING -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
        }

        HostShareMode.WIFI_DIRECT -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }
}

@Composable
fun HostScreen(
    hostState: HostServiceState,
    settings: RouterSettings,
    onStartRouter: () -> Unit,
    onStopRouter: () -> Unit,
    onSelectShareMode: (HostShareMode) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var envCheck by remember { mutableStateOf(inspectHostEnvironment(context)) }
    var pendingStartAfterPermissions by remember { mutableStateOf(false) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showQrDialog by rememberSaveable { mutableStateOf(false) }
    var guideExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedTetherIp by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                envCheck = inspectHostEnvironment(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val updated = inspectHostEnvironment(context)
        envCheck = updated
        if (pendingStartAfterPermissions) {
            pendingStartAfterPermissions = false
            if (!updated.missingWifiDirectPermission) {
                onStartRouter()
            } else {
                onShowMessage(
                    tr(
                        "Se necesita permiso de Wi-Fi cercano para encender el router.",
                        "Nearby Wi-Fi permission is required to start the router.",
                    ),
                )
            }
        }
    }

    val isRunning = hostState.status == ServiceStatus.RUNNING
    val isStarting = hostState.status == ServiceStatus.STARTING
    val activeShareMode = if (isRunning || isStarting) hostState.shareMode else settings.hostShareMode

    LaunchedEffect(activeShareMode, isRunning, hostState.localIpAddresses) {
        if (!isRunning || activeShareMode != HostShareMode.NATIVE_TETHERING) {
            selectedTetherIp = null
        } else if (selectedTetherIp != null) {
            val stillPresent = hostState.localIpAddresses.any { entry ->
                extractIpFromInterfaceEntry(entry) == selectedTetherIp
            }
            if (!stillPresent) {
                selectedTetherIp = null
            }
        }
    }

    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRunning, hostState.traffic.startedAtEpochMs) {
        if (isRunning && hostState.traffic.startedAtEpochMs > 0L) {
            while (true) {
                nowEpochMs = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    val uptimeMs = if (isRunning && hostState.traffic.startedAtEpochMs > 0L) {
        (nowEpochMs - hostState.traffic.startedAtEpochMs).coerceAtLeast(0L)
    } else {
        0L
    }

    val displaySsid = hostState.ssid.takeIf { isRunning && it.isNotBlank() } ?: settings.fullSsid
    val displayPassphrase = hostState.passphrase.takeIf { isRunning && it.isNotBlank() } ?: settings.passphrase
    val displayPort = if (isRunning && hostState.port > 0) hostState.port else settings.proxyPort
    val defaultPreviewIp = when (activeShareMode) {
        HostShareMode.WIFI_DIRECT -> "192.168.49.1"
        HostShareMode.NATIVE_TETHERING -> "192.168.43.1"
    }
    val displayIp = when {
        activeShareMode == HostShareMode.NATIVE_TETHERING && selectedTetherIp != null ->
            selectedTetherIp!!
        isRunning && hostState.ipAddress.isNotBlank() ->
            hostState.ipAddress
        else ->
            defaultPreviewIp
    }
    val displayPacUrl = when {
        activeShareMode == HostShareMode.NATIVE_TETHERING && selectedTetherIp != null ->
            "http://$selectedTetherIp:$displayPort/proxy.pac"
        isRunning && hostState.pacUrl.isNotBlank() ->
            hostState.pacUrl
        else ->
            "http://$displayIp:$displayPort/proxy.pac"
    }

    fun copyField(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
        onShowMessage(tr("$label copiado al portapapeles", "$label copied to clipboard"))
    }

    fun handleSelectShareMode(mode: HostShareMode) {
        if (mode == activeShareMode) return
        if (isRunning || isStarting) {
            onShowMessage(
                tr(
                    "Apaga el router para cambiar de modo",
                    "Stop the router to switch modes",
                ),
            )
            return
        }
        selectedTetherIp = null
        onSelectShareMode(mode)
    }

    fun handlePowerToggle() {
        when (hostState.status) {
            ServiceStatus.RUNNING, ServiceStatus.STARTING -> onStopRouter()
            ServiceStatus.STOPPED, ServiceStatus.ERROR -> {
                if (activeShareMode == HostShareMode.NATIVE_TETHERING) {
                    onStartRouter()
                    return
                }
                val fresh = inspectHostEnvironment(context)
                envCheck = fresh
                if (fresh.missingWifiDirectPermission) {
                    pendingStartAfterPermissions = true
                    permissionLauncher.launch(requiredRuntimePermissions(HostShareMode.WIFI_DIRECT))
                } else if (!fresh.wifiEnabled) {
                    onShowMessage(
                        tr(
                            "Activa el Wi-Fi antes de encender el router.",
                            "Enable Wi-Fi before starting the router.",
                        ),
                    )
                } else if (fresh.needsLocationServices) {
                    onShowMessage(
                        tr(
                            "Activa los Servicios de Ubicación para crear la red Wi-Fi Direct.",
                            "Enable Location Services to create the Wi-Fi Direct network.",
                        ),
                    )
                } else {
                    onStartRouter()
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "hero_power") {
            HeroPowerCard(
                status = hostState.status,
                shareMode = activeShareMode,
                errorMessage = hostState.errorMessage,
                onSelectShareMode = ::handleSelectShareMode,
                onTogglePower = ::handlePowerToggle,
            )
        }

        if (envCheck.hasAnyIssue(activeShareMode)) {
            item(key = "permissions_card") {
                PrerequisitesCard(
                    check = envCheck,
                    shareMode = activeShareMode,
                    onRequestPermissions = {
                        pendingStartAfterPermissions = false
                        val perms = requiredRuntimePermissions(activeShareMode)
                        if (perms.isNotEmpty()) {
                            permissionLauncher.launch(perms)
                        }
                    },
                    onOpenWifiSettings = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        }
                    },
                    onOpenLocationSettings = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                    },
                )
            }
        }

        item(key = "credentials_card") {
            CredentialsCard(
                isRunning = isRunning,
                shareMode = activeShareMode,
                ssid = displaySsid,
                passphrase = displayPassphrase,
                ipAddress = displayIp,
                port = displayPort,
                pacUrl = displayPacUrl,
                localIpAddresses = hostState.localIpAddresses,
                showPassword = showPassword,
                onTogglePassword = { showPassword = !showPassword },
                onCopy = ::copyField,
                onSelectInterfaceIp = { ip ->
                    selectedTetherIp = ip
                    copyField("IP", ip)
                },
                onShowQr = { showQrDialog = true },
                onOpenTetheringSettings = { openTetheringSettings(context) },
            )
        }

        item(key = "stats_card") {
            RealtimeStatsCard(
                hostState = hostState,
                shareMode = activeShareMode,
                uptimeMs = uptimeMs,
            )
        }

        item(key = "quick_guide") {
            QuickConnectGuideCard(
                expanded = guideExpanded,
                shareMode = activeShareMode,
                ssid = displaySsid,
                ipAddress = displayIp,
                port = displayPort,
                pacUrl = displayPacUrl,
                onToggleExpanded = { guideExpanded = !guideExpanded },
            )
        }

        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(6.dp))
        }
    }

    if (showQrDialog && activeShareMode == HostShareMode.WIFI_DIRECT) {
        WifiQrCodeDialog(
            ssid = displaySsid,
            passphrase = displayPassphrase,
            ipAddress = displayIp,
            port = displayPort,
            pacUrl = displayPacUrl,
            onDismiss = { showQrDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeroPowerCard(
    status: ServiceStatus,
    shareMode: HostShareMode,
    errorMessage: String?,
    onSelectShareMode: (HostShareMode) -> Unit,
    onTogglePower: () -> Unit,
) {
    val isRunning = status == ServiceStatus.RUNNING
    val isStarting = status == ServiceStatus.STARTING
    val isError = status == ServiceStatus.ERROR
    val isNativeTethering = shareMode == HostShareMode.NATIVE_TETHERING

    val ringColor by animateColorAsState(
        targetValue = when (status) {
            ServiceStatus.RUNNING -> RouterStatusColors.ActiveEmerald
            ServiceStatus.STARTING -> RouterStatusColors.WarningAmber
            ServiceStatus.ERROR -> RouterStatusColors.ErrorCoral
            ServiceStatus.STOPPED -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = 350),
        label = "ringColor",
    )

    val buttonContainerColor by animateColorAsState(
        targetValue = when (status) {
            ServiceStatus.RUNNING -> RouterStatusColors.ActiveEmerald
            ServiceStatus.STARTING -> RouterStatusColors.WarningAmber
            ServiceStatus.ERROR -> RouterStatusColors.ErrorCoral
            ServiceStatus.STOPPED -> MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = tween(durationMillis = 350),
        label = "buttonContainerColor",
    )

    val buttonContentColor by animateColorAsState(
        targetValue = when (status) {
            ServiceStatus.RUNNING, ServiceStatus.STARTING, ServiceStatus.ERROR -> Color.White
            ServiceStatus.STOPPED -> MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = tween(durationMillis = 350),
        label = "buttonContentColor",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isRunning || isStarting) 1.12f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val modes = listOf(
        HostShareMode.WIFI_DIRECT to "Wi-Fi Direct",
        HostShareMode.NATIVE_TETHERING to tr("Tethering Nativo", "Native Tethering"),
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, ringColor.copy(alpha = if (isRunning) 0.5f else 0.2f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = shareMode == mode,
                        onClick = { onSelectShareMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = modes.size,
                        ),
                        label = {
                            Text(
                                text = label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            StatusPill(
                label = when (status) {
                    ServiceStatus.RUNNING -> if (isNativeTethering) {
                        tr("Proxy Tethering Activo", "Native Tethering Active")
                    } else {
                        tr("Router Activo", "Router Active")
                    }
                    ServiceStatus.STARTING -> if (isNativeTethering) {
                        tr("Iniciando Proxy...", "Starting Proxy...")
                    } else {
                        tr("Iniciando Wi-Fi Direct...", "Starting Wi-Fi Direct...")
                    }
                    ServiceStatus.ERROR -> tr("Error de inicio", "Startup Error")
                    ServiceStatus.STOPPED -> tr("Router Detenido", "Router Stopped")
                },
                dotColor = ringColor,
            )

            Spacer(modifier = Modifier.height(18.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(162.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(152.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(ringColor.copy(alpha = if (isRunning || isStarting) 0.16f else 0.08f)),
                )

                Surface(
                    onClick = onTogglePower,
                    shape = CircleShape,
                    color = buttonContainerColor,
                    contentColor = buttonContentColor,
                    tonalElevation = 6.dp,
                    shadowElevation = if (isRunning) 10.dp else 4.dp,
                    modifier = Modifier.size(134.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(34.dp),
                                color = Color.White,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = when (status) {
                                ServiceStatus.RUNNING -> tr("APAGAR", "STOP")
                                ServiceStatus.STARTING -> tr("INICIANDO...", "STARTING...")
                                ServiceStatus.STOPPED, ServiceStatus.ERROR -> tr("ENCENDER", "START")
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = when (status) {
                    ServiceStatus.RUNNING -> if (isNativeTethering) {
                        tr(
                            "Proxy tunelizado activo sobre Zona Wi-Fi / USB nativo (sin VPN en ningún teléfono)",
                            "Tunneled proxy active over native Hotspot / USB Tethering (no VPN app needed)",
                        )
                    } else {
                        tr(
                            "Compartiendo la conexión actual mediante proxy híbrido HTTP/PAC + SOCKS4/5",
                            "Sharing your current internet connection via HTTP/PAC + SOCKS4/5 hybrid proxy",
                        )
                    }
                    ServiceStatus.STARTING -> if (isNativeTethering) {
                        tr(
                            "Abriendo puerto del proxy y detectando interfaces de tethering...",
                            "Binding proxy port and scanning tethering interfaces...",
                        )
                    } else {
                        tr(
                            "Creando grupo Wi-Fi Direct y abriendo el puerto del proxy...",
                            "Creating Wi-Fi Direct group and binding proxy port...",
                        )
                    }
                    ServiceStatus.STOPPED -> if (isNativeTethering) {
                        tr(
                            "Usa tu Zona Wi-Fi o Tethering USB nativo + proxy tunelizado (sin VPN en ningún teléfono)",
                            "Uses Android's native Hotspot or USB Tethering + tunneled proxy (no VPN needed)",
                        )
                    } else {
                        tr(
                            "Pulsa ENCENDER para compartir internet por Wi-Fi Direct sin root",
                            "Tap START to share internet over Wi-Fi Direct without root",
                        )
                    }
                    ServiceStatus.ERROR -> tr(
                        "No se pudo iniciar el servicio. Revisa los permisos o reintenta.",
                        "Could not start the service. Check permissions or retry.",
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            AnimatedVisibility(visible = isError && !errorMessage.isNullOrBlank()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    dotColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = dotColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = dotColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PrerequisitesCard(
    check: HostEnvironmentCheck,
    shareMode: HostShareMode,
    onRequestPermissions: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val isNativeTethering = shareMode == HostShareMode.NATIVE_TETHERING

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, RouterStatusColors.WarningAmber.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = RouterStatusColors.WarningAmber,
                )
                Text(
                    text = if (isNativeTethering) {
                        tr("Permiso opcional", "Optional permission")
                    } else {
                        tr("Requisitos pendientes", "Action required")
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = RouterStatusColors.WarningAmber,
                )
            }

            if (isNativeTethering) {
                if (check.missingNotificationPermission) {
                    Text(
                        text = tr(
                            "Concede el permiso de Notificaciones (opcional) para ver el estado y velocidad del proxy en segundo plano.",
                            "Grant Notifications permission (optional) to view live proxy status and speed in the background.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Permitir notificaciones", "Allow notifications"))
                    }
                }
            } else {
                if (check.missingWifiDirectPermission || check.missingNotificationPermission) {
                    val permDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        tr(
                            "Android 13+ requiere permisos de Dispositivos Wi-Fi cercanos y Notificaciones para operar en segundo plano.",
                            "Android 13+ requires Nearby Wi-Fi Devices and Notifications permissions to run in the background.",
                        )
                    } else {
                        tr(
                            "Android 8–12 requiere el permiso de Ubicación precisa para crear redes Wi-Fi Direct.",
                            "Android 8–12 requires Fine Location permission to create Wi-Fi Direct networks.",
                        )
                    }
                    Text(
                        text = permDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Conceder permisos", "Grant permissions"))
                    }
                }

                if (!check.wifiEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WifiOff,
                                contentDescription = null,
                                tint = RouterStatusColors.WarningAmber,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = tr("El Wi-Fi está desactivado", "Wi-Fi is turned off"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = onOpenWifiSettings) {
                            Text(tr("Activar Wi-Fi", "Enable Wi-Fi"))
                        }
                    }
                }

                if (check.needsLocationServices) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = tr(
                                "Ubicación desactivada (necesaria en Android < 13)",
                                "Location is off (required on Android < 13)",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenLocationSettings) {
                            Text(tr("Abrir", "Open"))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CredentialsCard(
    isRunning: Boolean,
    shareMode: HostShareMode,
    ssid: String,
    passphrase: String,
    ipAddress: String,
    port: Int,
    pacUrl: String,
    localIpAddresses: List<String>,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    onCopy: (label: String, value: String) -> Unit,
    onSelectInterfaceIp: (String) -> Unit,
    onShowQr: () -> Unit,
    onOpenTetheringSettings: () -> Unit,
) {
    val isNativeTethering = shareMode == HostShareMode.NATIVE_TETHERING

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(
            width = 1.dp,
            color = if (isRunning) {
                RouterStatusColors.ActiveEmerald.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isNativeTethering) {
                        tr("Proxy Tethering Nativo", "Native Tethering Proxy")
                    } else {
                        tr("Credenciales Wi-Fi Direct y Proxy", "Wi-Fi Direct & Proxy Credentials")
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isRunning) {
                        RouterStatusColors.ActiveEmerald.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ) {
                    Text(
                        text = if (isRunning) tr("ACTIVO", "LIVE") else tr("VISTA PREVIA", "PREVIEW"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isRunning) {
                            RouterStatusColors.ActiveEmerald
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            if (!isNativeTethering) {
                CredentialRow(
                    label = "SSID",
                    value = ssid,
                    onCopy = { onCopy("SSID", ssid) },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                CredentialRow(
                    label = tr("Contraseña", "Passphrase"),
                    value = if (showPassword) passphrase else "•".repeat(passphrase.length.coerceIn(8, 14)),
                    trailingExtra = {
                        IconButton(onClick = onTogglePassword) {
                            Icon(
                                imageVector = if (showPassword) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = tr("Mostrar u ocultar", "Toggle visibility"),
                            )
                        }
                    },
                    onCopy = { onCopy(tr("Contraseña", "Passphrase"), passphrase) },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.weight(1.35f),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr("IP del Proxy", "Proxy IP"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = ipAddress,
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onCopy("IP", ipAddress) }) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = tr("Copiar IP", "Copy IP"),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.weight(0.9f),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr("Puerto", "Port"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = port.toString(),
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                        }
                        IconButton(onClick = { onCopy(tr("Puerto", "Port"), port.toString()) }) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = tr("Copiar puerto", "Copy port"),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = tr("URL Auto-Proxy (PAC)", "Auto-Proxy URL (PAC)"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = pacUrl,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isNativeTethering) {
                            Text(
                                text = tr(
                                    "Android Wi-Fi → Proxy → Configuración automática",
                                    "Android Wi-Fi → Proxy → Auto-Config",
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { onCopy("PAC URL", pacUrl) }) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = tr("Copiar URL PAC", "Copy PAC URL"),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (isNativeTethering && localIpAddresses.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = tr(
                            "Interfaces detectadas (toca para seleccionar y copiar):",
                            "Detected interfaces (tap to select & copy):",
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        localIpAddresses.forEach { entry ->
                            val chipIp = extractIpFromInterfaceEntry(entry)
                            FilterChip(
                                selected = chipIp == ipAddress,
                                onClick = { onSelectInterfaceIp(chipIp) },
                                label = {
                                    Text(
                                        text = entry,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            if (isNativeTethering) {
                FilledTonalButton(
                    onClick = onOpenTetheringSettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WifiTethering,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Abrir Zona Wi-Fi / Tethering", "Open Hotspot / Tethering"))
                }
            } else {
                FilledTonalButton(
                    onClick = onShowQr,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QrCode2,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Mostrar código QR Wi-Fi", "Show Wi-Fi QR Code"))
                }
            }
        }
    }
}

@Composable
private fun CredentialRow(
    label: String,
    value: String,
    trailingExtra: (@Composable () -> Unit)? = null,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (trailingExtra != null) {
                trailingExtra()
            }
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = tr("Copiar", "Copy"),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun RealtimeStatsCard(
    hostState: HostServiceState,
    shareMode: HostShareMode,
    uptimeMs: Long,
) {
    val traffic = hostState.traffic
    val totalBytes = traffic.bytesDown + traffic.bytesUp
    val isNativeTethering = shareMode == HostShareMode.NATIVE_TETHERING

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = tr("Estadísticas en Tiempo Real", "Real-Time Statistics"),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.ArrowDownward,
                    iconTint = RouterStatusColors.ActiveEmerald,
                    label = tr("Descarga", "Download"),
                    value = Formatters.formatSpeed(traffic.downloadBytesPerSec),
                    subtitle = Formatters.formatBytes(traffic.bytesDown),
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.ArrowUpward,
                    iconTint = MaterialTheme.colorScheme.primary,
                    label = tr("Subida", "Upload"),
                    value = Formatters.formatSpeed(traffic.uploadBytesPerSec),
                    subtitle = Formatters.formatBytes(traffic.bytesUp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.SwapVert,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    label = tr("Total transferido", "Total Transferred"),
                    value = Formatters.formatBytes(totalBytes),
                    subtitle = tr(
                        "${traffic.activeConnections} activas / ${traffic.totalConnections} total",
                        "${traffic.activeConnections} active / ${traffic.totalConnections} total",
                    ),
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Devices,
                    iconTint = RouterStatusColors.WarningAmber,
                    label = if (isNativeTethering) {
                        tr("Conexiones activas", "Active Conns")
                    } else {
                        tr("Clientes Wi-Fi", "Wi-Fi Clients")
                    },
                    value = if (isNativeTethering) {
                        traffic.activeConnections.toString()
                    } else {
                        hostState.connectedWifiClients.toString()
                    },
                    subtitle = tr("Tiempo: ", "Uptime: ") + Formatters.formatDuration(uptimeMs),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = tr(
                        "Conexiones activas: ${traffic.activeConnections}  •  Tiempo activo: ${Formatters.formatDuration(uptimeMs)}",
                        "Active connections: ${traffic.activeConnections}  •  Uptime: ${Formatters.formatDuration(uptimeMs)}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    subtitle: String,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickConnectGuideCard(
    expanded: Boolean,
    shareMode: HostShareMode,
    ssid: String,
    ipAddress: String,
    port: Int,
    pacUrl: String,
    onToggleExpanded: () -> Unit,
) {
    val isNativeTethering = shareMode == HostShareMode.NATIVE_TETHERING

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = tr(
                            "Cómo conectar otro teléfono sin instalar app",
                            "Connect a 2nd device without installing any app",
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            ) {
                Text(
                    text = "HTTP/HTTPS • PAC • SOCKS4/4a • SOCKS5 TCP+UDP",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (isNativeTethering) {
                            tr(
                                "1. Activa la Zona Wi-Fi (Hotspot) o Tethering USB de este teléfono y conecta el 2.º teléfono o PC a esa red.",
                                "1. Enable this phone's native Hotspot or USB Tethering and connect the 2nd phone or PC to it.",
                            )
                        } else {
                            tr(
                                "1. En el otro teléfono o PC, conéctate al Wi-Fi \"$ssid\" (o escanea el código QR).",
                                "1. On the 2nd phone or PC, connect to Wi-Fi \"$ssid\" (or scan the QR code).",
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = tr(
                            "2. En los ajustes de esa red Wi-Fi del otro dispositivo, elige Proxy → Manual (IP $ipAddress y Puerto $port) O Proxy → Auto-Config (PAC) con la URL $pacUrl. Funciona sin instalar ninguna app ni VPN en ningún teléfono.",
                            "2. In that Wi-Fi network's settings on the 2nd device, choose Proxy → Manual (IP $ipAddress and Port $port) OR Proxy → Auto-Config (PAC) with URL $pacUrl. Works without installing any VPN app on either phone.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = tr(
                            "3. Para juegos, llamadas VoIP o apps UDP en un 2.º Android, opcionalmente puedes instalar DirectRouter y usar la pestaña Cliente VPN.",
                            "3. For games, VoIP calls, or UDP apps on a 2nd Android phone, you can optionally install DirectRouter and use the VPN Client tab.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun WifiQrCodeDialog(
    ssid: String,
    passphrase: String,
    ipAddress: String,
    port: Int,
    pacUrl: String,
    onDismiss: () -> Unit,
) {
    val payload = remember(ssid, passphrase) {
        ValidationUtils.buildWifiQrPayload(ssid, passphrase)
    }
    val qrBitmap = remember(payload) {
        QrCodeGenerator.generateQrBitmap(payload, sizePx = 600)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = tr("Escanear para conectar Wi-Fi", "Scan to Join Wi-Fi"),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    shadowElevation = 2.dp,
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "Wi-Fi QR",
                            modifier = Modifier
                                .padding(16.dp)
                                .size(220.dp),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .padding(16.dp)
                                .size(220.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = tr("Error generando QR", "Failed to generate QR"),
                                color = Color.Black,
                            )
                        }
                    }
                }

                Text(
                    text = ssid,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = tr(
                        "Proxy Manual: $ipAddress : $port",
                        "Manual Proxy: $ipAddress : $port",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "PAC: $pacUrl",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(tr("Cerrar", "Close"))
            }
        },
    )
}
