package com.directrouter.app.proxy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Base64

internal object HttpProxyHandler {
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_HEADER_LINES = 200
    private const val DEFAULT_HTTP_PORT = 80
    private const val DEFAULT_HTTPS_PORT = 443

    private val HOP_BY_HOP_HEADERS = setOf(
        "proxy-connection",
        "proxy-authorization",
        "proxy-authenticate",
        "connection",
        "keep-alive",
        "te",
        "trailers",
    )

    private val PAC_PATHS = setOf("/proxy.pac", "/wpad.dat")

    private val RESPONSE_200_ESTABLISHED =
        "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII)
    private val RESPONSE_400_BAD_REQUEST =
        "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
    private val RESPONSE_403_FORBIDDEN =
        "HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
    private val RESPONSE_407_PROXY_AUTH_REQUIRED =
        "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"DirectRouter\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
    private val RESPONSE_502_BAD_GATEWAY =
        "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)

    private data class ParsedHttpRequest(
        val method: String,
        val target: String,
        val version: String,
        val headers: List<Pair<String, String>>,
    )

    internal data class HostPort(
        val host: String,
        val port: Int,
    )

    private data class HttpTarget(
        val host: String,
        val port: Int,
        val originFormPath: String,
    )

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
        val request = try {
            readHttpRequest(clientIn)
        } catch (_: HeaderTooLargeException) {
            writeResponseQuietly(clientOut, RESPONSE_400_BAD_REQUEST)
            return
        } catch (_: IOException) {
            return
        } ?: return

        // PAC endpoints are local autoconfig fetches made by Android Wi-Fi settings without Proxy-Authorization.
        if (isPacRequest(request)) {
            handlePacRequest(request, clientSocket, clientOut, statsTracker, proxyBoundPort)
            return
        }

        if (securityPolicy.authEnabled && !isProxyAuthorized(request, securityPolicy)) {
            writeResponseQuietly(clientOut, RESPONSE_407_PROXY_AUTH_REQUIRED)
            return
        }

        if (request.method.equals("CONNECT", ignoreCase = true)) {
            handleConnect(
                request = request,
                clientSocket = clientSocket,
                clientIn = clientIn,
                clientOut = clientOut,
                statsTracker = statsTracker,
                dispatcher = dispatcher,
                securityPolicy = securityPolicy,
                proxyBoundPort = proxyBoundPort,
                localAddresses = localAddresses,
            )
        } else {
            handlePlainHttp(
                request = request,
                clientSocket = clientSocket,
                clientIn = clientIn,
                clientOut = clientOut,
                statsTracker = statsTracker,
                dispatcher = dispatcher,
                securityPolicy = securityPolicy,
                proxyBoundPort = proxyBoundPort,
                localAddresses = localAddresses,
            )
        }
    }

    private fun isProxyAuthorized(
        request: ParsedHttpRequest,
        securityPolicy: ProxySecurityPolicy,
    ): Boolean {
        val authHeader = request.headers
            .firstOrNull { it.first.equals("Proxy-Authorization", ignoreCase = true) }
            ?.second
            ?: return false

        val spaceIdx = authHeader.indexOf(' ')
        if (spaceIdx <= 0) return false
        val scheme = authHeader.substring(0, spaceIdx)
        if (!scheme.equals("Basic", ignoreCase = true)) return false

        val encoded = authHeader.substring(spaceIdx + 1).trim()
        if (encoded.isEmpty()) return false

        val decodedBytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return false
        val decoded = String(decodedBytes, Charsets.UTF_8)
        val colonIdx = decoded.indexOf(':')
        if (colonIdx < 0) return false

        val username = decoded.substring(0, colonIdx)
        val password = decoded.substring(colonIdx + 1)
        return securityPolicy.verifyCredentials(username, password)
    }

    private fun isPacRequest(request: ParsedHttpRequest): Boolean {
        val method = request.method.uppercase()
        if (method != "GET" && method != "HEAD") return false
        if (!request.target.startsWith("/")) return false
        val pathOnly = request.target.substringBefore('?').lowercase()
        return pathOnly in PAC_PATHS
    }

    private fun handlePacRequest(
        request: ParsedHttpRequest,
        clientSocket: Socket,
        clientOut: OutputStream,
        statsTracker: TrafficStatsTracker,
        proxyBoundPort: Int,
    ) {
        val hostHeader = request.headers
            .firstOrNull { it.first.equals("Host", ignoreCase = true) }
            ?.second
        val sanitizedHostHeader = hostHeader?.let { ProxySecurityPolicy.sanitizePacHost(it) }
        val proxyPort = if (proxyBoundPort in 1..65535) proxyBoundPort else clientSocket.localPort

        val formattedHost = if (sanitizedHostHeader != null) {
            extractSanitizedHostOnly(sanitizedHostHeader)
        } else {
            val fallbackHost = Socks5Handler.normalizeLocalBindAddress(clientSocket.localAddress)
                .hostAddress ?: "127.0.0.1"
            if (fallbackHost.contains(':') && !fallbackHost.startsWith('[')) {
                "[$fallbackHost]"
            } else {
                fallbackHost
            }
        }

        val pacBody = buildString {
            append("function FindProxyForURL(url, host) {\n")
            append("  if (isPlainHostName(host) || shExpMatch(host, \"*.local\") ||\n")
            append("      isInNet(dnsResolve(host), \"127.0.0.0\", \"255.0.0.0\") ||\n")
            append("      isInNet(dnsResolve(host), \"192.168.0.0\", \"255.255.0.0\") ||\n")
            append("      isInNet(dnsResolve(host), \"10.0.0.0\", \"255.0.0.0\")) {\n")
            append("    return \"DIRECT\";\n")
            append("  }\n")
            append("  return \"PROXY ").append(formattedHost).append(':').append(proxyPort).append("; DIRECT\";\n")
            append("}\n")
        }
        val bodyBytes = pacBody.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/x-ns-proxy-autoconfig\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: close\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)

        val isHead = request.method.equals("HEAD", ignoreCase = true)
        runCatching {
            clientOut.write(headers)
            if (!isHead) {
                clientOut.write(bodyBytes)
            }
            clientOut.flush()
            val sent = headers.size.toLong() + if (isHead) 0L else bodyBytes.size.toLong()
            statsTracker.addBytesDown(sent)
        }
    }

    private fun extractSanitizedHostOnly(sanitizedHostHeader: String): String {
        if (sanitizedHostHeader.startsWith('[')) {
            val closeIdx = sanitizedHostHeader.indexOf(']')
            val inner = sanitizedHostHeader.substring(1, closeIdx)
            val stripped = stripIpv4MappedPrefix(inner)
            return if (stripped.contains(':')) "[$stripped]" else stripped
        }
        val hostOnly = if (sanitizedHostHeader.count { it == ':' } == 1) {
            sanitizedHostHeader.substringBefore(':')
        } else {
            sanitizedHostHeader
        }
        val stripped = stripIpv4MappedPrefix(hostOnly)
        return if (stripped.contains(':')) "[$stripped]" else stripped
    }

    private fun stripIpv4MappedPrefix(host: String): String {
        val lower = host.lowercase()
        return if (lower.startsWith("::ffff:")) {
            host.substring(7)
        } else {
            host
        }
    }

    private suspend fun handleConnect(
        request: ParsedHttpRequest,
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        statsTracker: TrafficStatsTracker,
        dispatcher: CoroutineDispatcher,
        securityPolicy: ProxySecurityPolicy,
        proxyBoundPort: Int,
        localAddresses: Set<InetAddress>,
    ) {
        val hostPort = parseAuthority(request.target, DEFAULT_HTTPS_PORT)
        if (hostPort == null) {
            writeResponseQuietly(clientOut, RESPONSE_400_BAD_REQUEST)
            return
        }

        val remoteSocket = when (
            val result = OutboundConnector.connectWithResult(
                host = hostPort.host,
                port = hostPort.port,
                securityPolicy = securityPolicy,
                proxyBoundPort = proxyBoundPort,
                localAddresses = localAddresses,
            )
        ) {
            is OutboundConnector.ConnectResult.Success -> result.socket
            is OutboundConnector.ConnectResult.Blocked -> {
                writeResponseQuietly(clientOut, RESPONSE_403_FORBIDDEN)
                return
            }
            is OutboundConnector.ConnectResult.Failure -> {
                writeResponseQuietly(clientOut, RESPONSE_502_BAD_GATEWAY)
                return
            }
        }

        try {
            clientOut.write(RESPONSE_200_ESTABLISHED)
            clientOut.flush()

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

    private suspend fun handlePlainHttp(
        request: ParsedHttpRequest,
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        statsTracker: TrafficStatsTracker,
        dispatcher: CoroutineDispatcher,
        securityPolicy: ProxySecurityPolicy,
        proxyBoundPort: Int,
        localAddresses: Set<InetAddress>,
    ) {
        val target = resolveHttpTarget(request.target, request.headers)
        if (target == null) {
            writeResponseQuietly(clientOut, RESPONSE_400_BAD_REQUEST)
            return
        }

        val remoteSocket = when (
            val result = OutboundConnector.connectWithResult(
                host = target.host,
                port = target.port,
                securityPolicy = securityPolicy,
                proxyBoundPort = proxyBoundPort,
                localAddresses = localAddresses,
            )
        ) {
            is OutboundConnector.ConnectResult.Success -> result.socket
            is OutboundConnector.ConnectResult.Blocked -> {
                writeResponseQuietly(clientOut, RESPONSE_403_FORBIDDEN)
                return
            }
            is OutboundConnector.ConnectResult.Failure -> {
                writeResponseQuietly(clientOut, RESPONSE_502_BAD_GATEWAY)
                return
            }
        }

        try {
            val remoteOut = remoteSocket.getOutputStream()
            val remoteIn = remoteSocket.getInputStream()

            val isUpgrade = request.headers.any { (name, value) ->
                name.equals("Upgrade", ignoreCase = true) && value.isNotBlank()
            }

            val headerBytes = buildForwardedRequestHeader(request, target, isUpgrade)
            remoteOut.write(headerBytes)
            remoteOut.flush()
            statsTracker.addBytesUp(headerBytes.size.toLong())

            if (isUpgrade) {
                HybridProxyServer.relayTcpBidirectional(
                    clientSocket = clientSocket,
                    clientIn = clientIn,
                    clientOut = clientOut,
                    remoteSocket = remoteSocket,
                    remoteIn = remoteIn,
                    remoteOut = remoteOut,
                    statsTracker = statsTracker,
                    dispatcher = dispatcher,
                )
                return
            }

            runCatching { clientSocket.soTimeout = HybridProxyServer.RELAY_IDLE_TIMEOUT_MS }
            runCatching { remoteSocket.soTimeout = HybridProxyServer.RELAY_IDLE_TIMEOUT_MS }

            val transferEncoding = request.headers
                .firstOrNull { it.first.equals("Transfer-Encoding", ignoreCase = true) }
                ?.second
                ?.lowercase()
            val isChunked = transferEncoding?.contains("chunked") == true
            val contentLength = request.headers
                .firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }
                ?.second
                ?.trim()
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L

            coroutineScope {
                val requestBodyJob = when {
                    isChunked -> launch(dispatcher) {
                        runCatching {
                            copyChunkedBody(clientIn, remoteOut, statsTracker)
                        }
                    }

                    contentLength > 0L -> launch(dispatcher) {
                        runCatching {
                            copyFixedLengthBody(clientIn, remoteOut, contentLength, statsTracker)
                        }
                    }

                    else -> null
                }

                // Stream upstream response back to client until upstream EOF.
                val buffer = ByteArray(HybridProxyServer.RELAY_BUFFER_SIZE)
                try {
                    while (isActive) {
                        val read = try {
                            remoteIn.read(buffer)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        if (read <= 0) break
                        clientOut.write(buffer, 0, read)
                        clientOut.flush()
                        statsTracker.addBytesDown(read.toLong())
                    }
                } catch (_: IOException) {
                    // Upstream or client closed.
                } finally {
                    if (requestBodyJob != null && requestBodyJob.isActive) {
                        requestBodyJob.cancel()
                        runCatching {
                            if (!clientSocket.isClosed && !clientSocket.isInputShutdown) {
                                clientSocket.shutdownInput()
                            }
                        }
                    }
                    runCatching {
                        if (!clientSocket.isClosed && !clientSocket.isOutputShutdown) {
                            clientSocket.shutdownOutput()
                        }
                    }
                }
            }
        } finally {
            runCatching { remoteSocket.close() }
        }
    }

    private fun buildForwardedRequestHeader(
        request: ParsedHttpRequest,
        target: HttpTarget,
        isUpgrade: Boolean,
    ): ByteArray {
        val sb = StringBuilder(512)
        sb.append(request.method)
            .append(' ')
            .append(target.originFormPath)
            .append(' ')
            .append(request.version)
            .append("\r\n")

        var hasHostHeader = false
        for ((name, value) in request.headers) {
            val lower = name.lowercase()
            if (lower in HOP_BY_HOP_HEADERS) continue
            if (lower == "host") {
                hasHostHeader = true
            }
            sb.append(name).append(": ").append(value).append("\r\n")
        }

        if (!hasHostHeader) {
            val formattedHost = if (target.host.contains(':') && !target.host.startsWith('[')) {
                "[${target.host}]"
            } else {
                target.host
            }
            val hostValue = if (target.port == DEFAULT_HTTP_PORT) {
                formattedHost
            } else {
                "$formattedHost:${target.port}"
            }
            sb.append("Host: ").append(hostValue).append("\r\n")
        }

        if (isUpgrade) {
            sb.append("Connection: Upgrade\r\n\r\n")
        } else {
            sb.append("Connection: close\r\n\r\n")
        }
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun copyFixedLengthBody(
        input: InputStream,
        output: OutputStream,
        contentLength: Long,
        statsTracker: TrafficStatsTracker,
    ) {
        val buffer = ByteArray(HybridProxyServer.RELAY_BUFFER_SIZE)
        var remaining = contentLength
        while (remaining > 0L) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break
            output.write(buffer, 0, read)
            output.flush()
            statsTracker.addBytesUp(read.toLong())
            remaining -= read
        }
    }

    private fun copyChunkedBody(
        input: InputStream,
        output: OutputStream,
        statsTracker: TrafficStatsTracker,
    ) {
        val buffer = ByteArray(HybridProxyServer.RELAY_BUFFER_SIZE)
        while (true) {
            val sizeLine = readLineRaw(input, MAX_HEADER_BYTES) ?: break
            output.write(sizeLine)
            statsTracker.addBytesUp(sizeLine.size.toLong())

            val lineText = String(sizeLine, Charsets.ISO_8859_1).trim()
            val sizeHex = lineText.substringBefore(';').trim()
            val chunkSize = sizeHex.toLongOrNull(16) ?: break

            if (chunkSize == 0L) {
                // Forward trailing headers until empty line.
                while (true) {
                    val trailerLine = readLineRaw(input, MAX_HEADER_BYTES) ?: break
                    output.write(trailerLine)
                    statsTracker.addBytesUp(trailerLine.size.toLong())
                    val trimmed = String(trailerLine, Charsets.ISO_8859_1).trim()
                    if (trimmed.isEmpty()) break
                }
                output.flush()
                break
            }

            var remaining = chunkSize
            while (remaining > 0L) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read <= 0) return
                output.write(buffer, 0, read)
                statsTracker.addBytesUp(read.toLong())
                remaining -= read
            }

            val crlf = readLineRaw(input, 16) ?: break
            output.write(crlf)
            output.flush()
            statsTracker.addBytesUp(crlf.size.toLong())
        }
    }

    private fun readHttpRequest(input: InputStream): ParsedHttpRequest? {
        var totalBytes = 0
        val requestLine = readHeaderLine(input, MAX_HEADER_BYTES) ?: return null
        totalBytes += requestLine.length + 2
        if (requestLine.isEmpty()) return null

        val parts = requestLine.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 3) {
            throw HeaderTooLargeException("Malformed request line")
        }

        val method = parts[0]
        val target = parts[1]
        val version = parts[2]
        val headers = mutableListOf<Pair<String, String>>()

        while (true) {
            if (headers.size >= MAX_HEADER_LINES || totalBytes > MAX_HEADER_BYTES) {
                throw HeaderTooLargeException("Header limit exceeded")
            }
            val line = readHeaderLine(input, MAX_HEADER_BYTES - totalBytes) ?: break
            totalBytes += line.length + 2
            if (line.isEmpty()) break

            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val name = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                if (name.isNotEmpty()) {
                    headers.add(name to value)
                }
            }
        }

        return ParsedHttpRequest(
            method = method,
            target = target,
            version = version,
            headers = headers,
        )
    }

    private fun readHeaderLine(input: InputStream, maxBytes: Int): String? {
        val out = ByteArrayOutputStream(128)
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sawAny) out.toString(Charsets.ISO_8859_1.name()) else null
            }
            sawAny = true
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val length = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, length, Charsets.ISO_8859_1)
            }
            if (out.size() >= maxBytes) {
                throw HeaderTooLargeException("Header line exceeds maximum allowed bytes")
            }
            out.write(b)
        }
    }

    private fun readLineRaw(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream(64)
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sawAny) out.toByteArray() else null
            }
            sawAny = true
            out.write(b)
            if (b == '\n'.code) {
                return out.toByteArray()
            }
            if (out.size() >= maxBytes) {
                throw HeaderTooLargeException("Line exceeds limit")
            }
        }
    }

    internal fun parseAuthority(authority: String, defaultPort: Int): HostPort? {
        val trimmed = authority.trim()
        if (trimmed.isEmpty()) return null

        val host: String
        val port: Int

        if (trimmed.startsWith('[')) {
            val closeBracket = trimmed.indexOf(']')
            if (closeBracket <= 1) return null
            host = trimmed.substring(1, closeBracket)
            val rest = trimmed.substring(closeBracket + 1)
            port = when {
                rest.isEmpty() -> defaultPort
                rest.startsWith(':') -> rest.substring(1).toIntOrNull() ?: return null
                else -> return null
            }
        } else {
            val colonCount = trimmed.count { it == ':' }
            if (colonCount == 1) {
                val idx = trimmed.indexOf(':')
                host = trimmed.substring(0, idx)
                port = trimmed.substring(idx + 1).toIntOrNull() ?: return null
            } else if (colonCount == 0) {
                host = trimmed
                port = defaultPort
            } else {
                // Bare IPv6 address without port
                host = trimmed
                port = defaultPort
            }
        }

        if (host.isBlank() || port !in 1..65535) return null
        return HostPort(host, port)
    }

    private fun resolveHttpTarget(
        target: String,
        headers: List<Pair<String, String>>,
    ): HttpTarget? {
        val lower = target.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val defaultPort = if (lower.startsWith("https://")) DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
            val parsedUri = runCatching { URI(target) }.getOrNull()
            if (parsedUri != null && !parsedUri.host.isNullOrBlank()) {
                val host = parsedUri.host.removePrefix("[").removeSuffix("]")
                val port = if (parsedUri.port > 0) parsedUri.port else defaultPort
                if (port !in 1..65535) return null
                val rawPath = parsedUri.rawPath.orEmpty().ifEmpty { "/" }
                val rawQuery = parsedUri.rawQuery
                val originPath = if (rawQuery != null) "$rawPath?$rawQuery" else rawPath
                return HttpTarget(host = host, port = port, originFormPath = originPath)
            }

            // Fallback manual parsing for URLs with characters rejected by java.net.URI
            val schemeEnd = target.indexOf("://")
            val afterScheme = target.substring(schemeEnd + 3)
            val pathStart = afterScheme.indexOfFirst { it == '/' || it == '?' }
            val authorityPart = if (pathStart == -1) afterScheme else afterScheme.substring(0, pathStart)
            val rawPathPart = when {
                pathStart == -1 -> "/"
                afterScheme[pathStart] == '?' -> "/" + afterScheme.substring(pathStart)
                else -> afterScheme.substring(pathStart)
            }
            val hostPort = parseAuthority(authorityPart.substringAfter('@'), defaultPort) ?: return null
            return HttpTarget(
                host = hostPort.host,
                port = hostPort.port,
                originFormPath = rawPathPart,
            )
        }

        // Origin-form request (e.g. "GET /path HTTP/1.1" with Host header)
        val hostHeader = headers
            .firstOrNull { it.first.equals("Host", ignoreCase = true) }
            ?.second
            ?: return null
        val hostPort = parseAuthority(hostHeader, DEFAULT_HTTP_PORT) ?: return null
        val originPath = if (target.startsWith("/")) target else "/$target"
        return HttpTarget(
            host = hostPort.host,
            port = hostPort.port,
            originFormPath = originPath,
        )
    }

    private fun writeResponseQuietly(output: OutputStream, bytes: ByteArray) {
        runCatching {
            output.write(bytes)
            output.flush()
        }
    }

    private class HeaderTooLargeException(message: String) : IOException(message)
}
