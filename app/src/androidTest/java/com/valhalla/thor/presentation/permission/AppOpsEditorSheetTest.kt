// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.permission

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppOpDefinition
import com.valhalla.thor.domain.model.AppOpEntry
import com.valhalla.thor.domain.model.AppOpMode
import com.valhalla.thor.domain.model.AppOpScope
import com.valhalla.thor.domain.model.AppOpsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the same usage-access editor that appeared unresponsive on the physical device. */
@RunWith(AndroidJUnit4::class)
class AppOpsEditorSheetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val writes = mutableListOf<Triple<Int, AppOpScope, AppOpMode>>()

    @Test
    fun swipingDownFromTheEditorTitleDismissesAndAllowsReopening() {
        setSection { state() }
        openUsageStats()

        val title = rule.onNodeWithTag("app_ops_editor_title").assertIsDisplayed()
        rule.waitForIdle()
        title.performTouchInput {
            swipe(center, center + Offset(0f, 300.dp.toPx()), durationMillis = 500)
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag("app_ops_editor_title").fetchSemanticsNodes().isEmpty()
        }

        openUsageStats()
        mode(R.string.app_ops_mode_ignore).performScrollTo().assertIsDisplayed()
        title.assertIsDisplayed()
        title.performTouchInput {
            swipe(center, center + Offset(0f, 300.dp.toPx()), durationMillis = 500)
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag("app_ops_editor_title").fetchSemanticsNodes().isEmpty()
        }

        openUsageStats()
        title.assertIsDisplayed()
    }

    @Test
    fun selectingAnotherUsageStatsModeEnablesApplyAndConfirmsThePackageWrite() {
        setSection { state() }
        openUsageStats()

        mode(R.string.app_ops_mode_allow).assertIsEnabled().assertIsSelected()
        applyButton().performScrollTo().assertIsNotEnabled()

        // Use a real touch on the selectable row, not only its accessibility click action.
        mode(R.string.app_ops_mode_ignore)
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performTouchInput { click() }
        mode(R.string.app_ops_mode_ignore).assertIsSelected()
        mode(R.string.app_ops_mode_allow).assertIsNotSelected()
        applyButton().performScrollTo().assertIsEnabled().performClick()

        rule.onNodeWithText(str(R.string.app_ops_confirm_title)).assertIsDisplayed()
        rule.onNodeWithText(
            str(
                R.string.app_ops_confirm_set,
                str(R.string.app_ops_mode_ignore),
                str(R.string.app_ops_scope_package),
                "Get Usage Stats",
            ),
        ).assertIsDisplayed()
        rule.runOnIdle { assertTrue(writes.isEmpty()) }
        rule.onNodeWithText(str(R.string.confirm)).performClick()

        rule.runOnIdle {
            assertEquals(listOf(Triple(43, AppOpScope.PACKAGE, AppOpMode.IGNORE)), writes)
        }
    }

    @Test
    fun aPendingRefreshBlocksTheDraftUntilTheReadFinishes() {
        var current by mutableStateOf(state())
        setSection { current }
        openUsageStats()
        mode(R.string.app_ops_mode_ignore).performScrollTo().performClick()
        applyButton().performScrollTo().assertIsEnabled()

        rule.runOnIdle { current = current.copy(isAppOpsLoading = true) }
        mode(R.string.app_ops_mode_allow).assertIsNotEnabled()
        mode(R.string.app_ops_mode_ignore).assertIsNotEnabled().assertIsSelected()
        applyButton().assertIsNotEnabled().performClick()
        rule.onNodeWithText(str(R.string.app_ops_confirm_title)).assertDoesNotExist()
        rule.runOnIdle { assertTrue(writes.isEmpty()) }

        rule.runOnIdle { current = current.copy(isAppOpsLoading = false) }
        mode(R.string.app_ops_mode_ignore).assertIsEnabled().assertIsSelected()
        applyButton().assertIsEnabled()
    }

    @Test
    fun aFailedRefreshExplainsWhyTheExistingEditorCannotWrite() {
        setSection { state().copy(appOpsLoadFailed = true) }
        openUsageStats()

        rule.onNodeWithText(str(R.string.app_ops_refresh_before_edit))
            .performScrollTo().assertIsDisplayed()
        mode(R.string.app_ops_mode_ignore)
            .performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        mode(R.string.app_ops_mode_allow).assertIsSelected()
        mode(R.string.app_ops_mode_ignore).assertIsNotSelected()
        applyButton().performScrollTo().assertIsNotEnabled()
        rule.runOnIdle { assertTrue(writes.isEmpty()) }
    }

    @Test
    fun aRuntimePermissionControlledOperationOpensItsRequestedPermissionInsteadOfOfferingWrites() {
        var openedPermissions = 0
        setSection(onOpenPermissions = { openedPermissions++ }) { handoverState(requested = true) }
        openOperation("Accept Handover")

        rule.onNodeWithText(str(R.string.app_ops_permission_controlled))
            .performScrollTo().assertIsDisplayed()
        assertNoDirectEditorControls()
        rule.onNodeWithText(str(R.string.app_ops_open_permissions))
            .performScrollTo().assertIsEnabled().performClick()

        rule.runOnIdle {
            assertEquals(1, openedPermissions)
            assertTrue(writes.isEmpty())
        }
    }

    @Test
    fun aRuntimePermissionNotRequestedByTheAppExplainsWhyItCannotBeGranted() {
        var openedPermissions = 0
        var current by mutableStateOf(handoverState(requested = false))
        setSection(
            onOpenPermissions = { openedPermissions++ },
            onFilterChange = { current = current.copy(appOpsFilter = it) },
        ) { current }

        rule.onNodeWithText("Accept Handover").assertDoesNotExist()
        rule.onNodeWithText(str(R.string.app_ops_filter_all)).performClick()
        openOperation("Accept Handover")

        rule.onNodeWithText(str(R.string.app_ops_permission_not_requested))
            .performScrollTo().assertIsDisplayed()
        assertNoDirectEditorControls()
        rule.onNodeWithText(str(R.string.app_ops_open_permissions)).assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(0, openedPermissions)
            assertTrue(writes.isEmpty())
        }
    }

    @Test
    fun anUnknownRuntimePermissionPolicyShowsReadOnlyExplanationWithoutPermissionLink() {
        var openedPermissions = 0
        setSection(onOpenPermissions = { openedPermissions++ }) {
            handoverState(requested = true, policyUncertain = true)
        }
        openOperation("Accept Handover")

        rule.onNodeWithText(str(R.string.app_ops_permission_policy_uncertain))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(str(R.string.app_ops_permission_controlled)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.app_ops_open_permissions)).assertDoesNotExist()
        assertNoDirectEditorControls()
        rule.runOnIdle {
            assertEquals(0, openedPermissions)
            assertTrue(writes.isEmpty())
        }
    }

    @Test
    fun aFailedWriteClosesTheEditorAndRefreshDoesNotReopenItsStaleSelection() {
        var current by mutableStateOf(state())
        setSection { current }
        openUsageStats()
        mode(R.string.app_ops_mode_allow).assertExists()

        rule.runOnIdle {
            current = current.copy(
                appOpsSnapshot = null,
                appOpsLoadFailed = true,
                appOpsStatusUncertain = true,
            )
        }
        mode(R.string.app_ops_mode_allow).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.app_ops_status_uncertain)).assertIsDisplayed()

        rule.runOnIdle { current = state() }
        mode(R.string.app_ops_mode_allow).assertDoesNotExist()
        rule.onNodeWithText("Get Usage Stats").assertIsDisplayed()

        // A deliberate new tap can still open the refreshed operation.
        openUsageStats()
        mode(R.string.app_ops_mode_allow).assertIsEnabled().assertIsSelected()
        rule.runOnIdle { assertTrue(writes.isEmpty()) }
    }

    private fun setSection(
        onOpenPermissions: () -> Unit = {},
        onFilterChange: (AppOpsFilter) -> Unit = {},
        state: () -> PermissionUiState,
    ) = rule.setContent {
        MaterialTheme {
            AppOpsSection(
                state = state(),
                onSearchQueryChange = {},
                onFilterChange = onFilterChange,
                onRefresh = {},
                onSetMode = { code, scope, mode -> writes += Triple(code, scope, mode) },
                onResetMode = { _, _ -> error("This test must not request a reset") },
                onOpenPermissions = onOpenPermissions,
            )
        }
    }

    private fun openUsageStats() = openOperation("Get Usage Stats")

    private fun openOperation(name: String) {
        rule.onNodeWithText(name).performClick()
    }

    private fun assertNoDirectEditorControls() {
        rule.onNodeWithText(str(R.string.app_ops_choose_mode)).assertDoesNotExist()
        mode(R.string.app_ops_mode_allow).assertDoesNotExist()
        mode(R.string.app_ops_mode_ignore).assertDoesNotExist()
        applyButton().assertDoesNotExist()
        rule.onNodeWithText(str(R.string.app_ops_reset_platform_default)).assertDoesNotExist()
    }

    private fun mode(label: Int) = rule.onNode(
        hasText(str(label)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
    )

    private fun applyButton() = rule.onNodeWithText(str(R.string.app_ops_apply_mode))

    private fun str(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    private fun handoverState(requested: Boolean, policyUncertain: Boolean = false): PermissionUiState {
        val base = state()
        val snapshot = requireNotNull(base.appOpsSnapshot)
        val usage = snapshot.entries.single()
        return base.copy(
            appOpsSnapshot = snapshot.copy(
                entries = listOf(
                    usage.copy(
                        definition = usage.definition.copy(
                            code = 78,
                            debugName = "ACCEPT_HANDOVER",
                            publicName = "android:accept_handover",
                            relatedPermissions = listOf("android.permission.ACCEPT_HANDOVER"),
                            platformDefault = AppOpMode.ALLOW,
                            isRuntimePermissionControlled = !policyUncertain,
                            isRuntimePermissionControlUncertain = policyUncertain,
                        ),
                        packageMode = AppOpMode.IGNORE,
                        permissionRequested = requested,
                    ),
                ),
            ),
        )
    }

    private fun state() = PermissionUiState(
        packageName = "com.example.usage",
        appName = "Usage app",
        isLoading = false,
        isPrivilegeMode = true,
        appOpsSnapshot = AppOpsSnapshot(
            userId = 0,
            uid = 10296,
            canEdit = true,
            entries = listOf(
                AppOpEntry(
                    definition = AppOpDefinition(
                        code = 43,
                        debugName = "GET_USAGE_STATS",
                        publicName = "android:get_usage_stats",
                        aliases = emptyList(),
                        relatedPermissions = listOf("android.permission.PACKAGE_USAGE_STATS"),
                        platformDefault = AppOpMode.DEFAULT,
                        allowsReset = true,
                    ),
                    packageMode = AppOpMode.ALLOW,
                    uidMode = null,
                    observed = true,
                    permissionRequested = true,
                ),
            ),
        ),
    )
}
