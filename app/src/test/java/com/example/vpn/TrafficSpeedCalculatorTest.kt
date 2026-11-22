package com.example.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficSpeedCalculatorTest {

    @Test
    fun `calculates bytes per second across elapsed intervals and clamps negative deltas`() {
        assertEquals(1024L, TrafficSpeedCalculator.calculateBytesPerSecond(1000L, 2024L, 1000L))
        assertEquals(2048L, TrafficSpeedCalculator.calculateBytesPerSecond(0L, 1024L, 500L))
        assertEquals(0L, TrafficSpeedCalculator.calculateBytesPerSecond(5000L, 1000L, 1000L))
        assertEquals(0L, TrafficSpeedCalculator.calculateBytesPerSecond(100L, 500L, 0L))
    }

    @Test
    fun `formats speeds cleanly across zero, B_s, KB_s, and MB_s`() {
        assertEquals("0.0 KB/s", TrafficSpeedCalculator.formatSpeed(0L))
        assertEquals("512 B/s", TrafficSpeedCalculator.formatSpeed(512L))
        assertEquals("1.0 KB/s", TrafficSpeedCalculator.formatSpeed(1024L))
        assertEquals("150.5 KB/s", TrafficSpeedCalculator.formatSpeed(154_112L))
        assertEquals("1.50 MB/s", TrafficSpeedCalculator.formatSpeed(1_572_864L))
    }
}
