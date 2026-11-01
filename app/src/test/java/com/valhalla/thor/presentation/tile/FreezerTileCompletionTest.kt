// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import android.app.Application
import android.service.quicksettings.Tile
import com.valhalla.thor.data.freezer.PrivilegeSweepResolutionRuntime
import com.valhalla.thor.data.freezer.PrivilegeSweepSurfaceLauncher
import com.valhalla.thor.data.freezer.PrivilegeSweepTargetResolver
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.gateway.root.DefaultRootLaneStatusSource
import com.valhalla.thor.data.privilege.RootAvailabilityCoordinator
import com.valhalla.thor.data.privilege.RootAvailabilityProbe
import com.valhalla.thor.data.privilege.RootProbeOutcome
import com.valhalla.thor.data.privilege.RootProbeResult
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
    private val frozenPackages = mutableSetOf<String>()
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

    private fun start(packages: Set<String> = setOf("com.example.app")) {
        val freezer = FakeFreezerRepository(packages)
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
                override fun candidatesFor(op: BulkOp): (String) -> FreezeCandidate = { packageName ->
                    FreezeCandidate(if (frozen || packageName in frozenPackages) FreezeState.FROZEN else FreezeState.ACTIVE)
                }
            })
        val root = RootAvailabilityCoordinator(
            RootAvailabilityProbe { RootProbeResult(RootProbeOutcome.ROOT) }, main.dispatcher,
        )
        val manager = PrivilegeManager(
            FakeSystemRepository(), prefs, main.dispatcher, main.dispatcher, root, DefaultRootLaneStatusSource(),
        )
        startKoin { modules(module {
            single { resolver }
            single<PrivilegeSweepController> { controller }
            single { PrivilegeSweepSurfaceLauncher(resolver, controller, main.dispatcher) }
            single { manager }
            single<kotlinx.coroutines.CoroutineDispatcher>(org.koin.core.qualifier.named("main")) { main.dispatcher }
            single<kotlinx.coroutines.CoroutineDispatcher>(org.koin.core.qualifier.named("io")) { main.dispatcher }
        }) }
        service = Robolectric.buildService(FreezerTileService::class.java).create().get()
        tile = service.qsTile
        service.onStartListening()
    }

    @Test fun `terminal sweep refreshes frozen state without shade restart`() = runTest {
        start()
        runCurrent()
        assertEquals(Tile.STATE_INACTIVE, tile.state)
        assertEquals(1, reads)
        controller.emit(status(PrivilegeSweepPhase.RUNNING))
        runCurrent()
        controller.emit(status(PrivilegeSweepPhase.RUNNING).copy(succeeded = 1))
        runCurrent()
        assertEquals("progress must not rescan", 1, reads)
        frozen = true
        controller.emit(status(PrivilegeSweepPhase.SUCCEEDED))
        runCurrent()
        assertEquals("terminal completion must refresh live candidates", Tile.STATE_ACTIVE, tile.state)
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
        val candidates = FreezerTileService::class.java.getDeclaredField("candidates")
            .apply { isAccessible = true }.get(service) as kotlinx.coroutines.flow.StateFlow<*>
        assertNull("stopped generation must not publish the suspended snapshot", candidates.value)
    }

    @Test fun `frozen watchlist click requests unfreeze`() = runTest {
        frozen = true
        start()
        runCurrent()
        service.onClick()
        runCurrent()
        assertEquals(PrivilegeSweepOperation.UNFREEZE, controller.launched.single().operation)
        assertEquals(listOf("com.example.app"), controller.launched.single().packageNames)
    }

    @Test fun `frozen watchlist announces unfreeze and lights the tile`() = runTest {
        frozen = true
        start()
        runCurrent()
        assertFalse(
            "A frozen app is available to unfreeze, so No apps is misleading",
            tile.contentDescription.toString().contains(service.getString(com.valhalla.thor.R.string.tile_no_apps)),
        )
        assertEquals(Tile.STATE_ACTIVE, tile.state)
        assertTrue(tile.contentDescription.toString().contains(service.resources.getQuantityString(
            com.valhalla.thor.R.plurals.tile_unfreeze_subtitle_format, 1, 1)))
    }

    @Test fun `second tap during terminal refresh requests unfreeze`() = runTest {
        start()
        runCurrent()
        controller.emit(status(PrivilegeSweepPhase.RUNNING))
        runCurrent()
        frozen = true
        pause = CompletableDeferred()
        controller.emit(status(PrivilegeSweepPhase.SUCCEEDED))
        runCurrent()

        service.onClick()
        runCurrent()
        pause!!.complete(Unit)
        runCurrent()

        assertEquals(PrivilegeSweepOperation.UNFREEZE, controller.launched.single().operation)
        assertEquals(listOf("com.example.app"), controller.launched.single().packageNames)
    }

    @Test fun `watchlist toggles freeze unfreeze freeze without restarting shade`() = runTest {
        start()
        runCurrent()
        service.onClick()
        runCurrent()
        assertEquals(PrivilegeSweepOperation.FREEZE, controller.launched.last().operation)
        frozen = true
        controller.emit(status(PrivilegeSweepPhase.SUCCEEDED))
        runCurrent()
        service.onClick()
        runCurrent()
        assertEquals(PrivilegeSweepOperation.UNFREEZE, controller.launched.last().operation)
        frozen = false
        controller.emit(status(PrivilegeSweepPhase.SUCCEEDED, 2, PrivilegeSweepOperation.UNFREEZE))
        runCurrent()
        assertEquals(Tile.STATE_INACTIVE, tile.state)
        service.onClick()
        runCurrent()
        assertEquals(listOf(PrivilegeSweepOperation.FREEZE, PrivilegeSweepOperation.UNFREEZE,
            PrivilegeSweepOperation.FREEZE), controller.launched.map { it.operation })
        assertTrue(controller.launched.all { it.packageNames == listOf("com.example.app") })
    }

    @Test fun `click reads actual freeze state instead of the last painted count`() = runTest {
        start()
        runCurrent()
        frozen = true
        service.onClick()
        runCurrent()
        assertEquals(PrivilegeSweepOperation.UNFREEZE, controller.launched.single().operation)
    }

    @Test fun `running unfreeze keeps its label and repeated taps reconnect`() = runTest {
        start()
        runCurrent()
        controller.emit(status(PrivilegeSweepPhase.RUNNING, operation = PrivilegeSweepOperation.UNFREEZE))
        runCurrent()
        assertTrue(tile.contentDescription.toString().contains(
            service.getString(com.valhalla.thor.R.string.tile_unfreezing)))
        service.onClick()
        service.onClick()
        runCurrent()
        assertTrue("taps must not reverse or duplicate in-flight work", controller.launched.isEmpty())
        assertEquals("reconnecting does not rescan candidates", 1, reads)
    }

    @Test fun `closing shade after click cannot cancel the enqueue handoff`() = runTest {
        start()
        runCurrent()
        pause = CompletableDeferred()
        service.onClick()
        runCurrent()
        service.onStopListening()
        pause!!.complete(Unit)
        runCurrent()
        assertEquals(PrivilegeSweepOperation.FREEZE, controller.launched.single().operation)
        assertEquals(listOf("com.example.app"), controller.launched.single().packageNames)
    }

    @Test fun `partial freeze offers recovery of successful apps before retrying failures`() = runTest {
        start(setOf("failed.app", "frozen.app"))
        runCurrent()
        frozenPackages += "frozen.app"
        controller.emit(status(PrivilegeSweepPhase.PARTIAL).copy(total = 2, succeeded = 1, failed = 1, unresolved = 0))
        runCurrent()
        assertEquals(Tile.STATE_ACTIVE, tile.state)
        service.onClick()
        runCurrent()
        assertEquals(PrivilegeSweepOperation.UNFREEZE, controller.launched.single().operation)
        assertEquals(listOf("frozen.app"), controller.launched.single().packageNames)
    }

    @Test
    @Config(sdk = [29])
    fun `API 29 subtitle and accessibility describe the unfreeze action`() = runTest {
        frozen = true
        start()
        runCurrent()
        val action = service.resources.getQuantityString(com.valhalla.thor.R.plurals.tile_unfreeze_subtitle_format, 1, 1)
        if (android.os.Build.VERSION.SDK_INT >= 29) assertEquals(action, tile.subtitle.toString())
        assertTrue(tile.contentDescription.toString().contains(action))
    }

    @Test
    @Config(sdk = [30])
    fun `API 30 accessibility keeps identity separate from the unfreeze state`() = runTest {
        frozen = true
        start()
        runCurrent()
        val action = service.resources.getQuantityString(com.valhalla.thor.R.plurals.tile_unfreeze_subtitle_format, 1, 1)
        assertEquals(service.getString(com.valhalla.thor.R.string.freezer), tile.contentDescription.toString())
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            assertEquals(action, tile.stateDescription.toString())
            assertEquals(action, tile.subtitle.toString())
        }
    }

    private fun status(phase: PrivilegeSweepPhase, id: Long = 1,
        operation: PrivilegeSweepOperation = PrivilegeSweepOperation.FREEZE) = PrivilegeSweepStatus(
        UUID(0, id), UUID(1, id), operation, PrivilegeSweepSource.QS_TILE,
        phase, 1, if (phase == PrivilegeSweepPhase.SUCCEEDED) 1 else 0, 0, 0,
        if (phase == PrivilegeSweepPhase.SUCCEEDED) 0 else 1, false,
    )
}
