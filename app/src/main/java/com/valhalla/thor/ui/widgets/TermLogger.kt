package com.valhalla.thor.ui.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
        scrimColor = Color.Companion.Black.copy(alpha = 0.6f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.Companion.fillMaxWidth(),
            horizontalAlignment = Alignment.Companion.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.Companion.CenterVertically) {
                if (!canExit)
                    AnimateLottieRaw(
                        resId = R.raw.rearrange,
                        shouldLoop = true,
                        modifier = Modifier.Companion
                            .size(50.dp),
                        contentScale = ContentScale.Companion.Crop
                    )
                Text(if (!canExit) "Reinstalling..," else "")
            }
            LazyColumn(modifier = Modifier.Companion.padding(10.dp)) {
                items(logObserver) { logTxt ->
                    Column(modifier = Modifier.Companion.fillMaxWidth()) {
                        Text(
                            text = logTxt,
                            softWrap = false,
                            modifier = Modifier.Companion.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = firaMonoFontFamily
                            ),
                            maxLines = 10,
                            textAlign = TextAlign.Companion.Start
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