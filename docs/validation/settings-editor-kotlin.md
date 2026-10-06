# Settings Editor Kotlin migration validation

Validated 2026-10-04. Code revision: `c3702064e8a9409305f025218b6a8af4e54af5f3`.
Base: `6720196613117be3f14319b566ae5dbfcac82763` (`dev`, the #552 merge).
Branch: `chore/settings-editor-kotlin`; any PR must target `dev`.
Version remains **1.97.0 / 1970**.

## Changes and compatibility

Converted `SettingsEditorBridge`, `SettingsEditorLiveWriterProbe`, and
`SettingsEditorStallProbe` to Kotlin in their existing packages. All retain their
original class names and public static `void main(String[])` entry points.
The production R8 keep rule is unchanged.

- The production bridge separates provider acquisition from request execution.
  Existing request/response fields, sanitized errors, nullable values, conflict
  handling, process exits, provider attribution, and watchdogs are preserved.
  This conversion does not introduce stricter request validation.
- The live writer retains its explicit disposable-key restriction, ownership and
  symlink checks, bounded reads, durable markers, process identities, child group,
  signal handling, and deadlines. Named `OsConstants` replace Java octal masks.
  Java ASCII trimming and regex splitting semantics are preserved for `/proc`.
- Helpers shared with the nested probe have Kotlin `internal` visibility, matching
  their previous package-level role without synthetic accessor methods. There is
  no suppression of `SyntheticAccessor`.
- Test launchers now use `test APK:app APK` as the quoted `CLASSPATH`. A fresh
  `app_process` does not inherit instrumentation's class loader. DEX inspection
  confirmed Kotlin runtime classes are in the app APK and absent from the test
  APK. The child writer inherits the same classpath.

The two compile-only `vm-runtime` stubs remain Java. No dependency, supported ABI,
locale, release version, or production root policy was changed.

## Host verification

Zulu **21.0.12.1**, Gradle **9.8.0**, AGP **9.5.0-alpha08**, Kotlin **2.4.20**,
and the pinned published Odin **1.1.0** were used, without local composite overrides.

```sh
./gradlew --max-workers=1 :app:assembleFossDebug :app:assembleFossDebugAndroidTest
./gradlew --max-workers=1 test lintFossDebug lintStoreRelease \
  :app:assembleFossRelease :app:assembleStoreRelease
```

Both commands passed. There are **3,554 tests per debug flavor**, with zero failures,
errors, or skips. The final invocation reused valid unchanged unit-test results
from the preceding invocation. FOSS Debug lint has **9 hints** and Store Release
has **8 hints**, with **zero errors or warnings**: no MissingTranslation or
SyntheticAccessor findings. `javap` confirmed all three static entry points and
no synthetic accessor methods on the final live-writer object. `git diff --check`
passed.

## Device verification

| Target | ROOT through Odin, final debug APKs | Minified FOSS and Store bridge |
| --- | --- | --- |
| `Odin_Magisk_API36_1`, Magisk 30.7, Android 16/API 36 (`emulator-5582`) | **9/9 passed**, zero skips | Both passed in shell `app_process` |
| POCO F7 / `25053PC47G`, ReSuKiSU, Android 16/API 36 (`1da5425f`) | **9/9 passed**, zero skips | Both passed in shell `app_process` |

The nine ROOT checks cover reconciliation (3), production settings round trips
(1), stalled-helper timeout/cancellation (1), hostile live-writer/child cancellation
(1), and history UI (3). They verify create/update/delete/undo, exact multiline and
Unicode values, empty strings and SQL null, stale conflicts, diagnostic views,
receipt barriers, real cleanup acknowledgement, and all four producer/child/watchdog
identities. The physical hostile-cancellation fixture also checked refusal through
its already available Shizuku provider. Full SHIZUKU round trips were not rerun.

The final emulator live-producer sequence additionally passed **observe + recovery**.
Its arm phase was deliberately interrupted by killing only the acknowledged app PID;
that interruption is not a passing JUnit test. Heartbeat progression proved continued
writes after app death. Normal helper completion retained the same-boot receipt;
an actual emulator reboot allowed public write admission to retire it and restore
state. Ordinary Home startup was established before recovery instrumentation.

Both release APKs were staged temporarily, made read-only, and loaded directly by
shell `app_process`; no release app was installed. Each flavor passed disposable
System/Secure/Global key checks for exact text, stale conflicts, empty strings, SQL
null, deletion, and property enumeration on both devices. These are minified **shell**
checks; ROOT/Odin acceptance used the debug APKs.

Installed debug app/test APK hashes were verified on both devices. Journal semantics
and disposable key sets matched before/after; live fixture directories and temporary
release APKs were removed. All root acceptance used Thor debug's existing manager
grant through Odin. **No `adb root`, root-policy change, physical-device reboot, or
daily-use release-app replacement was needed.**

## Artifacts and limits

| Artifact | SHA-256 |
| --- | --- |
| FOSS debug app | `191efbe179de18502c90b91e58852d71a8b9c5d36283ee542fcee1c125361e39` |
| FOSS debug test | `9fdb97fca1950f94024a3f5af94cda3be2608d6c5bd6408bfe20be9797f42e02` |
| FOSS release, unsigned | `b69005952b2d2b286df18ff00699aabc7f67111b830f857361e87c6a2fa73eda` |
| Store release, unsigned | `838e61743d5ae0bb202d4308e4ddba95cdfb4017b68d689748f9007965c9177f` |

Evidence is retained in
`~/.codex/artifacts/thor-settings-editor-kotlin-2026-10-04/`:
`validation-summary.json`, `tested-source.json` (1,046 input hashes),
`gates-final.log`, `debug-build-final.log`, `final-entrypoints.txt`,
`*-final-root-validation.json`, `emulator-final-live-validation.json`,
`*-final-release-bridge.json`, lint XML reports, and `apks/final/`.
The immutable `device-start-manifest.json` preserves the manifest hash recorded
by the final phase runner; `host-validation.json` contains the completed host summary.
Private restoration originals were not copied into host artifacts.

The first lint run exposed private-helper synthetic accessors, which were fixed
before the final checks. An earlier recovery instrumentation attempt ended before
any test started after reboot; an unchanged-APK recovery retry passed. Those earlier
attempts remain separately recorded and are not substituted for the final results.

Android 9–12/API 28–31 and additional OEMs were not retested in this migration.
Unsigned release artifacts were exercised through their bridge entry point, not
through PackageInstaller or a signed release application lifecycle. No APK-size
improvement or performance gain is claimed by this conversion.
