# Dependency cleanup device validation

Date: 2026-10-05. Branch: `chore/dependency-r8-cleanup`, based on `dev` at
`b679e91ec574a92de1659f7c452d66484e928c19` (PR #557 merge).

## Scope and builds

The candidate retains extended material icons, replaces Biometric KTX with base Biometric,
and removes redundant direct Room KTX, Accompanist DrawablePainter, adaptive-navigation,
and `:bypass` core-ktx declarations. No production Kotlin code or R8 rule changes are part
of this candidate. Lottie remains unchanged during these checks.

The host gates and unsigned release comparisons are recorded in the
[dependency audit](../analysis/dependency-r8-2026-10-05/README.md). Both release builds and
`test lintFossDebug lintStoreRelease` passed; the final DEX/resources/native contents are
identical to merged `dev`.

The normal FOSS Debug and instrumentation APKs were rebuilt from this candidate. Separate
FOSS and Store release fixtures use `com.valhalla.thor.dependencycleanup` and debug signing
through an artifact-only Gradle init script, retaining release R8/resource shrinking and
compressed-native packaging. These fixtures are **not byte-identical to production APKs**.
The daily-use `com.valhalla.thor` package was not replaced.

| Target | API / ABI / page size | Selected instrumentation |
| --- | --- | ---: |
| Odin Magisk emulator, `emulator-5582` | 36 / arm64-v8a / 16 KiB | **17/17 passed** |
| POCO F7 / ReSuKiSU, `1da5425f` | 36 / arm64-v8a / 4 KiB | **17/17 passed** |

No selected test was skipped or ignored. No `adb root` command was used. Broad privileged
mutation suites and package-data clearing were not run.

## Instrumentation coverage

Each target ran three separate batches:

1. **Room: 9 checks.** All six `SweepMigrationTest` cases, plus
   `PrivilegeSweepDaoTest#createAllocatesQueueSequenceAndClaimsInInsertionOrder`,
   `DataTaskDaoTest#observationReloadsAfterTaskDetailItemAndOutputChanges`, and
   `DataTaskIdentityDaoTest#activeTaskIdentitySurvivesProcessLocalStateLoss`.
   These use dedicated test databases. Including `PrivilegeSweepDaoTest` makes
   `ThorTestRunner` isolate application startup for this whole batch.
2. **Native/font regression: 7 checks.** `NativeLibraryPackagingTest`,
   `OutfitVariableFontTest`, and `FontPresetRenderingTest`, with ordinary application
   startup enabled for the invocation.
3. **Production read: 1 check.**
   `UadFilterIntegrationTest#installedSystemAppsMatchUadTagsAndDetails` exercises real
   package scanning, Room-backed repositories, UAD loading/filtering and detail reads.
   This ran separately from the isolated Room batch so production Koin bindings were used.

## UI and biometric evidence

- Both targets cold-launched the updated Debug app and rendered real installed-app icons
  through the production Coil/DrawablePainter path. The phone also exercised frozen and
  suspended icon decoration without changing package state.
- The emulator opened portrait app details, switched to landscape, opened the wide detail
  screen, and returned to the app list. The adaptive layout rendered both columns. Its
  original rotation settings (`accelerometer_rotation=1`, `user_rotation=0`) were restored.
- Both targets installed and cold-launched the minified FOSS fixture, then updated the same
  package to the minified Store fixture and cold-launched it. Store's Backup & Restore hub
  opened on the emulator; the directory was empty, so archive-thumbnail rendering was not
  exercised there.
- On the phone, the minified FOSS fixture initially showed Biometric Lock off and available.
  Enabling it produced the framework fingerprint prompt. The maintainer confirmed that
  their enrolled fingerprint unlocked Thor, and the main UI was observed afterward.
- A cold-start cancellation left the phone on **Authentication Failed / Authentication
  cancelled**, offering **TRY AGAIN / EXIT**, with the main UI unavailable. Retrying
  returned to the main UI after fingerprint authentication; the maintainer confirmed it
  worked as expected.
- After updating the same package to the minified Store fixture, the maintainer confirmed
  another successful fingerprint unlock. Settings still showed **App lock on**, and the
  active app window retained `FLAG_SECURE`. Screenshot/screen-recording blocking itself
  is not claimed as validated by these ADB captures.

Initial startup permission dialogs from the phone's Shizuku-compatible provider and the
emulator's Magisk manager were distinguished from the biometric prompt. They are not
biometric failures. The emulator's minified fixture root request timed out without a grant.

Captured Store-fixture process logs on both targets contain no `FATAL EXCEPTION`, fatal
signal, `UnsatisfiedLinkError`, `NoClassDefFoundError`, or `NoSuchMethodError` matches.
These are bounded smoke checks, not exhaustive coverage of every app operation.

## Artifacts and cleanup

Evidence is retained under
`~/.codex/artifacts/thor-dependency-r8-audit-2026-10-05/device-validation/`: build logs,
APK hashes, per-device instrumentation logs, screenshots/XML, package dumps, and runtime
log summaries. Normal production release outputs were restored after the fixture builds;
their SHA-256 hashes exactly match the audit's validated final cleanup APKs.

Both temporary minified fixtures and their scratch UI XMLs were removed after validation.
The updated normal Debug app was retained. Biometric Lock was changed only in the temporary
fixture; the daily app's security preference was untouched. All five tested build/catalog
changes retained the exact source hashes recorded by the passing host-gate run.
