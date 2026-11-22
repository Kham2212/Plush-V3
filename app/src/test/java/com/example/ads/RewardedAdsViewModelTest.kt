package com.example.ads

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.unity3d.ads.UnityAds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RewardedAdsViewModelTest {
    private class FakeGateway : RewardedAdGateway {
        lateinit var init: (Boolean) -> Unit
        lateinit var loaded: (Boolean) -> Unit
        lateinit var shown: (RewardedAdResult) -> Unit
        override fun initialize(callback: (Boolean) -> Unit) { init = callback }
        override fun load(callback: (Boolean) -> Unit) { loaded = callback }
        override fun show(callback: (RewardedAdResult) -> Unit) { shown = callback }
    }

    @Test
    fun `completed reward survives a recreated activity and can be consumed only once`() {
        val sdk = FakeGateway()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                RewardedAdsViewModel(app, sdk) as T
        }
        try {
            val before = ViewModelProvider(store, factory)[RewardedAdsViewModel::class.java]
            sdk.init(true)
            before.request()
            sdk.loaded(true)
            assertFalse(before.connectionReady.value)
            // The activity is recreated while Unity's full-screen ad is showing.
            val after = ViewModelProvider(store, factory)[RewardedAdsViewModel::class.java]
            assertSame(before, after)
            sdk.shown(RewardedAdResult.COMPLETED)
            assertTrue(after.connectionReady.value)
            assertTrue(after.consumeConnection())
            assertFalse(after.consumeConnection())
            sdk.shown(RewardedAdResult.COMPLETED)
            assertFalse(after.connectionReady.value)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `unavailable ad retains one connection handoff across recreation`() {
        val sdk = FakeGateway()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                RewardedAdsViewModel(app, sdk) as T
        }
        try {
            val before = ViewModelProvider(store, factory)[RewardedAdsViewModel::class.java]
            sdk.init(true)
            before.request()
            sdk.loaded(false)
            val after = ViewModelProvider(store, factory)[RewardedAdsViewModel::class.java]
            assertSame(before, after)
            assertEquals(AdMessage.UNAVAILABLE, after.state.value.message)
            assertTrue(after.consumeConnection())
            assertFalse(after.consumeConnection())
            sdk.loaded(false)
            assertFalse(after.connectionReady.value)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `only the configured placement and completed state grants reward`() {
        assertEquals(RewardedAdResult.COMPLETED, rewardedResult(REWARDED_PLACEMENT, UnityAds.UnityAdsShowCompletionState.COMPLETED))
        assertEquals(RewardedAdResult.SKIPPED, rewardedResult(REWARDED_PLACEMENT, UnityAds.UnityAdsShowCompletionState.SKIPPED))
        assertEquals(RewardedAdResult.SKIPPED, rewardedResult(BANNER_PLACEMENT, UnityAds.UnityAdsShowCompletionState.COMPLETED))
    }
}
