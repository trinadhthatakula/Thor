// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.R
import com.valhalla.thor.data.backup.job.RestoreSourceGrantHolder
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.repository.TaskActionController
import com.valhalla.thor.domain.repository.TaskActionDispatch
import com.valhalla.thor.domain.repository.TaskActionRejection
import com.valhalla.thor.presentation.launcher.ShareHandoffActivity
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskActionRouteHostTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun archivePassphraseIsSubmittedToExactTaskThenClearedAndZeroed() {
        val state = TaskActionRouteState().apply {
            activate(
                com.valhalla.thor.domain.repository.TaskUiRoute.AuthenticateArchive(
                    TASK_ID,
                    "example.app",
                    DataTaskKind.ARCHIVE_RESTORE,
                ),
            )
        }
        val controller = FakeTaskActionController()
        setHost(state, controller)

        rule.onNodeWithText(rule.activity.getString(R.string.task_dialog_archive_passphrase_label))
            .performTextInput("secret")
        rule.onNodeWithText(rule.activity.getString(R.string.task_dialog_archive_auth_submit))
            .performClick()

        rule.waitUntil { controller.passphraseCopy != null && state.active == null }
        rule.runOnIdle {
            assertEquals(TASK_ID, controller.passphraseTaskId)
            assertEquals("secret", controller.passphraseCopy?.concatToString())
            assertTrue(requireNotNull(controller.passphraseReference).all { it == '\u0000' })
            assertNull(state.active)
        }
    }

    @Test
    fun archiveSubmissionFailureDismissesTheDisabledDialog() {
        val state = TaskActionRouteState().apply {
            activate(
                com.valhalla.thor.domain.repository.TaskUiRoute.AuthenticateArchive(
                    TASK_ID,
                    "example.app",
                    DataTaskKind.ARCHIVE_RESTORE,
                ),
            )
        }
        val controller = FakeTaskActionController().apply {
            archiveFailure = IllegalStateException("ambiguous submission")
        }
        setHost(state, controller)

        rule.onNodeWithText(rule.activity.getString(R.string.task_dialog_archive_passphrase_label))
            .performTextInput("secret")
        rule.onNodeWithText(rule.activity.getString(R.string.task_dialog_archive_auth_submit))
            .performClick()

        rule.waitUntil { controller.passphraseReference != null }
        rule.runOnIdle {
            assertNull(state.active)
            assertTrue(requireNotNull(controller.passphraseReference).all { it == '\u0000' })
        }
    }

    @Test
    fun privilegeAuthorizationDoesNotAddAThorConfirmationDialog() {
        val state = TaskActionRouteState().apply {
            activate(com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(TASK_ID))
        }

        val controller = FakeTaskActionController()
        val privilegeActions = object : TaskPrivilegeActions(
            rule.activity.packageManager,
            Dispatchers.IO,
        ) {
            override suspend fun resolveSetupTarget(): SetupTarget = SetupTarget.RefreshOnly
        }
        setHost(state, controller, privilegeActions)

        rule.onNode(androidx.compose.ui.test.isDialog()).assertDoesNotExist()
        rule.waitUntil { controller.privilegeReturns == 1 }
        rule.runOnIdle { assertNull(state.active) }
    }

    @Test
    fun pausedPrivilegeOwnerDefersExternalRequestUntilResumed() {
        val state = TaskActionRouteState().apply {
            activate(com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(TASK_ID))
        }
        val controller = FakeTaskActionController()
        val resolutionStarted = CountDownLatch(1)
        val releaseResolution = CompletableDeferred<Unit>()
        val requestCount = AtomicInteger()
        val resolutionCount = AtomicInteger()
        var dhizukuReturned: (() -> Unit)? = null
        val privilegeActions = object : TaskPrivilegeActions(
            rule.activity.packageManager,
            Dispatchers.IO,
        ) {
            override suspend fun resolveSetupTarget(): SetupTarget {
                resolutionCount.incrementAndGet()
                resolutionStarted.countDown()
                releaseResolution.await()
                return SetupTarget.DhizukuPermission
            }

            override suspend fun requestDhizuku(
                context: android.content.Context,
                onIssued: () -> Boolean,
                onReturned: () -> Unit,
            ): Boolean {
                check(onIssued())
                dhizukuReturned = onReturned
                requestCount.incrementAndGet()
                return true
            }
        }
        setHost(state, controller, privilegeActions)
        rule.waitForIdle()
        assertTrue(
            resolutionStarted.await(EXTERNAL_LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )

        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        releaseResolution.complete(Unit)
        rule.waitForIdle()

        rule.runOnIdle {
            assertEquals(0, requestCount.get())
            assertEquals(TASK_ID, state.active?.route?.taskId)
            assertTrue(controller.privilegeTaskIds.isEmpty())
        }

        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil { requestCount.get() == 1 }
        rule.runOnIdle {
            assertEquals(2, resolutionCount.get())
            assertEquals(1, requestCount.get())
            assertEquals(TASK_ID, state.active?.route?.taskId)
            assertTrue(controller.privilegeTaskIds.isEmpty())
            requireNotNull(dhizukuReturned).invoke()
        }

        rule.waitUntil { controller.privilegeTaskIds.size == 1 }
        rule.runOnIdle {
            assertEquals(listOf(TASK_ID), controller.privilegeTaskIds)
            assertNull(state.active)
        }
    }


    @Test
    fun dhizukuRequestCancelledBeforeIssuanceIsRetriedAfterResume() {
        val state = TaskActionRouteState().apply {
            activate(com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(TASK_ID))
        }
        val controller = FakeTaskActionController()
        val firstRequestStarted = CountDownLatch(1)
        val holdFirstRequest = CompletableDeferred<Unit>()
        val requestCount = AtomicInteger()
        val callbacks = mutableListOf<() -> Unit>()
        val privilegeActions = object : TaskPrivilegeActions(
            rule.activity.packageManager,
            Dispatchers.IO,
        ) {
            override suspend fun resolveSetupTarget(): SetupTarget = SetupTarget.DhizukuPermission

            override suspend fun requestDhizuku(
                context: android.content.Context,
                onIssued: () -> Boolean,
                onReturned: () -> Unit,
            ): Boolean {
                if (requestCount.incrementAndGet() == 1) {
                    firstRequestStarted.countDown()
                    holdFirstRequest.await()
                }
                check(onIssued())
                callbacks += onReturned
                return true
            }
        }
        setHost(state, controller, privilegeActions)
        rule.waitForIdle()
        assertTrue(
            firstRequestStarted.await(EXTERNAL_LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )

        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.waitForIdle()
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil { requestCount.get() == 2 && callbacks.size == 1 }

        rule.runOnIdle {
            assertEquals(TASK_ID, state.active?.route?.taskId)
            callbacks.single().invoke()
        }
        rule.waitUntil { controller.privilegeTaskIds.size == 1 }
        rule.runOnIdle {
            assertEquals(listOf(TASK_ID), controller.privilegeTaskIds)
            assertNull(state.active)
        }
    }

    @Test
    fun staleDhizukuResultCannotSettleReplacementTask() {
        val state = TaskActionRouteState()
        val firstActivation = state.activate(
            com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(TASK_ID),
        )
        val firstRequestKey = state.externalResultKey(firstActivation)
        val controller = FakeTaskActionController()
        val callbacks = mutableListOf<() -> Unit>()
        val privilegeActions = object : TaskPrivilegeActions(
            rule.activity.packageManager,
            Dispatchers.IO,
        ) {
            override suspend fun resolveSetupTarget(): SetupTarget = SetupTarget.DhizukuPermission

            override suspend fun requestDhizuku(
                context: android.content.Context,
                onIssued: () -> Boolean,
                onReturned: () -> Unit,
            ): Boolean {
                check(onIssued())
                callbacks += onReturned
                return true
            }
        }
        setHost(state, controller, privilegeActions)
        rule.waitUntil { callbacks.size == 1 }

        rule.runOnIdle {
            state.activate(
                com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(OTHER_TASK_ID),
            )
        }
        rule.waitUntil { callbacks.size == 2 }

        rule.runOnIdle { callbacks.first().invoke() }
        rule.waitForIdle()
        var staleReplays = 0
        val staleListener = { staleReplays += 1 }
        rule.runOnIdle {
            privilegeActions.addDhizukuResultListener(firstRequestKey, staleListener)
            assertEquals(0, staleReplays)
            privilegeActions.removeDhizukuResultListener(firstRequestKey, staleListener)
            assertTrue(controller.privilegeTaskIds.isEmpty())
            assertEquals(OTHER_TASK_ID, state.active?.route?.taskId)
        }

        rule.runOnIdle { callbacks.last().invoke() }
        rule.waitUntil { controller.privilegeTaskIds.isNotEmpty() }
        rule.runOnIdle {
            assertEquals(listOf(OTHER_TASK_ID), controller.privilegeTaskIds)
            assertNull(state.active)
        }
    }

    @Test
    fun dhizukuResultIsScopedToExactRouteStateOwnerAcrossHostSwaps() {
        val firstState = TaskActionRouteState().apply {
            activate(com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(TASK_ID))
        }
        val secondState = TaskActionRouteState().apply {
            activate(com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(TASK_ID))
        }
        val firstController = FakeTaskActionController()
        val secondController = FakeTaskActionController()
        val callbacks = mutableListOf<() -> Unit>()
        val privilegeActions = object : TaskPrivilegeActions(
            rule.activity.packageManager,
            Dispatchers.IO,
        ) {
            override suspend fun resolveSetupTarget(): SetupTarget = SetupTarget.DhizukuPermission

            override suspend fun requestDhizuku(
                context: android.content.Context,
                onIssued: () -> Boolean,
                onReturned: () -> Unit,
            ): Boolean {
                check(onIssued())
                callbacks += onReturned
                return true
            }
        }
        val displayFirstOwner = mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                if (displayFirstOwner.value) {
                    TaskActionRouteHost(
                        state = firstState,
                        taskActionController = firstController,
                        restoreSourceGrants = RestoreSourceGrantHolder(),
                        privilegeActions = privilegeActions,
                    )
                } else {
                    TaskActionRouteHost(
                        state = secondState,
                        taskActionController = secondController,
                        restoreSourceGrants = RestoreSourceGrantHolder(),
                        privilegeActions = privilegeActions,
                    )
                }
            }
        }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil { callbacks.size == 1 }

        rule.runOnIdle { displayFirstOwner.value = false }
        rule.waitUntil { callbacks.size == 2 }
        rule.runOnIdle { callbacks.first().invoke() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(firstController.privilegeTaskIds.isEmpty())
            assertTrue(secondController.privilegeTaskIds.isEmpty())
            assertEquals(TASK_ID, secondState.active?.route?.taskId)
        }

        rule.runOnIdle { displayFirstOwner.value = true }
        rule.waitUntil { firstController.privilegeTaskIds.isNotEmpty() }
        rule.runOnIdle {
            assertEquals(listOf(TASK_ID), firstController.privilegeTaskIds)
            assertNull(firstState.active)
        }

        rule.runOnIdle { displayFirstOwner.value = false }
        rule.runOnIdle { callbacks.last().invoke() }
        rule.waitUntil { secondController.privilegeTaskIds.isNotEmpty() }
        rule.runOnIdle {
            assertEquals(listOf(TASK_ID), secondController.privilegeTaskIds)
            assertNull(secondState.active)
        }
    }

    @Test
    fun restoredDhizukuRouteReattachesToOutstandingResultWithoutRelaunching() {
        val state = TaskActionRouteState().apply {
            activate(com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(TASK_ID))
        }
        val controller = FakeTaskActionController()
        val callbacks = mutableListOf<() -> Unit>()
        val privilegeActions = object : TaskPrivilegeActions(
            rule.activity.packageManager,
            Dispatchers.IO,
        ) {
            override suspend fun resolveSetupTarget(): SetupTarget = SetupTarget.DhizukuPermission

            override suspend fun requestDhizuku(
                context: android.content.Context,
                onIssued: () -> Boolean,
                onReturned: () -> Unit,
            ): Boolean {
                check(onIssued())
                callbacks += onReturned
                return true
            }
        }
        val displayedState = mutableStateOf(state)
        rule.setContent {
            MaterialTheme {
                TaskActionRouteHost(
                    state = displayedState.value,
                    taskActionController = controller,
                    restoreSourceGrants = RestoreSourceGrantHolder(),
                    privilegeActions = privilegeActions,
                )
            }
        }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil { callbacks.size == 1 }

        lateinit var restored: TaskActionRouteState
        rule.runOnIdle {
            restored = requireNotNull(restoreTaskActionRouteState(state.savedValues()))
            displayedState.value = restored
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, callbacks.size) }

        rule.runOnIdle { callbacks.single().invoke() }
        rule.waitUntil { controller.privilegeTaskIds.isNotEmpty() }
        rule.runOnIdle {
            assertEquals(listOf(TASK_ID), controller.privilegeTaskIds)
            assertNull(restored.active)
        }
    }

    @Test
    fun dhizukuResultDuringHostRecreationIsReplayedToRestoredRoute() {
        val state = TaskActionRouteState().apply {
            activate(com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(TASK_ID))
        }
        val controller = FakeTaskActionController()
        val callbacks = mutableListOf<() -> Unit>()
        val privilegeActions = object : TaskPrivilegeActions(
            rule.activity.packageManager,
            Dispatchers.IO,
        ) {
            override suspend fun resolveSetupTarget(): SetupTarget = SetupTarget.DhizukuPermission

            override suspend fun requestDhizuku(
                context: android.content.Context,
                onIssued: () -> Boolean,
                onReturned: () -> Unit,
            ): Boolean {
                check(onIssued())
                callbacks += onReturned
                return true
            }
        }
        val displayedState = mutableStateOf<TaskActionRouteState?>(state)
        rule.setContent {
            displayedState.value?.let { activeState ->
                MaterialTheme {
                    TaskActionRouteHost(
                        state = activeState,
                        taskActionController = controller,
                        restoreSourceGrants = RestoreSourceGrantHolder(),
                        privilegeActions = privilegeActions,
                    )
                }
            }
        }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil { callbacks.size == 1 }

        lateinit var restored: TaskActionRouteState
        rule.runOnIdle {
            restored = requireNotNull(restoreTaskActionRouteState(state.savedValues()))
            displayedState.value = null
        }
        rule.waitForIdle()
        rule.runOnIdle { callbacks.single().invoke() }
        rule.runOnIdle { displayedState.value = restored }

        rule.waitUntil { controller.privilegeTaskIds.isNotEmpty() }
        rule.runOnIdle {
            assertEquals(listOf(TASK_ID), controller.privilegeTaskIds)
            assertNull(restored.active)
        }
    }

    @Test
    fun dhizukuResultClaimedBeforeRecreationIsReplayedAfterSettlementCancellation() {
        val original = TaskActionRouteState().apply {
            activate(com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(TASK_ID))
        }
        val controller = FakeTaskActionController().apply {
            beforePrivilegeReturn = { attempt ->
                if (attempt == 1) awaitCancellation()
            }
        }
        val callbacks = mutableListOf<() -> Unit>()
        val privilegeActions = object : TaskPrivilegeActions(
            rule.activity.packageManager,
            Dispatchers.IO,
        ) {
            override suspend fun resolveSetupTarget(): SetupTarget = SetupTarget.DhizukuPermission

            override suspend fun requestDhizuku(
                context: android.content.Context,
                onIssued: () -> Boolean,
                onReturned: () -> Unit,
            ): Boolean {
                check(onIssued())
                callbacks += onReturned
                return true
            }
        }
        val displayedState = mutableStateOf<TaskActionRouteState?>(original)
        rule.setContent {
            displayedState.value?.let { activeState ->
                MaterialTheme {
                    TaskActionRouteHost(
                        state = activeState,
                        taskActionController = controller,
                        restoreSourceGrants = RestoreSourceGrantHolder(),
                        privilegeActions = privilegeActions,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(timeoutMillis = EXTERNAL_LAUNCH_TIMEOUT_SECONDS * 1_000) {
            callbacks.size == 1
        }

        rule.runOnIdle { callbacks.single().invoke() }
        rule.waitUntil(timeoutMillis = EXTERNAL_LAUNCH_TIMEOUT_SECONDS * 1_000) {
            controller.privilegeReturns == 1
        }

        lateinit var restored: TaskActionRouteState
        rule.runOnIdle {
            restored = requireNotNull(restoreTaskActionRouteState(original.savedValues()))
            displayedState.value = null
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(TASK_ID, original.active?.route?.taskId)
            displayedState.value = restored
        }

        rule.waitUntil { controller.privilegeReturns == 2 && restored.active == null }
        rule.runOnIdle {
            assertEquals(listOf(TASK_ID, TASK_ID), controller.privilegeTaskIds)
            assertNull(restored.active)
        }
    }

    @Test
    fun backgroundDhizukuResultIsDispatchedBeforeClaimingRoute() {
        val state = TaskActionRouteState().apply {
            activate(com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(TASK_ID))
        }
        val settledOnMain = java.util.concurrent.atomic.AtomicBoolean()
        val controller = FakeTaskActionController().apply {
            beforePrivilegeReturn = {
                settledOnMain.set(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            }
        }
        val callbacks = mutableListOf<() -> Unit>()
        val privilegeActions = object : TaskPrivilegeActions(
            rule.activity.packageManager,
            Dispatchers.IO,
        ) {
            override suspend fun resolveSetupTarget(): SetupTarget = SetupTarget.DhizukuPermission

            override suspend fun requestDhizuku(
                context: android.content.Context,
                onIssued: () -> Boolean,
                onReturned: () -> Unit,
            ): Boolean {
                check(onIssued())
                callbacks += onReturned
                return true
            }
        }
        setHost(state, controller, privilegeActions)
        rule.waitUntil { callbacks.size == 1 }
        rule.runOnIdle {
            val delivery = Thread(callbacks.single(), "dhizuku-result")
            delivery.start()
            delivery.join(EXTERNAL_LAUNCH_TIMEOUT_SECONDS * 1_000)
            assertTrue(!delivery.isAlive)
            assertEquals(0, controller.privilegeReturns)
            assertEquals(TASK_ID, state.active?.route?.taskId)
        }
        rule.waitUntil { controller.privilegeReturns == 1 && state.active == null }
        rule.runOnIdle { assertTrue(settledOnMain.get()) }
    }

    @Test
    fun pausedDhizukuSettlementCannotAcknowledgeSavedOwner() {
        val original = TaskActionRouteState().apply {
            activate(com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(TASK_ID))
        }
        val releaseFirstSettlement = CompletableDeferred<Unit>()
        val controller = FakeTaskActionController().apply {
            beforePrivilegeReturn = { attempt ->
                if (attempt == 1) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        releaseFirstSettlement.await()
                    }
                }
            }
        }
        val callbacks = mutableListOf<() -> Unit>()
        val privilegeActions = object : TaskPrivilegeActions(
            rule.activity.packageManager,
            Dispatchers.IO,
        ) {
            override suspend fun resolveSetupTarget(): SetupTarget = SetupTarget.DhizukuPermission

            override suspend fun requestDhizuku(
                context: android.content.Context,
                onIssued: () -> Boolean,
                onReturned: () -> Unit,
            ): Boolean {
                check(onIssued())
                callbacks += onReturned
                return true
            }
        }
        val displayedState = mutableStateOf<TaskActionRouteState?>(original)
        rule.setContent {
            displayedState.value?.let { activeState ->
                MaterialTheme {
                    TaskActionRouteHost(
                        state = activeState,
                        taskActionController = controller,
                        restoreSourceGrants = RestoreSourceGrantHolder(),
                        privilegeActions = privilegeActions,
                    )
                }
            }
        }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil { callbacks.size == 1 }
        rule.runOnIdle { callbacks.single().invoke() }
        rule.waitUntil { controller.privilegeReturns == 1 }

        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        lateinit var restored: TaskActionRouteState
        rule.runOnIdle {
            restored = requireNotNull(restoreTaskActionRouteState(original.savedValues()))
            releaseFirstSettlement.complete(Unit)
        }
        rule.waitForIdle()
        rule.runOnIdle { displayedState.value = null }
        rule.waitForIdle()
        rule.runOnIdle { displayedState.value = restored }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        rule.waitUntil {
            callbacks.size > 1 || controller.privilegeReturns == 2
        }
        rule.runOnIdle {
            assertEquals(1, callbacks.size)
            assertEquals(listOf(TASK_ID, TASK_ID), controller.privilegeTaskIds)
            assertNull(restored.active)
        }
    }

    @Test
    fun restoredManagerResultRetriesSettlementWithoutRelaunchingManager() {
        val monitor = IntentCaptureMonitor { intent -> intent.action == MANAGER_ACTION }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(monitor)
        try {
            val original = TaskActionRouteState().apply {
                activate(com.valhalla.thor.domain.repository.TaskUiRoute.AuthorizePrivilege(TASK_ID))
            }
            val controller = FakeTaskActionController().apply {
                beforePrivilegeReturn = { attempt ->
                    if (attempt == 1) awaitCancellation()
                }
            }
            val privilegeActions = object : TaskPrivilegeActions(
                rule.activity.packageManager,
                Dispatchers.IO,
            ) {
                override suspend fun resolveSetupTarget(): SetupTarget =
                    SetupTarget.ManagerApp(Intent(MANAGER_ACTION))
            }
            val displayedState = mutableStateOf<TaskActionRouteState?>(original)
            rule.setContent {
                displayedState.value?.let { activeState ->
                    MaterialTheme {
                        TaskActionRouteHost(
                            state = activeState,
                            taskActionController = controller,
                            restoreSourceGrants = RestoreSourceGrantHolder(),
                            privilegeActions = privilegeActions,
                        )
                    }
                }
            }
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            requireNotNull(monitor.await())
            rule.waitUntil { controller.privilegeReturns == 1 }

            lateinit var restored: TaskActionRouteState
            rule.runOnIdle {
                restored = requireNotNull(restoreTaskActionRouteState(original.savedValues()))
                displayedState.value = null
            }
            rule.waitForIdle()
            rule.runOnIdle { displayedState.value = restored }

            rule.waitUntil { controller.privilegeReturns == 2 && restored.active == null }
            rule.runOnIdle {
                assertEquals(1, monitor.launchCount.get())
                assertEquals(listOf(TASK_ID, TASK_ID), controller.privilegeTaskIds)
                assertNull(restored.active)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun restoreSourceReusesAuthorizedTokenWithoutLaunchingAReplacementPicker() {
        val state = TaskActionRouteState().apply {
            activate(
                com.valhalla.thor.domain.repository.TaskUiRoute.PickRestoreSource(
                    TASK_ID,
                    "example.app",
                ),
            )
        }
        val grants = RestoreSourceGrantHolder()
        val token = grants.registerAuthorized(TASK_ID, "content://restore/source")
        val controller = FakeTaskActionController().apply {
            restoreDispatch = TaskActionDispatch.Rejected(TaskActionRejection.START_REJECTED)
        }
        setHost(state, controller, restoreSourceGrants = grants)

        rule.onNodeWithText(rule.activity.getString(R.string.task_dialog_restore_source_select))
            .performClick()

        rule.waitUntil { controller.restoreToken != null && state.active == null }
        rule.runOnIdle {
            assertEquals(TASK_ID, controller.restoreTaskId)
            assertEquals(UUID.fromString(token), controller.restoreToken)
            assertEquals(token, grants.currentToken(TASK_ID))
        }
    }

    @Test
    fun restoreSourceCancelDismissesOnlyTheForegroundRoute() {
        val state = TaskActionRouteState().apply {
            activate(
                com.valhalla.thor.domain.repository.TaskUiRoute.PickRestoreSource(
                    TASK_ID,
                    "example.app",
                ),
            )
        }
        setHost(state, FakeTaskActionController())

        rule.onNodeWithText(rule.activity.getString(R.string.cancel)).performClick()

        rule.runOnIdle { assertNull(state.active) }
    }

    @Test
    fun preparedShareLaunchesOnlyTheExactTaskIdentity() {
        val monitor = IntentCaptureMonitor { intent ->
            intent.component?.className == ShareHandoffActivity::class.java.name
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(monitor)
        try {
            val state = TaskActionRouteState().apply {
                activate(
                    com.valhalla.thor.domain.repository.TaskUiRoute.SharePreparedOutputs(
                        TASK_ID,
                        listOf(UUID.randomUUID()),
                    ),
                )
            }
            setHost(state, FakeTaskActionController())

            val intent = requireNotNull(monitor.await())
            assertEquals(
                TASK_ID.toString(),
                intent.getStringExtra(ShareHandoffActivity.EXTRA_TASK_ID)
            )
            assertEquals(setOf(ShareHandoffActivity.EXTRA_TASK_ID), intent.extras?.keySet())
            rule.waitUntil { state.active == null }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun notificationSettingsLaunchesOnlyTheExactChannelIdentity() {
        val monitor = IntentCaptureMonitor { intent ->
            intent.action == Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(monitor)
        try {
            val state = TaskActionRouteState().apply {
                activate(
                    com.valhalla.thor.domain.repository.TaskUiRoute.OpenNotificationSettings(
                        TASK_ID,
                        "exact-channel",
                    ),
                )
            }
            setHost(state, FakeTaskActionController())

            val intent = requireNotNull(monitor.await())
            assertEquals(
                rule.activity.packageName,
                intent.getStringExtra(Settings.EXTRA_APP_PACKAGE),
            )
            assertEquals(
                "exact-channel",
                intent.getStringExtra(Settings.EXTRA_CHANNEL_ID),
            )
            assertEquals(
                setOf(Settings.EXTRA_APP_PACKAGE, Settings.EXTRA_CHANNEL_ID),
                intent.extras?.keySet(),
            )
            rule.waitUntil { state.active == null }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun restoreReviewResumesOnlyAfterExplicitConfirmation() {
        val state = TaskActionRouteState().apply {
            activate(
                com.valhalla.thor.domain.repository.TaskUiRoute.ReviewInterruptedRestore(
                    TASK_ID,
                    RestoreMutationBreadcrumb("example.app", "Example", 1L),
                ),
            )
        }
        val controller = FakeTaskActionController()
        setHost(state, controller)

        rule.runOnIdle { assertTrue(controller.performed.isEmpty()) }
        rule.onNodeWithText(rule.activity.getString(R.string.task_dialog_restore_review_resume))
            .performClick()

        rule.waitUntil { controller.performed.isNotEmpty() }
        rule.runOnIdle {
            assertEquals(listOf(TASK_ID to TaskAction.RESUME), controller.performed)
            assertNull(state.active)
        }
    }

    @Test
    fun sweepRetryForwardsExactTaskAndTargetOrdinal() {
        val state = TaskActionRouteState().apply {
            activate(
                com.valhalla.thor.domain.repository.TaskUiRoute.ConfirmSweepRetry(
                    TASK_ID,
                    targetOrdinal = 7,
                    packageName = "example.app",
                    operation = PrivilegeSweepOperation.CLEAR_CACHE,
                ),
            )
        }
        val controller = FakeTaskActionController()
        setHost(state, controller)

        rule.onNodeWithText(rule.activity.getString(R.string.task_dialog_sweep_retry_confirm))
            .performClick()

        rule.waitUntil { controller.sweepRetry != null }
        rule.runOnIdle {
            assertEquals(TASK_ID to 7, controller.sweepRetry)
            assertNull(state.active)
        }
    }

    private fun setHost(
        state: TaskActionRouteState,
        controller: FakeTaskActionController,
        privilegeActions: TaskPrivilegeActions = TaskPrivilegeActions(
            rule.activity.packageManager,
            Dispatchers.IO,
        ),
        restoreSourceGrants: RestoreSourceGrantHolder = RestoreSourceGrantHolder(),
    ) {
        rule.setContent {
            MaterialTheme {
                TaskActionRouteHost(
                    state = state,
                    taskActionController = controller,
                    restoreSourceGrants = restoreSourceGrants,
                    privilegeActions = privilegeActions,
                )
            }
        }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    private class FakeTaskActionController : TaskActionController {
        val performed = mutableListOf<Pair<UUID, TaskAction>>()
        var passphraseTaskId: UUID? = null
        var passphraseCopy: CharArray? = null
        var passphraseReference: CharArray? = null
        var sweepRetry: Pair<UUID, Int>? = null
        var archiveFailure: Throwable? = null
        var privilegeReturns = 0
        val privilegeTaskIds = mutableListOf<UUID>()
        var beforePrivilegeReturn: suspend (Int) -> Unit = {}
        var restoreTaskId: UUID? = null
        var restoreToken: UUID? = null
        var restoreDispatch: TaskActionDispatch = TaskActionDispatch.Applied

        override suspend fun perform(taskId: UUID, action: TaskAction): TaskActionDispatch {
            performed += taskId to action
            return TaskActionDispatch.Applied
        }

        override suspend fun submitArchivePassphrase(
            taskId: UUID,
            passphrase: CharArray,
        ): TaskActionDispatch {
            passphraseTaskId = taskId
            passphraseCopy = passphrase.copyOf()
            passphraseReference = passphrase
            archiveFailure?.let { throw it }
            return TaskActionDispatch.Applied
        }

        override suspend fun submitRestoreSource(
            taskId: UUID,
            transientSourceToken: UUID,
        ): TaskActionDispatch {
            restoreTaskId = taskId
            restoreToken = transientSourceToken
            return restoreDispatch
        }

        override suspend fun privilegeAuthorizationReturned(taskId: UUID): TaskActionDispatch {
            privilegeReturns += 1
            privilegeTaskIds += taskId
            beforePrivilegeReturn(privilegeReturns)
            return TaskActionDispatch.Applied
        }

        override suspend fun authorizeSweepTargetRetry(
            taskId: UUID,
            targetOrdinal: Int,
        ): TaskActionDispatch {
            sweepRetry = taskId to targetOrdinal
            return TaskActionDispatch.Applied
        }
    }

    private class IntentCaptureMonitor(
        private val matches: (Intent) -> Boolean,
    ) : Instrumentation.ActivityMonitor() {
        private val started = CountDownLatch(1)

        @Volatile
        private var captured: Intent? = null

        val launchCount = AtomicInteger()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (!matches(intent)) return null
            launchCount.incrementAndGet()
            captured = Intent(intent)
            started.countDown()
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }

        fun await(): Intent? =
            if (started.await(EXTERNAL_LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) captured else null
    }

    private companion object {
        const val EXTERNAL_LAUNCH_TIMEOUT_SECONDS = 5L
        const val MANAGER_ACTION = "com.valhalla.thor.test.PRIVILEGE_MANAGER"
        val TASK_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val OTHER_TASK_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    }
}
