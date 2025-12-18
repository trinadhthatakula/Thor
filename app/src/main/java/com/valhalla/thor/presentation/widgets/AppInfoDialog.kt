package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import coil3.compose.rememberAsyncImagePainter
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.presentation.utils.getAppIcon

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppInfoDialog(
    modifier: Modifier = Modifier,
    appInfo: AppInfo,
    isRoot: Boolean = false,    // <--- NEW PARAM
    isShizuku: Boolean = false, // <--- NEW PARAM
    onDismiss: () -> Unit,
    onAppAction: (AppClickAction) -> Unit = {}
) {
    val context = LocalContext.current
    var getConfirmation by remember { mutableStateOf(false) }

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

            Row(modifier = Modifier.align(Alignment.End)){
                IconButton(
                    onClick = {
                        onAppAction(AppClickAction.AppInfoSettings(appInfo))
                    },
                    modifier = Modifier.padding(end = 10.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.settings),
                        "Settings"
                    )
                }
            }

            Image(
                painter = rememberAsyncImagePainter(getAppIcon(appInfo.packageName, context)),
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

            // Chips Row
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
                isRoot = isRoot,          // PASS DOWN
                isShizuku = isShizuku,    // PASS DOWN
                onDismiss = { onDismiss() },
                onAppAction = {
                    if (it is AppClickAction.Uninstall) {
                        if (appInfo.isSystem) {
                            getConfirmation = true
                        } else {
                            onAppAction(AppClickAction.Uninstall(appInfo))
                            onDismiss()
                        }
                    } else {
                        onAppAction(it)
                    }
                }
            )
        }
    }

    if (getConfirmation) {
        AlertDialog(
            onDismissRequest = { getConfirmation = false },
            confirmButton = {
                TextButton(onClick = {
                    onAppAction(AppClickAction.Uninstall(appInfo))
                    getConfirmation = false
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { getConfirmation = false }) { Text("No") }
            },
            title = { Text("Uninstall ${appInfo.appName}?") },
            text = {
                Text("Are you sure you want to uninstall ${appInfo.appName}?${if (appInfo.isSystem) "\nthis is a system app it might be risky, you can freeze them instead" else ""}")
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun FloatingBar(
    modifier: Modifier = Modifier,
    appInfo: AppInfo = AppInfo(),
    isRoot: Boolean = false,    // <--- NEW PARAM
    isShizuku: Boolean = false, // <--- NEW PARAM
    onAppAction: (AppClickAction) -> Unit = {},
    onDismiss: () -> Unit = {}
) {

    val isFrozen by remember { mutableStateOf(appInfo.enabled.not()) }
    // Logic: Actions available if Root OR Shizuku is present
    val hasPrivilege = isRoot || isShizuku

    Row(
        modifier = modifier
            .padding(horizontal = 30.dp)
            .horizontalScroll(rememberScrollState()),
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

        // Reinstall: STRICTLY Root only (Google Play Hack requirement)
        if (!appInfo.isSystem && appInfo.installerPackageName != "com.android.vending" && isRoot) {
            AppActionItem(
                icon = R.drawable.apk_install,
                text = "ReInstall"
            ) {
                onDismiss()
                onAppAction(AppClickAction.Reinstall(appInfo))
            }
        }

        AppActionItem(
            icon = R.drawable.share,
            text = "Share"
        ) {
            onAppAction(AppClickAction.Share(appInfo))
        }

        // Freeze/Unfreeze: Root OR Shizuku
        if (hasPrivilege) {
            AppActionItem(
                icon = if (isFrozen) R.drawable.unfreeze else R.drawable.frozen,
                text = if (isFrozen) "Unfreeze" else "Freeze",
            ) {
                if (isFrozen)
                    onAppAction(AppClickAction.UnFreeze(appInfo))
                else
                    onAppAction(AppClickAction.Freeze(appInfo))
                onDismiss()
            }
        }

        // Cache: Root OR Shizuku
        if (hasPrivilege) {
            AppActionItem(
                icon = R.drawable.clear_all,
                text = "Cache",
            ) {
                onAppAction(AppClickAction.ClearCache(appInfo))
            }
        }

        // Kill: Root OR Shizuku (Recommended, though simple kill might work without)
        if (appInfo.enabled && hasPrivilege) {
            AppActionItem(
                icon = R.drawable.danger,
                text = "Kill App",
            ) {
                onAppAction(AppClickAction.Kill(appInfo))
            }
        }

        // Uninstall
        if (appInfo.packageName != "com.valhalla.thor" && appInfo.packageName !="com.android.vending") {
            AppActionItem(
                icon = R.drawable.delete_forever,
                text = "Uninstall",
            ) {
                onAppAction(AppClickAction.Uninstall(appInfo))
            }
        }
    }
}

// ... (AppActionItem and RotatableActionItem remain unchanged) ...
@Composable
fun AppActionItem(
    modifier: Modifier = Modifier,
    icon: Int,
    text: String,
    onClick: () -> Unit
) {
    // ... [Same implementation as before]
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        IconButton(
            onClick = { onClick() },
            colors = IconButtonDefaults.filledIconButtonColors()
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