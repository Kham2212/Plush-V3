package com.example.vpn

import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultInterfaceUpdateTest {
    @Test
    fun `only a usable lookup or explicit loss publishes an update`() {
        val updates = mutableListOf<Triple<String, Int, Boolean>>()
        val publish: (String, Int, Boolean) -> Unit = { name, index, expensive ->
            updates.add(Triple(name, index, expensive))
        }

        publishDefaultInterfaceUpdate(true, "wlan0", false, { 7 }, publish)
        publishDefaultInterfaceUpdate(true, "wlan0", false, { throw SocketException("disappeared") }, publish)
        publishDefaultInterfaceUpdate(true, "wlan0", false, { null }, publish)
        publishDefaultInterfaceUpdate(true, "wlan0", false, { 0 }, publish)
        publishDefaultInterfaceUpdate(true, null, false, { error("missing name must not be looked up") }, publish)
        assertEquals(listOf(Triple("wlan0", 7, false)), updates)

        publishDefaultInterfaceUpdate(true, "rmnet0", true, { 8 }, publish)
        publishDefaultInterfaceUpdate(false, null, true, { error("loss must not be looked up") }, publish)
        assertEquals(
            listOf(Triple("wlan0", 7, false), Triple("rmnet0", 8, true), Triple("", -1, false)),
            updates,
        )
    }
}
