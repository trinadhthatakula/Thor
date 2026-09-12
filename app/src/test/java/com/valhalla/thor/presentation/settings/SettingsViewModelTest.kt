// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import com.valhalla.thor.domain.model.FontPreset
import com.valhalla.thor.domain.model.FreezeCandidate
import com.valhalla.thor.domain.model.FreezeState
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchRejection
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
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
import com.valhalla.thor.presentation.navigation.TaskNavigationRequest
import com.valhalla.thor.presentation.navigation.TaskNavigationTargets
import com.valhalla.thor.presentation.privilegeSweepResolver
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentity
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentityRegistry
import com.valhalla.thor.util.LocaleManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun `restore all opens provisional before resolving and accepts canonical task`() = runTest {
        val freezer = FakeFreezerRepository(setOf("z", "active", "a"))
        val preferences = FakePreferenceRepository()
        val controller = FakePrivilegeSweepController().apply {
            nextLaunchResult = PrivilegeSweepLaunchResult.Accepted(
                requestId = CANONICAL_REQUEST_ID,
                workId = WORK_ID,
                coalesced = true,
            )
        }
        val candidates = mapOf(
            "z" to FreezeCandidate(FreezeState.FROZEN),
            "active" to FreezeCandidate(FreezeState.ACTIVE),
            "a" to FreezeCandidate(FreezeState.FROZEN),
        )
        val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        val vm = viewModel(freezer, preferences, controller, candidates, targets)
        runCurrent()

        vm.unfreezeAll()

        val open = targets.requests.first() as TaskNavigationRequest.OpenProvisional
        assertEquals(
            ProvisionalTaskIdentity(
                queueKind = TaskQueueKind.PRIVILEGE,
                operationId = PrivilegeSweepOperation.UNFREEZE.name,
            ),
            open.identity,
        )
        assertTrue(controller.launched.isEmpty())

        runCurrent()

        val spec = controller.launched.single()
        assertEquals(open.taskId, controller.launchedRequestIds.single())
        assertEquals(PrivilegeSweepOperation.UNFREEZE, spec.operation)
        assertEquals(listOf("a", "z"), spec.packageNames)
        assertEquals(10, spec.userId)
        assertEquals(PrivilegeSweepSource.SETTINGS, spec.source)
        assertEquals(
            TaskNavigationRequest.Accepted(open.taskId, CANONICAL_REQUEST_ID),
            targets.requests.first(),
        )
    }

    @Test
    fun `restore all rejection rejects the exact provisional task`() = runTest {
        val freezer = FakeFreezerRepository(setOf("a"))
        val preferences = FakePreferenceRepository()
        val controller = FakePrivilegeSweepController().apply {
            nextLaunchResult = PrivilegeSweepLaunchResult.Rejected(
                PrivilegeSweepLaunchRejection.NoPrivilege
            )
        }
        val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        val vm = viewModel(
            freezer = freezer,
            preferences = preferences,
            controller = controller,
            candidates = mapOf("a" to FreezeCandidate(FreezeState.FROZEN)),
            targets = targets,
        )
        runCurrent()

        vm.unfreezeAll()
        val open = targets.requests.first() as TaskNavigationRequest.OpenProvisional
        runCurrent()

        assertEquals(open.taskId, controller.launchedRequestIds.single())
        assertEquals(
            TaskNavigationRequest.Rejected(open.taskId),
            targets.requests.first(),
        )
    }

    @Test
    fun `restore all launch exception rejects the exact provisional task`() = runTest {
        val controller = FakePrivilegeSweepController().apply {
            launchFailure = IllegalStateException("acceptance failed")
        }
        val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        val requests = mutableListOf<TaskNavigationRequest>()
        backgroundScope.launch(mainDispatcherRule.dispatcher) {
            targets.requests.collect(requests::add)
        }
        val vm = viewModel(
            freezer = FakeFreezerRepository(setOf("a")),
            preferences = FakePreferenceRepository(),
            controller = controller,
            candidates = mapOf("a" to FreezeCandidate(FreezeState.FROZEN)),
            targets = targets,
        )
        runCurrent()

        vm.unfreezeAll()
        runCurrent()

        assertEquals(2, requests.size)
        val open = requests[0] as TaskNavigationRequest.OpenProvisional
        assertEquals(TaskNavigationRequest.Rejected(open.taskId), requests[1])
    }

    @Test
    fun `legacy APK install setting is persisted through preferences`() = runTest {
        val preferences = FakePreferenceRepository()
        val vm = viewModel(
            freezer = FakeFreezerRepository(),
            preferences = preferences,
            controller = FakePrivilegeSweepController(),
            candidates = emptyMap(),
            targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry()),
        )
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.uiState.collect {} }
        runCurrent()
        assertEquals(false, vm.uiState.value.prefs.allowLegacyApkInstall)

        vm.setAllowLegacyApkInstall(true)
        runCurrent()

        assertTrue(vm.uiState.value.prefs.allowLegacyApkInstall)
    }

    @Test
    fun `font selection follows the saved preference and keeps other appearance settings`() = runTest {
        val initial = UserPreferences(themeMode = ThemeMode.DARK, useAmoled = true)
        val preferences = FakePreferenceRepository(initial)
        val vm = viewModel(
            freezer = FakeFreezerRepository(),
            preferences = preferences,
            controller = FakePrivilegeSweepController(),
            candidates = emptyMap(),
            targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry()),
        )
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.uiState.collect {} }
        runCurrent()

        vm.setFontPreset(FontPreset.SYSTEM)
        runCurrent()

        assertEquals(initial.copy(fontPreset = FontPreset.SYSTEM), vm.uiState.value.prefs)
        assertEquals(FontPreset.SYSTEM, preferences.userPreferences.first().fontPreset)
    }

    @Test
    fun `failed font write keeps the saved selection and reports the write failure`() = runTest {
        val preferences = FakePreferenceRepository(writesFail = true)
        val vm = viewModel(
            freezer = FakeFreezerRepository(),
            preferences = preferences,
            controller = FakePrivilegeSweepController(),
            candidates = emptyMap(),
            targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry()),
        )
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.uiState.collect {} }
        runCurrent()

        vm.setFontPreset(FontPreset.SYSTEM)
        runCurrent()

        assertEquals(FontPreset.ASGARD, vm.uiState.value.prefs.fontPreset)
        assertTrue(preferences.writeFailureLatched)
    }

    private fun viewModel(
        freezer: FakeFreezerRepository,
        preferences: FakePreferenceRepository,
        controller: FakePrivilegeSweepController,
        candidates: Map<String, FreezeCandidate>,
        targets: TaskNavigationTargets,
    ): SettingsViewModel = SettingsViewModel(
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
        taskNavigationTargets = targets,
        appShortcuts = FakeAppShortcutController(),
        anyFileOpenerController = object : AnyFileOpenerController {
            override suspend fun isEnabled(): Boolean = false
            override suspend fun setEnabled(enabled: Boolean) = Unit
        },
        ioDispatcher = mainDispatcherRule.dispatcher,
    )

    private companion object {
        val CANONICAL_REQUEST_ID: UUID =
            UUID.fromString("22222222-2222-2222-2222-222222222222")
        val WORK_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    }
}
