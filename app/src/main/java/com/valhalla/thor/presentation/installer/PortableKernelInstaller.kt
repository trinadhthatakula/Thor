// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.presentation.installer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.data.kernel.KernelFlashController
import com.valhalla.thor.data.kernel.KernelFlashPhase
import com.valhalla.thor.domain.model.PortablePackage
import com.valhalla.thor.presentation.kernel.KernelFlasherScreen
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

/** Shares the controller, confirmation, slot handling and terminal with the Extensions flasher. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortableKernelInstaller(
    input: PortablePackage.Kernel,
    onDismiss: () -> Unit,
    viewModel: PortableKernelViewModel = koinViewModel(),
) {
    val controller = koinInject<KernelFlashController>()
    val state by controller.state.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    LaunchedEffect(input) { viewModel.prepare(input) }
    val writing = state.phase == KernelFlashPhase.STARTING || state.phase == KernelFlashPhase.FLASHING
    val dismissAllowed = rememberUpdatedState(imported != true || !writing)
    val confirmValueChange = remember<(SheetValue) -> Boolean> {
        { next -> next != SheetValue.Hidden || dismissAllowed.value }
    }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = confirmValueChange,
    )
    ModalBottomSheet(
        onDismissRequest = { if (dismissAllowed.value) onDismiss() },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        when (imported) {
            true -> KernelFlasherScreen(
                onBack = { if (dismissAllowed.value) onDismiss() },
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
                titleRes = R.string.bolt,
            )
            else -> Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.bolt), style = MaterialTheme.typography.titleLarge)
                if (imported == null) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.kernel_preparing))
                } else {
                    Text(stringResource(R.string.kernel_shared_blocked))
                    Button(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}
