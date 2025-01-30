package com.valhalla.thor.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.model.KeyEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

@Composable
fun KBoxVerificationScreen(modifier: Modifier = Modifier) {

    var jsonEntries by remember {
        mutableStateOf(emptyList<KeyEntry>())
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            URL("https://android.googleapis.com/attestation/status").readText().let {
                JSONObject(it).getJSONObject("entries").let { entries ->
                    val tempList = mutableListOf<KeyEntry>()
                    entries.keys().forEach {
                        tempList.add(
                            KeyEntry(
                                cert = it,
                                status = entries.getJSONObject(it).getString("status"),
                                reason = entries.getJSONObject(it).getString("reason")
                            )
                        )
                    }
                    jsonEntries = tempList
                }
            }
        }
    }

    Column(modifier) {

        var filteredKeyEntries by remember {
            mutableStateOf(jsonEntries)
        }

        val searchTerm = rememberTextFieldState("")
        searchTerm.edit {
            filteredKeyEntries = if (length > 0) jsonEntries.filter {
                it.cert.contains(this.toString())
            }
            else jsonEntries
        }

        Card(
            modifier = Modifier
                .padding(5.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(50)
        ) {
            BasicTextField(
                searchTerm,
                decorator = { tf ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {}) {
                            Icon(
                                painterResource(R.drawable.round_search),
                                "Search Icon",
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (searchTerm.text.isEmpty()) {
                                Text(
                                    "Search any Key",
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                )
                            }
                            tf()
                        }
                        if (searchTerm.text.isNotEmpty())
                            IconButton(
                                onClick = {
                                    searchTerm.clearText()
                                }
                            ) {
                                Icon(
                                    painterResource(R.drawable.round_close),
                                    "clear"
                                )
                            }
                    }
                },
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = KeyboardType.Number,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Search
                ),
                onKeyboardAction = {

                }
            )
        }

        LazyColumn {
            items(filteredKeyEntries) { entry ->
                ListItem(
                    headlineContent = {
                        Text(entry.cert)
                    },
                    supportingContent = {
                        Text("${entry.status}, ${entry.reason}")
                    }
                )
            }
        }

    }
}