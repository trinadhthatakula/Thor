package com.valhalla.thor.presentation.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.valhalla.thor.R
import com.valhalla.thor.presentation.widgets.SupportAction
import com.valhalla.thor.presentation.widgets.SupportDeveloperBottomSheet
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

    val actions = remember(products, isBillingAvailable) {
        if (!isBillingAvailable || products.isEmpty()) {
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
                SupportAction(
                    iconRes = R.drawable.shield_with_heart,
                    title = product.name,
                    description = if (product.formattedPrice.isNotEmpty()) "${product.formattedPrice} / month" else product.description,
                    onClick = {
                        if (activity != null) {
                            billingProcessor.launchBillingFlow(activity, product.id)
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
