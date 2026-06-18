package com.valhalla.thor.presentation.settings

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.Single

@Single
class BillingProcessorImpl : BillingProcessor {
    override val isBillingAvailable: StateFlow<Boolean> = MutableStateFlow(false)
    override val products: StateFlow<List<BillingProduct>> = MutableStateFlow(emptyList())

    override fun launchBillingFlow(activity: Activity, productId: String) {
        // No-op in FOSS flavor
    }
}
