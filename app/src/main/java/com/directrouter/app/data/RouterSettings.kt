package com.directrouter.app.data

enum class WifiBand {
    AUTO,
    BAND_2GHZ,
    BAND_5GHZ,
}

data class RouterSettings(
    val proxyPort: Int = 8080,
    val ssidSuffix: String = "DirectRouter",
    val passphrase: String = "Direct1234",
    val wifiBand: WifiBand = WifiBand.AUTO,
    val keepCpuAwake: Boolean = true,
    val clientHostIp: String = "192.168.49.1",
    val clientHostPort: Int = 8080,
) {
    val fullSsid: String
        get() = ValidationUtils.buildDirectSsid(ssidSuffix)
}
