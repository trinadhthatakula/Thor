package com.valhalla.thor.ui.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
            Text(
                appInfo.packageName ?: "",
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

            HorizontalFloatingToolbar(
                expanded = true,
                modifier = Modifier.padding(5.dp),
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()
            ) {
                IconButton(
                    onClick = {
                        onAppAction(AppClickAction.Launch(appInfo))
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.open_in_new),
                        "Launch",
                        modifier = Modifier
                            .padding(2.dp)
                            .size(30.dp)
                            .padding(3.dp)
                    )
                }
                if (!appInfo.isSystem && appInfo.installerPackageName != "com.android.vending")
                    IconButton(
                        onClick = {
                            onDismiss()
                            onAppAction(AppClickAction.Reinstall(appInfo))
                        }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.apk_install),
                            "Reinstall",
                            modifier = Modifier
                                .padding(2.dp)
                                .size(30.dp)
                                .padding(3.dp)
                        )
                    }
                if (appInfo.isSystem.not())
                    IconButton(
                        onClick = {
                            onAppAction(AppClickAction.Share(appInfo))
                        }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.share),
                            "Share",
                            modifier = Modifier
                                .padding(2.dp)
                                .size(30.dp)
                                .padding(3.dp)
                        )
                    }
                IconButton(
                    onClick = {
                        if (appInfo.isSystem) {
                            getConfirmation = true
                        } else {
                            onDismiss()
                            onAppAction(AppClickAction.Uninstall(appInfo))
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.delete_forever),
                        "Uninstall",
                        modifier = Modifier
                            .padding(2.dp)
                            .size(30.dp)
                            .padding(3.dp)
                    )
                }
            }

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

