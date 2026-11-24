package com.directrouter.app.data

import java.security.SecureRandom

object ValidationUtils {
    const val SSID_PREFIX = "DIRECT-DR-"
    private const val MAX_SSID_UTF8_BYTES = 32
    private const val DEFAULT_SSID_SUFFIX = "Router"
    private const val PASSPHRASE_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    private val DIRECT_PREFIX_REGEX = Regex("^DIRECT-[A-Za-z0-9]{2}-", RegexOption.IGNORE_CASE)
    private val HOSTNAME_LABEL_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val secureRandom = SecureRandom()

    fun sanitizeSsidSuffix(suffix: String): String {
        var cleaned = suffix
            .filter { ch -> ch.code >= 0x20 && ch.code != 0x7F }
            .trim()

        while (DIRECT_PREFIX_REGEX.containsMatchIn(cleaned)) {
            cleaned = DIRECT_PREFIX_REGEX.replaceFirst(cleaned, "").trim()
        }

        if (cleaned.isEmpty()) {
            return DEFAULT_SSID_SUFFIX
        }

        val maxSuffixBytes = MAX_SSID_UTF8_BYTES - SSID_PREFIX.toByteArray(Charsets.UTF_8).size
        val result = StringBuilder()
        var currentBytes = 0
        var i = 0
        while (i < cleaned.length) {
            val cp = cleaned.codePointAt(i)
            val charCount = Character.charCount(cp)
            val segment = cleaned.substring(i, i + charCount)
            val segmentBytes = segment.toByteArray(Charsets.UTF_8).size
            if (currentBytes + segmentBytes > maxSuffixBytes) {
                break
            }
            result.append(segment)
            currentBytes += segmentBytes
            i += charCount
        }

        val finalSuffix = result.toString().trim()
        return finalSuffix.ifEmpty { DEFAULT_SSID_SUFFIX }
    }

    fun buildDirectSsid(suffix: String): String {
        return SSID_PREFIX + sanitizeSsidSuffix(suffix)
    }

    fun isValidPort(port: Int): Boolean = port in 1024..65535

    fun isValidPassphrase(passphrase: String): Boolean {
        if (passphrase.length !in 8..63) return false
        return passphrase.all { ch -> ch.code in 0x20..0x7E }
    }

    fun isValidHostIp(ip: String): Boolean {
        if (ip.isEmpty() || ip.length > 253 || ip.any { it.isWhitespace() }) {
            return false
        }
        if (ip.startsWith('.') || ip.endsWith('.')) {
            return false
        }

        val parts = ip.split('.')
        if (parts.any { it.isEmpty() }) {
            return false
        }

        // If all segments are purely numeric, validate strictly as IPv4
        if (parts.all { segment -> segment.all { it.isDigit() } }) {
            if (parts.size != 4) return false
            return parts.all { octet ->
                val value = octet.toIntOrNull() ?: return@all false
                value in 0..255 && (octet.length == 1 || !octet.startsWith('0'))
            }
        }

        // Otherwise validate as RFC 1123 hostname
        return parts.all { label -> HOSTNAME_LABEL_REGEX.matches(label) }
    }

    fun generateRandomPassphrase(length: Int = 10): String {
        val targetLength = length.coerceIn(8, 63)
        val chars = CharArray(targetLength) {
            PASSPHRASE_ALPHABET[secureRandom.nextInt(PASSPHRASE_ALPHABET.length)]
        }
        return String(chars)
    }

    fun escapeWifiQrField(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '\\', ';', ',', '"', ':' -> {
                    sb.append('\\')
                    sb.append(ch)
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun buildWifiQrPayload(ssid: String, passphrase: String): String {
        return "WIFI:T:WPA;S:${escapeWifiQrField(ssid)};P:${escapeWifiQrField(passphrase)};;"
    }
}
