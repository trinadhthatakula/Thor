# Worker Shell Lanes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent archive backup/restore from blocking unrelated privileged actions, replace process-local bulk mutation loops with durable sweep work, and make bulk progress terminate and reconnect correctly.

**Architecture:** Root commands are routed through three logical execution lanes: Odin MainShell for `INTERACTIVE`, a Thor-owned shell for `ARCHIVE`, and another Thor-owned shell for `SWEEP`. Archive and sweep jobs remain independently serialized by `THOR_JOB_CHAIN` and `THOR_SWEEP_CHAIN`; a Room-backed request snapshot, one generic non-foreground `PrivilegeSweepWorker`, and a durable controller replace `BulkFreezeRunner` and `Deferred<BulkOutcome>`. A package-keyed coordinator prevents same-package archive/mutation races while allowing different packages and independent shell lanes to proceed concurrently.

**Tech Stack:** Kotlin 2.3/JVM 21, Android 28–37, Kotlin coroutines and Flow, Odin 1.0.0, WorkManager, Room 2.8.4, Koin Annotations compiler plugin, Jetpack Compose, JUnit/coroutines-test/Turbine, AndroidX Room and WorkManager test APIs.

**Spec:** `docs/superpowers/specs/2026-08-28-worker-shell-lanes-design.md`

## Context

Thor currently funnels Root commands through Odin's process-wide cached MainShell. MainShell owns one persistent `su` process and one FIFO, so an archive `tar`, extract, or swap command blocks later interactive commands such as unfreeze even when WorkManager and coroutines have spare threads. A second `RealShellRepository` would still resolve to the same cached MainShell; isolation requires independent `Shell.Builder.create().build()` instances.

Bulk freeze/unfreeze has a second lifecycle defect: three process-local mutation paths own execution and `MainViewModel.performCountedFreeze` marks its dialog complete only after its loop. Cancellation or an unexpected exception skips that write, leaving `FreezeLoggerDialog` spinning forever. WorkManager must own replay-safe sweeps, Room must own their request and result state, and the UI must observe rather than own execution.

## Global Constraints

- Start from `dev` on a dedicated `feat/worker-shell-lanes` branch. Never commit on `dev`, `master`, or `production`; the PR target is `dev`.
- Keep `versionCode=1952`; this feature does not perform the later 1953 release bump.
- Never add a `Co-Authored-By` trailer.
- Stage only named files. Never use `git add .` or `git add -A`; leave `.kotlin/` untracked and never commit `docs/audit/` or `docs/enforcement/`.
- Use JDK/Zulu 21. Run every Gradle invocation through context-mode `ctx_execute(language: "shell")`, with `--rerun-tasks` on unit-test tasks.
- Count tests from `app/build/test-results/**/*.xml` with Python `xml.etree.ElementTree`; do not infer totals from Gradle's console summary.
- Koin uses `io.insert-koin.compiler.plugin`, not KSP. Annotate Thor-owned implementations for component scanning; add explicit `AppModule` providers only for Room DAOs or external-library types.
- Keep existing `ThorJobKind` values in place and append exactly `PRIVILEGE_SWEEP`; notification IDs and `PendingIntent` IDs depend on ordinals.
- Keep `ArchiveBackupWorker`, `ArchiveRestoreWorker`, and `AppExportWorker` on `THOR_JOB_CHAIN`; put only replay-safe privilege sweeps on `THOR_SWEEP_CHAIN`. Both use `ExistingWorkPolicy.APPEND_OR_REPLACE`.
- `PrivilegeSweepWorker.runsForeground` is `false`. It still publishes progress and a queue-wide cancellation action through `ThorJobWorker`/`ThorJobNotifications`.
- Keep force-stop, uninstall, whole-device cache trim, sharing, explicit Suspend/Unsuspend actions, and multi-app export outside WorkManager.
- Never return `Result.retry()` from a sweep. WorkManager may rerun interrupted work independently of a returned result, so every admitted operation must converge safely when replayed.
- Preserve structured cancellation. `CancellationException` and its subclasses must not be folded into ordinary `Result.failure` values.
- Never log raw shell commands, command output, archive passphrases, or user-selected paths. Log only lane, shell generation, stable command class, package, request/work IDs, timings, and terminal reason.
- Use Kotlin's locale-independent natural `String` ordering for canonical target sorting. Preserve case, reject blanks, and remove exact duplicates.
- Add no blanket deadline around archive tar/extract. Sweep package commands retain a 30-second execution deadline.
- A release build must have a real Room 7→8 migration. Debug `fallbackToDestructiveMigration` is not migration coverage.
- Before implementation completes, validate dedicated-shell cancellation on physical Root hardware. If `Shell.close()` leaves the child alive, add the PID-scoped termination path specified in Task 8 before any sweep migration is accepted.

## Stable Interfaces and Constants

The tasks below use these names consistently.

```kotlin
// domain/model/PrivilegeExecution.kt
enum class PrivilegeExecutionLane { INTERACTIVE, ARCHIVE, SWEEP }

@JvmInline
value class PrivilegeCommandClass(val value: String)

data class PrivilegeExecutionContext(
    val lane: PrivilegeExecutionLane = PrivilegeExecutionLane.INTERACTIVE,
    val commandClass: PrivilegeCommandClass = PrivilegeCommandClass("interactive.command"),
    val packageName: String? = null,
    val workRequestId: UUID? = null,
    val sweepRequestId: UUID? = null,
    val commandTimeout: Duration? = null,
)

object PrivilegeExecutionTimeouts {
    val INTERACTIVE_ADMISSION: Duration = Duration.ZERO
    val SWEEP_ADMISSION: Duration = 2.seconds
    val ARCHIVE_ADMISSION: Duration = 5.seconds
    val SWEEP_COMMAND: Duration = 30.seconds
}

enum class PackageOperationOwner {
    ARCHIVE_BACKUP,
    ARCHIVE_RESTORE,
    FREEZE,
    UNFREEZE,
    CLEAR_CACHE,
    CLEAR_DATA,
    REINSTALL,
    FORCE_STOP,
    UNINSTALL,
    OTHER_MUTATION,
}

sealed interface PackageLeaseResult<out T> {
    data class Acquired<T>(val value: T) : PackageLeaseResult<T>
    data class Busy(val owner: PackageOperationOwner) : PackageLeaseResult<Nothing>
}
```

```kotlin
// domain/repository/PackageOperationCoordinator.kt
interface PackageOperationCoordinator {
    suspend fun <T> withPackageLease(
        packageName: String,
        owner: PackageOperationOwner,
        admissionTimeout: Duration,
        block: suspend () -> T,
    ): PackageLeaseResult<T>
}
```

```kotlin
// data/gateway/root/RootShellSession.kt
data class RootCommand(
    val text: String,
    val execution: PrivilegeExecutionContext,
)

data class RootCommandResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
)

internal class RootShellTransportException(cause: Throwable? = null) :
    Exception("Root shell transport unavailable", cause)

internal interface RootShellSession {
    val isAlive: Boolean
    @Throws(RootShellTransportException::class)
    suspend fun execute(command: String): RootCommandResult
    fun close()
}

internal fun interface RootShellSessionFactory {
    suspend fun open(): RootShellSession
}

internal interface RootCommandExecutor {
    suspend fun execute(command: RootCommand): RootCommandResult
}
```

```kotlin
// domain/model/PrivilegeSweep.kt
enum class PrivilegeSweepOperation { FREEZE, UNFREEZE, CLEAR_CACHE, REINSTALL }

enum class PrivilegeSweepSource {
    MAIN,
    APP_LIST,
    FREEZER,
    PROFILE,
    QS_TILE,
    LAUNCHER,
    SETTINGS,
}

data class PrivilegeSweepSpec(
    val operation: PrivilegeSweepOperation,
    val packageNames: List<String>,
    val freezerMode: FreezerMode?,
    val userId: Int,
    val source: PrivilegeSweepSource,
)

enum class PrivilegeSweepPhase {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    CANCELLED,
    FAILED,
    OBSERVER_FAILURE,
}

data class PrivilegeSweepStatus(
    val requestId: UUID,
    val workId: UUID,
    val operation: PrivilegeSweepOperation,
    val source: PrivilegeSweepSource,
    val phase: PrivilegeSweepPhase,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val busy: Int,
    val unresolved: Int,
    val rootLaneDegraded: Boolean,
)

sealed interface PrivilegeSweepLaunchResult {
    data class Accepted(
        val requestId: UUID,
        val workId: UUID,
        val coalesced: Boolean,
    ) : PrivilegeSweepLaunchResult

    data class Rejected(val reason: PrivilegeSweepLaunchRejection) : PrivilegeSweepLaunchResult
}

sealed interface PrivilegeSweepLaunchRejection {
    data object NotificationsRequired : PrivilegeSweepLaunchRejection
    data object NoPrivilege : PrivilegeSweepLaunchRejection
    data object NoTargets : PrivilegeSweepLaunchRejection
    data class EnqueueFailed(val message: String) : PrivilegeSweepLaunchRejection
}
```

```kotlin
// domain/repository/PrivilegeSweepController.kt
interface PrivilegeSweepController {
    val activeRequests: Flow<List<PrivilegeSweepStatus>>
    suspend fun launch(spec: PrivilegeSweepSpec): PrivilegeSweepLaunchResult
    fun observe(requestId: UUID): Flow<PrivilegeSweepStatus?>
    fun observeLatest(source: PrivilegeSweepSource): Flow<PrivilegeSweepStatus?>
    suspend fun cancelQueue()
    suspend fun reconcile()
}
```

A `null` value from `observe(requestId)` means the durable snapshot is missing; presentation maps that to explicit observer-failure UI rather than continuing a spinner.

```kotlin
// domain/repository/PrivilegeSweepStore.kt
enum class SweepAttemptOutcome { SUCCEEDED, FAILED, BUSY }
enum class StoredSweepTerminal { SUCCEEDED, PARTIAL, CANCELLED, FAILED }

data class NewPrivilegeSweepSnapshot(
    val requestId: UUID,
    val workId: UUID,
    val operation: PrivilegeSweepOperation,
    val freezerMode: FreezerMode?,
    val userId: Int,
    val source: PrivilegeSweepSource,
    val createdAtEpochMs: Long,
    val targets: List<String>,
)

data class StoredPrivilegeSweep(
    val requestId: UUID,
    val workId: UUID,
    val operation: PrivilegeSweepOperation,
    val freezerMode: FreezerMode?,
    val userId: Int,
    val source: PrivilegeSweepSource,
    val createdAtEpochMs: Long,
    val targets: List<String>,
    val terminalState: StoredSweepTerminal?,
    val succeeded: Int,
    val failed: Int,
    val busy: Int,
    val unresolved: Int,
    val terminalAtEpochMs: Long?,
    val retainUntilEpochMs: Long?,
)

sealed interface SweepCreateResult {
    data class Created(val snapshot: StoredPrivilegeSweep) : SweepCreateResult
    data class Equivalent(val snapshot: StoredPrivilegeSweep) : SweepCreateResult
}
```

```kotlin
// Timing/retention constants
internal val SWEEP_RESULT_RETENTION: Duration = 24.hours
internal const val SWEEP_REQUEST_ID_KEY = "sweep_request_id"
```

---

### Task 0: Create the branch and record the approved design package

**Files:**
- Create: `docs/superpowers/plans/2026-08-28-worker-shell-lanes.md`
- Create: `web/public/thor-worker-shell-lanes.html`
- Modify: `docs/superpowers/specs/2026-08-28-worker-shell-lanes-design.md`

**Interfaces:**
- Consumes: the approved spec, this approved plan, and `/tmp/thor-worker-shell-lanes.html`.
- Produces: the canonical repository plan, standalone visual architecture page, and an accurate spec status.

- [ ] **Step 1: Verify the branch base without changing it**

Run read-only checks:

```bash
git status --short
git rev-parse --abbrev-ref HEAD
git rev-parse dev
git rev-parse origin/dev
```

Expected: current branch `dev`, local and GitHub `dev` at the approved base, with only `.kotlin/` and the approved spec untracked.

- [ ] **Step 2: Create the topic branch**

```bash
git switch -c feat/worker-shell-lanes dev
```

- [ ] **Step 3: Save the approved plan with the native Write tool**

Write this exact approved plan to `docs/superpowers/plans/2026-08-28-worker-shell-lanes.md`. Do not use a shell copy command.

- [ ] **Step 4: Convert the visual review fragment into a standalone static page**

Read `/tmp/thor-worker-shell-lanes.html` completely, then use the native Write tool to create `web/public/thor-worker-shell-lanes.html`. Move any fragment-level `<title>`, Google Fonts links, and `<style>` block into the document head; put the remaining visible fragment inside the body:

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Thor Lane Architecture</title>
  <!-- Preserve the fragment's Google Fonts links and complete style block here. -->
  <link rel="icon" href="/favicon.svg">
</head>
<body>
  <!-- Preserve the fragment's visible content and scripts here. -->
</body>
</html>
```

The comment lines in this wrapper describe placement; the finished page contains the actual source content, not those comments. Preserve theme tokens, responsive behavior, reduced-motion handling, and all existing text.

- [ ] **Step 5: Update the design status**

Replace the stale status line with:

```markdown
**Status:** implementation plan approved on 2026-08-28; implementation tracked by `docs/superpowers/plans/2026-08-28-worker-shell-lanes.md`.
```

- [ ] **Step 6: Validate the standalone page and documentation links**

Run through context-mode:

```bash
python3 - <<'PY'
from pathlib import Path
from html.parser import HTMLParser
p = Path('web/public/thor-worker-shell-lanes.html')
s = p.read_text()
assert s.startswith('<!DOCTYPE html>')
assert '<html lang="en">' in s
assert '<title>Thor Lane Architecture</title>' in s
assert '<link rel="icon" href="/favicon.svg">' in s
assert s.count('<head>') == s.count('</head>') == 1
assert s.count('<body>') == s.count('</body>') == 1
HTMLParser().feed(s)
for linked in (
    'docs/superpowers/specs/2026-08-28-worker-shell-lanes-design.md',
    'docs/superpowers/plans/2026-08-28-worker-shell-lanes.md',
):
    assert Path(linked).is_file(), linked
print('standalone architecture page and docs: OK')
PY
```

Expected: `standalone architecture page and docs: OK`.

- [ ] **Step 7: Commit the design package**

```bash
git add docs/superpowers/specs/2026-08-28-worker-shell-lanes-design.md docs/superpowers/plans/2026-08-28-worker-shell-lanes.md web/public/thor-worker-shell-lanes.html
git commit -m "docs: record worker shell lane design"
```

### Task 1: Make the legacy freeze logger terminate on every exit

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt`
- Test: `app/src/test/java/com/valhalla/thor/presentation/main/MainViewModelTest.kt`

**Interfaces:**
- Consumes: existing `FreezeLoggerState`, `ManageAppUseCase`, and the injected IO dispatcher.
- Produces: a legacy safety net that sets `isComplete=true` on success, ordinary failure, unexpected exception, and cancellation while preserving the cancellation exception.

- [ ] **Step 1: Add failing lifecycle tests**

Add tests that make the first target throw an ordinary exception and a second test that suspends the first target then cancels the owning job. Pin these outcomes:

```kotlin
assertTrue(viewModel.uiState.value.freezeLoggerState.isComplete)
assertEquals(processedBeforeExit, viewModel.uiState.value.freezeLoggerState.processed)
assertEquals(failedBeforeExit, viewModel.uiState.value.freezeLoggerState.failed)
```

The cancellation test must also assert the collected job is cancelled; completion-state cleanup must not convert cancellation into success.

- [ ] **Step 2: Run the focused test and verify the regression is reproduced**

Run through context-mode:

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.presentation.main.MainViewModelTest' \
  --rerun-tasks
```

Expected: the new tests fail because the final `_uiState.update` is skipped when the loop does not return normally.

- [ ] **Step 3: Put terminal-state ownership in `finally`**

Refactor `performCountedFreeze` to this control shape:

```kotlin
private suspend fun performCountedFreeze(
    apps: List<AppInfo>,
    isFreeze: Boolean,
    useSuspend: Boolean = false,
) {
    var processed = 0
    var failed = 0
    try {
        withContext(ioDispatcher) {
            targets.forEach { app ->
                coroutineContext.ensureActive()
                val result = performLegacyAction(app, isFreeze, useSuspend)
                processed++
                if (result.isFailure) failed++
                publishLegacyCounts(processed, failed)
            }
        }
    } finally {
        _uiState.update { state ->
            state.copy(
                freezeLoggerState = state.freezeLoggerState.copy(
                    processed = processed,
                    failed = failed,
                    isComplete = true,
                )
            )
        }
    }
}
```

Keep the existing action body inline if extracting `performLegacyAction`/`publishLegacyCounts` would add no reuse. Do not catch `CancellationException` here.

- [ ] **Step 4: Re-run the focused tests**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit the independent bug fix**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt app/src/test/java/com/valhalla/thor/presentation/main/MainViewModelTest.kt
git commit -m "fix(freezer): terminate bulk progress on cancellation"
```

### Task 2: Define execution metadata, lane status, and typed failures

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/PrivilegeExecution.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/PrivilegeExecutionTest.kt`

**Interfaces:**
- Consumes: Kotlin `Duration` and Java `UUID`.
- Produces: `PrivilegeExecutionLane`, `PrivilegeCommandClass`, `PrivilegeExecutionContext`, timeout constants, package-owner vocabulary, lane status, and transport exceptions used by Tasks 3–8 and 12.

- [ ] **Step 1: Write failing domain tests**

Pin the following rules:

```kotlin
@Test fun `default context is interactive and has no request identity`()
@Test fun `sweep context carries work request and sweep request ids`()
@Test fun `command class rejects blank values and control characters`()
@Test fun `shell command cancelled remains a CancellationException`()
@Test fun `lane status names which background lane owns degraded MainShell`()
```

- [ ] **Step 2: Run the focused test and see unresolved types**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.domain.model.PrivilegeExecutionTest' \
  --rerun-tasks
```

Expected: FAIL at compilation because `PrivilegeExecution.kt` does not exist.

- [ ] **Step 3: Implement the stable domain vocabulary**

Implement the interfaces in “Stable Interfaces and Constants,” plus:

```kotlin
enum class RootLaneMode { ISOLATED, DEGRADED }

data class RootLaneStatus(
    val lane: PrivilegeExecutionLane,
    val mode: RootLaneMode,
    val activeCommandClass: PrivilegeCommandClass? = null,
    val fallbackOwner: PrivilegeExecutionLane? = null,
)

interface RootLaneStatusSource {
    val statuses: StateFlow<Map<PrivilegeExecutionLane, RootLaneStatus>>
}

sealed class PrivilegeExecutionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class PackageOperationBusy(val owner: PackageOperationOwner) :
    PrivilegeExecutionException("Package operation busy: $owner")
class ShellLaneUnavailable(val lane: PrivilegeExecutionLane, cause: Throwable? = null) :
    PrivilegeExecutionException("Root shell lane unavailable: $lane", cause)
class ShellLaneBusy(val owner: PrivilegeExecutionLane) :
    PrivilegeExecutionException("Root shell lane busy: $owner")
class ShellLaneDegraded(val lane: PrivilegeExecutionLane, cause: Throwable? = null) :
    PrivilegeExecutionException("Root shell lane degraded: $lane", cause)
class ShellTransportDied(val lane: PrivilegeExecutionLane, cause: Throwable? = null) :
    PrivilegeExecutionException("Root shell transport died: $lane", cause)
class ShellCommandTimedOut(val commandClass: PrivilegeCommandClass) :
    PrivilegeExecutionException("Root command timed out: ${commandClass.value}")
class ReinstallPostconditionFailed(val packageName: String) :
    PrivilegeExecutionException("Fix Store postcondition failed for $packageName")
class ShellCommandCancelled(
    val commandClass: PrivilegeCommandClass,
    cause: CancellationException,
) : CancellationException("Root command cancelled: ${commandClass.value}") {
    init { initCause(cause) }
}
class SweepInputMissing(requestId: String?) :
    PrivilegeExecutionException("Sweep input missing: ${requestId ?: "request id"}")
```

`PrivilegeCommandClass` must reject blank strings and ISO control characters. Do not store raw command text in any status or exception.

- [ ] **Step 4: Re-run the focused tests**

Expected: PASS.

- [ ] **Step 5: Commit the vocabulary**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/PrivilegeExecution.kt app/src/test/java/com/valhalla/thor/domain/model/PrivilegeExecutionTest.kt
git commit -m "feat(privilege): define execution lane contracts"
```

### Task 3: Add package-scoped operation coordination

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/repository/PackageOperationCoordinator.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/privilege/DefaultPackageOperationCoordinator.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/usecase/ManageAppUseCase.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/privilege/DefaultPackageOperationCoordinatorTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/usecase/ManageAppUseCaseTest.kt`

**Interfaces:**
- Consumes: `PackageOperationOwner`, admission constants, and existing `ManageAppUseCase` repository calls.
- Produces: the `PackageOperationCoordinator` interface and a process-singleton implementation; every public package mutation in `ManageAppUseCase` acquires exactly one lease.

- [ ] **Step 1: Write failing coordinator tests**

Cover these exact schedules with `StandardTestDispatcher`:

```kotlin
@Test fun `different packages run concurrently`()
@Test fun `same package reports current owner after zero admission timeout`()
@Test fun `sweep waits two seconds then reports busy`()
@Test fun `archive waits five seconds then reports busy`()
@Test fun `cancelled waiter never enters its block`()
@Test fun `entry is removed only after last waiter or owner releases it`()
```

Also add `ManageAppUseCase` tests proving `forceUnfreeze` holds one `UNFREEZE` lease across unsuspend and enable, rather than releasing between its two rungs.

- [ ] **Step 2: Run the two focused test classes**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinatorTest' \
  --tests 'com.valhalla.thor.domain.usecase.ManageAppUseCaseTest' \
  --rerun-tasks
```

Expected: FAIL because the coordinator and execution-context overloads do not exist.

- [ ] **Step 3: Implement keyed leases without a global package bottleneck**

Use a short state mutex around a map of package name to `{ mutex, owner, references }`; never hold the state mutex while executing the caller's block. `Duration.ZERO` uses `tryLock`; positive durations use `withTimeoutOrNull`. Increment the reference count before waiting and decrement it in `finally`, so cancellation cannot leak entries.

```kotlin
@Single(binds = [PackageOperationCoordinator::class])
internal class DefaultPackageOperationCoordinator : PackageOperationCoordinator {
    override suspend fun <T> withPackageLease(
        packageName: String,
        owner: PackageOperationOwner,
        admissionTimeout: Duration,
        block: suspend () -> T,
    ): PackageLeaseResult<T>
}
```

The busy result must read the owner while holding the map-state mutex. A cancelled waiter rethrows cancellation and never invokes `block`.

- [ ] **Step 4: Make `ManageAppUseCase` the lease boundary for ordinary mutations**

Add an optional `execution: PrivilegeExecutionContext = PrivilegeExecutionContext()` parameter to:

```kotlin
forceStop(packageName, execution)
clearCache(packageName, execution)
clearAppData(packageName, execution)
setAppDisabled(packageName, disabled, execution)
setAppSuspended(packageName, suspended, execution)
forceUnfreeze(packageName, execution)
uninstallApp(packageName, execution)
reinstallAppWithGoogle(packageName, execution)
```

Derive admission timeout from `execution.lane`. Map `PackageLeaseResult.Busy` to `Result.failure(PackageOperationBusy(owner))`. Implement private uncoordinated helpers so `forceUnfreeze` takes one lease and performs both rungs under it; do not call the public leased methods from inside another lease.

Archive use cases do not call these leased wrappers for their inner gateway phases; Task 7 holds one outer archive lease instead.

- [ ] **Step 5: Re-run tests and commit**

Expected: both classes PASS.

```bash
git add app/src/main/java/com/valhalla/thor/domain/repository/PackageOperationCoordinator.kt app/src/main/java/com/valhalla/thor/data/privilege/DefaultPackageOperationCoordinator.kt app/src/main/java/com/valhalla/thor/domain/usecase/ManageAppUseCase.kt app/src/test/java/com/valhalla/thor/data/privilege/DefaultPackageOperationCoordinatorTest.kt app/src/test/java/com/valhalla/thor/domain/usecase/ManageAppUseCaseTest.kt
git commit -m "feat(privilege): coordinate package mutations"
```

### Task 4: Wrap independent Odin shells behind a fakeable session

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/gateway/root/RootShellSession.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/gateway/root/OdinRootShellSession.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/gateway/root/OwnedRootShellExecutor.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/gateway/root/OwnedRootShellExecutorTest.kt`

**Interfaces:**
- Consumes: `Shell.Builder.create().build()`, `shell.newJob().add(command).submit(callback)`, `shell.isAlive`, `shell.close()`, and Task 2's lane types.
- Produces: `RootShellSession`, `RootShellSessionFactory`, `RootCommandExecutor`, `OdinRootShellSessionFactory`, and generation-safe `OwnedRootShellExecutor`.

- [ ] **Step 1: Write failing generation/cancellation tests with fake sessions**

Pin all of these behaviors:

```kotlin
@Test fun `healthy commands reuse one generation`()
@Test fun `only one job is submitted per lane at a time`()
@Test fun `waiter cancelled before mutex admission submits nothing`()
@Test fun `nonzero command exit does not replace a healthy generation`()
@Test fun `transport death closes and replaces only the used generation`()
@Test fun `external cancellation closes used generation and rethrows cancellation`()
@Test fun `deadline closes used generation and reports ShellCommandTimedOut`()
@Test fun `late cleanup from generation one cannot close generation two`()
@Test fun `mutating command is never retried after unknown transport outcome`()
```

The fake factory records opened sessions, submitted commands, close calls, and callback completion. Do not place raw command text in assertion failure messages; identify commands by `PrivilegeCommandClass`.

- [ ] **Step 2: Run the focused tests and see unresolved production types**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.data.gateway.root.OwnedRootShellExecutorTest' \
  --rerun-tasks
```

Expected: FAIL at compilation.

- [ ] **Step 3: Implement the Odin adapter**

`OdinRootShellSessionFactory.open()` must call `Shell.Builder.create().build()` on the injected IO dispatcher. `OdinRootShellSession.execute()` must bridge Odin's asynchronous `submit` callback with `suspendCancellableCoroutine`; a cancelled continuation ignores a late callback. Map stdout and stderr separately into `RootCommandResult`. A result representing “job not executed” or a dead `shell.isAlive` throws the data-layer `RootShellTransportException`; `OwnedRootShellExecutor` attaches its configured lane and exposes `ShellTransportDied(lane, cause)`. A normal positive/nonzero exit remains a normal `RootCommandResult`.

- [ ] **Step 4: Implement generation-safe ownership**

`OwnedRootShellExecutor` receives its `PrivilegeExecutionLane` in the constructor and has one outer `Mutex`, one nullable `(generation, session)`, and a monotonically increasing generation counter. It performs this sequence while holding the mutex:

```kotlin
val lease = healthySessionOrOpen()
try {
    executeWithOptionalTimeout(lease, command)
} catch (timeout: TimeoutCancellationException) {
    invalidateExactGeneration(lease.generation)
    throw ShellCommandTimedOut(command.execution.commandClass)
} catch (cancelled: CancellationException) {
    invalidateExactGeneration(lease.generation)
    throw ShellCommandCancelled(command.execution.commandClass, cancelled)
} catch (transport: RootShellTransportException) {
    invalidateExactGeneration(lease.generation)
    throw ShellTransportDied(lane, transport)
}
```

`invalidateExactGeneration` compares generation and session identity, then closes under `withContext(NonCancellable + ioDispatcher)`. It does not retry the command.

- [ ] **Step 5: Re-run the focused tests and commit**

Expected: PASS.

```bash
git add app/src/main/java/com/valhalla/thor/data/gateway/root/RootShellSession.kt app/src/main/java/com/valhalla/thor/data/gateway/root/OdinRootShellSession.kt app/src/main/java/com/valhalla/thor/data/gateway/root/OwnedRootShellExecutor.kt app/src/test/java/com/valhalla/thor/data/gateway/root/OwnedRootShellExecutorTest.kt
git commit -m "feat(root): own isolated archive and sweep shells"
```

### Task 5: Route lanes and expose visible degraded fallback

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/gateway/root/MainShellCommandExecutor.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/gateway/root/RootFallbackCoordinator.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/gateway/root/RootCommandRouter.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/gateway/root/DefaultRootLaneStatusSource.kt`
- Modify: `app/src/main/java/com/valhalla/thor/di/Modules.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/gateway/root/RootCommandRouterTest.kt`

**Interfaces:**
- Consumes: Odin `ShellRepository` only for MainShell, two qualified `OwnedRootShellExecutor` instances, and Task 2 lane status.
- Produces: one process-singleton `RootCommandRouter`; archive/sweep lane construction failures permanently degrade only that lane for the process lifetime.

- [ ] **Step 1: Write failing router tests**

Cover:

```kotlin
@Test fun `interactive always routes to MainShell`()
@Test fun `archive and sweep route to different owned executors`()
@Test fun `archive factory failure degrades archive only`()
@Test fun `degraded background lane uses coordinated MainShell`()
@Test fun `interactive command is promptly rejected while degraded archive owns MainShell`()
@Test fun `interactive command is promptly rejected while degraded sweep owns MainShell`()
@Test fun `sweep and archive fallback serialize when both lanes are degraded`()
@Test fun `cancelled degraded command drains active callback before releasing MainShell`()
@Test fun `timed out degraded sweep cannot execute after terminal failure is published`()
@Test fun `lane status never includes raw command or output`()
```

- [ ] **Step 2: Run the focused tests**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.data.gateway.root.RootCommandRouterTest' \
  --rerun-tasks
```

Expected: FAIL at compilation.

- [ ] **Step 3: Implement coordinated MainShell access**

`RootFallbackCoordinator` wraps every MainShell submission—ordinary interactive and degraded background—in one mutex. It records the active lane before submission and clears it in `finally`. Interactive admission uses `tryLock`; if a degraded archive/sweep owns the channel, throw `ShellLaneBusy(owner)`. Background fallback may wait for a short interactive command, but it must remain cancellation-aware before submission.

Once a degraded background command has been submitted to MainShell, cancellation or the sweep's 30-second deadline must not release the coordinator and report a terminal result while Odin can still execute the command later. Record the cancellation/timeout request, await that already-active callback under `NonCancellable`, then release the coordinator and rethrow cancellation or return `ShellCommandTimedOut`. The degraded UI remains visible while this drain is happening. This intentionally trades prompt command termination for truthful lifecycle on restrictive Root managers; an interactive command is rejected promptly during the drain instead of joining MainShell's FIFO.

`MainShellCommandExecutor` converts Odin `ShellResult` into `RootCommandResult`; it never treats a nonzero process exit as a transport exception and never logs command text or output.

- [ ] **Step 4: Implement lane routing and process-lifetime degradation**

```kotlin
@Single(binds = [RootCommandExecutor::class])
internal class RootCommandRouter(
    private val main: MainShellCommandExecutor,
    @Named("archive") private val archive: OwnedRootShellExecutor,
    @Named("sweep") private val sweep: OwnedRootShellExecutor,
    private val fallback: RootFallbackCoordinator,
    private val statuses: DefaultRootLaneStatusSource,
) : RootCommandExecutor
```

On the first dedicated-shell open failure, atomically mark that lane `DEGRADED`, retain the cause only for diagnostics without raw command data, and route later commands from that lane directly to fallback. A command already submitted to an owned generation follows Task 4's invalidation path rather than silently switching transport and replaying.

Provide the two qualified owned executors through `AppModule`; this is justified because the same implementation needs two explicit lane instances. Continue to provide the external Odin `ShellRepository` once for MainShell.

- [ ] **Step 5: Re-run tests and commit**

Expected: PASS.

```bash
git add app/src/main/java/com/valhalla/thor/data/gateway/root/MainShellCommandExecutor.kt app/src/main/java/com/valhalla/thor/data/gateway/root/RootFallbackCoordinator.kt app/src/main/java/com/valhalla/thor/data/gateway/root/RootCommandRouter.kt app/src/main/java/com/valhalla/thor/data/gateway/root/DefaultRootLaneStatusSource.kt app/src/main/java/com/valhalla/thor/di/Modules.kt app/src/test/java/com/valhalla/thor/data/gateway/root/RootCommandRouterTest.kt
git commit -m "feat(root): route commands across three lanes"
```

### Task 6: Centralize every Root command and propagate execution context

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/domain/gateway/SystemGateway.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/repository/SystemRepository.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/SystemRepositoryImpl.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/gateway/ShizukuSystemGateway.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/gateway/DhizukuSystemGateway.kt`
- Modify: `app/src/test/java/com/valhalla/thor/presentation/ViewModelTestDoubles.kt`
- Modify: `app/src/test/java/com/valhalla/thor/domain/usecase/FreezeAppUseCaseTest.kt`
- Modify: `app/src/test/java/com/valhalla/thor/data/freezer/BulkFreezeWorkerTest.kt`
- Modify: `app/src/test/java/com/valhalla/thor/domain/usecase/ComponentControlUseCaseTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/repository/SystemRepositorySurfaceTest.kt`
- Create: `app/src/test/java/com/valhalla/thor/data/gateway/RootSystemGatewayRoutingTest.kt`

**Interfaces:**
- Consumes: `PrivilegeExecutionContext` and `RootCommandExecutor`.
- Produces: explicit context propagation for package mutation and raw-shell methods; no `RootSystemGateway` call bypasses `RootCommandRouter`.

- [ ] **Step 1: Extend structural tests before changing interfaces**

Add source-surface assertions that reject these patterns in `RootSystemGateway.kt`:

```text
shellRepository.exec(
shellRepository.submit(
shellRepository.enqueue(
```

Allow `ShellRepository` only inside `MainShellCommandExecutor.kt`. Add compile-level fake assertions for context propagation through disable, suspend, force-stop, cache clear, reinstall, and `executeShellCommand`.

- [ ] **Step 2: Run structural tests and verify current bypasses fail**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.domain.repository.SystemRepositorySurfaceTest' \
  --tests 'com.valhalla.thor.data.gateway.RootSystemGatewayRoutingTest' \
  --rerun-tasks
```

Expected: FAIL on the six direct `shellRepository.exec` sites and missing context parameters.

- [ ] **Step 3: Propagate context through the relevant domain ports**

Add `execution: PrivilegeExecutionContext = PrivilegeExecutionContext()` to package mutation and raw-shell methods in `SystemRepository`; add the corresponding inherited parameter to `SystemGateway` methods that Root implements through shell commands. At minimum this covers:

```kotlin
forceStopApp(packageName, execution)
clearAppData(packageName, execution)
setAppDisabled(packageName, isDisabled, execution)
setAppSuspended(packageName, isSuspended, execution)
setAppRestricted(packageName, isRestricted, execution)
uninstallApp(packageName, execution)
reinstallAppWithGoogle(packageName, execution)
grantPermission(packageName, permissionName, execution)
revokePermission(packageName, permissionName, execution)
setComponentEnabled(packageName, className, state, userId, execution)
forceLaunchActivity(packageName, className, userId, execution)
stopService(packageName, className, userId, execution)
executeShellCommand(command, execution)
```

Also thread context through Root-only repository methods `clearCache`, `copyFileWithRoot`, and `getAppPaths`. Defaults preserve all existing interactive callers. Shizuku and Dhizuku accept the context and retain current transport behavior; they do not create Odin lanes.

- [ ] **Step 4: Replace all six direct Root submissions**

Inject `RootCommandExecutor` into `RootSystemGateway`. Route RootService reset, pm-path lookup, ordinary command execution, raw shell execution, install/session helpers, and internal probes through one private helper:

```kotlin
private suspend fun execute(
    command: String,
    context: PrivilegeExecutionContext,
): RootCommandResult = rootCommands.execute(RootCommand(command, context))
```

Assign a stable `PrivilegeCommandClass` at each command construction site. Keep command text local to the `RootCommand`; never include it in logs or failure messages. Convert `RootCommandResult` to existing public `Result<Unit>` or `(exitCode, combinedOutput)` shapes only at the gateway boundary.

- [ ] **Step 5: Update fakes and verify cancellation**

All fakes record the complete context. `SystemRepositoryImpl.runGatewayAction` must continue to rethrow `CancellationException`; add a test that a `ShellCommandCancelled` reaches the caller as cancellation instead of becoming `Result.failure`.

- [ ] **Step 6: Run focused tests and compile all JVM tests**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.domain.repository.SystemRepositorySurfaceTest' \
  --tests 'com.valhalla.thor.data.gateway.RootSystemGatewayRoutingTest' \
  --rerun-tasks
```

Expected: PASS, and source search returns zero direct Odin repository calls in `RootSystemGateway.kt`.

- [ ] **Step 7: Commit the execution seam**

Stage every file listed in this task explicitly:

```bash
git add \
  app/src/main/java/com/valhalla/thor/domain/gateway/SystemGateway.kt \
  app/src/main/java/com/valhalla/thor/domain/repository/SystemRepository.kt \
  app/src/main/java/com/valhalla/thor/data/repository/SystemRepositoryImpl.kt \
  app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt \
  app/src/main/java/com/valhalla/thor/data/gateway/ShizukuSystemGateway.kt \
  app/src/main/java/com/valhalla/thor/data/gateway/DhizukuSystemGateway.kt \
  app/src/test/java/com/valhalla/thor/presentation/ViewModelTestDoubles.kt \
  app/src/test/java/com/valhalla/thor/domain/usecase/FreezeAppUseCaseTest.kt \
  app/src/test/java/com/valhalla/thor/data/freezer/BulkFreezeWorkerTest.kt \
  app/src/test/java/com/valhalla/thor/domain/usecase/ComponentControlUseCaseTest.kt \
  app/src/test/java/com/valhalla/thor/domain/repository/SystemRepositorySurfaceTest.kt \
  app/src/test/java/com/valhalla/thor/data/gateway/RootSystemGatewayRoutingTest.kt
git diff --cached --name-only
git commit -m "refactor(root): centralize privileged command routing"
```

Before committing, verify the staged list contains only those twelve files.

### Task 7: Move archives to the archive lane, redact logs, and hold full-operation leases

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImpl.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/usecase/BackupAppArchiveUseCase.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/usecase/RestoreAppArchiveUseCase.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImplTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/usecase/BackupAppArchiveUseCaseTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/usecase/RestoreAppArchiveUseCaseTest.kt`

**Interfaces:**
- Consumes: `PrivilegeExecutionLane.ARCHIVE`, stable archive command classes, and `PackageOperationCoordinator`.
- Produces: every privileged archive phase uses the dedicated archive shell; backup/restore exclude same-package mutations across their complete logical operation.

- [ ] **Step 1: Add failing route, privacy, and lease tests**

Pin every archive command label and lane:

```text
archive.force_stop
archive.list
archive.verify
archive.tar
archive.stage_chown
archive.extract
archive.swap
archive.chown
archive.restorecon
```

Assert each captured `PrivilegeExecutionContext` has `lane=ARCHIVE`, the package name, and no fixed command timeout. Add a source test that rejects logs containing `$command`, `result.second`, or raw archive output. In both use-case tests, block one archive phase and assert a same-package zero-time mutation receives `PackageOperationBusy(ARCHIVE_BACKUP|ARCHIVE_RESTORE)` while a different package proceeds.

- [ ] **Step 2: Run the three focused tests**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.data.repository.AppDataArchiveGatewayImplTest' \
  --tests 'com.valhalla.thor.domain.usecase.BackupAppArchiveUseCaseTest' \
  --tests 'com.valhalla.thor.domain.usecase.RestoreAppArchiveUseCaseTest' \
  --rerun-tasks
```

Expected: FAIL because archive commands still default to MainShell and use cases do not own leases.

- [ ] **Step 3: Route and redact every archive phase**

Create contexts with `lane=ARCHIVE`, package name, and the stable label above. Preserve `NonCancellable + ioDispatcher` cleanup. Replace raw-command/output logging with structured metadata such as:

```kotlin
Logger.d(TAG, "archive command started class=${execution.commandClass.value} package=$packageName")
Logger.e(TAG, "archive command failed class=${execution.commandClass.value} package=$packageName exitCode=${result?.first}")
```

Do not include archive paths or shell output.

- [ ] **Step 4: Wrap complete backup and restore use cases**

Inject `PackageOperationCoordinator`. Acquire `ARCHIVE_BACKUP`/`ARCHIVE_RESTORE` with `ARCHIVE_ADMISSION` before staging begins and retain the lease through validation, force-stop/install-first behavior, archive commands, encryption/decryption, swap, ownership repair, relabeling, and terminal cleanup. Map `Busy` into the existing typed backup/restore failure surface; do not throw it away as a generic message. Keep cancellation rethrow and existing `finally` cleanup/deflater shutdown.

- [ ] **Step 5: Re-run tests and commit**

Expected: PASS.

```bash
git add app/src/main/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImpl.kt app/src/main/java/com/valhalla/thor/domain/usecase/BackupAppArchiveUseCase.kt app/src/main/java/com/valhalla/thor/domain/usecase/RestoreAppArchiveUseCase.kt app/src/test/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImplTest.kt app/src/test/java/com/valhalla/thor/domain/usecase/BackupAppArchiveUseCaseTest.kt app/src/test/java/com/valhalla/thor/domain/usecase/RestoreAppArchiveUseCaseTest.kt
git commit -m "fix(archive): isolate root commands from interactive work"
```

### Task 8: Gate the architecture on physical Root shell behavior

**Files:**
- Modify only if required by the device result: `app/src/main/java/com/valhalla/thor/data/gateway/root/OdinRootShellSession.kt`
- Modify only if required by the device result: `app/src/main/java/com/valhalla/thor/data/gateway/root/OwnedRootShellExecutor.kt`
- Test if fallback is required: `app/src/test/java/com/valhalla/thor/data/gateway/root/OwnedRootShellExecutorTest.kt`
- Record results in: `docs/superpowers/plans/2026-08-28-worker-shell-lanes.md` under a dated execution-results appendix.

**Interfaces:**
- Consumes: the archive and interactive route built in Tasks 4–7.
- Produces: physical evidence that independent `su` sessions are concurrent and that cancellation kills the active child, or a tested PID-scoped termination fallback.

- [ ] **Step 1: Install a debug build on a rooted physical device**

Run through context-mode:

```bash
./gradlew :app:assembleFossDebug
adb install -r app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

Expected: build and install succeed with the device's Root manager granting Thor.

- [ ] **Step 2: Prove archive and interactive commands use different processes**

Instrument debug-only structured logs with lane and shell generation, start a deliberately long archive backup, then unfreeze a different app. Verify the unfreeze completes before the archive command ends and the logs show `ARCHIVE` and `INTERACTIVE` on independent shell generations/processes. Remove any temporary debug-only instrumentation before commit.

- [ ] **Step 3: Prove cancellation terminates the active archive child**

Start an archive large enough to keep `tar` active, capture its child PID through a debug-only PID file that contains no user path or secret, read the PID before cancellation, cancel the archive, and test that exact PID:

```bash
PID="$(adb shell su -c 'cat /data/local/tmp/thor-archive-child.pid' | tr -d '\r\n')"
test -n "$PID"
adb shell su -c "test ! -d /proc/$PID"
```

Expected: all three commands exit 0; the exact child no longer exists, partial plaintext staging is removed, and the next archive command opens a fresh generation.

- [ ] **Step 4: If the child survives, implement PID-scoped termination before continuing**

Wrap each owned-lane command with a generated execution token and a mode-0600 PID file under `/data/local/tmp`. On cancellation/timeout, close the used shell, open a fresh one-shot control shell with `Shell.Builder.create().build()`, verify `/proc/<pid>/cmdline` still carries that execution token, send `TERM` to that PID and its recorded children, wait a bounded grace interval, send `KILL` only to survivors with the same token, delete the PID file, and close the control shell. Never use `pkill` by package name or a broad process pattern.

Add fake-process tests proving PID reuse/token mismatch causes no kill, matching descendants receive TERM then KILL, and cleanup cannot kill a replacement generation. Repeat Step 3 until the child is gone.

- [ ] **Step 5: Record the observed Root manager/device/API and result**

Append factual evidence only: device model, Android API, Root manager/version, whether extra `su` sessions opened, unfreeze latency while tar ran, and whether `Shell.close()` alone killed the child. Do not record package data paths, command strings, or command output.

- [ ] **Step 6: Commit only if production code or the plan appendix changed**

Stage the exact changed files, then:

```bash
git commit -m "test(root): verify shell lane cancellation on device"
```

### Task 9: Add the durable sweep model and Room 7→8 schema

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/PrivilegeSweep.kt`
- Create: `app/src/main/java/com/valhalla/thor/domain/repository/PrivilegeSweepStore.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/source/local/room/SweepRequestEntity.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/source/local/room/SweepTargetEntity.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/source/local/room/PrivilegeSweepDao.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/repository/RoomPrivilegeSweepStore.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/source/local/room/AppDatabase.kt`
- Modify: `app/src/main/java/com/valhalla/thor/di/Modules.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/com/valhalla/thor/domain/model/PrivilegeSweepTest.kt`
- Create: `app/src/androidTest/java/com/valhalla/thor/data/source/local/room/SweepMigrationTest.kt`
- Create: `app/schemas/com.valhalla.thor.data.source.local.room.AppDatabase/8.json`

**Interfaces:**
- Consumes: Room conventions in `AppDatabase`, `FreezerMode`, Task 2 status vocabulary.
- Produces: canonical sweep snapshots, aggregate result persistence, conditional terminal writes, and a tested AutoMigration from schema 7 to 8.

- [ ] **Step 1: Add Room migration-test dependency and failing model tests**

Add `androidx.room:room-testing:2.8.4` through the version catalog and `androidTestImplementation`. Pin normalization:

```kotlin
assertEquals(
    listOf("A.pkg", "a.pkg", "z.pkg"),
    normalizeSweepTargets(listOf("z.pkg", "A.pkg", "a.pkg", "z.pkg")),
)
assertFailsWith<IllegalArgumentException> { normalizeSweepTargets(listOf("ok.pkg", " ")) }
```

Also assert `FREEZE` requires a resolved `FreezerMode`, non-freeze operations persist `freezerMode=null`, and force-stop/uninstall cannot be represented by `PrivilegeSweepOperation`.

- [ ] **Step 2: Run the model test and verify it fails**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.domain.model.PrivilegeSweepTest' \
  --rerun-tasks
```

Expected: FAIL at compilation.

- [ ] **Step 3: Implement the two-table schema exactly**

Create `sweep_requests` with:

```text
request_id TEXT PRIMARY KEY
work_id TEXT NOT NULL UNIQUE
operation TEXT NOT NULL
freezer_mode TEXT NULL
user_id INTEGER NOT NULL
source_surface TEXT NOT NULL
created_at_epoch_ms INTEGER NOT NULL
terminal_state TEXT NULL
succeeded INTEGER NULL
failed INTEGER NULL
busy INTEGER NULL
unresolved INTEGER NULL
terminal_at_epoch_ms INTEGER NULL
retain_until_epoch_ms INTEGER NULL
```

Create `sweep_targets` with `(request_id, ordinal)` as the primary key, `package_name TEXT NOT NULL`, a foreign key with `ON DELETE CASCADE`, and an index on `request_id`. Do not add per-target status, error text, or command output columns.

Set `AppDatabase.version=8`, append `AutoMigration(from = 7, to = 8)`, add both entities and `privilegeSweepDao()`, and provide the DAO from `AppModule`.

- [ ] **Step 4: Define the store's atomic contract**

```kotlin
interface PrivilegeSweepStore {
    suspend fun createOrFindEquivalent(snapshot: NewPrivilegeSweepSnapshot): SweepCreateResult
    suspend fun load(requestId: UUID): StoredPrivilegeSweep?
    fun observe(requestId: UUID): Flow<StoredPrivilegeSweep?>
    fun observeRetained(): Flow<List<StoredPrivilegeSweep>>
    suspend fun resetForRun(requestId: UUID): StoredPrivilegeSweep?
    suspend fun recordAttempt(requestId: UUID, outcome: SweepAttemptOutcome): Boolean
    suspend fun finish(requestId: UUID, terminal: StoredSweepTerminal, nowMs: Long): Boolean
    suspend fun cancelAllNonterminal(nowMs: Long): List<UUID>
    suspend fun delete(requestId: UUID)
    suspend fun deleteExpired(nowMs: Long): Int
}
```

`createOrFindEquivalent` is one Room transaction: compare all nonterminal candidates by operation, resolved freezer mode, user ID, and complete canonical target list; source is not part of coalescing. If no exact match exists, insert request and targets atomically. `resetForRun` sets all four aggregates to zero only while `terminal_state IS NULL`. Every increment and terminal update includes `WHERE terminal_state IS NULL`; late completion cannot overwrite cancellation. Cancellation computes `unresolved = total - succeeded - failed - busy` transactionally.

- [ ] **Step 5: Write and run the migration test**

Create a schema-7 database containing rows in every existing table, run generated 7→8 migration, validate schema 8, assert old rows remain, and assert both new tables accept and cascade-delete a request/targets pair.

```bash
./gradlew :app:connectedFossDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.data.source.local.room.SweepMigrationTest
```

Expected: PASS on a connected API 28+ device/emulator and export `8.json`.

- [ ] **Step 6: Run model tests and commit**

Run the model test from Step 2; expected PASS. Stage every named schema/model/store/dependency file and schema JSON explicitly, then:

```bash
git commit -m "feat(sweep): persist durable request snapshots"
```

### Task 10: Build notification-gated enqueue, observation, and reconciliation

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/repository/PrivilegeSweepController.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepController.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepReconciler.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobLauncher.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobNotifications.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/model/ThorJob.kt`
- Modify: `app/src/main/java/com/valhalla/thor/ThorApplication.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepControllerTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/ThorJobTest.kt`

**Interfaces:**
- Consumes: `PrivilegeSweepStore`, `WorkManager`, `enqueueUniqueJob`, notification state, `RootLaneStatusSource`.
- Produces: `PrivilegeSweepController`, one `request_id` WorkRequest input, exact coalescing, reconstructable lifecycle state, and startup/launch reconciliation.

- [ ] **Step 1: Add failing launcher/observer tests**

Test:

```kotlin
@Test fun `disabled app notifications reject before snapshot or enqueue`()
@Test fun `missing post notifications permission rejects before persistence`()
@Test fun `disabled thor jobs channel rejects before persistence`()
@Test fun `accepted request stores snapshot before enqueue`()
@Test fun `work input contains request id and no package names`()
@Test fun `exact canonical duplicate coalesces onto existing work id`()
@Test fun `opposite operation never coalesces`()
@Test fun `enqueue rejection deletes newly inserted snapshot`()
@Test fun `observer combines Room terminal counts with WorkInfo phase`()
@Test fun `missing WorkInfo becomes observer failure rather than endless running`()
@Test fun `reconciler terminalizes orphaned nonterminal snapshots and prunes expired rows`()
```

Add a `ThorJobTest` assertion that the old three ordinals are unchanged and `PRIVILEGE_SWEEP` is last.

- [ ] **Step 2: Run focused tests and verify failure**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.data.freezer.DefaultPrivilegeSweepControllerTest' \
  --tests 'com.valhalla.thor.domain.model.ThorJobTest' \
  --rerun-tasks
```

Expected: FAIL at compilation and on the missing enum value.

- [ ] **Step 3: Append the job kind and implement a complete notification gate**

Append only:

```kotlin
PRIVILEGE_SWEEP("privilege-sweep"),
```

Keep the first three ordinals unchanged. Correct `ThorJobStage.ACTING` KDoc so its examples name only admitted sweep actions (freeze, unfreeze, per-app cache clear, and verified Fix Store); remove the stale force-stop example.

Add `ThorJobNotifications.canPostJobs()` that requires:

```text
NotificationManagerCompat.areNotificationsEnabled() == true
API < 33 or POST_NOTIFICATIONS is granted
API < 26 or channel "thor.jobs" has importance != IMPORTANCE_NONE
```

Ensure the channel exists before querying its importance. Do not persist or enqueue a sweep when the gate fails.

- [ ] **Step 4: Implement launch ordering and exact coalescing**

`DefaultPrivilegeSweepController.launch` performs:

```text
1. Validate privilege and notification capability.
2. Canonicalize targets and reject an empty result.
3. Generate request UUID and WorkRequest UUID.
4. Transactionally return an equivalent nonterminal snapshot or insert the new snapshot.
5. If coalesced, return Accepted(existing ids, coalesced=true) without enqueueing.
6. Build PrivilegeSweepWorker input containing only sweep_request_id.
7. Enqueue on THOR_SWEEP_CHAIN with APPEND_OR_REPLACE and await the Operation.
8. On enqueue rejection, delete the new snapshot and return EnqueueFailed.
9. Return Accepted(new ids, coalesced=false).
```

Construct the `OneTimeWorkRequest` with the generated work UUID before inserting, so `work_id` is never nullable.

- [ ] **Step 5: Implement durable observation and reconciliation**

Combine each retained Room snapshot with `WorkManager.getWorkInfoByIdFlow(workId)` and `RootLaneStatusSource`:

```text
nonterminal + BLOCKED/ENQUEUED -> QUEUED
nonterminal + RUNNING -> RUNNING
persisted SUCCEEDED/PARTIAL/CANCELLED/FAILED -> same terminal phase
nonterminal + WorkInfo CANCELLED -> observer shows cancellation; reconciler persists CANCELLED with unresolved remainder
nonterminal + WorkInfo SUCCEEDED/FAILED -> observer failure; reconciler persists FAILED with unresolved remainder
nonterminal + absent WorkInfo -> observer failure; reconciler persists FAILED with unresolved remainder
missing Room snapshot -> observe(requestId) emits null; presentation renders observer failure
```

Use `SWEEP_RESULT_RETENTION = 24.hours`. `PrivilegeSweepReconciler` repairs absent/terminal WorkManager rows before deleting expired terminal snapshots. It never overwrites an existing Room terminal state. Inject it into `ThorApplication` and invoke it from the existing application scope after Koin startup; also invoke it at the start of every launch. Cancellation is rethrown, not logged as an ordinary startup failure.

- [ ] **Step 6: Re-run tests and commit**

Expected: PASS.

```bash
git add app/src/main/java/com/valhalla/thor/domain/repository/PrivilegeSweepController.kt app/src/main/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepController.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepReconciler.kt app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobLauncher.kt app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobNotifications.kt app/src/main/java/com/valhalla/thor/domain/model/ThorJob.kt app/src/main/java/com/valhalla/thor/ThorApplication.kt app/src/test/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepControllerTest.kt app/src/test/java/com/valhalla/thor/domain/model/ThorJobTest.kt
git commit -m "feat(sweep): enqueue and observe durable requests"
```

### Task 11: Make sweep cancellation queue-wide and durable

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/SweepQueueCanceller.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/SweepQueueCancelReceiver.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobNotifications.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/valhalla/thor/data/freezer/SweepQueueCancellerTest.kt`
- Test: `app/src/androidTest/java/com/valhalla/thor/data/freezer/SweepQueueCancelReceiverTest.kt`

**Interfaces:**
- Consumes: `PrivilegeSweepStore.cancelAllNonterminal`, `WorkManager.cancelUniqueWork(THOR_SWEEP_CHAIN)`, and `ThorApplication`'s application scope.
- Produces: a non-exported broadcast receiver and a notification action labelled “Cancel sweep queue.”

- [ ] **Step 1: Write failing cancellation-order tests**

Assert:

```kotlin
@Test fun `all nonterminal requests are marked cancelled before WorkManager cancellation`()
@Test fun `terminal rows are not overwritten`()
@Test fun `queued request becomes cancelled with all targets unresolved`()
@Test fun `running request preserves counts and derives unresolved remainder`()
@Test fun `repeated queue cancellation is idempotent`()
```

The instrumentation test sends the explicit broadcast and verifies only Thor can address the non-exported receiver.

- [ ] **Step 2: Run focused JVM tests**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.data.freezer.SweepQueueCancellerTest' \
  --rerun-tasks
```

Expected: FAIL at compilation.

- [ ] **Step 3: Implement durable cancellation before WorkManager cancellation**

`SweepQueueCanceller.cancelQueue()` runs in the application scope:

```kotlin
store.cancelAllNonterminal(clock.nowEpochMilliseconds())
WorkManager.getInstance(context).cancelUniqueWork(THOR_SWEEP_CHAIN).result.await()
```

Use `goAsync()` in `SweepQueueCancelReceiver`, resolve the singleton canceller from Koin, and call `finish()` in `finally`. Marking Room first means a Worker that races with cancellation sees terminal state and starts no later package action.

- [ ] **Step 4: Replace only sweep notification cancellation**

For `PRIVILEGE_SWEEP`, build an immutable explicit broadcast `PendingIntent` to the receiver and use the localized label `Cancel sweep queue`. Preserve `WorkManager.createCancelPendingIntent(jobId)` for archive backup, archive restore, and export.

Declare:

```xml
<receiver
    android:name=".data.freezer.SweepQueueCancelReceiver"
    android:exported="false" />
```

- [ ] **Step 5: Run JVM and instrumentation tests, then commit**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.data.freezer.SweepQueueCancellerTest' \
  --rerun-tasks
./gradlew :app:connectedFossDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.data.freezer.SweepQueueCancelReceiverTest
```

Expected: PASS.

```bash
git add app/src/main/java/com/valhalla/thor/data/freezer/SweepQueueCanceller.kt app/src/main/java/com/valhalla/thor/data/freezer/SweepQueueCancelReceiver.kt app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobNotifications.kt app/src/main/AndroidManifest.xml app/src/test/java/com/valhalla/thor/data/freezer/SweepQueueCancellerTest.kt app/src/androidTest/java/com/valhalla/thor/data/freezer/SweepQueueCancelReceiverTest.kt
git commit -m "feat(sweep): cancel the durable queue safely"
```

### Task 12: Implement one generic non-foreground sweep Worker

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepItemExecutor.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorker.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobNotifications.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepItemExecutorTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorkerTest.kt`
- Create: `app/src/androidTest/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorkerIntegrationTest.kt`

**Interfaces:**
- Consumes: request snapshots, `ManageAppUseCase`, Task 3 package leases, Task 4 sweep lane, and `ThorJobWorker` lifecycle helpers.
- Produces: sequential per-package execution, conditional aggregate persistence, truthful cancellation results, and no retry path.

- [ ] **Step 1: Add the WorkManager test dependency and failing reducer/Worker tests**

Add `androidx.work:work-testing` using the existing WorkManager version and `androidTestImplementation`. JVM tests cover:

```kotlin
@Test fun `missing request id fails permanently without retry`()
@Test fun `missing snapshot fails permanently without retry`()
@Test fun `genuine rerun resets aggregate counts before first attempt`()
@Test fun `freeze dispatches configured suspend or disable action on sweep lane`()
@Test fun `unfreeze runs the composite unsuspend then enable operation`()
@Test fun `busy package increments busy and continues`()
@Test fun `ordinary failure increments failed and continues`()
@Test fun `success increments succeeded`()
@Test fun `terminal phase is success only when every target succeeds`()
@Test fun `late completion cannot overwrite queue cancellation`()
@Test fun `cancellation notes partial and unresolved counts then rethrows`()
@Test fun `worker never returns Result retry`()
```

- [ ] **Step 2: Run focused tests and verify failure**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepItemExecutorTest' \
  --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepWorkerTest' \
  --rerun-tasks
```

Expected: FAIL at compilation.

- [ ] **Step 3: Implement a pure per-package executor**

For every package, create:

```kotlin
PrivilegeExecutionContext(
    lane = PrivilegeExecutionLane.SWEEP,
    commandClass = PrivilegeCommandClass("sweep.${operation.name.lowercase()}") ,
    packageName = packageName,
    workRequestId = workId,
    sweepRequestId = requestId,
    commandTimeout = PrivilegeExecutionTimeouts.SWEEP_COMMAND,
)
```

Revalidate current package state immediately before acting. Dispatch only `FREEZE`, `UNFREEZE`, `CLEAR_CACHE`, and `REINSTALL`. Convert `PackageOperationBusy` to `SweepAttemptOutcome.BUSY`; normal action failure to `FAILED`; success to `SUCCEEDED`. Rethrow cancellation.

- [ ] **Step 4: Implement `PrivilegeSweepWorker` on the existing base**

```kotlin
internal class PrivilegeSweepWorker(...) : ThorJobWorker(...) {
    override val kind = ThorJobKind.PRIVILEGE_SWEEP
    override val runsForeground = false
    override val sheetTarget: JobSheetTarget? = null
}
```

`runJob()` reads only `SWEEP_REQUEST_ID_KEY`, rejects a blank/malformed UUID with permanent `Result.failure()`, and loads the snapshot. A missing snapshot is a permanent failure. A snapshot already carrying `terminalState` performs no package action and returns the matching terminal WorkManager result. Otherwise call `resetForRun`, iterate canonical targets in ordinal order, check `ensureActive()` before lease admission and before each action, update Room after every attempt, and call `publish(ThorJobStage.ACTING, processed, total)`.

After the loop, persist `SUCCEEDED` only when all targets succeeded; persist `PARTIAL` when at least one target is failed or busy, including the all-failed/all-busy cases. Return `Result.success()` for these completed sweeps because their operation-level outcome is already represented durably; reserve `Result.failure()` for malformed input, a missing snapshot, or an execution-engine failure that prevents truthful iteration. Call `noteResult` with localized aggregate counts before returning. Catch `CancellationException`, then under `withContext(NonCancellable + ioDispatcher)` conditionally persist `CANCELLED`, calculate `unresolved`, and call `noteResult`; finally rethrow the original cancellation. Never use `Result.retry()`.

Root executes sequentially because one sweep shell owns one command at a time. Use the same sequential loop for Shizuku/Dhizuku in this first cut so all transports share ordering/count semantics.

- [ ] **Step 5: Complete notification title/icon mappings**

Map `PRIVILEGE_SWEEP` in every exhaustive `when`: use a freeze/bulk-action 24dp small icon already present, a localized “Applying app actions” title, and the queue-wide action from Task 11. Add a source test that all four `ThorJobKind` values are covered.

- [ ] **Step 6: Run WorkManager integration tests**

Use `WorkManagerTestInitHelper` and `TestDriver` to verify enqueue→RUNNING→terminal observation, process-style recreation from the same in-memory Room database, cancellation-before-start, cancellation-while-running, and no hidden FGS startup for `runsForeground=false`.

```bash
./gradlew :app:connectedFossDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.data.freezer.PrivilegeSweepWorkerIntegrationTest
```

Expected: PASS.

- [ ] **Step 7: Re-run JVM tests and commit**

Expected: PASS.

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepItemExecutor.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorker.kt app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobNotifications.kt app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepItemExecutorTest.kt app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorkerTest.kt app/src/androidTest/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorkerIntegrationTest.kt
git commit -m "feat(sweep): execute durable privilege sweeps"
```

### Task 13: Extract target resolution and retire the process-local execution engine

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepTargetResolver.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/model/BulkFreeze.kt`
- Delete after consumers migrate in Tasks 14–16: `app/src/main/java/com/valhalla/thor/domain/repository/BulkFreezeController.kt`
- Delete after consumers migrate in Tasks 14–16: `app/src/main/java/com/valhalla/thor/data/freezer/BulkFreezeRunner.kt`
- Modify: `app/src/test/java/com/valhalla/thor/domain/model/BulkFreezeTest.kt`
- Replace: `app/src/test/java/com/valhalla/thor/data/freezer/BulkFreezeWorkerTest.kt`
- Delete: `app/src/test/java/com/valhalla/thor/data/freezer/CoalesceTargetIndexTest.kt`

**Interfaces:**
- Consumes: current `targetsFor`, `freezableCandidates`, `AppFreezeStateReader`, UAD snapshot logic, watchlist/profile repositories, preferences, and `thorUserId`.
- Produces: target resolution only; WorkManager/Room owns mutation, coalescing, lifecycle, timing, and results.

- [ ] **Step 1: Rewrite tests around the resolver rather than the runner**

Pin:

```kotlin
@Test fun `watchlist resolves current members before enqueue`()
@Test fun `profile resolves current members before enqueue`()
@Test fun `freeze applies UAD and freeze tier safety filters`()
@Test fun `unfreeze does not apply the freeze safety block`()
@Test fun `resolved freezer mode and user id are snapshotted`()
@Test fun `selected package list is passed explicitly without BulkScope Selection`()
```

Keep `BulkScope` exactly `Watchlist` and `Profile`; do not add `BulkScope.Selection`.

- [ ] **Step 2: Run the rewritten focused tests**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepTargetResolverTest' \
  --tests 'com.valhalla.thor.domain.model.BulkFreezeTest' \
  --rerun-tasks
```

Expected: FAIL because the resolver does not exist.

- [ ] **Step 3: Extract target resolution with no execution ownership**

Provide:

```kotlin
suspend fun resolve(
    request: BulkRequest,
    source: PrivilegeSweepSource,
): PrivilegeSweepSpec

suspend fun resolveSelection(
    operation: PrivilegeSweepOperation,
    packageNames: Collection<String>,
    source: PrivilegeSweepSource,
    freezerMode: FreezerMode? = null,
): PrivilegeSweepSpec
```

The first method reuses watchlist/profile lookup and freeze candidate/UAD filtering. The second accepts already selected package names. Both resolve preference-backed freezer mode and `thorUserId` before enqueue, then rely on `normalizeSweepTargets`.

- [ ] **Step 4: Remove runner-owned behavior only after the final consumer compiles**

Tasks 14–16 first move every production consumer to `PrivilegeSweepController`. Then delete:

```text
BulkFreezeController
BulkFreezeRunner
MAX_CONCURRENT
DEADLINE_MS
CANCEL_GRACE_MS
RESULT_TTL_MS
SWEEP_GRACE_MS
in-memory coalescing
parked result mutation engine
```

Retain reusable pure freeze-state/candidate functions in focused files. Do not leave a compatibility path that still mutates packages in a process-local coroutine.

- [ ] **Step 5: Commit this task together with Tasks 14–16's consumer migration**

Do not commit a revision in which production references deleted types. The combined commit command is in Task 16.

### Task 14: Migrate Main and App List selection actions

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt`
- Modify: `app/src/test/java/com/valhalla/thor/presentation/main/MainViewModelTest.kt`
- Modify: `app/src/test/java/com/valhalla/thor/presentation/appList/AppListViewModelTest.kt`
- Modify: `app/src/test/java/com/valhalla/thor/presentation/ViewModelTestDoubles.kt`

**Interfaces:**
- Consumes: `PrivilegeSweepTargetResolver.resolveSelection`, `PrivilegeSweepController.launch/observe`, and retained direct `ManageAppUseCase` actions.
- Produces: durable freeze/unfreeze selection launch and observation; explicit Suspend/Unsuspend, force-stop, uninstall, sharing, and multi-app export remain direct.

- [ ] **Step 1: Replace the fake controller and add failing ViewModel tests**

The fake stores launch specs and exposes Room/Work-style status flows by request ID. Tests pin:

```kotlin
@Test fun `freeze selection launches one durable sweep`()
@Test fun `unfreeze selection launches one durable sweep`()
@Test fun `selected names are resolved before enqueue`()
@Test fun `notification rejection clears launch state and shows actionable error`()
@Test fun `queued running partial cancelled and observer failure statuses reach UI`()
@Test fun `active retained request reconnects without launching duplicate work`()
@Test fun `explicit suspend and unsuspend remain direct`()
@Test fun `force stop uninstall share and export remain direct`()
```

- [ ] **Step 2: Run focused tests and verify old loops fail the new contract**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.presentation.main.MainViewModelTest' \
  --tests 'com.valhalla.thor.presentation.appList.AppListViewModelTest' \
  --rerun-tasks
```

Expected: FAIL because the ViewModels still execute their own loops.

- [ ] **Step 3: Launch durable eligible operations**

For freeze/unfreeze, snapshot selected package names, resolve a `PrivilegeSweepSpec`, call `launch`, retain the accepted request UUID in existing saved/UI state, and collect `observe(requestId)`. On process recreation, first reconnect to the retained request ID; if none is retained, use `observeLatest(MAIN|APP_LIST)` to surface still-active work without enqueueing.

Delete optimistic “operation succeeded” package-state patching. Refresh authoritative app/freezer state when a persisted attempt or terminal status arrives.

- [ ] **Step 4: Keep excluded actions on their current direct path**

Do not encode these as `PrivilegeSweepOperation`: force-stop, uninstall, share, multi-app export, whole-device trim, and explicit Suspend/Unsuspend. They still use Task 3's package coordinator where they mutate one package.

- [ ] **Step 5: Re-run focused tests**

Expected: PASS.

### Task 15: Migrate Freezer profiles/watchlist and Settings restore-all

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezeProfilesSheet.kt`
- Modify: `app/src/test/java/com/valhalla/thor/presentation/freezer/FreezerViewModelTest.kt`
- Modify: `app/src/test/java/com/valhalla/thor/presentation/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `PrivilegeSweepTargetResolver.resolve(BulkRequest, source)` and durable controller status.
- Produces: profile/watchlist actions and cross-app single-user “restore all” survive process death.

- [ ] **Step 1: Add failing durable lifecycle tests**

Test profile and watchlist target snapshots, configured freezer mode, exact coalescing, process recreation, partial/busy summaries, and queue cancellation. For Settings, assert restore-all uses all package rows for `thorUserId` and is described as cross-app/single-user, never device-wide.

- [ ] **Step 2: Run focused tests**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.presentation.freezer.FreezerViewModelTest' \
  --tests 'com.valhalla.thor.presentation.settings.SettingsViewModelTest' \
  --rerun-tasks
```

Expected: FAIL because both ViewModels still depend on `Deferred<BulkOutcome>`/process-local running requests.

- [ ] **Step 3: Replace process-local running requests**

Observe `PrivilegeSweepController.activeRequests` and retained terminal rows. `FreezerUiState.runningRequests` becomes `List<PrivilegeSweepStatus>`. Profile/watchlist commands resolve targets before launch. Settings restore-all resolves the current user's complete stored package set, enqueues `UNFREEZE`, and acknowledges `QUEUED` instead of awaiting completion in the ViewModel coroutine.

- [ ] **Step 4: Re-run focused tests**

Expected: PASS.

### Task 16: Migrate QS tile and launcher shortcuts, then delete `BulkFreezeRunner`

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/tile/FreezerTileService.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/tile/TileVisual.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/launcher/FreezerLaunchActivity.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/launcher/FreezerShortcutManager.kt`
- Modify: `app/src/test/java/com/valhalla/thor/presentation/tile/TileVisualTest.kt`
- Create: `app/src/test/java/com/valhalla/thor/presentation/tile/FreezerTileServiceTest.kt`
- Create: `app/src/test/java/com/valhalla/thor/presentation/launcher/FreezerLaunchActivityTest.kt`
- Create: `app/src/test/java/com/valhalla/thor/data/launcher/FreezerShortcutManagerTest.kt`
- Delete the runner/controller/tests named in Task 13.

**Interfaces:**
- Consumes: durable controller, target resolver, Work/Room status.
- Produces: prompt enqueue acknowledgement for shortcut/tile surfaces and no remaining process-local bulk mutation engine.

- [ ] **Step 1: Add failing tile/shortcut tests**

Pin:

```kotlin
@Test fun `tile enqueues and returns without awaiting sweep completion`()
@Test fun `tile visual reflects queued running and latest retained terminal count`()
@Test fun `shortcut finishes within report window after accepted enqueue`()
@Test fun `shortcut reports notification gate rejection visibly`()
@Test fun `neither surface starts a second request after reconnect`()
```

- [ ] **Step 2: Run focused tests**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.presentation.tile.TileVisualTest' \
  --tests 'com.valhalla.thor.presentation.tile.FreezerTileServiceTest' \
  --tests 'com.valhalla.thor.presentation.launcher.FreezerLaunchActivityTest' \
  --tests 'com.valhalla.thor.data.launcher.FreezerShortcutManagerTest' \
  --rerun-tasks
```

Expected: FAIL against the existing runner/`Deferred` behavior.

- [ ] **Step 3: Replace waiting with durable acknowledgement**

The tile launches the request, updates to a queued/running visual, and lets WorkManager/Room update counts. The launch activity keeps `REPORT_WINDOW_MS=2_000L` only as an enqueue/report bound: after `Accepted`, show “Sweep queued” and finish; it never stays alive for the full sweep. Notification and retained Room status own completion.

- [ ] **Step 4: Remove the old engine and prove no production references remain**

Delete `BulkFreezeRunner`, `BulkFreezeController`, obsolete parked-result/coalescing files, and their runner-specific tests. Run:

```bash
rg -n 'BulkFreezeRunner|BulkFreezeController|Deferred<BulkOutcome>|MAX_CONCURRENT|CANCEL_GRACE_MS|RESULT_TTL_MS|SWEEP_GRACE_MS' app/src/main app/src/test
```

Expected: no matches. `BulkRequest`, `BulkOp`, scope resolution, and pure candidate logic may remain only where the resolver uses them.

- [ ] **Step 5: Run all migration-focused ViewModel/surface tests**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.presentation.main.MainViewModelTest' \
  --tests 'com.valhalla.thor.presentation.appList.AppListViewModelTest' \
  --tests 'com.valhalla.thor.presentation.freezer.FreezerViewModelTest' \
  --tests 'com.valhalla.thor.presentation.settings.SettingsViewModelTest' \
  --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Commit Tasks 13–16 atomically**

Stage the exact resolver/model, Main, App List, Freezer, Settings, tile, launcher, shortcut, fake, test, and deleted runner/controller paths. Verify `git diff --cached --name-only` contains no `.kotlin/`, `docs/audit/`, or `docs/enforcement/`, then:

```bash
git commit -m "feat(sweep): migrate bulk freeze actions to WorkManager"
```

### Task 17: Model progress UI as explicit durable states

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/widgets/FreezeLoggerDialog.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppListScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezeProfilesSheet.kt`
- Create: `app/src/androidTest/java/com/valhalla/thor/presentation/widgets/FreezeLoggerDialogTest.kt`
- Modify: `app/src/test/java/com/valhalla/thor/presentation/main/MainViewModelTest.kt`
- Modify: `app/src/test/java/com/valhalla/thor/presentation/appList/AppListViewModelTest.kt`
- Modify: `app/src/test/java/com/valhalla/thor/presentation/freezer/FreezerViewModelTest.kt`

**Interfaces:**
- Consumes: `PrivilegeSweepStatus` and localized text resources.
- Produces: explicit launch-failure, queued, running, success, partial/busy, cancelled/unresolved, observer-failure, reconnected, and degraded-lane presentations.

- [ ] **Step 1: Add failing Compose semantics tests**

Create states with fixed counts and assert visible labels/actions for:

```text
launch failure
queued
running 3 of 10
success 10 of 10
partial: 6 succeeded, 2 failed, 1 busy, 1 unresolved
cancelled with partial counts
observer failure
reconnected running request
Root archive/sweep lane degraded
```

Assert Back/outside dismissal is blocked only while a request is truly queued/running, cancellation offers “Cancel sweep queue,” successful completion may auto-dismiss, and partial/cancelled/failure states remain until acknowledged.

- [ ] **Step 2: Run the Compose test and verify old boolean UI is insufficient**

```bash
./gradlew :app:connectedFossDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.presentation.widgets.FreezeLoggerDialogTest
```

Expected: FAIL because `isComplete` cannot represent the required phases.

- [ ] **Step 3: Replace boolean progress with a presentation state**

```kotlin
data class SweepProgressUiState(
    val phase: PrivilegeSweepPhase,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val busy: Int,
    val unresolved: Int,
    val rootLaneDegraded: Boolean,
    val message: UiText? = null,
)
```

`FreezeLoggerDialog` accepts this state instead of `processed/failed/isComplete`. Use state and icons—not color alone—to distinguish outcomes. A missing/failed observer becomes a visible terminal error rather than continuing animation. Reconnection maps the existing retained request into queued/running UI without calling `launch`.

- [ ] **Step 4: Run Compose and ViewModel tests, then commit**

Expected: PASS.

```bash
git add \
  app/src/main/java/com/valhalla/thor/presentation/widgets/FreezeLoggerDialog.kt \
  app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt \
  app/src/main/java/com/valhalla/thor/presentation/main/MainScreen.kt \
  app/src/main/java/com/valhalla/thor/presentation/appList/AppListScreen.kt \
  app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt \
  app/src/main/java/com/valhalla/thor/presentation/freezer/FreezeProfilesSheet.kt \
  app/src/androidTest/java/com/valhalla/thor/presentation/widgets/FreezeLoggerDialogTest.kt \
  app/src/test/java/com/valhalla/thor/presentation/main/MainViewModelTest.kt \
  app/src/test/java/com/valhalla/thor/presentation/appList/AppListViewModelTest.kt \
  app/src/test/java/com/valhalla/thor/presentation/freezer/FreezerViewModelTest.kt
git diff --cached --name-only
git commit -m "feat(sweep): render durable progress states"
```

Before committing, verify the staged list contains only the named files.

### Task 18: Admit per-app cache clear and prove Fix Store replay safety

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/gateway/ReinstallPostconditionVerifier.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/repository/InstallerSourceReader.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/AppInfoMapper.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/receivers/AutoReinstallReceiver.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/gateway/ShizukuSystemGateway.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/gateway/DhizukuSystemGateway.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepItemExecutor.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/gateway/ReinstallPostconditionTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepItemExecutorTest.kt`

**Interfaces:**
- Consumes: `CLEAR_CACHE` and `REINSTALL` enum values already defined, existing reinstall commands, `PackageManager` install-source APIs.
- Produces: replay-safe per-app cache sweep and Fix Store that succeeds only after verifying its convergent final state.

- [ ] **Step 1: Add failing replay/postcondition tests**

Pin:

```kotlin
@Test fun `reinstall success requires package installed for requested user`()
@Test fun `reinstall success requires Google Play install source`()
@Test fun `exit zero with failed postcondition is failure`()
@Test fun `repeating a verified reinstall remains success`()
@Test fun `Thor package is rejected before command execution`()
@Test fun `cache clear replay is accepted and reports final attempt`()
```

Use `PackageManager.getInstallSourceInfo` on API 30+ and `getInstallerPackageName` on API 28–29. Accept Google Play only when installing/initiating package resolves to `com.android.vending` and the package carries `FLAG_INSTALLED` for the target user.

- [ ] **Step 2: Run focused tests and observe missing postcondition**

```bash
./gradlew :app:testFossDebugUnitTest \
  --tests 'com.valhalla.thor.data.gateway.ReinstallPostconditionTest' \
  --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepItemExecutorTest' \
  --rerun-tasks
```

Expected: FAIL because existing gateways trust command completion without the final-state proof.

- [ ] **Step 3: Implement and apply the postcondition in all privilege modes**

Extract the duplicated API-level installer-source lookup from `AppInfoMapper.getInstallerPackageName` and `AutoReinstallReceiver.getInstallerOfRecord` into:

```kotlin
internal fun PackageManager.installerPackageNameOf(packageName: String): String?
```

`ReinstallPostconditionVerifier` injects `PackageManager` and exposes:

```kotlin
internal data class ReinstallFinalState(
    val installedForThorUser: Boolean,
    val installerPackageName: String?,
)

internal fun interface ReinstallStateReader {
    suspend fun read(packageName: String, userId: Int): ReinstallFinalState
}

internal class ReinstallPostconditionVerifier(
    private val stateReader: ReinstallStateReader,
) {
    suspend fun verify(packageName: String, userId: Int): Result<Unit>
}
```

The Android `ReinstallStateReader` rejects any `userId != thorUserId`, reads `ApplicationInfo` with `MATCH_UNINSTALLED_PACKAGES`, requires `FLAG_INSTALLED`, and uses `installerPackageNameOf`. Thor's commands already target `thorUserId`, the user in which Thor's `PackageManager` runs. Root/Shizuku/Dhizuku return success only after `verify` sees `installedForThorUser=true` and installer `com.android.vending`. Keep the command itself idempotent (`pm install -r`/current PackageInstaller equivalent), and return `Result.failure(ReinstallPostconditionFailed(packageName))` when state cannot be proven. Do not retry an unknown transport outcome inside the gateway.

- [ ] **Step 4: Route eligible multi-actions to sweeps**

Use `resolveSelection(CLEAR_CACHE|REINSTALL, ...)` in Main/App List multi-action surfaces. Single-app actions remain immediate and package-coordinated. Whole-device cache trim stays direct. No force-stop or uninstall enum/call site is added.

- [ ] **Step 5: Re-run tests and perform a physical replay check**

On Root and Shizuku devices, run Fix Store twice for the same package and verify both runs converge to installed + Play source without duplicate destructive effects. Verify cache clear while archive runs for a different package uses the sweep lane and completes independently.

- [ ] **Step 6: Commit**

Stage the exact gateway, evaluator, sweep executor, action-site, and test files, then:

```bash
git commit -m "feat(sweep): add replay-safe cache and reinstall actions"
```

### Task 19: Localize the new lifecycle and update worker documentation

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ar/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`
- Modify: `app/src/main/res/values-pl/strings.xml`
- Modify: `app/src/main/res/values-pt/strings.xml`
- Modify: `app/src/main/res/values-pt-rBR/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `docs/workers/README.md`
- Modify: `app/src/test/java/com/valhalla/thor/util/LocalePolicyTest.kt`

**Interfaces:**
- Consumes: final UI states, notification action, lane fallback behavior, actual producers/exclusions.
- Produces: complete eight-locale copy and worker documentation that matches code.

- [ ] **Step 1: Define the complete English key set and add coupling tests**

Add keys for:

```text
sweep queued
sweep running count
sweep success count
sweep partial counts (succeeded, failed, busy, unresolved)
sweep cancelled counts
sweep observer failure
sweep launch failure
notifications required and how to enable them
cancel sweep queue
archive lane degraded
sweep lane degraded
backup/restore busy for this app
sweep item busy for this app
sweep notification title
```

Use positional placeholders consistently and add tests that all eight locale files contain each key with the same placeholder signature.

- [ ] **Step 2: Add semantically equivalent translations in all eight locales**

Do not mark the keys translatable=false and do not add file-level lint suppression. Preserve “single Android user” semantics in restore-all copy and “queue” semantics in cancellation copy.

- [ ] **Step 3: Rewrite `docs/workers/README.md` from implemented behavior**

Document:

```text
THOR_JOB_CHAIN: archive backup, archive restore, app export
THOR_SWEEP_CHAIN: freeze, unfreeze, per-app cache clear, verified Fix Store
Root INTERACTIVE: Odin MainShell
Root ARCHIVE: owned dedicated shell
Root SWEEP: owned dedicated shell
Degraded lane: coordinated MainShell, visible status, prompt interactive busy failure
Queue cancellation: cancels the entire sweep chain
Excluded: force-stop, uninstall, whole-device trim, share, explicit suspend/unsuspend, multi-app export
```

Remove the stale warning that all jobs necessarily block each other on MainShell. State that same-package coordination still intentionally blocks conflicting operations.

- [ ] **Step 4: Run locale/resource tests and lint variants**

```bash
./gradlew :app:testFossDebugUnitTest --rerun-tasks
./gradlew :app:lintFossDebug :app:lintStoreRelease
```

Expected: all resource-coupling tests pass and both lint variants are clean. Run variants separately if the combined Kotlin backend exceeds memory.

- [ ] **Step 5: Commit localization and docs**

```bash
git add \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-ar/strings.xml \
  app/src/main/res/values-es/strings.xml \
  app/src/main/res/values-fr/strings.xml \
  app/src/main/res/values-pl/strings.xml \
  app/src/main/res/values-pt/strings.xml \
  app/src/main/res/values-pt-rBR/strings.xml \
  app/src/main/res/values-zh-rCN/strings.xml \
  docs/workers/README.md \
  app/src/test/java/com/valhalla/thor/util/LocalePolicyTest.kt
git diff --cached --name-only
git commit -m "docs(workers): document shell lanes and sweep states"
```

Before committing, verify the staged list contains only the named files.

### Task 20: Complete automated and physical acceptance gates

**Files:**
- Modify only when a failing gate identifies a defect; use the task-specific files above.
- Append verified device evidence to `docs/superpowers/plans/2026-08-28-worker-shell-lanes.md`.

**Interfaces:**
- Consumes: the complete implementation.
- Produces: test/build/lint evidence and physical proof for concurrency, cancellation, replay, degraded behavior, and process recreation.

- [ ] **Step 1: Run the complete unit suite with forced execution**

```bash
./gradlew :app:testFossDebugUnitTest --rerun-tasks
```

Expected: PASS.

- [ ] **Step 2: Parse actual XML totals**

Run through context-mode after Gradle:

```bash
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
files = list(Path('app/build/test-results').glob('**/*.xml'))
assert files, 'no test XML files'
tests = failures = errors = skipped = 0
for path in files:
    root = ET.parse(path).getroot()
    tests += int(root.attrib.get('tests', 0))
    failures += int(root.attrib.get('failures', 0))
    errors += int(root.attrib.get('errors', 0))
    skipped += int(root.attrib.get('skipped', 0))
print({'files': len(files), 'tests': tests, 'failures': failures, 'errors': errors, 'skipped': skipped})
assert failures == 0 and errors == 0
PY
```

Record these counts in the execution-results appendix.

- [ ] **Step 3: Run migration and WorkManager instrumentation suites**

```bash
./gradlew :app:connectedFossDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.data.source.local.room.SweepMigrationTest,com.valhalla.thor.data.freezer.PrivilegeSweepWorkerIntegrationTest,com.valhalla.thor.presentation.widgets.FreezeLoggerDialogTest
```

Expected: PASS.

- [ ] **Step 4: Run compile/build/lint gates**

```bash
./gradlew :app:compileFossDebugKotlin
./gradlew :app:lintFossDebug
./gradlew :app:lintStoreRelease
./gradlew assembleFossDebug
```

Expected: all pass under JDK 21. The Koin compiler must report no missing/ambiguous binding.

- [ ] **Step 5: Run anti-pattern validation on changed Kotlin/Gradle files**

Pass the final changed Kotlin and Gradle file contents to `mcp__android-agent-brain__check_anti_patterns`. Resolve every `FORBIDDEN` hit and evaluate each heuristic hit against source context; do not suppress the ledger.

- [ ] **Step 6: Execute the full Root physical matrix**

Verify on a rooted device:

```text
archive backup + different-package unfreeze: unfreeze completes promptly
archive restore + different-package freeze: freeze completes promptly
active sweep + interactive different-package action: both progress on independent lanes
same-package archive + interactive mutation: prompt busy result naming backup/restore
same-package archive + sweep item: bounded wait, then busy count and continuation
cancel archive during tar/extract: child dies and partial staging is removed
cancel sweep during package action: generation invalidated, truthful partial/unresolved counts
next archive/sweep after cancellation: fresh generation works
Root manager refusing extra su session: visible degraded status and prompt interactive busy rejection
```

- [ ] **Step 7: Execute lifecycle and replay matrix**

Verify:

```text
queue two sweeps: deterministic serial order on THOR_SWEEP_CHAIN
cancel active sweep from notification: whole queue is terminal-cancelled
cancel queued sweep before doWork: snapshot is retained with all targets unresolved
kill process during sweep, reopen app: existing request reconnects without duplicate launch
interrupt/restart freeze, unfreeze, cache clear, Fix Store: final state converges safely
notifications disabled/app permission denied/channel disabled: no snapshot and no hidden work
success, partial, busy, cancellation, observer failure: dialog terminates and reports correct counts
```

- [ ] **Step 8: Review privacy and architecture invariants**

Run:

```bash
rg -n 'Logger\..*(command|stdout|stderr|passphrase|absolutePath)|shellRepository\.(exec|submit|enqueue)|Result\.retry\(\)|BulkFreezeRunner|BulkFreezeController' app/src/main/java
```

Expected: no raw-command/output/path logging, no RootGateway bypass, no sweep retry, and no process-local bulk executor. Manually inspect legitimate occurrences matched by broad words.

- [ ] **Step 9: Review the full diff and commit final corrections/evidence**

```bash
git status --short
git diff --check
git diff --stat dev...HEAD
git log --oneline dev..HEAD
```

Confirm `.kotlin/` remains untracked, `versionCode` is unchanged, the spec/plan/page are present, schema 8 is committed, all eight locales changed, and only intended files are staged. Commit any final correction with an accurate conventional message and explicit paths.

- [ ] **Step 10: Prepare the PR without merging it automatically**

Push `feat/worker-shell-lanes` to GitHub, open a PR against `dev`, and include:

```text
Root cause: one cached MainShell FIFO
Three-lane topology and degraded fallback
Durable sweep scope and exclusions
Room migration and queue-wide cancellation semantics
Automated test counts from XML
Physical device/root-manager evidence
Privacy/logging validation
```

Review CodeRabbit and human findings as untrusted claims: reproduce each against the current source before changing code or replying. Use first-person maintainer voice in any GitHub comment. Merge only after the user explicitly requests it and all required checks are green.

## Execution results — 2026-09-03

### Automated verification

- Post-Task-83 final HEAD: `9671462cec7e6794c65f5494c28ac6cd4d92dca0`.
- `origin/dev` was fetched immediately before final review and remains `a4b1fcef6db70902657c60cdb6abb297587acec8`, an ancestor of this branch.
- Full forced JVM suites, counted from JUnit XML:
  - Foss debug: **2,043 tests**, 0 failures, 0 errors, 0 skipped.
  - Store debug: **2,043 tests**, 0 failures, 0 errors, 0 skipped.
  - Aggregate: **4,086 tests**, 0 failures, 0 errors, 0 skipped.
- Emulator-only instrumentation on `emulator-5554` / `Thor_Root_API37`: **16 tests**, 0 failures, 0 errors, 0 skipped (Room 7→8 migration, WorkManager sweep integration, queue cancellation receiver, and durable progress dialog semantics).
- `compileFossDebugKotlin`: passed with no missing or ambiguous Koin binding.
- `lintFossDebug`: passed — 0 errors, 11 warnings, 9 hints.
- `lintStoreRelease`: passed — 0 errors, 11 warnings, 9 hints.
- `assembleFossDebug`: passed.
- `assembleStoreRelease`: passed using the repository's local signing configuration.
- `git diff --check`: clean.
- `versionCode=1952` remains unchanged.
- Validation used Corretto/OpenJDK 21.0.12.1 because Zulu 21 is not installed.

### Emulator behavior verified

On the API 37 / Android 17 ARM64 emulator with 16 KiB pages, the actual app passed the non-Root acceptance surface:

- leaving immediately after accepting backup still enqueued exactly one WorkSpec per action;
- three controlled WorkSpecs reached `SUCCEEDED` with `run_attempt_count = 1`;
- a live backup notification reopened the correct running backup sheet;
- one `HomeActivity` existed before and after the notification tap.

### Root-only acceptance blocked

The emulator has the Magisk Alpha manager package `io.github.vvb2060.magisk` installed and running, but it has no `magisk` binary or `magiskd` daemon. Its only `su` is `/system/xbin/su`, the AOSP userdebug UID-switching utility; it rejects `-c`, `-v`, and `-V`. Thor/Odin therefore cannot create app-level Root shells.

A fresh official upstream check on 2026-09-03 found no Magisk v31 tag, release, or APK; the newest official published release remains v30.7. No unofficial artifact, unpublished source build, shared SDK image modification, or broad process termination was used.

The following are explicitly **blocked, not passed**:

- ARCHIVE plus different-package INTERACTIVE overlap;
- SWEEP plus different-package INTERACTIVE overlap;
- same-package runtime busy behavior;
- exact child PID capture and `/proc/<pid>` disappearance;
- `Shell.close()` child behavior and any conditional PID-scoped fallback;
- partial Root staging cleanup and fresh shell generation;
- degraded Root fallback runtime;
- private CE/DE archive round trip;
- Root Fix Store replay.

No API 28/29 image is installed (installed images are API 30, 36.1, 37.0, 37.1, and CinnamonBun), so the legacy hidden `PackageParser` branch remains runtime-unverified on Android 9/10.

### Privacy and architecture inspection

Manual dataflow review found 27 existing production logging violations across 14 files. `git blame` established that all 27 predate local `dev`; this branch introduced none. The baseline debt is disclosed rather than expanded into an unrelated cleanup. Branch-specific checks confirmed:

- no new raw command, command output, passphrase, URI, user path, or attacker-controlled archive-name logging;
- `MainShellCommandExecutor` is the intentional single Odin `ShellRepository.exec` boundary;
- no executable sweep returns `Result.retry()`;
- `BulkFreezeRunner` and `BulkFreezeController` are absent;
- no `.kotlin/`, `docs/audit/`, or `docs/enforcement/` path is tracked by this branch.

### Independent review

- Task 83 authenticated archive review: **`SPEC COMPLIANCE: PASS`; `CODE QUALITY/SECURITY: PASS`**.
- Final whole-branch correctness/security review: pending at this appendix commit; the independent review runs against this committed record, and its verdict is recorded before PR creation.
