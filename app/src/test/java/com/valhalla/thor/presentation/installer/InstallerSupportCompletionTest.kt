// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.model.AppMetadata
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerSupportCompletionTest {
    @Test
    fun `replayed success does not count as an observed installation`() {
        val completion = InstallerSupportCompletion()

        assertFalse(completion.observe(InstallState.Success))
        assertFalse(completion.observe(InstallState.Success))
    }

    @Test
    fun `observed installation qualifies only once it succeeds`() {
        val completion = InstallerSupportCompletion()

        assertFalse(completion.observe(InstallState.Installing(0f)))
        assertFalse(completion.observe(InstallState.Installing(0.8f)))
        assertTrue(completion.observe(InstallState.Success))
        assertTrue(completion.observe(InstallState.Success))
    }

    @Test
    fun `fast delivered installation is recognized before presentation conflation`() = runTest {
        val lastPresented = flowOf(
            InstallState.Installing(1f),
            InstallState.Success,
        ).observeSupportCompletion().conflate().last()

        assertTrue(lastPresented.completedWhileObserved)
    }

    @Test
    fun `system confirmation followed by success qualifies`() {
        val completion = InstallerSupportCompletion()

        assertFalse(completion.observe(InstallState.UserConfirmationRequired))
        assertTrue(completion.observe(InstallState.Success))
    }

    @Test
    fun `reset failure and new parsing each discard earlier completion`() {
        val resetStates = listOf(
            InstallState.Idle,
            InstallState.Parsing,
            InstallState.ReadyToInstall(
                AppMetadata("Example", "app.example", "1", 1L, null),
                isUpdate = false,
            ),
            InstallState.Error(UiText.DynamicString("Install failed")),
        )

        resetStates.forEach { reset ->
            val completion = InstallerSupportCompletion()
            completion.observe(InstallState.Installing(1f))
            assertTrue(completion.observe(InstallState.Success))

            assertFalse("Reset state: $reset", completion.observe(reset))
            assertFalse("Stale success after $reset", completion.observe(InstallState.Success))
            completion.observe(InstallState.Installing(0f))
            assertTrue("New installation after $reset", completion.observe(InstallState.Success))
        }
    }

    @Test
    fun `failed installation never qualifies`() {
        val completion = InstallerSupportCompletion()

        completion.observe(InstallState.Installing(0f))
        assertFalse(completion.observe(InstallState.Error(UiText.DynamicString("Install failed"))))
        assertFalse(completion.observe(InstallState.Success))
    }
}
