// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.gateway.ComponentEnabledState
import com.valhalla.thor.domain.model.ComponentDetail
import com.valhalla.thor.domain.model.ComponentOverride
import com.valhalla.thor.domain.model.ComponentType
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.repository.ComponentOverrideRepository
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one place the ledger and the platform have to stay in step.
 *
 * The ledger is **bookkeeping, not enforcement** — that was the design decision, and it is what
 * makes these orderings load-bearing rather than stylistic. Nothing re-applies a row at boot and no
 * sweep reconciles one later, so a row that exists for a component the platform never disabled is a
 * lie that survives until somebody presses Restore All and gets told a component was restored that
 * was never touched. The two invariants:
 *
 *  - a row is written **after** the platform call succeeds, never before or unconditionally;
 *  - a row is dropped **after** the restoring call succeeds, so a failed restore leaves the row
 *    behind to be retried rather than silently forgetting a component Thor is still holding down.
 *
 * The third thing under test is [enableTargetState], which is the difference between "switch it on"
 * and "put it back". For a component that ships disabled those are opposite outcomes, and getting it
 * wrong invents a state the app has never been in — an `ENABLED` override on a component the
 * developer ships off, which then also survives the app update that would have cleared a default.
 */
class ComponentControlUseCaseTest {

    private val pkg = "com.example.app"

    // --- enableTargetState: the "put it back" question ---

    /**
     * A component the manifest already enables must be restored with `default-state`, which
     * *removes* the override. Writing `ENABLED` instead would leave a real row in
     * `package-restrictions.xml` that looks identical from the outside but outlives the app update
     * the developer's own default would not.
     */
    @Test
    fun `a component that ships enabled is restored to default and not to enabled`() {
        assertEquals(
            ComponentEnabledState.DEFAULT,
            enableTargetState(manifestDefaultEnabled = true),
        )
    }

    /**
     * A component that ships **disabled** is the case that makes this a function rather than a
     * constant. `default-state` would put it straight back to off, so an explicit `ENABLED` is the
     * only way an "Enable" press does what the label says.
     */
    @Test
    fun `a component that ships disabled is explicitly enabled`() {
        assertEquals(
            ComponentEnabledState.ENABLED,
            enableTargetState(manifestDefaultEnabled = false),
        )
    }

    // --- disable: the ledger follows the platform ---

    @Test
    fun `a successful disable writes the ledger row`() = runTest {
        val system = FakeSystem()
        val ledger = FakeLedger()
        val useCase = ComponentControlUseCase(system, ledger)

        val result = useCase.disable(pkg, ComponentType.SERVICE, component(className = "Sync"))

        assertTrue(result.isPlatformSuccess)
        assertNull("a clean run reported a ledger error", result.ledgerError)
        assertEquals(
            listOf("setComponentEnabled:$pkg:Sync:DISABLED"),
            system.calls,
        )
        assertEquals(1, ledger.rows.size)
        assertEquals("Sync", ledger.rows.single().className)
        assertEquals(ComponentType.SERVICE, ledger.rows.single().type)
    }

    /**
     * The invariant that keeps the ledger honest. A row written before the call, or written
     * regardless of its outcome, claims Thor disabled something it did not — and because nothing
     * reconciles the ledger later, that claim is permanent.
     */
    @Test
    fun `a failed disable writes no ledger row`() = runTest {
        val system = FakeSystem(respond = { Result.failure(IllegalStateException("denied")) })
        val ledger = FakeLedger()
        val useCase = ComponentControlUseCase(system, ledger)

        val result = useCase.disable(pkg, ComponentType.RECEIVER, component(className = "Boot"))

        assertTrue(result.platform.isFailure)
        assertTrue("the ledger recorded a disable that never happened", ledger.rows.isEmpty())
    }

    /**
     * `restoreToEnabled` records the manifest default **as read at the time**, not `true`. A
     * component that ships disabled and is disabled again by Thor has to be restored to *disabled*,
     * and this row is the only thing that will remember that once the component is off and its
     * effective state no longer says which way it shipped.
     */
    @Test
    fun `the row remembers how the component shipped`() = runTest {
        val ledger = FakeLedger()
        val useCase = ComponentControlUseCase(FakeSystem(), ledger)

        useCase.disable(
            pkg,
            ComponentType.RECEIVER,
            component(className = "ShipsOff", manifestDefaultEnabled = false),
        )

        assertFalse(ledger.rows.single().restoreToEnabled)
    }

    // --- enable / reset: the ledger follows the platform, in the other direction ---

    @Test
    fun `a successful enable drops the ledger row`() = runTest {
        val ledger = FakeLedger(rows = mutableListOf(row("Sync", restoreToEnabled = true)))
        val system = FakeSystem()
        val useCase = ComponentControlUseCase(system, ledger)

        val result = useCase.enable(pkg, component(className = "Sync"))

        assertTrue(result.isPlatformSuccess)
        assertEquals(listOf("setComponentEnabled:$pkg:Sync:DEFAULT"), system.calls)
        assertTrue(ledger.rows.isEmpty())
    }

    /**
     * The mirror of the disable invariant, and the more damaging direction of the two: forgetting a
     * row for a component that is *still disabled* strands it. Nothing re-applies and nothing
     * reconciles, so Restore All will never see it again and the user has no route back short of
     * finding the component by hand.
     */
    @Test
    fun `a failed enable keeps the ledger row`() = runTest {
        val ledger = FakeLedger(rows = mutableListOf(row("Sync", restoreToEnabled = true)))
        val useCase = ComponentControlUseCase(
            FakeSystem(respond = { Result.failure(IllegalStateException("denied")) }),
            ledger,
        )

        val result = useCase.enable(pkg, component(className = "Sync"))

        assertTrue(result.platform.isFailure)
        assertEquals(1, ledger.rows.size)
    }

    /**
     * The third verb with the same `.onSuccess { forget }` contract, and the one whose failure half
     * was unverified — `disable` and `enable` both had a test for it. A ledger row dropped after a
     * refused `pm default-state` would strand the component: still overridden, no longer listed, so
     * "Restore all" can never reach it.
     */
    @Test
    fun `a failed reset keeps the ledger row`() = runTest {
        val ledger = FakeLedger(rows = mutableListOf(row("Sync", restoreToEnabled = true)))
        val useCase = ComponentControlUseCase(
            FakeSystem(respond = { Result.failure(IllegalStateException("denied")) }),
            ledger,
        )

        val result = useCase.resetToDefault(pkg, "Sync")

        assertTrue(result.platform.isFailure)
        assertEquals(1, ledger.rows.size)
    }

    /** "Enable" on a component that ships off asks the platform for the explicit state. */
    @Test
    fun `enabling a ships-off component asks for ENABLED`() = runTest {
        val system = FakeSystem()
        val useCase = ComponentControlUseCase(system, FakeLedger())

        useCase.enable(pkg, component(className = "ShipsOff", manifestDefaultEnabled = false))

        assertEquals(listOf("setComponentEnabled:$pkg:ShipsOff:ENABLED"), system.calls)
    }

    /**
     * "Reset to default" is not "enable". It is offered for *any* explicit override, including one
     * Thor did not write, so it always clears rather than choosing a direction.
     */
    @Test
    fun `reset always asks for DEFAULT`() = runTest {
        val system = FakeSystem()
        val ledger = FakeLedger(rows = mutableListOf(row("Sync", restoreToEnabled = false)))
        val useCase = ComponentControlUseCase(system, ledger)

        useCase.resetToDefault(pkg, "Sync")

        assertEquals(listOf("setComponentEnabled:$pkg:Sync:DEFAULT"), system.calls)
        assertTrue(ledger.rows.isEmpty())
    }

    /** Forgetting a drifted row touches the ledger only — the component is somebody else's now. */
    @Test
    fun `forget touches the ledger and not the platform`() = runTest {
        val system = FakeSystem()
        val ledger = FakeLedger(rows = mutableListOf(row("Sync", restoreToEnabled = true)))
        val useCase = ComponentControlUseCase(system, ledger)

        useCase.forget(pkg, "Sync")

        assertTrue(system.calls.isEmpty())
        assertTrue(ledger.rows.isEmpty())
    }

    // --- the ledger failing is not the platform failing ---

    /**
     * The inversion this outcome type exists to prevent.
     *
     * Chaining the ledger write inside `Result.onSuccess` meant a Room failure threw out through the
     * `Result`, so the caller reported a *failed disable* for a component that was already off. The
     * user is then told nothing happened, retries, and gets the same error — while the component has
     * been disabled the whole time.
     */
    @Test
    fun `a ledger failure does not turn a successful disable into a failure`() = runTest {
        val system = FakeSystem()
        val ledger = FakeLedger(writeFailure = IllegalStateException("database or disk is full"))
        val useCase = ComponentControlUseCase(system, ledger)

        val result = useCase.disable(pkg, ComponentType.SERVICE, component(className = "Sync"))

        assertTrue("the platform call succeeded and must be reported as such", result.isPlatformSuccess)
        assertNotNull("the lost record has to be reported separately", result.ledgerError)
        assertEquals(listOf("setComponentEnabled:$pkg:Sync:DISABLED"), system.calls)
        assertTrue("nothing could be written, so there is no row", ledger.rows.isEmpty())
    }

    /** Same for the restoring direction, where the leftover row shows up as drift in the UI. */
    @Test
    fun `a ledger failure does not turn a successful enable into a failure`() = runTest {
        val ledger = FakeLedger(
            rows = mutableListOf(row("Sync", restoreToEnabled = true)),
            writeFailure = IllegalStateException("disk I/O error"),
        )
        val useCase = ComponentControlUseCase(FakeSystem(), ledger)

        val result = useCase.enable(pkg, component(className = "Sync"))

        assertTrue(result.isPlatformSuccess)
        assertNotNull(result.ledgerError)
        assertEquals("the row survives, which the UI reads as drift", 1, ledger.rows.size)
    }

    /** A ledger write that throws must be reported, not thrown — the caller launches this from the UI. */
    @Test
    fun `forget reports a ledger failure rather than throwing`() = runTest {
        val useCase = ComponentControlUseCase(
            FakeSystem(),
            FakeLedger(
                rows = mutableListOf(row("Sync", restoreToEnabled = true)),
                writeFailure = IllegalStateException("disk I/O error"),
            ),
        )

        val result = useCase.forget(pkg, "Sync")

        assertTrue(result.isFailure)
    }

    /**
     * One unwritable row must not abort the sweep, and must not be counted as restored.
     *
     * Counting it would report "restored 2 of 2" while a row was still in the table telling the
     * screen a component is restricted — a completion message the user's own screen contradicts.
     */
    @Test
    fun `restore all under-reports a row whose ledger delete failed`() = runTest {
        val system = FakeSystem()
        val useCase = ComponentControlUseCase(
            system,
            FakeLedger(
                rows = mutableListOf(row("Sync", restoreToEnabled = true)),
                writeFailure = IllegalStateException("database or disk is full"),
            ),
        )

        val outcome = useCase.restoreAll()

        assertEquals("the platform call was still made", 1, system.calls.size)
        assertEquals(0, outcome.restored)
        assertEquals(1, outcome.attempted)
        assertFalse("an unforgettable row cannot be a complete run", outcome.isComplete)
    }

    // --- restoreAll ---

    @Test
    fun `restore all restores every row and reports a complete run`() = runTest {
        val ledger = FakeLedger(
            rows = mutableListOf(
                row("Sync", restoreToEnabled = true),
                row("Boot", restoreToEnabled = false),
            )
        )
        val system = FakeSystem()
        val useCase = ComponentControlUseCase(system, ledger)

        val outcome = useCase.restoreAll()

        assertEquals(RestoreAllOutcome(restored = 2, attempted = 2), outcome)
        assertTrue(outcome.isComplete)
        assertTrue(ledger.rows.isEmpty())
        assertEquals(
            listOf(
                "setComponentEnabled:$pkg:Sync:DEFAULT",
                "setComponentEnabled:$pkg:Boot:ENABLED",
            ),
            system.calls,
        )
    }

    /**
     * A partial run is the interesting one. The row that stays behind here failed at the platform, so
     * it really is still disabled and this ledger is the only record of that — but that is one of two
     * ways to survive the sweep, and `restore all under-reports a row whose ledger delete failed`
     * covers the other. Either way the count reported has to be the number actually restored, not the
     * number tried. The banner says "N restricted by Thor" from the same rows, so a run that cleared
     * them all regardless would show 0 while N components were still switched off.
     */
    @Test
    fun `a partial restore keeps the rows it could not restore`() = runTest {
        val ledger = FakeLedger(
            rows = mutableListOf(
                row("Sync", restoreToEnabled = true),
                row("Boot", restoreToEnabled = true),
                row("Widget", restoreToEnabled = true),
            )
        )
        val useCase = ComponentControlUseCase(
            FakeSystem(respond = { call ->
                if (call.contains(":Boot:")) Result.failure(IllegalStateException("denied"))
                else Result.success(Unit)
            }),
            ledger,
        )

        val outcome = useCase.restoreAll()

        assertEquals(RestoreAllOutcome(restored = 2, attempted = 3), outcome)
        assertFalse(outcome.isComplete)
        assertEquals(listOf("Boot"), ledger.rows.map { it.className })
    }

    /** An empty ledger is a complete run of nothing, not a failure. */
    @Test
    fun `restore all with no rows is complete`() = runTest {
        val outcome = ComponentControlUseCase(FakeSystem(), FakeLedger()).restoreAll()
        assertEquals(RestoreAllOutcome(restored = 0, attempted = 0), outcome)
        assertTrue(outcome.isComplete)
    }

    /**
     * Restore All is cross-app by design — the whole point is that a user who disabled components
     * across a dozen apps has one way back — so it must not filter to the package that happens to be
     * on screen.
     */
    @Test
    fun `restore all crosses package boundaries`() = runTest {
        val ledger = FakeLedger(
            rows = mutableListOf(
                row("Sync", restoreToEnabled = true, packageName = "com.example.a"),
                row("Boot", restoreToEnabled = true, packageName = "com.example.b"),
            )
        )
        val system = FakeSystem()

        ComponentControlUseCase(system, ledger).restoreAll()

        assertEquals(
            listOf(
                "setComponentEnabled:com.example.a:Sync:DEFAULT",
                "setComponentEnabled:com.example.b:Boot:DEFAULT",
            ),
            system.calls,
        )
    }

    // --- pass-throughs ---

    @Test
    fun `force launch and stop service reach the platform unchanged`() = runTest {
        val system = FakeSystem()
        val useCase = ComponentControlUseCase(system, FakeLedger())

        useCase.forceLaunch(pkg, "Secret")
        useCase.stopService(pkg, "Sync")

        assertEquals(
            listOf("forceLaunchActivity:$pkg:Secret", "stopService:$pkg:Sync"),
            system.calls,
        )
    }

    /** Neither read-only verb writes a ledger row — only Disable does. */
    @Test
    fun `neither force launch nor stop service writes a ledger row`() = runTest {
        val ledger = FakeLedger()
        val useCase = ComponentControlUseCase(FakeSystem(), ledger)

        useCase.forceLaunch(pkg, "Secret")
        useCase.stopService(pkg, "Sync")

        assertTrue(ledger.rows.isEmpty())
    }

    // --- fixtures ---

    private fun component(
        className: String,
        manifestDefaultEnabled: Boolean = true,
    ) = ComponentDetail(
        className = className,
        exported = true,
        enabled = manifestDefaultEnabled,
        manifestDefaultEnabled = manifestDefaultEnabled,
    )

    private fun row(
        className: String,
        restoreToEnabled: Boolean,
        packageName: String = pkg,
    ) = ComponentOverride(
        packageName = packageName,
        className = className,
        type = ComponentType.SERVICE,
        restoreToEnabled = restoreToEnabled,
        disabledAt = 0L,
    )
}

/**
 * The ledger, in memory, with the rows readable.
 *
 * A real list rather than a call recorder because every assertion here is about *what is left in
 * the ledger* after a partly-failed run, which a list of "record"/"forget" strings answers only by
 * replaying them.
 */
private class FakeLedger(
    val rows: MutableList<ComponentOverride> = mutableListOf(),
    /**
     * Makes every write throw, standing in for the database being full or damaged.
     *
     * The real repository hands Room calls straight through, so this is not a hypothetical: `upsert`
     * and `delete` can both throw `SQLiteFullException` or a disk-I/O error, and the production code
     * used to let that throw travel out through a `Result` the caller read as the *platform* call
     * having failed.
     */
    private val writeFailure: Throwable? = null,
) : ComponentOverrideRepository {

    private val revision = MutableStateFlow(0)

    override fun observe(packageName: String): Flow<List<ComponentOverride>> =
        revision.map { rows.filter { row -> row.packageName == packageName } }

    override suspend fun getAll(): List<ComponentOverride> = rows.toList()

    override suspend fun record(
        packageName: String,
        className: String,
        type: ComponentType,
        restoreToEnabled: Boolean,
    ) {
        writeFailure?.let { throw it }
        rows.removeAll { it.packageName == packageName && it.className == className }
        rows += ComponentOverride(packageName, className, type, restoreToEnabled, disabledAt = 0L)
        revision.value++
    }

    override suspend fun forget(packageName: String, className: String) {
        writeFailure?.let { throw it }
        rows.removeAll { it.packageName == packageName && it.className == className }
        revision.value++
    }

    override suspend fun forgetPackage(packageName: String) {
        rows.removeAll { it.packageName == packageName }
        revision.value++
    }
}

/**
 * Records the three component verbs in order and lets a test decide what each one answers.
 *
 * Hand-written for the same reason as the other `SystemRepository` doubles in this source set: there
 * is no mocking library on `:app`. Everything off the component path throws rather than returning a
 * benign default, so a use case that starts calling one of them fails loudly here instead of
 * recording nothing and still passing.
 */
private class FakeSystem(
    private val respond: (String) -> Result<Unit> = { Result.success(Unit) },
) : SystemRepository {

    val calls = mutableListOf<String>()

    override suspend fun setComponentEnabled(
        packageName: String,
        className: String,
        state: ComponentEnabledState,
    ): Result<Unit> = record("setComponentEnabled:$packageName:$className:$state")

    override suspend fun forceLaunchActivity(
        packageName: String,
        className: String,
    ): Result<Unit> = record("forceLaunchActivity:$packageName:$className")

    override suspend fun stopService(packageName: String, className: String): Result<Unit> =
        record("stopService:$packageName:$className")

    private fun record(call: String): Result<Unit> {
        calls += call
        return respond(call)
    }

    override suspend fun isRootAvailable(): Boolean = unreachable("isRootAvailable")
    override suspend fun isShizukuAvailable(): Boolean = unreachable("isShizukuAvailable")
    override suspend fun isDhizukuAvailable(): Boolean = unreachable("isDhizukuAvailable")

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> =
        unreachable("setAppDisabled")

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit> =
        unreachable("setAppSuspended")

    override suspend fun forceStopApp(packageName: String): Result<Unit> =
        unreachable("forceStopApp")

    override suspend fun clearCache(packageName: String): Result<Long?> = unreachable("clearCache")
    override suspend fun clearAllCaches(): Result<Long?> = unreachable("clearAllCaches")

    override suspend fun clearAppData(packageName: String): Result<Unit> =
        unreachable("clearAppData")

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean,
    ): Result<Unit> = unreachable("setAppRestricted")

    override suspend fun uninstallApp(packageName: String): Result<Unit> =
        unreachable("uninstallApp")

    override suspend fun rebootDevice(reason: String): Result<Unit> = unreachable("rebootDevice")

    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> =
        unreachable("reinstallAppWithGoogle")

    override suspend fun copyFileWithRoot(
        sourcePath: String,
        destinationPath: String,
    ): Result<Unit> = unreachable("copyFileWithRoot")

    override suspend fun getAppPaths(packageName: String): Result<List<String>> =
        unreachable("getAppPaths")

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String,
    ): Result<Unit> = unreachable("grantPermission")

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String,
    ): Result<Unit> = unreachable("revokePermission")

    override suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>> =
        unreachable("executeShellCommand")

    override suspend fun probeObb(packageName: String): ObbProbe = unreachable("probeObb")

    private fun unreachable(name: String): Nothing =
        throw UnsupportedOperationException("$name is off the component-control path")
}
