// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.thor.R

data class SupportAction(
    val iconRes: Int,
    val title: String,
    val description: String,
    val onClick: () -> Unit
)

/** A labelled group of support actions, shown under its own tab in [SupportDeveloperTabbedBottomSheet]. */
data class SupportTab(
    val label: String,
    val actions: List<SupportAction>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportDeveloperBottomSheet(
    actions: List<SupportAction>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 48.dp)
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.support_developer),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = stringResource(R.string.support_developer_desc_detailed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            actions.forEachIndexed { index, action ->
                if (index > 0) {
                    Spacer(Modifier.height(12.dp))
                }
                SupportActionRow(action = action)
            }
        }
    }
}

/**
 * Variant of [SupportDeveloperBottomSheet] that groups actions under switchable tabs (e.g. the store
 * build offers "Play Store" subscriptions and "Direct" Patreon/PayPal options).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportDeveloperTabbedBottomSheet(
    tabs: List<SupportTab>,
    onDismiss: () -> Unit,
    initialTab: Int = 0
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        var selectedTab by remember {
            mutableIntStateOf(initialTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
        }
        val scrollState = rememberScrollState()

        // Reset to the top when switching tabs so a shorter tab doesn't open scrolled past its actions.
        LaunchedEffect(selectedTab) {
            scrollState.scrollTo(0)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(bottom = 48.dp)
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.support_developer),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = stringResource(R.string.support_developer_desc_detailed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (tabs.size > 1) {
                ConnectedButtonGroup(
                    items = tabs.map { ConnectedButtonGroupItem.Label(it.label) },
                    selectedIndex = selectedTab,
                    onItemSelected = { selectedTab = it },
                    modifier = Modifier.width(IntrinsicSize.Max).padding(bottom = 16.dp)
                )
            }

            tabs.getOrNull(selectedTab)?.actions.orEmpty().forEachIndexed { index, action ->
                if (index > 0) {
                    Spacer(Modifier.height(12.dp))
                }
                SupportActionRow(action = action)
            }
        }
    }
}

@Composable
private fun SupportActionRow(action: SupportAction) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .clickable { action.onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(action.iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = action.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
