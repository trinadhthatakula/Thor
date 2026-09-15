// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.FreezeProfileRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.usecase.FreezeAppUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.FakeAppRepository
import com.valhalla.thor.presentation.FakeAppShortcutController
import com.valhalla.thor.presentation.FakeFreezeProfileRepository
import com.valhalla.thor.presentation.FakeFreezerRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import com.valhalla.thor.presentation.userApp
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppInfoProfileMembershipViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val appA = userApp("com.example.a")
    private val appB = userApp("com.example.b")
    private val apps = FakeAppRepository(listOf(appA, appB))
    private val freezer = FakeFreezerRepository()
    private val system = FakeSystemRepository()
    private val profiles = FakeFreezeProfileRepository(
        listOf(
            FreezeProfile(1, "Games", listOf(appA.packageName)),
            FreezeProfile(2, "Work", listOf(appB.packageName)),
        ),
    )

    private fun viewModel(
        appRepository: AppRepository = apps,
        profileRepository: FreezeProfileRepository = profiles,
        freezerRepository: FreezerRepository = freezer,
    ): AppInfoDetailsViewModel {
        val manageApps = ManageAppUseCase(system, DefaultPackageOperationCoordinator())
        return AppInfoDetailsViewModel(
            appRepository = appRepository,
            systemRepository = system,
            manageAppUseCase = manageApps,
            freezeAppUseCase = FreezeAppUseCase(appRepository, manageApps),
            freezerRepository = freezerRepository,
            freezeProfileRepository = profileRepository,
            appShortcuts = FakeAppShortcutController(),
            preferenceRepository = FakePreferenceRepository(),
            ioDispatcher = main.dispatcher,
        )
    }

    @Test
    fun `collapsed info observes live names and membership without loading heavy details`() = runTest {
        var detailReads = 0
        val countedApps = object : AppRepository by apps {
            override suspend fun getDetailedAppInfo(packageName: String): DetailedAppInfo? {
                detailReads++
                return apps.getDetailedAppInfo(packageName)
            }
        }
        val viewModel = viewModel(appRepository = countedApps)
        viewModel.observeProfileMembership(appA.packageName)
        runCurrent()
        assertEquals(listOf("Games"), viewModel.uiState.value.profileMembership!!.profiles.map { it.name })

        profiles.update(1, "Renamed games", listOf(appA.packageName))
        val added = profiles.create("Travel", listOf(appA.packageName))
        runCurrent()
        assertEquals(
            listOf("Renamed games", "Travel"),
            viewModel.uiState.value.profileMembership!!.profiles.map { it.name },
        )
        profiles.delete(1)
        runCurrent()
        assertEquals(listOf(added), viewModel.uiState.value.profileMembership!!.profiles.map { it.id })
        profiles.update(added, "Travel", emptyList())
        runCurrent()
        assertTrue(viewModel.uiState.value.profileMembership!!.profiles.isEmpty())
        assertEquals(0, detailReads)
        assertTrue(system.calls.isEmpty())
    }

    @Test
    fun `watchlist changes update profiles-only status without a detail reload`() = runTest {
        val viewModel = viewModel()
        val observedStates = mutableListOf<AppInfoDetailsUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { observedStates += it }
        }
        viewModel.observeProfileMembership(appA.packageName)
        runCurrent()
        assertFalse(viewModel.uiState.value.profileMembership!!.isInFreezer)
        assertFalse(viewModel.uiState.value.isInFreezer)

        freezer.add(appA.packageName)
        runCurrent()
        assertTrue(viewModel.uiState.value.profileMembership!!.isInFreezer)
        assertTrue(viewModel.uiState.value.isInFreezer)
        freezer.remove(appA.packageName)
        runCurrent()
        assertFalse(viewModel.uiState.value.profileMembership!!.isInFreezer)
        assertFalse(viewModel.uiState.value.isInFreezer)
        assertEquals(listOf(1L), viewModel.uiState.value.profileMembership!!.profiles.map { it.id })
        assertTrue(observedStates.all { state ->
            state.profileMembership?.isInFreezer?.let { it == state.isInFreezer } ?: true
        })
        assertTrue(system.calls.isEmpty())
    }

    @Test
    fun `switching packages clears old membership and releases each prior subscription`() = runTest {
        var subscriptions = 0
        var activeSubscriptions = 0
        val countedProfiles = object : FreezeProfileRepository by profiles {
            override fun observeProfiles(): Flow<List<FreezeProfile>> = flow {
                subscriptions++
                activeSubscriptions++
                try {
                    emitAll(profiles.observeProfiles())
                } finally {
                    activeSubscriptions--
                }
            }
        }
        val viewModel = viewModel(profileRepository = countedProfiles)
        freezer.add(appA.packageName)
        viewModel.observeProfileMembership(appA.packageName)
        runCurrent()
        assertTrue(viewModel.uiState.value.isInFreezer)
        viewModel.observeProfileMembership(appA.packageName)
        runCurrent()
        assertEquals(1, subscriptions)

        viewModel.observeProfileMembership(appB.packageName)
        assertNull(viewModel.uiState.value.profileMembership)
        assertFalse(viewModel.uiState.value.isInFreezer)
        runCurrent()
        assertEquals(2, subscriptions)
        assertEquals(1, activeSubscriptions)
        assertEquals(appB.packageName, viewModel.uiState.value.profileMembership!!.packageName)
        assertFalse(viewModel.uiState.value.isInFreezer)
        assertEquals(listOf("Work"), viewModel.uiState.value.profileMembership!!.profiles.map { it.name })
        profiles.update(1, "Only A changed", listOf(appA.packageName))
        runCurrent()
        assertEquals(listOf("Work"), viewModel.uiState.value.profileMembership!!.profiles.map { it.name })

        viewModel.viewModelScope.cancel()
        runCurrent()
        assertEquals(0, activeSubscriptions)
    }

    @Test
    fun `late details for a previous package cannot replace current details or membership`() = runTest {
        val releaseA = CompletableDeferred<Unit>()
        val delayedApps = object : AppRepository by apps {
            override suspend fun getDetailedAppInfo(packageName: String): DetailedAppInfo =
                if (packageName == appA.packageName) withContext(NonCancellable) {
                    releaseA.await()
                    DetailedAppInfo(appInfo = appA)
                } else DetailedAppInfo(appInfo = appB)
        }
        val viewModel = viewModel(appRepository = delayedApps)
        viewModel.loadAppDetails(appA.packageName)
        runCurrent()
        viewModel.loadAppDetails(appB.packageName)
        runCurrent()
        assertEquals(appB.packageName, viewModel.uiState.value.detailedInfo!!.appInfo.packageName)

        releaseA.complete(Unit)
        advanceUntilIdle()
        assertEquals(appB.packageName, viewModel.uiState.value.detailedInfo!!.appInfo.packageName)
        assertEquals(appB.packageName, viewModel.uiState.value.profileMembership!!.packageName)
        assertEquals(listOf("Work"), viewModel.uiState.value.profileMembership!!.profiles.map { it.name })
    }

    @Test
    fun `delayed initial details cannot overwrite a newer live Freezer addition`() = runTest {
        val releaseDetails = CompletableDeferred<Unit>()
        val delayedApps = object : AppRepository by apps {
            override suspend fun getDetailedAppInfo(packageName: String): DetailedAppInfo {
                releaseDetails.await()
                return DetailedAppInfo(appInfo = appA)
            }
        }
        val viewModel = viewModel(appRepository = delayedApps)
        viewModel.loadAppDetails(appA.packageName)
        runCurrent()
        assertNull(viewModel.uiState.value.detailedInfo)

        freezer.add(appA.packageName)
        runCurrent()
        assertTrue(viewModel.uiState.value.isInFreezer)
        releaseDetails.complete(Unit)
        runCurrent()

        assertEquals(appA.packageName, viewModel.uiState.value.detailedInfo!!.appInfo.packageName)
        assertTrue(viewModel.uiState.value.profileMembership!!.isInFreezer)
        assertTrue(viewModel.uiState.value.isInFreezer)
    }

    @Test
    fun `delayed action refresh cannot overwrite a newer live Freezer removal`() = runTest {
        val releaseDetails = CompletableDeferred<Unit>()
        var delayDetails = false
        val delayedApps = object : AppRepository by apps {
            override suspend fun getDetailedAppInfo(packageName: String): DetailedAppInfo {
                if (delayDetails) releaseDetails.await()
                return DetailedAppInfo(appInfo = appA)
            }
        }
        freezer.add(appA.packageName)
        val viewModel = viewModel(appRepository = delayedApps)
        viewModel.loadAppDetails(appA.packageName)
        runCurrent()
        assertTrue(viewModel.uiState.value.isInFreezer)

        delayDetails = true
        viewModel.forceStopApp(appA.packageName)
        runCurrent()
        freezer.remove(appA.packageName)
        runCurrent()
        assertFalse(viewModel.uiState.value.isInFreezer)
        releaseDetails.complete(Unit)
        runCurrent()

        assertFalse(viewModel.uiState.value.profileMembership!!.isInFreezer)
        assertFalse(viewModel.uiState.value.isInFreezer)
    }

    @Test
    fun `freeze action keeps live membership when its one-shot read is stale and details are absent`() = runTest {
        val releaseRead = CompletableDeferred<Unit>()
        var holdFirstRead = true
        val delayedFreezer = object : FreezerRepository by freezer {
            override suspend fun contains(packageName: String): Boolean {
                val snapshot = freezer.contains(packageName)
                if (holdFirstRead) {
                    holdFirstRead = false
                    releaseRead.await()
                }
                return snapshot
            }
        }
        val viewModel = viewModel(freezerRepository = delayedFreezer)
        viewModel.observeProfileMembership(appA.packageName)
        runCurrent()
        viewModel.toggleFreezerState(appA.packageName, appA.appName, freeze = true)
        runCurrent()
        assertFalse(holdFirstRead)

        freezer.add(appA.packageName)
        runCurrent()
        assertTrue(viewModel.uiState.value.isInFreezer)
        releaseRead.complete(Unit)
        runCurrent()

        assertNull(viewModel.uiState.value.detailedInfo)
        assertNull(viewModel.uiState.value.freezerPrompt)
        assertTrue(viewModel.uiState.value.profileMembership!!.isInFreezer)
        assertTrue(viewModel.uiState.value.isInFreezer)
    }

    @Test
    fun `profile lookup failure hides the informational section without breaking app info`() = runTest {
        val failingProfiles = object : FreezeProfileRepository by profiles {
            override fun observeProfiles(): Flow<List<FreezeProfile>> = flow {
                throw IOException("private database details")
            }
        }
        apps.details[appA.packageName] = DetailedAppInfo(appInfo = appA)
        val viewModel = viewModel(profileRepository = failingProfiles)
        viewModel.loadAppDetails(appA.packageName)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.profileMembership)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(appA.packageName, viewModel.uiState.value.detailedInfo!!.appInfo.packageName)
    }

    @Test
    fun `same app can restart membership after retries exhaust without duplicating active reads`() = runTest {
        var fail = true
        var subscriptions = 0
        val recoveringProfiles = object : FreezeProfileRepository by profiles {
            override fun observeProfiles(): Flow<List<FreezeProfile>> = flow {
                subscriptions++
                if (fail) throw IOException("temporarily unavailable")
                emitAll(profiles.observeProfiles())
            }
        }
        val viewModel = viewModel(profileRepository = recoveringProfiles)
        viewModel.observeProfileMembership(appA.packageName)
        runCurrent()
        viewModel.observeProfileMembership(appA.packageName)
        runCurrent()
        assertEquals(1, subscriptions)
        advanceUntilIdle()
        assertEquals(3, subscriptions)
        assertNull(viewModel.uiState.value.profileMembership)

        fail = false
        viewModel.observeProfileMembership(appA.packageName)
        runCurrent()
        viewModel.observeProfileMembership(appA.packageName)
        runCurrent()

        assertEquals(4, subscriptions)
        assertEquals(listOf("Games"), viewModel.uiState.value.profileMembership!!.profiles.map { it.name })
        freezer.add(appA.packageName)
        runCurrent()
        assertTrue(viewModel.uiState.value.isInFreezer)
        assertTrue(viewModel.uiState.value.profileMembership!!.isInFreezer)
    }

    @Test
    fun `reloading same app details restarts a terminally failed membership read`() = runTest {
        var fail = true
        var subscriptions = 0
        val recoveringProfiles = object : FreezeProfileRepository by profiles {
            override fun observeProfiles(): Flow<List<FreezeProfile>> = flow {
                subscriptions++
                if (fail) throw IOException("temporarily unavailable")
                emitAll(profiles.observeProfiles())
            }
        }
        apps.details[appA.packageName] = DetailedAppInfo(appInfo = appA)
        val viewModel = viewModel(profileRepository = recoveringProfiles)
        viewModel.loadAppDetails(appA.packageName)
        advanceUntilIdle()
        assertEquals(3, subscriptions)
        assertNull(viewModel.uiState.value.profileMembership)

        fail = false
        viewModel.loadAppDetails(appA.packageName)
        runCurrent()

        assertEquals(4, subscriptions)
        assertEquals(appA.packageName, viewModel.uiState.value.detailedInfo!!.appInfo.packageName)
        assertEquals(listOf("Games"), viewModel.uiState.value.profileMembership!!.profiles.map { it.name })
        assertNull(viewModel.uiState.value.errorMessage)
    }
}
