// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.valhalla.thor.R

/** Input only: the host list already presents results and retains its own Back/keyboard behavior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThorSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val input = remember { TextFieldState(query) }
    val reportQuery by rememberUpdatedState(onQueryChange)
    LaunchedEffect(query) {
        if (input.text.toString() != query) input.setTextAndPlaceCursorAtEnd(query)
    }
    LaunchedEffect(input) {
        snapshotFlow { input.text.toString() }.collect { reportQuery(it) }
    }
    SearchBarDefaults.InputField(
        textFieldState = input,
        searchBarState = rememberSearchBarState(),
        onSearch = { onSearch() },
        modifier = modifier,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(painterResource(R.drawable.round_search), contentDescription = null) },
        trailingIcon = {
            if (input.text.isNotEmpty()) IconButton(onClick = { input.edit { replace(0, length, "") } }) {
                Icon(painterResource(R.drawable.round_close), stringResource(R.string.cd_clear))
            }
        },
    )
}
