package com.valhalla.thor.presentation.settings

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

interface BillingProcessor {
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
