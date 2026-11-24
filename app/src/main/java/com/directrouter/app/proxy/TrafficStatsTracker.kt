package com.directrouter.app.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class TrafficSnapshot(
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L,
    val uploadBytesPerSec: Long = 0L,
    val downloadBytesPerSec: Long = 0L,
    val activeConnections: Int = 0,
    val totalConnections: Long = 0L,
    val startedAtEpochMs: Long = 0L,
)

class TrafficStatsTracker {
    private val bytesUpCounter = AtomicLong(0L)
    private val bytesDownCounter = AtomicLong(0L)
    private val activeConnectionsCounter = AtomicInteger(0)
    private val totalConnectionsCounter = AtomicLong(0L)
    private val startedAtEpochMsRef = AtomicLong(0L)

    private val _snapshot = MutableStateFlow(TrafficSnapshot())
    val snapshot: StateFlow<TrafficSnapshot> = _snapshot.asStateFlow()

    private val lock = Any()
    private var tickerJob: Job? = null

    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            tickerJob?.cancel()
            val startEpoch = System.currentTimeMillis()
            startedAtEpochMsRef.set(startEpoch)
            _snapshot.value = currentSnapshot(uploadRate = 0L, downloadRate = 0L)

            tickerJob = scope.launch {
                var lastUp = bytesUpCounter.get()
                var lastDown = bytesDownCounter.get()
                var lastTickMs = System.currentTimeMillis()

                while (isActive) {
                    delay(TICK_INTERVAL_MS)
                    val nowMs = System.currentTimeMillis()
                    val elapsedMs = (nowMs - lastTickMs).coerceAtLeast(1L)
                    val currentUp = bytesUpCounter.get()
                    val currentDown = bytesDownCounter.get()
                    val deltaUp = (currentUp - lastUp).coerceAtLeast(0L)
                    val deltaDown = (currentDown - lastDown).coerceAtLeast(0L)
                    val upPerSec = (deltaUp * 1_000L) / elapsedMs
                    val downPerSec = (deltaDown * 1_000L) / elapsedMs

                    lastUp = currentUp
                    lastDown = currentDown
                    lastTickMs = nowMs

                    _snapshot.value = currentSnapshot(
                        uploadRate = upPerSec,
                        downloadRate = downPerSec,
                    )
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            tickerJob?.cancel()
            tickerJob = null
            _snapshot.value = currentSnapshot(uploadRate = 0L, downloadRate = 0L)
        }
    }

    fun reset() {
        synchronized(lock) {
            bytesUpCounter.set(0L)
            bytesDownCounter.set(0L)
            activeConnectionsCounter.set(0)
            totalConnectionsCounter.set(0L)
            startedAtEpochMsRef.set(0L)
            _snapshot.value = TrafficSnapshot()
        }
    }

    fun addBytesUp(count: Long) {
        if (count <= 0L) return
        val total = bytesUpCounter.addAndGet(count)
        _snapshot.update { current ->
            current.copy(bytesUp = total)
        }
    }

    fun addBytesDown(count: Long) {
        if (count <= 0L) return
        val total = bytesDownCounter.addAndGet(count)
        _snapshot.update { current ->
            current.copy(bytesDown = total)
        }
    }

    fun onConnectionOpened() {
        val active = activeConnectionsCounter.incrementAndGet()
        val total = totalConnectionsCounter.incrementAndGet()
        _snapshot.update { current ->
            current.copy(
                activeConnections = active,
                totalConnections = total,
            )
        }
    }

    fun onConnectionClosed() {
        val active = activeConnectionsCounter.updateAndGet { current ->
            if (current > 0) current - 1 else 0
        }
        _snapshot.update { current ->
            current.copy(activeConnections = active)
        }
    }

    private fun currentSnapshot(uploadRate: Long, downloadRate: Long): TrafficSnapshot {
        return TrafficSnapshot(
            bytesUp = bytesUpCounter.get(),
            bytesDown = bytesDownCounter.get(),
            uploadBytesPerSec = uploadRate,
            downloadBytesPerSec = downloadRate,
            activeConnections = activeConnectionsCounter.get(),
            totalConnections = totalConnectionsCounter.get(),
            startedAtEpochMs = startedAtEpochMsRef.get(),
        )
    }

    companion object {
        private const val TICK_INTERVAL_MS = 1_000L
    }
}
