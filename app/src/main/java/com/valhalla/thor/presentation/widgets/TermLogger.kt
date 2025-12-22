package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.valhalla.thor.R
import com.valhalla.thor.presentation.theme.firaMonoFontFamily

@Composable
fun TermLoggerDialog(
    modifier: Modifier = Modifier,
    title: String,
    logs: List<String>,
    isOperationComplete: Boolean,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = {
            // Only allow dismiss if operation is done
            if (isOperationComplete) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.background,
                        shape = RoundedCornerShape(topEnd = 20.dp, topStart = 20.dp)
                    )
                    .padding(10.dp)
                    .padding(bottom = 50.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!isOperationComplete) {
                        AnimateLottieRaw(
                            resId = R.raw.rearrange,
                            shouldLoop = true,
                            modifier = Modifier.size(50.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            painterResource(R.drawable.check_circle),
                            contentDescription = "Done",
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        text = if (isOperationComplete) "Done" else title,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    )

                    if (isOperationComplete) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                painterResource(R.drawable.round_close),
                                "Close",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }

                // Logs List
                val lazyListState = rememberLazyListState()
                LaunchedEffect(logs.size) {
                    if (logs.isNotEmpty()) {
                        lazyListState.animateScrollToItem(logs.lastIndex)
                    }
                }

                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    items(logs) { logTxt ->
                        Text(
                            text = "> $logTxt",
                            softWrap = false,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = firaMonoFontFamily
                            ),
                            maxLines = 1,
                            textAlign = TextAlign.Start,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                if (isOperationComplete) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .fillMaxWidth()
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}