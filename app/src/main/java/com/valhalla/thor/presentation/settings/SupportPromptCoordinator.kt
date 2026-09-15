// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

data class SupportPromptState(
    val canInvite: Boolean = false,
    val canSelfDeclareSupport: Boolean = false,
    val hasShownPrompt: Boolean = false,
    val alreadySupportsThor: Boolean = false,
)

/** Shared eligibility for optional invitations. Manual support entry points stay available. */
@Single
class SupportPromptCoordinator(
    private val preferenceRepository: PreferenceRepository,
    private val billingProcessor: BillingProcessor,
    @Named("io") ioDispatcher: CoroutineDispatcher,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Any()
    private var preferences: UserPreferences? = null
    private var promptShownThisSession = false
    private var supportDeclaredThisSession = false
    private val _state = MutableStateFlow(SupportPromptState())
    val state: StateFlow<SupportPromptState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(
                preferenceRepository.userPreferences,
                billingProcessor.connectionState,
                billingProcessor.subscriptionStatus,
                billingProcessor.activeSubscription,
            ) { preferences, _, _, _ -> preferences }.collect { loadedPreferences ->
                synchronized(lock) {
                    preferences = loadedPreferences
                    publishState()
                }
            }
        }
    }

    /** Mark the introduction when a sheet actually opens, including from a manual entry point. */
    fun markPromptShown() {
        synchronized(lock) {
            if (promptShownThisSession || preferences?.hasShownSupportDeveloperPrompt == true) return
            promptShownThisSession = true
            publishState()
        }
        persistChoice { preferenceRepository.setHasShownSupportDeveloperPrompt(true) }
    }

    fun declareSupport() {
        synchronized(lock) {
            if (!_state.value.canSelfDeclareSupport) return
            supportDeclaredThisSession = true
            publishState()
        }
        persistChoice { preferenceRepository.setAlreadySupportsThor(true) }
    }

    private fun persistChoice(write: suspend () -> Unit) {
        scope.launch {
            try {
                write()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The current session still honours the choice if storage becomes unavailable.
                Logger.e("SupportPromptCoordinator", "Could not save support preference", failure)
            }
        }
    }

    /** Called under [lock]; local latches survive slow or failed preference writes. */
    private fun publishState() {
        val loaded = preferences
        val alreadySupports = supportDeclaredThisSession || loaded?.alreadySupportsThor == true
        val connection = billingProcessor.connectionState.value
        val billingAllowsInvitation = connection == BillingConnectionState.NOT_APPLICABLE ||
            (connection == BillingConnectionState.CONNECTED &&
                billingProcessor.subscriptionStatus.value == SubscriptionStatus.NOT_SUBSCRIBED &&
                billingProcessor.activeSubscription.value == null)
        _state.value = SupportPromptState(
            canInvite = loaded != null && !loaded.settingsLost && !alreadySupports &&
                billingAllowsInvitation,
            canSelfDeclareSupport = loaded != null && !alreadySupports &&
                (connection == BillingConnectionState.NOT_APPLICABLE ||
                    connection == BillingConnectionState.UNAVAILABLE),
            hasShownPrompt = promptShownThisSession || loaded?.hasShownSupportDeveloperPrompt == true,
            alreadySupportsThor = alreadySupports,
        )
    }

    override fun close() {
        scope.cancel()
    }
}
