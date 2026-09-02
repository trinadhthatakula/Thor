// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FreezeLoggerDialogTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var dismissals = 0
    private var cancellations = 0

    private fun progress(
        phase: PrivilegeSweepPhase,
        total: Int = 10,
        succeeded: Int = 0,
        failed: Int = 0,
        busy: Int = 0,
        unresolved: Int = total - succeeded - failed - busy,
        rootLaneDegraded: Boolean = false,
        message: UiText? = null,
    ) = SweepProgressUiState(
        phase = phase,
        total = total,
        succeeded = succeeded,
        failed = failed,
        busy = busy,
        unresolved = unresolved,
        rootLaneDegraded = rootLaneDegraded,
        message = message,
    )

    private fun setDialog(
        state: SweepProgressUiState,
        autoDismissMillis: Long = 60_000L,
    ) {
        setMutableDialog(state, autoDismissMillis)
    }

    private fun setMutableDialog(
        initialState: SweepProgressUiState,
        autoDismissMillis: Long = 60_000L,
    ): MutableState<SweepProgressUiState> {
        dismissals = 0
        cancellations = 0
        val state = mutableStateOf(initialState)
        rule.setContent {
            MaterialTheme {
                FreezeLoggerDialog(
                    state = state.value,
                    onDismiss = { dismissals++ },
                    onCancelQueue = { cancellations++ },
                    modifier = Modifier.testTag("sweep-dialog"),
                    autoDismissMillis = autoDismissMillis,
                )
            }
        }
        return state
    }

    @Test
    fun launchFailure_isTerminalAndKeepsItsActionableMessage() {
        setDialog(
            progress(
                phase = PrivilegeSweepPhase.FAILED,
                message = UiText.DynamicString("Notifications are required"),
            )
        )

        rule.onNodeWithText("Sweep could not start").assertExists()
        rule.onNodeWithText("Notifications are required").assertExists()
        rule.onNodeWithText("Cancel sweep queue").assertDoesNotExist()
        rule.onNodeWithText("Close").assertExists()
    }

    @Test
    fun queued_showsQueueStateAndCancellation() {
        setDialog(progress(PrivilegeSweepPhase.QUEUED))

        rule.onNodeWithText("Sweep queued").assertExists()
        rule.onNodeWithText("Cancel sweep queue").performClick()

        rule.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test
    fun running_showsCompletedCountAndCancellation() {
        setDialog(progress(PrivilegeSweepPhase.RUNNING, succeeded = 3))

        rule.onNodeWithText("Running 3 of 10").assertExists()
        rule.onNodeWithText("Cancel sweep queue").assertExists()
    }

    @Test
    fun success_showsFullCountAndMayAutoDismiss() {
        rule.mainClock.autoAdvance = false
        setDialog(
            progress(
                phase = PrivilegeSweepPhase.SUCCEEDED,
                succeeded = 10,
                unresolved = 0,
            ),
            autoDismissMillis = 2_000L,
        )

        rule.onNodeWithText("Completed 10 of 10").assertExists()
        rule.mainClock.advanceTimeBy(2_100L)
        rule.waitForIdle()

        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun partial_namesEveryOutcomeAndWaitsForAcknowledgement() {
        rule.mainClock.autoAdvance = false
        setDialog(
            progress(
                phase = PrivilegeSweepPhase.PARTIAL,
                succeeded = 6,
                failed = 2,
                busy = 1,
                unresolved = 1,
            ),
            autoDismissMillis = 1L,
        )

        rule.onNodeWithText("Sweep finished with issues").assertExists()
        rule.onNodeWithText("6 succeeded, 2 failed, 1 busy, 1 unresolved").assertExists()
        rule.mainClock.advanceTimeBy(10_000L)
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, dismissals) }
        rule.onNodeWithText("Close").assertExists()
    }

    @Test
    fun cancelled_keepsPartialCountsUntilAcknowledged() {
        setDialog(
            progress(
                phase = PrivilegeSweepPhase.CANCELLED,
                succeeded = 4,
                failed = 1,
                busy = 0,
                unresolved = 5,
            )
        )

        rule.onNodeWithText("Sweep cancelled").assertExists()
        rule.onNodeWithText("4 succeeded, 1 failed, 0 busy, 5 unresolved").assertExists()
        rule.onNodeWithText("Close").performClick()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun observerFailure_isAVisibleTerminalErrorRatherThanRunningAnimation() {
        setDialog(progress(PrivilegeSweepPhase.OBSERVER_FAILURE))

        rule.onNodeWithText("Progress unavailable").assertExists()
        rule.onNodeWithText("Thor could not reconnect to this sweep. Check the apps before relying on it.")
            .assertExists()
        rule.onNodeWithText("Cancel sweep queue").assertDoesNotExist()
        rule.onNodeWithText("Close").assertExists()
    }

    @Test
    fun reconnectedRunningRequest_rendersAsRunningWithoutASeparateTransientState() {
        setDialog(progress(PrivilegeSweepPhase.RUNNING, succeeded = 3))

        rule.onNodeWithText("Running 3 of 10").assertExists()
        rule.onNodeWithText("Cancel sweep queue").assertExists()
    }

    @Test
    fun degradedRootLane_isNamedInAdditionToTheCurrentPhase() {
        setDialog(
            progress(
                phase = PrivilegeSweepPhase.RUNNING,
                succeeded = 3,
                rootLaneDegraded = true,
            )
        )

        rule.onNodeWithText("Running 3 of 10").assertExists()
        rule.onNodeWithText("Root archive/sweep lane degraded").assertExists()
    }

    @Test
    fun backDismissal_isBlockedOnlyForQueuedAndRunningRequests() {
        val state = setMutableDialog(progress(PrivilegeSweepPhase.QUEUED))
        pressBack()
        rule.runOnIdle { assertEquals(0, dismissals) }

        rule.runOnIdle { state.value = progress(PrivilegeSweepPhase.RUNNING, succeeded = 3) }
        pressBack()
        rule.runOnIdle { assertEquals(0, dismissals) }

        rule.runOnIdle { state.value = progress(PrivilegeSweepPhase.PARTIAL, failed = 1) }
        pressBack()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun outsideDismissal_isBlockedOnlyForQueuedAndRunningRequests() {
        val state = setMutableDialog(progress(PrivilegeSweepPhase.RUNNING, succeeded = 3))
        clickOutsideDialog()
        rule.runOnIdle { assertEquals(0, dismissals) }

        rule.runOnIdle { state.value = progress(PrivilegeSweepPhase.CANCELLED, succeeded = 3) }
        clickOutsideDialog()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    private fun clickOutsideDialog() {
        onView(isRoot()).inRoot(isDialog()).perform(
            GeneralClickAction(
                Tap.SINGLE,
                { view -> floatArrayOf(1f, view.height / 2f) },
                Press.FINGER,
                InputDevice.SOURCE_UNKNOWN,
                MotionEvent.BUTTON_PRIMARY,
            )
        )
        rule.waitForIdle()
        rule.onNodeWithTag("sweep-dialog").assertExists()
    }
}
