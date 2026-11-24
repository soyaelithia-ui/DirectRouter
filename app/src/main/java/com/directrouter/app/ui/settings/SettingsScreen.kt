package com.directrouter.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.directrouter.app.data.RouterSettings
import com.directrouter.app.data.ValidationUtils
import com.directrouter.app.data.WifiBand
import com.directrouter.app.ui.theme.RouterStatusColors
import com.directrouter.app.ui.theme.tr

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return runCatching {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }.getOrDefault(false)
}

@OptIn(ExperimentalMaterial3Api::class)
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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var portInput by rememberSaveable { mutableStateOf(settings.proxyPort.toString()) }
    var ssidSuffixInput by rememberSaveable { mutableStateOf(settings.ssidSuffix) }
    var passphraseInput by rememberSaveable { mutableStateOf(settings.passphrase) }
    var showPassphrase by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings.proxyPort) {
        portInput = settings.proxyPort.toString()
    }
    LaunchedEffect(settings.ssidSuffix) {
        ssidSuffixInput = settings.ssidSuffix
    }
    LaunchedEffect(settings.passphrase) {
        passphraseInput = settings.passphrase
    }

    var ignoringBatteryOpt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBatteryOpt = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val parsedPort = portInput.trim().toIntOrNull()
    val portValid = parsedPort != null && ValidationUtils.isValidPort(parsedPort)
    val passphraseValid = ValidationUtils.isValidPassphrase(passphraseInput)
    val previewSsid = ValidationUtils.buildDirectSsid(ssidSuffixInput)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "proxy_port_card") {
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = tr("Puerto del Proxy Híbrido", "Hybrid Proxy Port"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = tr(
                            "Un único puerto atiende HTTP/HTTPS, Auto-Proxy PAC, SOCKS4/4a y SOCKS5 (TCP + UDP). Rango permitido: 1024–65535.",
                            "A single port serves HTTP/HTTPS, Auto-Proxy PAC, SOCKS4/4a, and SOCKS5 (TCP + UDP). Valid range: 1024–65535.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { value ->
                                portInput = value.filter { it.isDigit() }.take(5)
                            },
                            label = { Text(tr("Puerto", "Port")) },
                            singleLine = true,
                            isError = portInput.isNotEmpty() && !portValid,
                            supportingText = {
                                if (portInput.isNotEmpty() && !portValid) {
                                    Text(tr("Debe estar entre 1024 y 65535", "Must be between 1024 and 65535"))
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )

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
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Guardar", "Save"))
                        }
                    }
                }
            }
        }

        item(key = "wifi_direct_card") {
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
                    Text(
                        text = tr("Red Wi-Fi Direct", "Wi-Fi Direct Network"),
                        style = MaterialTheme.typography.titleMedium,
                    )

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
        }

        item(key = "power_battery_card") {
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
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = tr("Energía y Segundo Plano", "Power & Background"),
                        style = MaterialTheme.typography.titleMedium,
                    )

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
                                text = tr("Mantener CPU activa (WakeLock)", "Keep CPU Awake (WakeLock)"),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = tr(
                                    "Evita que el modo reposo (Doze) pause el proxy al apagar la pantalla.",
                                    "Prevents Android Doze from pausing the proxy when the screen turns off.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.keepCpuAwake,
                            onCheckedChange = onUpdateKeepCpuAwake,
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BatteryChargingFull,
                            contentDescription = null,
                            tint = if (ignoringBatteryOpt) {
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
                                    imageVector = if (ignoringBatteryOpt) {
                                        Icons.Rounded.CheckCircle
                                    } else {
                                        Icons.Rounded.Warning
                                    },
                                    contentDescription = null,
                                    tint = if (ignoringBatteryOpt) {
                                        RouterStatusColors.ActiveEmerald
                                    } else {
                                        RouterStatusColors.WarningAmber
                                    },
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = if (ignoringBatteryOpt) {
                                        tr("Sin restricciones (recomendado)", "Unrestricted (recommended)")
                                    } else {
                                        tr("Restringida: Android puede detener el router", "Optimized: Android may stop the router")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ignoringBatteryOpt) {
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
                            text = if (ignoringBatteryOpt) {
                                tr("Revisar ajustes de batería", "Review battery settings")
                            } else {
                                tr("Permitir sin restricciones", "Allow unrestricted background use")
                            },
                        )
                    }
                }
            }
        }

        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
