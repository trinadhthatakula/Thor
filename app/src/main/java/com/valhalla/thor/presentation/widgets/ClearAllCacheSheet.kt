// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.presentation.main.CacheClearState
import kotlinx.coroutines.delay

/** How long the result stays up before it takes itself away. */
private const val RESULT_AUTO_DISMISS_MS = 3_000L

/**
 * The whole-device cache clear, all three of its states in one sheet.
 *
 * One composable rather than three because the three states are one conversation: the user is asked,
 * watches it happen, and is told what it freed, without the surface under their thumb moving. A
 * separate sheet per state would animate out and back in twice for a single tap.
 *
 * The confirmation is not decoration. `pm trim-caches` hands the choice of victim to
 * `PackageManagerService`, which evicts by LRU across the volume — so *system* apps are included and
 * there is no way to ask for anything narrower. This sheet is the only place the user is told that,
 * which is why [CacheClearState.Confirming] is a state of the operation rather than a boolean owned
 * by whichever screen happens to host the tile.
 *
 * [CacheClearState.Done] auto-dismisses after three seconds. That is a number the user asked for and
 * cannot act on, so leaving it to be dismissed by hand makes every clear cost two taps; the buttons
 * and the swipe still work, and whichever happens first wins.
 */
@Composable
fun ClearAllCacheSheet(
    state: CacheClearState,
    formattedFreedBytes: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is CacheClearState.Done) {
        // Keyed on the state instance: a second clear started after this one auto-dismissed gets its
        // own timer rather than inheriting a cancelled one.
        LaunchedEffect(state) {
            delay(RESULT_AUTO_DISMISS_MS)
            onDismiss()
        }
    }

    // Swallowing the dismiss *request* is only half the guard: it stops the host being told, but the
    // sheet still settles to Hidden on a swipe or a scrim tap and sits there invisible until the
    // clear finishes, taking the byte count with it. Vetoing the value change is the other half — it
    // stops the settle itself. Both are needed: ModalBottomSheet's back/scrim path calls
    // `onDismissRequest()` once `hide()` returns whether the veto fired or not.
    val running = rememberUpdatedState(state is CacheClearState.Running)
    // Deliberately created once. `rememberBottomSheetState` passes this straight into
    // `rememberSaveable` as a *key*, so a lambda that changed identity between recompositions would
    // discard the live SheetState and snap the sheet shut mid-operation. Reading `running.value`
    // from inside keeps the lambda identity-stable while still seeing the current state.
    val confirmValueChange = remember<(SheetValue) -> Boolean> {
        { value -> value != SheetValue.Hidden || !running.value }
    }

    ModalBottomSheet(
        // The clear is already in flight and cannot be called back, so a sheet that vanishes
        // mid-operation would either have to reappear when the result lands — reopening something the
        // user just closed — or drop the byte count they tapped the tile to see.
        onDismissRequest = if (state is CacheClearState.Running) ({ }) else onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Expanded, SheetValue.Hidden),
            confirmValueChange = confirmValueChange
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(14.dp)
            ) {
                if (state is CacheClearState.Running) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                } else {
                    Icon(
                        painter = painterResource(
                            if (state is CacheClearState.Confirming) R.drawable.warning
                            else R.drawable.clear_all
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Text(
                text = stringResource(
                    when (state) {
                        CacheClearState.Confirming -> R.string.clear_all_cache
                        CacheClearState.Running -> R.string.clear_all_cache_running
                        is CacheClearState.Done -> R.string.clear_all_cache_done_title
                    }
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = when (state) {
                    CacheClearState.Confirming -> stringResource(R.string.clear_all_cache_confirm_desc)
                    CacheClearState.Running -> stringResource(R.string.clear_all_cache_running_desc)
                    // Three outcomes, not two, because a measured zero and an absent measurement are
                    // different facts and the repository keeps them apart all the way to here.
                    // `formattedFreedBytes == null` is the absent one — the clear worked and Thor
                    // could not weigh it — and saying "0 B" there would report a missing usage-access
                    // grant as a clear that did nothing. A real zero is the opposite: the measurement
                    // worked, so the sheet must not send the user off to grant a permission they
                    // already hold. Negative deltas never arrive; SystemRepositoryImpl clamps them to
                    // null, since cache an app rebuilt mid-clear is an unmeasurable, not a zero.
                    is CacheClearState.Done -> when {
                        formattedFreedBytes == null ->
                            stringResource(R.string.clear_all_cache_done_unmeasured)
                        state.freedBytes == 0L ->
                            stringResource(R.string.clear_all_cache_done_nothing)
                        else ->
                            stringResource(R.string.clear_all_cache_done_size, formattedFreedBytes)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            if (state !is CacheClearState.Running) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    if (state is CacheClearState.Confirming) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(onClick = onConfirm) {
                            Text(stringResource(R.string.proceed))
                        }
                    } else {
                        Button(onClick = onDismiss) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
            }
        }
    }
}
