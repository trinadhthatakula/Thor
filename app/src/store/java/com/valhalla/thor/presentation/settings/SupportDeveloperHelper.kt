package com.valhalla.thor.presentation.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.valhalla.thor.R
import com.valhalla.thor.presentation.widgets.SupportAction
import com.valhalla.thor.presentation.widgets.SupportDeveloperBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun SupportDeveloperHelper(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val coroutineScope = rememberCoroutineScope()

    var productDetailsList by remember { mutableStateOf<List<ProductDetails>>(emptyList()) }
    var isBillingAvailable by remember { mutableStateOf(true) }

    val billingClient = remember {
        lateinit var client: BillingClient
        client = BillingClient.newBuilder(context.applicationContext)
            .setListener { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    for (purchase in purchases) {
                        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                                .setPurchaseToken(purchase.purchaseToken)
                                .build()
                            coroutineScope.launch {
                                val ackResult = client.acknowledgePurchase(acknowledgePurchaseParams)
                                if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, context.getString(R.string.thank_you_support), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                } else if (billingResult.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                    Toast.makeText(context, context.getString(R.string.billing_error) + ": " + billingResult.responseCode, Toast.LENGTH_SHORT).show()
                }
            }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()
        client
    }

    LaunchedEffect(billingClient) {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
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

                    coroutineScope.launch {
                        val result = billingClient.queryProductDetails(params)
                        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            productDetailsList = result.productDetailsList ?: emptyList()
                        }
                    }
                } else {
                    isBillingAvailable = false
                }
            }

            override fun onBillingServiceDisconnected() {
                isBillingAvailable = false
            }
        })
    }

    DisposableEffect(billingClient) {
        onDispose {
            if (billingClient.isReady) {
                billingClient.endConnection()
            }
        }
    }

    val actions = remember(productDetailsList, isBillingAvailable) {
        if (!isBillingAvailable || productDetailsList.isEmpty()) {
            listOf(
                SupportAction(
                    iconRes = R.drawable.brand_patreon,
                    title = context.getString(R.string.become_patreon_title),
                    description = context.getString(R.string.become_patreon_desc),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://www.patreon.com/trinadh".toUri())
                        runCatching { context.startActivity(intent) }
                        onDismiss()
                    }
                ),
                SupportAction(
                    iconRes = R.drawable.shield_with_heart,
                    title = context.getString(R.string.donate_paypal_title),
                    description = context.getString(R.string.donate_paypal_desc),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://www.paypal.me/trinadhthatakula".toUri())
                        runCatching { context.startActivity(intent) }
                        onDismiss()
                    }
                ),
                SupportAction(
                    iconRes = R.drawable.open_in_new,
                    title = context.getString(R.string.rate_play_store_title),
                    description = context.getString(R.string.rate_play_store_desc),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "market://details?id=com.valhalla.thor".toUri()).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        }
                        runCatching { context.startActivity(intent) }.onFailure {
                            val webIntent = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=com.valhalla.thor".toUri())
                            runCatching { context.startActivity(webIntent) }
                        }
                        onDismiss()
                    }
                ),
                SupportAction(
                    iconRes = R.drawable.apps,
                    title = context.getString(R.string.explore_other_apps_title),
                    description = context.getString(R.string.explore_other_apps_desc),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/developer?id=Spectra+Apps".toUri())
                        runCatching { context.startActivity(intent) }
                        onDismiss()
                    }
                )
            )
        } else {
            val sortedDetails = productDetailsList.filter { it.subscriptionOfferDetails?.isNotEmpty() == true }.sortedBy { details ->
                when (details.productId) {
                    "support_tier_5" -> 5
                    "support_tier_10" -> 10
                    "support_tier_25" -> 25
                    "support_tier_50" -> 50
                    else -> 0
                }
            }

            sortedDetails.mapNotNull { productDetails ->
                val basePlan = productDetails.subscriptionOfferDetails?.firstOrNull()
                val offerToken = basePlan?.offerToken
                if (offerToken.isNullOrEmpty()) return@mapNotNull null
                val priceText = basePlan.pricingPhases.pricingPhaseList.firstOrNull()?.formattedPrice ?: ""
                SupportAction(
                    iconRes = R.drawable.shield_with_heart,
                    title = productDetails.name,
                    description = if (priceText.isNotEmpty()) "$priceText / month" else productDetails.description,
                    onClick = {
                        if (activity != null) {
                            val productDetailsParamsList = listOf(
                                BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(productDetails)
                                    .setOfferToken(offerToken)
                                    .build()
                            )
                            val billingFlowParams = BillingFlowParams.newBuilder()
                                .setProductDetailsParamsList(productDetailsParamsList)
                                .build()
                            billingClient.launchBillingFlow(activity, billingFlowParams)
                        }
                        onDismiss()
                    }
                )
            }
        }
    }

    SupportDeveloperBottomSheet(
        actions = actions,
        onDismiss = onDismiss
    )
}
