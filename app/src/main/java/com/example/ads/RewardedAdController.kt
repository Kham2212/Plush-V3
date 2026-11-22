package com.example.ads

/** All callbacks must be delivered on the main thread. */
internal interface RewardedAdGateway {
    fun initialize(callback: (Boolean) -> Unit)
    fun load(callback: (Boolean) -> Unit)
    fun show(callback: (RewardedAdResult) -> Unit)
}

internal enum class RewardedAdResult { COMPLETED, SKIPPED, FAILED }
enum class AdMessage { LOADING, UNAVAILABLE, INCOMPLETE }
data class AdState(
    val initialized: Boolean = false,
    val busy: Boolean = false,
    val message: AdMessage? = null,
)

/** Owns one connection attempt. Stale callbacks cannot start another connection. */
internal class RewardedAdController(
    private val gateway: RewardedAdGateway,
    private val onState: (AdState) -> Unit,
) {
    private enum class Phase { IDLE, INITIALIZING, LOADING, SHOWING, DISPOSED }
    private var phase = Phase.IDLE
    private var state = AdState()
    private var onConnectionAllowed: (() -> Unit)? = null
    private var generation = 0

    fun initialize() {
        if (phase != Phase.IDLE || state.initialized) return
        phase = Phase.INITIALIZING
        val token = ++generation
        gateway.initialize { success ->
            if (phase != Phase.INITIALIZING || token != generation) return@initialize
            phase = Phase.IDLE
            if (success) {
                update(state.copy(initialized = true))
                if (onConnectionAllowed != null) load()
            } else {
                if (onConnectionAllowed != null) finishAndContinue(AdMessage.UNAVAILABLE)
            }
        }
    }

    fun request(onAllowed: () -> Unit) {
        if (phase == Phase.DISPOSED || state.busy) return
        onConnectionAllowed = onAllowed
        update(state.copy(busy = true, message = AdMessage.LOADING))
        if (state.initialized) load() else initialize()
    }

    private fun load() {
        phase = Phase.LOADING
        val token = ++generation
        gateway.load { success ->
            if (phase != Phase.LOADING || token != generation) return@load
            if (!success) {
                finishAndContinue(AdMessage.UNAVAILABLE)
            } else {
                phase = Phase.SHOWING
                gateway.show { result ->
                    if (phase != Phase.SHOWING || token != generation) return@show
                    when (result) {
                        RewardedAdResult.COMPLETED -> finishAndContinue(null)
                        RewardedAdResult.SKIPPED -> finish(AdMessage.INCOMPLETE)
                        RewardedAdResult.FAILED -> finishAndContinue(AdMessage.UNAVAILABLE)
                    }
                }
            }
        }
    }

    private fun finishAndContinue(message: AdMessage?) {
        val onAllowed = onConnectionAllowed
        finish(message)
        onAllowed?.invoke()
    }

    private fun finish(message: AdMessage?) {
        phase = Phase.IDLE
        onConnectionAllowed = null
        update(state.copy(busy = false, message = message))
    }

    fun consumeUnavailableMessage() {
        if (state.message == AdMessage.UNAVAILABLE) update(state.copy(message = null))
    }

    private fun update(newState: AdState) {
        state = newState
        onState(newState)
    }

    fun dispose() {
        phase = Phase.DISPOSED
        generation++
        onConnectionAllowed = null
    }
}
