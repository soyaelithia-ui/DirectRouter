package com.directrouter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.directrouter.app.ui.client.ClientVpnScreen
import com.directrouter.app.ui.host.HostScreen
import com.directrouter.app.ui.settings.SettingsScreen
import com.directrouter.app.ui.theme.DirectRouterTheme
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
            DirectRouterTheme {
                val viewModel: MainViewModel = viewModel(
                    factory = MainViewModel.factory(application),
                )
                DirectRouterAppRoot(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectRouterAppRoot(
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (selectedTab) {
                            RouterTab.HOST -> "DirectRouter"
                            RouterTab.CLIENT_VPN -> tr("Cliente VPN", "VPN Client")
                            RouterTab.SETTINGS -> tr("Ajustes", "Settings")
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
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
                    onShowMessage = ::showMessage,
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
                )
            }
        }
    }
}
