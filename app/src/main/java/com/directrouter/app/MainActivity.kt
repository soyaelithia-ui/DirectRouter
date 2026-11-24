package com.directrouter.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.directrouter.app.data.AppThemeMode
import com.directrouter.app.data.RouterSettings
import com.directrouter.app.host.HostRouterService
import com.directrouter.app.host.ServiceStatus
import com.directrouter.app.ui.client.ClientVpnScreen
import com.directrouter.app.ui.components.BrandStatus
import com.directrouter.app.ui.components.DirectRouterBrandMark
import com.directrouter.app.ui.host.HostScreen
import com.directrouter.app.ui.settings.SettingsScreen
import com.directrouter.app.ui.theme.DirectRouterTheme
import com.directrouter.app.ui.theme.isDarkThemeActive
import com.directrouter.app.ui.theme.tr
import kotlinx.coroutines.launch

private enum class RouterTab(
    val icon: ImageVector,
) {
    HOST(Icons.Rounded.Router),
    CLIENT_VPN(Icons.Rounded.VpnKey),
    SETTINGS(Icons.Rounded.Settings),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel(
                factory = MainViewModel.factory(application),
            )
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val isDark = isDarkThemeActive(settings.themeMode)

            DisposableEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
                onDispose {}
            }

            DirectRouterTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                DirectRouterAppRoot(
                    viewModel = viewModel,
                    settings = settings,
                )
            }
        }
    }
}

private fun resolveBrandStatus(
    hostStatus: ServiceStatus,
    clientStatus: ServiceStatus,
): BrandStatus = when {
    hostStatus == ServiceStatus.RUNNING || clientStatus == ServiceStatus.RUNNING -> BrandStatus.ACTIVE
    hostStatus == ServiceStatus.STARTING || clientStatus == ServiceStatus.STARTING -> BrandStatus.STARTING
    hostStatus == ServiceStatus.ERROR || clientStatus == ServiceStatus.ERROR -> BrandStatus.ERROR
    else -> BrandStatus.IDLE
}

private fun openNotificationChannelSettings(
    context: Context,
    channelId: String,
) {
    HostRouterService.ensureNotificationChannels(context)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        }
        if (runCatching { context.startActivity(channelIntent) }.isSuccess) {
            return
        }
    }
    openAppNotificationSettings(context)
}

private fun openAppNotificationSettings(context: Context) {
    HostRouterService.ensureNotificationChannels(context)
    val appNotifIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    if (runCatching { context.startActivity(appNotifIntent) }.isSuccess) {
        return
    }
    val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    runCatching { context.startActivity(detailsIntent) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectRouterAppRoot(
    viewModel: MainViewModel,
    settings: RouterSettings,
) {
    val context = LocalContext.current
    val hostState by viewModel.hostState.collectAsStateWithLifecycle()
    val clientState by viewModel.clientState.collectAsStateWithLifecycle()
    val probeState by viewModel.probeState.collectAsStateWithLifecycle()

    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val selectedTab = RouterTab.entries[selectedTabIndex.coerceIn(0, RouterTab.entries.lastIndex)]

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    fun showMessage(message: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    val brandStatus = resolveBrandStatus(
        hostStatus = hostState.status,
        clientStatus = clientState.status,
    )
    val isHostRunning = hostState.status == ServiceStatus.RUNNING ||
        hostState.status == ServiceStatus.STARTING

    val themeToggleIcon = when (settings.themeMode) {
        AppThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
        AppThemeMode.LIGHT -> Icons.Rounded.LightMode
        AppThemeMode.DARK -> Icons.Rounded.DarkMode
        AppThemeMode.AMOLED -> Icons.Rounded.Contrast
    }
    val themeToggleDescription = when (settings.themeMode) {
        AppThemeMode.SYSTEM -> tr("Cambiar tema (actual: Sistema)", "Switch theme (current: System)")
        AppThemeMode.LIGHT -> tr("Cambiar tema (actual: Claro)", "Switch theme (current: Light)")
        AppThemeMode.DARK -> tr("Cambiar tema (actual: Oscuro)", "Switch theme (current: Dark)")
        AppThemeMode.AMOLED -> tr("Cambiar tema (actual: AMOLED)", "Switch theme (current: AMOLED)")
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DirectRouterBrandMark(
                            status = brandStatus,
                            size = 32.dp,
                            contentDescription = "DirectRouter",
                        )
                        Text(
                            text = when (selectedTab) {
                                RouterTab.HOST -> "DirectRouter"
                                RouterTab.CLIENT_VPN -> tr("Cliente VPN", "VPN Client")
                                RouterTab.SETTINGS -> tr("Ajustes", "Settings")
                            },
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val nextLabel = when (settings.themeMode) {
                                AppThemeMode.SYSTEM, AppThemeMode.LIGHT -> tr("Oscuro", "Dark")
                                AppThemeMode.DARK -> "AMOLED"
                                AppThemeMode.AMOLED -> tr("Claro", "Light")
                            }
                            viewModel.cycleThemeMode()
                            showMessage(tr("Tema: $nextLabel", "Theme: $nextLabel"))
                        },
                    ) {
                        Icon(
                            imageVector = themeToggleIcon,
                            contentDescription = themeToggleDescription,
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                RouterTab.entries.forEachIndexed { index, tab ->
                    val label = when (tab) {
                        RouterTab.HOST -> tr("Router", "Router")
                        RouterTab.CLIENT_VPN -> tr("Cliente VPN", "VPN Client")
                        RouterTab.SETTINGS -> tr("Ajustes", "Settings")
                    }
                    NavigationBarItem(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        icon = { Icon(imageVector = tab.icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                RouterTab.HOST -> HostScreen(
                    hostState = hostState,
                    settings = settings,
                    onStartRouter = { viewModel.startHostRouter(context) },
                    onStopRouter = { viewModel.stopHostRouter(context) },
                    onSelectShareMode = viewModel::updateHostShareMode,
                    onShowMessage = ::showMessage,
                    isBoundAllInterfaces = hostState.isBoundAllInterfaces,
                    boundAddress = hostState.boundAddress,
                    onRestartRouter = { viewModel.restartHostRouter(context) },
                    onUpdateProxyPort = viewModel::updateProxyPort,
                    onUpdateBindConfig = viewModel::updateBindConfig,
                    onGenerateRandomPort = viewModel::generateRandomProxyPort,
                    onOpenSettings = { selectedTabIndex = RouterTab.SETTINGS.ordinal },
                )

                RouterTab.CLIENT_VPN -> ClientVpnScreen(
                    clientState = clientState,
                    settings = settings,
                    probeState = probeState,
                    onConnectVpn = { ip, port -> viewModel.connectClientVpn(context, ip, port) },
                    onDisconnectVpn = { viewModel.disconnectClientVpn(context) },
                    onSaveEndpoint = viewModel::saveClientEndpoint,
                    onProbeConnection = viewModel::probeHostConnection,
                    onClearProbe = viewModel::clearProbeState,
                    onShowMessage = ::showMessage,
                    onUpdateClientAuth = viewModel::updateClientAuth,
                )

                RouterTab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onUpdateProxyPort = viewModel::updateProxyPort,
                    onUpdateSsidSuffix = viewModel::updateSsidSuffix,
                    onUpdatePassphrase = viewModel::updatePassphrase,
                    onRegeneratePassphrase = viewModel::regeneratePassphrase,
                    onUpdateWifiBand = viewModel::updateWifiBand,
                    onUpdateKeepCpuAwake = viewModel::updateKeepCpuAwake,
                    onShowMessage = ::showMessage,
                    isHostRunning = isHostRunning,
                    onRestartHost = { viewModel.restartHostRouter(context) },
                    onUpdateThemeMode = viewModel::updateThemeMode,
                    onUpdateDynamicColor = viewModel::updateDynamicColor,
                    onUpdateBindMode = viewModel::updateBindMode,
                    onUpdateCustomBindIp = viewModel::updateCustomBindIp,
                    onUpdateBindConfig = viewModel::updateBindConfig,
                    onGenerateRandomPort = viewModel::generateRandomProxyPort,
                    onUpdateBlockLocalhostAccess = viewModel::updateBlockLocalhostAccess,
                    onUpdateMaxConnections = viewModel::updateMaxConnections,
                    onUpdateProxyAuth = viewModel::updateProxyAuth,
                    onGenerateProxyAuthPassword = viewModel::generateProxyAuthPassword,
                    onUpdateNotifShowLiveSpeed = viewModel::updateNotifShowLiveSpeed,
                    onUpdateNotifPrivacyMode = viewModel::updateNotifPrivacyMode,
                    onUpdateNotifShowStopAction = viewModel::updateNotifShowStopAction,
                    onUpdateNotifClientAlerts = viewModel::updateNotifClientAlerts,
                    onUpdateNotifAlertSound = viewModel::updateNotifAlertSound,
                    onUpdateNotifAlertVibrate = viewModel::updateNotifAlertVibrate,
                    onOpenServiceChannelSettings = {
                        openNotificationChannelSettings(
                            context = context,
                            channelId = HostRouterService.CHANNEL_ID_SERVICE,
                        )
                    },
                    onOpenAlertsChannelSettings = {
                        val alertChannelId = HostRouterService.resolveAlertChannelId(
                            sound = settings.notifAlertSound,
                            vibrate = settings.notifAlertVibrate,
                        )
                        openNotificationChannelSettings(
                            context = context,
                            channelId = alertChannelId,
                        )
                    },
                    onOpenAppNotificationSettings = {
                        openAppNotificationSettings(context)
                    },
                )
            }
        }
    }
}
