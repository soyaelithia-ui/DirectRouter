package com.directrouter.app.proxy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class HybridProxyServer(
    val statsTracker: TrafficStatsTracker = TrafficStatsTracker(),
) {
    private val running = AtomicBoolean(false)
    private val boundPortRef = AtomicInteger(0)
    private val lifecycleLock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var serverScope: CoroutineScope? = null

    @Volatile
    private var proxyExecutor: ThreadPoolExecutor? = null

    @Volatile
    private var proxyDispatcher: ExecutorCoroutineDispatcher? = null

    private val activeClientSockets: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    val isRunning: Boolean
        get() = running.get()

    val boundPort: Int
        get() = boundPortRef.get()

    suspend fun start(port: Int, bindAddress: String = "0.0.0.0") {
        withContext(Dispatchers.IO) {
            synchronized(lifecycleLock) {
                if (running.get()) {
                    return@synchronized
                }

                val bindInetAddress = InetAddress.getByName(bindAddress)
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindInetAddress, port), SERVER_BACKLOG)
                }

                val executor = createProxyExecutor()
                val dispatcher = executor.asCoroutineDispatcher()
                val scope = CoroutineScope(SupervisorJob() + dispatcher)

                serverSocket = socket
                proxyExecutor = executor
                proxyDispatcher = dispatcher
                serverScope = scope
                boundPortRef.set(socket.localPort)
                running.set(true)

                statsTracker.start(scope)

                scope.launch(dispatcher) {
                    acceptLoop(socket, scope, dispatcher)
                }
            }
        }
    }

    fun stop() {
        val socketToClose: ServerSocket?
        val scopeToCancel: CoroutineScope?
        val dispatcherToClose: ExecutorCoroutineDispatcher?
        val executorToShutdown: ThreadPoolExecutor?
        val clientsToClose: List<Socket>

        synchronized(lifecycleLock) {
            if (!running.getAndSet(false) &&
                serverSocket == null &&
                serverScope == null &&
                proxyExecutor == null
            ) {
                return
            }
            boundPortRef.set(0)
            socketToClose = serverSocket
            serverSocket = null
            scopeToCancel = serverScope
            serverScope = null
            dispatcherToClose = proxyDispatcher
            proxyDispatcher = null
            executorToShutdown = proxyExecutor
            proxyExecutor = null
            clientsToClose = activeClientSockets.toList()
            activeClientSockets.clear()
        }

        runCatching { socketToClose?.close() }
        for (client in clientsToClose) {
            runCatching { client.close() }
        }
        statsTracker.stop()
        scopeToCancel?.cancel()
        runCatching { executorToShutdown?.shutdownNow() }
        runCatching { dispatcherToClose?.close() }
    }

    private fun createProxyExecutor(): ThreadPoolExecutor {
        val threadIndex = AtomicInteger(1)
        val factory = ThreadFactory { runnable ->
            Thread(runnable, "DirectRouter-Proxy-${threadIndex.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
        return ThreadPoolExecutor(
            EXECUTOR_CORE_POOL_SIZE,
            EXECUTOR_MAX_POOL_SIZE,
            EXECUTOR_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            factory,
            ThreadPoolExecutor.CallerRunsPolicy(),
        )
    }

    private fun acceptLoop(
        server: ServerSocket,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
    ) {
        while (running.get() && !server.isClosed && scope.isActive) {
            val clientSocket = try {
                server.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                if (!running.get() || server.isClosed) break
                continue
            }

            if (!running.get()) {
                runCatching { clientSocket.close() }
                break
            }

            activeClientSockets.add(clientSocket)
            scope.launch(dispatcher) {
                handleClient(clientSocket, scope, dispatcher)
            }
        }
    }

    private suspend fun handleClient(
        clientSocket: Socket,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
    ) {
        statsTracker.onConnectionOpened()
        try {
            clientSocket.tcpNoDelay = true
            clientSocket.keepAlive = true
            clientSocket.soTimeout = HANDSHAKE_TIMEOUT_MS

            val pushbackIn = PushbackInputStream(clientSocket.getInputStream(), PUSHBACK_BUFFER_SIZE)
            val clientOut = clientSocket.getOutputStream()

            val firstByte = pushbackIn.read()
            if (firstByte == -1) {
                return
            }
            pushbackIn.unread(firstByte)

            when {
                firstByte == SOCKS5_VERSION -> {
                    Socks5Handler.handle(
                        clientSocket = clientSocket,
                        clientIn = pushbackIn,
                        clientOut = clientOut,
                        statsTracker = statsTracker,
                        scope = scope,
                        dispatcher = dispatcher,
                    )
                }

                firstByte == SOCKS4_VERSION -> {
                    Socks4Handler.handle(
                        clientSocket = clientSocket,
                        clientIn = pushbackIn,
                        clientOut = clientOut,
                        statsTracker = statsTracker,
                        scope = scope,
                        dispatcher = dispatcher,
                    )
                }

                isHttpMethodInitialByte(firstByte) -> {
                    HttpProxyHandler.handle(
                        clientSocket = clientSocket,
                        clientIn = pushbackIn,
                        clientOut = clientOut,
                        statsTracker = statsTracker,
                        scope = scope,
                        dispatcher = dispatcher,
                    )
                }

                else -> Unit
            }
        } catch (_: IOException) {
            // Normal client disconnect, reset, or timeout.
        } finally {
            activeClientSockets.remove(clientSocket)
            runCatching { clientSocket.close() }
            statsTracker.onConnectionClosed()
        }
    }

    companion object {
        private const val EXECUTOR_CORE_POOL_SIZE = 16
        private const val EXECUTOR_MAX_POOL_SIZE = 1024
        private const val EXECUTOR_KEEP_ALIVE_SECONDS = 30L
        private const val SERVER_BACKLOG = 256
        private const val PUSHBACK_BUFFER_SIZE = 4
        private const val SOCKS4_VERSION = 0x04
        private const val SOCKS5_VERSION = 0x05
        internal const val HANDSHAKE_TIMEOUT_MS = 15_000
        internal const val CONNECT_TIMEOUT_MS = OutboundConnector.DEFAULT_CONNECT_TIMEOUT_MS
        internal const val RELAY_IDLE_TIMEOUT_MS = 300_000
        internal const val RELAY_BUFFER_SIZE = 32 * 1024

        private fun isHttpMethodInitialByte(byteValue: Int): Boolean {
            return byteValue in 'A'.code..'Z'.code
        }

        internal suspend fun relayTcpBidirectional(
            clientSocket: Socket,
            clientIn: InputStream,
            clientOut: OutputStream,
            remoteSocket: Socket,
            remoteIn: InputStream,
            remoteOut: OutputStream,
            statsTracker: TrafficStatsTracker,
            dispatcher: CoroutineDispatcher,
        ) {
            runCatching { clientSocket.soTimeout = RELAY_IDLE_TIMEOUT_MS }
            runCatching { remoteSocket.soTimeout = RELAY_IDLE_TIMEOUT_MS }

            val lastActivityMs = AtomicLong(System.currentTimeMillis())

            coroutineScope {
                val clientToRemote = launch(dispatcher) {
                    val buffer = ByteArray(RELAY_BUFFER_SIZE)
                    var cleanEof = false
                    try {
                        while (isActive) {
                            val bytesRead = try {
                                clientIn.read(buffer)
                            } catch (_: SocketTimeoutException) {
                                if (System.currentTimeMillis() - lastActivityMs.get() >= RELAY_IDLE_TIMEOUT_MS) {
                                    break
                                }
                                continue
                            }
                            if (bytesRead <= 0) {
                                cleanEof = true
                                break
                            }
                            lastActivityMs.set(System.currentTimeMillis())
                            remoteOut.write(buffer, 0, bytesRead)
                            remoteOut.flush()
                            statsTracker.addBytesUp(bytesRead.toLong())
                        }
                    } catch (_: IOException) {
                        // Peer reset or closed; unblock sibling.
                    } finally {
                        if (cleanEof) {
                            runCatching {
                                if (!remoteSocket.isClosed && !remoteSocket.isOutputShutdown) {
                                    remoteSocket.shutdownOutput()
                                }
                            }
                        } else {
                            runCatching { remoteSocket.close() }
                            runCatching { clientSocket.close() }
                        }
                    }
                }

                val remoteToClient = launch(dispatcher) {
                    val buffer = ByteArray(RELAY_BUFFER_SIZE)
                    var cleanEof = false
                    try {
                        while (isActive) {
                            val bytesRead = try {
                                remoteIn.read(buffer)
                            } catch (_: SocketTimeoutException) {
                                if (System.currentTimeMillis() - lastActivityMs.get() >= RELAY_IDLE_TIMEOUT_MS) {
                                    break
                                }
                                continue
                            }
                            if (bytesRead <= 0) {
                                cleanEof = true
                                break
                            }
                            lastActivityMs.set(System.currentTimeMillis())
                            clientOut.write(buffer, 0, bytesRead)
                            clientOut.flush()
                            statsTracker.addBytesDown(bytesRead.toLong())
                        }
                    } catch (_: IOException) {
                        // Peer reset or closed; unblock sibling.
                    } finally {
                        if (cleanEof) {
                            runCatching {
                                if (!clientSocket.isClosed && !clientSocket.isOutputShutdown) {
                                    clientSocket.shutdownOutput()
                                }
                            }
                        } else {
                            runCatching { clientSocket.close() }
                            runCatching { remoteSocket.close() }
                        }
                    }
                }

                joinAll(clientToRemote, remoteToClient)
            }
        }
    }
}
