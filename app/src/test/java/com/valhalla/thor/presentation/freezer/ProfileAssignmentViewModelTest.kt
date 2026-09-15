// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.ProfileAssignmentResult
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.FreezeProfileRepository
import com.valhalla.thor.presentation.FakeAppRepository
import com.valhalla.thor.presentation.FakeFreezeProfileRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import com.valhalla.thor.presentation.userApp
import com.valhalla.thor.util.UiText
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileAssignmentViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val selected = userApp("com.example.selected", appName = "Selected")
    private val existing = "com.example.existing"
    private val profiles = RecordingProfiles(
        FakeFreezeProfileRepository(listOf(FreezeProfile(1, "Games", listOf(existing)))),
    )
    private val apps = FakeAppRepository(listOf(selected))

    private fun viewModel(
        appRepository: AppRepository = apps,
        profileRepository: FreezeProfileRepository = profiles,
    ) = ProfileAssignmentViewModel(profileRepository, appRepository, main.dispatcher)

    private fun TestScope.events(viewModel: ProfileAssignmentViewModel): List<ProfileAssignmentEvent> {
        val events = mutableListOf<ProfileAssignmentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }
        return events
    }

    @Test
    fun `open snapshots unique apps and cancel never writes or emits success`() = runTest {
        val viewModel = viewModel()
        val events = events(viewModel)
        runCurrent()
        val sourceSelection = mutableListOf(selected, selected.copy(appName = "Duplicate"))
        viewModel.open(sourceSelection)
        viewModel.toggleProfile(1)
        sourceSelection.clear()

        assertEquals(listOf(selected), viewModel.uiState.value.selectedApps)
        viewModel.dismiss()
        viewModel.submit()
        runCurrent()

        assertFalse(viewModel.uiState.value.isOpen)
        assertTrue(events.isEmpty())
        assertTrue(profiles.requests.isEmpty())
        assertEquals(listOf(existing), profiles.packagesOf(1))
    }

    @Test
    fun `profile names and sizes stay live and deleted selections are pruned`() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.open(listOf(selected))
        viewModel.toggleProfile(1)
        profiles.update(1, "Renamed", listOf(existing, "com.example.other"))
        runCurrent()

        assertEquals("Renamed", viewModel.uiState.value.profiles.single().name)
        assertEquals(2, viewModel.uiState.value.profiles.single().size)
        assertEquals(setOf(1L), viewModel.uiState.value.selectedProfileIds)

        profiles.delete(1)
        runCurrent()
        viewModel.submit()

        assertTrue(viewModel.uiState.value.isOpen)
        assertTrue(viewModel.uiState.value.profiles.isEmpty())
        assertTrue(viewModel.uiState.value.selectedProfileIds.isEmpty())
        assertEquals(listOf(selected), viewModel.uiState.value.selectedApps)
        assertEquals(
            UiText.StringResource(R.string.profile_assignment_profiles_changed),
            viewModel.uiState.value.error,
        )
        assertTrue(profiles.requests.isEmpty())
    }

    @Test
    fun `assignment revalidates apps preserves recoverable hidden apps and reports real counts`() = runTest {
        val hidden = userApp("com.example.hidden").copy(isInstalled = false, enabled = false)
        val gone = userApp("com.example.gone")
        val blockedSnapshot = userApp("com.example.blocked")
        val blockedNow = blockedSnapshot.copy(isSystem = true, bloatRecommendation = "Unsafe")
        val secondProfile = profiles.create("Tools", listOf(selected.packageName))
        apps.apps.value = listOf(selected, hidden, blockedNow)
        val viewModel = viewModel()
        val events = events(viewModel)
        runCurrent()
        viewModel.open(listOf(selected, selected, hidden, gone, blockedSnapshot))
        viewModel.toggleProfile(1)
        viewModel.toggleProfile(secondProfile)
        profiles.update(1, "Current name", listOf(existing, selected.packageName))
        runCurrent()

        viewModel.submit()
        advanceUntilIdle()

        val assigned = events.single() as ProfileAssignmentEvent.Assigned
        assertEquals(2, assigned.result.addedCount)
        assertEquals(2, assigned.result.alreadyPresentCount)
        assertEquals(2, assigned.skippedCount)
        assertEquals("Current name", assigned.result.profiles.first().profileName)
        assertEquals(setOf(selected.packageName, hidden.packageName), profiles.requests.single().second)
        assertEquals(setOf(existing, selected.packageName, hidden.packageName), profiles.packagesOf(1).toSet())
        assertFalse(viewModel.uiState.value.isOpen)
    }

    @Test
    fun `lookup failure is localized and keeps the draft without treating it as absence`() = runTest {
        val failing = object : AppRepository by apps {
            override suspend fun getAppDetails(packageName: String): AppInfo? =
                throw IOException("private package manager diagnostics")
        }
        val viewModel = viewModel(appRepository = failing)
        val events = events(viewModel)
        runCurrent()
        viewModel.open(listOf(selected))
        viewModel.toggleProfile(1)
        viewModel.submit()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isOpen)
        assertEquals(listOf(selected), viewModel.uiState.value.selectedApps)
        assertEquals(setOf(1L), viewModel.uiState.value.selectedProfileIds)
        assertEquals(UiText.StringResource(R.string.profile_assignment_save_failed), viewModel.uiState.value.error)
        assertTrue(events.isEmpty())
        assertTrue(profiles.requests.isEmpty())
    }

    @Test
    fun `failed write keeps selections and retry emits success only after persistence`() = runTest {
        val viewModel = viewModel()
        val events = events(viewModel)
        profiles.delegate.writeFailure = IOException("private database diagnostics")
        runCurrent()
        viewModel.open(listOf(selected))
        viewModel.toggleProfile(1)
        viewModel.submit()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isOpen)
        assertFalse(viewModel.uiState.value.isSaving)
        assertEquals(listOf(selected), viewModel.uiState.value.selectedApps)
        assertEquals(setOf(1L), viewModel.uiState.value.selectedProfileIds)
        assertEquals(UiText.StringResource(R.string.profile_assignment_save_failed), viewModel.uiState.value.error)
        assertTrue(events.isEmpty())
        assertEquals(listOf(existing), profiles.packagesOf(1))

        profiles.delegate.writeFailure = null
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(1, events.size)
        assertTrue(selected.packageName in profiles.packagesOf(1))
        assertFalse(viewModel.uiState.value.isOpen)
    }

    @Test
    fun `duplicate submit and draft changes are ignored throughout a pending write`() = runTest {
        val release = CompletableDeferred<Unit>()
        profiles.beforeWrite = { release.await() }
        val viewModel = viewModel()
        runCurrent()
        viewModel.open(listOf(selected))
        viewModel.toggleProfile(1)
        viewModel.submit()
        viewModel.submit()
        runCurrent()

        viewModel.dismiss()
        viewModel.open(listOf(userApp("com.example.replacement")))
        viewModel.toggleProfile(1)
        viewModel.submit()
        runCurrent()
        assertTrue(viewModel.uiState.value.isSaving)
        assertTrue(viewModel.uiState.value.isOpen)
        assertEquals(listOf(selected), viewModel.uiState.value.selectedApps)
        assertEquals(setOf(1L), viewModel.uiState.value.selectedProfileIds)
        assertEquals(1, profiles.requests.size)

        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSaving)
        // No collector existed at commit time: the Channel must retain this one-off result.
        val assigned = async { viewModel.events.first() }
        runCurrent()
        assertEquals(1, (assigned.await() as ProfileAssignmentEvent.Assigned).result.addedCount)
    }

    @Test
    fun `expert assignment requires shown confirmation and dismissing it preserves the draft`() = runTest {
        val expert = selected.copy(isSystem = true, bloatRecommendation = "Expert")
        apps.apps.value = listOf(expert)
        val viewModel = viewModel()
        val events = events(viewModel)
        runCurrent()
        viewModel.open(listOf(selected))
        viewModel.toggleProfile(1)
        viewModel.submit(confirmExperts = true)
        advanceUntilIdle()

        assertEquals(listOf(expert), viewModel.uiState.value.expertApps)
        assertTrue(profiles.requests.isEmpty())
        viewModel.dismissExpertConfirmation()
        assertTrue(viewModel.uiState.value.isOpen)
        assertEquals(setOf(1L), viewModel.uiState.value.selectedProfileIds)
        assertTrue(viewModel.uiState.value.expertApps.isEmpty())

        viewModel.submit()
        advanceUntilIdle()
        viewModel.submit(confirmExperts = true)
        viewModel.submit(confirmExperts = true)
        advanceUntilIdle()
        assertEquals(1, profiles.requests.size)
        assertEquals(1, events.size)
    }

    @Test
    fun `a newly expert app after confirmation is shown before any write`() = runTest {
        val firstExpert = selected.copy(isSystem = true, bloatRecommendation = "Expert")
        val initiallyNormal = userApp("com.example.changed")
        val nowExpert = initiallyNormal.copy(isSystem = true, bloatRecommendation = "Expert")
        apps.apps.value = listOf(firstExpert, initiallyNormal)
        val viewModel = viewModel()
        runCurrent()
        viewModel.open(listOf(firstExpert, initiallyNormal))
        viewModel.toggleProfile(1)
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(listOf(firstExpert), viewModel.uiState.value.expertApps)

        apps.apps.value = listOf(firstExpert, nowExpert)
        viewModel.submit(confirmExperts = true)
        advanceUntilIdle()
        assertEquals(listOf(firstExpert, nowExpert), viewModel.uiState.value.expertApps)
        assertTrue(profiles.requests.isEmpty())

        viewModel.submit(confirmExperts = true)
        advanceUntilIdle()
        assertEquals(1, profiles.requests.size)
        assertEquals(setOf(firstExpert.packageName, nowExpert.packageName), profiles.requests.single().second)
    }

    @Test
    fun `a target deleted during app revalidation aborts all originally selected profiles`() = runTest {
        val secondProfile = profiles.create("Tools", emptyList())
        val release = CompletableDeferred<Unit>()
        val delayed = object : AppRepository by apps {
            override suspend fun getAppDetails(packageName: String): AppInfo? {
                release.await()
                return apps.getAppDetails(packageName)
            }
        }
        val viewModel = viewModel(appRepository = delayed)
        val events = events(viewModel)
        runCurrent()
        viewModel.open(listOf(selected))
        viewModel.toggleProfile(1)
        viewModel.toggleProfile(secondProfile)
        viewModel.submit()
        runCurrent()
        profiles.delete(secondProfile)
        runCurrent()
        assertEquals(setOf(1L), viewModel.uiState.value.selectedProfileIds)

        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(profiles.requests.isEmpty())
        assertEquals(listOf(existing), profiles.packagesOf(1))
        assertTrue(viewModel.uiState.value.isOpen)
        assertEquals(UiText.StringResource(R.string.profile_assignment_profiles_changed), viewModel.uiState.value.error)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `transaction rejection for a deleted target cannot partially assign the survivors`() = runTest {
        val secondProfile = profiles.create("Tools", emptyList())
        val release = CompletableDeferred<Unit>()
        profiles.beforeWrite = { release.await() }
        val viewModel = viewModel()
        val events = events(viewModel)
        runCurrent()
        viewModel.open(listOf(selected))
        viewModel.toggleProfile(1)
        viewModel.toggleProfile(secondProfile)
        viewModel.submit()
        runCurrent()
        profiles.delete(secondProfile)
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(setOf(1L, secondProfile), profiles.requests.single().first)
        assertEquals(listOf(existing), profiles.packagesOf(1))
        assertEquals(setOf(1L), viewModel.uiState.value.selectedProfileIds)
        assertEquals(UiText.StringResource(R.string.profile_assignment_profiles_changed), viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.isOpen)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `all unavailable or blocked leaves picker open without an empty assignment`() = runTest {
        val blocked = userApp("com.example.blocked").copy(isSystem = true, isUadLoadFailed = true)
        apps.apps.value = listOf(blocked)
        val viewModel = viewModel()
        val events = events(viewModel)
        runCurrent()
        viewModel.open(listOf(selected, blocked))
        viewModel.toggleProfile(1)
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.skippedCount)
        assertEquals(UiText.StringResource(R.string.profile_assignment_no_eligible_apps), viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.isOpen)
        assertTrue(profiles.requests.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `already present assignment is still a completed persistence result`() = runTest {
        profiles.update(1, "Games", listOf(selected.packageName))
        val viewModel = viewModel()
        val events = events(viewModel)
        runCurrent()
        viewModel.open(listOf(selected))
        viewModel.toggleProfile(1)
        viewModel.submit()
        advanceUntilIdle()

        val result = (events.single() as ProfileAssignmentEvent.Assigned).result
        assertEquals(0, result.addedCount)
        assertEquals(1, result.alreadyPresentCount)
        assertFalse(viewModel.uiState.value.isOpen)
    }

    @Test
    fun `cancellation is not turned into an error or a completed assignment`() = runTest {
        profiles.delegate.writeFailure = CancellationException("Owner cancelled")
        val viewModel = viewModel()
        val events = events(viewModel)
        runCurrent()
        viewModel.open(listOf(selected))
        viewModel.toggleProfile(1)
        viewModel.submit()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isOpen)
        assertFalse(viewModel.uiState.value.isSaving)
        assertNull(viewModel.uiState.value.error)
        assertTrue(events.isEmpty())
        assertEquals(listOf(existing), profiles.packagesOf(1))
    }

    @Test
    fun `profile observation can recover after a localized load failure`() = runTest {
        var shouldFail = true
        val failing = object : FreezeProfileRepository by profiles {
            override fun observeProfiles(): Flow<List<FreezeProfile>> =
                if (shouldFail) flow { throw IOException("private database diagnostics") }
                else profiles.observeProfiles()
        }
        val viewModel = viewModel(profileRepository = failing)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.profilesLoadFailed)
        assertEquals(UiText.StringResource(R.string.error_profiles_load_failed), viewModel.uiState.value.error)

        shouldFail = false
        viewModel.retryProfiles()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.profilesLoadFailed)
        assertEquals("Games", viewModel.uiState.value.profiles.single().name)
        assertNull(viewModel.uiState.value.error)
    }

    private class RecordingProfiles(val delegate: FakeFreezeProfileRepository) :
        FreezeProfileRepository by delegate {
        val requests = mutableListOf<Pair<Set<Long>, Set<String>>>()
        var beforeWrite: suspend () -> Unit = {}

        override suspend fun addApps(
            profileIds: Set<Long>,
            packageNames: Set<String>,
        ): ProfileAssignmentResult {
            requests += profileIds to packageNames
            beforeWrite()
            return delegate.addApps(profileIds, packageNames)
        }
    }
}
