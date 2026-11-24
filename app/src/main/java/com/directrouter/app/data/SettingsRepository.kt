package com.directrouter.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

private val Context.routerSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "directrouter_settings"
)

class SettingsRepository(context: Context) {

    private val dataStore: DataStore<Preferences> =
        context.applicationContext.routerSettingsDataStore
    private val initMutex = Mutex()

    val settingsFlow: Flow<RouterSettings> = dataStore.data
        .onStart { ensurePassphraseInitialized() }
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs -> prefs.toRouterSettings() }

    suspend fun getCurrentSettings(): RouterSettings = settingsFlow.first()

    suspend fun updateProxyPort(port: Int) {
        if (!ValidationUtils.isValidPort(port)) return
        dataStore.edit { prefs ->
            prefs[KEY_PROXY_PORT] = port
        }
    }

    suspend fun updateSsidSuffix(suffix: String) {
        val sanitized = ValidationUtils.sanitizeSsidSuffix(suffix)
        dataStore.edit { prefs ->
            prefs[KEY_SSID_SUFFIX] = sanitized
        }
    }

    suspend fun updatePassphrase(passphrase: String) {
        if (!ValidationUtils.isValidPassphrase(passphrase)) return
        dataStore.edit { prefs ->
            prefs[KEY_PASSPHRASE] = passphrase
        }
    }

    suspend fun updateWifiBand(band: WifiBand) {
        dataStore.edit { prefs ->
            prefs[KEY_WIFI_BAND] = band.name
        }
    }

    suspend fun updateKeepCpuAwake(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_KEEP_CPU_AWAKE] = enabled
        }
    }

    suspend fun updateHostShareMode(mode: HostShareMode) {
        dataStore.edit { prefs ->
            prefs[KEY_HOST_SHARE_MODE] = mode.name
        }
    }

    suspend fun updateClientEndpoint(ip: String, port: Int) {
        val trimmedIp = ip.trim()
        if (!ValidationUtils.isValidHostIp(trimmedIp) || !ValidationUtils.isValidPort(port)) {
            return
        }
        dataStore.edit { prefs ->
            prefs[KEY_CLIENT_HOST_IP] = trimmedIp
            prefs[KEY_CLIENT_HOST_PORT] = port
        }
    }

    suspend fun updateThemeMode(mode: AppThemeMode) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    suspend fun updateDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun updateBindMode(mode: BindMode) {
        dataStore.edit { prefs ->
            prefs[KEY_BIND_MODE] = mode.name
        }
    }

    suspend fun updateCustomBindIp(ip: String) {
        val trimmed = ip.trim()
        if (trimmed.isNotEmpty() && !ValidationUtils.isStrictIpv4(trimmed)) {
            return
        }
        dataStore.edit { prefs ->
            prefs[KEY_CUSTOM_BIND_IP] = trimmed
        }
    }

    suspend fun updateBindConfig(mode: BindMode, customIp: String) {
        val trimmed = customIp.trim()
        if (trimmed.isNotEmpty() && !ValidationUtils.isStrictIpv4(trimmed)) {
            return
        }
        dataStore.edit { prefs ->
            prefs[KEY_BIND_MODE] = mode.name
            prefs[KEY_CUSTOM_BIND_IP] = trimmed
        }
    }

    suspend fun updateBlockLocalhostAccess(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_BLOCK_LOCALHOST_ACCESS] = enabled
        }
    }

    suspend fun updateMaxConnections(maxConnections: Int) {
        val clamped = maxConnections.coerceIn(
            ValidationUtils.MIN_MAX_CONNECTIONS,
            ValidationUtils.MAX_MAX_CONNECTIONS,
        )
        dataStore.edit { prefs ->
            prefs[KEY_MAX_CONNECTIONS] = clamped
        }
    }

    suspend fun updateProxyAuthEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_PROXY_AUTH_ENABLED] = enabled
        }
    }

    suspend fun updateProxyAuthUsername(username: String) {
        val trimmed = username.trim()
        if (!ValidationUtils.isValidProxyAuthCredential(trimmed)) return
        dataStore.edit { prefs ->
            prefs[KEY_PROXY_AUTH_USERNAME] = trimmed
        }
    }

    suspend fun updateProxyAuthPassword(password: String) {
        val trimmed = password.trim()
        if (trimmed.isNotEmpty() && !ValidationUtils.isValidProxyAuthCredential(trimmed)) return
        dataStore.edit { prefs ->
            prefs[KEY_PROXY_AUTH_PASSWORD] = trimmed
        }
    }

    suspend fun updateProxyAuth(enabled: Boolean, username: String, password: String) {
        val trimmedUser = username.trim()
        val trimmedPass = password.trim()
        if (!ValidationUtils.isValidProxyAuthCredential(trimmedUser)) return
        if (enabled && !ValidationUtils.isValidProxyAuthCredential(trimmedPass)) return
        if (trimmedPass.isNotEmpty() && !ValidationUtils.isValidProxyAuthCredential(trimmedPass)) return
        dataStore.edit { prefs ->
            prefs[KEY_PROXY_AUTH_ENABLED] = enabled
            prefs[KEY_PROXY_AUTH_USERNAME] = trimmedUser
            prefs[KEY_PROXY_AUTH_PASSWORD] = trimmedPass
        }
    }

    suspend fun updateClientAuthUsername(username: String) {
        val trimmed = username.trim()
        if (trimmed.isNotEmpty() && !ValidationUtils.isValidProxyAuthCredential(trimmed)) return
        dataStore.edit { prefs ->
            prefs[KEY_CLIENT_AUTH_USERNAME] = trimmed
        }
    }

    suspend fun updateClientAuthPassword(password: String) {
        val trimmed = password.trim()
        if (trimmed.isNotEmpty() && !ValidationUtils.isValidProxyAuthCredential(trimmed)) return
        dataStore.edit { prefs ->
            prefs[KEY_CLIENT_AUTH_PASSWORD] = trimmed
        }
    }

    suspend fun updateClientAuth(username: String, password: String) {
        val trimmedUser = username.trim()
        val trimmedPass = password.trim()
        if (trimmedUser.isNotEmpty() && !ValidationUtils.isValidProxyAuthCredential(trimmedUser)) return
        if (trimmedPass.isNotEmpty() && !ValidationUtils.isValidProxyAuthCredential(trimmedPass)) return
        dataStore.edit { prefs ->
            prefs[KEY_CLIENT_AUTH_USERNAME] = trimmedUser
            prefs[KEY_CLIENT_AUTH_PASSWORD] = trimmedPass
        }
    }

    suspend fun updateNotifShowLiveSpeed(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIF_SHOW_LIVE_SPEED] = enabled
        }
    }

    suspend fun updateNotifPrivacyMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIF_PRIVACY_MODE] = enabled
        }
    }

    suspend fun updateNotifShowStopAction(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIF_SHOW_STOP_ACTION] = enabled
        }
    }

    suspend fun updateNotifClientAlerts(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIF_CLIENT_ALERTS] = enabled
        }
    }

    suspend fun updateNotifAlertSound(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIF_ALERT_SOUND] = enabled
        }
    }

    suspend fun updateNotifAlertVibrate(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIF_ALERT_VIBRATE] = enabled
        }
    }

    suspend fun updateNotificationSettings(
        showLiveSpeed: Boolean,
        privacyMode: Boolean,
        showStopAction: Boolean,
        clientAlerts: Boolean,
        alertSound: Boolean,
        alertVibrate: Boolean,
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIF_SHOW_LIVE_SPEED] = showLiveSpeed
            prefs[KEY_NOTIF_PRIVACY_MODE] = privacyMode
            prefs[KEY_NOTIF_SHOW_STOP_ACTION] = showStopAction
            prefs[KEY_NOTIF_CLIENT_ALERTS] = clientAlerts
            prefs[KEY_NOTIF_ALERT_SOUND] = alertSound
            prefs[KEY_NOTIF_ALERT_VIBRATE] = alertVibrate
        }
    }

    private suspend fun ensurePassphraseInitialized() {
        initMutex.withLock {
            try {
                dataStore.edit { prefs ->
                    val current = prefs[KEY_PASSPHRASE]
                    if (current.isNullOrBlank() || !ValidationUtils.isValidPassphrase(current)) {
                        prefs[KEY_PASSPHRASE] = ValidationUtils.generateRandomPassphrase(10)
                    }
                }
            } catch (_: IOException) {
                // Fallback to defaults on storage read/write error
            }
        }
    }

    private fun Preferences.toRouterSettings(): RouterSettings {
        val defaults = RouterSettings()
        val storedPort = this[KEY_PROXY_PORT]
        val port = if (storedPort != null && ValidationUtils.isValidPort(storedPort)) {
            storedPort
        } else {
            defaults.proxyPort
        }

        val ssidSuffix = this[KEY_SSID_SUFFIX]
            ?.takeIf { it.isNotBlank() }
            ?.let { ValidationUtils.sanitizeSsidSuffix(it) }
            ?: defaults.ssidSuffix

        val storedPassphrase = this[KEY_PASSPHRASE]
        val passphrase = if (
            storedPassphrase != null && ValidationUtils.isValidPassphrase(storedPassphrase)
        ) {
            storedPassphrase
        } else {
            defaults.passphrase
        }

        val wifiBand = this[KEY_WIFI_BAND]
            ?.let { raw -> runCatching { WifiBand.valueOf(raw) }.getOrDefault(defaults.wifiBand) }
            ?: defaults.wifiBand

        val keepCpuAwake = this[KEY_KEEP_CPU_AWAKE] ?: defaults.keepCpuAwake

        val storedClientIp = this[KEY_CLIENT_HOST_IP]
        val clientHostIp = if (
            storedClientIp != null && ValidationUtils.isValidHostIp(storedClientIp)
        ) {
            storedClientIp
        } else {
            defaults.clientHostIp
        }

        val storedClientPort = this[KEY_CLIENT_HOST_PORT]
        val clientHostPort = if (
            storedClientPort != null && ValidationUtils.isValidPort(storedClientPort)
        ) {
            storedClientPort
        } else {
            defaults.clientHostPort
        }

        val hostShareMode = this[KEY_HOST_SHARE_MODE]
            ?.let { raw ->
                runCatching { HostShareMode.valueOf(raw) }.getOrDefault(defaults.hostShareMode)
            }
            ?: defaults.hostShareMode

        val themeMode = this[KEY_THEME_MODE]
            ?.let { raw ->
                runCatching { AppThemeMode.valueOf(raw) }.getOrDefault(defaults.themeMode)
            }
            ?: defaults.themeMode

        val dynamicColor = this[KEY_DYNAMIC_COLOR] ?: defaults.dynamicColor

        val bindMode = this[KEY_BIND_MODE]
            ?.let { raw ->
                runCatching { BindMode.valueOf(raw) }.getOrDefault(defaults.bindMode)
            }
            ?: defaults.bindMode

        val storedCustomBindIp = this[KEY_CUSTOM_BIND_IP]?.trim()
        val customBindIp = when {
            storedCustomBindIp.isNullOrEmpty() -> defaults.customBindIp
            ValidationUtils.isStrictIpv4(storedCustomBindIp) -> storedCustomBindIp
            else -> defaults.customBindIp
        }

        val blockLocalhostAccess =
            this[KEY_BLOCK_LOCALHOST_ACCESS] ?: defaults.blockLocalhostAccess

        val maxConnections = (this[KEY_MAX_CONNECTIONS] ?: defaults.maxConnections)
            .coerceIn(ValidationUtils.MIN_MAX_CONNECTIONS, ValidationUtils.MAX_MAX_CONNECTIONS)

        val proxyAuthEnabled = this[KEY_PROXY_AUTH_ENABLED] ?: defaults.proxyAuthEnabled

        val storedProxyUser = this[KEY_PROXY_AUTH_USERNAME]?.trim()
        val proxyAuthUsername = if (
            storedProxyUser != null && ValidationUtils.isValidProxyAuthCredential(storedProxyUser)
        ) {
            storedProxyUser
        } else {
            defaults.proxyAuthUsername
        }

        val storedProxyPass = this[KEY_PROXY_AUTH_PASSWORD]?.trim()
        val proxyAuthPassword = when {
            storedProxyPass.isNullOrEmpty() -> defaults.proxyAuthPassword
            ValidationUtils.isValidProxyAuthCredential(storedProxyPass) -> storedProxyPass
            else -> defaults.proxyAuthPassword
        }

        val storedClientUser = this[KEY_CLIENT_AUTH_USERNAME]?.trim()
        val clientAuthUsername = when {
            storedClientUser.isNullOrEmpty() -> defaults.clientAuthUsername
            ValidationUtils.isValidProxyAuthCredential(storedClientUser) -> storedClientUser
            else -> defaults.clientAuthUsername
        }

        val storedClientPass = this[KEY_CLIENT_AUTH_PASSWORD]?.trim()
        val clientAuthPassword = when {
            storedClientPass.isNullOrEmpty() -> defaults.clientAuthPassword
            ValidationUtils.isValidProxyAuthCredential(storedClientPass) -> storedClientPass
            else -> defaults.clientAuthPassword
        }

        val notifShowLiveSpeed = this[KEY_NOTIF_SHOW_LIVE_SPEED] ?: defaults.notifShowLiveSpeed
        val notifPrivacyMode = this[KEY_NOTIF_PRIVACY_MODE] ?: defaults.notifPrivacyMode
        val notifShowStopAction = this[KEY_NOTIF_SHOW_STOP_ACTION] ?: defaults.notifShowStopAction
        val notifClientAlerts = this[KEY_NOTIF_CLIENT_ALERTS] ?: defaults.notifClientAlerts
        val notifAlertSound = this[KEY_NOTIF_ALERT_SOUND] ?: defaults.notifAlertSound
        val notifAlertVibrate = this[KEY_NOTIF_ALERT_VIBRATE] ?: defaults.notifAlertVibrate

        return RouterSettings(
            proxyPort = port,
            ssidSuffix = ssidSuffix,
            passphrase = passphrase,
            wifiBand = wifiBand,
            keepCpuAwake = keepCpuAwake,
            clientHostIp = clientHostIp,
            clientHostPort = clientHostPort,
            hostShareMode = hostShareMode,
            themeMode = themeMode,
            dynamicColor = dynamicColor,
            bindMode = bindMode,
            customBindIp = customBindIp,
            blockLocalhostAccess = blockLocalhostAccess,
            maxConnections = maxConnections,
            proxyAuthEnabled = proxyAuthEnabled,
            proxyAuthUsername = proxyAuthUsername,
            proxyAuthPassword = proxyAuthPassword,
            clientAuthUsername = clientAuthUsername,
            clientAuthPassword = clientAuthPassword,
            notifShowLiveSpeed = notifShowLiveSpeed,
            notifPrivacyMode = notifPrivacyMode,
            notifShowStopAction = notifShowStopAction,
            notifClientAlerts = notifClientAlerts,
            notifAlertSound = notifAlertSound,
            notifAlertVibrate = notifAlertVibrate,
        )
    }

    private companion object {
        val KEY_PROXY_PORT = intPreferencesKey("proxy_port")
        val KEY_SSID_SUFFIX = stringPreferencesKey("ssid_suffix")
        val KEY_PASSPHRASE = stringPreferencesKey("passphrase")
        val KEY_WIFI_BAND = stringPreferencesKey("wifi_band")
        val KEY_KEEP_CPU_AWAKE = booleanPreferencesKey("keep_cpu_awake")
        val KEY_CLIENT_HOST_IP = stringPreferencesKey("client_host_ip")
        val KEY_CLIENT_HOST_PORT = intPreferencesKey("client_host_port")
        val KEY_HOST_SHARE_MODE = stringPreferencesKey("host_share_mode")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_BIND_MODE = stringPreferencesKey("bind_mode")
        val KEY_CUSTOM_BIND_IP = stringPreferencesKey("custom_bind_ip")
        val KEY_BLOCK_LOCALHOST_ACCESS = booleanPreferencesKey("block_localhost_access")
        val KEY_MAX_CONNECTIONS = intPreferencesKey("max_connections")
        val KEY_PROXY_AUTH_ENABLED = booleanPreferencesKey("proxy_auth_enabled")
        val KEY_PROXY_AUTH_USERNAME = stringPreferencesKey("proxy_auth_username")
        val KEY_PROXY_AUTH_PASSWORD = stringPreferencesKey("proxy_auth_password")
        val KEY_CLIENT_AUTH_USERNAME = stringPreferencesKey("client_auth_username")
        val KEY_CLIENT_AUTH_PASSWORD = stringPreferencesKey("client_auth_password")
        val KEY_NOTIF_SHOW_LIVE_SPEED = booleanPreferencesKey("notif_show_live_speed")
        val KEY_NOTIF_PRIVACY_MODE = booleanPreferencesKey("notif_privacy_mode")
        val KEY_NOTIF_SHOW_STOP_ACTION = booleanPreferencesKey("notif_show_stop_action")
        val KEY_NOTIF_CLIENT_ALERTS = booleanPreferencesKey("notif_client_alerts")
        val KEY_NOTIF_ALERT_SOUND = booleanPreferencesKey("notif_alert_sound")
        val KEY_NOTIF_ALERT_VIBRATE = booleanPreferencesKey("notif_alert_vibrate")
    }
}
