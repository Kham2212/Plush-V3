package com.example.vpn

import java.util.Locale

object TrafficSpeedCalculator {

    fun calculateBytesPerSecond(
        previousBytes: Long,
        currentBytes: Long,
        elapsedMillis: Long,
    ): Long {
        if (elapsedMillis <= 0L) return 0L
        val delta = (currentBytes - previousBytes).coerceAtLeast(0L)
        return (delta * 1000L) / elapsedMillis
    }

    fun formatSpeed(bytesPerSecond: Long): String {
        val safe = bytesPerSecond.coerceAtLeast(0L)
        return when {
            safe == 0L -> "0.0 KB/s"
            safe < 1024L -> "$safe B/s"
            safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB/s", safe / 1024.0)
            else -> String.format(Locale.US, "%.2f MB/s", safe / (1024.0 * 1024.0))
        }
    }
}
