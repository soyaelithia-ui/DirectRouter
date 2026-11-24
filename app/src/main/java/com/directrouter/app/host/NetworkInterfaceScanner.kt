package com.directrouter.app.host

import com.directrouter.app.data.ValidationUtils
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

data class DetectedInterface(
    val name: String,
    val ipAddress: String,
    val priority: Int,
)

object NetworkInterfaceScanner {

    private val HOTSPOT_EXACT_NAMES = setOf("ap0", "swlan0", "softap0", "wlan1", "wlan2")
    private val USB_EXACT_NAMES = setOf("rndis0", "usb0", "ncm0")
    private val EXCLUDED_FALLBACK_PREFIXES = listOf(
        "rmnet",
        "v4-rmnet",
        "ccmni",
        "clat",
        "tun",
        "tap",
        "dummy",
        "sit",
        "ip6tnl",
    )

    fun scanTetheringIps(): List<DetectedInterface> {
        val detected = mutableListOf<DetectedInterface>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        while (interfaces.hasMoreElements()) {
            val iface = try {
                interfaces.nextElement()
            } catch (_: Exception) {
                continue
            }
            val isUpAndNonLoopback = try {
                iface.isUp && !iface.isLoopback
            } catch (_: Exception) {
                false
            }
            if (!isUpAndNonLoopback) continue

            val rawName = iface.name?.trim().orEmpty()
            if (rawName.isEmpty()) continue
            val normalizedName = rawName.lowercase(Locale.US)

            val addresses = try {
                iface.inetAddresses
            } catch (_: Exception) {
                continue
            }

            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement() as? Inet4Address ?: continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) {
                    continue
                }
                val ip = addr.hostAddress?.trim().orEmpty()
                if (ip.isEmpty() || ip.startsWith("127.") || ip.startsWith("169.254.")) {
                    continue
                }

                val priority = classifyPriority(normalizedName, addr) ?: continue
                detected.add(
                    DetectedInterface(
                        name = rawName,
                        ipAddress = ip,
                        priority = priority,
                    ),
                )
            }
        }

        return detected
            .sortedWith(
                compareBy<DetectedInterface>(
                    { it.priority },
                    { it.name.lowercase(Locale.US) },
                    { it.ipAddress },
                ),
            )
            .distinctBy { it.ipAddress }
    }

    fun isActiveLocalIpv4(ip: String): Boolean {
        if (!ValidationUtils.isStrictIpv4(ip)) return false
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: Exception) {
            null
        } ?: return false

        while (interfaces.hasMoreElements()) {
            val iface = try {
                interfaces.nextElement()
            } catch (_: Exception) {
                continue
            }
            val isUpAndNonLoopback = try {
                iface.isUp && !iface.isLoopback
            } catch (_: Exception) {
                false
            }
            if (!isUpAndNonLoopback) continue

            val addresses = try {
                iface.inetAddresses
            } catch (_: Exception) {
                continue
            }

            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement() as? Inet4Address ?: continue
                if (addr.isLoopbackAddress || addr.isAnyLocalAddress) continue
                if (addr.hostAddress == ip) {
                    return true
                }
            }
        }
        return false
    }

    fun getAllLocalInetAddresses(): Set<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: Exception) {
            null
        } ?: return emptySet()

        while (interfaces.hasMoreElements()) {
            val iface = try {
                interfaces.nextElement()
            } catch (_: Exception) {
                continue
            }
            val isUp = try {
                iface.isUp
            } catch (_: Exception) {
                false
            }
            if (!isUp) continue

            val addresses = try {
                iface.inetAddresses
            } catch (_: Exception) {
                continue
            }

            while (addresses.hasMoreElements()) {
                val addr = try {
                    addresses.nextElement()
                } catch (_: Exception) {
                    continue
                }
                if (!addr.isAnyLocalAddress) {
                    result.add(addr)
                }
            }
        }
        return result
    }

    private fun classifyPriority(normalizedName: String, address: Inet4Address): Int? {
        return when {
            normalizedName in HOTSPOT_EXACT_NAMES ||
                normalizedName.startsWith("ap_br") ||
                normalizedName.startsWith("softap") ||
                normalizedName.startsWith("swlan") ||
                (normalizedName.startsWith("ap") && normalizedName.length > 2 && normalizedName[2].isDigit()) -> 1

            normalizedName in USB_EXACT_NAMES ||
                normalizedName.startsWith("rndis") ||
                normalizedName.startsWith("usb") ||
                normalizedName.startsWith("ncm") -> 2

            normalizedName == "bt-pan" || normalizedName.startsWith("bnep") -> 3

            normalizedName.startsWith("p2p") -> 4

            normalizedName == "wlan0" -> 5

            isPrivateRfc1918(address) &&
                EXCLUDED_FALLBACK_PREFIXES.none { normalizedName.startsWith(it) } -> 6

            else -> null
        }
    }

    private fun isPrivateRfc1918(address: Inet4Address): Boolean {
        val bytes = address.address
        if (bytes.size != 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return when {
            b0 == 10 -> true
            b0 == 172 && b1 in 16..31 -> true
            b0 == 192 && b1 == 168 -> true
            else -> false
        }
    }
}
