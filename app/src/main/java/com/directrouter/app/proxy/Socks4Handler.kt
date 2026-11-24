package com.directrouter.app.proxy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

internal object Socks4Handler {
    private const val SOCKS4_VERSION = 0x04
    private const val CMD_CONNECT = 0x01
    private const val REPLY_VERSION = 0x00
    private const val REP_GRANTED = 0x5A
    private const val REP_REJECTED = 0x5B
    private const val MAX_NULL_TERMINATED_BYTES = 256

    fun rejectImmediately(clientOut: OutputStream) {
        sendReply(clientOut, REP_REJECTED, null, 0)
    }

    suspend fun handle(
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        statsTracker: TrafficStatsTracker,
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        securityPolicy: ProxySecurityPolicy = ProxySecurityPolicy(),
        proxyBoundPort: Int = clientSocket.localPort,
        localAddresses: Set<InetAddress> = emptySet(),
    ) {
        if (securityPolicy.authEnabled) {
            rejectImmediately(clientOut)
            return
        }

        val header = readExact(clientIn, 8) ?: return
        val version = header[0].toInt() and 0xFF
        val command = header[1].toInt() and 0xFF
        val dstPort = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val dstIpBytes = header.copyOfRange(4, 8)

        if (version != SOCKS4_VERSION) {
            return
        }

        // Read null-terminated USERID (ignored for no-auth proxy).
        if (readNullTerminatedString(clientIn) == null) {
            return
        }

        // SOCKS4a: DSTIP == 0.0.0.x where x != 0 means a null-terminated domain follows.
        val isSocks4a = dstIpBytes[0] == 0.toByte() &&
            dstIpBytes[1] == 0.toByte() &&
            dstIpBytes[2] == 0.toByte() &&
            dstIpBytes[3] != 0.toByte()

        val domain: String? = if (isSocks4a) {
            readNullTerminatedString(clientIn) ?: return
        } else {
            null
        }

        if (command != CMD_CONNECT || dstPort !in 1..65535 || (isSocks4a && domain.isNullOrBlank())) {
            sendReply(clientOut, REP_REJECTED, null, 0)
            return
        }

        val connectResult = if (isSocks4a) {
            OutboundConnector.connectWithResult(
                host = domain!!,
                port = dstPort,
                securityPolicy = securityPolicy,
                proxyBoundPort = proxyBoundPort,
                localAddresses = localAddresses,
            )
        } else {
            val targetIp = InetAddress.getByAddress(dstIpBytes)
            OutboundConnector.connectWithResult(
                address = targetIp,
                port = dstPort,
                securityPolicy = securityPolicy,
                proxyBoundPort = proxyBoundPort,
                localAddresses = localAddresses,
            )
        }

        val remoteSocket = when (connectResult) {
            is OutboundConnector.ConnectResult.Success -> connectResult.socket
            is OutboundConnector.ConnectResult.Blocked,
            is OutboundConnector.ConnectResult.Failure,
            -> {
                sendReply(clientOut, REP_REJECTED, null, 0)
                return
            }
        }

        try {
            sendReply(
                clientOut = clientOut,
                replyCode = REP_GRANTED,
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
                dispatcher = dispatcher,
            )
        } finally {
            runCatching { remoteSocket.close() }
        }
    }

    private fun sendReply(
        clientOut: OutputStream,
        replyCode: Int,
        bindAddress: InetAddress?,
        bindPort: Int,
    ) {
        val unwrapped = bindAddress?.let { OutboundConnector.unwrapIpv4Mapped(it) }
        val ipBytes = if (unwrapped is Inet4Address) unwrapped.address else ByteArray(4)

        val reply = ByteArray(8)
        reply[0] = REPLY_VERSION.toByte()
        reply[1] = replyCode.toByte()
        reply[2] = ((bindPort ushr 8) and 0xFF).toByte()
        reply[3] = (bindPort and 0xFF).toByte()
        System.arraycopy(ipBytes, 0, reply, 4, 4)

        runCatching {
            clientOut.write(reply)
            clientOut.flush()
        }
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

    private fun readNullTerminatedString(input: InputStream): String? {
        val out = ByteArrayOutputStream(32)
        while (true) {
            val b = try {
                input.read()
            } catch (_: IOException) {
                return null
            }
            if (b == -1) return null
            if (b == 0) {
                return out.toString(Charsets.UTF_8.name())
            }
            if (out.size() >= MAX_NULL_TERMINATED_BYTES) {
                return null
            }
            out.write(b)
        }
    }
}
