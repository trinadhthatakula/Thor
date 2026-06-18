package com.valhalla.thor.presentation.settings

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

interface BillingProcessor {
    val isBillingAvailable: StateFlow<Boolean>
    val products: StateFlow<List<BillingProduct>>
    
    fun launchBillingFlow(activity: Activity, productId: String)
}

data class BillingProduct(
    val id: String,
    val name: String,
    val formattedPrice: String,
    val description: String
)
