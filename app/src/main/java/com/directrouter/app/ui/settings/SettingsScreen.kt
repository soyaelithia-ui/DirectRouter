package com.directrouter.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.directrouter.app.data.AppThemeMode
import com.directrouter.app.data.BindMode
import com.directrouter.app.data.RouterSettings
import com.directrouter.app.data.ValidationUtils
import com.directrouter.app.data.WifiBand
import com.directrouter.app.host.DetectedInterface
import com.directrouter.app.host.NetworkInterfaceScanner
import com.directrouter.app.ui.theme.RouterStatusColors
import com.directrouter.app.ui.theme.tr
import java.util.Locale

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return runCatching {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }.getOrDefault(false)
}

private data class DeviceDiagnosticsSnapshot(
    val manufacturerDisplay: String,
    val modelDisplay: String,
    val androidVersionLabel: String,
    val wifiDirectSupported: Boolean,
    val wifiDirectPermissionGranted: Boolean,
    val wifiDirectPermissionName: String,
    val notificationPermissionGranted: Boolean,
    val notificationPermissionRequired: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val interfaces: List<DetectedInterface>,
    val oemTitle: String,
    val oemTip: String,
)

private fun inspectDeviceDiagnostics(context: Context): DeviceDiagnosticsSnapshot {
    val rawManufacturer = Build.MANUFACTURER.orEmpty().trim()
    val rawBrand = Build.BRAND.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    val manufacturerDisplay = rawManufacturer.replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
    }.ifBlank { "Android" }

    val wifiDirectSupported = runCatching {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    }.getOrDefault(false)

    val wifiPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
    val wifiPermName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        "NEARBY_WIFI_DEVICES"
    } else {
        "ACCESS_FINE_LOCATION"
    }
    val wifiPermGranted = ContextCompat.checkSelfPermission(context, wifiPerm) == PackageManager.PERMISSION_GRANTED

    val notifRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val notifGranted = if (notifRequired) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    val oemKey = "$rawManufacturer $rawBrand".lowercase(Locale.US)
    val (oemTitle, oemTip) = when {
        oemKey.contains("xiaomi") || oemKey.contains("redmi") || oemKey.contains("poco") -> {
            tr("Xiaomi / Redmi / POCO (HyperOS / MIUI)", "Xiaomi / Redmi / POCO (HyperOS / MIUI)") to
                tr(
                    "En Info de la app → Ahorro de batería elige \"Sin restricciones\", activa \"Inicio automático\" y fija DirectRouter con el candado en Apps recientes para evitar cortes al apagar la pantalla.",
                    "In App Info → Battery Saver choose \"No restrictions\", enable \"Autostart\", and lock DirectRouter in Recents so the proxy stays alive with the screen off.",
                )
        }

        oemKey.contains("samsung") -> {
            tr("Samsung (One UI)", "Samsung (One UI)") to
                tr(
                    "En Ajustes → Batería → Límites de uso en segundo plano añade DirectRouter a \"Aplicaciones nunca inactivas\" y selecciona Batería \"Sin restricciones\" en Info de la app.",
                    "In Settings → Battery → Background usage limits add DirectRouter to \"Never sleeping apps\" and set App Info → Battery to \"Unrestricted\".",
                )
        }

        oemKey.contains("huawei") || oemKey.contains("honor") -> {
            tr("Huawei / Honor (EMUI / MagicOS)", "Huawei / Honor (EMUI / MagicOS)") to
                tr(
                    "En Ajustes → Batería → Inicio de aplicaciones desactiva la gestión automática para DirectRouter y activa \"Inicio automático\", \"Inicio secundario\" y \"Ejecutar en segundo plano\".",
                    "In Settings → Battery → App launch disable automatic management for DirectRouter and enable \"Auto-launch\", \"Secondary launch\", and \"Run in background\".",
                )
        }

        oemKey.contains("oppo") ||
            oemKey.contains("realme") ||
            oemKey.contains("oneplus") ||
            oemKey.contains("vivo") ||
            oemKey.contains("iqoo") -> {
            tr("OPPO / Realme / OnePlus / Vivo (ColorOS / OxygenOS / FuntouchOS)", "OPPO / Realme / OnePlus / Vivo (ColorOS / OxygenOS / FuntouchOS)") to
                tr(
                    "En Info de la app → Uso de batería activa \"Permitir actividad en segundo plano\" y \"Permitir inicio automático\". Desactiva la optimización nocturna si el túnel se pausa en reposo.",
                    "In App Info → Battery usage enable \"Allow background activity\" and \"Allow auto-launch\". Disable sleep standby optimization if the tunnel pauses while idle.",
                )
        }

        else -> {
            tr("Android estándar ($manufacturerDisplay)", "Standard Android ($manufacturerDisplay)") to
                tr(
                    "Mantén WakeLock activo y configura la batería de DirectRouter como \"Sin restricciones\" para evitar que Android Doze suspenda el proxy con la pantalla apagada.",
                    "Keep WakeLock enabled and set DirectRouter's battery usage to \"Unrestricted\" so Android Doze does not suspend sockets while the screen is off.",
                )
        }
    }

    return DeviceDiagnosticsSnapshot(
        manufacturerDisplay = manufacturerDisplay,
        modelDisplay = model.ifBlank { Build.DEVICE.orEmpty() },
        androidVersionLabel = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        wifiDirectSupported = wifiDirectSupported,
        wifiDirectPermissionGranted = wifiPermGranted,
        wifiDirectPermissionName = wifiPermName,
        notificationPermissionGranted = notifGranted,
        notificationPermissionRequired = notifRequired,
        ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context),
        interfaces = NetworkInterfaceScanner.scanTetheringIps(),
        oemTitle = oemTitle,
        oemTip = oemTip,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: RouterSettings,
    onUpdateProxyPort: (Int) -> Boolean,
    onUpdateSsidSuffix: (String) -> Unit,
    onUpdatePassphrase: (String) -> Boolean,
    onRegeneratePassphrase: () -> String,
    onUpdateWifiBand: (WifiBand) -> Unit,
    onUpdateKeepCpuAwake: (Boolean) -> Unit,
    onShowMessage: (String) -> Unit,
    isHostRunning: Boolean = false,
    onRestartHost: () -> Unit = {},
    onUpdateThemeMode: (AppThemeMode) -> Unit = {},
    onUpdateDynamicColor: (Boolean) -> Unit = {},
    onUpdateBindMode: (BindMode) -> Unit = {},
    onUpdateCustomBindIp: (String) -> Boolean = { false },
    onUpdateBindConfig: (BindMode, String) -> Boolean = { _, _ -> false },
    onGenerateRandomPort: () -> Int = { ValidationUtils.generateRandomPort() },
    onUpdateBlockLocalhostAccess: (Boolean) -> Unit = {},
    onUpdateMaxConnections: (Int) -> Boolean = { false },
    onUpdateProxyAuth: (Boolean, String, String) -> Boolean = { _, _, _ -> false },
    onGenerateProxyAuthPassword: () -> String = { ValidationUtils.generateSecurePassword() },
    onUpdateNotifShowLiveSpeed: (Boolean) -> Unit = {},
    onUpdateNotifPrivacyMode: (Boolean) -> Unit = {},
    onUpdateNotifShowStopAction: (Boolean) -> Unit = {},
    onUpdateNotifClientAlerts: (Boolean) -> Unit = {},
    onUpdateNotifAlertSound: (Boolean) -> Unit = {},
    onUpdateNotifAlertVibrate: (Boolean) -> Unit = {},
    onOpenServiceChannelSettings: () -> Unit = {},
    onOpenAlertsChannelSettings: () -> Unit = {},
    onOpenAppNotificationSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var portInput by rememberSaveable { mutableStateOf(settings.proxyPort.toString()) }
    var bindModeInput by rememberSaveable { mutableStateOf(settings.bindMode) }
    var customBindIpInput by rememberSaveable { mutableStateOf(settings.customBindIp) }

    var proxyAuthEnabledInput by rememberSaveable { mutableStateOf(settings.proxyAuthEnabled) }
    var proxyAuthUserInput by rememberSaveable { mutableStateOf(settings.proxyAuthUsername) }
    var proxyAuthPassInput by rememberSaveable { mutableStateOf(settings.proxyAuthPassword) }
    var showProxyAuthPass by rememberSaveable { mutableStateOf(false) }

    var ssidSuffixInput by rememberSaveable { mutableStateOf(settings.ssidSuffix) }
    var passphraseInput by rememberSaveable { mutableStateOf(settings.passphrase) }
    var showPassphrase by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings.proxyPort) {
        portInput = settings.proxyPort.toString()
    }
    LaunchedEffect(settings.bindMode) {
        bindModeInput = settings.bindMode
    }
    LaunchedEffect(settings.customBindIp) {
        customBindIpInput = settings.customBindIp
    }
    LaunchedEffect(settings.proxyAuthEnabled) {
        proxyAuthEnabledInput = settings.proxyAuthEnabled
    }
    LaunchedEffect(settings.proxyAuthUsername) {
        proxyAuthUserInput = settings.proxyAuthUsername
    }
    LaunchedEffect(settings.proxyAuthPassword) {
        proxyAuthPassInput = settings.proxyAuthPassword
    }
    LaunchedEffect(settings.ssidSuffix) {
        ssidSuffixInput = settings.ssidSuffix
    }
    LaunchedEffect(settings.passphrase) {
        passphraseInput = settings.passphrase
    }

    var diagnostics by remember { mutableStateOf(inspectDeviceDiagnostics(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                diagnostics = inspectDeviceDiagnostics(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val parsedPort = portInput.trim().toIntOrNull()
    val portValid = parsedPort != null && ValidationUtils.isValidPort(parsedPort)
    val trimmedBindIp = customBindIpInput.trim()
    val customBindIpValid = ValidationUtils.isStrictIpv4(trimmedBindIp)

    val trimmedProxyAuthUser = proxyAuthUserInput.trim()
    val trimmedProxyAuthPass = proxyAuthPassInput.trim()
    val proxyAuthUserValid = ValidationUtils.isValidProxyAuthCredential(trimmedProxyAuthUser)
    val proxyAuthPassValid = ValidationUtils.isValidProxyAuthCredential(trimmedProxyAuthPass)

    val passphraseValid = ValidationUtils.isValidPassphrase(passphraseInput)
    val previewSsid = ValidationUtils.buildDirectSsid(ssidSuffixInput)
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Apariencia y Tema
        item(key = "appearance_card") {
            SettingsCard(
                icon = Icons.Rounded.Palette,
                title = tr("Apariencia y Tema", "Appearance & Theme"),
            ) {
                val themeOptions = listOf(
                    AppThemeMode.SYSTEM to tr("Sistema", "System"),
                    AppThemeMode.LIGHT to tr("Claro", "Light"),
                    AppThemeMode.DARK to tr("Oscuro", "Dark"),
                    AppThemeMode.AMOLED to "AMOLED",
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    themeOptions.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = {
                                onUpdateThemeMode(mode)
                                onShowMessage(tr("Tema: $label", "Theme: $label"))
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = themeOptions.size,
                            ),
                            icon = {},
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingSwitchRow(
                    title = tr("Colores dinámicos (Material You)", "Dynamic Colors (Material You)"),
                    subtitle = if (supportsDynamicColor) {
                        tr(
                            "Adapta los acentos al fondo de pantalla. En modo AMOLED mantiene el fondo negro puro.",
                            "Adapts accent colors to your wallpaper. Keeps pure black background in AMOLED mode.",
                        )
                    } else {
                        tr(
                            "Disponible en Android 12 o superior (actual: Android ${Build.VERSION.RELEASE}).",
                            "Available on Android 12 or higher (current: Android ${Build.VERSION.RELEASE}).",
                        )
                    },
                    checked = supportsDynamicColor && settings.dynamicColor,
                    enabled = supportsDynamicColor,
                    onCheckedChange = { enabled ->
                        onUpdateDynamicColor(enabled)
                    },
                )
            }
        }

        // 2. Red, IP y Puerto Seguro
        item(key = "network_ip_port_card") {
            SettingsCard(
                icon = Icons.Rounded.Lan,
                title = tr("Red, IP y Puerto Seguro", "Network, IP & Safe Port"),
            ) {
                Text(
                    text = tr(
                        "Un único puerto atiende HTTP/HTTPS, Auto-Proxy PAC, SOCKS4/4a y SOCKS5 (TCP + UDP). Rango permitido: 1024–65535.",
                        "A single port serves HTTP/HTTPS, Auto-Proxy PAC, SOCKS4/4a, and SOCKS5 (TCP + UDP). Valid range: 1024–65535.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = portInput,
                    onValueChange = { value ->
                        portInput = value.filter { it.isDigit() }.take(5)
                    },
                    label = { Text(tr("Puerto del Proxy", "Proxy Port")) },
                    singleLine = true,
                    isError = portInput.isNotEmpty() && !portValid,
                    supportingText = {
                        if (portInput.isNotEmpty() && !portValid) {
                            Text(tr("Debe estar entre 1024 y 65535", "Must be between 1024 and 65535"))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val randomPort = onGenerateRandomPort()
                            portInput = randomPort.toString()
                            onShowMessage(tr("Puerto aleatorio: $randomPort", "Random port: $randomPort"))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = tr("Puerto aleatorio", "Random port"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Button(
                        onClick = {
                            val port = parsedPort
                            if (port != null && onUpdateProxyPort(port)) {
                                onShowMessage(tr("Puerto guardado ($port)", "Port saved ($port)"))
                            } else {
                                onShowMessage(tr("Puerto inválido", "Invalid port"))
                            }
                        },
                        enabled = portValid,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Guardar", "Save"))
                    }
                }

                if (isHostRunning) {
                    FilledTonalButton(
                        onClick = {
                            val port = parsedPort
                            if (port != null && portValid) {
                                onUpdateProxyPort(port)
                            }
                            if (bindModeInput == BindMode.SPECIFIC_IP) {
                                if (customBindIpValid) {
                                    onUpdateBindConfig(BindMode.SPECIFIC_IP, trimmedBindIp)
                                } else {
                                    onShowMessage(
                                        tr(
                                            "Introduce una IP IPv4 válida antes de reiniciar.",
                                            "Enter a valid IPv4 address before restarting.",
                                        ),
                                    )
                                    return@FilledTonalButton
                                }
                            } else {
                                onUpdateBindMode(bindModeInput)
                            }
                            onRestartHost()
                            onShowMessage(
                                tr("Aplicando cambios y reiniciando router...", "Applying changes and restarting router..."),
                            )
                        },
                        enabled = portValid && (bindModeInput != BindMode.SPECIFIC_IP || customBindIpValid),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Aplicar y reiniciar router", "Apply & Restart Router"))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = tr("Modo de enlace IP (Bind)", "IP Bind Mode"),
                    style = MaterialTheme.typography.titleSmall,
                )

                val bindModes = listOf(
                    BindMode.AUTO_LOCAL to tr("Automático", "Safe Auto"),
                    BindMode.SPECIFIC_IP to tr("IP específica", "Specific IP"),
                    BindMode.ALL_INTERFACES to tr("Todas 0.0.0.0", "All 0.0.0.0"),
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    bindModes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = bindModeInput == mode,
                            onClick = {
                                bindModeInput = mode
                                when (mode) {
                                    BindMode.AUTO_LOCAL -> {
                                        onUpdateBindMode(BindMode.AUTO_LOCAL)
                                        onShowMessage(
                                            tr(
                                                "Enlace: Automático seguro (solo interfaz local)",
                                                "Bind: Safe Auto (local interface only)",
                                            ),
                                        )
                                    }

                                    BindMode.ALL_INTERFACES -> {
                                        onUpdateBindMode(BindMode.ALL_INTERFACES)
                                        onShowMessage(
                                            tr(
                                                "Advertencia: enlace en todas las interfaces (0.0.0.0)",
                                                "Warning: binding on all interfaces (0.0.0.0)",
                                            ),
                                        )
                                    }

                                    BindMode.SPECIFIC_IP -> {
                                        if (customBindIpValid) {
                                            onUpdateBindConfig(BindMode.SPECIFIC_IP, trimmedBindIp)
                                        } else {
                                            onUpdateBindMode(BindMode.SPECIFIC_IP)
                                        }
                                    }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = bindModes.size,
                            ),
                            icon = {},
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }

                AnimatedVisibility(visible = bindModeInput == BindMode.ALL_INTERFACES) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = RouterStatusColors.WarningAmber.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, RouterStatusColors.WarningAmber.copy(alpha = 0.45f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = RouterStatusColors.WarningAmber,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = tr(
                                    "Advertencia de seguridad: 0.0.0.0 expone el proxy en TODAS las interfaces activas (incluido Wi-Fi público). Se recomienda \"Automático\" o \"IP específica\".",
                                    "Security warning: 0.0.0.0 exposes the proxy on ALL active interfaces (including public Wi-Fi). \"Safe Auto\" or \"Specific IP\" is recommended.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = tr("Interfaces locales detectadas", "Detected local interfaces"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            diagnostics = inspectDeviceDiagnostics(context)
                            onShowMessage(
                                tr(
                                    "Interfaces actualizadas (${diagnostics.interfaces.size})",
                                    "Interfaces refreshed (${diagnostics.interfaces.size})",
                                ),
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Actualizar", "Refresh"))
                    }
                }

                if (diagnostics.interfaces.isEmpty()) {
                    Text(
                        text = tr(
                            "No se detectaron interfaces locales activas (activa Wi-Fi, Zona Wi-Fi o Tethering USB y pulsa Actualizar).",
                            "No active local interfaces detected (enable Wi-Fi, Hotspot, or USB Tethering and tap Refresh).",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        diagnostics.interfaces.forEach { iface ->
                            FilterChip(
                                selected = bindModeInput == BindMode.SPECIFIC_IP && trimmedBindIp == iface.ipAddress,
                                onClick = {
                                    customBindIpInput = iface.ipAddress
                                    bindModeInput = BindMode.SPECIFIC_IP
                                    onUpdateBindConfig(BindMode.SPECIFIC_IP, iface.ipAddress)
                                    onShowMessage(
                                        tr(
                                            "IP seleccionada: ${iface.ipAddress} (${iface.name})",
                                            "Selected IP: ${iface.ipAddress} (${iface.name})",
                                        ),
                                    )
                                },
                                label = {
                                    Text(
                                        text = "${iface.ipAddress} (${iface.name})",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                },
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = bindModeInput == BindMode.SPECIFIC_IP) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = customBindIpInput,
                                onValueChange = { customBindIpInput = it.trim() },
                                label = { Text(tr("IP personalizada (IPv4)", "Custom IP (IPv4)")) },
                                placeholder = { Text("192.168.43.1") },
                                singleLine = true,
                                isError = customBindIpInput.isNotEmpty() && !customBindIpValid,
                                supportingText = {
                                    if (customBindIpInput.isNotEmpty() && !customBindIpValid) {
                                        Text(tr("IPv4 inválida (ej. 192.168.43.1)", "Invalid IPv4 (e.g. 192.168.43.1)"))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )

                            Button(
                                onClick = {
                                    val saved = if (settings.bindMode == BindMode.SPECIFIC_IP) {
                                        onUpdateCustomBindIp(trimmedBindIp)
                                    } else {
                                        onUpdateBindConfig(BindMode.SPECIFIC_IP, trimmedBindIp)
                                    }
                                    if (saved) {
                                        onShowMessage(tr("IP de enlace guardada ($trimmedBindIp)", "Bind IP saved ($trimmedBindIp)"))
                                    } else {
                                        onShowMessage(tr("IPv4 inválida", "Invalid IPv4"))
                                    }
                                },
                                enabled = customBindIpValid,
                            ) {
                                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Guardar", "Save"))
                            }
                        }
                        Text(
                            text = tr(
                                "La IP debe pertenecer a una interfaz activa del teléfono al iniciar el router (en Wi-Fi Direct Android fija 192.168.49.1 sin root).",
                                "The IP must belong to an active interface on this phone when starting the router (Android locks Wi-Fi Direct to 192.168.49.1 without root).",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 3. Seguridad del Proxy
        item(key = "proxy_security_card") {
            SettingsCard(
                icon = Icons.Rounded.Security,
                title = tr("Seguridad del Proxy", "Proxy Security"),
            ) {
                SettingSwitchRow(
                    title = tr("Bloquear acceso a localhost/Host (Anti-SSRF)", "Block localhost/Host access (Anti-SSRF)"),
                    subtitle = tr(
                        "Impide conexiones hacia 127.0.0.1, ::1, enlaces locales (169.254.x.x) o al propio puerto del proxy.",
                        "Blocks client connections to 127.0.0.1, ::1, link-local (169.254.x.x), or the proxy's own port.",
                    ),
                    checked = settings.blockLocalhostAccess,
                    onCheckedChange = { enabled ->
                        onUpdateBlockLocalhostAccess(enabled)
                        onShowMessage(
                            if (enabled) {
                                tr("Protección Anti-SSRF activada", "Anti-SSRF protection enabled")
                            } else {
                                tr("Protección Anti-SSRF desactivada", "Anti-SSRF protection disabled")
                            },
                        )
                    },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = tr("Límite de conexiones simultáneas", "Max Concurrent Connections"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = tr(
                        "Protege la memoria y batería limitando los túneles abiertos al mismo tiempo.",
                        "Protects memory and battery by capping simultaneous open tunnels.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val connectionLimits = listOf(64, 128, 256, 512)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    connectionLimits.forEachIndexed { index, limit ->
                        SegmentedButton(
                            selected = settings.maxConnections == limit,
                            onClick = {
                                if (onUpdateMaxConnections(limit)) {
                                    onShowMessage(
                                        tr("Límite de conexiones: $limit", "Max connections: $limit"),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = connectionLimits.size,
                            ),
                            icon = {},
                            label = {
                                Text(
                                    text = limit.toString(),
                                    fontFamily = FontFamily.Monospace,
                                )
                            },
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingSwitchRow(
                    title = tr("Autenticación Usuario/Contraseña", "Username / Password Authentication"),
                    subtitle = tr(
                        "Exige credenciales en HTTP Basic y SOCKS5 (rechaza SOCKS4 automáticamente).",
                        "Requires credentials for HTTP Basic and SOCKS5 (automatically rejects SOCKS4).",
                    ),
                    checked = proxyAuthEnabledInput,
                    onCheckedChange = { enabled ->
                        proxyAuthEnabledInput = enabled
                        if (!enabled) {
                            onUpdateProxyAuth(false, trimmedProxyAuthUser.ifEmpty { "router" }, trimmedProxyAuthPass)
                            onShowMessage(tr("Autenticación del proxy desactivada", "Proxy authentication disabled"))
                        } else {
                            if (proxyAuthUserInput.isBlank()) {
                                proxyAuthUserInput = "router"
                            }
                            if (proxyAuthPassInput.isBlank()) {
                                val generated = onGenerateProxyAuthPassword()
                                proxyAuthPassInput = generated
                                showProxyAuthPass = true
                            }
                        }
                    },
                )

                AnimatedVisibility(visible = proxyAuthEnabledInput) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = tr(
                                        "Compatibilidad: el proxy manual nativo de Android tiene fallos en algunas apps cuando el proxy exige contraseña HTTP. Recomendado con la pestaña Cliente VPN, navegadores o clientes SOCKS5.",
                                        "Compatibility: Android's native manual Wi-Fi proxy has known quirks in some apps when HTTP auth is required. Best used with the VPN Client tab, browsers, or SOCKS5 clients.",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        OutlinedTextField(
                            value = proxyAuthUserInput,
                            onValueChange = { proxyAuthUserInput = it.take(ValidationUtils.MAX_PROXY_AUTH_CREDENTIAL_LENGTH) },
                            label = { Text(tr("Usuario", "Username")) },
                            singleLine = true,
                            isError = !proxyAuthUserValid,
                            supportingText = {
                                if (!proxyAuthUserValid) {
                                    Text(tr("1–64 caracteres ASCII sin espacios ni ':'", "1–64 ASCII chars without spaces or ':'"))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = proxyAuthPassInput,
                            onValueChange = { proxyAuthPassInput = it.take(ValidationUtils.MAX_PROXY_AUTH_CREDENTIAL_LENGTH) },
                            label = { Text(tr("Contraseña", "Password")) },
                            singleLine = true,
                            isError = !proxyAuthPassValid,
                            visualTransformation = if (showProxyAuthPass) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showProxyAuthPass = !showProxyAuthPass }) {
                                    Icon(
                                        imageVector = if (showProxyAuthPass) {
                                            Icons.Rounded.VisibilityOff
                                        } else {
                                            Icons.Rounded.Visibility
                                        },
                                        contentDescription = tr("Mostrar u ocultar", "Toggle visibility"),
                                    )
                                }
                            },
                            supportingText = {
                                if (!proxyAuthPassValid) {
                                    Text(tr("1–64 caracteres ASCII sin espacios ni ':'", "1–64 ASCII chars without spaces or ':'"))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val generated = onGenerateProxyAuthPassword()
                                    proxyAuthPassInput = generated
                                    showProxyAuthPass = true
                                    onShowMessage(tr("Contraseña segura generada", "Secure password generated"))
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = tr("Generar contraseña", "Generate password"),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Button(
                                onClick = {
                                    if (onUpdateProxyAuth(true, trimmedProxyAuthUser, trimmedProxyAuthPass)) {
                                        onShowMessage(tr("Autenticación del proxy guardada", "Proxy authentication saved"))
                                    } else {
                                        onShowMessage(tr("Credenciales inválidas", "Invalid credentials"))
                                    }
                                },
                                enabled = proxyAuthUserValid && proxyAuthPassValid,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Guardar", "Save"))
                            }
                        }
                    }
                }
            }
        }

        // 4. Notificaciones y Alertas
        item(key = "notifications_card") {
            SettingsCard(
                icon = Icons.Rounded.Notifications,
                title = tr("Notificaciones y Alertas", "Notifications & Alerts"),
            ) {
                SettingSwitchRow(
                    title = tr("Mostrar velocidad en vivo", "Show live speed"),
                    subtitle = tr(
                        "Muestra velocidad de descarga/subida y total transferido en la notificación activa.",
                        "Displays live download/upload speed and total traffic in the active notification.",
                    ),
                    checked = settings.notifShowLiveSpeed,
                    onCheckedChange = onUpdateNotifShowLiveSpeed,
                )

                SettingSwitchRow(
                    title = tr("Modo privacidad", "Privacy mode"),
                    subtitle = tr(
                        "Oculta SSID e IP en la notificación y en la pantalla de bloqueo.",
                        "Hides SSID and IP address in the notification and on the lock screen.",
                    ),
                    checked = settings.notifPrivacyMode,
                    onCheckedChange = onUpdateNotifPrivacyMode,
                )

                SettingSwitchRow(
                    title = tr("Mostrar botón rápido Apagar", "Show quick Stop action"),
                    subtitle = tr(
                        "Incluye un botón directo para detener el router o VPN desde la notificación.",
                        "Adds a one-tap Stop button directly inside the notification.",
                    ),
                    checked = settings.notifShowStopAction,
                    onCheckedChange = onUpdateNotifShowStopAction,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingSwitchRow(
                    title = tr("Alertas de clientes y errores", "Client & error alerts"),
                    subtitle = tr(
                        "Emite una alerta cuando cambian los clientes conectados o ocurre un fallo.",
                        "Sends an alert when connected clients change or a service error occurs.",
                    ),
                    checked = settings.notifClientAlerts,
                    onCheckedChange = onUpdateNotifClientAlerts,
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                        alpha = if (settings.notifClientAlerts) 1f else 0.55f,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SettingSwitchRow(
                            title = tr("Sonido de alertas", "Alert sound"),
                            subtitle = tr("Reproducir tono al recibir alertas", "Play a tone on alerts"),
                            checked = settings.notifAlertSound,
                            enabled = settings.notifClientAlerts,
                            onCheckedChange = onUpdateNotifAlertSound,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        SettingSwitchRow(
                            title = tr("Vibración de alertas", "Alert vibration"),
                            subtitle = tr("Vibrar al recibir alertas", "Vibrate on alerts"),
                            checked = settings.notifAlertVibrate,
                            enabled = settings.notifClientAlerts,
                            onCheckedChange = onUpdateNotifAlertVibrate,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = tr("Canales de notificación de Android", "Android Notification Channels"),
                    style = MaterialTheme.typography.titleSmall,
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onOpenServiceChannelSettings) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Servicio Router", "Router Service"))
                    }

                    OutlinedButton(onClick = onOpenAlertsChannelSettings) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Alertas", "Alerts"))
                    }

                    FilledTonalButton(onClick = onOpenAppNotificationSettings) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Ajustes de app", "App Settings"))
                    }
                }
            }
        }

        // 5. Red Wi-Fi Direct
        item(key = "wifi_direct_card") {
            SettingsCard(
                icon = Icons.Rounded.Wifi,
                title = tr("Red Wi-Fi Direct", "Wi-Fi Direct Network"),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                tr(
                                    "SSID fijo, contraseña y banda aplican en Android 10+. Reinicia el router para aplicar cambios.",
                                    "Custom SSID, fixed passphrase, and band apply on Android 10+. Restart the router to apply changes.",
                                )
                            } else {
                                tr(
                                    "Este dispositivo usa Android 8–9: Android asigna credenciales Wi-Fi Direct automáticamente (personalización disponible en Android 10+).",
                                    "This device runs Android 8–9: Android generates Wi-Fi Direct credentials automatically (customization requires Android 10+).",
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = ssidSuffixInput,
                    onValueChange = { ssidSuffixInput = it.take(22) },
                    label = { Text(tr("Sufijo del nombre de red (SSID)", "Network name suffix (SSID)")) },
                    prefix = {
                        Text(
                            text = ValidationUtils.SSID_PREFIX,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("Vista previa SSID:", "SSID preview:"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = previewSsid,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            onUpdateSsidSuffix(ssidSuffixInput)
                            onShowMessage(tr("SSID actualizado: $previewSsid", "SSID updated: $previewSsid"))
                        },
                    ) {
                        Text(tr("Guardar SSID", "Save SSID"))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                OutlinedTextField(
                    value = passphraseInput,
                    onValueChange = { passphraseInput = it.take(63) },
                    label = { Text(tr("Contraseña fija (8–63 caracteres)", "Fixed passphrase (8–63 chars)")) },
                    singleLine = true,
                    isError = passphraseInput.isNotEmpty() && !passphraseValid,
                    visualTransformation = if (showPassphrase) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassphrase = !showPassphrase }) {
                            Icon(
                                imageVector = if (showPassphrase) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = tr("Mostrar u ocultar", "Toggle visibility"),
                            )
                        }
                    },
                    supportingText = {
                        if (passphraseInput.isNotEmpty() && !passphraseValid) {
                            Text(tr("Usa entre 8 y 63 caracteres ASCII visibles", "Use 8 to 63 printable ASCII characters"))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val regenerated = onRegeneratePassphrase()
                            passphraseInput = regenerated
                            showPassphrase = true
                            onShowMessage(tr("Nueva contraseña generada", "New passphrase generated"))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Generar nueva", "Generate new"))
                    }

                    Button(
                        onClick = {
                            if (onUpdatePassphrase(passphraseInput)) {
                                onShowMessage(tr("Contraseña guardada", "Passphrase saved"))
                            } else {
                                onShowMessage(tr("Contraseña inválida", "Invalid passphrase"))
                            }
                        },
                        enabled = passphraseValid,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Guardar", "Save"))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = tr("Banda Wi-Fi Direct", "Wi-Fi Direct Band"),
                    style = MaterialTheme.typography.titleSmall,
                )

                val bands = listOf(
                    WifiBand.AUTO to tr("Automática", "Auto"),
                    WifiBand.BAND_2GHZ to "2.4 GHz",
                    WifiBand.BAND_5GHZ to "5 GHz",
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    bands.forEachIndexed { index, (band, label) ->
                        SegmentedButton(
                            selected = settings.wifiBand == band,
                            onClick = {
                                onUpdateWifiBand(band)
                                onShowMessage(tr("Banda: $label", "Band: $label"))
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = bands.size,
                            ),
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        // 6. Energía, Compatibilidad y Diagnóstico OEM
        item(key = "power_oem_diagnostics_card") {
            SettingsCard(
                icon = Icons.Rounded.BatteryChargingFull,
                title = tr("Energía, Compatibilidad y Diagnóstico OEM", "Power, Compatibility & OEM Diagnostics"),
            ) {
                SettingSwitchRow(
                    title = tr("Mantener CPU activa (WakeLock)", "Keep CPU Awake (WakeLock)"),
                    subtitle = tr(
                        "Evita que el modo reposo (Doze) pause el proxy al apagar la pantalla.",
                        "Prevents Android Doze from pausing the proxy when the screen turns off.",
                    ),
                    checked = settings.keepCpuAwake,
                    onCheckedChange = onUpdateKeepCpuAwake,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BatteryChargingFull,
                        contentDescription = null,
                        tint = if (diagnostics.ignoringBatteryOptimizations) {
                            RouterStatusColors.ActiveEmerald
                        } else {
                            RouterStatusColors.WarningAmber
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("Optimización de Batería", "Battery Optimization"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = if (diagnostics.ignoringBatteryOptimizations) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    Icons.Rounded.Warning
                                },
                                contentDescription = null,
                                tint = if (diagnostics.ignoringBatteryOptimizations) {
                                    RouterStatusColors.ActiveEmerald
                                } else {
                                    RouterStatusColors.WarningAmber
                                },
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = if (diagnostics.ignoringBatteryOptimizations) {
                                    tr("Sin restricciones (recomendado)", "Unrestricted (recommended)")
                                } else {
                                    tr("Restringida: Android puede detener el router", "Optimized: Android may stop the router")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (diagnostics.ignoringBatteryOptimizations) {
                                    RouterStatusColors.ActiveEmerald
                                } else {
                                    RouterStatusColors.WarningAmber
                                },
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = {
                        val opened = runCatching {
                            val directIntent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            )
                            context.startActivity(directIntent)
                        }.isSuccess
                        if (!opened) {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (diagnostics.ignoringBatteryOptimizations) {
                            tr("Revisar ajustes de batería", "Review battery settings")
                        } else {
                            tr("Permitir sin restricciones", "Allow unrestricted background use")
                        },
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Diagnóstico de Compatibilidad panel
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = tr("Diagnóstico de Compatibilidad", "Compatibility Diagnostics"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    TextButton(
                        onClick = {
                            diagnostics = inspectDeviceDiagnostics(context)
                            onShowMessage(tr("Diagnóstico actualizado", "Diagnostics refreshed"))
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Actualizar", "Refresh"), style = MaterialTheme.typography.labelMedium)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DiagnosticRow(
                            label = tr("Dispositivo", "Device"),
                            value = "${diagnostics.manufacturerDisplay} ${diagnostics.modelDisplay}",
                        )
                        DiagnosticRow(
                            label = tr("Sistema", "OS / API"),
                            value = diagnostics.androidVersionLabel,
                        )
                        DiagnosticStatusRow(
                            label = tr("Hardware Wi-Fi Direct", "Wi-Fi Direct Hardware"),
                            ok = diagnostics.wifiDirectSupported,
                            value = if (diagnostics.wifiDirectSupported) {
                                tr("Soportado", "Supported")
                            } else {
                                tr("No detectado", "Not supported")
                            },
                            isErrorWhenNotOk = true,
                        )
                        DiagnosticStatusRow(
                            label = tr("Permiso ${diagnostics.wifiDirectPermissionName}", "${diagnostics.wifiDirectPermissionName} Perm"),
                            ok = diagnostics.wifiDirectPermissionGranted,
                            value = if (diagnostics.wifiDirectPermissionGranted) {
                                tr("Concedido", "Granted")
                            } else {
                                tr("Pendiente", "Missing")
                            },
                        )
                        DiagnosticStatusRow(
                            label = tr("Permiso Notificaciones", "Notifications Permission"),
                            ok = diagnostics.notificationPermissionGranted,
                            value = when {
                                !diagnostics.notificationPermissionRequired -> tr("No requerido", "Not required")
                                diagnostics.notificationPermissionGranted -> tr("Concedido", "Granted")
                                else -> tr("Pendiente", "Missing")
                            },
                        )
                        DiagnosticStatusRow(
                            label = tr("Batería sin restricciones", "Unrestricted Battery"),
                            ok = diagnostics.ignoringBatteryOptimizations,
                            value = if (diagnostics.ignoringBatteryOptimizations) {
                                tr("Sí", "Yes")
                            } else {
                                tr("Optimizada", "Optimized")
                            },
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        Text(
                            text = tr("Interfaces de red detectadas:", "Detected network interfaces:"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (diagnostics.interfaces.isEmpty()) {
                            Text(
                                text = tr("Ninguna interfaz local activa", "No active local interfaces"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                diagnostics.interfaces.forEach { iface ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    ) {
                                        Text(
                                            text = "${iface.ipAddress} (${iface.name})",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // OEM-specific tips banner
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.TipsAndUpdates,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = tr("Consejos OEM: ${diagnostics.oemTitle}", "OEM Tips: ${diagnostics.oemTitle}"),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = diagnostics.oemTip,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
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
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.5f
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DiagnosticStatusRow(
    label: String,
    ok: Boolean,
    value: String,
    isErrorWhenNotOk: Boolean = false,
) {
    val tint: Color = when {
        ok -> RouterStatusColors.ActiveEmerald
        isErrorWhenNotOk -> RouterStatusColors.ErrorCoral
        else -> RouterStatusColors.WarningAmber
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = when {
                    ok -> Icons.Rounded.CheckCircle
                    isErrorWhenNotOk -> Icons.Rounded.Error
                    else -> Icons.Rounded.Warning
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
