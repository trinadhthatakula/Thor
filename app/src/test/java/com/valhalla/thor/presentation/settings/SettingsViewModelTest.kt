// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.FreezeCandidate
import com.valhalla.thor.domain.model.FreezeState
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.repository.AnyFileOpenerController
import com.valhalla.thor.presentation.FakeAppShortcutController
import com.valhalla.thor.presentation.FakeAuthCapability
import com.valhalla.thor.presentation.FakeContext
import com.valhalla.thor.presentation.FakeFreezeProfileRepository
import com.valhalla.thor.presentation.FakeFreezerRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakePrivilegeSweepController
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import com.valhalla.thor.presentation.privilegeSweepResolver
import com.valhalla.thor.util.LocaleManager
import com.valhalla.thor.util.UiText
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `restore all snapshots every frozen package for current user and acknowledges queued`() = runTest {
        val freezer = FakeFreezerRepository(setOf("z", "active", "a"))
        val preferences = FakePreferenceRepository()
        val controller = FakePrivilegeSweepController()
        val candidates = mapOf(
            "z" to FreezeCandidate(FreezeState.FROZEN),
            "active" to FreezeCandidate(FreezeState.ACTIVE),
            "a" to FreezeCandidate(FreezeState.FROZEN),
        )
        val vm = SettingsViewModel(
            preferenceRepository = preferences,
            systemRepository = FakeSystemRepository(),
            biometricHelper = FakeAuthCapability(),
            localeManager = LocaleManager(FakeContext(File("/tmp"))),
            sweepResolver = privilegeSweepResolver(
                freezerRepository = freezer,
                freezeProfileRepository = FakeFreezeProfileRepository(),
                preferenceRepository = preferences,
                candidates = candidates,
                userId = 10,
            ),
            sweepController = controller,
            appShortcuts = FakeAppShortcutController(),
            anyFileOpenerController = object : AnyFileOpenerController {
                override suspend fun isEnabled(): Boolean = false
                override suspend fun setEnabled(enabled: Boolean) = Unit
            },
            ioDispatcher = mainDispatcherRule.dispatcher,
        )
        val events = mutableListOf<UiText>()
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.events.collect { events += it } }
        runCurrent()

        vm.unfreezeAll()
        runCurrent()

        val spec = controller.launched.single()
        assertEquals(PrivilegeSweepOperation.UNFREEZE, spec.operation)
        assertEquals(listOf("a", "z"), spec.packageNames)
        assertEquals(10, spec.userId)
        assertEquals(PrivilegeSweepSource.SETTINGS, spec.source)
        assertEquals(listOf(UiText.StringResource(R.string.sweep_queued)), events)
    }
}
