// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import com.valhalla.thor.R
import com.valhalla.thor.data.source.local.dhizuku.DhizukuReflector
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.presentation.FakeContext
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.UiTextException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DhizukuCapabilityTest {
    @Test
    fun `force stop refuses with localized text without issuing a privileged call`() = runTest {
        val context = FakeContext(File("."))
        val gateway = DhizukuSystemGateway(
            context, DhizukuReflector(context), FakePreferenceRepository(), Dispatchers.Unconfined,
        )
        // No Dhizuku runtime or Android service is initialized. Unsupported operations must not
        // need either, including old queued work that bypasses the UI capability gate.
        val result = gateway.forceStopApp("com.example.target", PrivilegeExecutionContext())
        assertEquals(
            UiText.StringResource(R.string.force_stop_unsupported_dhizuku),
            (result.exceptionOrNull() as? UiTextException)?.uiText,
        )
    }
}
