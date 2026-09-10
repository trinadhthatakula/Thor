// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.MultiAppAction
import java.util.UUID

/**
 * Consent belongs to this particular selection, not whichever apps are selected after a refresh.
 * Keep only identities and the freeze-tier inputs in saved state; APK paths and other scan metadata
 * can make a large selection exceed Android's saved-instance-state transaction limit.
 */
internal data class BulkFreezeConfirmationRequest(
    val id: String,
    val action: MultiAppAction.Freeze,
) {
    companion object {
        fun from(action: MultiAppAction.Freeze) = BulkFreezeConfirmationRequest(
            id = UUID.randomUUID().toString(),
            action = action.copy(appList = action.appList.toList()),
        )

        val Saver = mapSaver<BulkFreezeConfirmationRequest?>(
            save = { request ->
                if (request == null) emptyMap() else mapOf(
                    "id" to request.id,
                    "useSuspend" to request.action.useSuspend,
                    "packages" to ArrayList(request.action.appList.map { it.packageName }),
                    "system" to request.action.appList.map { it.isSystem }.toBooleanArray(),
                    "recommendations" to ArrayList(request.action.appList.map { it.bloatRecommendation }),
                    "uadLoadFailed" to request.action.appList.map { it.isUadLoadFailed }.toBooleanArray(),
                )
            },
            restore = { saved ->
                if (saved.isEmpty()) null else {
                    val packages = saved.getValue("packages") as List<*>
                    val system = saved.getValue("system") as BooleanArray
                    val recommendations = saved.getValue("recommendations") as List<*>
                    val uadLoadFailed = saved.getValue("uadLoadFailed") as BooleanArray
                    BulkFreezeConfirmationRequest(
                        id = saved.getValue("id") as String,
                        action = MultiAppAction.Freeze(
                            appList = packages.mapIndexed { index, packageName ->
                                AppInfo(
                                    packageName = packageName as String,
                                    isSystem = system[index],
                                    bloatRecommendation = recommendations[index] as String?,
                                    isUadLoadFailed = uadLoadFailed[index],
                                )
                            },
                            useSuspend = saved.getValue("useSuspend") as Boolean,
                        ),
                    )
                }
            },
        )
    }
}

/** App-list-only freeze consent. The checkbox controls tracking, never whether freezing occurs. */
@Composable
internal fun BulkFreezeConfirmationDialog(
    appCount: Int,
    requestKey: String,
    onConfirm: (addToFreezer: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Rotation keeps an explicit opt-out; another selection/request always starts checked again.
    var addToFreezer by rememberSaveable(requestKey) { mutableStateOf(true) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(48.dp),
        title = {
            Text(
                text = stringResource(R.string.action_freeze),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
        },
        text = {
            // The body scrolls independently so the actions remain reachable with large text.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = pluralStringResource(R.plurals.bulk_freeze_confirm_desc, appCount, appCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = addToFreezer,
                            role = Role.Checkbox,
                            onValueChange = { addToFreezer = it },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // One accessible checkbox target includes both label and explanation.
                    Checkbox(checked = addToFreezer, onCheckedChange = null)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.add_to_freezer),
                            // Use the allocated column width for both paragraph and semantic bounds.
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.bulk_freeze_add_to_freezer_desc),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(addToFreezer) }, enabled = appCount > 0) {
                Text(stringResource(R.string.action_freeze), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
