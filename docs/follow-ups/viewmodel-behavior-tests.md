# Follow-up: ViewModel behavior tests need `kotlinx-coroutines-test` + turbine

**Status:** Partly done, **no longer blocked**. The two dependencies landed on `chore/tier0-batch-1`
and were used: `MainViewModelTest` (24 tests) and `SecurityViewModelTest` (7) now cover those two
view models behaviourally. `AppListViewModel` — the one this doc was actually written about — is
still uncovered, and the four tests listed under *Sketch* §4 are still unwritten. What remains is
writing them, not enabling them.
**Severity:** Minor (test-coverage gap; no runtime defect). **Effort:** small–medium.
**Revised:** 2026-07-30, after the dependencies landed.
**Raised by:** an external model's review of PR #278 (2026-07-27), which noted that the PR's timing
behavior is covered only by pure-function tests over the delay constants, not by tests of the
ViewModel logic that consumes them. The observation is correct.

Files: `app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt`,
`app/src/test/java/com/valhalla/thor/presentation/appList/TransitionSettleDelayTest.kt`,
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

This is therefore no longer a missing-capability gap. It is unwritten tests, with the harness they
need already sitting beside them.

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

- All five behaviors asserted under virtual time; no measurable wall-clock added to the suite.
- **Mutation-checked**, the same way `REFRESH_INDICATOR_MIN_VISIBLE` was: deleting the
  `ensureActive()` call, or flipping the `deferForTransition` default, must make at least one test
  fail. A test that survives both mutations is not constraining the behavior it claims to.
