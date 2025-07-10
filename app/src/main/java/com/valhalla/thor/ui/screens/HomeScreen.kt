package com.valhalla.thor.ui.screens

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.valhalla.thor.R
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.AppListType
import com.valhalla.thor.model.shizuku.ElevatableState
import com.valhalla.thor.model.shizuku.ShizukuState
import com.valhalla.thor.model.generateRandomColors
import com.valhalla.thor.model.getRootStatusText
import com.valhalla.thor.model.rootAvailable
import com.valhalla.thor.model.shizuku.ShizukuManager
import com.valhalla.thor.ui.theme.greenDark
import com.valhalla.thor.ui.theme.greenLight
import com.valhalla.thor.ui.widgets.TypeWriterText

sealed interface HomeActions {
    data class ShowToast(val text: String, val longDuration: Boolean = false) : HomeActions
    data class FrozenApps(val appListType: AppListType) : HomeActions
    data class ActiveApps(val appListType: AppListType) : HomeActions
    data object SwitchAutoReinstall : HomeActions
    data object ReinstallAll : HomeActions
    data object BKI : HomeActions
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    userAppList: List<AppInfo> = emptyList(),
    systemAppList: List<AppInfo> = emptyList(),
    onHomeActions: (HomeActions) -> Unit
) {

    val context = LocalContext.current

    HomeContent(
        modifier,
        userAppList,
        systemAppList,
    ) { homeAction ->
        if (homeAction is HomeActions.ShowToast) {
            Toast.makeText(context, homeAction.text, Toast.LENGTH_SHORT).show()
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
    shizukuManager: ShizukuManager = viewModel(),
    onHomeActions: (HomeActions) -> Unit = {}
) {
    val shizukuState by shizukuManager.shizukuState.collectAsStateWithLifecycle()
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

            var rootStatus by remember {
                mutableStateOf(
                    getRootStatusText(rootAvailable(),shizukuState)
                )
            }
            var rootIcon by remember { mutableIntStateOf(R.drawable.magisk_icon) }
            var elevatable by remember { mutableStateOf(ElevatableState.NONE) }
            LaunchedEffect(shizukuState) {
                Log.d("HomeScreen", "HomeContent: shizuku state changed to $shizukuState")
                if (!rootAvailable()) {
                    if(shizukuState == ShizukuState.Ready || shizukuState == ShizukuState.PermissionNeeded){
                        rootIcon = R.drawable.shizuku
                    }
                    elevatable = when(shizukuState){
                        ShizukuState.NotInstalled -> ElevatableState.SHIZUKU_NOT_INSTALLED
                        ShizukuState.NotRunning -> ElevatableState.SHIZUKU_NOT_RUNNING
                        ShizukuState.PermissionNeeded -> ElevatableState.SHIZUKU_PERMISSION_NEEDED
                        ShizukuState.Ready -> ElevatableState.SHIZUKU_RUNNING
                    }
                    rootStatus = getRootStatusText(rootAvailable(),shizukuState)
                }else{
                    elevatable = ElevatableState.SU
                }
            }
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
                    painterResource(rootIcon),
                    "Root Icon",
                    tint = if (elevatable == ElevatableState.SU || elevatable == ElevatableState.SHIZUKU_RUNNING) {
                        if (isSystemInDarkTheme()) greenDark else greenLight
                    } else MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(5.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (elevatable == ElevatableState.SHIZUKU_PERMISSION_NEEDED) {
                                shizukuManager.requestPermission()
                            }
                            onHomeActions(HomeActions.ShowToast(rootStatus))
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

        LaunchedEffect(appListType, userAppList, systemAppList) {
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

        val colors = generateRandomColors(
            appsMapByInstaller.keys.size,
            true,
            MaterialTheme.colorScheme.primary.toArgb(),
            MaterialTheme.colorScheme.secondary.toArgb(),
            MaterialTheme.colorScheme.tertiary.toArgb(),
            MaterialTheme.colorScheme.error.toArgb(),
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
                                .background(color = colors[index % colors.size], CircleShape)
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
                    if (activeAppsCount > 0)
                        onHomeActions(HomeActions.ActiveApps(appListType))
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 5.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
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
                    if (frozenAppsCount > 0)
                        onHomeActions(HomeActions.FrozenApps(appListType))
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 5.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
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

        val canReinstallAll = false
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 10.dp)) {

            val unknownAppsCount by animateIntAsState(userAppList.count {
                it.installerPackageName != "com.android.vending"
            })

            if (rootAvailable() && canReinstallAll) { ///disable this for now
                if (unknownAppsCount > 0) {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(5.dp),
                        onClick = {
                            onHomeActions(HomeActions.ReinstallAll)
                        }
                    ) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "Reinstall All",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                modifier = Modifier
                                    .padding(5.dp)
                            )

                            Text(
                                text = "$unknownAppsCount of ${userAppList.size} user apps are not installed from play store, try reinstalling them?",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .padding(horizontal = 5.dp)
                                    .padding(bottom = 5.dp)
                            )
                        }
                    }
                }

                /*val context = LocalContext.current
                        val trickyTargets = readTargets(context)
                        if(trickyTargets.isNotEmpty()){
                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(5.dp),
                                onClick = {
                                    onHomeActions(HomeActions.ReinstallAll)
                                }
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "</> Edit Targets",
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .padding(5.dp)
                                    )

                                    Text(
                                        text = "Add/Edit Targets for tricky store",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .padding(horizontal = 5.dp)
                                            .padding(bottom = 5.dp)
                                    )
                                }
                            }
                        }*/

            }

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
    gap: Float = 0.5f,
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

}
