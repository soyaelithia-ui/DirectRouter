package com.directrouter.app.proxy

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

internal object OutboundConnector {
    internal const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000
    private const val MULTI_CANDIDATE_TIMEOUT_MS = 5_500
    private const val MAX_CANDIDATES = 4
    private const val DNS_CACHE_TTL_MS = 30_000L
    private const val DNS_CACHE_MAX_SIZE = 512

    sealed class ConnectResult {
        data class Success(val socket: Socket) : ConnectResult()
        data class Failure(val error: Exception) : ConnectResult()
    }

    private data class CachedDnsEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long,
    )

    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()

    fun connect(
        host: String,
        port: Int,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    ): Socket? {
        return when (val result = connectWithResult(host, port, connectTimeoutMs)) {
            is ConnectResult.Success -> result.socket
            is ConnectResult.Failure -> null
        }
    }

    fun connectWithResult(
        host: String,
        port: Int,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    ): ConnectResult {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty() || port !in 1..65535) {
            return ConnectResult.Failure(IllegalArgumentException("Invalid target $host:$port"))
        }

        val resolved = try {
            resolveOrdered(trimmedHost)
        } catch (e: IOException) {
            return ConnectResult.Failure(e)
        } catch (e: SecurityException) {
            return ConnectResult.Failure(e)
        }

        if (resolved.isEmpty()) {
            return ConnectResult.Failure(UnknownHostException(trimmedHost))
        }

        val candidates = selectCandidates(resolved)
        val perAttemptTimeout = if (candidates.size > 1) {
            minOf(connectTimeoutMs, MULTI_CANDIDATE_TIMEOUT_MS).coerceAtLeast(1_000)
        } else {
            connectTimeoutMs.coerceAtLeast(1_000)
        }

        var lastError: Exception = IOException("Failed to connect to $trimmedHost:$port")
        for (candidate in candidates) {
            when (val attempt = connectSingle(candidate, port, perAttemptTimeout)) {
                is ConnectResult.Success -> return attempt
                is ConnectResult.Failure -> lastError = attempt.error
            }
        }
        return ConnectResult.Failure(lastError)
    }

    fun connectWithResult(
        address: InetAddress,
        port: Int,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    ): ConnectResult {
        if (port !in 1..65535) {
            return ConnectResult.Failure(IllegalArgumentException("Invalid port $port"))
        }
        return connectSingle(unwrapIpv4Mapped(address), port, connectTimeoutMs.coerceAtLeast(1_000))
    }

    @Throws(UnknownHostException::class)
    fun resolveFirst(host: String): InetAddress {
        val candidates = resolveOrdered(host.trim())
        if (candidates.isEmpty()) {
            throw UnknownHostException(host)
        }
        return candidates.first()
    }

    @Throws(UnknownHostException::class)
    fun resolveOrdered(host: String): List<InetAddress> {
        val cacheKey = host.lowercase()
        val now = System.currentTimeMillis()
        val cached = dnsCache[cacheKey]
        if (cached != null && cached.expiresAtMs > now) {
            return cached.addresses
        }

        val raw = InetAddress.getAllByName(host)
        if (raw.isEmpty()) {
            throw UnknownHostException(host)
        }

        val ipv4 = ArrayList<InetAddress>(raw.size)
        val ipv6 = ArrayList<InetAddress>(raw.size)
        val other = ArrayList<InetAddress>()

        for (addr in raw) {
            val normalized = unwrapIpv4Mapped(addr)
            when (normalized) {
                is Inet4Address -> ipv4.add(normalized)
                is Inet6Address -> ipv6.add(normalized)
                else -> other.add(normalized)
            }
        }

        val ordered = ArrayList<InetAddress>(raw.size).apply {
            addAll(ipv4)
            addAll(ipv6)
            addAll(other)
        }

        if (dnsCache.size >= DNS_CACHE_MAX_SIZE) {
            dnsCache.entries.removeIf { it.value.expiresAtMs <= now }
            if (dnsCache.size >= DNS_CACHE_MAX_SIZE) {
                dnsCache.clear()
            }
        }
        dnsCache[cacheKey] = CachedDnsEntry(
            addresses = ordered,
            expiresAtMs = now + DNS_CACHE_TTL_MS,
        )
        return ordered
    }

    internal fun unwrapIpv4Mapped(addr: InetAddress): InetAddress {
        val bytes = addr.address
        if (bytes.size == 16 && isIpv4MappedBytes(bytes)) {
            return InetAddress.getByAddress(bytes.copyOfRange(12, 16))
        }
        return addr
    }

    private fun isIpv4MappedBytes(bytes: ByteArray): Boolean {
        for (i in 0 until 10) {
            if (bytes[i] != 0.toByte()) return false
        }
        return bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
    }

    private fun selectCandidates(ordered: List<InetAddress>): List<InetAddress> {
        if (ordered.size <= MAX_CANDIDATES) {
            return ordered
        }
        val ipv4 = ordered.filterIsInstance<Inet4Address>()
        val ipv6 = ordered.filterIsInstance<Inet6Address>()
        if (ipv4.isNotEmpty() && ipv6.isNotEmpty()) {
            val selected = ArrayList<InetAddress>(MAX_CANDIDATES)
            val v4Take = minOf(ipv4.size, MAX_CANDIDATES - 1)
            for (i in 0 until v4Take) {
                selected.add(ipv4[i])
            }
            val remaining = MAX_CANDIDATES - selected.size
            for (i in 0 until minOf(ipv6.size, remaining)) {
                selected.add(ipv6[i])
            }
            return selected
        }
        return ordered.take(MAX_CANDIDATES)
    }

    private fun connectSingle(
        address: InetAddress,
        port: Int,
        timeoutMs: Int,
    ): ConnectResult {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            ConnectResult.Success(socket)
        } catch (e: IOException) {
            runCatching { socket.close() }
            ConnectResult.Failure(e)
        } catch (e: IllegalArgumentException) {
            runCatching { socket.close() }
            ConnectResult.Failure(e)
        }
    }
}
