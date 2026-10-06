// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.presentation.settingseditor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.SettingValue
import com.valhalla.thor.domain.model.SettingsEditObservation
import com.valhalla.thor.domain.model.SettingsEditOutcome
import com.valhalla.thor.domain.model.SettingsEditRecord
import com.valhalla.thor.domain.model.SettingsEditorView
import java.text.DateFormat
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsEditorHistoryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun checkCurrentValueRequiresConsentProviderIdleStateAndRecordedUser() {
        var controls by mutableStateOf(Controls())
        var record by mutableStateOf(record())
        val checked = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsEditHistoryCard(record, controls.consent, controls.mode, controls.busy,
                        controls.userId, true, { checked += it }, {})
                }
            }
        }
        val check = rule.activity.getString(R.string.sett_check_current_value)
        val blocked = listOf(
            Controls(consent = false), Controls(consent = null), Controls(mode = null),
            Controls(mode = PrivilegeMode.NONE), Controls(mode = PrivilegeMode.DHIZUKU),
            Controls(busy = true), Controls(userId = 11),
        )
        for (state in blocked) {
            rule.runOnIdle { controls = state }
            rule.onNodeWithText(check).performScrollTo().assertIsNotEnabled()
        }
        for (mode in listOf(PrivilegeMode.ROOT, PrivilegeMode.SHIZUKU)) {
            rule.runOnIdle { controls = Controls(mode = mode) }
            for (outcome in listOf(SettingsEditOutcome.PENDING, SettingsEditOutcome.UNKNOWN, SettingsEditOutcome.UNCONFIRMED)) {
                rule.runOnIdle { record = record.copy(outcome = outcome) }
                rule.onNodeWithText(check).performScrollTo().assertIsEnabled().performClick()
            }
        }
        rule.runOnIdle { assertEquals(List(6) { record.id }, checked) }
        for (outcome in listOf(SettingsEditOutcome.VERIFIED, SettingsEditOutcome.REJECTED, SettingsEditOutcome.CONFLICT)) {
            rule.runOnIdle { record = record.copy(outcome = outcome) }
            rule.onNodeWithText(check).assertDoesNotExist()
        }
    }

    @Test fun matchingObservationKeepsUnconfirmedOutcomeAndUndoDisabled() {
        val desired = SettingValue(true, "literal null\nsecond line")
        val observedAt = 1_700_000_050_000L
        val record = record().copy(
            outcome = SettingsEditOutcome.UNCONFIRMED,
            desired = desired,
            observation = SettingsEditObservation(desired, PrivilegeMode.SHIZUKU, observedAt),
        )
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsEditHistoryCard(record, true, PrivilegeMode.ROOT, false, 10, true, {}, {})
                }
            }
        }
        val activity = rule.activity
        rule.onNodeWithText(activity.getString(R.string.sett_execution_unconfirmed)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(activity.getString(R.string.sett_verified)).assertDoesNotExist()
        rule.onNodeWithText(activity.getString(R.string.sett_observed_value) + "\n" + desired.value).performScrollTo().assertIsDisplayed()
        val localizedTime = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT,
            activity.resources.configuration.locales[0]).format(Date(observedAt))
        rule.onNodeWithText(activity.getString(R.string.sett_observation_source, "SHIZUKU", localizedTime)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(activity.getString(R.string.sett_observation_note)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(activity.getString(R.string.sett_user_scope, 10)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(activity.getString(R.string.sett_check_current_value)).performScrollTo().assertIsEnabled()
        rule.onNodeWithText(activity.getString(R.string.sett_undo)).performScrollTo().assertIsNotEnabled()
    }

    @Test fun globalObservationPreservesAbsentNullEmptyAndLiteralValues() {
        var record by mutableStateOf(record().copy(view = SettingsEditorView.GLOBAL, userId = 0))
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsEditHistoryCard(record, true, PrivilegeMode.SHIZUKU, false, 12, true, {}, {})
                }
            }
        }
        val activity = rule.activity
        val values = listOf(
            SettingValue.ABSENT to activity.getString(R.string.sett_absent),
            SettingValue(true, null) to activity.getString(R.string.sett_null),
            SettingValue(true, "") to activity.getString(R.string.sett_empty_value),
            SettingValue(true, "null") to "null",
        )
        for ((value, expected) in values) {
            rule.runOnIdle { record = record.copy(observation = SettingsEditObservation(value, PrivilegeMode.ROOT, 1_700_000_050_000L)) }
            rule.onNodeWithText(activity.getString(R.string.sett_observed_value) + "\n" + expected).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(activity.getString(R.string.sett_shared_scope)).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(activity.getString(R.string.sett_check_current_value)).performScrollTo().assertIsEnabled()
            rule.onNodeWithText(activity.getString(R.string.sett_undo)).performScrollTo().assertIsNotEnabled()
        }
    }

    private fun record() = SettingsEditRecord(
        id = "history-read-check",
        view = SettingsEditorView.SECURE,
        userId = 10,
        key = "test.ui.setting",
        before = SettingValue(true, "before"),
        desired = SettingValue(true, "after"),
        provider = PrivilegeMode.ROOT,
        timestamp = 1_700_000_000_000L,
        outcome = SettingsEditOutcome.UNKNOWN,
    )

    private data class Controls(
        val consent: Boolean? = true,
        val mode: PrivilegeMode? = PrivilegeMode.ROOT,
        val busy: Boolean = false,
        val userId: Int = 10,
    )
}
