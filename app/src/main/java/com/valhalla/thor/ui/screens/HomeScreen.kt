package com.valhalla.thor.ui.screens

import android.content.Context.MODE_PRIVATE
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.valhalla.thor.R
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.AppListType
import com.valhalla.thor.model.AppListener
import com.valhalla.thor.model.getTokenResponse
import com.valhalla.thor.model.getVerdict
import com.valhalla.thor.model.initStandardIntegrityProvider
import com.valhalla.thor.model.parseIntegrityIcon
import com.valhalla.thor.model.parseIntegrityStatus
import com.valhalla.thor.model.rootAvailable
import com.valhalla.thor.registerReceiver
import com.valhalla.thor.ui.theme.greenDark
import com.valhalla.thor.ui.theme.greenLight
import com.valhalla.thor.ui.widgets.TypeWriterText


sealed interface HomeActions {
    data class ShowToast(val text: String, val longDuration: Boolean = false) : HomeActions
    data class FrozenApps(val appListType: AppListType) : HomeActions
    data class ActiveApps(val appListType: AppListType) : HomeActions
    data object SwitchAutoReinstall : HomeActions
    data object BKI : HomeActions
}

var deviceIntegrityJsonBackup = ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    userAppList: List<AppInfo> = emptyList(),
    systemAppList: List<AppInfo> = emptyList(),
    onHomeActions: (HomeActions) -> Unit
) {

    val context = LocalContext.current

    var usedTokens by remember { mutableStateOf(emptyList<String>()) }

    var tokenString by rememberSaveable { mutableStateOf("") }
    var deviceIntegrityJson by rememberSaveable { mutableStateOf(deviceIntegrityJsonBackup) }
    var integrityStatus by remember { mutableStateOf("Checking Integrity") }
    var integrityIcon by remember { mutableIntStateOf(R.drawable.shield_countdown) }

    LaunchedEffect(Unit) {
        if (deviceIntegrityJson.isEmpty())
            context.initStandardIntegrityProvider { integrityTokenProvider ->
                if (integrityTokenProvider.isSuccess) {
                    integrityTokenProvider.getOrNull()?.let { provider ->
                        getVerdict(provider) { tokenResponse ->
                            if (tokenResponse.isSuccess) {
                                tokenResponse.getOrNull()?.let { token ->
                                    tokenString = token
                                }
                            }
                        }
                    }
                }
            }
    }

    LaunchedEffect(tokenString) {
        if (deviceIntegrityJson.isEmpty() && tokenString.isNotEmpty() && usedTokens.contains(
                tokenString
            ).not()
        ) {
            getTokenResponse(tokenString) { jsonResult ->
                if (jsonResult.isSuccess) {
                    jsonResult.getOrNull()?.let {
                        Log.d("HomeScreen", "HomeScreen: token is $tokenString")
                        deviceIntegrityJson = it
                        deviceIntegrityJsonBackup = deviceIntegrityJson
                    }
                    usedTokens += tokenString
                    integrityStatus = parseIntegrityStatus(deviceIntegrityJson)
                    integrityIcon = parseIntegrityIcon(deviceIntegrityJson)
                }
            }
        }
    }

    var canReinstall by remember { mutableStateOf(context.getSharedPreferences("prefs", MODE_PRIVATE)
        .getBoolean("can_reinstall", false) == true) }

    HomeContent(
        modifier,
        userAppList,
        systemAppList,
        integrityStatus,
        integrityIcon,
        canReinstall
    ) { homeAction ->
        if (homeAction is HomeActions.ShowToast) {
            Toast.makeText(context, homeAction.text, Toast.LENGTH_SHORT).show()
        } else if (homeAction is HomeActions.SwitchAutoReinstall) {
            try {
                context.getSharedPreferences("prefs", MODE_PRIVATE).edit {
                    putBoolean(
                        "can_reinstall",
                        !canReinstall
                    )
                }
                canReinstall = !canReinstall
                if (canReinstall) {
                    context.registerReceiver(AppListener.getInstance())
                    Toast.makeText(context, "Auto Reinstall Enabled", Toast.LENGTH_SHORT).show()
                } else {
                    context.unregisterReceiver(AppListener.getInstance())
                    Toast.makeText(context, "Auto Reinstall Disabled", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else onHomeActions(homeAction)
    }


}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun HomeContent(
    modifier: Modifier = Modifier,
    userAppList: List<AppInfo> = emptyList(),
    systemAppList: List<AppInfo> = emptyList(),
    integrityStatus: String = "Checking Integrity",
    integrityIcon: Int = R.drawable.shield_countdown,
    canReinstall: Boolean = false,
    onHomeActions: (HomeActions) -> Unit = {}
) {

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.thor_mono),
                "App Icon",
                modifier = Modifier
                    .padding(5.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable {

                    }
                    .padding(8.dp)
            )
            TypeWriterText(
                text = "Thor",
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .padding(vertical = 10.dp)
                    .weight(1f),
                delay = 25,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Start
            )

            val rootStatus =
                if (rootAvailable()) "Root access granted" else "Root access not available"
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(10.dp),
                tooltip = {
                    PlainTooltip {
                        Text(rootStatus)
                    }
                },
                state = rememberTooltipState()
            ) {
                Icon(
                    painterResource(R.drawable.magisk_icon),
                    "Root Icon",
                    tint = if (rootAvailable()) {
                        if (isSystemInDarkTheme()) greenDark else greenLight
                    } else MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(5.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable {
                            onHomeActions(HomeActions.ShowToast(rootStatus))
                        }
                        .padding(3.dp)
                )
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(10.dp),
                tooltip = {
                    PlainTooltip {
                        Text(integrityStatus)
                    }
                },
                state = rememberTooltipState()
            ) {
                Icon(
                    painterResource(integrityIcon),
                    "Integrity Indicator",
                    modifier = Modifier
                        .padding(vertical = 5.dp)
                        .padding(end = 10.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable {
                            onHomeActions(HomeActions.ShowToast(integrityStatus))
                        }
                        .padding(3.dp)
                )
            }
        }

        var appListType by remember { mutableStateOf(AppListType.USER) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (appListType == AppListType.USER) "Apps Installed (${userAppList.size})" else "System Apps (${systemAppList.size})",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .padding(top = 5.dp)
                    .weight(1f)
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(horizontal = 5.dp)) {
                AppListType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        selected = appListType == type,
                        onClick = {
                            appListType = type
                        },
                        icon = {}
                    ) {
                        Icon(
                            painter = painterResource(if (type == AppListType.USER) R.drawable.apps else R.drawable.android),
                            appListType.name
                        )
                    }
                }

            }
        }

        var appsMapByInstaller by remember {
            mutableStateOf(
                (if (appListType == AppListType.USER) userAppList else systemAppList).groupBy {
                    it.installerPackageName
                        ?: if (appListType == AppListType.USER) "Unknown" else "System"
                }.mapValues { it.value.size }
            )
        }

        var activeAppsCount by remember {
            mutableIntStateOf(
                if (appListType == AppListType.USER) userAppList.count { it.enabled }
                else systemAppList.count { it.enabled }
            )
        }
        val animatedActiveAppCount by animateIntAsState(targetValue = activeAppsCount)

        var frozenAppsCount by remember {
            mutableIntStateOf(
                if (appListType == AppListType.USER) userAppList.count { it.enabled.not() }
                else systemAppList.count { it.enabled.not() }
            )
        }
        val animatedFrozenAppCount by animateIntAsState(targetValue = frozenAppsCount)

        LaunchedEffect(appListType) {
            activeAppsCount =
                if (appListType == AppListType.USER) userAppList.count { it.enabled }
                else systemAppList.count { it.enabled }
            frozenAppsCount =
                if (appListType == AppListType.USER) userAppList.count { it.enabled.not() }
                else systemAppList.count { it.enabled.not() }
            appsMapByInstaller =
                (if (appListType == AppListType.USER) userAppList else systemAppList).groupBy {
                    it.installerPackageName
                        ?: if (appListType == AppListType.USER) "Unknown" else "System"
                }.mapValues { it.value.size }
        }

        val colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.error,
            Color.Green,
            Color.Yellow,
            Color.Red,
            Color.Magenta
        )

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(5.dp)
        ) {
            PieChart(
                data = appsMapByInstaller,
                radiusOuter = 80.dp,
                chartBarWidth = 20.dp,
                animDuration = 1000,
                colors = colors,
                modifier = Modifier.weight(1f)
            )
            Column(modifier = Modifier.weight(1f)) {
                appsMapByInstaller.keys.forEachIndexed { index, key ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(color = colors[index], CircleShape)
                        )
                        Text(
                            text = systemAppList.firstOrNull { it.packageName == key }?.appName
                                ?: key,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(5.dp)
                                .weight(1f),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "(${appsMapByInstaller[key]})",
                            maxLines = 1,
                            modifier = Modifier
                                .padding(5.dp)
                                .weight(1f),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                }
            }

        }

        Row(modifier = Modifier.padding(horizontal = 5.dp)) {
            ElevatedCard(
                onClick = {
                    onHomeActions(HomeActions.ActiveApps(appListType))
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 5.dp)
            ) {
                Column(modifier = Modifier.padding(5.dp)) {
                    Text(
                        text = animatedActiveAppCount.toString(),
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(horizontal = 5.dp)
                    )
                    Text(
                        text = "Active Apps",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 5.dp)
                    )
                }
            }
            ElevatedCard(
                onClick = {
                    onHomeActions(HomeActions.FrozenApps(appListType))
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 5.dp)
            ) {
                Column(
                    modifier = Modifier.padding(5.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = animatedFrozenAppCount.toString(),
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(horizontal = 5.dp)
                    )
                    Text(
                        text = "Frozen Apps",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 5.dp)
                    )
                }
            }
        }



        Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 10.dp)) {
            ElevatedCard(
                modifier = Modifier
                    .padding(5.dp)
            ) {

                Row(
                    modifier = Modifier
                        .padding(10.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                    ) {
                        Text(
                            text = "Auto Reinstall",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(5.dp)
                        )

                        Text(
                            text = "Thor listens to APP installation and tries to reinstall it with google",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 5.dp)
                        )
                    }
                    Switch(
                        checked = canReinstall,
                        onCheckedChange = {
                            onHomeActions(HomeActions.SwitchAutoReinstall)
                        },
                        modifier = Modifier.padding(5.dp)
                    )
                }

            }
            /*ElevatedCard(
                modifier = Modifier
                    .padding(5.dp),
                onClick = {
                    onHomeActions(HomeActions.BKI)
                }
            ) {
                Column(
                    modifier = Modifier
                        .padding(10.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "</> BetterKnownInstalled(BKI)",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(5.dp)
                    )
                    if (commandExists("abx2xml") != "command not found") {
                        Text(
                            text = "ABX2XML Supported",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            text = "ABX2XML Not Supported",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = "use Better known installer script by T3SL4 to edit packages.xml",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

            }*/

            Spacer(modifier = Modifier.weight(1f))

            ElevatedCard(
                modifier = Modifier
                    .padding(5.dp)
                    .align(Alignment.CenterHorizontally)
            ) {
                val uriHandler = LocalUriHandler.current
                Column(
                    modifier = Modifier
                        .padding(5.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    /*Text(
                        text = "Support us",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(5.dp)
                    )*/
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                    ) {
                        IconButton(onClick = { uriHandler.openUri("https://github.com/trinadhthatakula/Thor") }) {
                            Icon(
                                painterResource(R.drawable.brand_github),
                                contentDescription = null,
                            )
                        }
                        IconButton(onClick = { uriHandler.openUri("https://patreon.com/trinadh") }) {
                            Icon(
                                painterResource(R.drawable.brand_patreon),
                                contentDescription = null,
                            )
                        }
                        IconButton(onClick = { uriHandler.openUri("https://t.me/thorAppDev") }) {
                            Icon(
                                painterResource(R.drawable.brand_telegram),
                                contentDescription = null,
                            )
                        }
                    }
                }
            }
        }


    }
}

@Composable
fun PieChart(
    modifier: Modifier = Modifier,
    data: Map<String, Int>,
    colors: List<Color> = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
        Color.Green,
        Color.Yellow,
        Color.Red,
        Color.Magenta
    ),
    radiusOuter: Dp,
    chartBarWidth: Dp,
    innerPadding: PaddingValues = PaddingValues(chartBarWidth * 2),
    gap: Int = 2,
    animDuration: Int
) {

    val totalSum = data.values.sum()
    val floatValue = mutableListOf<Float>()

    data.values.forEachIndexed { index, values ->
        floatValue.add(index, 360 * values.toFloat() / totalSum.toFloat())
    }


    var animationPlayed by remember { mutableStateOf(false) }

    var lastValue = 0f

    val animateSize by animateFloatAsState(
        targetValue = if (animationPlayed) radiusOuter.value * 2f else 0f,
        animationSpec = tween(
            durationMillis = animDuration,
            delayMillis = 0,
            easing = LinearOutSlowInEasing
        )
    )

    // if you want to stabilize the Pie Chart you can use value -90f
    // 90f is used to complete 1/4 of the rotation
    val animateRotation by animateFloatAsState(
        targetValue = if (animationPlayed) 90f * 11f else 0f,
        animationSpec = tween(
            durationMillis = animDuration,
            delayMillis = 0,
            easing = LinearOutSlowInEasing
        )
    )

    LaunchedEffect(key1 = true) {
        animationPlayed = true
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // Pie Chart using Canvas Arc
        Box(
            modifier = Modifier
                .size(animateSize.dp)
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(radiusOuter * 2)
                    .rotate(animateRotation)
            ) {
                // draw each Arc for each data entry in Pie Chart
                floatValue.forEachIndexed { index, value ->
                    drawArc(
                        color = colors[index],
                        lastValue,
                        value - gap,
                        useCenter = false,
                        style = Stroke(chartBarWidth.toPx(), cap = StrokeCap.Butt)
                    )
                    lastValue += value + gap
                }
            }
        }
    }
    /*
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(data.toList().sortedBy { it.first }) { pair ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = pair.first
                        )
                    },
                    trailingContent = {
                        Text(
                            text = pair.second.toString()
                        )
                    }
                )
            }

        }*/

}
