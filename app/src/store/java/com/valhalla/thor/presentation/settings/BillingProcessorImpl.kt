package com.valhalla.thor.presentation.settings

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.valhalla.thor.R
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentHashMap

@Single
class BillingProcessorImpl(
    private val context: Context
) : BillingProcessor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isBillingAvailable = MutableStateFlow(true)
    override val isBillingAvailable: StateFlow<Boolean> = _isBillingAvailable.asStateFlow()

    private val _products = MutableStateFlow<List<BillingProduct>>(emptyList())
    override val products: StateFlow<List<BillingProduct>> = _products.asStateFlow()

    private val _activeSubscription = MutableStateFlow<ActiveSubscription?>(null)
    override val activeSubscription: StateFlow<ActiveSubscription?> = _activeSubscription.asStateFlow()

    private val _showThankYouDialog = MutableStateFlow(false)
    override val showThankYouDialog: StateFlow<Boolean> = _showThankYouDialog.asStateFlow()

    private val productDetailsMap = ConcurrentHashMap<String, ProductDetails>()

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                for (purchase in purchases) {
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        acknowledgePurchase(purchase)
                    }
                }
            } else if (billingResult.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.billing_error) + ": " + billingResult.responseCode,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    init {
        connectToBilling()
    }

    private fun connectToBilling() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProducts()
                    queryActiveSubscriptions()
                } else {
                    _isBillingAvailable.value = false
                }
            }

            override fun onBillingServiceDisconnected() {
                _isBillingAvailable.value = false
            }
        })
    }

    private fun queryProducts() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId("support_tier_5")
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId("support_tier_10")
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId("support_tier_25")
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId("support_tier_50")
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        scope.launch {
            try {
                val result = billingClient.queryProductDetails(params)
                if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val detailsList = result.productDetailsList ?: emptyList()
                    val mappedProducts = mutableListOf<BillingProduct>()
                    
                    for (details in detailsList) {
                        productDetailsMap[details.productId] = details
                        val basePlan = details.subscriptionOfferDetails?.firstOrNull()
                        val priceText = basePlan?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice ?: ""
                        mappedProducts.add(
                            BillingProduct(
                                id = details.productId,
                                name = details.name,
                                formattedPrice = priceText,
                                description = details.description
                            )
                        )
                    }
                    _products.value = mappedProducts
                } else {
                    Logger.e("BillingProcessor", "Failed to query product details: ${result.billingResult.responseCode}")
                }
            } catch (e: Exception) {
                Logger.e("BillingProcessor", "Error querying product details", e)
            }
        }
    }

    private fun queryActiveSubscriptions() {
        if (!billingClient.isReady) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchaseList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val active = purchaseList.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                if (active != null) {
                    _activeSubscription.value = ActiveSubscription(
                        productId = active.products.firstOrNull() ?: "",
                        purchaseToken = active.purchaseToken
                    )
                } else {
                    _activeSubscription.value = null
                }
            } else {
                Logger.e("BillingProcessor", "Failed to query active purchases: ${billingResult.responseCode}")
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        scope.launch {
            try {
                val ackResult = billingClient.acknowledgePurchase(acknowledgePurchaseParams)
                if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _showThankYouDialog.value = true
                    queryActiveSubscriptions()
                } else {
                    Logger.e("BillingProcessor", "Failed to acknowledge purchase: ${ackResult.responseCode}")
                }
            } catch (e: Exception) {
                Logger.e("BillingProcessor", "Error acknowledging purchase", e)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun launchBillingFlow(activity: Activity, productId: String, oldPurchaseToken: String?) {
        val productDetails = productDetailsMap[productId]
        if (productDetails == null) {
            Logger.e("BillingProcessor", "Product details not found for $productId")
            return
        }
        val basePlan = productDetails.subscriptionOfferDetails?.firstOrNull()
        val offerToken = basePlan?.offerToken
        if (offerToken.isNullOrEmpty()) {
            Logger.e("BillingProcessor", "Offer token not found or empty for $productId")
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .setOfferToken(offerToken)
                .build()
        )
        val billingFlowParamsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)

        if (!oldPurchaseToken.isNullOrEmpty()) {
            val updateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                .setOldPurchaseToken(oldPurchaseToken)
                .setSubscriptionReplacementMode(
                    BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE
                )
                .build()
            billingFlowParamsBuilder.setSubscriptionUpdateParams(updateParams)
        }

        val billingFlowParams = billingFlowParamsBuilder.build()

        if (billingClient.isReady) {
            billingClient.launchBillingFlow(activity, billingFlowParams)
        } else {
            Logger.e("BillingProcessor", "BillingClient is not ready to launch billing flow")
        }
    }

    override fun dismissThankYouDialog() {
        _showThankYouDialog.value = false
    }
}
