// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.FreezeProfileRepository
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
import kotlinx.coroutines.test.StandardTestDispatcher
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
    ): AppInfoDetailsViewModel {
        val manageApps = ManageAppUseCase(system, DefaultPackageOperationCoordinator())
        return AppInfoDetailsViewModel(
            appRepository = appRepository,
            systemRepository = system,
            manageAppUseCase = manageApps,
            freezeAppUseCase = FreezeAppUseCase(appRepository, manageApps),
            freezerRepository = freezer,
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
        viewModel.observeProfileMembership(appA.packageName)
        runCurrent()
        assertFalse(viewModel.uiState.value.profileMembership!!.isInFreezer)

        freezer.add(appA.packageName)
        runCurrent()
        assertTrue(viewModel.uiState.value.profileMembership!!.isInFreezer)
        freezer.remove(appA.packageName)
        runCurrent()
        assertFalse(viewModel.uiState.value.profileMembership!!.isInFreezer)
        assertEquals(listOf(1L), viewModel.uiState.value.profileMembership!!.profiles.map { it.id })
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
        viewModel.observeProfileMembership(appA.packageName)
        runCurrent()
        viewModel.observeProfileMembership(appA.packageName)
        runCurrent()
        assertEquals(1, subscriptions)

        viewModel.observeProfileMembership(appB.packageName)
        assertNull(viewModel.uiState.value.profileMembership)
        runCurrent()
        assertEquals(2, subscriptions)
        assertEquals(1, activeSubscriptions)
        assertEquals(appB.packageName, viewModel.uiState.value.profileMembership!!.packageName)
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
}
