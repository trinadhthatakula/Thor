// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.valhalla.thor.R
import com.valhalla.thor.data.backup.job.RestoreSourceGrantHolder
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.repository.TaskActionController
import com.valhalla.thor.domain.repository.TaskActionDispatch
import com.valhalla.thor.domain.repository.TaskUiRoute
import com.valhalla.thor.presentation.launcher.ShareHandoffActivity
import com.valhalla.thor.util.Logger
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal data class TaskActionRouteActivation(
    val route: TaskUiRoute,
    val generation: Long,
)

internal enum class TaskExternalResultKind {
    ACTION_SUBMISSION,
    RESTORE_SOURCE,
    PRIVILEGE_MANAGER,
    SHIZUKU_PERMISSION,
    DHIZUKU_PERMISSION,
}

/**
 * Saveable foreground route state. Repeating the same route creates a new generation so an older
 * picker, manager, or controller completion cannot settle the replacement.
 */
@Stable
internal class TaskActionRouteState(
    initialNextGeneration: Long = 0L,
    initialActive: TaskActionRouteActivation? = null,
    initialArmedResult: Pair<Long, TaskExternalResultKind>? = null,
    private val ownerId: String = UUID.randomUUID().toString(),
) {
    var active by mutableStateOf(initialActive)
        private set

    private var nextGeneration = initialNextGeneration
    private var armedResult = initialArmedResult
    private var claimedResult: Pair<Long, TaskExternalResultKind>? = null

    fun activate(route: TaskUiRoute): TaskActionRouteActivation {
        check(nextGeneration < Long.MAX_VALUE) { "Task action route generation exhausted" }
        val activation = TaskActionRouteActivation(route, ++nextGeneration)
        armedResult = null
        claimedResult = null
        active = activation
        return activation
    }

    fun arm(
        activation: TaskActionRouteActivation,
        kind: TaskExternalResultKind,
    ): Boolean {
        if (!isCurrent(activation) || armedResult != null) return false
        armedResult = activation.generation to kind
        return true
    }

    fun disarm(
        activation: TaskActionRouteActivation,
        kind: TaskExternalResultKind,
    ) {
        val owner = activation.generation to kind
        if (armedResult == owner) armedResult = null
        if (claimedResult == owner) claimedResult = null
    }

    fun isArmed(
        activation: TaskActionRouteActivation,
        kind: TaskExternalResultKind,
    ): Boolean = isCurrent(activation) && armedResult == activation.generation to kind

    fun claim(
        activation: TaskActionRouteActivation,
        kind: TaskExternalResultKind,
    ): TaskActionRouteActivation? {
        if (!isCurrent(activation)) return null
        if (armedResult != activation.generation to kind) return null
        armedResult = null
        return activation
    }

    fun claimReplayable(
        activation: TaskActionRouteActivation,
        kind: TaskExternalResultKind,
    ): TaskActionRouteActivation? {
        if (!isCurrent(activation)) return null
        val owner = activation.generation to kind
        if (armedResult != owner || claimedResult != null) return null
        claimedResult = owner
        return activation
    }

    fun releaseClaim(
        activation: TaskActionRouteActivation,
        kind: TaskExternalResultKind,
    ) {
        if (claimedResult == activation.generation to kind) claimedResult = null
    }

    fun complete(
        activation: TaskActionRouteActivation,
        dispatch: TaskActionDispatch,
    ) {
        if (!isCurrent(activation)) return
        when (dispatch) {
            is TaskActionDispatch.Route -> activate(dispatch.destination)
            TaskActionDispatch.Applied,
            is TaskActionDispatch.Rejected,
                -> dismiss(activation)
        }
    }

    fun dismiss(activation: TaskActionRouteActivation) {
        if (!isCurrent(activation)) return
        armedResult = null
        claimedResult = null
        active = null
    }

    fun isCurrent(activation: TaskActionRouteActivation): Boolean =
        active?.generation == activation.generation

    internal fun externalResultKey(activation: TaskActionRouteActivation): String =
        "${activation.route.taskId}:$ownerId:${activation.generation}"

    private fun TaskUiRoute.savedValues(): List<String> {
        val empty = List(5) { "" }
        return when (this) {
            is TaskUiRoute.AuthenticateArchive ->
                listOf(
                    "AUTHENTICATE_ARCHIVE",
                    taskId.toString(),
                    packageName,
                    kind.name
                ) + empty.drop(2)

            is TaskUiRoute.PickRestoreSource ->
                listOf("PICK_RESTORE_SOURCE", taskId.toString(), expectedPackageName) + empty.drop(1)

            is TaskUiRoute.ReviewInterruptedRestore -> listOf(
                "REVIEW_INTERRUPTED_RESTORE",
                taskId.toString(),
                breadcrumb.packageName,
                breadcrumb.appLabel,
                breadcrumb.startedAtEpochMs.toString(),
                "",
                "",
            )

            is TaskUiRoute.AuthorizePrivilege ->
                listOf("AUTHORIZE_PRIVILEGE", taskId.toString()) + empty

            is TaskUiRoute.ConfirmSweepRetry -> listOf(
                "CONFIRM_SWEEP_RETRY",
                taskId.toString(),
                packageName,
                operation.name,
                "",
                targetOrdinal.toString(),
                "",
            )

            is TaskUiRoute.SharePreparedOutputs -> listOf(
                "SHARE_PREPARED_OUTPUTS",
                taskId.toString(),
                "",
                "",
                "",
                "",
                outputIds.joinToString(","),
            )

            is TaskUiRoute.OpenNotificationSettings ->
                listOf("OPEN_NOTIFICATION_SETTINGS", taskId.toString(), channelId) + empty.drop(1)
        }
    }

    internal fun savedValues(): List<String> {
        val activation = active
        val routeValues = activation?.route?.savedValues() ?: EMPTY_ROUTE_VALUES
        val savedResult = armedResult?.takeUnless {
            it.second == TaskExternalResultKind.ACTION_SUBMISSION
        }
        return listOf(
            TASK_ACTION_ROUTE_STATE_VERSION,
            nextGeneration.toString(),
            (activation?.generation ?: 0L).toString(),
            (savedResult?.first ?: 0L).toString(),
            savedResult?.second?.name.orEmpty(),
        ) + routeValues + ownerId + TASK_ACTION_PROCESS_ID
    }
}

private val TaskActionRouteStateSaver = listSaver<TaskActionRouteState, String>(
    save = { it.savedValues() },
    restore = ::restoreTaskActionRouteState,
)

internal fun restoreTaskActionRouteState(values: List<String>): TaskActionRouteState? =
    runCatching {
        require(values.size == TASK_ACTION_ROUTE_STATE_VALUE_COUNT)
        require(values[0] == TASK_ACTION_ROUTE_STATE_VERSION)
        val nextGeneration = values[1].toLong()
        val activeGeneration = values[2].toLong()
        val armedGeneration = values[3].toLong()
        val armedKindName = values[4]
        val route = restoreTaskUiRoute(values.subList(5, 5 + TASK_ACTION_ROUTE_VALUE_COUNT))
        val ownerId = values[5 + TASK_ACTION_ROUTE_VALUE_COUNT].also(UUID::fromString)
        val activation = route?.let { TaskActionRouteActivation(it, activeGeneration) }
        require(nextGeneration >= 0L)
        require(
            (activation == null && activeGeneration == 0L) ||
                    (activation != null && activeGeneration in 1..nextGeneration)
        )
        val restoredArmed = when {
            armedGeneration == 0L && armedKindName.isEmpty() -> null
            activation != null && armedGeneration == activeGeneration ->
                armedGeneration to TaskExternalResultKind.valueOf(armedKindName)

            else -> error("Invalid saved task action result owner")
        }
        val armed = restoredArmed?.takeIf { (_, kind) ->
            when (kind) {
                TaskExternalResultKind.ACTION_SUBMISSION -> false
                TaskExternalResultKind.SHIZUKU_PERMISSION,
                TaskExternalResultKind.DHIZUKU_PERMISSION,
                    -> values.last() == TASK_ACTION_PROCESS_ID

                TaskExternalResultKind.RESTORE_SOURCE,
                TaskExternalResultKind.PRIVILEGE_MANAGER,
                    -> true
            }
        }
        TaskActionRouteState(nextGeneration, activation, armed, ownerId)
    }.getOrNull()

private fun restoreTaskUiRoute(values: List<String>): TaskUiRoute? {
    require(values.size == TASK_ACTION_ROUTE_VALUE_COUNT)
    if (values[0] == "NONE") return null
    val taskId = UUID.fromString(values[1])
    return when (values[0]) {
        "AUTHENTICATE_ARCHIVE" -> TaskUiRoute.AuthenticateArchive(
            taskId,
            values[2],
            DataTaskKind.valueOf(values[3]),
        )

        "PICK_RESTORE_SOURCE" -> TaskUiRoute.PickRestoreSource(taskId, values[2])
        "REVIEW_INTERRUPTED_RESTORE" -> TaskUiRoute.ReviewInterruptedRestore(
            taskId,
            RestoreMutationBreadcrumb(values[2], values[3], values[4].toLong()),
        )

        "AUTHORIZE_PRIVILEGE" -> TaskUiRoute.AuthorizePrivilege(taskId)
        "CONFIRM_SWEEP_RETRY" -> TaskUiRoute.ConfirmSweepRetry(
            taskId,
            values[5].toInt(),
            values[2],
            PrivilegeSweepOperation.valueOf(values[3]),
        )

        "SHARE_PREPARED_OUTPUTS" -> TaskUiRoute.SharePreparedOutputs(
            taskId,
            values[6].takeIf(String::isNotEmpty)
                ?.split(',')
                ?.map(UUID::fromString)
                .orEmpty(),
        )

        "OPEN_NOTIFICATION_SETTINGS" -> TaskUiRoute.OpenNotificationSettings(taskId, values[2])
        else -> error("Unknown saved task action route")
    }
}

private const val TASK_ACTION_ROUTE_STATE_VERSION = "3"
private const val TASK_ACTION_ROUTE_VALUE_COUNT = 7
private const val TASK_ACTION_ROUTE_STATE_VALUE_COUNT = 7 + TASK_ACTION_ROUTE_VALUE_COUNT
private val EMPTY_ROUTE_VALUES = listOf("NONE", "", "", "", "", "", "")
private val TASK_ACTION_PROCESS_ID = UUID.randomUUID().toString()

@Composable
internal fun rememberTaskActionRouteState(): TaskActionRouteState =
    rememberSaveable(saver = TaskActionRouteStateSaver) { TaskActionRouteState() }

/** Single foreground owner for every typed task action route. */
@Composable
internal fun TaskActionRouteHost(
    state: TaskActionRouteState,
    taskActionController: TaskActionController = koinInject(),
    restoreSourceGrants: RestoreSourceGrantHolder = koinInject(),
    privilegeActions: TaskPrivilegeActions = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun launchAction(
        activation: TaskActionRouteActivation,
        block: suspend () -> TaskActionDispatch,
    ) {
        scope.launchTaskAction(state, activation, block)
    }

    fun submitRestoreToken(
        claimed: TaskActionRouteActivation,
        tokenText: String,
    ) {
        val token = runCatching { UUID.fromString(tokenText) }.getOrNull()
        if (token == null) {
            restoreSourceGrants.drop(claimed.route.taskId, tokenText)
            state.dismiss(claimed)
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                state.complete(
                    claimed,
                    taskActionController.submitRestoreSource(claimed.route.taskId, token),
                )
            } catch (exception: CancellationException) {
                state.dismiss(claimed)
                throw exception
            } catch (exception: Exception) {
                Logger.e(TAG, "restore source action failed", exception)
                state.dismiss(claimed)
            }
        }
    }

    val activation = state.active ?: return

    when (val route = activation.route) {
        is TaskUiRoute.AuthenticateArchive -> ArchiveAuthenticationDialog(
            route = route,
            onDismiss = { state.dismiss(activation) },
            onSubmit = { passphrase ->
                if (!state.arm(activation, TaskExternalResultKind.ACTION_SUBMISSION)) {
                    passphrase.fill('\u0000')
                    return@ArchiveAuthenticationDialog
                }
                scope.launch {
                    try {
                        state.complete(
                            activation,
                            taskActionController.submitArchivePassphrase(route.taskId, passphrase),
                        )
                    } catch (exception: CancellationException) {
                        state.dismiss(activation)
                        throw exception
                    } catch (exception: Exception) {
                        Logger.e(TAG, "archive authentication action failed", exception)
                        state.dismiss(activation)
                    } finally {
                        passphrase.fill('\u0000')
                        state.disarm(activation, TaskExternalResultKind.ACTION_SUBMISSION)
                    }
                }
            },
        )

        is TaskUiRoute.PickRestoreSource -> key(activation.generation) {
            RestoreSourceRoute(
                activation = activation,
                state = state,
                restoreSourceGrants = restoreSourceGrants,
                submitRestoreToken = ::submitRestoreToken,
            )
        }

        is TaskUiRoute.ReviewInterruptedRestore -> ConfirmationDialog(
            title = stringResource(R.string.task_dialog_restore_review_title),
            message = stringResource(
                R.string.task_dialog_restore_review_message,
                route.breadcrumb.appLabel,
            ),
            confirmLabel = stringResource(R.string.task_dialog_restore_review_resume),
            onConfirm = {
                launchAction(activation) {
                    taskActionController.perform(route.taskId, TaskAction.RESUME)
                }
            },
            onDismiss = { state.dismiss(activation) },
        )

        is TaskUiRoute.AuthorizePrivilege -> key(activation.generation) {
            PrivilegeAuthorizationRoute(
                activation = activation,
                state = state,
                taskActionController = taskActionController,
                privilegeActions = privilegeActions,
            )
        }

        is TaskUiRoute.ConfirmSweepRetry -> ConfirmationDialog(
            title = stringResource(R.string.task_dialog_sweep_retry_title),
            message = stringResource(
                R.string.task_dialog_sweep_retry_message,
                route.packageName,
                stringResource(route.operation.labelRes()),
            ),
            confirmLabel = stringResource(R.string.task_dialog_sweep_retry_confirm),
            onConfirm = {
                launchAction(activation) {
                    taskActionController.authorizeSweepTargetRetry(
                        route.taskId,
                        route.targetOrdinal,
                    )
                }
            },
            onDismiss = { state.dismiss(activation) },
        )

        is TaskUiRoute.SharePreparedOutputs -> LaunchedExternalRoute(
            activation = activation,
            state = state,
        ) {
            context.startActivity(
                Intent(context, ShareHandoffActivity::class.java)
                    .putExtra(ShareHandoffActivity.EXTRA_TASK_ID, route.taskId.toString()),
            )
        }

        is TaskUiRoute.OpenNotificationSettings -> LaunchedExternalRoute(
            activation = activation,
            state = state,
        ) {
            context.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, route.channelId),
            )
        }
    }
}

@Composable
private fun RestoreSourceRoute(
    activation: TaskActionRouteActivation,
    state: TaskActionRouteState,
    restoreSourceGrants: RestoreSourceGrantHolder,
    submitRestoreToken: (TaskActionRouteActivation, String) -> Unit,
) {
    val route = activation.route as TaskUiRoute.PickRestoreSource
    val restoreSourceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        val claimed = state.claim(activation, TaskExternalResultKind.RESTORE_SOURCE)
            ?: return@rememberLauncherForActivityResult
        if (source == null) {
            state.dismiss(claimed)
            return@rememberLauncherForActivityResult
        }

        val tokenText = try {
            restoreSourceGrants.register(claimed.route.taskId, source)
        } catch (exception: Exception) {
            Logger.e(TAG, "restore source registration failed", exception)
            state.dismiss(claimed)
            return@rememberLauncherForActivityResult
        }
        submitRestoreToken(claimed, tokenText)
    }

    AlertDialog(
        onDismissRequest = { state.dismiss(activation) },
        title = { Text(stringResource(R.string.task_dialog_restore_source_title)) },
        text = {
            Text(
                stringResource(
                    R.string.task_dialog_restore_source_message,
                    route.expectedPackageName
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!state.arm(activation, TaskExternalResultKind.RESTORE_SOURCE)) {
                        return@TextButton
                    }
                    val authorizedToken = restoreSourceGrants.currentToken(route.taskId)
                    if (authorizedToken != null) {
                        val claimed = state.claim(
                            activation,
                            TaskExternalResultKind.RESTORE_SOURCE,
                        ) ?: return@TextButton
                        submitRestoreToken(claimed, authorizedToken)
                    } else {
                        runCatching { restoreSourceLauncher.launch(arrayOf("*/*")) }
                            .onFailure {
                                state.disarm(
                                    activation,
                                    TaskExternalResultKind.RESTORE_SOURCE,
                                )
                                Logger.e(TAG, "restore source picker unavailable", it)
                            }
                    }
                },
            ) {
                Text(stringResource(R.string.task_dialog_restore_source_select))
            }
        },
        dismissButton = {
            TextButton(onClick = { state.dismiss(activation) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun PrivilegeAuthorizationRoute(
    activation: TaskActionRouteActivation,
    state: TaskActionRouteState,
    taskActionController: TaskActionController,
    privilegeActions: TaskPrivilegeActions,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val route = activation.route as TaskUiRoute.AuthorizePrivilege
    val privilegeResultKey = state.externalResultKey(activation)
    var resumedResultScope by remember { mutableStateOf<CoroutineScope?>(null) }
    DisposableEffect(state, activation, privilegeActions, privilegeResultKey) {
        onDispose {
            if (!state.isCurrent(activation)) {
                privilegeActions.retireResultOwner(privilegeResultKey)
            }
        }
    }
    val finishPrivilegeResult: (TaskExternalResultKind, () -> Unit) -> Unit = remember(
        activation,
        state,
        taskActionController,
    ) {
        finish@{ kind, acknowledge ->
            val resultScope = resumedResultScope ?: return@finish
            resultScope.launch {
                val claimed = when (kind) {
                    TaskExternalResultKind.PRIVILEGE_MANAGER,
                    TaskExternalResultKind.SHIZUKU_PERMISSION,
                    TaskExternalResultKind.DHIZUKU_PERMISSION,
                        -> state.claimReplayable(activation, kind)

                    else -> state.claim(activation, kind)
                } ?: return@launch
                var settled = false
                try {
                    val dispatch =
                        taskActionController.privilegeAuthorizationReturned(claimed.route.taskId)
                    coroutineContext.ensureActive()
                    state.complete(claimed, dispatch)
                    settled = true
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    coroutineContext.ensureActive()
                    Logger.e(TAG, "privilege action failed", exception)
                    state.dismiss(claimed)
                    settled = true
                } finally {
                    if (settled) acknowledge() else state.releaseClaim(claimed, kind)
                }
            }
        }
    }
    val privilegeManagerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        finishPrivilegeResult(TaskExternalResultKind.PRIVILEGE_MANAGER) {}
    }
    val shizukuResultListener = remember(
        privilegeActions,
        privilegeResultKey,
        finishPrivilegeResult,
    ) {
        {
            finishPrivilegeResult(TaskExternalResultKind.SHIZUKU_PERMISSION) {
                privilegeActions.acknowledgeShizukuResult(privilegeResultKey)
            }
        }
    }
    val dhizukuResultListener = remember(
        privilegeActions,
        privilegeResultKey,
        finishPrivilegeResult,
    ) {
        {
            finishPrivilegeResult(TaskExternalResultKind.DHIZUKU_PERMISSION) {
                privilegeActions.acknowledgeDhizukuResult(privilegeResultKey)
            }
        }
    }

    LifecycleResumeEffect(
        activation.generation,
        privilegeActions,
        privilegeManagerLauncher,
        shizukuResultListener,
        dhizukuResultListener,
    ) {
        val resultJob = Job(scope.coroutineContext[Job])
        val resultScope = CoroutineScope(scope.coroutineContext + resultJob)
        resumedResultScope = resultScope
        privilegeActions.addShizukuResultListener(privilegeResultKey, shizukuResultListener)
        privilegeActions.addDhizukuResultListener(privilegeResultKey, dhizukuResultListener)
        resultScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (state.isArmed(activation, TaskExternalResultKind.PRIVILEGE_MANAGER)) {
                    finishPrivilegeResult(TaskExternalResultKind.PRIVILEGE_MANAGER) {}
                    return@launch
                }
                if (state.isArmed(activation, TaskExternalResultKind.DHIZUKU_PERMISSION)) {
                    if (!privilegeActions.startDhizukuRequest(context, privilegeResultKey)) {
                        state.disarm(activation, TaskExternalResultKind.DHIZUKU_PERMISSION)
                        state.dismiss(activation)
                    }
                    return@launch
                }
                when (val target = privilegeActions.resolveSetupTarget()) {
                    TaskPrivilegeActions.SetupTarget.RefreshOnly -> {
                        resultScope.launchTaskAction(state, activation) {
                            taskActionController.privilegeAuthorizationReturned(route.taskId)
                        }
                    }

                    TaskPrivilegeActions.SetupTarget.ShizukuPermission -> {
                        if (state.arm(activation, TaskExternalResultKind.SHIZUKU_PERMISSION) &&
                            !privilegeActions.requestShizuku(privilegeResultKey)
                        ) {
                            state.disarm(activation, TaskExternalResultKind.SHIZUKU_PERMISSION)
                            state.dismiss(activation)
                        }
                    }

                    TaskPrivilegeActions.SetupTarget.DhizukuPermission -> {
                        if (state.arm(activation, TaskExternalResultKind.DHIZUKU_PERMISSION) &&
                            !privilegeActions.startDhizukuRequest(context, privilegeResultKey)
                        ) {
                            state.disarm(activation, TaskExternalResultKind.DHIZUKU_PERMISSION)
                            state.dismiss(activation)
                        }
                    }

                    is TaskPrivilegeActions.SetupTarget.ManagerApp -> {
                        if (state.arm(activation, TaskExternalResultKind.PRIVILEGE_MANAGER)) {
                            runCatching { privilegeManagerLauncher.launch(target.intent) }
                                .onFailure {
                                    state.disarm(
                                        activation,
                                        TaskExternalResultKind.PRIVILEGE_MANAGER,
                                    )
                                    state.dismiss(activation)
                                }
                        }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Logger.e(TAG, "privilege setup failed", exception)
                state.dismiss(activation)
            }
        }
        onPauseOrDispose {
            if (resumedResultScope === resultScope) resumedResultScope = null
            privilegeActions.removeShizukuResultListener(
                privilegeResultKey,
                shizukuResultListener,
            )
            privilegeActions.removeDhizukuResultListener(
                privilegeResultKey,
                dhizukuResultListener,
            )
            resultJob.cancel()
        }
    }
}

@Composable
private fun ArchiveAuthenticationDialog(
    route: TaskUiRoute.AuthenticateArchive,
    onDismiss: () -> Unit,
    onSubmit: (CharArray) -> Unit,
) {
    var passphrase by remember(route.taskId) { mutableStateOf("") }
    var submitting by remember(route.taskId) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            passphrase = ""
            onDismiss()
        },
        title = { Text(stringResource(R.string.task_dialog_archive_auth_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.task_dialog_archive_auth_message,
                        route.packageName,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.task_dialog_archive_passphrase_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !submitting,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = passphrase.isNotEmpty() && !submitting,
                onClick = {
                    submitting = true
                    val submitted = passphrase.toCharArray()
                    passphrase = ""
                    onSubmit(submitted)
                },
            ) {
                Text(stringResource(R.string.task_dialog_archive_auth_submit))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    passphrase = ""
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun LaunchedExternalRoute(
    activation: TaskActionRouteActivation,
    state: TaskActionRouteState,
    launch: () -> Unit,
) {
    LaunchedEffect(activation.generation) {
        runCatching(launch)
            .onSuccess { state.dismiss(activation) }
            .onFailure { Logger.e(TAG, "task action destination unavailable", it) }
    }
}

internal fun CoroutineScope.launchTaskAction(
    state: TaskActionRouteState,
    activation: TaskActionRouteActivation,
    block: suspend () -> TaskActionDispatch,
) {
    if (!state.arm(activation, TaskExternalResultKind.ACTION_SUBMISSION)) return
    launch {
        try {
            state.complete(activation, block())
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Logger.e(TAG, "task action failed", exception)
        } finally {
            state.disarm(activation, TaskExternalResultKind.ACTION_SUBMISSION)
        }
    }
}

@StringRes
private fun PrivilegeSweepOperation.labelRes(): Int = when (this) {
    PrivilegeSweepOperation.FREEZE -> R.string.task_operation_freeze
    PrivilegeSweepOperation.UNFREEZE -> R.string.task_operation_unfreeze
    PrivilegeSweepOperation.SUSPEND -> R.string.task_operation_suspend
    PrivilegeSweepOperation.UNSUSPEND -> R.string.task_operation_unsuspend
    PrivilegeSweepOperation.CLEAR_CACHE -> R.string.task_operation_clear_cache
    PrivilegeSweepOperation.REINSTALL -> R.string.task_operation_reinstall
}

private const val TAG = "TaskActionRouteHost"
