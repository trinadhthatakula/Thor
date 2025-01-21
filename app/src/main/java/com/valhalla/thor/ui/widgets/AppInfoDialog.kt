package com.valhalla.thor.ui.widgets

import androidx.compose.foundation.Image
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.valhalla.thor.R
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.ui.screens.getAppIcon

sealed interface AppAction {
    data class Launch(val appInfo: AppInfo) : AppAction
    data class Share(val appInfo: AppInfo) : AppAction
    data class Uninstall(val appInfo: AppInfo) : AppAction
    data object Reinstall: AppAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoDialog(
    modifier: Modifier = Modifier,
    appInfo: AppInfo,
    onDismiss: () -> Unit,
    onAppAction: (AppAction) -> Unit = {}
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                    .size(50.dp)
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

            Spacer(modifier = Modifier.height(10.dp))

            ControlsBar(appInfo = appInfo,onAppAction = onAppAction)

        }
    }
}

@Preview(showBackground = true)
@Composable
fun ControlsBar(
    modifier: Modifier = Modifier,
    appInfo: AppInfo = AppInfo(),
    onAppAction: (AppAction) -> Unit = {}
) {
    Row(modifier) {

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(5.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    onAppAction(AppAction.Launch(appInfo))
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.open_in_new),
                "Launch",
                modifier = Modifier
                    .padding(2.dp)
                    .size(30.dp)
                    .padding(3.dp)
            )
            Text(
                "Launch",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(5.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(5.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    onAppAction(AppAction.Share(appInfo))
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ios_share),
                "Share",
                modifier = Modifier
                    .padding(2.dp)
                    .size(30.dp)
                    .padding(3.dp)
            )
            Text(
                "Share",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(5.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(5.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    onAppAction(AppAction.Uninstall(appInfo))
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.delete_forever),
                "Uninstall",
                modifier = Modifier
                    .padding(2.dp)
                    .size(30.dp)
                    .padding(3.dp)
            )
            Text(
                "Uninstall",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(5.dp)
            )
        }


    }
}