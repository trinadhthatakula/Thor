package com.valhalla.thor.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.valhalla.thor.R
import com.valhalla.thor.ui.home.HomeUiState
import com.valhalla.thor.ui.theme.firaMonoFontFamily
import kotlinx.serialization.Serializable

@Suppress("unused")
@Composable
fun TermLogger(
    modifier: Modifier = Modifier,
    title: String = "Reinstalling..,",
    canExit: Boolean = false,
    logObserver: List<String>,
    doneReinstalling: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = {
            if (canExit) {
                doneReinstalling()
            }
        },
        scrimColor = Color.Black.copy(alpha = 0.6f),
        modifier = modifier,
        sheetGesturesEnabled = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!canExit)
                    AnimateLottieRaw(
                        resId = R.raw.rearrange,
                        shouldLoop = true,
                        modifier = Modifier
                            .size(50.dp),
                        contentScale = ContentScale.Crop
                    )
                Text(if (!canExit) title else "")
            }
            LazyColumn(
                modifier = Modifier
                    .padding(10.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                items(logObserver) { logTxt ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "* $logTxt",
                            softWrap = false,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = firaMonoFontFamily
                            ),
                            maxLines = 10,
                            textAlign = TextAlign.Start
                        )
                    }

                }
            }

            if (canExit)
                Button(
                    onClick = {
                        doneReinstalling()
                    }
                ) {
                    Text("Close")
                }
        }
    }
}

@Serializable
data class CustomAction(
    val icon: Int,
    val action: String
)

@Composable
fun TermLoggerDialog(
    modifier: Modifier = Modifier,
    uiState: HomeUiState,
    customAction: CustomAction? = null,
    onCustomActionClicked: (CustomAction) -> Unit = {},
    onTerminate: () -> Unit = {},
    done: () -> Unit
) {
    Dialog(
        onDismissRequest = {
            if (uiState.exitPermitted) {
                done()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
            Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom){
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(topEnd = 20.dp, topStart = 20.dp)
                        )
                        .padding(10.dp).padding(bottom = 50.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (!uiState.exitPermitted)
                            AnimateLottieRaw(
                                resId = R.raw.rearrange,
                                shouldLoop = true,
                                modifier = Modifier
                                    .size(50.dp),
                                contentScale = ContentScale.Crop
                            )
                        Text(
                            if (!uiState.exitPermitted) uiState.termLoggerTitle else "",
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                        if(customAction!=null){
                            IconButton(
                                onClick = {
                                    onCustomActionClicked(customAction)
                                }
                            ) {
                                Icon(
                                    painterResource(customAction.icon),
                                    customAction.action,
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                        if(uiState.showTerminate){
                            IconButton(
                                onClick = {
                                    onTerminate()
                                }
                            ) {
                                Icon(
                                    painterResource(R.drawable.danger),
                                    "terminate process",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                        if(uiState.exitPermitted){
                            IconButton(
                                onClick = {
                                    done()
                                }
                            ) {
                                Icon(
                                    painterResource(R.drawable.force_close),
                                    "terminate process",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }

                    val lazyListState = rememberLazyListState()
                    LaunchedEffect(uiState.logObserver) {
                        lazyListState.animateScrollToItem(uiState.logObserver.size)
                    }
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .padding(10.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        items(uiState.logObserver) { logTxt ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "* $logTxt",
                                    softWrap = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = firaMonoFontFamily
                                    ),
                                    maxLines = 10,
                                    textAlign = TextAlign.Start,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }

                        }
                    }

                    if (uiState.exitPermitted)
                        Button(
                            onClick = {
                                done()
                            }
                        ) {
                            Text("Close")
                        }
                }
            }
        }
}