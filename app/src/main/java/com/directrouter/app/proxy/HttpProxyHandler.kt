package com.directrouter.app.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

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

    private val RESPONSE_200_ESTABLISHED =
        "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII)
    private val RESPONSE_400_BAD_REQUEST =
        "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
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
    ) {
        val request = try {
            readHttpRequest(clientIn)
        } catch (_: HeaderTooLargeException) {
            writeResponseQuietly(clientOut, RESPONSE_400_BAD_REQUEST)
            return
        } catch (_: IOException) {
            return
        } ?: return

        if (request.method.equals("CONNECT", ignoreCase = true)) {
            handleConnect(request, clientSocket, clientIn, clientOut, statsTracker)
        } else {
            handlePlainHttp(request, clientSocket, clientIn, clientOut, statsTracker)
        }
    }

    private suspend fun handleConnect(
        request: ParsedHttpRequest,
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        statsTracker: TrafficStatsTracker,
    ) {
        val hostPort = parseAuthority(request.target, DEFAULT_HTTPS_PORT)
        if (hostPort == null) {
            writeResponseQuietly(clientOut, RESPONSE_400_BAD_REQUEST)
            return
        }

        val remoteSocket = connectOutbound(hostPort.host, hostPort.port)
        if (remoteSocket == null) {
            writeResponseQuietly(clientOut, RESPONSE_502_BAD_GATEWAY)
            return
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
    ) {
        val target = resolveHttpTarget(request.target, request.headers)
        if (target == null) {
            writeResponseQuietly(clientOut, RESPONSE_400_BAD_REQUEST)
            return
        }

        val remoteSocket = connectOutbound(target.host, target.port)
        if (remoteSocket == null) {
            writeResponseQuietly(clientOut, RESPONSE_502_BAD_GATEWAY)
            return
        }

        try {
            val remoteOut = remoteSocket.getOutputStream()
            val remoteIn = remoteSocket.getInputStream()

            val headerBytes = buildForwardedRequestHeader(request, target)
            remoteOut.write(headerBytes)
            remoteOut.flush()
            statsTracker.addBytesUp(headerBytes.size.toLong())

            runCatching { clientSocket.soTimeout = 0 }
            runCatching { remoteSocket.soTimeout = 0 }

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
                    isChunked -> launch(Dispatchers.IO) {
                        runCatching {
                            copyChunkedBody(clientIn, remoteOut, statsTracker)
                        }
                    }

                    contentLength > 0L -> launch(Dispatchers.IO) {
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
                        val read = remoteIn.read(buffer)
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

        sb.append("Connection: close\r\n\r\n")
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

    private fun connectOutbound(host: String, port: Int): Socket? {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), HybridProxyServer.CONNECT_TIMEOUT_MS)
            socket
        } catch (_: IOException) {
            runCatching { socket.close() }
            null
        } catch (_: IllegalArgumentException) {
            runCatching { socket.close() }
            null
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
