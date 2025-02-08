package com.valhalla.thor.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.getTokenResponse
import com.valhalla.thor.model.getVerdict
import com.valhalla.thor.model.initStandardIntegrityProvider
import com.valhalla.thor.model.parseIntegrityIcon
import com.valhalla.thor.model.parseIntegrityStatus
import com.valhalla.thor.model.rootAvailable
import com.valhalla.thor.ui.widgets.TypeWriterText


sealed interface HomeActions {
    data class ShowToast(val text: String, val longDuration: Boolean = false) : HomeActions
    data object FrozenApps : HomeActions
    data object ActiveApps : HomeActions
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

    var usedTokens by remember { mutableStateOf(emptyList<String>()) }

    var tokenString by remember { mutableStateOf("") }
    var deviceIntegrityJson by rememberSaveable { mutableStateOf("") }
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
                    }
                    usedTokens += tokenString
                    integrityStatus = parseIntegrityStatus(deviceIntegrityJson)
                    integrityIcon = parseIntegrityIcon(deviceIntegrityJson)
                }
            }
        }
    }

    Scaffold {
        HomeContent(
            Modifier.padding(it),
            userAppList,
            systemAppList,
            integrityStatus,
            integrityIcon
        ) { homeAction ->
            if (homeAction is HomeActions.ShowToast) {
                Toast.makeText(context, integrityStatus, Toast.LENGTH_SHORT).show()
            } else onHomeActions(homeAction)
        }
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
    onHomeActions: (HomeActions) -> Unit = {}
) {

    Column(modifier) {
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
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(10.dp),
                tooltip = {
                    PlainTooltip {
                        Text(integrityStatus)
                    }
                },
                state = rememberTooltipState()
            ) {
                IconButton(
                    onClick = {
                        onHomeActions(HomeActions.ShowToast(integrityStatus))
                    }
                ) {
                    Icon(
                        painterResource(integrityIcon),
                        "Integrity Indicator"
                    )
                }
            }
        }

        if(rootAvailable()){
            Row(
                modifier = Modifier.padding(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = {}
                ) {
                    Icon(
                        painterResource(R.drawable.magisk_icon),
                        "Root Available",
                        modifier = Modifier.padding(5.dp)
                    )
                }

                Text("Root Access Available")
            }
        }

        Text(
            "Installed Apps:",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .padding(top = 5.dp)
        )


    }
}
