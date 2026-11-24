package com.directrouter.app.data

import java.security.SecureRandom

object ValidationUtils {
    const val SSID_PREFIX = "DIRECT-DR-"
    const val MIN_RANDOM_PORT = 10000
    const val MAX_RANDOM_PORT = 60000
    const val MIN_MAX_CONNECTIONS = 16
    const val MAX_MAX_CONNECTIONS = 512
    const val MAX_PROXY_AUTH_CREDENTIAL_LENGTH = 64

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

    fun generateRandomPort(): Int {
        return MIN_RANDOM_PORT + secureRandom.nextInt(MAX_RANDOM_PORT - MIN_RANDOM_PORT + 1)
    }

    fun isValidMaxConnections(value: Int): Boolean = value in MIN_MAX_CONNECTIONS..MAX_MAX_CONNECTIONS

    fun isValidPassphrase(passphrase: String): Boolean {
        if (passphrase.length !in 8..63) return false
        return passphrase.all { ch -> ch.code in 0x20..0x7E }
    }

    /**
     * Validates a strict dotted-decimal IPv4 address (4 ASCII decimal octets in 0..255,
     * no leading zeros on multi-digit octets, no whitespace or extraneous characters).
     */
    fun isStrictIpv4(ip: String): Boolean {
        if (ip.length !in 7..15) return false
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { octet ->
            if (octet.isEmpty() || octet.length > 3) return false
            if (octet.length > 1 && octet[0] == '0') return false
            if (!octet.all { ch -> ch in '0'..'9' }) return false
            val value = octet.toIntOrNull() ?: return@all false
            value in 0..255
        }
    }

    /**
     * Sanitizes a Host header or proxy address for safe inclusion in a generated `/proxy.pac` file.
     *
     * Accepts only:
     * - Strict IPv4 (`192.168.49.1`) or IPv4 with port (`192.168.49.1:8080`)
     * - Bracketed IPv6 (`[2001:db8::1]`) or bracketed IPv6 with port (`[2001:db8::1]:8080`)
     * - Plain IPv6 without port (`::1`, `2001:db8::1`), normalized to bracketed form (`[::1]`)
     * - IPv4-mapped IPv6 (`::ffff:192.168.49.1` / `[::ffff:192.168.49.1]:8080`), normalized to IPv4
     *
     * Rejects hostnames, quotes, semicolons, newlines, control characters, and any JS injection payloads.
     * Returns normalized `host` or `host:port` (with port in `1..65535`), or `null` if invalid.
     */
    fun sanitizePacHost(host: String): String? {
        if (host.isEmpty()) return null
        if (host.any { ch -> ch.code < 0x20 || ch.code == 0x7F }) return null

        val trimmed = host.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { ch ->
                ch !in '0'..'9' &&
                    ch !in 'a'..'f' &&
                    ch !in 'A'..'F' &&
                    ch != '.' &&
                    ch != ':' &&
                    ch != '[' &&
                    ch != ']'
            }
        ) {
            return null
        }

        if (trimmed.startsWith('[')) {
            val closeIndex = trimmed.indexOf(']')
            if (closeIndex <= 1) return null
            if (trimmed.indexOf('[', startIndex = 1) != -1) return null
            if (trimmed.indexOf(']', startIndex = closeIndex + 1) != -1) return null

            val addr = trimmed.substring(1, closeIndex)
            val rest = trimmed.substring(closeIndex + 1)
            val port = when {
                rest.isEmpty() -> null
                rest.startsWith(':') -> parseStrictPort(rest.substring(1)) ?: return null
                else -> return null
            }
            if (!isStrictIpv6(addr)) return null

            val normalizedHost = normalizeIpv6ForPac(addr)
            return if (port != null) "$normalizedHost:$port" else normalizedHost
        }

        if (trimmed.contains('[') || trimmed.contains(']')) return null

        val colonCount = trimmed.count { it == ':' }
        return when (colonCount) {
            0 -> if (isStrictIpv4(trimmed)) trimmed else null
            1 -> {
                val idx = trimmed.indexOf(':')
                val ipv4 = trimmed.substring(0, idx)
                val portStr = trimmed.substring(idx + 1)
                if (!isStrictIpv4(ipv4)) return null
                val port = parseStrictPort(portStr) ?: return null
                "$ipv4:$port"
            }
            else -> {
                if (!isStrictIpv6(trimmed)) return null
                normalizeIpv6ForPac(trimmed)
            }
        }
    }

    /**
     * Validates a proxy authentication username or password for HTTP Basic (RFC 7617),
     * SOCKS5 (RFC 1929), and YAML tunnel configuration compatibility.
     *
     * Rules:
     * - Non-empty, length in `1..64`
     * - Printable ASCII without whitespace or control characters (`0x21..0x7E`)
     * - Disallows `:` (required by HTTP Basic `username:password` framing)
     */
    fun isValidProxyAuthCredential(value: String): Boolean {
        if (value.length !in 1..MAX_PROXY_AUTH_CREDENTIAL_LENGTH) return false
        return value.all { ch -> ch.code in 0x21..0x7E && ch != ':' }
    }

    /**
     * Generates a cryptographically random password using an unambiguous alphanumeric alphabet
     * that is safe for URLs, YAML configs, and HTTP Basic / SOCKS5 authentication.
     */
    fun generateSecurePassword(length: Int = 12): String {
        val targetLength = length.coerceIn(8, MAX_PROXY_AUTH_CREDENTIAL_LENGTH)
        val chars = CharArray(targetLength) {
            PASSPHRASE_ALPHABET[secureRandom.nextInt(PASSPHRASE_ALPHABET.length)]
        }
        return String(chars)
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
            return isStrictIpv4(ip)
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

    private fun parseStrictPort(portStr: String): Int? {
        if (portStr.isEmpty() || portStr.length > 5) return null
        if (portStr[0] == '0') return null
        if (!portStr.all { ch -> ch in '0'..'9' }) return null
        val port = portStr.toIntOrNull() ?: return null
        return port.takeIf { it in 1..65535 }
    }

    private fun normalizeIpv6ForPac(ipv6: String): String {
        val lower = ipv6.lowercase()
        if (lower.startsWith("::ffff:")) {
            val mapped = lower.substring(7)
            if (isStrictIpv4(mapped)) {
                return mapped
            }
        }
        return "[$lower]"
    }

    private fun isStrictIpv6(ip: String): Boolean {
        if (ip.length < 2 || ip.length > 45 || !ip.contains(':')) return false
        if (ip.contains(":::")) return false
        if (ip.startsWith(':') && !ip.startsWith("::")) return false
        if (ip.endsWith(':') && !ip.endsWith("::")) return false

        val doubleColonIndex = ip.indexOf("::")
        val hasCompression = doubleColonIndex != -1
        if (hasCompression && ip.indexOf("::", startIndex = doubleColonIndex + 2) != -1) {
            return false
        }

        val leftGroups: List<String>
        val rightGroups: List<String>
        if (hasCompression) {
            val leftPart = ip.substring(0, doubleColonIndex)
            val rightPart = ip.substring(doubleColonIndex + 2)
            leftGroups = if (leftPart.isEmpty()) emptyList() else leftPart.split(':')
            rightGroups = if (rightPart.isEmpty()) emptyList() else rightPart.split(':')
        } else {
            leftGroups = ip.split(':')
            rightGroups = emptyList()
        }

        val allGroups = leftGroups + rightGroups
        if (allGroups.any { it.isEmpty() }) return false

        var total16BitUnits = 0
        for (index in allGroups.indices) {
            val group = allGroups[index]
            if (group.contains('.')) {
                if (index != allGroups.lastIndex) return false
                if (!isStrictIpv4(group)) return false
                total16BitUnits += 2
            } else {
                if (group.length !in 1..4) return false
                if (!group.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' }) {
                    return false
                }
                total16BitUnits += 1
            }
        }

        return if (hasCompression) {
            total16BitUnits < 8
        } else {
            total16BitUnits == 8
        }
    }
}
