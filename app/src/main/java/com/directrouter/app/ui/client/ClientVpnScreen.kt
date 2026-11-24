package com.directrouter.app.ui.client

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.directrouter.app.ConnectionProbeState
import com.directrouter.app.client.ClientVpnState
import com.directrouter.app.data.RouterSettings
import com.directrouter.app.data.ValidationUtils
import com.directrouter.app.host.ServiceStatus
import com.directrouter.app.ui.theme.RouterStatusColors
import com.directrouter.app.ui.theme.tr
import com.directrouter.app.ui.util.Formatters
import kotlinx.coroutines.delay

@Composable
fun ClientVpnScreen(
    clientState: ClientVpnState,
    settings: RouterSettings,
    probeState: ConnectionProbeState,
    onConnectVpn: (hostIp: String, hostPort: Int) -> Unit,
    onDisconnectVpn: () -> Unit,
    onSaveEndpoint: (hostIp: String, hostPort: Int) -> Boolean,
    onProbeConnection: (hostIp: String, hostPort: Int) -> Unit,
    onClearProbe: () -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val context = LocalContext.current

    var hostIpInput by rememberSaveable { mutableStateOf(settings.clientHostIp) }
    var hostPortInput by rememberSaveable { mutableStateOf(settings.clientHostPort.toString()) }

    LaunchedEffect(settings.clientHostIp) {
        hostIpInput = settings.clientHostIp
    }
    LaunchedEffect(settings.clientHostPort) {
        hostPortInput = settings.clientHostPort.toString()
    }

    val parsedPort = hostPortInput.trim().toIntOrNull()
    val ipValid = ValidationUtils.isValidHostIp(hostIpInput.trim())
    val portValid = parsedPort != null && ValidationUtils.isValidPort(parsedPort)

    val isRunning = clientState.status == ServiceStatus.RUNNING
    val isStarting = clientState.status == ServiceStatus.STARTING
    val isError = clientState.status == ServiceStatus.ERROR

    var pendingConnectEndpoint by remember { mutableStateOf<Pair<String, Int>?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val endpoint = pendingConnectEndpoint
        pendingConnectEndpoint = null
        if (result.resultCode == Activity.RESULT_OK && endpoint != null) {
            onConnectVpn(endpoint.first, endpoint.second)
        } else if (result.resultCode != Activity.RESULT_OK) {
            onShowMessage(
                tr(
                    "Permiso de VPN cancelado por el usuario.",
                    "VPN permission was cancelled by the user.",
                ),
            )
        }
    }

    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRunning, clientState.startedAtEpochMs) {
        if (isRunning && clientState.startedAtEpochMs > 0L) {
            while (true) {
                nowEpochMs = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    val uptimeMs = if (isRunning && clientState.startedAtEpochMs > 0L) {
        (nowEpochMs - clientState.startedAtEpochMs).coerceAtLeast(0L)
    } else {
        0L
    }

    fun triggerVpnToggle() {
        if (isRunning || isStarting) {
            onDisconnectVpn()
            return
        }
        val cleanIp = hostIpInput.trim()
        val port = parsedPort
        if (!ipValid || port == null || !portValid) {
            onShowMessage(
                tr(
                    "Verifica la IP del Host y el Puerto (1024–65535).",
                    "Check Host IP and Port (1024–65535).",
                ),
            )
            return
        }
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            pendingConnectEndpoint = cleanIp to port
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            onConnectVpn(cleanIp, port)
        }
    }

    val accentColor by animateColorAsState(
        targetValue = when (clientState.status) {
            ServiceStatus.RUNNING -> RouterStatusColors.ActiveEmerald
            ServiceStatus.STARTING -> RouterStatusColors.WarningAmber
            ServiceStatus.ERROR -> RouterStatusColors.ErrorCoral
            ServiceStatus.STOPPED -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(300),
        label = "vpnAccent",
    )

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "vpn_header") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = tr("Modo Cliente VPN (2.º teléfono opcional)", "VPN Client Mode (Optional 2nd Phone)"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = tr(
                                "Enruta TODO el tráfico del dispositivo (TCP, UDP y DNS) mediante hev-socks5-tunnel hacia el router DirectRouter, sin configurar proxy manual. Ideal para juegos, llamadas VoIP y mensajería.",
                                "Routes ALL device traffic (TCP, UDP & DNS) via hev-socks5-tunnel through the DirectRouter host without manual Wi-Fi proxy setup. Ideal for gaming, VoIP, and messaging.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item(key = "vpn_toggle_card") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, accentColor.copy(alpha = if (isRunning) 0.5f else 0.2f)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = tr("Túnel SOCKS5", "SOCKS5 Tunnel"),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = when (clientState.status) {
                                    ServiceStatus.RUNNING -> "${clientState.hostIp}:${clientState.hostPort}"
                                    ServiceStatus.STARTING -> tr("Estableciendo túnel...", "Establishing tunnel...")
                                    ServiceStatus.STOPPED -> tr("Desconectado", "Disconnected")
                                    ServiceStatus.ERROR -> tr("Error de conexión", "Connection error")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = accentColor.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(accentColor),
                                )
                                Text(
                                    text = when (clientState.status) {
                                        ServiceStatus.RUNNING -> tr("Conectado", "Connected")
                                        ServiceStatus.STARTING -> tr("Conectando", "Connecting")
                                        ServiceStatus.ERROR -> tr("Error", "Error")
                                        ServiceStatus.STOPPED -> tr("Inactivo", "Idle")
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = accentColor,
                                )
                            }
                        }
                    }

                    Button(
                        onClick = ::triggerVpnToggle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning || isStarting) {
                                RouterStatusColors.ErrorCoral
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            contentColor = if (isRunning || isStarting) {
                                Color.White
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                        ),
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.5.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(tr("Conectando VPN...", "Connecting VPN..."))
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = if (isRunning) {
                                    tr("Desconectar VPN", "Disconnect VPN")
                                } else {
                                    tr("Conectar VPN", "Connect VPN")
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    AnimatedVisibility(visible = isError && !clientState.errorMessage.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
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
                                    text = clientState.errorMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "endpoint_config_card") {
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
                        text = tr("Servidor Host DirectRouter", "DirectRouter Host Server"),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = hostIpInput,
                            onValueChange = {
                                hostIpInput = it
                                onClearProbe()
                            },
                            label = { Text(tr("IP del Host", "Host IP")) },
                            singleLine = true,
                            enabled = !isRunning && !isStarting,
                            isError = hostIpInput.isNotEmpty() && !ipValid,
                            modifier = Modifier.weight(1.45f),
                        )

                        OutlinedTextField(
                            value = hostPortInput,
                            onValueChange = { value ->
                                hostPortInput = value.filter { it.isDigit() }.take(5)
                                onClearProbe()
                            },
                            label = { Text(tr("Puerto", "Port")) },
                            singleLine = true,
                            enabled = !isRunning && !isStarting,
                            isError = hostPortInput.isNotEmpty() && !portValid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.95f),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val port = parsedPort
                                if (ipValid && port != null && portValid) {
                                    onProbeConnection(hostIpInput.trim(), port)
                                } else {
                                    onShowMessage(
                                        tr(
                                            "Introduce una IP y puerto válidos.",
                                            "Enter a valid IP and port.",
                                        ),
                                    )
                                }
                            },
                            enabled = probeState !is ConnectionProbeState.Probing,
                            modifier = Modifier.weight(1.2f),
                        ) {
                            if (probeState is ConnectionProbeState.Probing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.NetworkCheck,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Probar conexión", "Test Connection"))
                        }

                        OutlinedButton(
                            onClick = {
                                val port = parsedPort
                                if (port != null && onSaveEndpoint(hostIpInput.trim(), port)) {
                                    onShowMessage(tr("Servidor guardado", "Host endpoint saved"))
                                } else {
                                    onShowMessage(tr("IP o puerto inválido", "Invalid IP or port"))
                                }
                            },
                            enabled = !isRunning && !isStarting && ipValid && portValid,
                            modifier = Modifier.weight(0.8f),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Guardar", "Save"))
                        }
                    }

                    ProbeResultBanner(probeState = probeState)
                }
            }
        }

        item(key = "vpn_stats_card") {
            VpnTunnelStatsCard(
                clientState = clientState,
                uptimeMs = uptimeMs,
            )
        }

        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ProbeResultBanner(probeState: ConnectionProbeState) {
    when (probeState) {
        ConnectionProbeState.Idle -> Unit
        ConnectionProbeState.Probing -> {
            Text(
                text = tr("Probando alcance TCP al proxy...", "Probing TCP reachability to proxy..."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is ConnectionProbeState.Success -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = RouterStatusColors.ActiveEmerald.copy(alpha = 0.14f),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = RouterStatusColors.ActiveEmerald,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = tr(
                            "Proxy alcanzable (${probeState.latencyMs} ms)",
                            "Proxy reachable (${probeState.latencyMs} ms)",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = RouterStatusColors.ActiveEmerald,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        is ConnectionProbeState.Failure -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = tr(
                            "Sin respuesta del Host: ${probeState.message}",
                            "Host unreachable: ${probeState.message}",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun VpnTunnelStatsCard(
    clientState: ClientVpnState,
    uptimeMs: Long,
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr("Estado del Túnel VPN", "VPN Tunnel Status"),
                    style = MaterialTheme.typography.titleMedium,
                )
                val wifiBadgeColor = if (clientState.wifiConnected) {
                    RouterStatusColors.ActiveEmerald
                } else {
                    RouterStatusColors.WarningAmber
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = wifiBadgeColor.copy(alpha = 0.14f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = if (clientState.wifiConnected) {
                                Icons.Rounded.Wifi
                            } else {
                                Icons.Rounded.WifiOff
                            },
                            contentDescription = null,
                            tint = wifiBadgeColor,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = clientState.wifiNetworkLabel.ifBlank {
                                if (clientState.wifiConnected) {
                                    tr("Wi-Fi listo", "Wi-Fi ready")
                                } else {
                                    tr("Sin Wi-Fi", "No Wi-Fi")
                                }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = wifiBadgeColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
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
                                imageVector = Icons.Rounded.ArrowDownward,
                                contentDescription = null,
                                tint = RouterStatusColors.ActiveEmerald,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = tr("Recibido (RX)", "Received (RX)"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = Formatters.formatBytes(clientState.rxBytes),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = tr("${clientState.rxPackets} paquetes", "${clientState.rxPackets} packets"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f),
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
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = tr("Enviado (TX)", "Sent (TX)"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = Formatters.formatBytes(clientState.txBytes),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = tr("${clientState.txPackets} paquetes", "${clientState.txPackets} packets"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
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
                        "Tiempo conectado: ${Formatters.formatDuration(uptimeMs)}",
                        "Connected uptime: ${Formatters.formatDuration(uptimeMs)}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
