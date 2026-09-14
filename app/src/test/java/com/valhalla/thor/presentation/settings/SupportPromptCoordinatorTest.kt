// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.app.Activity
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SupportPromptCoordinatorTest {
    @Test
    fun `saved supporter never receives an invitation while preferences load`() = runTest {
        val preferences = FakePreferenceRepository(
            initial = UserPreferences(alreadySupportsThor = true),
            firstReadDelayMs = 100,
        )
        coordinator(preferences).use { coordinator ->
            assertFalse(coordinator.state.value.canInvite)
            assertFalse(coordinator.state.value.canSelfDeclareSupport)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()

            assertTrue(coordinator.state.value.alreadySupportsThor)
            assertFalse(coordinator.state.value.canInvite)
            assertFalse(coordinator.state.value.canSelfDeclareSupport)
        }
    }

    @Test
    fun `FOSS can invite only after preferences arrive`() = runTest {
        coordinator(FakePreferenceRepository(firstReadDelayMs = 100)).use { coordinator ->
            runCurrent()
            assertFalse(coordinator.state.value.canInvite)
            advanceTimeBy(100)
            runCurrent()

            assertTrue(coordinator.state.value.canInvite)
            assertTrue(coordinator.state.value.canSelfDeclareSupport)
        }
    }

    @Test
    fun `unreadable preferences do not turn default false into permission to invite`() = runTest {
        coordinator(FakePreferenceRepository(UserPreferences(settingsLost = true))).use { coordinator ->
            runCurrent()
            assertFalse(coordinator.state.value.canInvite)
        }
    }

    @Test
    fun `Store waits for a connected successful purchase query`() = runTest {
        val billing = FakeSupportBillingProcessor(BillingConnectionState.CONNECTING)
        coordinator(billing = billing).use { coordinator ->
            runCurrent()
            assertFalse(coordinator.state.value.canInvite)
            assertFalse(coordinator.state.value.canSelfDeclareSupport)

            billing.connectionState.value = BillingConnectionState.CONNECTED
            runCurrent()
            assertFalse(coordinator.state.value.canInvite)

            billing.subscriptionStatus.value = SubscriptionStatus.NOT_SUBSCRIBED
            runCurrent()
            assertTrue(coordinator.state.value.canInvite)
            assertFalse(coordinator.state.value.canSelfDeclareSupport)
        }
    }

    @Test
    fun `unavailable billing permits self declaration without an invitation`() = runTest {
        val billing = FakeSupportBillingProcessor(BillingConnectionState.UNAVAILABLE)
        coordinator(billing = billing).use { coordinator ->
            runCurrent()
            assertFalse(coordinator.state.value.canInvite)
            assertTrue(coordinator.state.value.canSelfDeclareSupport)

            coordinator.declareSupport()
            assertTrue(coordinator.state.value.alreadySupportsThor)
            assertFalse(coordinator.state.value.canSelfDeclareSupport)
        }
    }

    @Test
    fun `pending and subscribed purchases suppress invitations across disconnects`() = runTest {
        val billing = FakeSupportBillingProcessor(BillingConnectionState.CONNECTED)
        coordinator(billing = billing).use { coordinator ->
            for (status in listOf(SubscriptionStatus.UNKNOWN, SubscriptionStatus.PENDING, SubscriptionStatus.SUBSCRIBED)) {
                billing.subscriptionStatus.value = status
                runCurrent()
                assertFalse("$status must not be invited", coordinator.state.value.canInvite)

                billing.connectionState.value = BillingConnectionState.UNAVAILABLE
                runCurrent()
                assertFalse(coordinator.state.value.canInvite)
                billing.connectionState.value = BillingConnectionState.CONNECTED
                runCurrent()
                assertFalse(coordinator.state.value.canInvite)
            }
        }
    }

    @Test
    fun `new subscription withdraws a visible invitation`() = runTest {
        val billing = FakeSupportBillingProcessor(BillingConnectionState.CONNECTED).apply {
            subscriptionStatus.value = SubscriptionStatus.NOT_SUBSCRIBED
        }
        coordinator(billing = billing).use { coordinator ->
            runCurrent()
            assertTrue(coordinator.state.value.canInvite)
            billing.subscriptionStatus.value = SubscriptionStatus.SUBSCRIBED
            runCurrent()
            assertFalse(coordinator.state.value.canInvite)
        }
    }

    @Test
    fun `an active plan suppresses invitations even if absence status arrives first`() = runTest {
        val billing = FakeSupportBillingProcessor(BillingConnectionState.CONNECTED).apply {
            activeSubscription.value = ActiveSubscription("support", "token")
            subscriptionStatus.value = SubscriptionStatus.NOT_SUBSCRIBED
        }
        coordinator(billing = billing).use { coordinator ->
            runCurrent()
            assertFalse(coordinator.state.value.canInvite)

            billing.activeSubscription.value = null
            runCurrent()
            assertTrue(coordinator.state.value.canInvite)
        }
    }

    @Test
    fun `actual sheet marks introduction immediately and saves once through a slow write`() = runTest {
        val preferences = GatedSupportPreferences()
        coordinator(preferences).use { coordinator ->
            // A manually opened sheet can precede the first preferences emission.
            coordinator.markPromptShown()
            coordinator.markPromptShown()
            assertTrue(coordinator.state.value.hasShownPrompt)
            runCurrent()
            assertEquals(1, preferences.promptWrites)
            assertTrue(coordinator.state.value.hasShownPrompt)
            assertFalse(preferences.userPreferences.first().hasShownSupportDeveloperPrompt)

            preferences.release.complete(Unit)
            runCurrent()
            assertTrue(preferences.userPreferences.first().hasShownSupportDeveloperPrompt)
        }
        coordinator(preferences).use { restored ->
            runCurrent()
            assertTrue(restored.state.value.hasShownPrompt)
            restored.markPromptShown()
            runCurrent()
            assertEquals(1, preferences.promptWrites)
            // Seeing the introduction does not remove an optional success button forever.
            assertTrue(restored.state.value.canInvite)
        }
    }

    @Test
    fun `declaration hides invitations immediately and survives coordinator recreation`() = runTest {
        val preferences = GatedSupportPreferences()
        coordinator(preferences).use { coordinator ->
            runCurrent()
            assertTrue(coordinator.state.value.canInvite)
            coordinator.declareSupport()
            coordinator.declareSupport()
            assertFalse(coordinator.state.value.canInvite)
            assertFalse(coordinator.state.value.canSelfDeclareSupport)
            assertTrue(coordinator.state.value.alreadySupportsThor)
            runCurrent()
            assertEquals(1, preferences.declarationWrites)
            assertFalse(preferences.userPreferences.first().alreadySupportsThor)

            preferences.release.complete(Unit)
            runCurrent()
            assertTrue(preferences.userPreferences.first().alreadySupportsThor)
        }
        coordinator(preferences).use { restored ->
            runCurrent()
            assertFalse(restored.state.value.canInvite)
            assertTrue(restored.state.value.alreadySupportsThor)
        }
    }

    @Test
    fun `rejected preference writes keep the current session quiet`() = runTest {
        val preferences = FakePreferenceRepository(writesFail = true)
        coordinator(preferences).use { coordinator ->
            runCurrent()
            coordinator.markPromptShown()
            coordinator.declareSupport()
            runCurrent()

            assertTrue(preferences.writeFailureLatched)
            assertFalse(preferences.userPreferences.first().alreadySupportsThor)
            assertTrue(coordinator.state.value.hasShownPrompt)
            assertTrue(coordinator.state.value.alreadySupportsThor)
            assertFalse(coordinator.state.value.canInvite)
        }
    }

    @Test
    fun `unexpected storage exceptions cannot crash support dismissal or undo suppression`() = runTest {
        val preferences = object : PreferenceRepository by FakePreferenceRepository() {
            override suspend fun setHasShownSupportDeveloperPrompt(hasShown: Boolean) {
                throw IOException("settings unavailable")
            }

            override suspend fun setAlreadySupportsThor(alreadySupports: Boolean) {
                throw IllegalStateException("store closed")
            }
        }
        coordinator(preferences).use { coordinator ->
            runCurrent()
            coordinator.markPromptShown()
            coordinator.declareSupport()
            runCurrent()

            assertTrue(coordinator.state.value.hasShownPrompt)
            assertTrue(coordinator.state.value.alreadySupportsThor)
            assertFalse(coordinator.state.value.canInvite)
        }
    }

    private fun TestScope.coordinator(
        preferences: PreferenceRepository = FakePreferenceRepository(),
        billing: BillingProcessor = FakeSupportBillingProcessor(),
    ) = SupportPromptCoordinator(preferences, billing, StandardTestDispatcher(testScheduler))
}

internal class GatedSupportPreferences(
    private val delegate: FakePreferenceRepository = FakePreferenceRepository(),
) : PreferenceRepository by delegate {
    val release = CompletableDeferred<Unit>()
    var promptWrites = 0
    var declarationWrites = 0

    override suspend fun setHasShownSupportDeveloperPrompt(hasShown: Boolean) {
        promptWrites++
        release.await()
        delegate.setHasShownSupportDeveloperPrompt(hasShown)
    }

    override suspend fun setAlreadySupportsThor(alreadySupports: Boolean) {
        declarationWrites++
        release.await()
        delegate.setAlreadySupportsThor(alreadySupports)
    }
}

internal class FakeSupportBillingProcessor(
    connection: BillingConnectionState = BillingConnectionState.NOT_APPLICABLE,
) : BillingProcessor {
    override val connectionState = MutableStateFlow(connection)
    override val subscriptionStatus = MutableStateFlow(SubscriptionStatus.UNKNOWN)
    override val isBillingAvailable = MutableStateFlow(connection == BillingConnectionState.CONNECTED)
    override val products = MutableStateFlow(emptyList<BillingProduct>())
    override val activeSubscription = MutableStateFlow<ActiveSubscription?>(null)
    override val showThankYouDialog = MutableStateFlow(false)

    override fun launchBillingFlow(
        activity: Activity,
        productId: String,
        oldPurchaseToken: String?,
        oldProductId: String?,
    ) = Unit
    override fun dismissThankYouDialog() = Unit
    override fun refreshPurchases() = Unit
    override fun close() = Unit
}
