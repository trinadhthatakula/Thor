// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

interface BillingProcessor : AutoCloseable {
    val isBillingAvailable: StateFlow<Boolean>
    val products: StateFlow<List<BillingProduct>>
    val activeSubscription: StateFlow<ActiveSubscription?>
    val showThankYouDialog: StateFlow<Boolean>

    fun launchBillingFlow(
        activity: Activity,
        productId: String,
        oldPurchaseToken: String? = null,
        oldProductId: String? = null
    )
    fun dismissThankYouDialog()

    /**
     * Tears down any long-lived resources (Play billing connection, coroutine scope).
     * Invoked from the application lifecycle. No-op for flavors without a real billing client.
     */
    override fun close()
}

data class BillingProduct(
    val id: String,
    val name: String,
    val formattedPrice: String,
    val description: String
)

data class ActiveSubscription(
    val productId: String,
    val purchaseToken: String
)
