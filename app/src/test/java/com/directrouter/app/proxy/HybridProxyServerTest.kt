package com.directrouter.app.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class HybridProxyServerTest {

    private lateinit var statsTracker: TrafficStatsTracker
    private lateinit var proxyServer: HybridProxyServer

    @Before
    fun setUp() {
        statsTracker = TrafficStatsTracker()
        proxyServer = HybridProxyServer(statsTracker)
        runBlocking {
            // Existing loopback relay tests intentionally connect to local test servers on 127.0.0.1.
            proxyServer.start(
                port = 0,
                bindAddress = "127.0.0.1",
                securityPolicy = ProxySecurityPolicy(blockLoopbackAndSelf = false),
            )
        }
        assertTrue("Proxy server should be running", proxyServer.isRunning)
        assertTrue("Proxy server should bind ephemeral port", proxyServer.boundPort > 0)
    }

    @After
    fun tearDown() {
        proxyServer.stop()
        assertFalse("Proxy server should be stopped", proxyServer.isRunning)
        assertEquals(0, proxyServer.boundPort)
    }

    @Test
    fun plainHttpGetForwardingRewritesPathAndStripsProxyHeaders() {
        val capturedRequestLine = AtomicReference<String>()
        val capturedHeaders = AtomicReference<Map<String, String>>()
        val requestHandled = CountDownLatch(1)

        val originServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val originThread = thread(isDaemon = true) {
            try {
                originServer.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                    val reqLine = reader.readLine()
                    capturedRequestLine.set(reqLine)

                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx > 0) {
                            headers[line.substring(0, idx).trim().lowercase()] =
                                line.substring(idx + 1).trim()
                        }
                    }
                    capturedHeaders.set(headers)

                    val body = "pong-directrouter"
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/plain\r\n")
                        append("Content-Length: ${body.length}\r\n")
                        append("Connection: close\r\n\r\n")
                        append(body)
                    }
                    socket.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
                    socket.getOutputStream().flush()
                    requestHandled.countDown()
                }
            } catch (_: Exception) {
            }
        }

        try {
            Socket("127.0.0.1", proxyServer.boundPort).use { client ->
                client.soTimeout = 5_000
                val rawRequest = buildString {
                    append("GET http://127.0.0.1:${originServer.localPort}/ping?mode=test HTTP/1.1\r\n")
                    append("Proxy-Connection: keep-alive\r\n")
                    append("Proxy-Authorization: Basic dGVzdDp0ZXN0\r\n")
                    append("X-Custom-Header: direct\r\n\r\n")
                }
                client.getOutputStream().write(rawRequest.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()

                val responseBytes = client.getInputStream().readBytes()
                val responseText = String(responseBytes, Charsets.ISO_8859_1)

                assertTrue(responseText.startsWith("HTTP/1.1 200 OK"))
                assertTrue(responseText.endsWith("pong-directrouter"))
            }

            assertTrue("Origin server should handle request", requestHandled.await(5, TimeUnit.SECONDS))
            assertEquals("GET /ping?mode=test HTTP/1.1", capturedRequestLine.get())

            val headers = capturedHeaders.get()
            assertFalse("Proxy-Connection must be stripped", headers.containsKey("proxy-connection"))
            assertFalse("Proxy-Authorization must be stripped", headers.containsKey("proxy-authorization"))
            assertEquals("close", headers["connection"]?.lowercase())
            assertEquals("direct", headers["x-custom-header"])
            assertEquals("127.0.0.1:${originServer.localPort}", headers["host"])

            assertTrue(statsTracker.snapshot.value.bytesUp > 0L)
            assertTrue(statsTracker.snapshot.value.bytesDown > 0L)
        } finally {
            originServer.close()
            originThread.join(1_000)
        }
    }

    @Test
    fun plainHttpPostForwardsRequestBody() {
        val capturedBody = AtomicReference<String>()
        val originServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val originThread = thread(isDaemon = true) {
            try {
                originServer.accept().use { socket ->
                    val input = socket.getInputStream()
                    val headerLines = readHttpHeadersRaw(input)
                    val contentLength = headerLines
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                        ?.toInt()
                        ?: 0
                    val bodyBytes = readExactBytes(input, contentLength)
                    capturedBody.set(String(bodyBytes, Charsets.UTF_8))

                    val replyBody = "received:${bodyBytes.size}"
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: ${replyBody.length}\r\nConnection: close\r\n\r\n$replyBody"
                    socket.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
                    socket.getOutputStream().flush()
                }
            } catch (_: Exception) {
            }
        }

        try {
            val payload = "hello-post-payload-12345"
            Socket("127.0.0.1", proxyServer.boundPort).use { client ->
                client.soTimeout = 5_000
                val rawRequest = buildString {
                    append("POST http://127.0.0.1:${originServer.localPort}/upload HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:${originServer.localPort}\r\n")
                    append("Content-Length: ${payload.length}\r\n\r\n")
                    append(payload)
                }
                client.getOutputStream().write(rawRequest.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()

                val responseText = String(client.getInputStream().readBytes(), Charsets.ISO_8859_1)
                assertTrue(responseText.startsWith("HTTP/1.1 200 OK"))
                assertTrue(responseText.endsWith("received:${payload.length}"))
            }

            assertEquals(payload, capturedBody.get())
        } finally {
            originServer.close()
            originThread.join(1_000)
        }
    }

    @Test
    fun httpsConnectTunnelRelaysBidirectionalTcpTraffic() {
        val echoServer = startTcpEchoServer()
        try {
            Socket("127.0.0.1", proxyServer.boundPort).use { client ->
                client.soTimeout = 5_000
                val out = client.getOutputStream()
                val input = client.getInputStream()

                val connectRequest =
                    "CONNECT 127.0.0.1:${echoServer.port} HTTP/1.1\r\nHost: 127.0.0.1:${echoServer.port}\r\n\r\n"
                out.write(connectRequest.toByteArray(Charsets.ISO_8859_1))
                out.flush()

                val statusLines = readHttpHeadersRaw(input)
                assertTrue(
                    "Expected 200 Connection Established, got: $statusLines",
                    statusLines.firstOrNull()?.startsWith("HTTP/1.1 200") == true,
                )

                val message = "TLS-encrypted-payload-simulation-0123456789"
                out.write(message.toByteArray(Charsets.UTF_8))
                out.flush()
                client.shutdownOutput()

                val echoed = String(input.readBytes(), Charsets.UTF_8)
                assertEquals(message, echoed)
            }

            waitForActiveConnectionsZero()
            val snapshot = statsTracker.snapshot.value
            assertTrue("bytesUp should reflect tunnel traffic", snapshot.bytesUp >= 40L)
            assertTrue("bytesDown should reflect tunnel traffic", snapshot.bytesDown >= 40L)
            assertEquals(0, snapshot.activeConnections)
            assertTrue(snapshot.totalConnections >= 1L)
        } finally {
            echoServer.close()
        }
    }

    @Test
    fun socks5ConnectSupportsIpv4AndDomainTargets() {
        val echoServer = startTcpEchoServer()
        try {
            // 1. SOCKS5 CONNECT via IPv4 (ATYP = 0x01)
            Socket("127.0.0.1", proxyServer.boundPort).use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream()
                val out = client.getOutputStream()

                // Auth negotiation: VER=5, NMETHODS=1, METHOD=0 (No Auth)
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val authReply = readExactBytes(input, 2)
                assertEquals(0x05, authReply[0].toInt() and 0xFF)
                assertEquals(0x00, authReply[1].toInt() and 0xFF)

                // CONNECT 127.0.0.1:<echoPort>
                val connectReq = byteArrayOf(
                    0x05, 0x01, 0x00, 0x01,
                    127, 0, 0, 1,
                    ((echoServer.port ushr 8) and 0xFF).toByte(),
                    (echoServer.port and 0xFF).toByte(),
                )
                out.write(connectReq)
                out.flush()

                val reply = readSocks5Reply(input)
                assertEquals(0x05, reply.ver)
                assertEquals(0x00, reply.rep)

                val payload = "socks5-ipv4-echo-test"
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                client.shutdownOutput()

                val echoed = String(input.readBytes(), Charsets.UTF_8)
                assertEquals(payload, echoed)
            }

            // 2. SOCKS5 CONNECT via Domain (ATYP = 0x03, "localhost")
            Socket("127.0.0.1", proxyServer.boundPort).use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream()
                val out = client.getOutputStream()

                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val authReply = readExactBytes(input, 2)
                assertEquals(0x00, authReply[1].toInt() and 0xFF)

                val domain = "localhost".toByteArray(Charsets.UTF_8)
                val connectReq = ByteArrayOutputStream().apply {
                    write(byteArrayOf(0x05, 0x01, 0x00, 0x03, domain.size.toByte()))
                    write(domain)
                    write((echoServer.port ushr 8) and 0xFF)
                    write(echoServer.port and 0xFF)
                }.toByteArray()
                out.write(connectReq)
                out.flush()

                val reply = readSocks5Reply(input)
                assertEquals(0x05, reply.ver)
                assertEquals(0x00, reply.rep)

                val payload = "socks5-domain-localhost-echo-test"
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                client.shutdownOutput()

                val echoed = String(input.readBytes(), Charsets.UTF_8)
                assertEquals(payload, echoed)
            }
        } finally {
            echoServer.close()
        }
    }

    @Test
    fun socks5UdpAssociateRelaysIpv4AndDomainDatagrams() {
        val udpEchoSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val echoPort = udpEchoSocket.localPort
        val echoThread = thread(isDaemon = true) {
            val buf = ByteArray(4096)
            while (!udpEchoSocket.isClosed) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    udpEchoSocket.receive(packet)
                    val incoming = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val replyBytes = "ECHO:$incoming".toByteArray(Charsets.UTF_8)
                    val replyPacket = DatagramPacket(
                        replyBytes,
                        replyBytes.size,
                        packet.address,
                        packet.port,
                    )
                    udpEchoSocket.send(replyPacket)
                } catch (_: Exception) {
                    break
                }
            }
        }

        try {
            Socket("127.0.0.1", proxyServer.boundPort).use { controlSocket ->
                controlSocket.soTimeout = 5_000
                val input = controlSocket.getInputStream()
                val out = controlSocket.getOutputStream()

                // Auth negotiation
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val authReply = readExactBytes(input, 2)
                assertEquals(0x00, authReply[1].toInt() and 0xFF)

                // UDP ASSOCIATE (CMD = 0x03) with 0.0.0.0:0
                out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()

                val reply = readSocks5Reply(input)
                assertEquals(0x05, reply.ver)
                assertEquals(0x00, reply.rep)
                assertTrue("Relay UDP port must be > 0", reply.bindPort > 0)

                val relayAddress = reply.bindAddress
                val relayPort = reply.bindPort

                DatagramSocket().use { clientUdp ->
                    clientUdp.soTimeout = 5_000

                    // 1. Send UDP datagram via IPv4 ATYP=0x01
                    val ipv4Payload = "udp-ipv4-hello".toByteArray(Charsets.UTF_8)
                    val ipv4PacketBytes = ByteArrayOutputStream().apply {
                        write(byteArrayOf(0x00, 0x00, 0x00, 0x01, 127, 0, 0, 1))
                        write((echoPort ushr 8) and 0xFF)
                        write(echoPort and 0xFF)
                        write(ipv4Payload)
                    }.toByteArray()

                    clientUdp.send(DatagramPacket(ipv4PacketBytes, ipv4PacketBytes.size, relayAddress, relayPort))

                    val recvBuf1 = ByteArray(4096)
                    val recvPacket1 = DatagramPacket(recvBuf1, recvBuf1.size)
                    clientUdp.receive(recvPacket1)

                    val parsed1 = Socks5Handler.parseSocks5UdpPacket(recvBuf1, recvPacket1.length)
                    assertTrue("Reply 1 must be valid SOCKS5 UDP packet", parsed1 != null)
                    assertEquals(echoPort, parsed1!!.port)
                    assertEquals("127.0.0.1", parsed1.host)
                    val responseBody1 = String(
                        recvBuf1,
                        parsed1.dataOffset,
                        recvPacket1.length - parsed1.dataOffset,
                        Charsets.UTF_8,
                    )
                    assertEquals("ECHO:udp-ipv4-hello", responseBody1)

                    // 2. Send UDP datagram via Domain ATYP=0x03 ("localhost")
                    val domainBytes = "localhost".toByteArray(Charsets.UTF_8)
                    val domainPayload = "udp-domain-hello".toByteArray(Charsets.UTF_8)
                    val domainPacketBytes = ByteArrayOutputStream().apply {
                        write(byteArrayOf(0x00, 0x00, 0x00, 0x03, domainBytes.size.toByte()))
                        write(domainBytes)
                        write((echoPort ushr 8) and 0xFF)
                        write(echoPort and 0xFF)
                        write(domainPayload)
                    }.toByteArray()

                    clientUdp.send(DatagramPacket(domainPacketBytes, domainPacketBytes.size, relayAddress, relayPort))

                    val recvBuf2 = ByteArray(4096)
                    val recvPacket2 = DatagramPacket(recvBuf2, recvBuf2.size)
                    clientUdp.receive(recvPacket2)

                    val parsed2 = Socks5Handler.parseSocks5UdpPacket(recvBuf2, recvPacket2.length)
                    assertTrue("Reply 2 must be valid SOCKS5 UDP packet", parsed2 != null)
                    assertEquals(echoPort, parsed2!!.port)
                    val responseBody2 = String(
                        recvBuf2,
                        parsed2.dataOffset,
                        recvPacket2.length - parsed2.dataOffset,
                        Charsets.UTF_8,
                    )
                    assertEquals("ECHO:udp-domain-hello", responseBody2)
                }
            }

            waitForActiveConnectionsZero()
            val snapshot = statsTracker.snapshot.value
            assertTrue("UDP bytesUp should be recorded", snapshot.bytesUp >= 30L)
            assertTrue("UDP bytesDown should be recorded", snapshot.bytesDown >= 40L)
            assertEquals(0, snapshot.activeConnections)
        } finally {
            udpEchoSocket.close()
            echoThread.join(1_000)
        }
    }

    @Test
    fun trafficStatsTrackerTracksCountersAndSpeedRates() = runBlocking {
        val tracker = TrafficStatsTracker()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            tracker.start(scope)
            assertTrue(tracker.snapshot.value.startedAtEpochMs > 0L)

            tracker.onConnectionOpened()
            tracker.onConnectionOpened()
            assertEquals(2, tracker.snapshot.value.activeConnections)
            assertEquals(2L, tracker.snapshot.value.totalConnections)

            tracker.addBytesUp(2_048L)
            tracker.addBytesDown(4_096L)
            assertEquals(2_048L, tracker.snapshot.value.bytesUp)
            assertEquals(4_096L, tracker.snapshot.value.bytesDown)

            delay(1_150L)
            val ticked = tracker.snapshot.value
            assertTrue("uploadBytesPerSec should be > 0 after tick", ticked.uploadBytesPerSec > 0L)
            assertTrue("downloadBytesPerSec should be > 0 after tick", ticked.downloadBytesPerSec > 0L)

            tracker.onConnectionClosed()
            tracker.onConnectionClosed()
            tracker.onConnectionClosed() // Should clamp at 0
            assertEquals(0, tracker.snapshot.value.activeConnections)
            assertEquals(2L, tracker.snapshot.value.totalConnections)

            tracker.stop()
            assertEquals(0L, tracker.snapshot.value.uploadBytesPerSec)
            assertEquals(0L, tracker.snapshot.value.downloadBytesPerSec)

            tracker.reset()
            assertEquals(TrafficSnapshot(), tracker.snapshot.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pacEndpointServesValidAutoconfigScript() {
        // 1. GET /proxy.pac with explicit Host header
        Socket("127.0.0.1", proxyServer.boundPort).use { client ->
            client.soTimeout = 5_000
            val req = "GET /proxy.pac HTTP/1.1\r\nHost: 192.168.49.1:${proxyServer.boundPort}\r\n\r\n"
            client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
            client.getOutputStream().flush()

            val rawResponse = String(client.getInputStream().readBytes(), Charsets.UTF_8)
            val splitIdx = rawResponse.indexOf("\r\n\r\n")
            assertTrue("PAC response must have header/body separator", splitIdx > 0)

            val headerPart = rawResponse.substring(0, splitIdx)
            val bodyPart = rawResponse.substring(splitIdx + 4)

            assertTrue("Expected 200 OK, got: $headerPart", headerPart.startsWith("HTTP/1.1 200 OK"))
            assertTrue(
                "Expected PAC Content-Type, got: $headerPart",
                headerPart.contains("Content-Type: application/x-ns-proxy-autoconfig", ignoreCase = true),
            )
            assertTrue(
                "Expected Cache-Control: no-cache",
                headerPart.contains("Cache-Control: no-cache", ignoreCase = true),
            )
            assertTrue(
                "Expected FindProxyForURL function",
                bodyPart.contains("function FindProxyForURL(url, host)"),
            )
            assertTrue(
                "Expected PROXY directive with Host IP and boundPort, got: $bodyPart",
                bodyPart.contains("PROXY 192.168.49.1:${proxyServer.boundPort}; DIRECT"),
            )
        }

        // 2. GET /wpad.dat without Host header falls back to local socket IPv4
        Socket("127.0.0.1", proxyServer.boundPort).use { client ->
            client.soTimeout = 5_000
            val req = "GET /wpad.dat HTTP/1.1\r\n\r\n"
            client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
            client.getOutputStream().flush()

            val rawResponse = String(client.getInputStream().readBytes(), Charsets.UTF_8)
            assertTrue(rawResponse.startsWith("HTTP/1.1 200 OK"))
            assertTrue(rawResponse.contains("PROXY 127.0.0.1:${proxyServer.boundPort}; DIRECT"))
        }

        // 3. HEAD /proxy.pac returns headers with Content-Length and empty body
        Socket("127.0.0.1", proxyServer.boundPort).use { client ->
            client.soTimeout = 5_000
            val req = "HEAD /proxy.pac HTTP/1.1\r\nHost: 127.0.0.1:${proxyServer.boundPort}\r\n\r\n"
            client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
            client.getOutputStream().flush()

            val rawResponse = String(client.getInputStream().readBytes(), Charsets.UTF_8)
            val splitIdx = rawResponse.indexOf("\r\n\r\n")
            assertTrue(splitIdx > 0)
            val headerPart = rawResponse.substring(0, splitIdx)
            val bodyPart = rawResponse.substring(splitIdx + 4)
            assertTrue(headerPart.startsWith("HTTP/1.1 200 OK"))
            assertTrue(headerPart.contains("Content-Length:", ignoreCase = true))
            assertEquals("", bodyPart)
        }
    }

    @Test
    fun plainHttpWebSocketUpgradePreservesHeadersAndRelaysBidirectionalFrames() {
        val capturedHeaders = AtomicReference<Map<String, String>>()
        val originServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val originThread = thread(isDaemon = true) {
            try {
                originServer.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val headerLines = readHttpHeadersRaw(input)
                    val headers = mutableMapOf<String, String>()
                    for (line in headerLines.drop(1)) {
                        val idx = line.indexOf(':')
                        if (idx > 0) {
                            headers[line.substring(0, idx).trim().lowercase()] =
                                line.substring(idx + 1).trim()
                        }
                    }
                    capturedHeaders.set(headers)

                    val switchingResp = buildString {
                        append("HTTP/1.1 101 Switching Protocols\r\n")
                        append("Upgrade: websocket\r\n")
                        append("Connection: Upgrade\r\n")
                        append("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n")
                    }
                    output.write(switchingResp.toByteArray(Charsets.ISO_8859_1))
                    output.flush()

                    // Bidirectional frame relay after 101 upgrade
                    val frame1 = readExactBytes(input, 10)
                    output.write(("ACK1:" + String(frame1, Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
                    output.flush()

                    val frame2 = readExactBytes(input, 10)
                    output.write(("ACK2:" + String(frame2, Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (_: Exception) {
            }
        }

        try {
            Socket("127.0.0.1", proxyServer.boundPort).use { client ->
                client.soTimeout = 5_000
                val out = client.getOutputStream()
                val input = client.getInputStream()

                val wsRequest = buildString {
                    append("GET http://127.0.0.1:${originServer.localPort}/ws HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:${originServer.localPort}\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: keep-alive, Upgrade\r\n")
                    append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                    append("Sec-WebSocket-Version: 13\r\n\r\n")
                }
                out.write(wsRequest.toByteArray(Charsets.ISO_8859_1))
                out.flush()

                val responseHeaders = readHttpHeadersRaw(input)
                assertTrue(
                    "Expected 101 Switching Protocols, got: $responseHeaders",
                    responseHeaders.firstOrNull()?.startsWith("HTTP/1.1 101") == true,
                )

                // Exchange full-duplex frames over upgraded socket
                out.write("WS-FRAME-1".toByteArray(Charsets.UTF_8))
                out.flush()
                val reply1 = String(readExactBytes(input, 15), Charsets.UTF_8)
                assertEquals("ACK1:WS-FRAME-1", reply1)

                out.write("WS-FRAME-2".toByteArray(Charsets.UTF_8))
                out.flush()
                val reply2 = String(readExactBytes(input, 15), Charsets.UTF_8)
                assertEquals("ACK2:WS-FRAME-2", reply2)
            }

            val headers = capturedHeaders.get()
            assertEquals("websocket", headers["upgrade"]?.lowercase())
            assertEquals("upgrade", headers["connection"]?.lowercase())
            assertEquals("dGhlIHNhbXBsZSBub25jZQ==", headers["sec-websocket-key"])
        } finally {
            originServer.close()
            originThread.join(1_000)
        }
    }

    @Test
    fun socks4AndSocks4aConnectTunnelIpv4AndDomainTargets() {
        val echoServer = startTcpEchoServer()
        try {
            // 1. SOCKS4 (IPv4 127.0.0.1)
            Socket("127.0.0.1", proxyServer.boundPort).use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream()
                val out = client.getOutputStream()

                val socks4Req = ByteArrayOutputStream().apply {
                    write(0x04) // VN = 4
                    write(0x01) // CD = CONNECT
                    write((echoServer.port ushr 8) and 0xFF)
                    write(echoServer.port and 0xFF)
                    write(byteArrayOf(127, 0, 0, 1))
                    write("testuser".toByteArray(Charsets.UTF_8))
                    write(0x00) // null terminator for USERID
                }.toByteArray()
                out.write(socks4Req)
                out.flush()

                val reply = readExactBytes(input, 8)
                assertEquals(0x00, reply[0].toInt() and 0xFF)
                assertEquals(0x5A, reply[1].toInt() and 0xFF)

                val payload = "socks4-ipv4-echo-payload"
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                client.shutdownOutput()

                val echoed = String(input.readBytes(), Charsets.UTF_8)
                assertEquals(payload, echoed)
            }

            // 2. SOCKS4a (domain "localhost" via 0.0.0.1)
            Socket("127.0.0.1", proxyServer.boundPort).use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream()
                val out = client.getOutputStream()

                val socks4aReq = ByteArrayOutputStream().apply {
                    write(0x04) // VN = 4
                    write(0x01) // CD = CONNECT
                    write((echoServer.port ushr 8) and 0xFF)
                    write(echoServer.port and 0xFF)
                    write(byteArrayOf(0, 0, 0, 1)) // SOCKS4a marker
                    write(0x00) // empty USERID
                    write("localhost".toByteArray(Charsets.UTF_8))
                    write(0x00) // null terminator for DOMAIN
                }.toByteArray()
                out.write(socks4aReq)
                out.flush()

                val reply = readExactBytes(input, 8)
                assertEquals(0x00, reply[0].toInt() and 0xFF)
                assertEquals(0x5A, reply[1].toInt() and 0xFF)

                val payload = "socks4a-domain-localhost-echo-payload"
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                client.shutdownOutput()

                val echoed = String(input.readBytes(), Charsets.UTF_8)
                assertEquals(payload, echoed)
            }

            // 3. SOCKS4 unsupported command (BIND = 0x02) -> rejected with 0x5B
            Socket("127.0.0.1", proxyServer.boundPort).use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream()
                val out = client.getOutputStream()

                val bindReq = byteArrayOf(
                    0x04, 0x02,
                    ((echoServer.port ushr 8) and 0xFF).toByte(),
                    (echoServer.port and 0xFF).toByte(),
                    127, 0, 0, 1,
                    0x00,
                )
                out.write(bindReq)
                out.flush()

                val reply = readExactBytes(input, 8)
                assertEquals(0x00, reply[0].toInt() and 0xFF)
                assertEquals(0x5B, reply[1].toInt() and 0xFF)
            }
        } finally {
            echoServer.close()
        }
    }

    @Test
    fun socks5NormalizeLocalBindAddressUnwrapsIpv4MappedIpv6AndWildcards() {
        val mappedBytes = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0xFF.toByte(), 0xFF.toByte(),
            192.toByte(), 168.toByte(), 49, 1,
        )
        val ipv4MappedV6 = Inet6Address.getByAddress(null, mappedBytes, -1)
        val normalizedMapped = Socks5Handler.normalizeLocalBindAddress(ipv4MappedV6)
        assertTrue("Expected Inet4Address", normalizedMapped is Inet4Address)
        assertEquals(4, normalizedMapped.address.size)
        assertEquals("192.168.49.1", normalizedMapped.hostAddress)

        val wildcardV4 = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
        val normalizedWildcard = Socks5Handler.normalizeLocalBindAddress(wildcardV4)
        assertTrue("Wildcard must resolve to clean IPv4", normalizedWildcard is Inet4Address)
        assertEquals("127.0.0.1", normalizedWildcard.hostAddress)

        val loopbackV6 = InetAddress.getByName("::1")
        val normalizedLoopbackV6 = Socks5Handler.normalizeLocalBindAddress(loopbackV6)
        assertTrue("IPv6 loopback must normalize to IPv4 loopback", normalizedLoopbackV6 is Inet4Address)
        assertEquals("127.0.0.1", normalizedLoopbackV6.hostAddress)
    }

    @Test
    fun highConcurrencyStressTestHandles80SimultaneousTcpConnections() {
        val concurrency = 80
        val echoServer = startTcpEchoServer(backlog = 256)
        val allEstablished = CountDownLatch(concurrency)
        val releaseBarrier = CountDownLatch(1)
        val allCompleted = CountDownLatch(concurrency)
        val errors = CopyOnWriteArrayList<Throwable>()

        val workers = (0 until concurrency).map { index ->
            thread(isDaemon = true, name = "stress-client-$index") {
                try {
                    Socket("127.0.0.1", proxyServer.boundPort).use { client ->
                        client.soTimeout = 15_000
                        val out = client.getOutputStream()
                        val input = client.getInputStream()

                        if (index % 2 == 0) {
                            // HTTP CONNECT tunnel
                            val req =
                                "CONNECT 127.0.0.1:${echoServer.port} HTTP/1.1\r\nHost: 127.0.0.1:${echoServer.port}\r\n\r\n"
                            out.write(req.toByteArray(Charsets.ISO_8859_1))
                            out.flush()
                            val statusLines = readHttpHeadersRaw(input)
                            assertTrue(
                                "Client $index expected 200 OK, got $statusLines",
                                statusLines.firstOrNull()?.startsWith("HTTP/1.1 200") == true,
                            )
                        } else {
                            // SOCKS5 CONNECT tunnel
                            out.write(byteArrayOf(0x05, 0x01, 0x00))
                            out.flush()
                            val authReply = readExactBytes(input, 2)
                            assertEquals(0x00, authReply[1].toInt() and 0xFF)
                            val connectReq = byteArrayOf(
                                0x05, 0x01, 0x00, 0x01,
                                127, 0, 0, 1,
                                ((echoServer.port ushr 8) and 0xFF).toByte(),
                                (echoServer.port and 0xFF).toByte(),
                            )
                            out.write(connectReq)
                            out.flush()
                            val reply = readSocks5Reply(input)
                            assertEquals(0x00, reply.rep)
                        }

                        // Hold all 80 tunnels open simultaneously (160+ active proxy relay threads!)
                        allEstablished.countDown()
                        assertTrue(
                            "Timed out waiting for all $concurrency tunnels to establish",
                            releaseBarrier.await(15, TimeUnit.SECONDS),
                        )

                        val payload = "stress-payload-connection-$index-0123456789"
                        out.write(payload.toByteArray(Charsets.UTF_8))
                        out.flush()
                        client.shutdownOutput()

                        val echoed = String(input.readBytes(), Charsets.UTF_8)
                        assertEquals("Client $index echo mismatch", payload, echoed)
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                    allEstablished.countDown()
                } finally {
                    allCompleted.countDown()
                }
            }
        }

        try {
            assertTrue(
                "All $concurrency simultaneous connections must establish without thread starvation (remaining=${allEstablished.count}, errors=${errors.take(3)})",
                allEstablished.await(15, TimeUnit.SECONDS),
            )
            releaseBarrier.countDown()
            assertTrue(
                "All $concurrency connections must complete cleanly (remaining=${allCompleted.count})",
                allCompleted.await(15, TimeUnit.SECONDS),
            )
            assertTrue("Expected zero errors across $concurrency connections, got: $errors", errors.isEmpty())

            waitForActiveConnectionsZero()
            assertEquals(0, statsTracker.snapshot.value.activeConnections)
            assertTrue(statsTracker.snapshot.value.totalConnections >= concurrency.toLong())
        } finally {
            releaseBarrier.countDown()
            workers.forEach { it.join(2_000) }
            echoServer.close()
        }
    }

    @Test
    fun proxySecurityPolicyUnitChecksCoverSsrfCredentialsAndPacSanitization() {
        val defaultPolicy = ProxySecurityPolicy()
        assertTrue(defaultPolicy.blockLoopbackAndSelf)
        assertEquals(256, defaultPolicy.maxConcurrentConnections)
        assertFalse(defaultPolicy.authEnabled)

        val hostIp = InetAddress.getByName("192.168.49.1")
        val localAddresses = setOf(hostIp)

        // Blocked ranges: loopback, wildcard, link-local, IPv4-mapped IPv6, and self proxy port
        assertTrue(defaultPolicy.isTargetBlocked(InetAddress.getByName("127.0.0.1"), 80, 8080, localAddresses))
        assertTrue(defaultPolicy.isTargetBlocked(InetAddress.getByName("127.255.0.1"), 443, 8080, localAddresses))
        assertTrue(defaultPolicy.isTargetBlocked(InetAddress.getByName("::1"), 443, 8080, localAddresses))
        assertTrue(defaultPolicy.isTargetBlocked(InetAddress.getByName("0.0.0.0"), 80, 8080, localAddresses))
        assertTrue(defaultPolicy.isTargetBlocked(InetAddress.getByName("::"), 80, 8080, localAddresses))
        assertTrue(defaultPolicy.isTargetBlocked(InetAddress.getByName("169.254.169.254"), 80, 8080, localAddresses))
        assertTrue(defaultPolicy.isTargetBlocked(InetAddress.getByName("fe80::1"), 80, 8080, localAddresses))

        val mappedLoopback = Inet6Address.getByAddress(
            null,
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 127, 0, 0, 1),
            -1,
        )
        val mappedLinkLocal = Inet6Address.getByAddress(
            null,
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 169.toByte(), 254.toByte(), 1, 1),
            -1,
        )
        val mappedWildcard = Inet6Address.getByAddress(
            null,
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0),
            -1,
        )
        val mappedHost = Inet6Address.getByAddress(
            null,
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 192.toByte(), 168.toByte(), 49, 1),
            -1,
        )
        assertTrue(defaultPolicy.isTargetBlocked(mappedLoopback, 443, 8080, localAddresses))
        assertTrue(defaultPolicy.isTargetBlocked(mappedLinkLocal, 80, 8080, localAddresses))
        assertTrue(defaultPolicy.isTargetBlocked(mappedWildcard, 80, 8080, localAddresses))

        // Self-proxy loop check (same local IP + proxyBoundPort)
        assertTrue(defaultPolicy.isTargetBlocked(hostIp, 8080, 8080, localAddresses))
        assertTrue(defaultPolicy.isTargetBlocked(mappedHost, 8080, 8080, localAddresses))
        assertFalse(defaultPolicy.isTargetBlocked(hostIp, 9000, 8080, localAddresses))

        // Public address is allowed
        assertFalse(defaultPolicy.isTargetBlocked(InetAddress.getByName("8.8.8.8"), 53, 8080, localAddresses))
        assertFalse(defaultPolicy.isTargetBlocked(InetAddress.getByName("8.8.8.8"), 8080, 8080, localAddresses))

        // Disabled anti-SSRF allows loopback and self
        val permissivePolicy = ProxySecurityPolicy(blockLoopbackAndSelf = false)
        assertFalse(permissivePolicy.isTargetBlocked(InetAddress.getByName("127.0.0.1"), 8080, 8080, localAddresses))
        assertFalse(permissivePolicy.isTargetBlocked(hostIp, 8080, 8080, localAddresses))

        // Constant-time credential verification
        val authPolicy = ProxySecurityPolicy(
            authEnabled = true,
            authUsername = "router",
            authPassword = "p@ss:word-UTF8-\u00f1",
        )
        assertTrue(authPolicy.verifyCredentials("router", "p@ss:word-UTF8-\u00f1"))
        assertFalse(authPolicy.verifyCredentials("router", "wrong"))
        assertFalse(authPolicy.verifyCredentials("wrong", "p@ss:word-UTF8-\u00f1"))
        assertFalse(authPolicy.verifyCredentials("", ""))

        // PAC Host sanitization
        assertEquals("192.168.49.1", ProxySecurityPolicy.sanitizePacHost("192.168.49.1"))
        assertEquals("192.168.49.1:8080", ProxySecurityPolicy.sanitizePacHost("192.168.49.1:8080"))
        assertEquals("[2001:db8::1]:8080", ProxySecurityPolicy.sanitizePacHost("[2001:db8::1]:8080"))
        assertEquals("[::1]", ProxySecurityPolicy.sanitizePacHost("[::1]"))
        assertEquals("[2001:db8::1]", ProxySecurityPolicy.sanitizePacHost("2001:db8::1"))
        assertNull(ProxySecurityPolicy.sanitizePacHost("192.168.49.1\"; alert(1);//"))
        assertNull(ProxySecurityPolicy.sanitizePacHost("192.168.49.1:8080; DIRECT"))
        assertNull(ProxySecurityPolicy.sanitizePacHost("192.168.49.1 "))
        assertNull(ProxySecurityPolicy.sanitizePacHost("192.168.49.1:0"))
        assertNull(ProxySecurityPolicy.sanitizePacHost("192.168.49.1:65536"))
        assertNull(ProxySecurityPolicy.sanitizePacHost("192.168.01.1"))
        assertNull(ProxySecurityPolicy.sanitizePacHost("evil.example.com:8080"))
        assertNull(ProxySecurityPolicy.sanitizePacHost(""))
    }

    @Test
    fun antiSsrfBlocksLoopbackAcrossHttpSocks5Socks4AndUdpWhenEnabled() {
        val strictStats = TrafficStatsTracker()
        val strictProxy = HybridProxyServer(strictStats)
        val echoServer = startTcpEchoServer()
        val udpEchoSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val udpReceivedCount = AtomicInteger(0)
        val udpThread = thread(isDaemon = true) {
            val buf = ByteArray(1024)
            while (!udpEchoSocket.isClosed) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpEchoSocket.receive(pkt)
                    udpReceivedCount.incrementAndGet()
                    udpEchoSocket.send(DatagramPacket(pkt.data, pkt.length, pkt.address, pkt.port))
                } catch (_: Exception) {
                    break
                }
            }
        }

        try {
            runBlocking {
                strictProxy.start(
                    port = 0,
                    bindAddress = "127.0.0.1",
                    securityPolicy = ProxySecurityPolicy(blockLoopbackAndSelf = true),
                )
            }

            // 1. HTTP CONNECT 127.0.0.1:<port> -> 403 Forbidden
            Socket("127.0.0.1", strictProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val req = "CONNECT 127.0.0.1:${echoServer.port} HTTP/1.1\r\nHost: 127.0.0.1:${echoServer.port}\r\n\r\n"
                client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val response = String(client.getInputStream().readBytes(), Charsets.ISO_8859_1)
                assertTrue("Expected 403 Forbidden on HTTP CONNECT loopback, got: $response", response.startsWith("HTTP/1.1 403 Forbidden"))
            }

            // 2. HTTP GET http://127.0.0.1:<port>/ -> 403 Forbidden
            Socket("127.0.0.1", strictProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val req = "GET http://127.0.0.1:${echoServer.port}/secret HTTP/1.1\r\nHost: 127.0.0.1:${echoServer.port}\r\n\r\n"
                client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val response = String(client.getInputStream().readBytes(), Charsets.ISO_8859_1)
                assertTrue("Expected 403 Forbidden on HTTP GET loopback, got: $response", response.startsWith("HTTP/1.1 403 Forbidden"))
            }

            // 3. SOCKS5 CONNECT 127.0.0.1:<port> -> REP = 0x02 (Connection not allowed by ruleset)
            Socket("127.0.0.1", strictProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream()
                val out = client.getOutputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                assertEquals(0x00, readExactBytes(input, 2)[1].toInt() and 0xFF)

                out.write(
                    byteArrayOf(
                        0x05, 0x01, 0x00, 0x01,
                        127, 0, 0, 1,
                        ((echoServer.port ushr 8) and 0xFF).toByte(),
                        (echoServer.port and 0xFF).toByte(),
                    ),
                )
                out.flush()
                val reply = readSocks5Reply(input)
                assertEquals(0x05, reply.ver)
                assertEquals(0x02, reply.rep)
            }

            // 4. SOCKS5 CONNECT domain "localhost" -> REP = 0x02
            Socket("127.0.0.1", strictProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream()
                val out = client.getOutputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                assertEquals(0x00, readExactBytes(input, 2)[1].toInt() and 0xFF)

                val domain = "localhost".toByteArray(Charsets.UTF_8)
                val connectReq = ByteArrayOutputStream().apply {
                    write(byteArrayOf(0x05, 0x01, 0x00, 0x03, domain.size.toByte()))
                    write(domain)
                    write((echoServer.port ushr 8) and 0xFF)
                    write(echoServer.port and 0xFF)
                }.toByteArray()
                out.write(connectReq)
                out.flush()
                val reply = readSocks5Reply(input)
                assertEquals(0x02, reply.rep)
            }

            // 5. SOCKS4 CONNECT 127.0.0.1:<port> -> 0x00, 0x5B
            Socket("127.0.0.1", strictProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream()
                val out = client.getOutputStream()
                val req = byteArrayOf(
                    0x04, 0x01,
                    ((echoServer.port ushr 8) and 0xFF).toByte(),
                    (echoServer.port and 0xFF).toByte(),
                    127, 0, 0, 1,
                    0x00,
                )
                out.write(req)
                out.flush()
                val reply = readExactBytes(input, 8)
                assertEquals(0x00, reply[0].toInt() and 0xFF)
                assertEquals(0x5B, reply[1].toInt() and 0xFF)
            }

            // 6. SOCKS5 UDP ASSOCIATE to 127.0.0.1:<udpEchoPort> -> datagram dropped
            Socket("127.0.0.1", strictProxy.boundPort).use { controlSocket ->
                controlSocket.soTimeout = 5_000
                val input = controlSocket.getInputStream()
                val out = controlSocket.getOutputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                assertEquals(0x00, readExactBytes(input, 2)[1].toInt() and 0xFF)

                out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()
                val reply = readSocks5Reply(input)
                assertEquals(0x00, reply.rep)

                DatagramSocket().use { clientUdp ->
                    clientUdp.soTimeout = 400
                    val udpEchoPort = udpEchoSocket.localPort
                    val payload = "blocked-udp-loopback".toByteArray(Charsets.UTF_8)
                    val pktBytes = ByteArrayOutputStream().apply {
                        write(byteArrayOf(0x00, 0x00, 0x00, 0x01, 127, 0, 0, 1))
                        write((udpEchoPort ushr 8) and 0xFF)
                        write(udpEchoPort and 0xFF)
                        write(payload)
                    }.toByteArray()
                    clientUdp.send(DatagramPacket(pktBytes, pktBytes.size, reply.bindAddress, reply.bindPort))

                    var timedOut = false
                    try {
                        val recv = DatagramPacket(ByteArray(1024), 1024)
                        clientUdp.receive(recv)
                    } catch (_: SocketTimeoutException) {
                        timedOut = true
                    }
                    assertTrue("UDP datagram targeting 127.0.0.1 must be dropped", timedOut)
                    assertEquals("Local UDP server must not receive blocked datagram", 0, udpReceivedCount.get())
                }
            }
        } finally {
            strictProxy.stop()
            echoServer.close()
            udpEchoSocket.close()
            udpThread.join(1_000)
        }
    }

    @Test
    fun httpProxyAuthenticationEnforcesBasicAuthAndStripsCredentialsUpstream() {
        val authProxy = HybridProxyServer(TrafficStatsTracker())
        val echoServer = startTcpEchoServer()
        val capturedHeaders = AtomicReference<Map<String, String>>()
        val originServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val originThread = thread(isDaemon = true) {
            try {
                originServer.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                    reader.readLine() // request line
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx > 0) {
                            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                        }
                    }
                    capturedHeaders.set(headers)
                    val body = "auth-ok"
                    val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    socket.getOutputStream().write(resp.toByteArray(Charsets.ISO_8859_1))
                    socket.getOutputStream().flush()
                }
            } catch (_: Exception) {
            }
        }

        try {
            runBlocking {
                authProxy.start(
                    port = 0,
                    bindAddress = "127.0.0.1",
                    securityPolicy = ProxySecurityPolicy(
                        blockLoopbackAndSelf = false,
                        authEnabled = true,
                        authUsername = "router",
                        authPassword = "s3cr3t:pass",
                    ),
                )
            }

            // 1. CONNECT without credentials -> 407 Proxy Authentication Required
            Socket("127.0.0.1", authProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val req = "CONNECT 127.0.0.1:${echoServer.port} HTTP/1.1\r\nHost: 127.0.0.1:${echoServer.port}\r\n\r\n"
                client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val resp = String(client.getInputStream().readBytes(), Charsets.ISO_8859_1)
                assertTrue("Expected 407, got: $resp", resp.startsWith("HTTP/1.1 407 Proxy Authentication Required"))
                assertTrue("Expected Basic realm header, got: $resp", resp.contains("Proxy-Authenticate: Basic realm=\"DirectRouter\""))
            }

            // 2. CONNECT with wrong credentials -> 407
            val badToken = Base64.getEncoder().encodeToString("router:wrong".toByteArray(Charsets.UTF_8))
            Socket("127.0.0.1", authProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val req = buildString {
                    append("CONNECT 127.0.0.1:${echoServer.port} HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:${echoServer.port}\r\n")
                    append("Proxy-Authorization: Basic $badToken\r\n\r\n")
                }
                client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val resp = String(client.getInputStream().readBytes(), Charsets.ISO_8859_1)
                assertTrue("Expected 407 on wrong password, got: $resp", resp.startsWith("HTTP/1.1 407"))
            }

            // 3. CONNECT with valid credentials -> 200 and tunnel works
            val validToken = Base64.getEncoder().encodeToString("router:s3cr3t:pass".toByteArray(Charsets.UTF_8))
            Socket("127.0.0.1", authProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val out = client.getOutputStream()
                val input = client.getInputStream()
                val req = buildString {
                    append("CONNECT 127.0.0.1:${echoServer.port} HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:${echoServer.port}\r\n")
                    append("Proxy-Authorization: Basic $validToken\r\n\r\n")
                }
                out.write(req.toByteArray(Charsets.ISO_8859_1))
                out.flush()
                val headers = readHttpHeadersRaw(input)
                assertTrue("Expected 200 OK, got: $headers", headers.firstOrNull()?.startsWith("HTTP/1.1 200") == true)

                val payload = "authenticated-tunnel-data"
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                client.shutdownOutput()
                assertEquals(payload, String(input.readBytes(), Charsets.UTF_8))
            }

            // 4. Plain HTTP GET without credentials -> 407
            Socket("127.0.0.1", authProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val req = "GET http://127.0.0.1:${originServer.localPort}/check HTTP/1.1\r\nHost: 127.0.0.1:${originServer.localPort}\r\n\r\n"
                client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val resp = String(client.getInputStream().readBytes(), Charsets.ISO_8859_1)
                assertTrue("Expected 407 on unauthenticated GET, got: $resp", resp.startsWith("HTTP/1.1 407"))
            }

            // 5. Plain HTTP GET with valid credentials -> 200 OK and Proxy-Authorization stripped upstream
            Socket("127.0.0.1", authProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val req = buildString {
                    append("GET http://127.0.0.1:${originServer.localPort}/check HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:${originServer.localPort}\r\n")
                    append("Proxy-Authorization: Basic $validToken\r\n\r\n")
                }
                client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val resp = String(client.getInputStream().readBytes(), Charsets.ISO_8859_1)
                assertTrue("Expected 200 OK, got: $resp", resp.startsWith("HTTP/1.1 200 OK"))
                assertTrue(resp.endsWith("auth-ok"))
            }
            assertFalse(
                "Proxy-Authorization must never be forwarded upstream",
                capturedHeaders.get().containsKey("proxy-authorization"),
            )

            // 6. Local /proxy.pac remains readable for Android Wi-Fi PAC autoconfig
            Socket("127.0.0.1", authProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val req = "GET /proxy.pac HTTP/1.1\r\nHost: 127.0.0.1:${authProxy.boundPort}\r\n\r\n"
                client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val resp = String(client.getInputStream().readBytes(), Charsets.UTF_8)
                assertTrue(resp.startsWith("HTTP/1.1 200 OK"))
                assertTrue(resp.contains("PROXY 127.0.0.1:${authProxy.boundPort}; DIRECT"))
            }
        } finally {
            authProxy.stop()
            echoServer.close()
            originServer.close()
            originThread.join(1_000)
        }
    }

    @Test
    fun socks5AuthenticationEnforcesRfc1929AndRejectsSocks4WhenAuthEnabled() {
        val authProxy = HybridProxyServer(TrafficStatsTracker())
        val echoServer = startTcpEchoServer()
        try {
            runBlocking {
                authProxy.start(
                    port = 0,
                    bindAddress = "127.0.0.1",
                    securityPolicy = ProxySecurityPolicy(
                        blockLoopbackAndSelf = false,
                        authEnabled = true,
                        authUsername = "alice",
                        authPassword = "secret123",
                    ),
                )
            }

            // 1. SOCKS5 offering only NO_AUTH (0x00) -> rejected with 0x05, 0xFF
            Socket("127.0.0.1", authProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val out = client.getOutputStream()
                val input = client.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val reply = readExactBytes(input, 2)
                assertEquals(0x05, reply[0].toInt() and 0xFF)
                assertEquals(0xFF, reply[1].toInt() and 0xFF)
            }

            // 2. SOCKS5 offering 0x02 with wrong credentials -> 0x05 0x02, then 0x01 0x01
            Socket("127.0.0.1", authProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val out = client.getOutputStream()
                val input = client.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x02))
                out.flush()
                val methodReply = readExactBytes(input, 2)
                assertEquals(0x05, methodReply[0].toInt() and 0xFF)
                assertEquals(0x02, methodReply[1].toInt() and 0xFF)

                val user = "alice".toByteArray(Charsets.UTF_8)
                val badPass = "wrongpass".toByteArray(Charsets.UTF_8)
                val authReq = ByteArrayOutputStream().apply {
                    write(0x01)
                    write(user.size)
                    write(user)
                    write(badPass.size)
                    write(badPass)
                }.toByteArray()
                out.write(authReq)
                out.flush()

                val authStatus = readExactBytes(input, 2)
                assertEquals(0x01, authStatus[0].toInt() and 0xFF)
                assertEquals(0x01, authStatus[1].toInt() and 0xFF)
            }

            // 3. SOCKS5 with valid RFC 1929 credentials -> 0x05 0x02, 0x01 0x00, CONNECT succeeds
            Socket("127.0.0.1", authProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val out = client.getOutputStream()
                val input = client.getInputStream()
                out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
                out.flush()
                val methodReply = readExactBytes(input, 2)
                assertEquals(0x05, methodReply[0].toInt() and 0xFF)
                assertEquals(0x02, methodReply[1].toInt() and 0xFF)

                val user = "alice".toByteArray(Charsets.UTF_8)
                val pass = "secret123".toByteArray(Charsets.UTF_8)
                val authReq = ByteArrayOutputStream().apply {
                    write(0x01)
                    write(user.size)
                    write(user)
                    write(pass.size)
                    write(pass)
                }.toByteArray()
                out.write(authReq)
                out.flush()

                val authStatus = readExactBytes(input, 2)
                assertEquals(0x01, authStatus[0].toInt() and 0xFF)
                assertEquals(0x00, authStatus[1].toInt() and 0xFF)

                out.write(
                    byteArrayOf(
                        0x05, 0x01, 0x00, 0x01,
                        127, 0, 0, 1,
                        ((echoServer.port ushr 8) and 0xFF).toByte(),
                        (echoServer.port and 0xFF).toByte(),
                    ),
                )
                out.flush()
                val connectReply = readSocks5Reply(input)
                assertEquals(0x00, connectReply.rep)

                val payload = "socks5-rfc1929-auth-ok"
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                client.shutdownOutput()
                assertEquals(payload, String(input.readBytes(), Charsets.UTF_8))
            }

            // 4. SOCKS4 rejected immediately when authEnabled = true
            Socket("127.0.0.1", authProxy.boundPort).use { client ->
                client.soTimeout = 5_000
                val out = client.getOutputStream()
                val input = client.getInputStream()
                val socks4Req = byteArrayOf(
                    0x04, 0x01,
                    ((echoServer.port ushr 8) and 0xFF).toByte(),
                    (echoServer.port and 0xFF).toByte(),
                    127, 0, 0, 1,
                    0x00,
                )
                out.write(socks4Req)
                out.flush()

                val reply = readExactBytes(input, 8)
                assertEquals(0x00, reply[0].toInt() and 0xFF)
                assertEquals(0x5B, reply[1].toInt() and 0xFF)
                assertEquals(-1, input.read())
            }
        } finally {
            authProxy.stop()
            echoServer.close()
        }
    }

    @Test
    fun concurrentConnectionLimitRejectsExcessAndReleasesPermitsOnClose() {
        val limitedStats = TrafficStatsTracker()
        val limitedProxy = HybridProxyServer(limitedStats)
        val echoServer = startTcpEchoServer()
        val tunnel1: Socket
        val tunnel2: Socket
        try {
            runBlocking {
                limitedProxy.start(
                    port = 0,
                    bindAddress = "127.0.0.1",
                    securityPolicy = ProxySecurityPolicy(
                        blockLoopbackAndSelf = false,
                        maxConcurrentConnections = 2,
                    ),
                )
            }

            fun openConnectTunnel(): Socket {
                val sock = Socket("127.0.0.1", limitedProxy.boundPort)
                sock.soTimeout = 5_000
                val req = "CONNECT 127.0.0.1:${echoServer.port} HTTP/1.1\r\nHost: 127.0.0.1:${echoServer.port}\r\n\r\n"
                sock.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                sock.getOutputStream().flush()
                val headers = readHttpHeadersRaw(sock.getInputStream())
                assertTrue("Expected 200 Connection Established, got: $headers", headers.firstOrNull()?.startsWith("HTTP/1.1 200") == true)
                return sock
            }

            tunnel1 = openConnectTunnel()
            tunnel2 = openConnectTunnel()
            try {
                // 3rd connection must be rejected cleanly while 2 permits are held
                Socket("127.0.0.1", limitedProxy.boundPort).use { excessClient ->
                    excessClient.soTimeout = 2_000
                    val rejected = try {
                        val req = "CONNECT 127.0.0.1:${echoServer.port} HTTP/1.1\r\nHost: 127.0.0.1:${echoServer.port}\r\n\r\n"
                        excessClient.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                        excessClient.getOutputStream().flush()
                        val firstRead = excessClient.getInputStream().read()
                        firstRead == -1
                    } catch (_: IOException) {
                        true
                    }
                    assertTrue("3rd concurrent connection must be rejected when limit is 2", rejected)
                }
            } finally {
                runCatching { tunnel1.close() }
                runCatching { tunnel2.close() }
            }

            // Wait for permits and active connections to release
            val deadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < deadline && limitedStats.snapshot.value.activeConnections > 0) {
                Thread.sleep(25L)
            }
            assertEquals(0, limitedStats.snapshot.value.activeConnections)

            // New connection must now succeed
            openConnectTunnel().use { tunnel3 ->
                val payload = "permit-released-ok"
                tunnel3.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
                tunnel3.getOutputStream().flush()
                tunnel3.shutdownOutput()
                val echoed = String(tunnel3.getInputStream().readBytes(), Charsets.UTF_8)
                assertEquals(payload, echoed)
            }
        } finally {
            limitedProxy.stop()
            echoServer.close()
        }
    }

    @Test
    fun pacEndpointSanitizesMaliciousHostHeaderAndFallsBackToLocalIp() {
        // 1. JS injection payload in Host header is rejected and replaced with safe local socket IP
        Socket("127.0.0.1", proxyServer.boundPort).use { client ->
            client.soTimeout = 5_000
            val maliciousHost = "192.168.49.1\"; alert(1);//"
            val req = "GET /proxy.pac HTTP/1.1\r\nHost: $maliciousHost\r\n\r\n"
            client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
            client.getOutputStream().flush()

            val rawResponse = String(client.getInputStream().readBytes(), Charsets.UTF_8)
            assertTrue("Expected 200 OK, got: $rawResponse", rawResponse.startsWith("HTTP/1.1 200 OK"))
            assertFalse("PAC must not reflect JS injection payload", rawResponse.contains("alert(1)"))
            assertFalse("PAC must not reflect unvalidated Host IP", rawResponse.contains("192.168.49.1"))
            assertTrue(
                "PAC must fall back to local socket IP, got: $rawResponse",
                rawResponse.contains("PROXY 127.0.0.1:${proxyServer.boundPort}; DIRECT"),
            )
        }

        // 2. Valid bracketed IPv6 Host header is preserved safely
        Socket("127.0.0.1", proxyServer.boundPort).use { client ->
            client.soTimeout = 5_000
            val req = "GET /proxy.pac HTTP/1.1\r\nHost: [2001:db8::1]:${proxyServer.boundPort}\r\n\r\n"
            client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
            client.getOutputStream().flush()

            val rawResponse = String(client.getInputStream().readBytes(), Charsets.UTF_8)
            assertTrue("Expected 200 OK, got: $rawResponse", rawResponse.startsWith("HTTP/1.1 200 OK"))
            assertTrue(
                "PAC must properly format bracketed IPv6 host, got: $rawResponse",
                rawResponse.contains("PROXY [2001:db8::1]:${proxyServer.boundPort}; DIRECT"),
            )
        }
    }

    private class TcpEchoServer(
        private val serverSocket: ServerSocket,
        private val acceptThread: Thread,
    ) : AutoCloseable {
        val port: Int get() = serverSocket.localPort

        override fun close() {
            runCatching { serverSocket.close() }
            acceptThread.join(1_000)
        }
    }

    private fun startTcpEchoServer(backlog: Int = 64): TcpEchoServer {
        val server = ServerSocket(0, backlog, InetAddress.getByName("127.0.0.1"))
        val acceptThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) {
                    client.use { socket ->
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        val buf = ByteArray(4096)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            output.flush()
                        }
                    }
                }
            }
        }
        return TcpEchoServer(server, acceptThread)
    }

    private data class Socks5Reply(
        val ver: Int,
        val rep: Int,
        val bindAddress: InetAddress,
        val bindPort: Int,
    )

    private fun readSocks5Reply(input: InputStream): Socks5Reply {
        val header = readExactBytes(input, 4)
        val ver = header[0].toInt() and 0xFF
        val rep = header[1].toInt() and 0xFF
        val atyp = header[3].toInt() and 0xFF
        val addr = when (atyp) {
            0x01 -> InetAddress.getByAddress(readExactBytes(input, 4))
            0x04 -> InetAddress.getByAddress(readExactBytes(input, 16))
            0x03 -> {
                val len = input.read()
                val domain = String(readExactBytes(input, len), Charsets.UTF_8)
                InetAddress.getByName(domain)
            }
            else -> error("Unexpected ATYP: $atyp")
        }
        val portBytes = readExactBytes(input, 2)
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        return Socks5Reply(ver, rep, addr, port)
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read == -1) error("Unexpected EOF after $offset/$count bytes")
            offset += read
        }
        return buf
    }

    private fun readHttpHeadersRaw(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        val current = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) {
                val bytes = current.toByteArray()
                current.reset()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                val line = String(bytes, 0, len, Charsets.ISO_8859_1)
                if (line.isEmpty()) break
                lines.add(line)
            } else {
                current.write(b)
            }
        }
        return lines
    }

    private fun waitForActiveConnectionsZero() {
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            if (statsTracker.snapshot.value.activeConnections == 0) return
            Thread.sleep(25L)
        }
    }
}
