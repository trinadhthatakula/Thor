// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillingStatusTrackerTest {
    private val tracker = BillingStatusTracker()

    @Test
    fun `startup is connecting with unknown subscription rather than a non subscriber`() {
        assertEquals(BillingConnectionState.CONNECTING, tracker.connectionState.value)
        assertEquals(SubscriptionStatus.UNKNOWN, tracker.subscriptionStatus.value)
        assertFalse(tracker.isBillingAvailable.value)
    }

    @Test
    fun `connection progress and failed connection stay distinct from subscription status`() {
        tracker.connectionChanged(BillingConnectionState.UNAVAILABLE)
        assertEquals(BillingConnectionState.UNAVAILABLE, tracker.connectionState.value)
        assertEquals(SubscriptionStatus.UNKNOWN, tracker.subscriptionStatus.value)
        tracker.connectionChanged(BillingConnectionState.CONNECTING)
        assertFalse(tracker.isBillingAvailable.value)
        tracker.connectionChanged(BillingConnectionState.CONNECTED)
        assertTrue(tracker.isBillingAvailable.value)
        assertEquals(SubscriptionStatus.UNKNOWN, tracker.subscriptionStatus.value)
    }

    @Test
    fun `only a successful empty snapshot establishes no subscription`() {
        val failedQuery = tracker.beginPurchaseQuery()
        tracker.purchaseQueryFailed(failedQuery)
        assertEquals(SubscriptionStatus.UNKNOWN, tracker.subscriptionStatus.value)
        assertTrue(tracker.purchasesQueried(tracker.beginPurchaseQuery(), false, false))
        assertEquals(SubscriptionStatus.NOT_SUBSCRIBED, tracker.subscriptionStatus.value)
    }

    @Test
    fun `refresh failure invalidates prior absence so it cannot trigger an invitation`() {
        tracker.purchasesQueried(tracker.beginPurchaseQuery(), false, false)
        val refresh = tracker.beginPurchaseQuery()
        assertEquals(SubscriptionStatus.UNKNOWN, tracker.subscriptionStatus.value)
        tracker.purchaseQueryFailed(refresh)
        assertEquals(SubscriptionStatus.UNKNOWN, tracker.subscriptionStatus.value)
    }

    @Test
    fun `subscriber remains suppressed during failed refreshes and reconnects`() {
        tracker.purchasesQueried(tracker.beginPurchaseQuery(), true, false)
        tracker.connectionChanged(BillingConnectionState.UNAVAILABLE)
        tracker.purchaseQueryFailed(tracker.beginPurchaseQuery())
        tracker.connectionChanged(BillingConnectionState.CONNECTING)
        assertEquals(SubscriptionStatus.SUBSCRIBED, tracker.subscriptionStatus.value)
    }

    @Test
    fun `pending payments also survive refresh failure`() {
        tracker.purchasesUpdated(hasPurchased = false, hasPending = true)
        tracker.purchaseQueryFailed(tracker.beginPurchaseQuery())
        assertEquals(SubscriptionStatus.PENDING, tracker.subscriptionStatus.value)
    }

    @Test
    fun `successful query distinguishes pending and purchased and prioritizes an active plan`() {
        tracker.purchasesQueried(tracker.beginPurchaseQuery(), false, true)
        assertEquals(SubscriptionStatus.PENDING, tracker.subscriptionStatus.value)
        tracker.purchasesQueried(tracker.beginPurchaseQuery(), true, true)
        assertEquals(SubscriptionStatus.SUBSCRIBED, tracker.subscriptionStatus.value)
    }

    @Test
    fun `purchase callback immediately suppresses even before acknowledgement finishes`() {
        tracker.purchasesQueried(tracker.beginPurchaseQuery(), false, false)
        tracker.purchasesUpdated(hasPurchased = true, hasPending = false)
        assertEquals(SubscriptionStatus.SUBSCRIBED, tracker.subscriptionStatus.value)
        tracker.purchasesUpdated(hasPurchased = false, hasPending = true)
        assertEquals(SubscriptionStatus.SUBSCRIBED, tracker.subscriptionStatus.value)
    }

    @Test
    fun `old empty query cannot erase a new purchase callback`() {
        val oldQuery = tracker.beginPurchaseQuery()
        tracker.purchasesUpdated(hasPurchased = true, hasPending = false)
        assertFalse(tracker.purchasesQueried(oldQuery, false, false))
        assertEquals(SubscriptionStatus.SUBSCRIBED, tracker.subscriptionStatus.value)
    }

    @Test
    fun `out of order query responses cannot overwrite the newer snapshot`() {
        val oldQuery = tracker.beginPurchaseQuery()
        val currentQuery = tracker.beginPurchaseQuery()
        tracker.purchasesQueried(currentQuery, true, false)
        assertFalse(tracker.purchasesQueried(oldQuery, false, false))
        tracker.purchaseQueryFailed(oldQuery)
        assertEquals(SubscriptionStatus.SUBSCRIBED, tracker.subscriptionStatus.value)
    }

    @Test
    fun `stale query cannot clear or replace the newer active plan`() {
        val oldQuery = tracker.beginPurchaseQuery()
        val currentQuery = tracker.beginPurchaseQuery()
        val currentPlan = ActiveSubscription("current", "current-token")
        tracker.purchasesQueried(currentQuery, true, false, currentPlan)

        assertFalse(tracker.purchasesQueried(oldQuery, false, false))
        assertFalse(tracker.purchasesQueried(oldQuery, true, false, ActiveSubscription("old", "old-token")))
        assertEquals(currentPlan, tracker.activeSubscription.value)
        assertEquals(SubscriptionStatus.SUBSCRIBED, tracker.subscriptionStatus.value)
    }

    @Test
    fun `plan transitions never publish absence alongside an active subscription`() = runTest {
        var inconsistent = false
        fun observe() {
            if (tracker.subscriptionStatus.value == SubscriptionStatus.NOT_SUBSCRIBED &&
                tracker.activeSubscription.value != null
            ) inconsistent = true
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            tracker.subscriptionStatus.collect { observe() }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            tracker.activeSubscription.collect { observe() }
        }

        tracker.purchasesQueried(tracker.beginPurchaseQuery(), false, false)
        tracker.purchasesQueried(
            tracker.beginPurchaseQuery(), true, false, ActiveSubscription("support", "token"),
        )
        tracker.purchasesQueried(tracker.beginPurchaseQuery(), false, false)

        assertFalse(inconsistent)
        assertNull(tracker.activeSubscription.value)
        assertEquals(SubscriptionStatus.NOT_SUBSCRIBED, tracker.subscriptionStatus.value)
    }

    @Test
    fun `later successful absence can resolve expiry or canceled pending payment`() {
        tracker.purchasesUpdated(hasPurchased = true, hasPending = false)
        tracker.purchasesQueried(tracker.beginPurchaseQuery(), false, false)
        assertEquals(SubscriptionStatus.NOT_SUBSCRIBED, tracker.subscriptionStatus.value)
    }

    @Test
    fun `empty purchase callback is not an empty account snapshot`() {
        tracker.purchasesUpdated(hasPurchased = true, hasPending = false)
        tracker.purchasesUpdated(hasPurchased = false, hasPending = false)
        assertEquals(SubscriptionStatus.SUBSCRIBED, tracker.subscriptionStatus.value)
    }
}
