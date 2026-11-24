package com.directrouter.app.data

enum class WifiBand {
    AUTO,
    BAND_2GHZ,
    BAND_5GHZ,
}

enum class HostShareMode {
    WIFI_DIRECT,
    NATIVE_TETHERING,
}

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED,
}

enum class BindMode {
    AUTO_LOCAL,
    SPECIFIC_IP,
    ALL_INTERFACES,
}

data class RouterSettings(
    val proxyPort: Int = 8080,
    val ssidSuffix: String = "DirectRouter",
    val passphrase: String = "Direct1234",
    val wifiBand: WifiBand = WifiBand.AUTO,
    val keepCpuAwake: Boolean = true,
    val clientHostIp: String = "192.168.49.1",
    val clientHostPort: Int = 8080,
    val hostShareMode: HostShareMode = HostShareMode.WIFI_DIRECT,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val bindMode: BindMode = BindMode.AUTO_LOCAL,
    val customBindIp: String = "",
    val blockLocalhostAccess: Boolean = true,
    val maxConnections: Int = 256,
    val proxyAuthEnabled: Boolean = false,
    val proxyAuthUsername: String = "router",
    val proxyAuthPassword: String = "",
    val clientAuthUsername: String = "",
    val clientAuthPassword: String = "",
    val notifShowLiveSpeed: Boolean = true,
    val notifPrivacyMode: Boolean = false,
    val notifShowStopAction: Boolean = true,
    val notifClientAlerts: Boolean = false,
    val notifAlertSound: Boolean = true,
    val notifAlertVibrate: Boolean = true,
) {
    val fullSsid: String
        get() = ValidationUtils.buildDirectSsid(ssidSuffix)
}
