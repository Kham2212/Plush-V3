package com.example.ads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps an ad attempt and its connection handoff across activity recreation. */
internal class RewardedAdsViewModel internal constructor(
    application: Application,
    private val gateway: RewardedAdGateway,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, UnityAdsGateway(application))

    private val mutableState = MutableStateFlow(AdState())
    val state: StateFlow<AdState> = mutableState.asStateFlow()
    private val mutableConnectionReady = MutableStateFlow(false)
    val connectionReady: StateFlow<Boolean> = mutableConnectionReady.asStateFlow()
    private val controller = RewardedAdController(gateway) { mutableState.value = it }

    init {
        controller.initialize()
    }

    fun request() {
        if (mutableConnectionReady.value) return
        controller.request { mutableConnectionReady.value = true }
    }

    fun consumeConnection(): Boolean {
        if (!mutableConnectionReady.value) return false
        mutableConnectionReady.value = false
        return true
    }

    fun consumeUnavailableMessage() = controller.consumeUnavailableMessage()

    fun attach(activity: android.app.Activity) {
        (gateway as? UnityAdsGateway)?.attach(activity)
    }

    fun detach(activity: android.app.Activity) {
        (gateway as? UnityAdsGateway)?.detach(activity)
    }

    override fun onCleared() {
        controller.dispose()
        super.onCleared()
    }
}
