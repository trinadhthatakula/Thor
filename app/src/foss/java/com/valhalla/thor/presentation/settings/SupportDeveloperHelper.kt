// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.valhalla.thor.R
import com.valhalla.thor.presentation.widgets.SupportAction
import com.valhalla.thor.presentation.widgets.SupportDeveloperBottomSheet

@Composable
fun SupportDeveloperHelper(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val sponsorsTitle = stringResource(R.string.sponsor_github_title)
    val sponsorsDesc = stringResource(R.string.sponsor_github_desc)
    val patreonTitle = stringResource(R.string.become_patreon_title)
    val patreonDesc = stringResource(R.string.become_patreon_desc)
    val kofiTitle = stringResource(R.string.support_kofi_title)
    val kofiDesc = stringResource(R.string.support_kofi_desc)
    val coffeeTitle = stringResource(R.string.buy_me_a_coffee_title)
    val coffeeDesc = stringResource(R.string.buy_me_a_coffee_desc)
    val paypalTitle = stringResource(R.string.donate_paypal_title)
    val paypalDesc = stringResource(R.string.donate_paypal_desc)

    val actions = remember(
        sponsorsTitle, sponsorsDesc, patreonTitle, patreonDesc,
        kofiTitle, kofiDesc, coffeeTitle, coffeeDesc, paypalTitle, paypalDesc
    ) {
        listOf(
            // First deliberately: lowest fees of the five, and it sits beside the source, which is
            // where a FOSS user already is. After it, recurring-capable before one-off.
            SupportAction(
                iconRes = R.drawable.brand_github,
                title = sponsorsTitle,
                description = sponsorsDesc,
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        "https://github.com/sponsors/trinadhthatakula".toUri()
                    )
                    runCatching { context.startActivity(intent) }
                    onDismiss()
                }
            ),
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
                iconRes = R.drawable.brand_kofi,
                title = kofiTitle,
                description = kofiDesc,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://ko-fi.com/trinadh".toUri())
                    runCatching { context.startActivity(intent) }
                    onDismiss()
                }
            ),
            SupportAction(
                iconRes = R.drawable.brand_buymeacoffee,
                title = coffeeTitle,
                description = coffeeDesc,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://www.buymeacoffee.com/trinadh".toUri())
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

    SupportDeveloperBottomSheet(
        actions = actions,
        onDismiss = onDismiss
    )
}
