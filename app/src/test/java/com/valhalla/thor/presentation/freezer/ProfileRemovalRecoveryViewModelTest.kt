// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import com.valhalla.thor.R
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.FreezeProfileRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.usecase.FreezeAppUseCase
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.FakeAppRepository
import com.valhalla.thor.presentation.FakeAppShortcutController
import com.valhalla.thor.presentation.FakeFreezeProfileRepository
import com.valhalla.thor.presentation.FakeFreezerRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakePrivilegeStateProvider
import com.valhalla.thor.presentation.FakePrivilegeSweepController
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import com.valhalla.thor.presentation.navigation.TaskNavigationTargets
import com.valhalla.thor.presentation.privilegeSweepResolver
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentityRegistry
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRemovalRecoveryViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    private val apps = FakeAppRepository()
    private val profiles = FakeFreezeProfileRepository()
    private val freezer = FakeFreezerRepository()
    private val trace = mutableListOf<String>()
    private val system = FakeSystemRepository(trace)

    @Test
    fun `editor only warns for frozen apps losing their last recoverable membership and cancel preserves draft`() = runTest {
        apps.apps.value = listOf(frozen("sole"), frozen("shared"), frozen("watched"), frozen("keep"), AppInfo(packageName = "active"))
        val id = profiles.create("Before", listOf("sole", "shared", "watched", "keep", "active"))
        profiles.create("Other", listOf("shared"))
        freezer.add("watched")
        val vm = viewModel()

        vm.updateProfile(9, id, "After", listOf("keep"))
        runCurrent()

        assertEquals(listOf("sole"), vm.recoveryPackages())
        assertEquals("Before", profiles.observeProfiles().first().first { it.id == id }.name)
        assertEquals(5, profiles.packagesOf(id).size)
        assertTrue(system.calls.isEmpty())
        vm.dismissProfileRemovalRecovery()
        runCurrent()
        assertNull(vm.uiState.value.profileRemovalRecovery)
        assertEquals(5, profiles.packagesOf(id).size)
        assertEquals(listOf("watched"), freezer.getAllPackageNames())
    }

    @Test
    fun `unknown stale membership can be explicitly removed without any privileged call`() = runTest {
        val id = profiles.create("Stale", listOf("uninstalled"))
        val vm = viewModel()
        vm.deleteProfile(id)
        runCurrent()

        assertNull(vm.uiState.value.profileRemovalRecovery!!.apps.single().app)
        assertEquals(listOf("uninstalled"), profiles.packagesOf(id))
        vm.confirmProfileRemovalRecovery(unfreeze = false)
        runCurrent()

        assertTrue(profiles.observeProfiles().first().isEmpty())
        assertTrue(system.calls.isEmpty())
        assertTrue(freezer.getAllPackageNames().isEmpty())
    }

    @Test
    fun `failed unknown restore keeps profile metadata and offers explicit removal afterward`() = runTest {
        val id = profiles.create("Stale", listOf("unknown"))
        system.failWith("setAppSuspended:unknown:false", IllegalStateException("no privilege"))
        val vm = viewModel()
        vm.updateProfile(7, id, "Renamed", emptyList())
        runCurrent()
        vm.confirmProfileRemovalRecovery(unfreeze = true)
        runCurrent()

        assertEquals(listOf("setAppSuspended:unknown:false"), system.calls)
        assertEquals("Stale", profiles.observeProfiles().first().single().name)
        assertEquals(listOf("unknown"), profiles.packagesOf(id))
        assertEquals(UiText.StringResource(R.string.profile_removal_restore_failed), vm.uiState.value.profileRemovalRecovery?.error)
        assertFalse(vm.uiState.value.profileSaveInFlight)

        vm.confirmProfileRemovalRecovery(unfreeze = false)
        runCurrent()
        assertEquals("Renamed", profiles.observeProfiles().first().single().name)
        assertTrue(profiles.packagesOf(id).isEmpty())
        assertNull(vm.uiState.value.profileRemovalRecovery)
    }

    @Test
    fun `restoration finishes and is checked before the editor metadata is written`() = runTest {
        apps.apps.value = listOf(frozen("app").copy(isSuspended = true), AppInfo(packageName = "keep"))
        val id = profiles.create("Before", listOf("app", "keep"))
        val recording = object : FreezeProfileRepository by profiles {
            override suspend fun update(profileId: Long, name: String, packageNames: List<String>) {
                trace += "write:$name"
                profiles.update(profileId, name, packageNames)
            }
        }
        system.onCall = { call ->
            if (call == "setAppDisabled:app:false") {
                apps.apps.value = listOf(AppInfo(packageName = "app"), AppInfo(packageName = "keep"))
            }
        }
        val vm = viewModel(profileRepository = recording)
        vm.updateProfile(12, id, "After", listOf("keep"))
        runCurrent()
        vm.confirmProfileRemovalRecovery(unfreeze = true)
        runCurrent()

        assertEquals(listOf("setAppSuspended:app:false", "setAppDisabled:app:false", "write:After"), trace)
        assertEquals(listOf("keep"), profiles.packagesOf(id))
        assertNull(vm.uiState.value.profileRemovalRecovery)
        assertTrue(freezer.getAllPackageNames().isEmpty())
    }

    @Test
    fun `every confirm rereads profiles watchlist and app state and requires consent for newly affected packages`() = runTest {
        apps.apps.value = listOf(frozen("a"), frozen("b"), frozen("c"), AppInfo(packageName = "d"))
        val id = profiles.create("Selected", listOf("a", "b", "c", "d"))
        val covering = profiles.create("Covering", listOf("c"))
        freezer.add("b")
        val vm = viewModel()
        vm.deleteProfile(id)
        runCurrent()
        assertEquals(listOf("a"), vm.recoveryPackages())

        profiles.delete(covering)
        freezer.remove("b")
        apps.apps.value = listOf(frozen("a"), frozen("b"), frozen("c"), frozen("d"))
        vm.confirmProfileRemovalRecovery(unfreeze = false)
        runCurrent()

        assertEquals(listOf("a", "b", "c", "d"), vm.recoveryPackages())
        assertEquals(4, profiles.packagesOf(id).size)
        assertTrue(system.calls.isEmpty())
        vm.confirmProfileRemovalRecovery(unfreeze = false)
        runCurrent()
        assertTrue(profiles.observeProfiles().first().isEmpty())
    }

    @Test
    fun `legacy user-uninstalled app is restored even when its enabled flag is true`() = runTest {
        apps.apps.value = listOf(AppInfo(packageName = "legacy", isSystem = true, isInstalled = false, enabled = true))
        val id = profiles.create("Legacy", listOf("legacy"))
        system.onCall = { call ->
            if (call == "setAppDisabled:legacy:false") apps.apps.value = listOf(AppInfo(packageName = "legacy"))
        }
        val vm = viewModel()
        vm.deleteProfile(id)
        runCurrent()
        assertEquals(listOf("legacy"), vm.recoveryPackages())

        vm.confirmProfileRemovalRecovery(unfreeze = true)
        runCurrent()

        assertEquals(listOf("setAppSuspended:legacy:false", "setAppDisabled:legacy:false"), system.calls)
        assertTrue(profiles.observeProfiles().first().isEmpty())
    }

    @Test
    fun `shrinking risk set does not require a second approval for acknowledged apps`() = runTest {
        apps.apps.value = listOf(frozen("a"), frozen("b"))
        val id = profiles.create("Selected", listOf("a", "b"))
        val vm = viewModel()
        vm.deleteProfile(id)
        runCurrent()
        assertEquals(listOf("a", "b"), vm.recoveryPackages())
        apps.apps.value = listOf(frozen("a"), AppInfo(packageName = "b"))

        vm.confirmProfileRemovalRecovery(unfreeze = false)
        runCurrent()

        assertTrue(profiles.observeProfiles().first().isEmpty())
        assertTrue(system.calls.isEmpty())
    }

    @Test
    fun `new watchlist membership removes the risk before confirm without unfreezing`() = runTest {
        apps.apps.value = listOf(frozen("a"))
        val id = profiles.create("Selected", listOf("a"))
        val vm = viewModel()
        vm.deleteProfile(id)
        runCurrent()
        assertEquals(listOf("a"), vm.recoveryPackages())

        freezer.add("a")
        vm.confirmProfileRemovalRecovery(unfreeze = true)
        runCurrent()

        assertTrue(profiles.observeProfiles().first().isEmpty())
        assertEquals(listOf("a"), freezer.getAllPackageNames())
        assertTrue(system.calls.isEmpty())
    }

    @Test
    fun `metadata write failure keeps recovery and retries the same retained edit`() = runTest {
        val id = profiles.create("Before", listOf("unknown"))
        val vm = viewModel()
        vm.updateProfile(6, id, "After", emptyList())
        runCurrent()
        profiles.writeFailure = IllegalStateException("disk full")
        vm.confirmProfileRemovalRecovery(unfreeze = false)
        runCurrent()

        assertEquals(UiText.StringResource(R.string.profile_removal_write_failed), vm.uiState.value.profileRemovalRecovery?.error)
        assertEquals("Before", profiles.observeProfiles().first().single().name)
        assertEquals(listOf("unknown"), profiles.packagesOf(id))
        profiles.writeFailure = null
        vm.confirmProfileRemovalRecovery(unfreeze = false)
        runCurrent()
        assertEquals("After", profiles.observeProfiles().first().single().name)
        assertTrue(profiles.packagesOf(id).isEmpty())
    }

    @Test
    fun `duplicate confirms writes and dismissals are ignored while a removal write is pending`() = runTest {
        val id = profiles.create("Selected", listOf("unknown"))
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        val blocking = object : FreezeProfileRepository by profiles {
            override suspend fun delete(profileId: Long) {
                writes++
                gate.await()
                profiles.delete(profileId)
            }
        }
        val vm = viewModel(profileRepository = blocking)
        vm.deleteProfile(id)
        runCurrent()
        vm.confirmProfileRemovalRecovery(unfreeze = false)
        runCurrent()
        assertTrue(vm.uiState.value.profileSaveInFlight)

        vm.confirmProfileRemovalRecovery(unfreeze = false)
        vm.dismissProfileRemovalRecovery()
        vm.deleteProfile(id)
        vm.createProfile(20, "Unrelated", emptyList())
        runCurrent()
        assertNotNull(vm.uiState.value.profileRemovalRecovery)
        assertEquals(1, writes)
        gate.complete(Unit)
        runCurrent()
        assertTrue(profiles.observeProfiles().first().isEmpty())
        assertNull(vm.uiState.value.profileRemovalRecovery)
        assertFalse(vm.uiState.value.profileSaveInFlight)
    }

    @Test
    fun `fresh lookup failure retains metadata and allows a checked retry`() = runTest {
        val id = profiles.create("Selected", listOf("unknown"))
        var failLookup = false
        val reading = object : AppRepository by apps {
            override suspend fun getAppDetails(packageName: String): AppInfo? {
                if (failLookup) error("package manager disconnected")
                return apps.getAppDetails(packageName)
            }
        }
        val vm = viewModel(appRepository = reading)
        vm.deleteProfile(id)
        runCurrent()
        failLookup = true
        vm.confirmProfileRemovalRecovery(unfreeze = false)
        runCurrent()
        assertEquals(UiText.StringResource(R.string.profile_removal_check_failed), vm.uiState.value.profileRemovalRecovery?.error)
        assertEquals(listOf("unknown"), profiles.packagesOf(id))
        failLookup = false
        vm.confirmProfileRemovalRecovery(unfreeze = false)
        runCurrent()
        assertTrue(profiles.observeProfiles().first().isEmpty())
    }

    private fun FreezerViewModel.recoveryPackages() =
        uiState.value.profileRemovalRecovery?.apps?.map { it.packageName }

    private fun frozen(packageName: String) = AppInfo(packageName = packageName, enabled = false)

    private fun viewModel(
        profileRepository: FreezeProfileRepository = profiles,
        appRepository: AppRepository = apps,
        watchlist: FreezerRepository = freezer,
    ): FreezerViewModel {
        val prefs = FakePreferenceRepository()
        val controller = FakePrivilegeSweepController()
        val navigation = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        val manage = ManageAppUseCase(system, DefaultPackageOperationCoordinator())
        return FreezerViewModel(
            freezerRepository = watchlist,
            freezeProfileRepository = profileRepository,
            profileSubmission = ProfileSubmissionCoordinator(
                privilegeSweepResolver(watchlist, profileRepository, prefs), controller, navigation, main.dispatcher,
            ),
            sweepController = controller,
            taskNavigationTargets = navigation,
            getInstalledAppsUseCase = GetInstalledAppsUseCase(appRepository),
            appRepository = appRepository,
            manageAppUseCase = manage,
            freezeAppUseCase = FreezeAppUseCase(appRepository, manage),
            privilege = FakePrivilegeStateProvider(),
            preferenceRepository = prefs,
            appShortcuts = FakeAppShortcutController(),
            defaultDispatcher = main.dispatcher,
            ioDispatcher = main.dispatcher,
        )
    }
}
