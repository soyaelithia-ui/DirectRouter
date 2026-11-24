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

data class RouterSettings(
    val proxyPort: Int = 8080,
    val ssidSuffix: String = "DirectRouter",
    val passphrase: String = "Direct1234",
    val wifiBand: WifiBand = WifiBand.AUTO,
    val keepCpuAwake: Boolean = true,
    val clientHostIp: String = "192.168.49.1",
    val clientHostPort: Int = 8080,
    val hostShareMode: HostShareMode = HostShareMode.WIFI_DIRECT,
) {
    val fullSsid: String
        get() = ValidationUtils.buildDirectSsid(ssidSuffix)
}
