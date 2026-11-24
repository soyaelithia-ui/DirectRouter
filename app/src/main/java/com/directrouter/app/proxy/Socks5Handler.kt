package com.directrouter.app.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal object Socks5Handler {
    private const val SOCKS_VERSION = 0x05
    private const val AUTH_NO_AUTH = 0x00
    private const val AUTH_NO_ACCEPTABLE = 0xFF

    private const val CMD_CONNECT = 0x01
    private const val CMD_UDP_ASSOCIATE = 0x03

    private const val ATYP_IPV4 = 0x01
    private const val ATYP_DOMAIN = 0x03
    private const val ATYP_IPV6 = 0x04

    private const val REP_SUCCEEDED = 0x00
    private const val REP_GENERAL_FAILURE = 0x01
    private const val REP_HOST_UNREACHABLE = 0x04
    private const val REP_CONNECTION_REFUSED = 0x05
    private const val REP_COMMAND_NOT_SUPPORTED = 0x07
    private const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08

    private const val MAX_UDP_PACKET_SIZE = 65_535
    private const val DNS_CACHE_TTL_MS = 60_000L
    private const val DNS_CACHE_MAX_SIZE = 256

    private data class CachedDnsEntry(
        val address: InetAddress,
        val expiresAtMs: Long,
    )

    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()

    private data class Socks5Address(
        val atyp: Int,
        val host: String,
        val resolvedAddress: InetAddress?,
        val port: Int,
    )

    suspend fun handle(
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        statsTracker: TrafficStatsTracker,
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    ) {
        if (!negotiateAuth(clientIn, clientOut)) {
            return
        }

        val header = readExact(clientIn, 4) ?: return
        val version = header[0].toInt() and 0xFF
        val command = header[1].toInt() and 0xFF
        val atyp = header[3].toInt() and 0xFF
        if (version != SOCKS_VERSION) {
            return
        }

        val destination = when (atyp) {
            ATYP_IPV4 -> {
                val addrBytes = readExact(clientIn, 4) ?: return
                val portBytes = readExact(clientIn, 2) ?: return
                val inetAddr = InetAddress.getByAddress(addrBytes)
                Socks5Address(
                    atyp = ATYP_IPV4,
                    host = inetAddr.hostAddress ?: "0.0.0.0",
                    resolvedAddress = inetAddr,
                    port = parsePort(portBytes[0], portBytes[1]),
                )
            }

            ATYP_DOMAIN -> {
                val lenByte = clientIn.read()
                if (lenByte <= 0) {
                    sendReply(clientOut, REP_GENERAL_FAILURE, null, 0)
                    return
                }
                val domainBytes = readExact(clientIn, lenByte) ?: return
                val portBytes = readExact(clientIn, 2) ?: return
                val domain = String(domainBytes, Charsets.UTF_8)
                Socks5Address(
                    atyp = ATYP_DOMAIN,
                    host = domain,
                    resolvedAddress = null,
                    port = parsePort(portBytes[0], portBytes[1]),
                )
            }

            ATYP_IPV6 -> {
                val addrBytes = readExact(clientIn, 16) ?: return
                val portBytes = readExact(clientIn, 2) ?: return
                val inetAddr = InetAddress.getByAddress(addrBytes)
                Socks5Address(
                    atyp = ATYP_IPV6,
                    host = inetAddr.hostAddress ?: "::",
                    resolvedAddress = inetAddr,
                    port = parsePort(portBytes[0], portBytes[1]),
                )
            }

            else -> {
                sendReply(clientOut, REP_ADDRESS_TYPE_NOT_SUPPORTED, null, 0)
                return
            }
        }

        when (command) {
            CMD_CONNECT -> handleConnect(
                destination = destination,
                clientSocket = clientSocket,
                clientIn = clientIn,
                clientOut = clientOut,
                statsTracker = statsTracker,
            )

            CMD_UDP_ASSOCIATE -> handleUdpAssociate(
                destination = destination,
                clientSocket = clientSocket,
                clientIn = clientIn,
                clientOut = clientOut,
                statsTracker = statsTracker,
            )

            else -> {
                sendReply(clientOut, REP_COMMAND_NOT_SUPPORTED, null, 0)
            }
        }
    }

    private fun negotiateAuth(clientIn: InputStream, clientOut: OutputStream): Boolean {
        val ver = clientIn.read()
        if (ver != SOCKS_VERSION) return false

        val nMethods = clientIn.read()
        if (nMethods <= 0) return false

        val methods = readExact(clientIn, nMethods) ?: return false
        val supportsNoAuth = methods.any { (it.toInt() and 0xFF) == AUTH_NO_AUTH }

        return if (supportsNoAuth) {
            clientOut.write(byteArrayOf(SOCKS_VERSION.toByte(), AUTH_NO_AUTH.toByte()))
            clientOut.flush()
            true
        } else {
            clientOut.write(byteArrayOf(SOCKS_VERSION.toByte(), AUTH_NO_ACCEPTABLE.toByte()))
            clientOut.flush()
            false
        }
    }

    private suspend fun handleConnect(
        destination: Socks5Address,
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        statsTracker: TrafficStatsTracker,
    ) {
        val targetAddress = try {
            destination.resolvedAddress ?: resolveHostCached(destination.host)
        } catch (_: UnknownHostException) {
            sendReply(clientOut, REP_HOST_UNREACHABLE, null, 0)
            return
        }

        val remoteSocket = Socket()
        try {
            remoteSocket.tcpNoDelay = true
            remoteSocket.connect(
                InetSocketAddress(targetAddress, destination.port),
                HybridProxyServer.CONNECT_TIMEOUT_MS,
            )
        } catch (e: IOException) {
            runCatching { remoteSocket.close() }
            val replyCode = when (e) {
                is ConnectException -> REP_CONNECTION_REFUSED
                is UnknownHostException,
                is NoRouteToHostException,
                is SocketTimeoutException,
                -> REP_HOST_UNREACHABLE
                else -> REP_GENERAL_FAILURE
            }
            sendReply(clientOut, replyCode, null, 0)
            return
        }

        try {
            sendReply(
                clientOut = clientOut,
                replyCode = REP_SUCCEEDED,
                bindAddress = remoteSocket.localAddress,
                bindPort = remoteSocket.localPort,
            )

            HybridProxyServer.relayTcpBidirectional(
                clientSocket = clientSocket,
                clientIn = clientIn,
                clientOut = clientOut,
                remoteSocket = remoteSocket,
                remoteIn = remoteSocket.getInputStream(),
                remoteOut = remoteSocket.getOutputStream(),
                statsTracker = statsTracker,
            )
        } finally {
            runCatching { remoteSocket.close() }
        }
    }

    private suspend fun handleUdpAssociate(
        destination: Socks5Address,
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        statsTracker: TrafficStatsTracker,
    ) {
        val bindAddr = clientSocket.localAddress
        val clientUdpSocket = try {
            DatagramSocket(0, bindAddr)
        } catch (_: IOException) {
            try {
                DatagramSocket(0)
            } catch (_: IOException) {
                sendReply(clientOut, REP_GENERAL_FAILURE, null, 0)
                return
            }
        }

        val remoteUdpSocket = try {
            DatagramSocket()
        } catch (_: IOException) {
            runCatching { clientUdpSocket.close() }
            sendReply(clientOut, REP_GENERAL_FAILURE, null, 0)
            return
        }

        try {
            sendReply(
                clientOut = clientOut,
                replyCode = REP_SUCCEEDED,
                bindAddress = bindAddr,
                bindPort = clientUdpSocket.localPort,
            )

            val clientEndpointRef = AtomicReference<InetSocketAddress?>(
                if (destination.resolvedAddress != null &&
                    !destination.resolvedAddress.isAnyLocalAddress &&
                    destination.port in 1..65535
                ) {
                    InetSocketAddress(destination.resolvedAddress, destination.port)
                } else {
                    null
                },
            )

            runCatching { clientSocket.soTimeout = 0 }

            coroutineScope {
                val clientToRemoteJob = launch(Dispatchers.IO) {
                    val receiveBuffer = ByteArray(MAX_UDP_PACKET_SIZE)
                    while (isActive && !clientUdpSocket.isClosed) {
                        val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                        try {
                            clientUdpSocket.receive(packet)
                        } catch (_: SocketException) {
                            break
                        } catch (_: IOException) {
                            if (clientUdpSocket.isClosed) break
                            continue
                        }

                        val parsed = parseSocks5UdpPacket(receiveBuffer, packet.length) ?: continue
                        clientEndpointRef.set(InetSocketAddress(packet.address, packet.port))

                        val dataLen = packet.length - parsed.dataOffset
                        if (dataLen < 0 || parsed.port !in 1..65535) continue

                        val targetAddr = parsed.resolvedAddress ?: try {
                            resolveHostCached(parsed.host)
                        } catch (_: IOException) {
                            continue
                        }

                        try {
                            val outboundPacket = DatagramPacket(
                                receiveBuffer,
                                parsed.dataOffset,
                                dataLen,
                                targetAddr,
                                parsed.port,
                            )
                            remoteUdpSocket.send(outboundPacket)
                            if (dataLen > 0) {
                                statsTracker.addBytesUp(dataLen.toLong())
                            }
                        } catch (_: IOException) {
                            // Individual datagram send failure should not tear down the association.
                        }
                    }
                }

                val remoteToClientJob = launch(Dispatchers.IO) {
                    val receiveBuffer = ByteArray(MAX_UDP_PACKET_SIZE)
                    while (isActive && !remoteUdpSocket.isClosed) {
                        val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                        try {
                            remoteUdpSocket.receive(packet)
                        } catch (_: SocketException) {
                            break
                        } catch (_: IOException) {
                            if (remoteUdpSocket.isClosed) break
                            continue
                        }

                        val currentClient = clientEndpointRef.get() ?: continue
                        val wrapped = buildSocks5UdpPacket(
                            sourceAddress = packet.address,
                            sourcePort = packet.port,
                            payload = receiveBuffer,
                            payloadLength = packet.length,
                        ) ?: continue

                        try {
                            val replyPacket = DatagramPacket(
                                wrapped,
                                wrapped.size,
                                currentClient.address,
                                currentClient.port,
                            )
                            clientUdpSocket.send(replyPacket)
                            if (packet.length > 0) {
                                statsTracker.addBytesDown(packet.length.toLong())
                            }
                        } catch (_: IOException) {
                            if (clientUdpSocket.isClosed) break
                        }
                    }
                }

                // Per RFC 1928 Section 7, a UDP association terminates when the TCP control connection closes.
                try {
                    val drain = ByteArray(64)
                    while (isActive) {
                        val read = clientIn.read(drain)
                        if (read == -1) break
                    }
                } catch (_: IOException) {
                    // TCP control connection closed or reset.
                } finally {
                    runCatching { clientUdpSocket.close() }
                    runCatching { remoteUdpSocket.close() }
                    clientToRemoteJob.cancel()
                    remoteToClientJob.cancel()
                }
            }
        } finally {
            runCatching { clientUdpSocket.close() }
            runCatching { remoteUdpSocket.close() }
        }
    }

    internal data class ParsedUdpHeader(
        val host: String,
        val resolvedAddress: InetAddress?,
        val port: Int,
        val dataOffset: Int,
    )

    internal fun parseSocks5UdpPacket(buffer: ByteArray, length: Int): ParsedUdpHeader? {
        if (length < 4) return null
        val frag = buffer[2].toInt() and 0xFF
        if (frag != 0) return null

        val atyp = buffer[3].toInt() and 0xFF
        return when (atyp) {
            ATYP_IPV4 -> {
                if (length < 10) return null
                val addr = InetAddress.getByAddress(buffer.copyOfRange(4, 8))
                val port = parsePort(buffer[8], buffer[9])
                ParsedUdpHeader(
                    host = addr.hostAddress ?: "0.0.0.0",
                    resolvedAddress = addr,
                    port = port,
                    dataOffset = 10,
                )
            }

            ATYP_DOMAIN -> {
                if (length < 5) return null
                val domainLen = buffer[4].toInt() and 0xFF
                val headerLen = 5 + domainLen + 2
                if (domainLen == 0 || length < headerLen) return null
                val domain = String(buffer, 5, domainLen, Charsets.UTF_8)
                val port = parsePort(buffer[5 + domainLen], buffer[6 + domainLen])
                ParsedUdpHeader(
                    host = domain,
                    resolvedAddress = null,
                    port = port,
                    dataOffset = headerLen,
                )
            }

            ATYP_IPV6 -> {
                if (length < 22) return null
                val addr = InetAddress.getByAddress(buffer.copyOfRange(4, 20))
                val port = parsePort(buffer[20], buffer[21])
                ParsedUdpHeader(
                    host = addr.hostAddress ?: "::",
                    resolvedAddress = addr,
                    port = port,
                    dataOffset = 22,
                )
            }

            else -> null
        }
    }

    internal fun buildSocks5UdpPacket(
        sourceAddress: InetAddress,
        sourcePort: Int,
        payload: ByteArray,
        payloadLength: Int,
    ): ByteArray? {
        val addrBytes = sourceAddress.address
        val atyp = when (addrBytes.size) {
            4 -> ATYP_IPV4
            16 -> ATYP_IPV6
            else -> return null
        }
        val headerLen = 4 + addrBytes.size + 2
        val out = ByteArray(headerLen + payloadLength)
        out[0] = 0x00
        out[1] = 0x00
        out[2] = 0x00
        out[3] = atyp.toByte()
        System.arraycopy(addrBytes, 0, out, 4, addrBytes.size)
        out[4 + addrBytes.size] = ((sourcePort ushr 8) and 0xFF).toByte()
        out[5 + addrBytes.size] = (sourcePort and 0xFF).toByte()
        if (payloadLength > 0) {
            System.arraycopy(payload, 0, out, headerLen, payloadLength)
        }
        return out
    }

    private fun sendReply(
        clientOut: OutputStream,
        replyCode: Int,
        bindAddress: InetAddress?,
        bindPort: Int,
    ) {
        val rawAddr = bindAddress?.address
        val (atyp, addrBytes) = when (rawAddr?.size) {
            4 -> ATYP_IPV4 to rawAddr
            16 -> ATYP_IPV6 to rawAddr
            else -> ATYP_IPV4 to ByteArray(4)
        }

        val reply = ByteArray(4 + addrBytes.size + 2)
        reply[0] = SOCKS_VERSION.toByte()
        reply[1] = replyCode.toByte()
        reply[2] = 0x00
        reply[3] = atyp.toByte()
        System.arraycopy(addrBytes, 0, reply, 4, addrBytes.size)
        reply[4 + addrBytes.size] = ((bindPort ushr 8) and 0xFF).toByte()
        reply[5 + addrBytes.size] = (bindPort and 0xFF).toByte()

        runCatching {
            clientOut.write(reply)
            clientOut.flush()
        }
    }

    private fun resolveHostCached(host: String): InetAddress {
        val now = System.currentTimeMillis()
        val cached = dnsCache[host]
        if (cached != null && cached.expiresAtMs > now) {
            return cached.address
        }

        val candidates = InetAddress.getAllByName(host)
        val resolved = candidates.firstOrNull { it is Inet4Address } ?: candidates.first()
        if (dnsCache.size >= DNS_CACHE_MAX_SIZE) {
            dnsCache.entries.removeIf { it.value.expiresAtMs <= now }
            if (dnsCache.size >= DNS_CACHE_MAX_SIZE) {
                dnsCache.clear()
            }
        }
        dnsCache[host] = CachedDnsEntry(
            address = resolved,
            expiresAtMs = now + DNS_CACHE_TTL_MS,
        )
        return resolved
    }

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = try {
                input.read(buffer, offset, count - offset)
            } catch (_: EOFException) {
                return null
            }
            if (read == -1) return null
            offset += read
        }
        return buffer
    }

    private fun parsePort(high: Byte, low: Byte): Int {
        return ((high.toInt() and 0xFF) shl 8) or (low.toInt() and 0xFF)
    }
}
