# Follow-up: ViewModel behavior tests need `kotlinx-coroutines-test` + turbine

**Status:** ✅ **Done, with one half-covered item named below.** `AppListViewModelTest` covers the
four temporal behaviours from *Sketch* §4 in 8 tests on virtual time, on branch
`test/applist-viewmodel-behavior`. The fifth — the cancelled-scan rule — is covered on the view
model side only (the relaunch tears the previous collector down); its repository half, the
`ensureActive()` in `AppRepositoryImpl`, is **out of reach from a JVM test** and is deliberately
left to the instrumented story. See *Acceptance*. The dependencies had landed earlier on
`chore/tier0-batch-1` and are also used by `MainViewModelTest` (24 tests) and
`SecurityViewModelTest` (7).
**Severity:** Minor (test-coverage gap; no runtime defect). **Effort:** small–medium.
**Revised:** 2026-07-30 — twice: once when the dependencies landed, again when the tests were
written and the *"only unwritten tests"* claim below turned out to be wrong. See
*What it actually took*.
**Raised by:** an external model's review of PR #278 (2026-07-27), which noted that the PR's timing
behavior is covered only by pure-function tests over the delay constants, not by tests of the
ViewModel logic that consumes them. The observation is correct.

Files: `app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt`,
`app/src/test/java/com/valhalla/thor/presentation/appList/AppListViewModelTest.kt`,
`app/src/test/java/com/valhalla/thor/presentation/appList/TransitionSettleDelayTest.kt`,
`app/src/test/java/com/valhalla/thor/presentation/ViewModelTestDoubles.kt`,
`app/src/main/java/com/valhalla/thor/domain/repository/{PrivilegeStateProvider,StorageStatsProvider,UsageAccessGate,AppShortcutController}.kt`,
`app/build.gradle.kts`, `gradle/libs.versions.toml`

## Problem

PR #278 changed three behaviors that are all **temporal**:

- pull-to-refresh no longer pays the settle delay (`loadApps()` with `deferForTransition = false`);
- screen entry still does (`loadApps(deferForTransition = true)`);
- `holdRefreshIndicator()` keeps `isManualRefreshing` true for `REFRESH_INDICATOR_MIN_VISIBLE`,
  cancels any previous hold, and **deliberately never lowers the flag from a cancelled job** — the
  only thing that cancels a hold is a newer hold, which has already raised it again.

What shipped is `TransitionSettleDelayTest`: 5 tests pinning the constants (`0/400/800`, `600 ms`).
Those tests are real — one was rewritten after mutation testing showed the original passed with
`1.milliseconds` substituted, i.e. it constrained nothing — but they still only constrain the
**mapping**. Nothing asserts that a manual refresh actually skips the delay, that entry actually
pays it, or that a second refresh arriving mid-hold keeps the indicator up instead of hiding it at
the original deadline. Those are precisely the regressions this PR could re-introduce.

~~The suite cannot express them today~~ — it can now. That paragraph described a state that ended on
2026-07-30: `kotlinx-coroutines-test` and turbine are in the catalog and on `:app`'s test classpath,
`MainDispatcherRule` exists at `app/src/test/java/com/valhalla/thor/presentation/MainDispatcherRule.kt`,
shared fakes live in `ViewModelTestDoubles.kt` next to it, and several suites already run on virtual
time. `AppListViewModel`'s temporal behaviour is simply not among them yet.

~~This is therefore no longer a missing-capability gap. It is unwritten tests, with the harness they
need already sitting beside them.~~ Half right, and the wrong half was load-bearing — see below.

## What it actually took

The harness was there; the *view model* was not constructible. Four of `AppListViewModel`'s eleven
collaborators were concrete Koin `@Single` classes bound to Android:

| Collaborator | Why it could not be built on a plain JVM |
|---|---|
| `PrivilegeManager` | registers Shizuku binder/permission listeners from `init` |
| `StorageStatsHelper` | `context.getSystemService(StorageStatsManager)` + `context.packageManager` in property initializers |
| `UsageAccessManager` | `context.getSystemService(AppOpsManager)` in a property initializer |
| `FreezerShortcutManager` | `Context` + `ShortcutManagerCompat` |

`android.jar` methods throw `RuntimeException("Stub!")` in unit tests (this project does not set
`testOptions.unitTests.isReturnDefaultValues`), and all four classes are `final`, so there was no
subclass or stub-Context route either. That is why this view model — the one the doc was written
about — stayed uncovered while two others got tested: **not** because nobody wrote the tests.

The seam, kept deliberately additive:

- four narrow ports in `domain/repository/` — `PrivilegeStateProvider` (just `state`),
  `StorageStatsProvider`, `UsageAccessGate`, `AppShortcutController` (just `disableAppShortcut`) —
  each covering only what `AppListViewModel` actually calls;
- the four classes implement them and declare `@Single(binds = [...])`, which **adds** a bound type
  without removing the concrete one, so the other seven call sites (`HomeViewModel`,
  `FreezerViewModel`, `BulkFreezeRunner`, `FreezerTileService`, …) are untouched;
- `AppListViewModel` takes the ports, plus `@Named("default")`/`@Named("io")` dispatchers instead of
  hardcoded `Dispatchers.*` — the convention CLAUDE.md already states, and the thing that lets the
  `flowOn` sort/filter pipeline behind `uiState` share the test's virtual clock rather than run on a
  real thread pool;
- fakes for the four ports live beside the existing ones in `ViewModelTestDoubles.kt`.

**The generalisable bit:** "the harness exists" is not the same as "the subject is reachable".
Before filing a test-coverage follow-up, try constructing the class.

## Sketch

Not a decision, just the shape:

1. ~~**Add the dependencies.**~~ Done. For the record, what landed in `gradle/libs.versions.toml`:
   ```toml
   kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
   turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
   ```
   `kotlinxCoroutines` already existed (`1.11.0`), so coroutines-test cost no new version entry — and
   it **must** stay pinned to the same version as the runtime artifact. Turbine resolved to `1.2.1`.

2. ~~**Check the dispatcher seam.**~~ Answered: `viewModelScope` does land on `Dispatchers.Main`, so
   `MainDispatcherRule` (`Dispatchers.setMain(StandardTestDispatcher())` / `resetMain()`) is the seam.
   It already exists — use it rather than writing a second one.

3. **Fake, don't mock.** Still the rule, and now the precedent: `ViewModelTestDoubles.kt` holds
   hand-written fakes shared across the suites written so far. `AppRepository` is an interface and
   `getAllApps()` is a flow, so a fake with a controllable channel is enough. No mockk/Robolectric —
   keep these as plain JVM tests so they stay in `testFossDebugUnitTest`.

4. **Tests worth having** (behavioral, not constant-pinning):
   - `loadApps()` starts the scan without advancing virtual time — the actual fix in this PR;
   - `loadApps(deferForTransition = true)` does **not** start the scan until
     `settleDelayFor(intensity)` has elapsed, asserted per intensity (this is what makes the
     0/400/800 mapping meaningful rather than decorative);
   - `isManualRefreshing` stays true across the whole `REFRESH_INDICATOR_MIN_VISIBLE` window and
     then clears;
   - a refresh at t=300 ms extends the indicator to t=900 ms instead of clearing at t=600 ms — the
     cancelled-hold rule, which is the subtlest thing in the change and currently untested;
   - a cancelled scan stops promptly and emits nothing into a torn-down collector, pairing with the
     `ensureActive()` + `CancellationException` rethrow added in `AppRepositoryImpl`.

## Acceptance

- ✅ for four of the five, ⚠️ for the fifth. The four temporal behaviours are asserted under virtual
  time; the cancelled-scan rule is asserted on the view model side and **not** on the repository
  side (see the `ensureActive()` note below — the criterion as written cannot be met from
  `testFossDebugUnitTest`). No measurable wall-clock added to the suite either way (the 8 tests run
  in ~0.2 s total; the suite went 209 → 217).
- **Mutation-checked**, the same way `REFRESH_INDICATOR_MIN_VISIBLE` was: deleting the
  `ensureActive()` call, or flipping the `deferForTransition` default, must make at least one test
  fail. A test that survives both mutations is not constraining the behavior it claims to.
  - ✅ `deferForTransition = false` → `true`: **3 tests fail** (`a manual refresh starts the scan
    without advancing the clock`, and both indicator tests — a deferred refresh never raises the
    flag).
  - ⚠️ `ensureActive()`: **not reachable from a JVM test, and the criterion as written cannot be
    met.** It sits inside `AppRepositoryImpl.getAllApps()`'s `callbackFlow`, behind
    `pm.getInstalledPackages`, a `BroadcastReceiver` and `context.resources` — the same wall this
    doc's own premise tripped over. Only an instrumented test can delete-and-observe it.
    What stands in for it is the view model half of the same rule: `a second load tears the previous
    scan down before starting the next` asserts the repository flow has exactly one live collector
    after a relaunch, and **deleting `appsJob?.cancel()` makes it fail**. `ensureActive()` only
    matters because that cancel happens; this pins the half that is in reach. Closing the other half
    belongs with the instrumented-test story, not here.
