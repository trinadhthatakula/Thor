// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared state for lifecycle queries and purchase callbacks; observing it never queries Play. */
internal class BillingStatusTracker {
    private val _connectionState = MutableStateFlow(BillingConnectionState.CONNECTING)
    val connectionState: StateFlow<BillingConnectionState> = _connectionState.asStateFlow()
    private val _isBillingAvailable = MutableStateFlow(false)
    val isBillingAvailable: StateFlow<Boolean> = _isBillingAvailable.asStateFlow()
    private val _subscriptionStatus = MutableStateFlow(SubscriptionStatus.UNKNOWN)
    val subscriptionStatus: StateFlow<SubscriptionStatus> = _subscriptionStatus.asStateFlow()
    private val _activeSubscription = MutableStateFlow<ActiveSubscription?>(null)
    val activeSubscription: StateFlow<ActiveSubscription?> = _activeSubscription.asStateFlow()

    private var generation = 0L

    @Synchronized
    fun connectionChanged(state: BillingConnectionState) {
        _connectionState.value = state
        _isBillingAvailable.value = state == BillingConnectionState.CONNECTED
        if (state != BillingConnectionState.CONNECTED) suppressUnconfirmedAbsence()
    }

    /** Fresh purchase snapshots alone can establish NOT_SUBSCRIBED, including after expiry. */
    @Synchronized
    fun beginPurchaseQuery(): Long {
        suppressUnconfirmedAbsence()
        return ++generation
    }

    /** Returns false when a newer query or purchase event has superseded this response. */
    @Synchronized
    fun purchasesQueried(
        query: Long,
        hasPurchased: Boolean,
        hasPending: Boolean,
        activeSubscription: ActiveSubscription? = null,
    ): Boolean {
        if (query != generation) return false
        val status = when {
            hasPurchased -> SubscriptionStatus.SUBSCRIBED
            hasPending -> SubscriptionStatus.PENDING
            else -> SubscriptionStatus.NOT_SUBSCRIBED
        }
        // Clear a stale plan before publishing absence; suppress first when publishing a plan.
        // The generation check and both writes share this lock with purchase callbacks/queries.
        if (status == SubscriptionStatus.NOT_SUBSCRIBED) {
            _activeSubscription.value = null
            _subscriptionStatus.value = status
        } else {
            _subscriptionStatus.value = status
            _activeSubscription.value = activeSubscription
        }
        return true
    }

    @Synchronized
    fun purchaseQueryFailed(query: Long) {
        if (query == generation) suppressUnconfirmedAbsence()
    }

    /** Purchase callbacks are deltas, so an empty callback cannot prove the absence of a plan. */
    @Synchronized
    fun purchasesUpdated(hasPurchased: Boolean, hasPending: Boolean) {
        if (!hasPurchased && !hasPending) return
        generation++
        _subscriptionStatus.value = when {
            hasPurchased || _subscriptionStatus.value == SubscriptionStatus.SUBSCRIBED ->
                SubscriptionStatus.SUBSCRIBED
            else -> SubscriptionStatus.PENDING
        }
    }

    /** A failed check must never erase a known supporter or a payment still being processed. */
    private fun suppressUnconfirmedAbsence() {
        if (_subscriptionStatus.value == SubscriptionStatus.NOT_SUBSCRIBED) {
            _subscriptionStatus.value = SubscriptionStatus.UNKNOWN
        }
    }
}
