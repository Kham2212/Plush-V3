package com.example.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedAdControllerTest {
    private class FakeGateway : RewardedAdGateway {
        lateinit var init: (Boolean) -> Unit
        lateinit var loaded: (Boolean) -> Unit
        lateinit var shown: (RewardedAdResult) -> Unit
        var initCalls = 0
        var loadCalls = 0
        var showCalls = 0
        override fun initialize(callback: (Boolean) -> Unit) { initCalls++; init = callback }
        override fun load(callback: (Boolean) -> Unit) { loadCalls++; loaded = callback }
        override fun show(callback: (RewardedAdResult) -> Unit) { showCalls++; shown = callback }
    }

    @Test
    fun `only completed ad grants reward once and ignores repeated taps and callbacks`() {
        val sdk = FakeGateway()
        var state = AdState()
        var rewards = 0
        val controller = RewardedAdController(sdk) { state = it }
        controller.initialize()
        controller.request { rewards++ }
        controller.request { rewards++ }
        assertTrue(state.busy)
        sdk.init(true)
        assertEquals(1, sdk.loadCalls)
        assertEquals(0, rewards)
        sdk.loaded(true)
        assertEquals(1, sdk.showCalls)
        assertEquals(0, rewards)
        sdk.shown(RewardedAdResult.COMPLETED)
        sdk.shown(RewardedAdResult.COMPLETED)
        assertEquals(1, rewards)
        assertFalse(state.busy)
    }

    @Test
    fun `initialization load and show failures continue once while skipped ads do not`() {
        val sdk = FakeGateway()
        var state = AdState()
        var rewards = 0
        val controller = RewardedAdController(sdk) { state = it }
        controller.request { rewards++ }
        sdk.init(false)
        assertEquals(AdMessage.UNAVAILABLE, state.message)
        assertFalse(state.busy)
        sdk.init(false)
        assertEquals(1, rewards)
        controller.request { rewards++ }
        assertEquals(2, sdk.initCalls)
        sdk.init(true)
        sdk.loaded(false)
        assertEquals(AdMessage.UNAVAILABLE, state.message)
        sdk.loaded(false)
        assertEquals(2, rewards)
        controller.request { rewards++ }
        sdk.loaded(true)
        sdk.shown(RewardedAdResult.FAILED)
        assertEquals(AdMessage.UNAVAILABLE, state.message)
        sdk.shown(RewardedAdResult.FAILED)
        assertEquals(3, rewards)
        controller.request { rewards++ }
        sdk.loaded(true)
        sdk.shown(RewardedAdResult.SKIPPED)
        assertEquals(AdMessage.INCOMPLETE, state.message)
        assertEquals(3, rewards)
        assertFalse(state.busy)
        controller.request { rewards++ }
        sdk.loaded(true)
        sdk.shown(RewardedAdResult.COMPLETED)
        assertEquals(4, rewards)
    }

    @Test
    fun `background initialization failure does not start a connection or show a fallback notice`() {
        val sdk = FakeGateway()
        var state = AdState()
        var connections = 0
        val controller = RewardedAdController(sdk) { state = it }
        controller.initialize()
        sdk.init(false)
        assertEquals(null, state.message)
        assertEquals(0, connections)
        controller.request { connections++ }
        sdk.init(false)
        assertEquals(1, connections)
        assertEquals(AdMessage.UNAVAILABLE, state.message)
        controller.consumeUnavailableMessage()
        assertEquals(null, state.message)
    }

    @Test
    fun `stale and disposed callbacks cannot grant a reward`() {
        val sdk = FakeGateway()
        var rewards = 0
        val controller = RewardedAdController(sdk) {}
        controller.request { rewards++ }
        sdk.init(true)
        val oldLoad = sdk.loaded
        oldLoad(false)
        assertEquals(1, rewards)
        controller.request { rewards++ }
        oldLoad(true)
        assertEquals(0, sdk.showCalls)
        sdk.loaded(true)
        controller.dispose()
        sdk.shown(RewardedAdResult.COMPLETED)
        assertEquals(1, rewards)
    }
}
