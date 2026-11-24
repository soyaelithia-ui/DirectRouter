package com.directrouter.app.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                serverSocket = socket
                serverScope = scope
                boundPortRef.set(socket.localPort)
                running.set(true)

                statsTracker.start(scope)

                scope.launch(Dispatchers.IO) {
                    acceptLoop(socket, scope)
                }
            }
        }
    }

    fun stop() {
        val socketToClose: ServerSocket?
        val scopeToCancel: CoroutineScope?
        val clientsToClose: List<Socket>

        synchronized(lifecycleLock) {
            if (!running.getAndSet(false) && serverSocket == null && serverScope == null) {
                return
            }
            boundPortRef.set(0)
            socketToClose = serverSocket
            serverSocket = null
            scopeToCancel = serverScope
            serverScope = null
            clientsToClose = activeClientSockets.toList()
            activeClientSockets.clear()
        }

        runCatching { socketToClose?.close() }
        for (client in clientsToClose) {
            runCatching { client.close() }
        }
        statsTracker.stop()
        scopeToCancel?.cancel()
    }

    private fun acceptLoop(server: ServerSocket, scope: CoroutineScope) {
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
            scope.launch(Dispatchers.IO) {
                handleClient(clientSocket, scope)
            }
        }
    }

    private suspend fun handleClient(clientSocket: Socket, scope: CoroutineScope) {
        statsTracker.onConnectionOpened()
        try {
            clientSocket.tcpNoDelay = true
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
                    )
                }

                isHttpMethodInitialByte(firstByte) -> {
                    HttpProxyHandler.handle(
                        clientSocket = clientSocket,
                        clientIn = pushbackIn,
                        clientOut = clientOut,
                        statsTracker = statsTracker,
                        scope = scope,
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
        private const val SERVER_BACKLOG = 128
        private const val PUSHBACK_BUFFER_SIZE = 4
        private const val SOCKS5_VERSION = 0x05
        internal const val HANDSHAKE_TIMEOUT_MS = 15_000
        internal const val CONNECT_TIMEOUT_MS = 15_000
        internal const val RELAY_BUFFER_SIZE = 16 * 1024

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
        ) {
            runCatching { clientSocket.soTimeout = 0 }
            runCatching { remoteSocket.soTimeout = 0 }

            coroutineScope {
                val clientToRemote = launch(Dispatchers.IO) {
                    val buffer = ByteArray(RELAY_BUFFER_SIZE)
                    var cleanEof = false
                    try {
                        while (isActive) {
                            val bytesRead = clientIn.read(buffer)
                            if (bytesRead <= 0) {
                                cleanEof = true
                                break
                            }
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

                val remoteToClient = launch(Dispatchers.IO) {
                    val buffer = ByteArray(RELAY_BUFFER_SIZE)
                    var cleanEof = false
                    try {
                        while (isActive) {
                            val bytesRead = remoteIn.read(buffer)
                            if (bytesRead <= 0) {
                                cleanEof = true
                                break
                            }
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
