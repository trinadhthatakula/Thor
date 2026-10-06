# Unused WorkManager helper cleanup validation

Date: 2026-10-05. Branch: `chore/legacy-workmanager-cleanup`, based on `dev` at
`6967de7d5ce636d9ab66553a6d0e6df1e75c2231` (PR #560).

## Scope

WorkManager remains installed and initialized for legacy-job compatibility. This cleanup
removes unused enqueue/future helpers, an uncalled launcher cancellation method, an unused
notification capability wrapper, and an execution fence with no production registration
callers. Associated constructor fixtures, obsolete tests, and comments are updated.

Legacy worker reconstruction, data-job observation, notification cancellation, and the
data drain gate remain. Sweep cutover still awaits WorkManager cancellation before Room
reconciliation, serialized by its process gate and mutex. The cutover tests check that
both callers await cancellation and that a failed cancellation does not
mutate Room or cache success. The architecture test now forbids new production
WorkRequests without an exception for the old launcher.

No dependency, manifest, version, or R8 changes were made. No APK-size or runtime-performance
improvement is claimed; this is removal of unused code.

## Host checks

- `test`: 7,152 tests passed (3,576 each for FOSS Debug and Store Debug), with no
  failures, errors, or skips.
- `lintFossDebug` and `lintStoreRelease`: passed, with hints only and no warnings/errors,
  including no MissingTranslation or SyntheticAccessor findings.
- FOSS Debug, its instrumentation APK, and both minified FOSS/Store release APKs built.

The initial combined build received a Gradle daemon stop request during R8. Repeating
the same gates/builds with `--no-daemon --max-workers=1` succeeded. This was a host build
interruption, not an Android app crash. Existing compiler deprecation warnings are unrelated.

The test-only rebuild later exhausted the Kotlin compiler's 4 GiB heap while compiling
`FreezerViewModel`. A command-line-only override,
`-Pkotlin.daemon.jvmargs="-Xmx6g -XX:+UseG1GC"`, completed compilation and the unit-test gate.
Lint then required `String.toUri()` in the new test helper; that correction was applied.
The final instrumentation APK, `lintFossDebug`, and `lintStoreRelease` passed with the same
local memory override. No project heap setting or production source was changed for these
build issues.

## Device checks

| Target | API / ABI / page size | Final result |
| --- | --- | ---: |
| Odin Magisk AVD `Odin_Magisk_API36_1`, `emulator-5590` | 36 / arm64-v8a / 16 KiB | **9/9 passed** |
| POCO F7 / ReSuKiSU, `1da5425f` | 36 / arm64-v8a / 4 KiB | **9/9 passed** |

No selected test was skipped or ignored. The separately running `emulator-5582` belonged
to another project and was not used for these checks.

The selected suites are `LegacyWorkManagerCutoverIntegrationTest` (4 checks),
`PrivilegeSweepWorkerIntegrationTest` (1), and `PrivilegeSweepServiceIntegrationTest` (4).
They exercise real framework services and in-memory Room/WorkManager databases with
controlled executors. Production application startup is isolated during these tests.

Coverage includes legacy export dispatch, archive rejection when process keys are missing,
legacy sweep reconstruction without mutation, startup pruning without starting foreground
services, immediate foreground promotion, repeated/sticky service wakes, task-specific
notification cancellation, queue admission, and ownership during service cleanup.

The first Odin run passed all nine checks. The first phone run passed eight; one test read
the initial preparing notification, which has no actions, before Android published the
running notification. The test now waits for the exact request's existing cancellation
PendingIntent using a read-only `FLAG_NO_CREATE` lookup. It also compares stable cancellation
and content identities instead of progress text that legitimately changes asynchronously.
Production notification code was not changed.

## Production startup smoke checks

Separately from the isolated test invocation, both devices cold-launched the normal FOSS
Debug app and opened the dashboard, installed-app list, Backup & restore hub, and Guardians
task queue. The phone's existing completed task history remained visible. Captured process
logs contain no FATAL EXCEPTION, fatal signal, ANR, missing-class/method, native-link, or
out-of-memory error signatures.

No `adb root`, package-data clearing, or actual privileged package mutation was needed.
The daily-use release package was not replaced. Device execution covered FOSS Debug;
minified releases were built but not installed for this cleanup. These checks do not
constitute exhaustive backup/restore, skipped-version upgrade, OEM, or ABI coverage.

## Evidence

Build logs, per-device test results, screenshots/UI XML, process logs, APK hashes, and
tested-source hashes are retained in
`~/.codex/artifacts/thor-legacy-workmanager-cleanup-2026-10-05/`.
Initial-run evidence is preserved separately from the corrected final test runs.
