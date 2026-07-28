# FreezerTileService Rework Implementation Plan

> **Status: executed.** This plan was implemented on `fix/freezer-tile-rework` (PR #284) and then
> changed in three review rounds. It is kept as the record of what was planned, not as a description
> of what shipped — the API sketches and code recipes below are pre-review and several are now
> wrong. The differences are listed in **[As-built deltas](#as-built-deltas)**; the spec's copy of
> the same table carries the reasoning. For current behaviour, read the source.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## As-built deltas

Every place the shipped code differs from the recipes below. Each one is a defect a review round
found in the plan, not a drifting implementation.

- **`TileVisual` is an `enum class`,** not a sealed type (§ file inventory, and `presentation/tile`
  in the Architecture note above). No variant carries data.
- **`BulkResult` carries `op: BulkOp`.** Without it an UNFREEZE run reports with freeze wording.
- **`BulkFreezeRunner.launch(op)` returns `Deferred<BulkResult?>`,** not `Job` — the trampoline
  needs the outcome, and watching `runningOp` races a run that finishes before the observer
  subscribes. `FreezerShortcutManager.runBulk` returns that same `Deferred` instead of `Unit`.
- **`isRunning: StateFlow<Boolean>` → `runningOp: StateFlow<BulkOp?>`,** and `freezableCount` is
  freeze-specific rather than "whatever op swept last". `refreshCandidates(op)` →
  `refreshFreezableCount()`. `consumeResult()` → `consumeResult(shown)` (compare-and-set).
- **`launch` tracks `activeOp` alongside `activeJob`.** Coalescing on *any* active job made an
  UNFREEZE issued during a FREEZE a silent no-op; a conflicting op now cancels-and-joins the
  previous run before touching a package.
- **The semaphore is instance-scoped, not per run,** and cancellation goes through a bounded
  grace/join handoff (`CANCEL_GRACE_MS`). A fresh `Semaphore(5)` per generation let five new
  workers start on top of workers the replaced batch had abandoned in blocking binder calls. The
  overlap is bounded, not eliminated — see the runner's KDoc.
- **`run()` awaits `privilegeManager.state.first { it.isReady }`** instead of reading `state.value`,
  which starts at `NONE`/`isReady = false` and turned a cold-start run into a silent no-op.
- **Result publishing is op-aware:** `run()` returns null for no-ops (no `BulkResult(0,0,0)`),
  `_lastResult` takes FREEZE results only and is cleared by UNFREEZE, and the notifier posts both.
- **The post-run sweep in the `finally` is bounded** by racing a cancellable `join()`; under
  `NonCancellable` a wedged `PackageManager` otherwise pinned `runningOp` for the process lifetime.
- **`runBulk` schedules at most one icon refresh per distinct run,** since same-op taps coalesce.
- **The Settings notification row is unconditional,** not API 33+ only: 33+ requests the runtime
  permission, 28–32 deep-link to app notification settings. `areNotificationsEnabled()` — the exact
  thing the notifier checks — is meaningful down to minSdk 28. The device-verification list must
  therefore cover the row below API 33 too. Only `SettingsScreen.kt` changed; no ViewModel.
- **`bulkResultMessage` lives in `util/BulkResultText.kt`,** not `presentation/tile/`.

**Goal:** Make Thor's Quick Settings freezer tile show truthful state and actually report its results, by moving the bulk freeze into a shared app-scoped runner.

**Architecture:** All decision logic moves into pure functions in `domain/model` and `presentation/tile` that take primitives and return sealed types — those carry the unit tests. Three Koin `@Single`s hold every framework interaction: `AppFreezeStateReader` (PackageManager), `BulkFreezeRunner` (scope, semaphore, deadline, counters, StateFlows), `BulkResultNotifier` (channel, gated post). `FreezerTileService` becomes a thin observer that starts work and paints; it holds no coroutine that outlives it.

**Tech Stack:** Kotlin, Koin 4.2.1 annotation DI (`@Single`/`@Factory`, `@ComponentScan("com.valhalla.thor")`), kotlinx.coroutines, JUnit 4, Jetpack Compose (Settings screen only), AndroidX Core `NotificationCompat`.

**Spec:** `docs/superpowers/specs/2026-07-28-freezer-tile-rework-design.md`

## Global Constraints

- Branch is `fix/freezer-tile-rework`, already created off `dev`. PR targets `dev`. Never commit to `dev` directly.
- **Never add a `Co-Authored-By` trailer to any commit.**
- `docs/audit/` is untracked and NOT gitignored. Never run `git add -A`. Stage files explicitly by path.
- Never push to the `codeberg` remote.
- Do NOT touch `versionCode` in `gradle.properties`. That belongs to a separate `chore(release)` commit.
- Every new `.kt` file starts with the two-line SPDX header used by every other first-party file:
  ```kotlin
  // SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
  // SPDX-License-Identifier: GPL-3.0-or-later
  ```
- Gradle commands must be run through `mcp__plugin_context-mode_context-mode__ctx_execute` with `language: "shell"`, never through Bash.
- File creation and modification must use the native Write/Edit tools, never `ctx_execute` or Bash heredocs.
- Unit tests must be run with `--rerun-tasks`. Gradle reports `UP-TO-DATE` and silently skips them otherwise.
- minSdk 28, targetSdk/compileSdk 37. Guard `Tile.setSubtitle` behind `SDK_INT >= Q` (29) and `setStateDescription` behind `SDK_INT >= R` (30).
- Injected dispatchers only **in Koin-constructed classes** — `@Named("io")` / `@Named("default")` / `@Named("main")` `CoroutineDispatcher`. This binds `BulkFreezeRunner` (Task 5). It does **not** bind `FreezerTileService` (Task 7): a `TileService` is constructed by the framework, is not unit-tested, and must paint on the main thread, so the literal `Dispatchers.Main` is correct there and matches the file it replaces.
- New pure functions in `domain/model` must not import any `android.*` type.
- Do NOT self-grant `POST_NOTIFICATIONS`. Do NOT add a foreground service, WorkManager, or a ContentProvider.
- The tile is freeze-only. Never make it `TOGGLEABLE_TILE` and never add `ACTIVE_TILE` meta-data.
- Do NOT prune uninstalled packages from the freezer watchlist. Out of scope (spec §7).
- **Leave these two Toasts alone.** Task 7 deletes the tile's Toasts because they never worked from
  a `TileService`. `FreezerLaunchActivity.kt:133-134` uses the same API and *does* work — a resumed
  Activity is not subject to `checkCanEnqueueToast`. `FreezerShortcutPinnedReceiver.kt:25` hits the
  same suppression class as the tile's but is a different trigger needing a different fix; the spec
  puts it out of scope.

### Two deliberate deviations from the spec

Both are simplifications, recorded here so a reviewer does not read them as drift:

1. **`TileVisual` is an `enum class`, not a `sealed class`.** The spec (§5, §6) calls it a sealed
   type. None of the five states carries a payload, so an enum gives the same exhaustive `when` with
   less ceremony. If a state later needs data, promote it then.
2. **The Settings permission row does not touch `SettingsViewModel`.** The spec's §6 file list says
   "`SettingsScreen.kt` + its ViewModel". The row is pure permission state read from the framework,
   and the adjacent Usage Access row already keeps that in the composable via `remember` +
   `DisposableEffect`. Routing it through the ViewModel would add indirection and break the
   symmetry. Task 9 mirrors the existing row instead.

---

## File Structure

| Path | Responsibility | Task |
|---|---|---|
| `domain/model/BulkFreeze.kt` | `BulkOp`, `BulkResult` — pure data | 1 |
| `domain/model/FreezeState.kt` | `FreezeState` enum + `freezableCandidates` — pure filter | 1 |
| `presentation/tile/TileVisual.kt` | `TileVisual` sealed type + `tileVisualFor` — pure state machine | 2 |
| `presentation/tile/BulkResultText.kt` | `bulkResultMessage` — pure `BulkResult` → `UiText` | 3 |
| `data/freezer/AppFreezeStateReader.kt` | `@Single`, PackageManager → `FreezeState` | 4 |
| `data/freezer/BulkFreezeRunner.kt` | `@Single`, owns scope/semaphore/deadline/counters/StateFlows | 5 |
| `data/freezer/BulkResultNotifier.kt` | `@Single`, channel creation + permission-gated post | 6 |
| `presentation/tile/FreezerTileService.kt` | rewritten — observe + paint only | 7 |
| `data/launcher/FreezerShortcutManager.kt` | `runBulk` delegates; `isFrozen` deleted | 8 |
| `presentation/settings/SettingsScreen.kt` | notification permission row | 9 |
| `app/src/main/AndroidManifest.xml` | `<uses-permission POST_NOTIFICATIONS>` | 6 |
| `res/values/strings.xml` + `values-{ar,es,fr,zh-rCN}` | 4 result strings (Task 3) + 3 settings-row strings (Task 9) | 3, 9 |
| `docs/follow-ups/freezer-tile-service-rework.md` | deleted — superseded by the spec | 10 |

---

### Task 1: Pure bulk-freeze models and the freezable filter

This is the fix for the reported bug: the tile currently counts watchlist rows, which never change when apps are frozen. This task builds the filter that counts *actually freezable* apps, plus the result type whose third bucket stops the timeout path from reporting every package as failed.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/BulkFreeze.kt`
- Create: `app/src/main/java/com/valhalla/thor/domain/model/FreezeState.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/BulkFreezeTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/FreezeStateTest.kt`

**Interfaces:**
- Consumes: `isActive(enabled: Boolean, isSuspended: Boolean): Boolean` and `isFrozen(enabled: Boolean, isSuspended: Boolean): Boolean` from `domain/model/FreezerMode.kt` (already exist, same package — no import needed).
- Produces:
  - `enum class BulkOp { FREEZE, UNFREEZE }`
  - `data class BulkResult(val total: Int, val succeeded: Int, val failed: Int)` with `val unresolved: Int`
  - `enum class FreezeState { FROZEN, ACTIVE, ABSENT }`
  - `fun freezableCandidates(watchlist: List<String>, op: BulkOp, stateOf: (String) -> FreezeState): List<String>`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/valhalla/thor/domain/model/BulkFreezeTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure bulk-freeze result arithmetic. No Android deps. */
class BulkFreezeTest {

    @Test
    fun `all succeeded leaves nothing unresolved`() {
        assertEquals(0, BulkResult(total = 5, succeeded = 5, failed = 0).unresolved)
    }

    @Test
    fun `failures are not counted as unresolved`() {
        assertEquals(0, BulkResult(total = 5, succeeded = 3, failed = 2).unresolved)
    }

    @Test
    fun `packages never reached are unresolved, not failed`() {
        // The deadline fired after 3 of 5 resolved. The old code reported 5 failures here.
        val result = BulkResult(total = 5, succeeded = 3, failed = 0)
        assertEquals(2, result.unresolved)
        assertEquals(0, result.failed)
    }

    @Test
    fun `an empty run is fully resolved`() {
        assertEquals(0, BulkResult(total = 0, succeeded = 0, failed = 0).unresolved)
    }
}
```

Create `app/src/test/java/com/valhalla/thor/domain/model/FreezeStateTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure candidate filtering for bulk freeze/unfreeze. No Android deps. */
class FreezeStateTest {

    private val states = mapOf(
        "com.active.one" to FreezeState.ACTIVE,
        "com.active.two" to FreezeState.ACTIVE,
        "com.frozen.one" to FreezeState.FROZEN,
        "com.gone" to FreezeState.ABSENT,
    )
    private val watchlist = states.keys.toList()
    private val stateOf: (String) -> FreezeState = { states[it] ?: FreezeState.ABSENT }

    @Test
    fun `freeze targets only active apps`() {
        assertEquals(
            listOf("com.active.one", "com.active.two"),
            freezableCandidates(watchlist, BulkOp.FREEZE, stateOf)
        )
    }

    @Test
    fun `unfreeze targets only frozen apps`() {
        assertEquals(
            listOf("com.frozen.one"),
            freezableCandidates(watchlist, BulkOp.UNFREEZE, stateOf)
        )
    }

    @Test
    fun `uninstalled packages are never candidates`() {
        val all = freezableCandidates(watchlist, BulkOp.FREEZE, stateOf) +
                freezableCandidates(watchlist, BulkOp.UNFREEZE, stateOf)
        assertEquals(emptyList<String>(), all.filter { it == "com.gone" })
    }

    @Test
    fun `a fully frozen watchlist yields no freeze candidates`() {
        // This is the reported bug: the tile must go INACTIVE here, and it can only do that
        // if the candidate list is empty rather than the watchlist size.
        val allFrozen = listOf("a", "b", "c")
        assertEquals(
            emptyList<String>(),
            freezableCandidates(allFrozen, BulkOp.FREEZE) { FreezeState.FROZEN }
        )
    }

    @Test
    fun `an empty watchlist yields no candidates`() {
        assertEquals(
            emptyList<String>(),
            freezableCandidates(emptyList(), BulkOp.FREEZE, stateOf)
        )
    }

    @Test
    fun `candidate order follows the watchlist`() {
        val reversed = listOf("com.active.two", "com.active.one")
        assertEquals(
            listOf("com.active.two", "com.active.one"),
            freezableCandidates(reversed, BulkOp.FREEZE, stateOf)
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run through `ctx_execute` with `language: "shell"`:
```
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.BulkFreezeTest" --tests "com.valhalla.thor.domain.model.FreezeStateTest"
```
Expected: compilation failure — `Unresolved reference: BulkResult`, `Unresolved reference: FreezeState`, `Unresolved reference: freezableCandidates`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/valhalla/thor/domain/model/BulkFreeze.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Which direction a bulk run moves apps. The QS tile only ever issues [FREEZE]. */
enum class BulkOp { FREEZE, UNFREEZE }

/**
 * Outcome of a bulk run.
 *
 * [unresolved] is the third bucket that makes a deadline honest: those packages were either
 * never started or were still running when the deadline fired. Reporting them as failures
 * (as the pre-rework tile did) claims knowledge we do not have.
 */
data class BulkResult(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
) {
    val unresolved: Int get() = total - succeeded - failed
}
```

Create `app/src/main/java/com/valhalla/thor/domain/model/FreezeState.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** An app's freeze state as far as a bulk run cares. [ABSENT] = not installed. */
enum class FreezeState { FROZEN, ACTIVE, ABSENT }

/**
 * The packages a bulk [op] would actually act on, in watchlist order.
 *
 * This is the fix for the tile counting watchlist rows: the freezer watchlist is invariant
 * under freeze/unfreeze, so only the live per-app state can tell us whether there is
 * anything left to do. [FreezeState.ABSENT] packages are skipped but deliberately left in
 * the watchlist — pruning them is out of scope.
 */
fun freezableCandidates(
    watchlist: List<String>,
    op: BulkOp,
    stateOf: (String) -> FreezeState,
): List<String> {
    val wanted = when (op) {
        BulkOp.FREEZE -> FreezeState.ACTIVE
        BulkOp.UNFREEZE -> FreezeState.FROZEN
    }
    return watchlist.filter { stateOf(it) == wanted }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.BulkFreezeTest" --tests "com.valhalla.thor.domain.model.FreezeStateTest"
```
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/BulkFreeze.kt \
        app/src/main/java/com/valhalla/thor/domain/model/FreezeState.kt \
        app/src/test/java/com/valhalla/thor/domain/model/BulkFreezeTest.kt \
        app/src/test/java/com/valhalla/thor/domain/model/FreezeStateTest.kt
git commit -m "feat(freezer): add pure bulk-freeze models and candidate filter

freezableCandidates resolves what a bulk run would act on from live per-app
state instead of watchlist size, which is invariant under freezing.

BulkResult carries a third bucket, unresolved, so a deadline can report what
it knows instead of declaring every package failed."
```

---

### Task 2: Pure tile state machine

The tile's visual state is decided by one pure function so the unknown-window rule can be unit-tested. That rule is load-bearing: AOSP's `CustomTile.handleClick()` early-returns on `STATE_UNAVAILABLE`, so painting UNAVAILABLE before the first privilege probe completes would make the tile permanently unclickable until the next listen.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/tile/TileVisual.kt`
- Test: `app/src/test/java/com/valhalla/thor/presentation/tile/TileVisualTest.kt`

**Interfaces:**
- Consumes: `PrivilegeState` from `domain/model/PrivilegeState.kt` — `data class PrivilegeState(root, shizuku, dhizuku, active: PrivilegeMode, isReady: Boolean)` with `val hasAnyPrivilege: Boolean get() = active != PrivilegeMode.NONE`.
- Produces:
  - `enum class TileVisual { CHECKING, NO_PRIVILEGE, NOTHING_TO_FREEZE, READY, WORKING }`
  - `fun tileVisualFor(privilege: PrivilegeState, freezableCount: Int?, isRunning: Boolean): TileVisual`

The function must not import `android.service.quicksettings.Tile`. Mapping `TileVisual` → `Tile.STATE_*` happens in Task 7, at the service edge.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/valhalla/thor/presentation/tile/TileVisualTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure QS tile state machine. No Android deps. */
class TileVisualTest {

    private val unprobed = PrivilegeState(isReady = false)
    private val none = PrivilegeState(active = PrivilegeMode.NONE, isReady = true)
    private val rooted =
        PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)

    @Test
    fun `before the first probe the tile is CHECKING, never NO_PRIVILEGE`() {
        // Painting STATE_UNAVAILABLE here would make AOSP drop every onClick until the
        // next listen, because CustomTile.handleClick early-returns on UNAVAILABLE.
        assertEquals(
            TileVisual.CHECKING,
            tileVisualFor(unprobed, freezableCount = null, isRunning = false)
        )
    }

    @Test
    fun `an unprobed privilege state is CHECKING even with a known count`() {
        assertEquals(
            TileVisual.CHECKING,
            tileVisualFor(unprobed, freezableCount = 4, isRunning = false)
        )
    }

    @Test
    fun `no privilege is NO_PRIVILEGE once probed`() {
        assertEquals(
            TileVisual.NO_PRIVILEGE,
            tileVisualFor(none, freezableCount = 4, isRunning = false)
        )
    }

    @Test
    fun `privileged with no freezable apps is NOTHING_TO_FREEZE`() {
        assertEquals(
            TileVisual.NOTHING_TO_FREEZE,
            tileVisualFor(rooted, freezableCount = 0, isRunning = false)
        )
    }

    @Test
    fun `privileged with freezable apps is READY`() {
        assertEquals(
            TileVisual.READY,
            tileVisualFor(rooted, freezableCount = 3, isRunning = false)
        )
    }

    @Test
    fun `privileged with an unknown count is CHECKING`() {
        assertEquals(
            TileVisual.CHECKING,
            tileVisualFor(rooted, freezableCount = null, isRunning = false)
        )
    }

    @Test
    fun `a running batch is WORKING regardless of count`() {
        assertEquals(
            TileVisual.WORKING,
            tileVisualFor(rooted, freezableCount = 0, isRunning = true)
        )
        assertEquals(
            TileVisual.WORKING,
            tileVisualFor(rooted, freezableCount = 3, isRunning = true)
        )
    }

    @Test
    fun `losing privilege beats a running batch`() {
        assertEquals(
            TileVisual.NO_PRIVILEGE,
            tileVisualFor(none, freezableCount = 3, isRunning = true)
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.presentation.tile.TileVisualTest"
```
Expected: compilation failure — `Unresolved reference: TileVisual`, `Unresolved reference: tileVisualFor`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/valhalla/thor/presentation/tile/TileVisual.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import com.valhalla.thor.domain.model.PrivilegeState

/**
 * What the QS tile should show. Deliberately framework-free — [FreezerTileService] maps
 * these onto `Tile.STATE_*` so this stays a plain JVM unit under test.
 */
enum class TileVisual { CHECKING, NO_PRIVILEGE, NOTHING_TO_FREEZE, READY, WORKING }

/**
 * Resolve the tile's visual state.
 *
 * [freezableCount] is null until the first PackageManager sweep lands.
 *
 * The ordering matters. CHECKING must win over NO_PRIVILEGE while the privilege probe is
 * still in flight: AOSP's `CustomTile.handleClick()` early-returns on `STATE_UNAVAILABLE`,
 * so an optimistic NO_PRIVILEGE paint would silently swallow every tap until the next
 * listen. CHECKING maps to a clickable state, and `onClick` re-checks privilege itself.
 */
fun tileVisualFor(
    privilege: PrivilegeState,
    freezableCount: Int?,
    isRunning: Boolean,
): TileVisual = when {
    !privilege.isReady -> TileVisual.CHECKING
    !privilege.hasAnyPrivilege -> TileVisual.NO_PRIVILEGE
    isRunning -> TileVisual.WORKING
    freezableCount == null -> TileVisual.CHECKING
    freezableCount == 0 -> TileVisual.NOTHING_TO_FREEZE
    else -> TileVisual.READY
}
```

- [ ] **Step 4: Run the test to verify it passes**

```
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.presentation.tile.TileVisualTest"
```
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/tile/TileVisual.kt \
        app/src/test/java/com/valhalla/thor/presentation/tile/TileVisualTest.kt
git commit -m "feat(tile): add the pure QS tile state machine

tileVisualFor decides the tile's state from privilege plus a nullable
freezable count. CHECKING deliberately outranks NO_PRIVILEGE while the probe
is in flight: AOSP's CustomTile.handleClick early-returns on
STATE_UNAVAILABLE, so an optimistic paint would swallow every tap."
```

---

### Task 3: Result strings and the pure message mapper

`tile_freeze_success` and `tile_freeze_partial_failure` are shared with `SettingsViewModel`, `AppListViewModel` and `FreezeLoggerDialog`, so they are reused verbatim — no rewording. Only the genuinely new cases get new strings.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/tile/BulkResultText.kt`
- Modify: `app/src/main/res/values/strings.xml` (after line 218, `tile_freeze_partial_failure`)
- Modify: `app/src/main/res/values-ar/strings.xml`, `values-es/strings.xml`, `values-fr/strings.xml`, `values-zh-rCN/strings.xml`
- Test: `app/src/test/java/com/valhalla/thor/presentation/tile/BulkResultTextTest.kt`

**Interfaces:**
- Consumes: `BulkResult` (Task 1). `UiText` from `com.valhalla.thor.util.UiText` — a sealed class with `data class DynamicString(value)`, `class StringResource(@StringRes resId, vararg args)`, `class PluralsResource(@PluralsRes resId, quantity, vararg args)`. `StringResource` and `PluralsResource` both implement `equals`/`hashCode` over `resId` + `args` without touching `Context`, which is what makes them assertable in a JVM test.
- Produces: `fun bulkResultMessage(result: BulkResult): UiText`

- [ ] **Step 1: Add the four new strings**

In `app/src/main/res/values/strings.xml`, immediately after the `tile_freeze_partial_failure` line (currently line 218):

```xml
    <!-- Third result bucket: neither confirmed success nor confirmed failure — the deadline
         fired before these packages resolved. -->
    <string name="tile_freeze_incomplete" tools:ignore="PluralsCandidate">Froze %1$d/%2$d apps (%3$d unfinished)</string>
    <string name="tile_checking">Checking…</string>
    <string name="tile_freezing">Freezing…</string>
    <string name="channel_bulk_result_name">Bulk action results</string>
```

In `app/src/main/res/values-ar/strings.xml`, after `tile_freeze_partial_failure` (currently line 197):

```xml
    <string name="tile_freeze_incomplete">تم تجميد %1$d/%2$d تطبيقات (%3$d لم تكتمل)</string>
    <string name="tile_checking">جارٍ التحقق…</string>
    <string name="tile_freezing">جارٍ التجميد…</string>
    <string name="channel_bulk_result_name">نتائج الإجراءات المجمّعة</string>
```

In `app/src/main/res/values-es/strings.xml`, after `tile_freeze_partial_failure` (currently line 187):

```xml
    <string name="tile_freeze_incomplete">Se congelaron %1$d/%2$d aplicaciones (%3$d sin terminar)</string>
    <string name="tile_checking">Comprobando…</string>
    <string name="tile_freezing">Congelando…</string>
    <string name="channel_bulk_result_name">Resultados de acciones masivas</string>
```

In `app/src/main/res/values-fr/strings.xml`, after `tile_freeze_partial_failure` (currently line 187):

```xml
    <string name="tile_freeze_incomplete">%1$d/%2$d applications gelées (%3$d inachevées)</string>
    <string name="tile_checking">Vérification…</string>
    <string name="tile_freezing">Gel en cours…</string>
    <string name="channel_bulk_result_name">Résultats des actions groupées</string>
```

In `app/src/main/res/values-zh-rCN/strings.xml`, after `tile_freeze_partial_failure` (currently line 184):

```xml
    <string name="tile_freeze_incomplete">已冻结 %1$d/%2$d 个应用（%3$d 个未完成）</string>
    <string name="tile_checking">正在检查…</string>
    <string name="tile_freezing">正在冻结…</string>
    <string name="channel_bulk_result_name">批量操作结果</string>
```

`tools:ignore="PluralsCandidate"` is only needed in `values/strings.xml`; the translated files inherit the suppression and that file already declares the `tools` namespace (its sibling `tile_freeze_partial_failure` uses it).

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/com/valhalla/thor/presentation/tile/BulkResultTextTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.BulkResult
import com.valhalla.thor.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure BulkResult -> UiText mapping. UiText's equals never touches a Context. */
class BulkResultTextTest {

    @Test
    fun `a clean run uses the shared success plural`() {
        assertEquals(
            UiText.PluralsResource(R.plurals.tile_freeze_success, 5),
            bulkResultMessage(BulkResult(total = 5, succeeded = 5, failed = 0))
        )
    }

    @Test
    fun `confirmed failures use the partial-failure string`() {
        assertEquals(
            UiText.StringResource(R.string.tile_freeze_partial_failure, 3, 5, 2),
            bulkResultMessage(BulkResult(total = 5, succeeded = 3, failed = 2))
        )
    }

    @Test
    fun `unresolved packages report as unfinished, not failed`() {
        // The pre-rework code reported `pkgs.size` failures the moment the deadline fired.
        assertEquals(
            UiText.StringResource(R.string.tile_freeze_incomplete, 3, 5, 2),
            bulkResultMessage(BulkResult(total = 5, succeeded = 3, failed = 0))
        )
    }

    @Test
    fun `unresolved wins when a run both failed and timed out`() {
        // 5 total, 2 ok, 1 failed, 2 unresolved: "unfinished" is the honest headline.
        assertEquals(
            UiText.StringResource(R.string.tile_freeze_incomplete, 2, 5, 2),
            bulkResultMessage(BulkResult(total = 5, succeeded = 2, failed = 1))
        )
    }

    @Test
    fun `an empty run reports zero frozen`() {
        assertEquals(
            UiText.PluralsResource(R.plurals.tile_freeze_success, 0),
            bulkResultMessage(BulkResult(total = 0, succeeded = 0, failed = 0))
        )
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.presentation.tile.BulkResultTextTest"
```
Expected: compilation failure — `Unresolved reference: bulkResultMessage`.

- [ ] **Step 4: Write the implementation**

Create `app/src/main/java/com/valhalla/thor/presentation/tile/BulkResultText.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.BulkResult
import com.valhalla.thor.util.UiText

/**
 * Human-readable outcome of a bulk run, resolved late so the caller (tile subtitle or
 * notification) supplies the Context.
 *
 * Unresolved outranks failed in the headline: "didn't finish" is what we actually know when
 * the deadline fires, and claiming those packages failed would be a guess. The success and
 * partial-failure strings are shared with SettingsViewModel / AppListViewModel /
 * FreezeLoggerDialog and are reused verbatim.
 */
fun bulkResultMessage(result: BulkResult): UiText = when {
    result.unresolved > 0 -> UiText.StringResource(
        R.string.tile_freeze_incomplete,
        result.succeeded,
        result.total,
        result.unresolved,
    )

    result.failed > 0 -> UiText.StringResource(
        R.string.tile_freeze_partial_failure,
        result.succeeded,
        result.total,
        result.failed,
    )

    else -> UiText.PluralsResource(R.plurals.tile_freeze_success, result.succeeded)
}
```

- [ ] **Step 5: Run the test to verify it passes**

```
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.presentation.tile.BulkResultTextTest"
```
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/tile/BulkResultText.kt \
        app/src/test/java/com/valhalla/thor/presentation/tile/BulkResultTextTest.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-ar/strings.xml \
        app/src/main/res/values-es/strings.xml \
        app/src/main/res/values-fr/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(tile): map bulk results to UiText, add four strings

Reuses the shared success/partial-failure strings verbatim; only the new
cases get strings (incomplete, checking, freezing, channel name), translated
into ar/es/fr/zh-rCN.

Unresolved outranks failed in the headline because that is what we actually
know when the deadline fires."
```

---

### Task 4: `AppFreezeStateReader`

A thin `@Single` wrapper turning a package name into a `FreezeState`. It replaces `FreezerShortcutManager.isFrozen`, which re-implemented the domain predicate inline.

**This task has no unit test, deliberately.** The class is one `PackageManager` call feeding the already-tested `isFrozen` predicate. Testing it needs a fake `PackageManager`, and `app/build.gradle.kts` carries only `testImplementation(libs.junit)` — no mocking library and no Robolectric. The spec (§5) fixes "zero new dependencies" as a constraint, so adding one to cover a three-line adapter is the wrong trade. The decision logic it feeds is covered by `FreezeStateTest` (Task 1); the branch that is genuinely this class's own — `NameNotFoundException` → `ABSENT` — is covered by device verification (Task 10). Do not add a test dependency to close this gap.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/AppFreezeStateReader.kt`

**Interfaces:**
- Consumes: `FreezeState` (Task 1); `isFrozen(enabled, isSuspended)` from `domain/model/FreezerMode.kt`; `PackageManager`, already bound in `di/Modules.kt:43` as `@Single fun packageManager(context: Context): PackageManager`.
- Produces: `class AppFreezeStateReader { fun stateOf(packageName: String): FreezeState }`

- [ ] **Step 1: Write the implementation**

Create `app/src/main/java/com/valhalla/thor/data/freezer/AppFreezeStateReader.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.valhalla.thor.domain.model.FreezeState
import com.valhalla.thor.domain.model.isFrozen
import org.koin.core.annotation.Single

/**
 * Reads an app's live freeze state. The single place that answers "is this app frozen?",
 * replacing the inline copy that used to live in FreezerShortcutManager.
 *
 * MATCH_DISABLED_COMPONENTS so a disabled app is still readable; FLAG_SUSPENDED (API 24+)
 * catches the suspend-mode case.
 */
@Single
class AppFreezeStateReader(
    private val packageManager: PackageManager,
) {
    fun stateOf(packageName: String): FreezeState = try {
        val info = packageManager.getApplicationInfo(
            packageName,
            PackageManager.MATCH_DISABLED_COMPONENTS
        )
        val suspended = (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
        if (isFrozen(info.enabled, suspended)) FreezeState.FROZEN else FreezeState.ACTIVE
    } catch (e: PackageManager.NameNotFoundException) {
        FreezeState.ABSENT
    }
}
```

Note the narrowed catch: the old `isFrozen` swallowed every `Exception` and returned `false`, which would have reported an uninstalled app as freezable. Only `NameNotFoundException` means "absent"; anything else should surface.

- [ ] **Step 2: Verify it compiles and Koin resolves it**

```
./gradlew :app:compileFossDebugKotlin
```
Expected: BUILD SUCCESSFUL. `@Single` + the existing `@ComponentScan("com.valhalla.thor")` in `di/Modules.kt:24` picks it up with no module edit.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/freezer/AppFreezeStateReader.kt
git commit -m "feat(freezer): add AppFreezeStateReader

One place that answers 'is this app frozen?', over the existing isFrozen
predicate. Catches only NameNotFoundException — the inline version it
replaces swallowed every exception and reported absent apps as freezable."
```

---

### Task 5: `BulkFreezeRunner`

The core of the rework. Owns the scope so the tile does not, bounds the whole operation rather than just the batch, and counts incrementally so a deadline reports what it knows.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/BulkFreezeRunner.kt`

**Interfaces:**
- Consumes: `BulkOp`, `BulkResult`, `freezableCandidates` (Task 1); `AppFreezeStateReader.stateOf` (Task 4); `FreezerRepository.getAllPackageNames(): List<String>`; `ManageAppUseCase.setAppDisabled(pkg, disabled): Result<Unit>`, `.setAppSuspended(pkg, suspended): Result<Unit>`, `.forceUnfreeze(pkg): Result<Unit>`; `PreferenceRepository.userPreferences: Flow<UserPreferences>` with `.freezerMode: FreezerMode`; `PrivilegeManager.state: StateFlow<PrivilegeState>`.
- Produces:
  - `val freezableCount: StateFlow<Int?>`
  - `val lastResult: StateFlow<BulkResult?>`
  - `val isRunning: StateFlow<Boolean>`
  - `suspend fun refreshCandidates(op: BulkOp)`
  - `fun launch(op: BulkOp): Job` — returns the running job so a caller that needs to sequence
    work after the batch can `join()` it. Task 8 depends on this; observing `isRunning` instead
    would race, because a fast run can flip back to `false` before the observer starts collecting.
  - `fun consumeResult()`

`ManageAppUseCase` is a Koin `@Factory`, so injecting it into a `@Single` gives this runner one long-lived instance — fine, it is stateless.

- [ ] **Step 1: Write the implementation**

Create `app/src/main/java/com/valhalla/thor/data/freezer/BulkFreezeRunner.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkResult
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.freezableCandidates
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs bulk freeze/unfreeze for every surface that needs it — the QS tile and the launcher
 * Freeze-all / Unfreeze-all shortcuts.
 *
 * As a @Single it owns a process-lifetime scope, which is the point: a QS shade collapse
 * destroys the TileService, and pinning the batch to a service-lifetime scope would leave a
 * partial freeze. Because the scope lives here rather than in a companion object on the
 * service, nothing retains the destroyed service.
 */
@Single
class BulkFreezeRunner(
    private val freezerRepository: FreezerRepository,
    private val manageAppUseCase: ManageAppUseCase,
    private val preferenceRepository: PreferenceRepository,
    private val privilegeManager: PrivilegeManager,
    private val stateReader: AppFreezeStateReader,
    @Named("io") private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _freezableCount = MutableStateFlow<Int?>(null)

    /** Candidates for the last [refreshCandidates] sweep; null until the first sweep lands. */
    val freezableCount: StateFlow<Int?> = _freezableCount.asStateFlow()

    private val _lastResult = MutableStateFlow<BulkResult?>(null)

    /** Outcome of the last completed run, consumed once by whoever displays it. */
    val lastResult: StateFlow<BulkResult?> = _lastResult.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var activeJob: Job? = null

    /** Re-derive how many apps [op] would act on and publish it to [freezableCount]. */
    suspend fun refreshCandidates(op: BulkOp) {
        val watchlist = freezerRepository.getAllPackageNames()
        _freezableCount.value = freezableCandidates(watchlist, op, stateReader::stateOf).size
    }

    /**
     * Start a bulk run and return its job. Returns the in-flight job instead of starting a
     * second one — the pre-rework tile spawned a fresh unbounded batch over the same packages
     * on every tap.
     *
     * Returning the job rather than making callers watch [isRunning] is deliberate: a fast run
     * can flip isRunning back to false before an observer starts collecting, and `join()` has
     * no such window.
     */
    @Synchronized
    fun launch(op: BulkOp): Job {
        activeJob?.takeIf { it.isActive }?.let { return it }
        _isRunning.value = true
        val job = scope.launch {
            try {
                _lastResult.value = run(op)
            } finally {
                _isRunning.value = false
                // The sweep re-derives real state, so a killed or truncated batch self-heals:
                // whatever is left simply shows up as the next count.
                //
                // NOT runCatching: CancellationException is an Exception in Kotlin, and this
                // runs in a finally where cancellation is exactly what we may be unwinding
                // from. withContext(NonCancellable) lets the sweep finish even then, and the
                // narrow catch keeps a PackageManager failure from masking the real outcome.
                try {
                    withContext(NonCancellable) { refreshCandidates(op) }
                } catch (e: Exception) {
                    Logger.e("BulkFreezeRunner", "post-run candidate sweep failed", e)
                }
            }
        }
        activeJob = job
        return job
    }

    /** Clear [lastResult] after it has been shown, so a later shade-open does not replay it. */
    fun consumeResult() {
        _lastResult.value = null
    }

    private suspend fun run(op: BulkOp): BulkResult {
        if (!privilegeManager.state.value.hasAnyPrivilege) return BulkResult(0, 0, 0)

        val watchlist = freezerRepository.getAllPackageNames()
        val targets = freezableCandidates(watchlist, op, stateReader::stateOf)
        if (targets.isEmpty()) return BulkResult(0, 0, 0)

        val useSuspend = op == BulkOp.FREEZE &&
                preferenceRepository.userPreferences.first().freezerMode == FreezerMode.SUSPEND

        val succeeded = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val semaphore = Semaphore(MAX_CONCURRENT)

        // The batch is a child of `scope`, NOT of the withTimeoutOrNull block below. That is
        // the whole trick: withTimeoutOrNull is a scoping builder, so wrapping the batch
        // directly would cancel the children and then block until they finish — and these
        // children are blocking shell/binder calls that never observe cancellation, i.e. it
        // would wait for exactly the thing the timeout exists to escape. Racing a cancellable
        // join() instead lets us abandon and report on time.
        val job = scope.launch {
            targets.forEach { pkg ->
                launch {
                    semaphore.withPermit {
                        ensureActive()
                        val result = try {
                            when {
                                op == BulkOp.UNFREEZE -> manageAppUseCase.forceUnfreeze(pkg)
                                useSuspend -> manageAppUseCase.setAppSuspended(pkg, true)
                                else -> manageAppUseCase.setAppDisabled(pkg, true)
                            }
                        } catch (e: CancellationException) {
                            // CancellationException IS an Exception in Kotlin, so it must be
                            // rethrown ahead of any broad catch or ensureActive() above is
                            // defeated and the batch silently ignores cancellation.
                            throw e
                        } catch (e: Exception) {
                            Logger.e("BulkFreezeRunner", "bulk $op failed for $pkg", e)
                            Result.failure(e)
                        }
                        if (result.isSuccess) succeeded.incrementAndGet()
                        else failed.incrementAndGet()
                    }
                }
            }
        }

        val finished = withTimeoutOrNull(DEADLINE_MS) { job.join() } != null
        if (!finished) {
            // Best-effort. Any op already blocked in the shell runs to completion in the
            // background; freezing is idempotent, so that is harmless and the next sweep
            // shows the truth.
            job.cancel()
            Logger.d("BulkFreezeRunner", "bulk $op hit the ${DEADLINE_MS}ms deadline")
        }

        return BulkResult(
            total = targets.size,
            succeeded = succeeded.get(),
            failed = failed.get(),
        )
    }

    private companion object {
        const val MAX_CONCURRENT = 5
        const val DEADLINE_MS = 30_000L
    }
}
```

- [ ] **Step 2: Verify it compiles**

```
./gradlew :app:compileFossDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/freezer/BulkFreezeRunner.kt
git commit -m "feat(freezer): add BulkFreezeRunner

App-scoped runner shared by the QS tile and the launcher bulk shortcuts.

Restructures the deadline: the batch is a child of the runner's scope, not
of the withTimeoutOrNull block, so the timeout races a cancellable join()
instead of waiting on blocking shell calls it just cancelled. Counters are
atomic and incremental, so a deadline reports what resolved.

Also: privilege comes from PrivilegeManager's cached StateFlow instead of
three unbounded shell probes, one in-flight job per op, and Semaphore(5)
replacing both the tile's unbounded fan-out and the shortcut's sequential
loop."
```

---

### Task 6: `BulkResultNotifier` and the manifest permission

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/BulkResultNotifier.kt`
- Modify: `app/src/main/AndroidManifest.xml` (permission block, after line 33)

**Interfaces:**
- Consumes: `BulkResult` (Task 1); `bulkResultMessage` (Task 3); `UiText.asString(context)`.
- Produces: `class BulkResultNotifier { fun post(result: BulkResult) }`

- [ ] **Step 1: Declare the permission**

In `app/src/main/AndroidManifest.xml`, after the last `<uses-permission>` entry in the permission block (the one beginning at line 33), add:

```xml
    <!-- Bulk-freeze results from the QS tile and launcher shortcuts, neither of which has a
         usable foreground surface: a Toast is dropped by checkCanEnqueueToast and, even when
         enqueued, draws under the shade (layer 7 vs 17). Requested normally from Settings;
         never self-granted. Inert below API 33. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 2: Write the implementation**

Create `app/src/main/java/com/valhalla/thor/data/freezer/BulkResultNotifier.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.BulkResult
import com.valhalla.thor.presentation.tile.bulkResultMessage
import org.koin.core.annotation.Single

/**
 * Posts the outcome of a bulk run as a notification, when permitted.
 *
 * Strictly additive: the tile's own subtitle is the unconditional surface, so a user who
 * never grants POST_NOTIFICATIONS still sees their result. Nothing here ever grants the
 * permission — it is requested from Settings like any other runtime permission.
 */
@Single
class BulkResultNotifier(
    private val context: Context,
) {
    fun post(result: BulkResult) {
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(manager)

        // areNotificationsEnabled() covers both regimes with no SDK_INT branch: it is backed
        // by POST_NOTIFICATIONS on 33+ and by the user's app-level toggle on 28-32. The
        // channel check catches a user who silenced this channel specifically.
        val channelSilenced = manager.getNotificationChannelCompat(CHANNEL_ID)?.importance ==
                NotificationManager.IMPORTANCE_NONE
        if (!manager.areNotificationsEnabled() || channelSilenced) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.frozen)
            .setContentTitle(bulkResultMessage(result).asString(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setContentIntent(homeIntent())
            .build()

        // A fixed id, so repeated taps replace the previous result instead of stacking.
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // areNotificationsEnabled() can race a revocation; a lost result notification is
            // not worth crashing a background batch over. The tile subtitle still reports.
        }
    }

    private fun ensureChannel(manager: NotificationManagerCompat) {
        // IMPORTANCE_DEFAULT, not LOW: SystemUI's PeekNotImportantSuppressor strips any peek
        // below DEFAULT, so LOW would be silently invisible. Not HIGH either — that is
        // dishonest for a routine result.
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            )
                .setName(context.getString(R.string.channel_bulk_result_name))
                .setShowBadge(false)
                .build()
        )
    }

    private fun homeIntent(): PendingIntent? {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val CHANNEL_ID = "thor.bulk_result"
        const val NOTIFICATION_ID = 1001
        const val TIMEOUT_MS = 10_000L
    }
}
```

- [ ] **Step 3: Wire it into the runner**

In `app/src/main/java/com/valhalla/thor/data/freezer/BulkFreezeRunner.kt`, add `private val notifier: BulkResultNotifier,` to the constructor (immediately after `private val stateReader: AppFreezeStateReader,`), then change the body of `launch` from:

```kotlin
            try {
                _lastResult.value = run(op)
            } finally {
```

to:

```kotlin
            try {
                val result = run(op)
                _lastResult.value = result
                if (result.total > 0) notifier.post(result)
            } finally {
```

`result.total > 0` keeps a no-op run (no privilege, or nothing to do) from posting a "Froze 0 apps" notification — those cases are already visible on the tile itself.

- [ ] **Step 4: Verify it compiles**

```
./gradlew :app:compileFossDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/freezer/BulkResultNotifier.kt \
        app/src/main/java/com/valhalla/thor/data/freezer/BulkFreezeRunner.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat(freezer): report bulk results via notification

Thor's first notification. Strictly additive to the tile subtitle, gated on
areNotificationsEnabled() plus a per-channel importance check, which covers
API 28-32 and 33+ with no SDK_INT branch.

IMPORTANCE_DEFAULT because SystemUI strips any peek below it. Fixed id so
repeat taps replace rather than stack. Declares POST_NOTIFICATIONS; the
grant itself is an ordinary runtime request, added in a later commit."
```

---

### Task 7: Rewrite `FreezerTileService`

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/tile/FreezerTileService.kt` (full rewrite, 141 lines → ~110)

**Interfaces:**
- Consumes: `BulkFreezeRunner` (Tasks 5-6); `tileVisualFor` / `TileVisual` (Task 2); `bulkResultMessage` (Task 3); `PrivilegeManager.state` (existing).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Replace the file wholesale**

Overwrite `app/src/main/java/com/valhalla/thor/presentation/tile/FreezerTileService.kt` with:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.valhalla.thor.R
import com.valhalla.thor.data.freezer.BulkFreezeRunner
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.BulkOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Quick Settings tile that bulk-freezes the freezer watchlist.
 *
 * The tile owns no work. [BulkFreezeRunner] is an app-scoped @Single that runs the batch and
 * publishes its state, so a QS shade collapse destroying this service cannot truncate a
 * freeze and cannot leave anything retaining the destroyed instance. This service only
 * observes and paints.
 */
class FreezerTileService : TileService() {

    private val runner: BulkFreezeRunner by inject()
    private val privilegeManager: PrivilegeManager by inject()

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val listenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = listenScope

        // Phase 1: paint synchronously from whatever is already cached, so the tile is never
        // blank while the sweep runs. Phase 2 is the collector below, which repaints as the
        // real state lands.
        paint()

        listenScope.launch {
            combine(
                privilegeManager.state,
                runner.freezableCount,
                runner.isRunning,
                runner.lastResult,
            ) { _, _, _, _ -> Unit }.collect { paint() }
        }

        // Phase 2: re-derive from live per-app state. The watchlist alone cannot tell us
        // whether anything is still freezable, which is why the tile used to stay lit after
        // freezing everything.
        listenScope.launch { runner.refreshCandidates(BulkOp.FREEZE) }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        // AOSP's CustomTile.handleClick early-returns on STATE_UNAVAILABLE, so this branch is
        // only reachable when the tile was painted CHECKING and the probe then resolved to
        // "no privilege" — a real race, not dead code.
        if (!privilegeManager.state.value.hasAnyPrivilege) {
            paint()
            return
        }
        runner.launch(BulkOp.FREEZE)
    }

    /** Push the current state onto the tile. Safe to call when unbound — [qsTile] is null then. */
    private fun paint() {
        val tile = qsTile ?: return
        val visual = tileVisualFor(
            privilege = privilegeManager.state.value,
            freezableCount = runner.freezableCount.value,
            isRunning = runner.isRunning.value,
        )
        val count = runner.freezableCount.value ?: 0

        tile.state = when (visual) {
            TileVisual.NO_PRIVILEGE -> Tile.STATE_UNAVAILABLE
            TileVisual.NOTHING_TO_FREEZE -> Tile.STATE_INACTIVE
            // CHECKING stays INACTIVE (clickable) on purpose: an UNAVAILABLE tile never
            // receives onClick, so an optimistic paint would swallow taps until the next
            // listen.
            TileVisual.CHECKING -> Tile.STATE_INACTIVE
            TileVisual.WORKING -> Tile.STATE_ACTIVE
            TileVisual.READY -> Tile.STATE_ACTIVE
        }

        // A finished run's message wins the subtitle once, then is consumed so a later
        // shade-open shows the live count again rather than replaying a stale result.
        val result = runner.lastResult.value
        val subtitle = if (result != null && !runner.isRunning.value) {
            runner.consumeResult()
            bulkResultMessage(result).asString(this)
        } else {
            when (visual) {
                TileVisual.CHECKING -> getString(R.string.tile_checking)
                TileVisual.WORKING -> getString(R.string.tile_freezing)
                TileVisual.NO_PRIVILEGE -> getString(R.string.tile_no_privilege)
                TileVisual.NOTHING_TO_FREEZE -> getString(R.string.tile_no_apps)
                TileVisual.READY ->
                    resources.getQuantityString(R.plurals.tile_subtitle_format, count, count)
            }
        }

        // Never setLabel: it mutates the tile's identity in the QS picker.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = subtitle
        tile.contentDescription = "${getString(R.string.freezer)}: $subtitle"
        tile.updateTile()
    }
}
```

- [ ] **Step 2: Verify it compiles**

```
./gradlew :app:compileFossDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Confirm the leak sources are gone**

```
grep -n "appScope\|Toast\|companion object" app/src/main/java/com/valhalla/thor/presentation/tile/FreezerTileService.kt
```
Expected: no output. The static scope, all four dead Toast call sites, and the companion object are all gone.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/tile/FreezerTileService.kt
git commit -m "fix(tile): derive tile state from real per-app state

The tile counted freezer watchlist rows, which never change when apps are
frozen, so it stayed lit forever after a freeze-all. It now paints from
BulkFreezeRunner's live candidate sweep.

Drops the static appScope, the four Toast call sites (all dead: dropped by
checkCanEnqueueToast, and drawing under the shade even when enqueued), and
the callback into a possibly-destroyed service. The tile now observes and
paints; nothing retains it after the shade collapses.

CHECKING paints INACTIVE rather than UNAVAILABLE on purpose — AOSP never
delivers onClick to an unavailable tile."
```

---

### Task 8: Route the launcher shortcuts through the runner

`runBulk` currently discards every `Result` and reports nothing at all. Delegating gives it the same filtering, concurrency, deadline and reporting as the tile.

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/data/launcher/FreezerShortcutManager.kt:116-140` (`runBulk` + its KDoc), `:38-44` (constructor), `:225` (`buildAppShortcut` call site), `:238-247` (`isFrozen` + its comment block)

**Interfaces:**
- Consumes: `BulkFreezeRunner.launch(op): Job` (Task 5); `AppFreezeStateReader.stateOf` (Task 4); `FreezeState` (Task 1).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Replace `runBulk`**

In `FreezerShortcutManager.kt`, replace the whole `runBulk` function (lines 116-140, the KDoc at 116 through the closing brace at 140) with:

```kotlin
    /** Bulk freeze/unfreeze every package in the freezer, off the finishing activity. */
    fun runBulk(disable: Boolean) {
        val op = if (disable) BulkOp.FREEZE else BulkOp.UNFREEZE
        // Delegate so this shares the tile's candidate filter, Semaphore(5), deadline and
        // result reporting. It previously ran sequentially and discarded every Result.
        val job = bulkFreezeRunner.launch(op)
        scope.launch {
            // Icons follow app state, so wait for the run to settle before rebuilding them.
            // join() rather than watching isRunning: a fast run can flip that back to false
            // before this coroutine is even dispatched, and we would then wait forever.
            job.join()
            val pinnedIds = pinnedShortcutIds()
            val updated = freezerRepository.getAllPackageNames()
                .filter { FreezerShortcutContract.appShortcutId(it) in pinnedIds }
                .mapNotNull { pkg -> appLabel(pkg)?.let { buildAppShortcut(pkg, it) } }
            if (updated.isNotEmpty()) {
                ShortcutManagerCompat.updateShortcuts(context, updated)
            }
        }
    }
```

- [ ] **Step 2: Swap the constructor dependencies**

Change the constructor (lines 38-44) to drop the two dependencies now only used by the runner and add the two it needs:

```kotlin
@Single
class FreezerShortcutManager(
    private val context: Context,
    private val freezerRepository: FreezerRepository,
    private val bulkFreezeRunner: BulkFreezeRunner,
    private val stateReader: AppFreezeStateReader,
) {
```

`manageAppUseCase` and `preferenceRepository` were used only by the old `runBulk` body; the runner owns both concerns now. Koin resolves the new parameters by type with no module edit.

- [ ] **Step 3: Delete the duplicated predicate**

Delete `isFrozen` entirely — lines 238-247, i.e. the three-line `// "Frozen" == …` comment at 238-240 plus the function at 241-247 — and change its single call site in `buildAppShortcut` (line 225) from:

```kotlin
            .setIcon(appIcon(packageName, grayscale = isFrozen(packageName)))
```

to:

```kotlin
            .setIcon(appIcon(packageName, grayscale = stateReader.stateOf(packageName) == FreezeState.FROZEN))
```

- [ ] **Step 4: Fix the imports**

Remove these four now-unused imports (lines 9, 25, 27, 28):

```kotlin
import android.content.pm.ApplicationInfo          // only isFrozen used FLAG_SUSPENDED
import com.valhalla.thor.domain.model.FreezerMode  // only runBulk read freezerMode
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
```

Add:

```kotlin
import com.valhalla.thor.data.freezer.AppFreezeStateReader
import com.valhalla.thor.data.freezer.BulkFreezeRunner
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.FreezeState
```

Keep `android.content.pm.PackageManager` (`appLabel`/`appIcon` still use it) and keep
`androidx.core.content.pm.ShortcutInfoCompat` (it is `buildAppShortcut`'s return type at line 221).
`kotlinx.coroutines.flow.first` may become unused — check before removing it; other functions in
the file may still use it.

- [ ] **Step 5: Verify it compiles with no new warnings**

```
./gradlew :app:compileFossDebugKotlin --rerun-tasks
```
Expected: BUILD SUCCESSFUL, and no `unused import` / `never used` / `unused parameter` warning
naming `FreezerShortcutManager.kt`. Capture the warning list before this task and diff against it —
any warning mentioning this file is new and must be fixed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/launcher/FreezerShortcutManager.kt
git commit -m "fix(shortcuts): route bulk shortcuts through BulkFreezeRunner

runBulk discarded every Result and reported nothing, and ran sequentially
where the tile fanned out unbounded. It now delegates, inheriting the shared
candidate filter, Semaphore(5), deadline and result notification.

Deletes isFrozen, which re-implemented the domain predicate inline and
swallowed every exception."
```

---

### Task 9: Notification permission row in Settings

The permission is requested here and nowhere else. A `TileService` cannot show a runtime-permission dialog, and self-granting is explicitly rejected by the spec (§3): Dhizuku physically cannot do it, and once the manifest declares the permission the ordinary dialog grants exactly the same capability.

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt:345-384` — the `// ── PERMISSIONS ──` section: label at 346, `usageGranted` at 350, `DisposableEffect` at 351-359, `Column(` at 361, its `SettingsSwitchRow` at 368-383, closing `}` at 384
- Modify: `app/src/main/res/values/strings.xml` and the four locale files

**Interfaces:**
- Consumes: `SettingsSwitchRow(icon, title, subtitle, checked, onCheckedChange)` and `SettingsSectionLabel(...)`, both already used in this file.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add the row strings**

Each block goes beside that locale's existing `usage_access*` group, so translators find them
together: `values` after line 201, `values-ar` after 479, `values-es` after 445, `values-fr`
after 445, `values-zh-rCN` after 437.

In `app/src/main/res/values/strings.xml`:

```xml
    <string name="notification_access">Notifications</string>
    <string name="notification_access_granted_subtitle">Bulk freeze results are shown as notifications</string>
    <string name="notification_access_needed_subtitle">Allow notifications to see bulk freeze results</string>
```

`values-ar/strings.xml`:

```xml
    <string name="notification_access">الإشعارات</string>
    <string name="notification_access_granted_subtitle">تظهر نتائج التجميد الجماعي كإشعارات</string>
    <string name="notification_access_needed_subtitle">اسمح بالإشعارات لرؤية نتائج التجميد الجماعي</string>
```

`values-es/strings.xml`:

```xml
    <string name="notification_access">Notificaciones</string>
    <string name="notification_access_granted_subtitle">Los resultados del congelado masivo se muestran como notificaciones</string>
    <string name="notification_access_needed_subtitle">Permite las notificaciones para ver los resultados del congelado masivo</string>
```

`values-fr/strings.xml`:

```xml
    <string name="notification_access">Notifications</string>
    <string name="notification_access_granted_subtitle">Les résultats du gel groupé sont affichés en notifications</string>
    <string name="notification_access_needed_subtitle">Autorisez les notifications pour voir les résultats du gel groupé</string>
```

`values-zh-rCN/strings.xml`:

```xml
    <string name="notification_access">通知</string>
    <string name="notification_access_granted_subtitle">批量冻结结果将以通知形式显示</string>
    <string name="notification_access_needed_subtitle">允许通知以查看批量冻结结果</string>
```

- [ ] **Step 2: Add the row**

In `SettingsScreen.kt`, inside the PERMISSIONS `Column` (the one opened at line 362), directly after the closing `)` of the existing usage-access `SettingsSwitchRow` and before the `Column`'s closing brace, add:

```kotlin
            // API 33+ only: below that, notifications need no runtime permission at all, so a
            // row here would be a switch the user cannot meaningfully change.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var notificationsGranted by remember {
                    mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted -> notificationsGranted = granted }

                SettingsSwitchRow(
                    icon = R.drawable.frozen,
                    title = stringResource(R.string.notification_access),
                    subtitle = if (notificationsGranted) {
                        stringResource(R.string.notification_access_granted_subtitle)
                    } else {
                        stringResource(R.string.notification_access_needed_subtitle)
                    },
                    checked = notificationsGranted,
                    onCheckedChange = {
                        // Only the system dialog can grant this. Thor never self-grants it,
                        // even when it holds root/Shizuku: the dialog grants the identical
                        // capability, and Dhizuku cannot self-grant at all.
                        if (!notificationsGranted) {
                            notificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        }
                    }
                )
            }
```

Extend the existing `DisposableEffect(lifecycleOwner)` at lines 350-359 so returning from system Settings refreshes this too — inside its `ON_RESUME` branch, after `usageGranted = usageAccessManager.isGranted()`, add:

```kotlin
                    notificationsGranted =
                        NotificationManagerCompat.from(context).areNotificationsEnabled()
```

This requires hoisting `notificationsGranted` above the `DisposableEffect`. Declare both state variables together, before the `DisposableEffect`, and guard the notification one so it is only read on TIRAMISU+:

```kotlin
        var usageGranted by remember { mutableStateOf(usageAccessManager.isGranted()) }
        var notificationsGranted by remember {
            mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
        }
```

(then drop the inner `var notificationsGranted` from the block above, keeping only the launcher inside the `if`).

- [ ] **Step 3: Add the imports**

At the top of `SettingsScreen.kt`:

```kotlin
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
```

`android.os.Build` is already imported (line 7) — do not add it twice. `androidx.activity.compose`
is on the classpath (`app/build.gradle.kts:228`, `libs.androidx.activity.compose`), so no dependency
change is needed.

- [ ] **Step 4: Verify it compiles**

```
./gradlew :app:compileFossDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-ar/strings.xml \
        app/src/main/res/values-es/strings.xml \
        app/src/main/res/values-fr/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(settings): request POST_NOTIFICATIONS from the permissions section

The ordinary runtime dialog, API 33+ only. Never self-granted, even under
root or Shizuku: with the permission declared the dialog grants the same
capability, and Dhizuku cannot self-grant at all — its DPM alternative would
also policy-fix the permission on, taking the choice away from the user."
```

---

### Task 10: Retire the superseded follow-up, file the two new ones, verify the whole build

**Files:**
- Delete: `docs/follow-ups/freezer-tile-service-rework.md`
- Create: `docs/follow-ups/grant-permission-missing-user-flag.md`
- Create: `docs/follow-ups/odin-root-availability-cache.md`

- [ ] **Step 1: Delete the superseded follow-up**

```bash
git rm docs/follow-ups/freezer-tile-service-rework.md
```

Its problem statement and acceptance criteria are written around a Toast that never worked, and its line 65 claims the `if (scope != null) refreshTile()` guard "is correct and should survive" — the reason given (that `updateTile()` can throw) is wrong, since `updateTile()` catches `RemoteException` internally. The spec supersedes it.

- [ ] **Step 2: File the `--user` follow-up**

Create `docs/follow-ups/grant-permission-missing-user-flag.md`:

```markdown
# Follow-up: `grantPermission` omits `--user`, unlike every other shell command

**Status:** Deferred — real but unrelated to the tile rework that surfaced it.
**Severity:** Minor–Major (silently targets the wrong user on multi-user/work-profile devices).
**Effort:** small.
**Raised by:** research during the FreezerTileService rework (2026-07-28).

Files: `app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt:533-542`,
`ShizukuSystemGateway.kt:164`, `DhizukuSystemGateway.kt:165`

## Problem

`SystemGateway.grantPermission` builds `pm grant <pkg> <perm>` with no `--user`. Every other
shell command Thor issues passes one (`Shizuku.kt:63/94/162`).
`PackageManagerShellCommand.runGrantRevokePermission` initialises
`userId = UserHandle.USER_SYSTEM` and has done so unchanged from android-9 through main, so
the grant lands on user 0 regardless of which user Thor is running as.

On a single-user device this is invisible. In a work profile or secondary user it grants the
permission to the wrong user's copy of the package — or fails outright.

This affects the Permission Manager screen today (`TogglePermissionUseCase.kt:19` →
`PermissionRepositoryImpl.kt:82`). It does **not** affect the QS tile work: that rework
deliberately does not use `grantPermission` at all.

## Sketch

Pass `--user ${UserHandle.myUserId()}` in all three gateways, matching the existing Shizuku
call sites. Verify on a device with a work profile before and after.
```

- [ ] **Step 3: File the Odin cache follow-up**

Create `docs/follow-ups/odin-root-availability-cache.md`:

```markdown
# Follow-up: root availability is cached for the process lifetime

**Status:** Deferred — narrow, and the fix belongs in Odin rather than Thor.
**Severity:** Minor (stale privilege state after a revocation, until restart).
**Effort:** small in Thor (add a re-probe), medium in Odin (invalidate the cache).
**Raised by:** assessment during the FreezerTileService rework (2026-07-28).

## Problem

Odin's `MainShell.cached` returns the same `ShellImpl` until its `status < 0`, and `status`
is computed **once at construction** via an `id` → `uid=0` probe. `isRoot` is `status >=
ROOT_SHELL`. So `isRootAvailable()` answers from a snapshot taken when the shell was first
built.

Consequence: if the user **revokes** root after granting it, Thor keeps reporting root as
available for the rest of the process lifetime. `PrivilegeManager.refresh()` re-runs the
probe but the probe itself is cached, so it cannot see the change.

The reverse direction — denying root at first ask — works correctly: the build falls back to
`sh`, `isRoot` is false, and privileged UI disables itself. That was verified on device
during the tile assessment, which is why the tile rework does not treat this as a blocker.

## Sketch

Not a decision, just the shape:

1. In Odin, invalidate the cached shell when a privileged command fails with a
   permission-denied exit, or expose an explicit `Shell.invalidate()`.
2. In Thor, call it from `PrivilegeManager.refresh()` so the existing refresh path becomes
   genuinely re-probing.

Deferring is reasonable: revoking root mid-session is rare, and the failure mode is a
privileged action that fails with a clear error rather than silent corruption.
```

- [ ] **Step 4: Run the full unit test suite**

```
./gradlew :app:testFossDebugUnitTest --rerun-tasks
```
Expected: PASS. The repo has 104 `@Test` methods across 15 files today; this plan adds 23 (10 in Task 1, 8 in Task 2, 5 in Task 3), so expect 127 with 0 failures. A report of `UP-TO-DATE` means the tests did not run — re-run with `--rerun-tasks`.

- [ ] **Step 5: Build all release variants**

```
./gradlew assembleFossRelease assembleStoreRelease
```
Expected: BUILD SUCCESSFUL. This is the R8 path — the store flavor is obfuscated, and the tile is reached only reflectively by SystemUI, so a release build is the only thing that proves the service survives shrinking.

- [ ] **Step 6: Lint all three variants**

```
./gradlew lintFossDebug lintFossRelease lintStoreRelease
```
Expected: 0 errors, and no warnings other than the 5 known intentional `VectorPath` ones. Watch specifically for `MissingTranslation` (all four new string groups must exist in all four locales) and `MissingQuantity` (Arabic requires all six plural forms — this only bites if a new `<plurals>` is added, which this plan avoids by using `<string>` for the new result cases).

- [ ] **Step 7: Commit**

```bash
git add docs/follow-ups/grant-permission-missing-user-flag.md \
        docs/follow-ups/odin-root-availability-cache.md
git commit -m "docs(follow-ups): retire the tile follow-up, file two new ones

The tile follow-up is superseded by the rework spec; its acceptance criteria
are written around a Toast that never worked, and its claim that the
refreshTile guard is correct rests on updateTile() throwing, which it does
not — it catches RemoteException internally.

New: grantPermission omits --user (affects the Permission Manager screen
today), and Odin caches root availability for the process lifetime so a
revocation is invisible until restart."
```

---

## Device Verification

Not automatable — hand these to the maintainer after Task 10. Run each under **root, Shizuku and Dhizuku**.

- [ ] Add several apps to the freezer, open QS: tile is lit, "N apps · tap to freeze".
- [ ] Tap: apps freeze, and on reopening the shade the tile is dim with "No apps". **This is the reported bug.**
- [ ] Revoke privilege, reopen QS: tile is greyed and does not respond to taps.
- [ ] Tap, then collapse the shade immediately: the result still arrives.
- [ ] Notification with the permission granted, with it denied, and with the shade open vs. collapsed.
- [ ] The Settings notification row appears on API 33+ and is absent below.
- [ ] The Freeze-all launcher shortcut now reports a result where it previously reported nothing.
- [ ] Unfreeze-all launcher shortcut still unfreezes everything.
- [ ] Pinned per-app shortcut icons still grey/ungrey correctly after a bulk run.
- [ ] Tap the tile twice rapidly: one batch runs, not two.
- [ ] LeakCanary or a heap dump after tapping and collapsing: no `FreezerTileService` retained.
