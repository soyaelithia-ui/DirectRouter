package com.directrouter.app.ui

import com.directrouter.app.data.AppThemeMode
import com.directrouter.app.data.BindMode
import com.directrouter.app.data.HostShareMode
import com.directrouter.app.data.RouterSettings
import com.directrouter.app.data.ValidationUtils
import com.directrouter.app.host.HostServiceState
import com.directrouter.app.ui.host.extractIpFromInterfaceEntry
import com.directrouter.app.ui.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterValidationTest {

    @Test
    fun buildDirectSsid_prependsPrefixAndSanitizes() {
        val normal = ValidationUtils.buildDirectSsid("MiRed")
        assertEquals("${ValidationUtils.SSID_PREFIX}MiRed", normal)

        val strippedExisting = ValidationUtils.buildDirectSsid("DIRECT-AB-HomeNet")
        assertTrue(strippedExisting.startsWith(ValidationUtils.SSID_PREFIX))
        assertFalse(strippedExisting.contains("DIRECT-AB-"))
        assertTrue(strippedExisting.endsWith("HomeNet"))

        val blankFallback = ValidationUtils.buildDirectSsid("   ")
        assertTrue(blankFallback.startsWith(ValidationUtils.SSID_PREFIX))
        assertTrue(blankFallback.length > ValidationUtils.SSID_PREFIX.length)

        val veryLong = ValidationUtils.buildDirectSsid("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_EXTRA")
        assertTrue(veryLong.startsWith(ValidationUtils.SSID_PREFIX))
        assertTrue(veryLong.toByteArray(Charsets.UTF_8).size <= 32)
    }

    @Test
    fun isValidPort_checksBoundaries() {
        assertFalse(ValidationUtils.isValidPort(-1))
        assertFalse(ValidationUtils.isValidPort(0))
        assertFalse(ValidationUtils.isValidPort(80))
        assertFalse(ValidationUtils.isValidPort(1023))
        assertTrue(ValidationUtils.isValidPort(1024))
        assertTrue(ValidationUtils.isValidPort(8080))
        assertTrue(ValidationUtils.isValidPort(65535))
        assertFalse(ValidationUtils.isValidPort(65536))
    }

    @Test
    fun generateRandomPort_staysWithinSafeRange() {
        val generated = mutableSetOf<Int>()
        repeat(200) {
            val port = ValidationUtils.generateRandomPort()
            assertTrue("Port out of range: $port", port in 10000..60000)
            assertTrue(ValidationUtils.isValidPort(port))
            generated.add(port)
        }
        assertTrue("Expected varied random ports", generated.size > 20)
    }

    @Test
    fun isValidMaxConnections_checksBoundaries() {
        assertFalse(ValidationUtils.isValidMaxConnections(0))
        assertFalse(ValidationUtils.isValidMaxConnections(15))
        assertTrue(ValidationUtils.isValidMaxConnections(16))
        assertTrue(ValidationUtils.isValidMaxConnections(256))
        assertTrue(ValidationUtils.isValidMaxConnections(512))
        assertFalse(ValidationUtils.isValidMaxConnections(513))
    }

    @Test
    fun isValidPassphrase_checksLengthAndAsciiPrintable() {
        assertFalse(ValidationUtils.isValidPassphrase(""))
        assertFalse(ValidationUtils.isValidPassphrase("1234567"))
        assertTrue(ValidationUtils.isValidPassphrase("12345678"))
        assertTrue(ValidationUtils.isValidPassphrase("DirectRouter#2026"))
        assertTrue(ValidationUtils.isValidPassphrase("a".repeat(63)))
        assertFalse(ValidationUtils.isValidPassphrase("a".repeat(64)))
        assertFalse(ValidationUtils.isValidPassphrase("bad\npassphrase"))
    }

    @Test
    fun isValidHostIp_checksIpAndHostnames() {
        assertTrue(ValidationUtils.isValidHostIp("192.168.49.1"))
        assertTrue(ValidationUtils.isValidHostIp("10.0.0.1"))
        assertTrue(ValidationUtils.isValidHostIp("router.local"))
        assertFalse(ValidationUtils.isValidHostIp(""))
        assertFalse(ValidationUtils.isValidHostIp("   "))
    }

    @Test
    fun isStrictIpv4_validatesExactFourDecimalOctets() {
        assertTrue(ValidationUtils.isStrictIpv4("0.0.0.0"))
        assertTrue(ValidationUtils.isStrictIpv4("127.0.0.1"))
        assertTrue(ValidationUtils.isStrictIpv4("192.168.49.1"))
        assertTrue(ValidationUtils.isStrictIpv4("255.255.255.255"))

        assertFalse(ValidationUtils.isStrictIpv4(""))
        assertFalse(ValidationUtils.isStrictIpv4(" 192.168.49.1"))
        assertFalse(ValidationUtils.isStrictIpv4("192.168.49.1 "))
        assertFalse(ValidationUtils.isStrictIpv4("192.168.49"))
        assertFalse(ValidationUtils.isStrictIpv4("192.168.49.1.5"))
        assertFalse(ValidationUtils.isStrictIpv4("192.168.49.256"))
        assertFalse(ValidationUtils.isStrictIpv4("192.168.01.1"))
        assertFalse(ValidationUtils.isStrictIpv4("192.168.-1.1"))
        assertFalse(ValidationUtils.isStrictIpv4("192.168.49.1:8080"))
        assertFalse(ValidationUtils.isStrictIpv4("router.local"))
        assertFalse(ValidationUtils.isStrictIpv4("192.168.49.١"))
    }

    @Test
    fun sanitizePacHost_acceptsValidIpv4AndIpv6AndNormalizes() {
        assertEquals("192.168.49.1", ValidationUtils.sanitizePacHost("192.168.49.1"))
        assertEquals("192.168.49.1:8080", ValidationUtils.sanitizePacHost("192.168.49.1:8080"))
        assertEquals("192.168.49.1:8080", ValidationUtils.sanitizePacHost("  192.168.49.1:8080  "))
        assertEquals("[::1]", ValidationUtils.sanitizePacHost("::1"))
        assertEquals("[::1]", ValidationUtils.sanitizePacHost("[::1]"))
        assertEquals("[::1]:8080", ValidationUtils.sanitizePacHost("[::1]:8080"))
        assertEquals("[2001:db8::1]:1080", ValidationUtils.sanitizePacHost("[2001:DB8::1]:1080"))
        assertEquals("[fe80::1]", ValidationUtils.sanitizePacHost("FE80::1"))
        assertEquals("192.168.49.1", ValidationUtils.sanitizePacHost("::ffff:192.168.49.1"))
        assertEquals("192.168.49.1:8080", ValidationUtils.sanitizePacHost("[::ffff:192.168.49.1]:8080"))
    }

    @Test
    fun sanitizePacHost_rejectsInjectionAndMalformedInputs() {
        assertNull(ValidationUtils.sanitizePacHost(""))
        assertNull(ValidationUtils.sanitizePacHost("   "))
        assertNull(ValidationUtils.sanitizePacHost("""192.168.49.1"; alert(1);//"""))
        assertNull(ValidationUtils.sanitizePacHost("""192.168.49.1:8080"; return "DIRECT";//"""))
        assertNull(ValidationUtils.sanitizePacHost("192.168.49.1\r\nX-Injected: 1"))
        assertNull(ValidationUtils.sanitizePacHost("192.168.49.1\nalert(1)"))
        assertNull(ValidationUtils.sanitizePacHost("192.168.49.1'"))
        assertNull(ValidationUtils.sanitizePacHost("192.168.49.1;"))
        assertNull(ValidationUtils.sanitizePacHost("router.local:8080"))
        assertNull(ValidationUtils.sanitizePacHost("192.168.49.1:0"))
        assertNull(ValidationUtils.sanitizePacHost("192.168.49.1:65536"))
        assertNull(ValidationUtils.sanitizePacHost("192.168.49.1:08080"))
        assertNull(ValidationUtils.sanitizePacHost("192.168.49.1:"))
        assertNull(ValidationUtils.sanitizePacHost("[192.168.49.1]:8080"))
        assertNull(ValidationUtils.sanitizePacHost("[::1]:99999"))
        assertNull(ValidationUtils.sanitizePacHost("fe80::1%wlan0"))
        assertNull(ValidationUtils.sanitizePacHost("1::2::3"))
        assertNull(ValidationUtils.sanitizePacHost("1:2:3:4:5:6:7:8:9"))
        assertNull(ValidationUtils.sanitizePacHost(":::"))
    }

    @Test
    fun isValidProxyAuthCredential_checksCharsetColonAndLength() {
        assertTrue(ValidationUtils.isValidProxyAuthCredential("router"))
        assertTrue(ValidationUtils.isValidProxyAuthCredential("User_123-!@#"))
        assertTrue(ValidationUtils.isValidProxyAuthCredential("a".repeat(64)))

        assertFalse(ValidationUtils.isValidProxyAuthCredential(""))
        assertFalse(ValidationUtils.isValidProxyAuthCredential("a".repeat(65)))
        assertFalse(ValidationUtils.isValidProxyAuthCredential("user:pass"))
        assertFalse(ValidationUtils.isValidProxyAuthCredential("has space"))
        assertFalse(ValidationUtils.isValidProxyAuthCredential("bad\nline"))
        assertFalse(ValidationUtils.isValidProxyAuthCredential("bad\rline"))
        assertFalse(ValidationUtils.isValidProxyAuthCredential("bad\ttab"))
        assertFalse(ValidationUtils.isValidProxyAuthCredential("café"))
    }

    @Test
    fun generateSecurePassword_producesSafeValidCredential() {
        val defaultPass = ValidationUtils.generateSecurePassword()
        assertEquals(12, defaultPass.length)
        assertTrue(ValidationUtils.isValidProxyAuthCredential(defaultPass))
        assertFalse(defaultPass.contains(':'))
        assertFalse(defaultPass.any { it.isWhitespace() || it == '"' || it == '\'' })

        val customPass = ValidationUtils.generateSecurePassword(20)
        assertEquals(20, customPass.length)
        assertTrue(ValidationUtils.isValidProxyAuthCredential(customPass))

        val clampedMin = ValidationUtils.generateSecurePassword(2)
        assertEquals(8, clampedMin.length)
        assertTrue(ValidationUtils.isValidProxyAuthCredential(clampedMin))

        val clampedMax = ValidationUtils.generateSecurePassword(100)
        assertEquals(64, clampedMax.length)
        assertTrue(ValidationUtils.isValidProxyAuthCredential(clampedMax))
    }

    @Test
    fun generateRandomPassphrase_producesValidPassphrase() {
        val pass = ValidationUtils.generateRandomPassphrase(12)
        assertEquals(12, pass.length)
        assertTrue(ValidationUtils.isValidPassphrase(pass))
    }

    @Test
    fun wifiQrEscapingAndPayload_matchesStandardFormat() {
        val escaped = ValidationUtils.escapeWifiQrField("""a\b;c,d"e:f""")
        assertEquals("""a\\b\;c\,d\"e\:f""", escaped)

        val payload = ValidationUtils.buildWifiQrPayload(
            ssid = "DIRECT-DR-My:Net",
            passphrase = "Pass;123,4",
        )
        assertEquals("""WIFI:T:WPA;S:DIRECT-DR-My\:Net;P:Pass\;123\,4;;""", payload)
    }

    @Test
    fun formatBytes_formatsAllUnitsCorrectly() {
        assertEquals("0 B", Formatters.formatBytes(-50L))
        assertEquals("0 B", Formatters.formatBytes(0L))
        assertEquals("512 B", Formatters.formatBytes(512L))
        assertEquals("1.0 KB", Formatters.formatBytes(1024L))
        assertEquals("1.5 KB", Formatters.formatBytes(1536L))
        assertEquals("1.00 MB", Formatters.formatBytes(1024L * 1024L))
        assertEquals("2.50 GB", Formatters.formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatSpeed_formatsAllUnitsCorrectly() {
        assertEquals("0 B/s", Formatters.formatSpeed(-10L))
        assertEquals("0 B/s", Formatters.formatSpeed(0L))
        assertEquals("800 B/s", Formatters.formatSpeed(800L))
        assertEquals("2.0 KB/s", Formatters.formatSpeed(2048L))
        assertEquals("3.50 MB/s", Formatters.formatSpeed((3.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatDuration_formatsHoursMinutesSeconds() {
        assertEquals("00:00:00", Formatters.formatDuration(-100L))
        assertEquals("00:00:00", Formatters.formatDuration(0L))
        assertEquals("00:00:45", Formatters.formatDuration(45_000L))
        assertEquals("00:05:09", Formatters.formatDuration(309_000L))
        assertEquals("01:02:03", Formatters.formatDuration(3_723_000L))
        assertEquals("25:00:00", Formatters.formatDuration(90_000_000L))
    }

    @Test
    fun routerSettings_hasSafeV12DefaultsAndEnums() {
        assertEquals(
            listOf(AppThemeMode.SYSTEM, AppThemeMode.LIGHT, AppThemeMode.DARK, AppThemeMode.AMOLED),
            AppThemeMode.entries,
        )
        assertEquals(
            listOf(BindMode.AUTO_LOCAL, BindMode.SPECIFIC_IP, BindMode.ALL_INTERFACES),
            BindMode.entries,
        )

        val settings = RouterSettings()
        assertEquals(HostShareMode.WIFI_DIRECT, settings.hostShareMode)
        assertEquals(AppThemeMode.SYSTEM, settings.themeMode)
        assertFalse(settings.dynamicColor)
        assertEquals(BindMode.AUTO_LOCAL, settings.bindMode)
        assertEquals("", settings.customBindIp)
        assertTrue(settings.blockLocalhostAccess)
        assertEquals(256, settings.maxConnections)
        assertFalse(settings.proxyAuthEnabled)
        assertEquals("router", settings.proxyAuthUsername)
        assertEquals("", settings.proxyAuthPassword)
        assertEquals("", settings.clientAuthUsername)
        assertEquals("", settings.clientAuthPassword)
        assertTrue(settings.notifShowLiveSpeed)
        assertFalse(settings.notifPrivacyMode)
        assertTrue(settings.notifShowStopAction)
        assertFalse(settings.notifClientAlerts)
        assertTrue(settings.notifAlertSound)
        assertTrue(settings.notifAlertVibrate)
    }

    @Test
    fun hostServiceState_buildsPacUrlFromIpAndPort() {
        val defaultState = HostServiceState()
        assertEquals("http://192.168.49.1:8080/proxy.pac", defaultState.pacUrl)

        val tetherState = HostServiceState(
            shareMode = HostShareMode.NATIVE_TETHERING,
            ipAddress = "192.168.43.1",
            port = 9090,
        )
        assertEquals("http://192.168.43.1:9090/proxy.pac", tetherState.pacUrl)
    }

    @Test
    fun extractIpFromInterfaceEntry_parsesLabeledAndPlainEntries() {
        assertEquals("192.168.43.1", extractIpFromInterfaceEntry("192.168.43.1 (ap0)"))
        assertEquals("192.168.42.129", extractIpFromInterfaceEntry("192.168.42.129 (rndis0)"))
        assertEquals("10.0.0.1", extractIpFromInterfaceEntry("  10.0.0.1  "))
    }
}
