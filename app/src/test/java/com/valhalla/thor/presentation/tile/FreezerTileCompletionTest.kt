// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import android.app.Application
import android.service.quicksettings.Tile
import com.valhalla.thor.data.freezer.PrivilegeSweepResolutionRuntime
import com.valhalla.thor.data.freezer.PrivilegeSweepSurfaceLauncher
import com.valhalla.thor.data.freezer.PrivilegeSweepTargetResolver
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PrivilegeSweepController
import com.valhalla.thor.presentation.*
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class FreezerTileCompletionTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val controller = FakePrivilegeSweepController()
    private var frozen = false
    private var reads = 0
    private var pause: CompletableDeferred<Unit>? = null
    private lateinit var service: FreezerTileService
    private lateinit var tile: Tile

    @After fun close() {
        try {
            if (::service.isInitialized) service.onStopListening()
        } finally {
            stopKoin()
        }
    }

    private fun start() {
        val freezer = FakeFreezerRepository(setOf("com.example.app"))
        val reading = object : FreezerRepository by freezer {
            override suspend fun getAllPackageNames(): List<String> {
                reads++
                // Deliberately uncooperative read: lifecycle/refresh generation must fence publication.
                val snapshot = freezer.getAllPackageNames()
                pause?.let { withContext(NonCancellable) { it.await() } }
                return snapshot
            }
        }
        val prefs = FakePreferenceRepository()
        val resolver = PrivilegeSweepTargetResolver(reading, FakeFreezeProfileRepository(), prefs,
            object : PrivilegeSweepResolutionRuntime {
                override val userId = 0
                override fun candidatesFor(op: BulkOp): (String) -> FreezeCandidate = {
                    FreezeCandidate(if (frozen) FreezeState.FROZEN else FreezeState.ACTIVE)
                }
            })
        val manager = PrivilegeManager(FakeSystemRepository(), prefs, main.dispatcher, main.dispatcher)
        startKoin { modules(module {
            single { resolver }
            single<PrivilegeSweepController> { controller }
            single { PrivilegeSweepSurfaceLauncher(resolver, controller, main.dispatcher) }
            single { manager }
            single<kotlinx.coroutines.CoroutineDispatcher>(org.koin.core.qualifier.named("main")) { main.dispatcher }
        }) }
        service = Robolectric.buildService(FreezerTileService::class.java).create().get()
        tile = service.qsTile
        service.onStartListening()
    }

    @Test fun `terminal sweep refreshes one candidate to inactive without shade restart`() = runTest {
        start()
        runCurrent()
        assertEquals(Tile.STATE_ACTIVE, tile.state)
        assertEquals(1, reads)
        controller.emit(status(PrivilegeSweepPhase.RUNNING))
        runCurrent()
        controller.emit(status(PrivilegeSweepPhase.RUNNING).copy(succeeded = 1))
        runCurrent()
        assertEquals("progress must not rescan", 1, reads)
        frozen = true
        controller.emit(status(PrivilegeSweepPhase.SUCCEEDED))
        runCurrent()
        assertEquals("terminal completion must refresh live candidates", Tile.STATE_INACTIVE, tile.state)
        assertEquals(2, reads)
        controller.emit(status(PrivilegeSweepPhase.SUCCEEDED).copy(rootLaneDegraded = true))
        runCurrent()
        assertEquals("repeat terminal metadata must not rescan", 2, reads)
        service.onStopListening()
        controller.emit(status(PrivilegeSweepPhase.RUNNING, 2))
        controller.emit(status(PrivilegeSweepPhase.SUCCEEDED, 2))
        runCurrent()
        assertEquals(2, reads)
    }

    @Test fun `stopped listening rejects completion from suspended candidate read`() = runTest {
        start()
        runCurrent()
        controller.emit(status(PrivilegeSweepPhase.RUNNING))
        runCurrent()
        pause = CompletableDeferred()
        frozen = true
        controller.emit(status(PrivilegeSweepPhase.SUCCEEDED))
        runCurrent()
        assertEquals("terminal starts exactly one refresh", 2, reads)
        service.onStopListening()
        val stoppedState = tile.state
        pause!!.complete(Unit)
        runCurrent()
        assertEquals(stoppedState, tile.state)
        val count = FreezerTileService::class.java.getDeclaredField("freezableCount")
            .apply { isAccessible = true }.get(service) as kotlinx.coroutines.flow.StateFlow<*>
        assertNotEquals("stopped generation must not publish the suspended zero count", 0, count.value)
    }

    private fun status(phase: PrivilegeSweepPhase, id: Long = 1) = PrivilegeSweepStatus(
        UUID(0, id), UUID(1, id), PrivilegeSweepOperation.FREEZE, PrivilegeSweepSource.QS_TILE,
        phase, 1, if (phase == PrivilegeSweepPhase.SUCCEEDED) 1 else 0, 0, 0,
        if (phase == PrivilegeSweepPhase.SUCCEEDED) 0 else 1, false,
    )
}
