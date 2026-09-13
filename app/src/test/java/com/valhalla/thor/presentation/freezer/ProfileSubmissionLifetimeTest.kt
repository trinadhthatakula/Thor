// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.freezer.DefaultPrivilegeSweepController
import com.valhalla.thor.data.freezer.PrivilegeQueueWakeSignal
import com.valhalla.thor.data.freezer.PrivilegeSweepCancellationCoordinator
import com.valhalla.thor.data.freezer.PrivilegeSweepClock
import com.valhalla.thor.data.freezer.PrivilegeSweepProcessGate
import com.valhalla.thor.data.freezer.SweepQueueCanceller
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.data.repository.RoomPrivilegeSweepStore
import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.data.source.local.room.AppDatabase
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.repository.FreezeProfileRepository
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeSweepCancellationDecision
import com.valhalla.thor.domain.repository.PrivilegeSweepController
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.SweepCreateResult
import com.valhalla.thor.domain.usecase.FreezeAppUseCase
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.*
import com.valhalla.thor.presentation.navigation.TaskNavigationRequest
import com.valhalla.thor.presentation.navigation.TaskNavigationTargets
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentityRegistry
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ProfileSubmissionLifetimeTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val navigation = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
    private val profiles = FakeFreezeProfileRepository()

    @Test fun `clearing profile owner after Room commit still wakes and settles exactly once`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        val room = RoomPrivilegeSweepStore(db.privilegeSweepDao())
        val committed = CompletableDeferred<UUID>()
        val release = CompletableDeferred<Unit>()
        var inserts = 0
        val pausedStore = object : PrivilegeSweepStore by room {
            override suspend fun createOrFindEquivalent(snapshot: NewPrivilegeSweepSnapshot): SweepCreateResult {
                val result = room.createOrFindEquivalent(snapshot)
                inserts++
                committed.complete(snapshot.requestId)
                release.await()
                return result
            }
        }
        val wakes = mutableListOf<UUID>()
        val controller = DefaultPrivilegeSweepController(
            pausedStore, PrivilegeSweepClock { 1_000L }, PrivilegeSweepProcessGate(),
            PrivilegeQueueWakeSignal { wakes += it; ServiceStartResult.Requested },
            SweepQueueCanceller(PrivilegeSweepCancellationCoordinator(
                requestCancellation = { PrivilegeSweepCancellationDecision.NotFound },
                cancelActive = { false }, wake = { ServiceStartResult.AlreadyRunning },
                reconcileStaleClaim = {},
            )),
            object : RootLaneStatusSource { override val statuses = MutableStateFlow(emptyMap<com.valhalla.thor.domain.model.PrivilegeExecutionLane, com.valhalla.thor.domain.model.RootLaneStatus>()) },
        )
        profiles.create("Morning", listOf("com.example.app"))
        val vm = viewModel(controller)
        val owner = ViewModelStore().apply {
            ViewModelProvider(this, viewModelFactory { initializer { vm } })[
                "freezer", FreezerViewModel::class.java,
            ]
        }
        val requests = mutableListOf<TaskNavigationRequest>()
        backgroundScope.launch(main.dispatcher) { navigation.requests.collect(requests::add) }
        try {
            vm.runProfile(1L, BulkOp.FREEZE)
            val id = committed.await()
            assertNotNull("the actual Room transaction committed before the pause", room.load(id))
            owner.clear()
            release.complete(Unit)
            runCurrent()
            assertEquals("clearing presentation must not cancel the committed service wake", listOf(id), wakes)
            assertEquals(1, inserts)
            assertEquals(2, requests.size)
            assertEquals(id, (requests[0] as TaskNavigationRequest.OpenProvisional).taskId)
            assertEquals(TaskNavigationRequest.Accepted(id, id), requests[1])
        } finally {
            release.complete(Unit)
            owner.clear()
            db.close()
        }
    }

    @Test fun `clearing profile owner during failed resolution still rejects provisional identity`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolving = object : FreezeProfileRepository by profiles {
            override suspend fun packagesOf(profileId: Long): List<String> {
                started.complete(Unit)
                release.await()
                throw IOException("profile read unavailable")
            }
        }
        val controller = FakePrivilegeSweepController()
        val vm = viewModel(controller, resolving)
        val owner = ViewModelStore().apply {
            ViewModelProvider(this, viewModelFactory { initializer { vm } })[
                "freezer", FreezerViewModel::class.java,
            ]
        }
        val requests = mutableListOf<TaskNavigationRequest>()
        backgroundScope.launch(main.dispatcher) { navigation.requests.collect(requests::add) }
        vm.runProfile(1L, BulkOp.FREEZE)
        started.await()
        owner.clear()
        release.complete(Unit)
        runCurrent()
        assertEquals("failure must settle even without its original observer", 2, requests.size)
        val id = (requests[0] as TaskNavigationRequest.OpenProvisional).taskId
        assertEquals(TaskNavigationRequest.Rejected(id), requests[1])
        assertTrue(controller.launched.isEmpty())
    }

    @Test fun `resolution cancellation is not converted into launch rejection`() = runTest {
        val cancelled = object : FreezeProfileRepository by profiles {
            override suspend fun packagesOf(profileId: Long): List<String> =
                throw kotlinx.coroutines.CancellationException("resolution cancelled")
        }
        val controller = FakePrivilegeSweepController()
        val requests = mutableListOf<TaskNavigationRequest>()
        backgroundScope.launch(main.dispatcher) { navigation.requests.collect(requests::add) }
        val vm = viewModel(controller, cancelled)
        val owner = ViewModelStore().apply {
            ViewModelProvider(this, viewModelFactory { initializer { vm } })[
                "freezer", FreezerViewModel::class.java,
            ]
        }
        try {
            vm.runProfile(1L, BulkOp.FREEZE)
            runCurrent()
            assertEquals(1, requests.size)
            assertTrue(requests.single() is TaskNavigationRequest.OpenProvisional)
            assertTrue(controller.launched.isEmpty())
        } finally {
            owner.clear()
        }
    }

    private fun viewModel(
        controller: PrivilegeSweepController,
        profileRepository: FreezeProfileRepository = profiles,
    ): FreezerViewModel {
        val apps = FakeAppRepository()
        val freezer = FakeFreezerRepository()
        val prefs = FakePreferenceRepository()
        val manage = ManageAppUseCase(FakeSystemRepository(), DefaultPackageOperationCoordinator())
        return FreezerViewModel(
            freezerRepository = freezer, freezeProfileRepository = profileRepository,
            profileSubmission = ProfileSubmissionCoordinator(
                privilegeSweepResolver(freezer, profileRepository, prefs), controller, navigation, main.dispatcher,
            ),
            sweepController = controller, taskNavigationTargets = navigation,
            getInstalledAppsUseCase = GetInstalledAppsUseCase(apps), manageAppUseCase = manage,
            freezeAppUseCase = FreezeAppUseCase(apps, manage), privilege = FakePrivilegeStateProvider(),
            preferenceRepository = prefs, appShortcuts = FakeAppShortcutController(),
            defaultDispatcher = main.dispatcher, ioDispatcher = main.dispatcher,
        )
    }
}
