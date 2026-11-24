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
            ?.let { raw -> WifiBand.entries.firstOrNull { it.name == raw } }
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

        return RouterSettings(
            proxyPort = port,
            ssidSuffix = ssidSuffix,
            passphrase = passphrase,
            wifiBand = wifiBand,
            keepCpuAwake = keepCpuAwake,
            clientHostIp = clientHostIp,
            clientHostPort = clientHostPort,
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
    }
}
