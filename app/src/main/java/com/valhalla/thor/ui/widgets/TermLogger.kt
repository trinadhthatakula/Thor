package com.valhalla.thor.ui.widgets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.ui.theme.firaMonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
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
        modifier = modifier
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
            LazyColumn(modifier = Modifier.padding(10.dp).horizontalScroll(rememberScrollState())) {
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