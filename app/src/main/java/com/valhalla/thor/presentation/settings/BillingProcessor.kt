// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

interface BillingProcessor : AutoCloseable {
    val isBillingAvailable: StateFlow<Boolean>

    /**
     * The support tiers Play is currently selling, **cheapest first**.
     *
     * Ordering is part of the contract rather than each screen's problem: it is decided once, from
     * Play's own prices, by [sortSupportTiers]. A consumer that re-sorts is reintroducing the guess
     * that function exists to remove.
     *
     * Which tiers appear is decided by Play, not by this list's length — see
     * [SUPPORT_TIER_PRODUCT_IDS]. Empty means either that billing is unavailable or that the
     * catalogue query has not succeeded yet; the support sheet renders its non-billing fallback in
     * both cases.
     */
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
     * Re-reads the account's purchases and acknowledges anything Play still owes an acknowledgement
     * for. Invoked from the activity lifecycle on every resume, because a purchase completed while
     * Thor's process was dead is auto-refunded by Google three days later and a resume is the first
     * moment Thor can catch it. No-op for flavors without a real billing client.
     */
    fun refreshPurchases()

    /**
     * Tears down any long-lived resources (Play billing connection, coroutine scope).
     * Invoked from the application lifecycle. No-op for flavors without a real billing client.
     */
    override fun close()
}

data class BillingProduct(
    val id: String,
    val name: String,
    /**
     * The price as Play formatted it — already localized to the user's currency and conventions
     * (`$1.00`, `₹99.00`, `1,00 €`). **This is the only thing shown to the user**; see
     * [priceAmountMicros] for why the numeric form exists and is never rendered.
     */
    val formattedPrice: String,
    val description: String,
    /**
     * ISO-8601 period the [formattedPrice] is charged at, straight from Play (`P1M`, `P1Y`, ...).
     * Carried instead of a rendered string so the UI picks a translated label; empty when Play
     * reported no period, which the UI renders as the bare price.
     */
    val billingPeriod: String = "",
    /**
     * [formattedPrice] as a sortable number. Ordering only, never displayed — see
     * [SubscriptionPricingPhase.priceAmountMicros]. `0` means Play reported no price, which
     * [sortSupportTiers] treats as unknown and orders last.
     */
    val priceAmountMicros: Long = 0L,
    /** ISO 4217 code for [priceAmountMicros]. See [SubscriptionPricingPhase.priceCurrencyCode]. */
    val priceCurrencyCode: String = ""
)

data class ActiveSubscription(
    val productId: String,
    val purchaseToken: String
)
