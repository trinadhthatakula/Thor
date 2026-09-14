// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.common

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.presentation.settings.SupportDeveloperHelper
import com.valhalla.thor.presentation.settings.SupportPromptCoordinator
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Only the operation's success branch may opt a message into a support invitation. */
data class OperationMessage(val message: UiText, val isSuccess: Boolean = false)

/** The active modal owns feedback while it covers the screen's ordinary snackbar host. */
class OperationFeedbackTarget {
    var current: OperationFeedbackState? by mutableStateOf(null)
}

val LocalOperationFeedbackTarget = staticCompositionLocalOf<OperationFeedbackTarget?> { null }

class OperationFeedbackState internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    internal val coordinator: SupportPromptCoordinator,
    private val supportLabel: String,
    private val target: OperationFeedbackTarget?,
) {
    internal val snackbar = SnackbarHostState()
    internal var showSupport by mutableStateOf(false)
    private var feedbackJob: Job? = null

    fun show(message: OperationMessage, allowSupport: Boolean = true) {
        feedbackJob?.cancel()
        snackbar.currentSnackbarData?.dismiss()
        val activeTarget = target?.current
        if (activeTarget != null && activeTarget !== this) {
            activeTarget.show(message, allowSupport)
            return
        }
        val text = message.message.asString(context)
        if (!message.isSuccess || !allowSupport || !coordinator.state.value.canInvite) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            return
        }
        feedbackJob = scope.launch {
            val result = snackbar.showSnackbar(
                message = text,
                actionLabel = supportLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && coordinator.state.value.canInvite) {
                showSupport = true
            }
        }
    }
}

@Composable
fun rememberOperationFeedback(): OperationFeedbackState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coordinator = koinInject<SupportPromptCoordinator>()
    val supportLabel = stringResource(R.string.support_thor)
    val target = LocalOperationFeedbackTarget.current
    return remember(context, scope, coordinator, supportLabel, target) {
        OperationFeedbackState(context, scope, coordinator, supportLabel, target)
    }
}

/** [enabled] reserves the surface for a recovery prompt or another primary action. */
@Composable
fun OperationFeedbackHost(
    state: OperationFeedbackState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val eligibility by state.coordinator.state.collectAsStateWithLifecycle()
    LaunchedEffect(enabled, eligibility.canInvite) {
        if (!enabled || !eligibility.canInvite) state.snackbar.currentSnackbarData?.dismiss()
    }
    if (enabled) SnackbarHost(state.snackbar, modifier)
    if (state.showSupport) {
        SupportDeveloperHelper(onDismiss = { state.showSupport = false })
    }
}
