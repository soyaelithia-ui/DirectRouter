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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
            proxyServer.start(port = 0, bindAddress = "127.0.0.1")
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
