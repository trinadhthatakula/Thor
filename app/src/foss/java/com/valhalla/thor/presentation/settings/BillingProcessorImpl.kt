// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.Single

@Single
class BillingProcessorImpl : BillingProcessor {
    override val isBillingAvailable: StateFlow<Boolean> = MutableStateFlow(false)
    override val products: StateFlow<List<BillingProduct>> = MutableStateFlow(emptyList())
    override val activeSubscription: StateFlow<ActiveSubscription?> = MutableStateFlow(null)
    override val showThankYouDialog: StateFlow<Boolean> = MutableStateFlow(false)

    override fun launchBillingFlow(
        activity: Activity,
        productId: String,
        oldPurchaseToken: String?,
        oldProductId: String?
    ) {
        // No-op in FOSS flavor
    }

    override fun dismissThankYouDialog() {
        // No-op in FOSS flavor
    }

    override fun close() {
        // No-op in FOSS flavor: no billing client or scope to tear down.
    }
}
