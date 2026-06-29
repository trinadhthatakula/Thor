package com.valhalla.thor.presentation.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.valhalla.thor.R
import com.valhalla.thor.presentation.widgets.AffirmationDialog
import com.valhalla.thor.presentation.widgets.SupportAction
import com.valhalla.thor.presentation.widgets.SupportDeveloperTabbedBottomSheet
import com.valhalla.thor.presentation.widgets.SupportTab
import org.koin.compose.koinInject

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun SupportDeveloperHelper(
    onDismiss: () -> Unit,
    billingProcessor: BillingProcessor = koinInject()
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val isBillingAvailable by billingProcessor.isBillingAvailable.collectAsState()
    val products by billingProcessor.products.collectAsState()
    val activeSubscription by billingProcessor.activeSubscription.collectAsState()

    var pendingChangeProductId by remember { mutableStateOf<String?>(null) }

    // "Direct" tab — Patreon + PayPal. Always available, including in the store build.
    val directActions = remember {
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
            )
        )
    }

    // "Play Store" tab — subscription tiers when billing is available, otherwise rate/explore so
    // the tab is never empty.
    val playStoreActions = remember(products, isBillingAvailable, activeSubscription) {
        if (isBillingAvailable && products.isNotEmpty()) {
            val sortedProducts = products.sortedBy { product ->
                when (product.id) {
                    "support_tier_5" -> 5
                    "support_tier_10" -> 10
                    "support_tier_25" -> 25
                    "support_tier_50" -> 50
                    else -> 0
                }
            }

            sortedProducts.map { product ->
                val isActive = activeSubscription?.productId == product.id
                val descriptionText = if (isActive) {
                    context.getString(R.string.active_plan)
                } else if (product.formattedPrice.isNotEmpty()) {
                    "${product.formattedPrice} / month"
                } else {
                    product.description
                }

                SupportAction(
                    iconRes = if (isActive) R.drawable.check_circle else R.drawable.shield_with_heart,
                    title = product.name,
                    description = descriptionText,
                    onClick = {
                        if (isActive) {
                            // Already active, do nothing
                        } else {
                            if (activeSubscription != null) {
                                pendingChangeProductId = product.id
                            } else {
                                if (activity != null) {
                                    billingProcessor.launchBillingFlow(activity, product.id)
                                }
                                onDismiss()
                            }
                        }
                    }
                )
            }
        } else {
            listOf(
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
        }
    }

    val tabs = remember(playStoreActions, directActions) {
        listOf(
            SupportTab(context.getString(R.string.support_tab_play_store), playStoreActions),
            SupportTab(context.getString(R.string.support_tab_direct), directActions)
        )
    }

    if (pendingChangeProductId != null) {
        AffirmationDialog(
            title = stringResource(R.string.change_support_plan_title),
            text = stringResource(R.string.change_support_plan_desc),
            icon = R.drawable.shield_with_heart,
            onConfirm = {
                val productId = pendingChangeProductId
                if (activity != null && productId != null) {
                    billingProcessor.launchBillingFlow(
                        activity = activity,
                        productId = productId,
                        oldPurchaseToken = activeSubscription?.purchaseToken,
                        oldProductId = activeSubscription?.productId
                    )
                }
                pendingChangeProductId = null
                onDismiss()
            },
            onRejected = {
                pendingChangeProductId = null
            }
        )
    }

    SupportDeveloperTabbedBottomSheet(
        tabs = tabs,
        onDismiss = onDismiss
    )
}
