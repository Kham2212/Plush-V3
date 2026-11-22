package com.example.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import java.lang.ref.WeakReference

internal const val UNITY_GAME_ID = "800448341"
internal const val REWARDED_PLACEMENT = "BP_Rewarded_Android"
internal const val BANNER_PLACEMENT = "BP_Banner_Android"

internal class UnityAdsGateway(context: Context) : RewardedAdGateway {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activity: WeakReference<Activity>? = null

    fun attach(host: Activity) { activity = WeakReference(host) }
    fun detach(host: Activity) {
        if (activity?.get() === host) activity = null
    }

    private fun onMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun initialize(callback: (Boolean) -> Unit) {
        UnityAds.initialize(appContext, UNITY_GAME_ID, false, object : IUnityAdsInitializationListener {
            override fun onInitializationComplete() = onMainThread { callback(true) }
            override fun onInitializationFailed(error: UnityAds.UnityAdsInitializationError, message: String) =
                onMainThread { callback(false) }
        })
    }

    override fun load(callback: (Boolean) -> Unit) {
        UnityAds.load(REWARDED_PLACEMENT, object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String) =
                onMainThread { callback(placementId == REWARDED_PLACEMENT) }
            override fun onUnityAdsFailedToLoad(placementId: String, error: UnityAds.UnityAdsLoadError, message: String) =
                onMainThread { callback(false) }
        })
    }

    override fun show(callback: (RewardedAdResult) -> Unit) {
        val host = activity?.get()
        if (host == null || host.isFinishing || host.isDestroyed) {
            callback(RewardedAdResult.FAILED)
            return
        }
        UnityAds.show(host, REWARDED_PLACEMENT, object : IUnityAdsShowListener {
            override fun onUnityAdsShowStart(placementId: String) = Unit
            override fun onUnityAdsShowClick(placementId: String) = Unit
            override fun onUnityAdsShowFailure(placementId: String, error: UnityAds.UnityAdsShowError, message: String) =
                onMainThread { callback(RewardedAdResult.FAILED) }
            override fun onUnityAdsShowComplete(placementId: String, state: UnityAds.UnityAdsShowCompletionState) =
                onMainThread { callback(rewardedResult(placementId, state)) }
        })
    }
}

internal fun rewardedResult(placementId: String, state: UnityAds.UnityAdsShowCompletionState): RewardedAdResult =
    if (placementId == REWARDED_PLACEMENT && state == UnityAds.UnityAdsShowCompletionState.COMPLETED) {
        RewardedAdResult.COMPLETED
    } else {
        RewardedAdResult.SKIPPED
    }
