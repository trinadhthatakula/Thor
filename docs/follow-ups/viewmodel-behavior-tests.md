# Follow-up: ViewModel behavior tests need `kotlinx-coroutines-test` + turbine

**Status:** Deferred — blocked on two test dependencies the project does not yet have.
**Severity:** Minor (test-coverage gap; no runtime defect). **Effort:** small–medium.
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

The suite cannot express them today:

- `app/build.gradle.kts:237` declares exactly one unit-test dependency — `testImplementation(libs.junit)`.
- No `kotlinx-coroutines-test` ⇒ no virtual time. Each of these tests would otherwise have to sleep
  in real wall-clock (800 ms+ per case), which is a bad trade for a suite that currently runs
  104 tests instantly.
- No turbine ⇒ no ergonomic way to assert a sequence of `StateFlow` emissions.
- All 15 existing test classes are synchronous pure-logic tests; `grep -rl "runTest\|runBlocking\|CoroutineScope" app/src/test` returns nothing.

So this is a missing-capability gap, not an oversight about whether the tests are worth having.

## Sketch

Not a decision, just the shape:

1. **Add the dependencies.** In `gradle/libs.versions.toml`:
   ```toml
   kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
   turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
   ```
   `kotlinxCoroutines` already exists (`1.11.0`), so coroutines-test costs no new version entry —
   and it **must** stay pinned to the same version as the runtime artifact. Resolve the current
   turbine release rather than copying a version from here.
   Then in `app/build.gradle.kts`: `testImplementation(libs.kotlinx.coroutines.test)` and
   `testImplementation(libs.turbine)`.

2. **Check the dispatcher seam.** The A2 batch of the audit remediation moved dispatchers behind
   injection, so the ViewModel may already be testable as-is. Where `viewModelScope` still lands on
   `Dispatchers.Main`, add a JUnit rule doing `Dispatchers.setMain(StandardTestDispatcher())` /
   `resetMain()`.

3. **Fake, don't mock.** `AppRepository` is an interface and `getAllApps()` is a flow, so a
   hand-written fake with a controllable channel is enough. No mockk/Robolectric — keep these as
   plain JVM tests so they stay in `testFossDebugUnitTest`.

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
