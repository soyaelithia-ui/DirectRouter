package com.directrouter.app.ui.util

import java.util.Locale

object Formatters {
    private const val KB = 1024L
    private const val MB = 1024L * 1024L
    private const val GB = 1024L * 1024L * 1024L

    fun formatBytes(bytes: Long): String {
        val safe = if (bytes < 0L) 0L else bytes
        return when {
            safe < KB -> "$safe B"
            safe < MB -> String.format(Locale.US, "%.1f KB", safe / KB.toDouble())
            safe < GB -> String.format(Locale.US, "%.2f MB", safe / MB.toDouble())
            else -> String.format(Locale.US, "%.2f GB", safe / GB.toDouble())
        }
    }

    fun formatSpeed(bytesPerSec: Long): String {
        val safe = if (bytesPerSec < 0L) 0L else bytesPerSec
        return when {
            safe < KB -> "$safe B/s"
            safe < MB -> String.format(Locale.US, "%.1f KB/s", safe / KB.toDouble())
            else -> String.format(Locale.US, "%.2f MB/s", safe / MB.toDouble())
        }
    }

    fun formatDuration(durationMs: Long): String {
        val safeMs = if (durationMs < 0L) 0L else durationMs
        val totalSeconds = safeMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
