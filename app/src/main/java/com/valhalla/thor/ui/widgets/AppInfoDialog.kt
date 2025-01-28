package com.valhalla.thor.ui.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.valhalla.thor.R
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.getAppIcon

sealed interface AppClickAction {
    data class Launch(val appInfo: AppInfo) : AppClickAction
    data class Share(val appInfo: AppInfo) : AppClickAction
    data class Uninstall(val appInfo: AppInfo) : AppClickAction
    data class Reinstall(val appInfo: AppInfo) : AppClickAction
    data object ReinstallAll : AppClickAction
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppInfoDialog(
    modifier: Modifier = Modifier,
    appInfo: AppInfo,
    onDismiss: () -> Unit,
    onAppAction: (AppClickAction) -> Unit = {}
) {
    val context = LocalContext.current
    var getConfirmation by remember {
        mutableStateOf(false)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = rememberDrawablePainter(getAppIcon(appInfo.packageName, context)),
                contentDescription = appInfo.appName,
                modifier = Modifier
                    .padding(5.dp)
                    .size(70.dp)
            )
            Text(
                appInfo.appName ?: "",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 5.dp)
            )
            Row {
                if (appInfo.splitPublicSourceDirs.isNotEmpty())
                    Text(
                        text = "${appInfo.splitPublicSourceDirs.size} Splits",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(5.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(50)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.5.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                if (!appInfo.enabled)
                    Text(
                        text = "Frozen",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(5.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(50)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.5.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
            }


            Text(
                appInfo.packageName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 5.dp)
            )
            Text(
                appInfo.versionName ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 5.dp)
            )

            Spacer(modifier = Modifier.height(15.dp))

            FloatingBar(
                appInfo = appInfo,
                onDismiss = { onDismiss() },
                onAppAction = {
                    if (it is AppClickAction.Uninstall) {
                        if (appInfo.isSystem) {
                            getConfirmation = true
                        } else {
                            onDismiss()
                            onAppAction(AppClickAction.Uninstall(appInfo))
                        }
                    } else {
                        onAppAction(it)
                    }
                }
            )

            //ControlsBar(appInfo = appInfo,onAppAction = onAppAction)

        }
    }

    if (getConfirmation) {
        UninstallConfirmationDialog(
            appInfo = appInfo,
            onDismiss = { getConfirmation = false },
            onAppAction = onAppAction
        )
    }

}


@Preview(showBackground = true)
@Composable
fun FloatingBar(
    modifier: Modifier = Modifier,
    appInfo: AppInfo = AppInfo(),
    onAppAction: (AppClickAction) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    Row(
        modifier = modifier.padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {

        AppActionItem(
            icon = R.drawable.open_in_new,
            text = "Launch"
        ) {
            onDismiss()
            onAppAction(AppClickAction.Launch(appInfo))
        }

        if (!appInfo.isSystem && appInfo.installerPackageName != "com.android.vending")
            AppActionItem(
                icon = R.drawable.apk_install,
                text = "ReInstall"
            ) {
                onDismiss()
                onAppAction(AppClickAction.Reinstall(appInfo))
            }

        if (appInfo.isSystem.not())
            AppActionItem(
                icon = R.drawable.share,
                text = "Share"
            ) {
                onAppAction(AppClickAction.Share(appInfo))
            }

        AppActionItem(
            icon = R.drawable.delete_forever,
            text = "Uninstall",
        ) {
            onAppAction(AppClickAction.Uninstall(appInfo))
        }

    }
}

@Preview(showBackground = true)
@Composable
fun AppActionItem(
    modifier: Modifier = Modifier,
    icon: Int = R.drawable.open_in_new,
    text: String = "Launch",
    onClick: () -> Unit = {}
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                onClick()
            }
    ) {
        IconButton(
            onClick = {
                onClick()
            },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                painter = painterResource(id = icon),
                text,
                modifier = Modifier
                    .padding(2.dp)
                    .size(30.dp)
                    .padding(3.dp)
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier
                .padding(horizontal = 5.dp)
                .padding(bottom = 5.dp),
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

