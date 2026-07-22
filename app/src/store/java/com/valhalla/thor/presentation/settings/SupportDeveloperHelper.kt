// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

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
    // Resolve strings via stringResource (configuration-aware) in composable scope, then pass them
    // into remember as keys so the list recomputes on locale/config change.
    val patreonTitle = stringResource(R.string.become_patreon_title)
    val patreonDesc = stringResource(R.string.become_patreon_desc)
    val paypalTitle = stringResource(R.string.donate_paypal_title)
    val paypalDesc = stringResource(R.string.donate_paypal_desc)
    val directActions = remember(patreonTitle, patreonDesc, paypalTitle, paypalDesc) {
        listOf(
            SupportAction(
                iconRes = R.drawable.brand_patreon,
                title = patreonTitle,
                description = patreonDesc,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://www.patreon.com/trinadh".toUri())
                    runCatching { context.startActivity(intent) }
                    onDismiss()
                }
            ),
            SupportAction(
                iconRes = R.drawable.shield_with_heart,
                title = paypalTitle,
                description = paypalDesc,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://www.paypal.me/trinadhthatakula".toUri())
                    runCatching { context.startActivity(intent) }
                    onDismiss()
                }
            )
        )
    }

    // "Play Store" tab — subscription tiers when billing is available, otherwise rate/explore so
    // the tab is never empty. Strings resolved in composable scope (configuration-aware) and passed
    // as remember keys.
    val activePlanText = stringResource(R.string.active_plan)
    val rateTitle = stringResource(R.string.rate_play_store_title)
    val rateDesc = stringResource(R.string.rate_play_store_desc)
    val exploreTitle = stringResource(R.string.explore_other_apps_title)
    val exploreDesc = stringResource(R.string.explore_other_apps_desc)
    val playStoreActions = remember(
        products, isBillingAvailable, activeSubscription,
        activePlanText, rateTitle, rateDesc, exploreTitle, exploreDesc
    ) {
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
                    activePlanText
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
                    title = rateTitle,
                    description = rateDesc,
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
                    title = exploreTitle,
                    description = exploreDesc,
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/developer?id=Spectra+Apps".toUri())
                        runCatching { context.startActivity(intent) }
                        onDismiss()
                    }
                )
            )
        }
    }

    val playStoreTabLabel = stringResource(R.string.support_tab_play_store)
    val directTabLabel = stringResource(R.string.support_tab_direct)
    val tabs = remember(playStoreActions, directActions, playStoreTabLabel, directTabLabel) {
        listOf(
            SupportTab(playStoreTabLabel, playStoreActions),
            SupportTab(directTabLabel, directActions)
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
