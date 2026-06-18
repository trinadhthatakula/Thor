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

    val actions = remember {
        listOf(
            SupportAction(
                iconRes = R.drawable.brand_patreon,
                title = context.getString(R.string.become_patreon_title),
                description = context.getString(R.string.become_patreon_desc),
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://www.patreon.com/trinadh".toUri())
                    context.startActivity(intent)
                    onDismiss()
                }
            ),
            SupportAction(
                iconRes = R.drawable.shield_with_heart,
                title = context.getString(R.string.donate_paypal_title),
                description = context.getString(R.string.donate_paypal_desc),
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://www.paypal.me/trinadhthatakula".toUri())
                    context.startActivity(intent)
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
