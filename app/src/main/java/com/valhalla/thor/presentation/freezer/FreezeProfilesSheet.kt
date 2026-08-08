// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.BulkScope
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.killableMembers

/**
 * The freeze-profiles list: named sets of apps the user can freeze or unfreeze in one tap.
 *
 * Profiles are deliberately *not* a view onto the freezer watchlist — see [FreezeProfile] — so
 * this sheet never edits watchlist membership. Running one goes through `BulkFreezeRunner`,
 * which is where the tier gate is applied to a list; nothing here freezes anything itself.
 *
 * Four verbs, chosen per tap and never stored on the profile: freeze and unfreeze on the row, and
 * suspend and force-stop in the overflow. [onRun]'s `mode` is what separates freeze from suspend —
 * null means "however this user has said they want apps frozen", and only the explicit Suspend
 * names one. [onKill] does not go through the runner at all; it hands the resolved app list to the
 * host, which routes it into the same confirm-and-log flow every other force-stop uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezeProfilesSheet(
    profiles: List<FreezeProfile>,
    allApps: List<AppInfo>,
    runningRequests: List<BulkRequest>,
    hasPrivilege: Boolean,
    onRun: (profileId: Long, op: BulkOp, mode: FreezerMode?) -> Unit,
    onKill: (List<AppInfo>) -> Unit,
    onCreate: () -> Unit,
    onEdit: (FreezeProfile) -> Unit,
    onDelete: (profileId: Long) -> Unit,
    onDismiss: () -> Unit
) {
    // Deletion is the one irreversible action here — a profile carries a name and a hand-picked
    // list that nothing else in the app can reconstruct. Plain remember: a confirmation that
    // survived process death would outlive the row it was raised from.
    var pendingDelete by remember { mutableStateOf<FreezeProfile?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.freeze_profiles),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(onClick = onCreate, shape = RoundedCornerShape(16.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.profile_new))
            }
        }

        if (profiles.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.list_alt),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.no_profiles_yet),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.no_profiles_yet_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    FreezeProfileRow(
                        profile = profile,
                        allApps = allApps,
                        // Row-specific, not a global "something is running": two profiles are
                        // serialized rather than coalesced, so tapping the second must not paint
                        // the first one's spinner onto it. Both rows do spin while both are in
                        // flight, which is the truth — the second is queued, not ignored.
                        isRunning = runningRequests.any { it.scope == BulkScope.Profile(profile.id) },
                        hasPrivilege = hasPrivilege,
                        onRun = { op, mode -> onRun(profile.id, op, mode) },
                        onKill = onKill,
                        onEdit = { onEdit(profile) },
                        onDelete = { pendingDelete = profile }
                    )
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.profile_delete_title, profile.name)) },
            // Says what deleting does NOT do, because that is the part a user is entitled to
            // worry about: the apps stay exactly as they are, frozen ones included.
            text = { Text(stringResource(R.string.profile_delete_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(profile.id)
                        pendingDelete = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun FreezeProfileRow(
    profile: FreezeProfile,
    allApps: List<AppInfo>,
    isRunning: Boolean,
    hasPrivilege: Boolean,
    onRun: (BulkOp, FreezerMode?) -> Unit,
    onKill: (List<AppInfo>) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Resolved here rather than in the host so the menu item can be disabled on the real answer.
    // "Force stop 0 apps" over a profile whose members are all frozen or uninstalled is a
    // confirmation dialog for nothing, and the user has no way to see why from the row.
    val killable = remember(profile.packageNames, allApps) {
        killableMembers(profile.packageNames, allApps)
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.profile_app_count,
                        profile.size,
                        profile.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isRunning) {
                // Replaces both action buttons rather than sitting beside them: while this
                // profile is mid-run, a second tap would coalesce into the same run anyway, and
                // the opposite op would cancel it. Removing the affordance says so plainly.
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                IconButton(
                    // No mode: the button says "freeze", and what freezing means is the user's
                    // standing choice. Only the explicit Suspend below overrides it.
                    onClick = { onRun(BulkOp.FREEZE, null) },
                    enabled = hasPrivilege && profile.size > 0
                ) {
                    Icon(
                        painter = painterResource(R.drawable.frozen),
                        contentDescription = stringResource(R.string.action_freeze),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = { onRun(BulkOp.UNFREEZE, null) },
                    enabled = hasPrivilege && profile.size > 0
                ) {
                    Icon(
                        painter = painterResource(R.drawable.unfreeze),
                        contentDescription = stringResource(R.string.action_unfreeze),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.more_options)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // The other two verbs the row was asked for. In the menu rather than on the
                    // row because they are the rarer half of the pair each sits beside — a fifth
                    // and sixth icon button would push the profile name into an ellipsis.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_suspend)) },
                        enabled = hasPrivilege && profile.size > 0 && !isRunning,
                        onClick = {
                            menuOpen = false
                            onRun(BulkOp.FREEZE, FreezerMode.SUSPEND)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_force_stop)) },
                        // Not gated on isRunning: a force-stop is orthogonal to a freeze rather
                        // than a competing direction, so it neither coalesces with one nor
                        // cancels it. Gated on the resolved list instead, which is what makes
                        // "there is nothing running to stop" visible before the tap.
                        enabled = hasPrivilege && killable.isNotEmpty(),
                        onClick = {
                            menuOpen = false
                            onKill(killable)
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
