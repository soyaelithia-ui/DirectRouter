package com.directrouter.app.proxy

import java.net.InetAddress
import java.security.MessageDigest

data class ProxySecurityPolicy(
    val blockLoopbackAndSelf: Boolean = true,
    val maxConcurrentConnections: Int = DEFAULT_MAX_CONCURRENT_CONNECTIONS,
    val authEnabled: Boolean = false,
    val authUsername: String = "",
    val authPassword: String = "",
) {
    fun effectiveMaxConcurrentConnections(): Int {
        return maxConcurrentConnections.coerceIn(
            MIN_CONCURRENT_CONNECTIONS,
            MAX_CONCURRENT_CONNECTIONS,
        )
    }

    fun verifyCredentials(user: String, pass: String): Boolean {
        val userMatch = MessageDigest.isEqual(
            user.toByteArray(Charsets.UTF_8),
            authUsername.toByteArray(Charsets.UTF_8),
        )
        val passMatch = MessageDigest.isEqual(
            pass.toByteArray(Charsets.UTF_8),
            authPassword.toByteArray(Charsets.UTF_8),
        )
        return userMatch and passMatch
    }

    fun isTargetBlocked(
        address: InetAddress,
        port: Int,
        proxyBoundPort: Int,
        localAddresses: Set<InetAddress>,
    ): Boolean {
        if (!blockLoopbackAndSelf) {
            return false
        }

        val unwrapped = unwrapIpv4Mapped(address)
        if (isDisallowedAddress(unwrapped)) {
            return true
        }

        if (proxyBoundPort > 0 && port == proxyBoundPort && localAddresses.isNotEmpty()) {
            val targetBytes = unwrapped.address
            for (localAddr in localAddresses) {
                val localBytes = unwrapIpv4Mapped(localAddr).address
                if (targetBytes.contentEquals(localBytes)) {
                    return true
                }
            }
        }

        return false
    }

    companion object {
        const val MIN_CONCURRENT_CONNECTIONS = 1
        const val DEFAULT_MAX_CONCURRENT_CONNECTIONS = 256
        const val MAX_CONCURRENT_CONNECTIONS = 1024

        internal fun unwrapIpv4Mapped(address: InetAddress): InetAddress {
            val bytes = address.address
            if (bytes.size == 16 && isIpv4MappedBytes(bytes)) {
                return InetAddress.getByAddress(bytes.copyOfRange(12, 16))
            }
            return address
        }

        private fun isIpv4MappedBytes(bytes: ByteArray): Boolean {
            for (i in 0 until 10) {
                if (bytes[i] != 0.toByte()) return false
            }
            return bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        }

        private fun isDisallowedAddress(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress) {
                return true
            }

            val bytes = address.address
            if (bytes.size == 4) {
                return isDisallowedIpv4Bytes(bytes, 0)
            }

            if (bytes.size == 16) {
                // :: (wildcard) and ::1 (loopback)
                var allFirst15Zero = true
                for (i in 0 until 15) {
                    if (bytes[i] != 0.toByte()) {
                        allFirst15Zero = false
                        break
                    }
                }
                if (allFirst15Zero && (bytes[15] == 0.toByte() || bytes[15] == 1.toByte())) {
                    return true
                }

                // fe80::/10 (IPv6 link-local)
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                if (b0 == 0xFE && (b1 and 0xC0) == 0x80) {
                    return true
                }

                // IPv4-compatible IPv6 (::/96) wrapping disallowed IPv4
                var first12Zero = true
                for (i in 0 until 12) {
                    if (bytes[i] != 0.toByte()) {
                        first12Zero = false
                        break
                    }
                }
                if (first12Zero && isDisallowedIpv4Bytes(bytes, 12)) {
                    return true
                }
            }

            return false
        }

        private fun isDisallowedIpv4Bytes(bytes: ByteArray, offset: Int): Boolean {
            val o0 = bytes[offset].toInt() and 0xFF
            val o1 = bytes[offset + 1].toInt() and 0xFF
            // 127.0.0.0/8 (loopback)
            if (o0 == 127) return true
            // 0.0.0.0/8 (wildcard / this network)
            if (o0 == 0) return true
            // 169.254.0.0/16 (link-local)
            if (o0 == 169 && o1 == 254) return true
            return false
        }

        /**
         * Validates and normalizes a Host header value for safe embedding into a PAC script.
         * Accepts strict IPv4 or valid IPv6 (plain or bracketed) with an optional `:port` (1..65535).
         * Rejects any quotes, semicolons, whitespace, hostnames, or JS injection payloads.
         */
        fun sanitizePacHost(host: String): String? {
            if (host.isEmpty() || host.length > 64) return null

            // Strict character whitelist prevents any JS injection, quotes, semicolons, or whitespace.
            for (ch in host) {
                val allowed = (ch in '0'..'9') ||
                    (ch in 'a'..'f') ||
                    (ch in 'A'..'F') ||
                    ch == '.' ||
                    ch == ':' ||
                    ch == '[' ||
                    ch == ']'
                if (!allowed) return null
            }

            if (host.startsWith('[')) {
                val closeIdx = host.indexOf(']')
                if (closeIdx <= 1) return null
                // Reject extra brackets anywhere
                if (host.indexOf('[', 1) != -1 || host.indexOf(']', closeIdx + 1) != -1) {
                    return null
                }
                val ipPart = host.substring(1, closeIdx)
                if (!isStrictIpv6(ipPart)) return null

                val rest = host.substring(closeIdx + 1)
                return when {
                    rest.isEmpty() -> "[$ipPart]"
                    rest.startsWith(':') -> {
                        val port = parseStrictPort(rest.substring(1)) ?: return null
                        "[$ipPart]:$port"
                    }
                    else -> null
                }
            }

            // No brackets allowed outside the leading '[' form
            if (host.indexOf('[') != -1 || host.indexOf(']') != -1) return null

            val colonCount = host.count { it == ':' }
            return when (colonCount) {
                0 -> {
                    if (isStrictIpv4(host)) host else null
                }
                1 -> {
                    val colonIdx = host.indexOf(':')
                    val ipPart = host.substring(0, colonIdx)
                    val portPart = host.substring(colonIdx + 1)
                    val port = parseStrictPort(portPart) ?: return null
                    if (isStrictIpv4(ipPart)) "$ipPart:$port" else null
                }
                else -> {
                    // Plain IPv6 address without port
                    if (isStrictIpv6(host)) "[$host]" else null
                }
            }
        }

        private fun parseStrictPort(portText: String): Int? {
            if (portText.isEmpty() || portText.length > 5) return null
            if (portText[0] == '0') return null
            for (ch in portText) {
                if (ch !in '0'..'9') return null
            }
            val value = portText.toIntOrNull() ?: return null
            return if (value in 1..65535) value else null
        }

        private fun isStrictIpv4(ip: String): Boolean {
            if (ip.isEmpty() || ip.length > 15) return false
            val parts = ip.split('.')
            if (parts.size != 4) return false
            for (part in parts) {
                if (part.isEmpty() || part.length > 3) return false
                if (part.length > 1 && part[0] == '0') return false
                for (ch in part) {
                    if (ch !in '0'..'9') return false
                }
                val octet = part.toIntOrNull() ?: return false
                if (octet !in 0..255) return false
            }
            return true
        }

        private fun isStrictIpv6(ip: String): Boolean {
            if (ip.length < 2 || ip.length > 45) return false
            if (!ip.contains(':')) return false
            if (ip.contains(":::")) return false

            val doubleColonIdx = ip.indexOf("::")
            val hasDoubleColon = doubleColonIdx != -1
            if (hasDoubleColon && ip.indexOf("::", doubleColonIdx + 2) != -1) {
                return false
            }

            // Cannot begin or end with a single colon
            if (ip.startsWith(':') && !ip.startsWith("::")) return false
            if (ip.endsWith(':') && !ip.endsWith("::")) return false

            val leftPart: String
            val rightPart: String
            if (hasDoubleColon) {
                leftPart = ip.substring(0, doubleColonIdx)
                rightPart = ip.substring(doubleColonIdx + 2)
            } else {
                leftPart = ip
                rightPart = ""
            }

            val leftGroups = if (leftPart.isEmpty()) emptyList() else leftPart.split(':')
            val rightGroups = if (rightPart.isEmpty()) emptyList() else rightPart.split(':')

            // Embedded IPv4 may only appear as the final group
            if (leftGroups.any { it.contains('.') } && (hasDoubleColon || leftGroups.dropLast(1).any { it.contains('.') })) {
                return false
            }
            if (rightGroups.dropLast(1).any { it.contains('.') }) {
                return false
            }

            var totalGroups = 0
            val allGroups = if (hasDoubleColon) leftGroups + rightGroups else leftGroups
            for ((index, group) in allGroups.withIndex()) {
                if (group.isEmpty()) return false
                val isLast = index == allGroups.lastIndex
                if (group.contains('.')) {
                    if (!isLast || !isStrictIpv4(group)) return false
                    totalGroups += 2
                } else {
                    if (group.length !in 1..4) return false
                    for (ch in group) {
                        val isHex = (ch in '0'..'9') || (ch in 'a'..'f') || (ch in 'A'..'F')
                        if (!isHex) return false
                    }
                    totalGroups += 1
                }
            }

            return if (hasDoubleColon) {
                totalGroups < 8
            } else {
                totalGroups == 8
            }
        }
    }
}
