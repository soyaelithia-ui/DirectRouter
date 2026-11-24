package com.directrouter.app.ui

import com.directrouter.app.data.HostShareMode
import com.directrouter.app.data.RouterSettings
import com.directrouter.app.data.ValidationUtils
import com.directrouter.app.host.HostServiceState
import com.directrouter.app.ui.host.extractIpFromInterfaceEntry
import com.directrouter.app.ui.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun routerSettings_defaultsToWifiDirectShareMode() {
        val settings = RouterSettings()
        assertEquals(HostShareMode.WIFI_DIRECT, settings.hostShareMode)
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
