package com.valhalla.thor.ui.screens

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.model.getTokenResponse
import com.valhalla.thor.model.getVerdict
import com.valhalla.thor.model.initIntegrityManager
import com.valhalla.thor.model.initStandardIntegrityProvider
import com.valhalla.thor.ui.theme.firaMonoFontFamily
import com.valhalla.thor.ui.widgets.TypeWriterText

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var tokenString by remember {
        mutableStateOf("")
    }
    var jsonString by remember {
        mutableStateOf("")
    }

    LaunchedEffect(Unit) {
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
        if (tokenString.isNotEmpty()) {
            getTokenResponse(tokenString) { jsonResult ->
                if (jsonResult.isSuccess) {
                    jsonResult.getOrNull()?.let {
                        Log.d("HomeScreen", "HomeScreen: token is $tokenString")
                        jsonString = it
                    }
                }
            }
        }
    }

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
        }
        Text(
            text = jsonString,
            softWrap = false,
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = firaMonoFontFamily
            ),
            textAlign = TextAlign.Start
        )
    }
}