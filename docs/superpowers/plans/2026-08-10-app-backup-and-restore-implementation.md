# App Backup and Restore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Back up one installed app — its `.xapk` bundle plus its private data — into a single encrypted `.thorbak` container, and restore that container onto a device, installing the app from the bundle first when it is absent.

**Architecture:** Three pure units carry the risk and are JVM-tested — the shell strings (`AppDataCommands`), the chunk-framed GCM codec (`AppArchiveCipher`), and the restore gate (`ArchiveRestoreGate`). Around them sit two use cases that sequence privileged shell calls through the existing `SystemRepository.executeShellCommand` seam, a streaming zip writer that appends one encrypted member per storage class so peak disk is the largest single class rather than the sum, and a new WorkManager-hosted foreground-service job seam that both use cases run on. The derived key never leaves the process: it lives in a process-scoped holder, never in WorkManager's `Data`.

**Tech Stack:** Kotlin, Coroutines, Compose, Koin Annotations (compiler plugin), `androidx.work:work-runtime` + `koin-androidx-workmanager`, `javax.crypto` (AES-256-GCM, PBKDF2WithHmacSHA256), `AndroidKeyStore`, DataStore Preferences, `java.util.zip`, kotlinx.serialization, JUnit4 + `kotlinx-coroutines-test` + Turbine.

**Spec:** `docs/superpowers/specs/2026-08-10-app-backup-and-restore-design.md`. Section references below (§4.1, §7.2, …) are to that document. Read the spec section named in a task before starting it.

## Global Constraints

- **minSdk 28, targetSdk/compileSdk 37**, all three from `gradle/libs.versions.toml`. Anything API-gated needs a working API-28 path, not a `@RequiresApi`.
- **JDK 21 (Zulu).** Gradle runs through `ctx_execute` with `language: "shell"`, never Bash.
- **Koin is the compiler plugin, not KSP.** `koinCompiler { }` sets `compileSafety`, `strictSafety` and `unsafeDslChecks` all true, so a missing or ambiguous binding **fails the build**. Annotate the class (`@Single`, `@Factory`, `@KoinViewModel`); only add a `@Single` function to `di/Modules.kt` for a type the component scan cannot see. Never add a KSP dependency for Koin.
- **Lint is fatal:** `abortOnError = true`, `warningsAsErrors = true`, `checkTestSources = true` (`app/build.gradle.kts:219-242`). A new warning is a red build.
- **The `UsableSpace` lint hint recommends the API that reproduced the #373 cache-clear bug.** Suppress it with a reason; do not obey it.
- **Every user-facing string this feature adds goes in one new file, `app/src/main/res/values/strings_backup.xml`, created in Task 8** — never in `values/strings.xml`. `MissingTranslation` is fatal here and the five string files (`values`, `values-ar`, `values-es`, `values-fr`, `values-zh-rCN`) are all at exactly **544** `<string name=`; a string added to `values/strings.xml` without four hand-written translations reddens `./gradlew lint`. The new file carries a file-level `tools:ignore="MissingTranslation"`, exactly as `app/src/main/res/values/non-translatable.xml` already does. Translating the block is a follow-up row, recorded in Task 18 — not a step in any task here. **Corollary:** any `%d` in one of these strings draws fatal `PluralsCandidate` (precedent: `<string name="export_bulk_progress" tools:ignore="PluralsCandidate">`), so a real count becomes `<plurals>` — the base file already has 15 — and a number that is not a count gets `tools:ignore="PluralsCandidate"` with the reason beside it.
- **Naming (§2):** `RestoreRequest.kt` already owns the word *restore* in this codebase (unfreeze via the Stormbringer launcher hook, GH#239). **Nothing here may be named bare `Restore*`.** Every type in this feature is `AppArchive*`, `AppData*`, or carries an explicit `Archive` qualifier — `RestoreAppArchiveUseCase` and `ArchiveRestoreGate` qualify; a `RestoreState` or `RestoreWorker` does not.
- **Two different numbers are called "uid" (§6).** `userId` is the Android multi-user id that appears in a path (`/data/user/0/…`). `uid` is the app's Linux uid from `ApplicationInfo.uid` and appears only in `chown`. They are never the same value and never interchangeable. Name every parameter accordingly.
- **Crypto values are fixed:** PBKDF2WithHmacSHA256, **210,000** iterations, **256-bit** key, **16-byte** salt generated fresh per archive. AES-256-GCM, **1 MiB** plaintext chunks, **128-bit** tag, IV = `8-byte per-member nonce ‖ 4-byte big-endian chunk index`. Verifier = `HMAC-SHA256(key, "thor-data-archive-v1")` truncated to **16 bytes**.
- **`CipherInputStream` is forbidden.** It swallows `AEADBadTagException` and returns `-1`, so a tampered archive decrypts to a silently short plaintext.
- **Container:** a plain zip named `<pkg>-<versionCode>.thorbak`, holding `thorbak.json`, `app.xapk`, and `<class>.tar.gz.enc` per selected class. `thorbak.json` is written **last** (chunk counts are unknown until members exist).
- **Excluded from `CE`, `DE` and `EXTERNAL_DATA`:** `cache`, `code_cache`, `no_backup`. `EXTERNAL_MEDIA` excludes nothing — it is user-visible content.
- **POSIX `du -s -k`, never `-b`.** `-b` is not safe to assume on toybox.
- **No Room involvement.** No migration, no `schemas/7.json`.
- **Every new file gets the two-line SPDX header** used by every other file in the tree:
  ```kotlin
  // SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
  // SPDX-License-Identifier: GPL-3.0-or-later
  ```
- **`versionCode` in `gradle.properties` is not touched by this work.** It changes only in a `chore(release)` commit.
- **Never `git add -A` / `git add .`** — stage explicit paths. `docs/audit/` and `docs/enforcement/` must never be committed.
- **Test counts come from `app/build/test-results/**/*.xml`, never from a Gradle log line.** Delete `app/build/test-results/testFossDebugUnitTest` and pass `--rerun-tasks`, or the task reports UP-TO-DATE and silently skips.
- **`rikka.shizuku.Shizuku`'s static initialiser builds a Binder and throws "not mocked" under JVM tests.** Nothing in a unit-tested unit may reference it, directly or through a class whose `<clinit>` touches it.

## Resolved ambiguities and deviations

Read these before Task 1. Each is a decision made at plan time that the spec left open or stated in a way the code cannot follow literally.

1. **The capability gate is a probe, not a root check (§6 wins over §11).** §6 says capability is decided by probing the channel that does the work; §11 says the probe is "root-gated … following `clearCache`'s precedent verbatim". They conflict: a root-started Shizuku shell passes the probe, so an `isRootAvailable()` pre-gate would make "requires Root" a lie on that device. **Implement §6**: run the probe through `executeShellCommand` (which `runGatewayAction` routes to whichever gateway is active) and decide on its exit code. §11's "root-gated" is honoured as the *shape* of the refusal — a disabled control with an explicit reason string, the way `clearCache` refuses — not as the predicate.

2. **Encrypted members are `DEFLATED` at `Deflater.NO_COMPRESSION`, not `STORED`.** `ZipOutputStream` requires `size`, `compressedSize` and `crc` to be set *before* `putNextEntry` for a `STORED` entry. The ciphertext CRC is not knowable until the ciphertext exists, so `STORED` would force either encrypting twice or buffering the whole member — both cost more than level-0 deflate's framing overhead (≈5 bytes per 64 KiB, under 0.01%). Level 0 does no compression, so §4.1's actual requirement ("compressing ciphertext is CPU that occasionally grows the file") is met. This is the same technique §14 already names for the bundle. `app.xapk` is written the same way, for the same reason. **`thorbak.json` alone is `STORED`** — it is built in memory, so its bytes and CRC are both known.

3. **The archive is read through `/proc/self/fd`, with a staged copy as the fallback.** `java.util.zip.ZipFile` needs random access and a `File`; `thorbak.json` is the *last* entry, so a sequential `ZipInputStream` scan would read the whole container to reach the header. `ArchiveSource` (Task 12) opens the `content://` Uri with `openFileDescriptor("r")` and hands `ZipFile` the path `/proc/self/fd/<fd>`, which is a real seekable file for any provider backed by one. Only when that fails does it copy to `cacheDir`, and that copy is recorded as a known limitation.

4. **`du` on a missing path exits nonzero**, which is indistinguishable from "the probe failed" — and rendering a legitimately-absent class as `Undetermined` instead of `Empty` puts "size unknown" next to a class that has nothing in it. The command therefore tests for the directory first and prints a `THOR_ABSENT` marker.

5. **No JVM test can cover the WorkManager wiring.** There is no Robolectric, no `koin-test` and no `work-testing` on the test classpath, and `TestListenableWorkerBuilder` needs a real `Context`. Task 1's verification is the **build** (Koin's `strictSafety` proves the binding graph resolves) plus device check 13. Do not add Robolectric for this; do not fabricate a unit test that asserts nothing.

6. **`isUsablePackageName` is re-implemented in `AppDataCommands.kt` rather than imported.** The existing one is `internal` in `com.valhalla.thor.data.repository` (`ObbProbeParser.kt:175`). `AppDataCommands.kt` lives in `domain/`, and a domain file importing from `data/` inverts the layering the whole module is built on. The duplication is four lines and is commented as deliberate.

7. **The new privileged operations go on two narrow ports, not onto `SystemRepository`.** §6 requires the probe to run on *the same privileged surface* as the work, which reads like "add the methods to `SystemRepository`". Doing that breaks the build in two places that have nothing to do with backup: `FreezeAppUseCaseTest.kt:33` and `BulkFreezeWorkerTest.kt:121` each hand-write a `RecordingSystemRepository` implementing the interface in full, so every added member is a compile error in both. Instead `AppDataProbe` (Task 6, the two read-only questions) is **implemented by `SystemRepositoryImpl` itself** — so §6's property holds: the probe and the work run through the same object, the same `runGatewayAction`, the same active gateway — while `AppDataArchiveGateway` (Task 9) carries the archive-specific surface that was never system-wide to begin with. Neither widens an interface a test double already implements.

8. **Progress is published to an in-memory registry, never through `setProgress` (§9.2 taken literally).** Every `setProgress` call is a row written to WorkManager's SQLite database, and WorkManager throttles observers to roughly one update a second; a gigabyte copied in 1 MiB chunks would attempt a thousand writes on the hot path. `JobRegistry` (Task 8) holds a `StateFlow` per job id and the ViewModel collects it directly. The consequence is that progress does not survive process death — which costs nothing, because an archive job's key lives in that same process (`ArchiveKeyHolder`) and so a killed job cannot resume either. WorkManager's own persisted `WorkInfo.State` stays the source of truth for *running / succeeded / failed*.

9. **One work chain for every archive job, `APPEND_OR_REPLACE` (§9.3).** The unique work name deliberately excludes the target, so jobs *serialise* and peak disk stays at one storage class however many backups the user queues. Per-target dedup — the double-tap defence — moves to `jobTag(kind, target)` and `getWorkInfosByTag`, which is also how the UI reattaches to a running job after a rotation. `APPEND_OR_REPLACE` rather than `APPEND` so one failed or cancelled job cannot wedge the chain permanently.

10. **The archive use cases import `AppArchiveCipher` from `data`, and they are the first `domain` files in this codebase to import anything from `data` at all.** `rg '^import com.valhalla.thor.data' app/src/main/java/com/valhalla/thor/domain/` returns nothing today. §11 nonetheless puts `BackupAppArchiveUseCase`/`RestoreAppArchiveUseCase`/`OpenArchiveUseCase` under `domain/usecase/` and `AppArchiveCipher` under `data/backup/`, so the reference is what the spec asks for. Kept as written rather than "fixed", for two reasons: the cipher is pure JCE with no Android types and no Thor types, so it costs the use cases none of their JVM-testability (which is the property the layer rule exists to protect); and wrapping it in a `domain` interface with exactly one implementation would add a seam no test needs — the tests use the *real* cipher with `iterations = 4`, because the framing is the thing worth exercising. What is **not** acceptable is the reverse direction for values: a `domain/model` file importing a constant from `data`, which is why Task 10 Step 3 moves `KDF_SALT_BYTES` and `KDF_ITERATIONS` into `domain/model/ArchiveBackupRequest.kt` instead of importing them back out of `AppArchiveCipher.kt`.

## §15's plan-time checks, answered

The spec listed three things to check *before* writing code. Two are answered; one is not desk-answerable and became a device check.

1. **Koin's compiler plugin with `strictSafety` and constructor-injected workers.** Not asserted — *proven by a build*. `koin-androidx-workmanager` contributes `workManagerFactory()`, Task 1 wires it inside `startKoin`, and Task 15 puts `@KoinWorker` on both workers with six- and seven-parameter constructors. Task 15 Step 11's `assembleFossDebug` is the gate: with `strictSafety` on, a factory that cannot construct a worker is a compile-time failure naming the binding, not a runtime crash. There is no JVM test for it and none is to be added (deviation 5).
2. **Toybox behaviour** for `tar -czf` / `-cf`, `ls -A`, `du -s -k`, `restorecon -RF`, `chown -R`. **Not desk-answerable** — it is a property of the device's shell, and Thor's supported range spans several toybox vintages. It is device check 14, and every command that could differ has a fallback written into Task 3's strings rather than a probe.
3. **Can `AppBundleBuilderImpl` be called without disturbing the export path under test in PR #376?** ✅ Yes, verified against `app/src/main/java/com/valhalla/thor/domain/repository/AppBundleBuilder.kt:28-33`. `build` already takes `cacheSubDir` (default `"share_temp"`) and an optional `fileName`, and its own KDoc states that distinct scopes never touch each other's files — the builder wipes its per-package directory on entry, so sharing a scope is what would break, not using a new one. Task 15 therefore passes `cacheSubDir = "archive_bundle"` and leaves `fileName` null: the archive entry name is fixed at `app.xapk` by the container, so what the builder calls the staged file does not matter. **The export path needs no change at all**, which is the outcome §15 was checking for.

## File Structure

| File | Responsibility |
|------|----------------|
| `gradle/libs.versions.toml` | Add `work` and `koin-androidx-workmanager` versions/libraries. |
| `app/build.gradle.kts` | Add the two dependencies. |
| `app/src/main/AndroidManifest.xml` | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`, the `dataSync` type overlay on `SystemForegroundService`, removal of WorkManager's `androidx.startup` initializer, and the `.thorbak` VIEW filter. |
| `app/src/main/java/com/valhalla/thor/ThorApplication.kt` | `workManagerFactory()` inside `startKoin`. |
| `domain/model/AppDataArchive.kt` | `DataClass`, `DataClassSize`, `SizeLabelKind`, and the whole `thorbak.json` header shape with its `Json` config. Pure. |
| `domain/model/AppDataCommands.kt` | Every shell string, each returning `String?` where null means *refused*. Path quoting, symlink guards, package-name validation, the enumerate-and-filter function, and the reply parsers. Pure. |
| `domain/model/ArchiveRestoreGate.kt` | §8.1 as one pure function over a header plus what is installed. |
| `domain/model/ThorJob.kt` | `ThorJobKind`, `ThorJobStage`, `ThorJobProgress`, the shared chain name and the per-target tag. Pure. |
| `domain/repository/AppArchiveStore.kt` | Streaming destination port — `.partial` open, write, promote, delete. `File`/String/`OutputStream` only. |
| `domain/repository/AppDataProbe.kt` | The two read-only questions — can this channel read private data, and how big is a class. A narrow port on `SystemRepositoryImpl` rather than a widening of `SystemRepository`, which two hand-written test doubles implement in full. |
| `domain/repository/AppDataArchiveGateway.kt` | The archive-specific privileged surface: staging paths, `tar` in and out, `chown`/`restorecon`, uid and signer lookups. One port so both use cases stay JVM-testable. |
| `domain/repository/ArchiveSource.kt` | `ArchiveSource` + `ArchiveSourceFactory`: reading a `.thorbak` back. `File`/`String` only, so no `Uri` reaches a testable path. |
| `domain/repository/AppArchiveInstaller.kt` | Installing the embedded `.xapk` and placing its OBB. A narrow port, because `InstallerRepository.installPackage` takes a `Uri` and returns `Unit`. |
| `domain/repository/ArchiveBreadcrumbStore.kt` | The interruption breadcrumb's port (§8.5). |
| `domain/repository/ArchiveJobLauncher.kt` | The enqueue port plus `ThorJobStatus`, so a ViewModel can start a job and reattach to a running one without importing WorkManager. |
| `domain/model/ObbPlacement.kt` | Moved out of `ObbInstaller.kt` so `domain` can name a placement outcome. |
| `domain/usecase/MeasureAppDataUseCase.kt` | Capability probe + per-class `du`. |
| `domain/usecase/BackupAppArchiveUseCase.kt` | §7.2 sequence. |
| `domain/usecase/RestoreAppArchiveUseCase.kt` | §8.3 sequence. |
| `domain/usecase/OpenArchiveUseCase.kt` | Header read and passphrase unlock — everything a restore screen may do before anything destructive. |
| `domain/usecase/ReadInstalledAppFactsUseCase.kt` | The one place that turns a package name into the `InstalledAppFacts` the gate needs (signer, version code, version name), so the screen and the worker cannot disagree about what is installed. |
| `data/backup/AppArchiveCipher.kt` | Key derivation, verifier, chunk framing. Streams and bytes in and out, no Thor types. |
| `data/backup/PassphraseVault.kt` | `VaultKeyProvider` seam, the Keystore implementation, and the DataStore cache with its cache-never-truth contract. |
| `data/backup/ZipArchiveSource.kt`, `UriArchiveSourceFactory.kt` | Opening a `content://` archive for random access, via `/proc/self/fd`, with the copy-to-cache fallback for a provider that hands back a pipe. |
| `data/backup/FileArchiveBreadcrumbStore.kt` | The breadcrumb as one JSON file in `filesDir`. |
| `data/backup/ArchiveOrphanSweeper.kt` | The launch-time breadcrumb report and orphan sweep (§8.5, §10). Exact names only. |
| `data/repository/AppArchiveInstallerImpl.kt` | `InstallerRepository` + `InstallerEventBus` + `ObbInstaller.placeStreaming`, behind the port. |
| `data/backup/job/ThorJobWorker.kt` | `CoroutineWorker` base: foreground promotion, notification, progress, cancellation. |
| `data/backup/job/ThorJobNotifications.kt` | The job channel and the `ForegroundInfo`, on ids `BulkResultNotifier` does not own. |
| `data/backup/job/JobRegistry.kt` | `StateFlow` per job id, so the UI never reads WorkManager `Data`. |
| `data/backup/job/ArchiveKeyHolder.kt` | Process-scoped derived-key holder. Never persisted. |
| `data/backup/job/AppArchiveWorker.kt` | The two workers; each dispatches to one use case. |
| `data/backup/job/ThorJobLauncher.kt` | The enqueue seam: derive the key in the foreground, hand it to `ArchiveKeyHolder`, enqueue on the one chain. |
| `data/repository/AppArchiveStoreImpl.kt` | MediaStore / SAF / legacy-Downloads streaming writer. |
| `data/repository/AppDataArchiveGatewayImpl.kt` | `executeShellCommand` calls, staging paths, `PackageManager` lookups. |
| `data/repository/DataArchiveCapabilityCache.kt` | Probe result cached per privilege generation. |
| `presentation/backup/AppBackupSheet.kt`, `AppBackupViewModel.kt` | Class checkboxes, sizes, passphrase, destination, progress. |
| `presentation/backup/ArchiveRestoreScreen.kt`, `ArchiveRestoreViewModel.kt` | Header display, gates, class selection, progress. |
| `presentation/navigation/ThorRoute.kt`, `presentation/main/MainScreen.kt`, `HomeActivity.kt` | The restore screen's two entry points: a Settings row, and a `.thorbak` opened from a file manager. `HomeActivity` reads the VIEW intent's Uri; `MainScreen` consumes it once and navigates. |
| `presentation/settings/PassphraseSettingsSheet.kt`, `PassphraseSettingsViewModel.kt` | Saving, replacing and forgetting the remembered passphrase, with §5.4's warning stated where the choice is made. |
| `presentation/settings/SettingsScreen.kt` (existing) | A *Backup & restore* section holding the Restore entry, the passphrase row and the interruption notice. |
| `app/src/main/res/values/strings_backup.xml` | **Every** string this feature adds, in one file with a file-level `tools:ignore="MissingTranslation"`. See Global Constraints for why it is not `values/strings.xml`. |
| `docs/follow-ups/README.md`, `docs/follow-ups/app-data-backup-and-xapk-export.md` | Row 23 closed, §13's release checklist and §14's limitations recorded, §16's follow-ups filed — including translating `strings_backup.xml`. No `release-notes/` file: notes ship in the `chore(release)` commit that bumps `versionCode`. |

## Device verification checklist (§12)

None of these are desk-testable. Task 18 records them; the owner runs them.

1. Round trip on a small app with only `CE` data.
2. Round trip on a game with splits and OBB.
3. Restore onto a **fresh install** — proves the `chown`.
4. Restore onto a differently-signed package — must refuse.
5. Restore with the app absent — installs from the bundle, then restores data.
6. Restore with only some classes selected.
7. Wrong passphrase — rejected before streaming.
8. Corrupted / truncated archive — rejected, nothing written.
9. Interrupted restore — breadcrumb present, warning on next launch.
10. Shizuku-only device — gate disables backup; restore offers install-only.
11. `ls -Z` after restore shows app contexts, and the app launches with its data.
12. Large payload — peak disk stays at the largest single class, not the sum.
13. Backup survives leaving the app (the foreground service actually works).
14. **Toybox behaviour** (§15): `tar -czf` and the `-cf` fallback, `ls -A`, `du -s -k`, `restorecon -RF`, `chown -R`, and `find … -exec … +` / `-exec … \;`.

---

### Task 1: Dependencies, the foreground service, and the Koin worker factory

Nothing in this task is unit-testable (see resolved ambiguity 5). Its gate is a green build plus the two `aapt2` assertions in Step 6, which read the *merged* manifest — the only ground truth for what shipped.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/valhalla/thor/ThorApplication.kt:206`

**Interfaces:**
- Consumes: nothing.
- Produces: a `WorkManager` whose `WorkerFactory` constructor-injects from Koin, so Task 8 can annotate a worker `@KoinWorker` and take repository parameters in its constructor.

- [ ] **Step 1: Add the two libraries to the version catalog**

`androidx.work:work-runtime` 2.11.2 is already in the local Gradle cache, as is `io.insert-koin:koin-androidx-workmanager:4.2.2`. Confirm before typing a version:

```bash
ls ~/.gradle/caches/modules-2/files-2.1/androidx.work/work-runtime/
ls ~/.gradle/caches/modules-2/files-2.1/io.insert-koin/koin-androidx-workmanager/
```

Use the newest **stable** `work-runtime` those list (`2.11.2`; not `2.12.0-alpha01`). The Koin artifact must match the existing `koin` version key exactly — a Koin core/module version skew fails at runtime, not at build time.

In `gradle/libs.versions.toml`, under `[versions]`:

```toml
work = "2.11.2"
```

Under `[libraries]`, beside the existing `koin-*` entries:

```toml
androidx-work-runtime = { group = "androidx.work", name = "work-runtime", version.ref = "work" }
koin-androidx-workmanager = { group = "io.insert-koin", name = "koin-androidx-workmanager", version.ref = "koin" }
```

- [ ] **Step 2: Add the dependencies**

In `app/build.gradle.kts`, in the `dependencies { }` block beside the other `koin` entries:

```kotlin
implementation(libs.androidx.work.runtime)
implementation(libs.koin.androidx.workmanager)
```

- [ ] **Step 3: Declare the foreground service, its type, and remove WorkManager's initializer**

`androidx.work:work-runtime`'s own manifest declares the base `FOREGROUND_SERVICE` permission and `androidx.work.impl.foreground.SystemForegroundService`, but **no `android:foregroundServiceType`** — WorkManager leaves the type to the app, and on targetSdk 34+ `setForeground()` against a typeless service throws `MissingForegroundServiceTypeException`. There is no configuration in which WorkManager provides long-running execution without Thor declaring the type itself.

`koin-androidx-workmanager`'s AAR manifest is **empty**: it does not remove WorkManager's `androidx.startup` initializer. `workManagerFactory()` calls `WorkManager.initialize(context, configuration)` itself, so the default initializer has to go or the two race to initialize.

`xmlns:tools` is already declared on `<manifest>` (line 3). `POST_NOTIFICATIONS` is already declared (line 49), so progress notifications cost no new runtime grant.

Add beside the other `<uses-permission>` elements:

```xml
<!-- WorkManager's own manifest declares the base FOREGROUND_SERVICE permission but leaves the
     type to the app. Without the type permission AND the type attribute below, setForeground()
     throws MissingForegroundServiceTypeException on targetSdk 34+. dataSync honestly describes
     work that moves bytes; a bulk freeze sweep would need a different type and is not on this
     seam — see the spec, section 9.4. -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

Add inside `<application>`, beside the two existing `<service>` elements:

```xml
<!-- Type overlay onto the service work-runtime declares. tools:node="merge" so this adds the
     attribute rather than replacing the library's declaration. -->
<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:node="merge" />

<!-- koin-androidx-workmanager's AAR manifest is empty, so it does NOT remove this itself.
     workManagerFactory() below calls WorkManager.initialize() directly; leaving the startup
     initializer in place means two initializers for one WorkManager. -->
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

- [ ] **Step 4: Install the Koin worker factory**

`workManagerFactory()` is `org.koin.androidx.workmanager.koin.workManagerFactory`, an extension on `KoinApplication`. It resolves `Context` **from Koin's root scope**, so it must come *after* `androidContext(...)` in the block — reordering these two is a startup crash, not a style choice.

In `app/src/main/java/com/valhalla/thor/ThorApplication.kt`, replace lines 206-209:

```kotlin
        startKoin<ThorApplication> {
            androidContext(this@ThorApplication)
            androidLogger(Logger.koinLogLevel)
            // Constructor injection for @KoinWorker workers. Reads Context out of the root scope,
            // so it has to follow androidContext(). Also performs WorkManager.initialize(), which
            // is why the manifest removes the androidx.startup initializer.
            workManagerFactory()
        }
```

and add the import:

```kotlin
import org.koin.androidx.workmanager.koin.workManagerFactory
```

- [ ] **Step 5: Build**

Run (through `ctx_execute`, `language: "shell"`):

```
./gradlew :app:assembleFossDebug
```

Expected: success. Koin's `strictSafety` is what proves the graph resolved; a failure here names the missing binding.

- [ ] **Step 6: Verify the merged manifest, not the source**

The source manifest is not what ships — manifest merger decides. Assert against the built APK:

```
$ANDROID_HOME/build-tools/*/aapt2 dump xmltree --file AndroidManifest.xml \
  app/build/outputs/apk/foss/debug/app-foss-debug.apk | grep -i -A2 "foregroundServiceType\|WorkManagerInitializer\|FOREGROUND_SERVICE"
```

Expected, all three:
1. `android.permission.FOREGROUND_SERVICE_DATA_SYNC` present.
2. `SystemForegroundService` carries `foregroundServiceType=0x1` (`dataSync`).
3. **No** `androidx.work.WorkManagerInitializer` meta-data anywhere.

If (3) still appears, the `tools:node="remove"` did not match — check that the `<provider>` `android:authorities` uses `${applicationId}` (the debug build appends `.debug`, so a hardcoded authority silently fails to match).

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml \
  app/src/main/java/com/valhalla/thor/ThorApplication.kt
git commit -m "feat(backup): WorkManager + Koin worker factory + dataSync foreground service"
```

---

### Task 2: The archive format — `DataClass`, `DataClassSize`, and the header

Pure Kotlin in `domain/model`, no Android types. This is the file every later task reads its names from, so it comes first.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/AppDataArchive.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/AppDataArchiveTest.kt`

**Interfaces:**
- Consumes: `ObbProbe` (`domain/model/ObbProbe.kt`) for the bundle's tri-state.
- Produces: `DataClass`, `DataClassSize`, `SizeLabelKind`, `ArchiveHeader`, `ArchiveBundleInfo`, `ArchiveKdf`, `ArchiveMember`, `ArchiveSkip`, `ArchiveHeader.encode()`, `ArchiveHeader.Companion.decode(String)`, `ARCHIVE_SCHEMA_VERSION`, `THORBAK_HEADER_ENTRY`, `THORBAK_BUNDLE_ENTRY`, `THORBAK_EXTENSION`, `THORBAK_MIME`, `thorbakFileName(pkg, versionCode)`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/valhalla/thor/domain/model/AppDataArchiveTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The format is the one part of this feature a *future* Thor has to agree with, so the tests here
 * are about what a foreign reader sees: the schema version is on the wire even at its default, an
 * unknown field does not kill a v1 reader, and `Undetermined` is never a number.
 */
class AppDataArchiveTest {

    private fun header() = ArchiveHeader(
        createdAt = 1_770_000_000_000L,
        thorVersionCode = 1950,
        packageName = "com.example.game",
        versionCode = 12L,
        versionName = "1.2",
        userId = 0,
        signerSha256 = "AB".repeat(32),
        appBundle = ArchiveBundleInfo(
            fileName = THORBAK_BUNDLE_ENTRY,
            bytes = 4096L,
            // The lowercase ids `captureName()` produces (Task 15), matching how `DataClass.id` and
            // `ArchiveCompression.id` are spelled in this same format.
            obbCapture = "present",
            obbCount = 2,
        ),
        kdf = ArchiveKdf(iterations = 210_000, salt = "c2FsdA=="),
        verifier = "dmVyaWZpZXI=",
        members = listOf(
            ArchiveMember(
                dataClass = DataClass.CE.id,
                fileName = "ce.tar.gz.enc",
                nonce = "bm9uY2U=",
                plainBytes = 2048L,
                chunkCount = 1,
                compression = ArchiveCompression.GZIP.id,
            )
        ),
    )

    @Test
    fun `schemaVersion is written even at its default`() {
        // The one field a foreign reader must see to know how to parse the rest. BackupIndex sets
        // encodeDefaults for exactly this reason.
        assertTrue(header().encode().contains("\"schemaVersion\": $ARCHIVE_SCHEMA_VERSION"))
    }

    @Test
    fun `a v1 reader survives a v2 document carrying unknown fields`() {
        val v2 = header().encode().replaceFirst(
            "\"schemaVersion\": $ARCHIVE_SCHEMA_VERSION,",
            "\"schemaVersion\": 2,\n  \"cloudDestination\": \"s3://nope\","
        )

        val decoded = ArchiveHeader.decode(v2)

        assertEquals(2, decoded.schemaVersion)
        assertEquals("com.example.game", decoded.packageName)
    }

    @Test
    fun `a round trip preserves every field`() {
        assertEquals(header(), ArchiveHeader.decode(header().encode()))
    }

    @Test
    fun `members are looked up by the header's own file name, never by guessing`() {
        val decoded = ArchiveHeader.decode(header().encode())

        assertNotNull(decoded.member(DataClass.CE))
        assertEquals("ce.tar.gz.enc", decoded.member(DataClass.CE)!!.fileName)
        assertEquals(null, decoded.member(DataClass.EXTERNAL_MEDIA))
    }

    @Test
    fun `an uncompressed member is named for what it actually is`() {
        // The name is derived, not fixed, so a member whose gzip attempt failed is not called
        // `.tar.gz.enc`. Readers use members[].fileName; this only keeps the name honest.
        assertEquals("ce.tar.gz.enc", DataClass.CE.memberName(compressed = true))
        assertEquals("ce.tar.enc", DataClass.CE.memberName(compressed = false))
    }

    @Test
    fun `Undetermined never renders as a size`() {
        // A size we could not measure, shown as `0 B`, is how a user deselects data they actually
        // have. Same discipline as ObbProbe.Undetermined.
        assertEquals(SizeLabelKind.Unknown, DataClassSize.Undetermined.labelKind())
        assertEquals(SizeLabelKind.Empty, DataClassSize.Empty.labelKind())
        assertEquals(SizeLabelKind.Bytes(4096L), DataClassSize.Known(4096L).labelKind())
    }

    @Test
    fun `the container name identifies the app and the version it came from`() {
        assertEquals("com.example.game-12.thorbak", thorbakFileName("com.example.game", 12L))
    }

    @Test
    fun `every data class has a distinct id and a distinct member name`() {
        val ids = DataClass.entries.map { it.id }
        val names = DataClass.entries.map { it.memberName(compressed = true) }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(names.size, names.toSet().size)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.AppDataArchiveTest"
```

Expected: compilation failure — `ArchiveHeader` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/valhalla/thor/domain/model/AppDataArchive.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Schema version of `thorbak.json`. Bumped only for a change a v1 reader could misread. */
const val ARCHIVE_SCHEMA_VERSION = 1

const val THORBAK_EXTENSION = "thorbak"
const val THORBAK_MIME = "application/octet-stream"

/** The header entry, written **last** — chunk counts are unknown until members exist. */
const val THORBAK_HEADER_ENTRY = "thorbak.json"

/** The bundle entry: always an `.xapk`, even for a single-APK app, so restore has one install path. */
const val THORBAK_BUNDLE_ENTRY = "app.xapk"

/** `<pkg>-<versionCode>.thorbak`. */
fun thorbakFileName(packageName: String, versionCode: Long): String =
    "$packageName-$versionCode.$THORBAK_EXTENSION"

/**
 * The four storage classes an app owns.
 *
 * `Android/obb/<pkg>` is deliberately **not** a fifth: it rides inside [THORBAK_BUNDLE_ENTRY], so
 * there is no second OBB path to write, test and keep in sync with the one PR #376 verified on
 * hardware.
 */
enum class DataClass(val id: String) {
    /** `/data/user/<userId>/<pkg>` — credential-encrypted; the bulk of what users care about. */
    CE("ce"),

    /**
     * `/data/user_de/<userId>/<pkg>` — device-encrypted. Not exotic: PMS creates a `user_de`
     * package directory for *every* app, and that entry spent its whole life missing from
     * `PerUserCommands`' cache list.
     */
    DE("de"),

    /** `<externalStorageDir>/Android/data/<pkg>`. */
    EXTERNAL_DATA("ext-data"),

    /** `<externalStorageDir>/Android/media/<pkg>` — user-visible content. */
    EXTERNAL_MEDIA("ext-media");

    /**
     * The member's entry name inside the container.
     *
     * Derived from [compressed] rather than fixed, so a class whose `tar -czf` failed and fell back
     * to `tar -cf` is not stored under a name claiming gzip. Readers resolve members through
     * [ArchiveHeader.member], which carries the name that was actually written.
     */
    fun memberName(compressed: Boolean): String =
        if (compressed) "$id.tar.gz.enc" else "$id.tar.enc"

    /**
     * True when `cache`, `code_cache` and `no_backup` are dropped from this class.
     *
     * [EXTERNAL_MEDIA] excludes nothing: it is user-visible content, and a directory a user can see
     * in a file manager is not Thor's to decide against.
     */
    val excludesVolatileDirs: Boolean get() = this != EXTERNAL_MEDIA
}

/**
 * How big a class is, as a **tri-state**.
 *
 * [Undetermined] exists because a measurement that failed is not a measurement of zero. Rendering it
 * as `0 B` is how a user deselects data they actually have — the same trap [ObbProbe.Undetermined]
 * exists to close.
 */
sealed interface DataClassSize {
    data class Known(val bytes: Long) : DataClassSize
    data object Empty : DataClassSize
    data object Undetermined : DataClassSize
}

/** What the UI is allowed to render for a [DataClassSize]. Never a number for `Undetermined`. */
sealed interface SizeLabelKind {
    data class Bytes(val value: Long) : SizeLabelKind
    data object Empty : SizeLabelKind
    data object Unknown : SizeLabelKind
}

fun DataClassSize.labelKind(): SizeLabelKind = when (this) {
    is DataClassSize.Known -> SizeLabelKind.Bytes(bytes)
    DataClassSize.Empty -> SizeLabelKind.Empty
    DataClassSize.Undetermined -> SizeLabelKind.Unknown
}

/** Which `tar` produced a member. Recorded because the gzip attempt is allowed to fail. */
enum class ArchiveCompression(val id: String) {
    GZIP("gzip"),
    NONE("none");

    companion object {
        fun fromId(id: String): ArchiveCompression = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * `thorbak.json`.
 *
 * Conventions are [BackupIndex]'s, and for its reasons: the reader is deliberately assumed **not**
 * to be Thor. `encodeDefaults` so [schemaVersion] is on the wire at its default,
 * `ignoreUnknownKeys` so a v1 reader survives a v2 document, `prettyPrint` because a person may
 * open it, and flat entries carrying a `dataClass` string rather than a sealed hierarchy so a
 * foreign reader need not learn Kotlin's discriminator convention.
 */
@Serializable
data class ArchiveHeader(
    val schemaVersion: Int = ARCHIVE_SCHEMA_VERSION,
    /** Epoch millis at which the run finished writing its members. */
    val createdAt: Long,
    /** The Thor build that produced this archive, for diagnosing one a later Thor rejects. */
    val thorVersionCode: Int,
    val packageName: String,
    val versionCode: Long,
    val versionName: String? = null,
    /** The Android multi-user id the data was read from. Not a Linux uid — see the plan's glossary. */
    val userId: Int,
    /**
     * SHA-256 of the app's first signing certificate, uppercase hex.
     *
     * **Load-bearing.** Without it, restoring into a same-named but differently-signed package is a
     * data-exfiltration primitive: sideload a fake `com.whatsapp`, restore, read everything.
     */
    val signerSha256: String,
    val appBundle: ArchiveBundleInfo? = null,
    val kdf: ArchiveKdf,
    /** `HMAC-SHA256(key, "thor-data-archive-v1")` truncated to 16 bytes, Base64. */
    val verifier: String,
    val members: List<ArchiveMember> = emptyList(),
    val skippedEntries: List<ArchiveSkip> = emptyList(),
    /** Non-fatal notes — a `tar` exit of 1, an `externalCacheDir` fallback. */
    val warnings: List<String> = emptyList(),
) {
    fun encode(): String = json.encodeToString(this)

    fun member(dataClass: DataClass): ArchiveMember? =
        members.firstOrNull { it.dataClass == dataClass.id }

    /** The classes this archive actually holds, in [DataClass] order. */
    fun heldClasses(): List<DataClass> = DataClass.entries.filter { member(it) != null }

    companion object {
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun decode(text: String): ArchiveHeader = json.decodeFromString<ArchiveHeader>(text)
    }
}

/**
 * The `.xapk` inside the container.
 *
 * [obbCapture] is [ObbProbe]'s tri-state name, not a boolean: OBB that could not be read is
 * recorded as `Undetermined`, never as "none", so restore never implies it holds game data it does
 * not. Same rule as #376.
 */
@Serializable
data class ArchiveBundleInfo(
    val fileName: String = THORBAK_BUNDLE_ENTRY,
    val bytes: Long,
    val obbCapture: String,
    val obbCount: Int,
)

@Serializable
data class ArchiveKdf(
    val algorithm: String = "PBKDF2WithHmacSHA256",
    val iterations: Int,
    /** Base64, 16 bytes, generated fresh per archive so one reused passphrase is not one key. */
    val salt: String,
)

@Serializable
data class ArchiveMember(
    /** [DataClass.id]. A string, not an enum, so a v2 class name does not break a v1 reader. */
    val dataClass: String,
    val fileName: String,
    /** Base64, 8 bytes. The IV is this nonce followed by a 4-byte big-endian chunk index. */
    val nonce: String,
    val plainBytes: Long,
    /** How many chunks the reader must see. A stream that ends early is refused. */
    val chunkCount: Int,
    val compression: String,
)

/** An entry Thor refused to pack, and why. Recorded rather than silently dropped. */
@Serializable
data class ArchiveSkip(
    val dataClass: String,
    val name: String,
    val reason: String,
)
```

- [ ] **Step 4: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.AppDataArchiveTest"
```

Expected: PASS. Read the count from `app/build/test-results/testFossDebugUnitTest/*.xml`, not the log line — 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/AppDataArchive.kt \
  app/src/test/java/com/valhalla/thor/domain/model/AppDataArchiveTest.kt
git commit -m "feat(backup): thorbak header, DataClass and the DataClassSize tri-state"
```

---

### Task 3: The shell surface, backup half

Every string that reaches a privileged shell, as pure functions returning `String?` where null means *refused* — the shape `obbPlaceCommand` and `PerUserCommands` already use. Path quoting, symlink guards and package-name validation live here and nowhere else. Read spec §7.2 first.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/AppDataCommands.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/AppDataCommandsTest.kt`

**Interfaces:**
- Consumes: `DataClass`, `DataClassSize`, `ArchiveSkip` (Task 2).
- Produces: `THOR_OK`, `THOR_ABSENT`, `isQuotableAbsolutePath`, `isUsablePackageName` (local copy), `dataClassRoot`, `capabilityProbeCommand`, `parseCapabilityProbe`, `classSizeCommand`, `parseClassSize`, `listClassEntriesCommand`, `ClassEntries`, `filterBackupEntries`, `tarCreateCommand`, `chownFileCommand`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/valhalla/thor/domain/model/AppDataCommandsTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privileged shell surface of app-data backup, kept pure so it can be checked without a device.
 *
 * The same reasoning as `ObbPlacementTest`: none of this is validated by the thing that runs it, so
 * each hostile input gets its own named test rather than being trusted to have been refused earlier
 * in the call chain.
 */
class AppDataCommandsTest {

    private val pkg = "com.example.game"
    private val ext = "/storage/emulated/0"

    @Test
    fun `each class names the platform's own directory for it`() {
        assertEquals("/data/user/0/$pkg", dataClassRoot(DataClass.CE, pkg, 0, ext))
        assertEquals("/data/user_de/0/$pkg", dataClassRoot(DataClass.DE, pkg, 0, ext))
        assertEquals("$ext/Android/data/$pkg", dataClassRoot(DataClass.EXTERNAL_DATA, pkg, 0, ext))
        assertEquals("$ext/Android/media/$pkg", dataClassRoot(DataClass.EXTERNAL_MEDIA, pkg, 0, ext))
    }

    @Test
    fun `a secondary user's data is read from that user's directory`() {
        // userId is the Android multi-user id, and it appears in the path. It is NOT the Linux uid
        // that chown takes — confusing the two is the bug this test exists to keep out.
        assertEquals("/data/user/10/$pkg", dataClassRoot(DataClass.CE, pkg, 10, ext))
    }

    @Test
    fun `an unusable package name yields no root at all`() {
        assertNull(dataClassRoot(DataClass.CE, "com.example.game; rm -rf /", 0, ext))
        assertNull(dataClassRoot(DataClass.CE, "../../data/local/tmp", 0, ext))
        assertNull(dataClassRoot(DataClass.CE, "com..example", 0, ext))
        assertNull(dataClassRoot(DataClass.CE, "", 0, ext))
    }

    @Test
    fun `a negative user id yields no root`() {
        // ApplicationInfo.uid / 100000 on a package that has gone away can arrive as -1.
        assertNull(dataClassRoot(DataClass.CE, pkg, -1, ext))
    }

    @Test
    fun `an external root that is not a quotable absolute path yields no root`() {
        assertNull(dataClassRoot(DataClass.EXTERNAL_DATA, pkg, 0, "/storage/emu'lated/0"))
        assertNull(dataClassRoot(DataClass.EXTERNAL_MEDIA, pkg, 0, "storage/emulated/0"))
        assertNull(dataClassRoot(DataClass.EXTERNAL_DATA, pkg, 0, ""))
        // CE and DE never touch it, so they are unaffected by a bad external root.
        assertEquals("/data/user/0/$pkg", dataClassRoot(DataClass.CE, pkg, 0, ""))
    }

    @Test
    fun `the capability probe reads Thor's own data directory and prints a marker`() {
        assertEquals(
            "ls -1 '/data/user/0/com.valhalla.thor' >/dev/null 2>&1 && echo $THOR_OK",
            capabilityProbeCommand("com.valhalla.thor", 0)
        )
    }

    @Test
    fun `the probe is believed only on a zero exit AND its own marker`() {
        // RootSystemGateway.execute() folds a *throw* into `-1 to stackTraceToString()`, so an exit
        // code alone can be a Thor stack trace rather than a shell verdict; and a gateway that
        // returns 0 with no output has not proved anything.
        assertTrue(parseCapabilityProbe(0, THOR_OK))
        assertTrue(parseCapabilityProbe(0, "$THOR_OK\n"))
        assertEquals(false, parseCapabilityProbe(0, ""))
        assertEquals(false, parseCapabilityProbe(0, null))
        assertEquals(false, parseCapabilityProbe(1, THOR_OK))
        assertEquals(false, parseCapabilityProbe(-1, "java.lang.SecurityException"))
    }

    @Test
    fun `the size probe tests for the directory before measuring it`() {
        // `du` on a missing path exits nonzero, which is indistinguishable from a failed probe —
        // and a legitimately absent class rendered as "size unknown" is a lie in the other
        // direction. The marker separates the two.
        val command = classSizeCommand("/data/user/0/$pkg")!!

        assertTrue(command, command.startsWith("if [ ! -d '/data/user/0/$pkg' ]"))
        assertTrue(command, command.contains("echo $THOR_ABSENT"))
        // POSIX -k. `du -b` is a GNU extension and is not safe to assume on toybox.
        assertTrue(command, command.contains("du -s -k '/data/user/0/$pkg'"))
        assertEquals(false, command.contains("-b"))
    }

    @Test
    fun `an absent class root is Empty, not Undetermined`() {
        assertEquals(DataClassSize.Empty, parseClassSize(0, THOR_ABSENT))
        // The `if` branch exits 0, but a gateway that reports otherwise must not turn the marker
        // into "unknown".
        assertEquals(DataClassSize.Empty, parseClassSize(1, "$THOR_ABSENT\n"))
    }

    @Test
    fun `a measured class is reported in bytes`() {
        assertEquals(DataClassSize.Known(2048L * 1024), parseClassSize(0, "2048\t/data/user/0/$pkg"))
        // Some shells separate with spaces rather than a tab.
        assertEquals(DataClassSize.Known(512L * 1024), parseClassSize(0, "512 /data/user/0/$pkg"))
    }

    @Test
    fun `an unreadable class is Undetermined and never zero`() {
        assertEquals(DataClassSize.Undetermined, parseClassSize(1, "du: permission denied"))
        assertEquals(DataClassSize.Undetermined, parseClassSize(0, ""))
        assertEquals(DataClassSize.Undetermined, parseClassSize(0, null))
        assertEquals(DataClassSize.Undetermined, parseClassSize(0, "not a number"))
    }

    @Test
    fun `the volatile directories are dropped from the classes that have them`() {
        val listing = "cache\ncode_cache\nno_backup\ndatabases\nshared_prefs\nfiles"

        val ce = filterBackupEntries(DataClass.CE, listing)

        assertEquals(listOf("databases", "files", "shared_prefs"), ce.kept)
        // Dropped by design, so not reported as skipped — skippedEntries is for entries Thor
        // *refused*, and three rows on every archive would bury the ones that matter.
        assertEquals(emptyList<ArchiveSkip>(), ce.skipped)
    }

    @Test
    fun `external media keeps everything the user can see`() {
        val listing = "cache\nWhatsApp Images"

        val media = filterBackupEntries(DataClass.EXTERNAL_MEDIA, listing)

        assertEquals(listOf("WhatsApp Images", "cache"), media.kept)
    }

    @Test
    fun `an entry Thor cannot quote is refused and recorded`() {
        val listing = "good\nit's bad\n-rf\nalso good"

        val entries = filterBackupEntries(DataClass.CE, listing)

        assertEquals(listOf("also good", "good"), entries.kept)
        assertEquals(2, entries.skipped.size)
        assertTrue(entries.skipped.any { it.name == "it's bad" })
        // A leading dash would be read as a tar option rather than as an operand.
        assertTrue(entries.skipped.any { it.name == "-rf" })
        assertTrue(entries.skipped.all { it.dataClass == DataClass.CE.id })
        assertTrue(entries.skipped.all { it.reason.isNotBlank() })
    }

    @Test
    fun `an absent root is distinguished from an empty one`() {
        assertTrue(filterBackupEntries(DataClass.CE, THOR_ABSENT).rootAbsent)
        assertEquals(false, filterBackupEntries(DataClass.CE, "").rootAbsent)
        // Both produce no member at all, but only one of them is worth a warning.
        assertEquals(emptyList<String>(), filterBackupEntries(DataClass.CE, "").kept)
    }

    @Test
    fun `tar names each survivor as its own quoted operand`() {
        val command = tarCreateCommand(
            root = "/data/user/0/$pkg",
            outPath = "/data/data/com.valhalla.thor/cache/backup/ce.tar.gz",
            entries = listOf("databases", "shared prefs"),
            compress = true,
        )!!

        assertEquals(
            "tar -czf '/data/data/com.valhalla.thor/cache/backup/ce.tar.gz' " +
                "-C '/data/user/0/$pkg' 'databases' 'shared prefs'",
            command
        )
    }

    @Test
    fun `the uncompressed fallback is the same command without z`() {
        // Deliberately not `tar --exclude`: that bets on toybox's option surface, where
        // enumerate-then-list is a pure List<String> -> String? function.
        val command = tarCreateCommand("/data/user/0/$pkg", "/tmp/ce.tar", listOf("files"), false)!!

        assertTrue(command, command.startsWith("tar -cf '/tmp/ce.tar' -C "))
    }

    @Test
    fun `tar refuses an empty entry list rather than writing an empty archive`() {
        assertNull(tarCreateCommand("/data/user/0/$pkg", "/tmp/ce.tar", emptyList(), true))
    }

    @Test
    fun `tar refuses an entry it cannot quote`() {
        assertNull(tarCreateCommand("/data/user/0/$pkg", "/tmp/ce.tar", listOf("it's"), true))
        assertNull(tarCreateCommand("/data/user/0/$pkg", "/tmp/ce.tar", listOf("-rf"), true))
        assertNull(tarCreateCommand("/data/user/0/$pkg", "/tmp/ce.tar", listOf(""), true))
    }

    @Test
    fun `the staged tar is handed to Thor's own uid and to nobody else`() {
        // The shell creates the file, so root owns it; Thor has to read it back. 600 because the
        // staged tar is plaintext app data.
        val command = chownFileCommand("/data/data/com.valhalla.thor/cache/backup/ce.tar.gz", 10234)!!

        assertEquals(
            "chown 10234:10234 '/data/data/com.valhalla.thor/cache/backup/ce.tar.gz' && " +
                "chmod 600 '/data/data/com.valhalla.thor/cache/backup/ce.tar.gz'",
            command
        )
    }

    @Test
    fun `a negative uid yields no chown`() {
        // ApplicationInfo.uid is -1 for a package that vanished between two calls.
        assertNull(chownFileCommand("/tmp/ce.tar", -1))
    }

    @Test
    fun `every command builder in this file refuses an unquotable path`() {
        // The sweep `PerUserCommandsTest` runs for "every builder names its user", for the property
        // that matters here. A builder added later without the quotability guard fails this test
        // rather than shipping an injection.
        val cls = Class.forName("com.valhalla.thor.domain.model.AppDataCommandsKt")
        val hostile = "/data/user/0/it's"
        val checked = mutableListOf<String>()

        for (method in cls.declaredMethods) {
            // `contains`, not `endsWith`: Kotlin may append a module suffix to an internal name.
            if (!method.name.contains("Command")) continue
            if (!Modifier.isStatic(method.modifiers)) continue
            val types = method.parameterTypes
            if (types.isEmpty() || types[0] != String::class.java) continue

            val args = ArrayList<Any?>(types.size)
            var usable = true
            types.forEachIndexed { index, type ->
                args += when {
                    index == 0 -> hostile
                    type == String::class.java -> "safe"
                    type == Int::class.javaPrimitiveType -> 0
                    type == Long::class.javaPrimitiveType -> 1L
                    type == Boolean::class.javaPrimitiveType -> true
                    type == List::class.java -> listOf("entry")
                    else -> { usable = false; null }
                }
            }
            if (!usable) continue

            method.isAccessible = true
            checked += method.name
            assertNull(method.name, method.invoke(null, *args.toTypedArray()))
        }

        // A reflective sweep that matched nothing is a green test proving nothing.
        assertTrue("only checked $checked", checked.size >= 5)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.AppDataCommandsTest"
```

Expected: compilation failure — `dataClassRoot` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/valhalla/thor/domain/model/AppDataCommands.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * Every shell string app-data backup and restore send to a privileged shell.
 *
 * Pure, and each builder returns `String?` where null means *refused* — the shape
 * `PerUserCommands.kt` and `obbPlaceCommand` already use, for the same reason: none of this is
 * validated by the thing that runs it, so it is validated at the site rather than inherited from
 * some earlier call having refused it.
 *
 * **Glossary, because two different numbers in this file are called "uid".** `userId` is the Android
 * multi-user id (0, 10, …) that appears *in a path*. `uid` is the app's Linux uid from
 * `ApplicationInfo.uid` and appears only in `chown`. They are never the same value.
 */

/** Printed on a probe's success path. */
const val THOR_OK = "THOR_OK"

/** Printed when the directory being measured or listed does not exist at all. */
const val THOR_ABSENT = "THOR_ABSENT"

/**
 * Deliberately a copy of `ObbProbeParser`'s regex rather than an import of it.
 *
 * That one is `internal` in `com.valhalla.thor.data.repository`, and a `domain/` file importing from
 * `data/` inverts the layering the module is built on. Four lines of duplication is the cheaper
 * price. The two must stay identical: a name one accepts and the other refuses is a bug in whichever
 * path is more permissive.
 */
private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")

internal fun isUsablePackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

/**
 * True when [path] can be interpolated inside single quotes in a shell command.
 *
 * Absolute because every path here names a system location and a relative one would resolve against
 * whatever directory the shell happens to be in; no `'` because that is what closes the quoting; no
 * newline because that ends the command.
 */
internal fun isQuotableAbsolutePath(path: String): Boolean =
    path.startsWith('/') && path.none { it == '\'' || it == '\n' }

/** True when [name] is safe as a quoted `tar` operand. */
private fun isQuotableEntryName(name: String): Boolean =
    name.isNotEmpty() &&
        !name.startsWith('-') &&
        name.none { it == '\'' || it == '\n' || it == '/' }

/**
 * The directory [dataClass] lives in for [packageName] under [userId], or null when any input is
 * unsafe to interpolate.
 *
 * [externalStorageDir] is only consulted by the two external classes, so a device that cannot
 * resolve it still backs up CE and DE.
 */
internal fun dataClassRoot(
    dataClass: DataClass,
    packageName: String,
    userId: Int,
    externalStorageDir: String,
): String? {
    if (!isUsablePackageName(packageName)) return null
    if (userId < 0) return null
    return when (dataClass) {
        DataClass.CE -> "/data/user/$userId/$packageName"
        DataClass.DE -> "/data/user_de/$userId/$packageName"
        DataClass.EXTERNAL_DATA ->
            if (isQuotableAbsolutePath(externalStorageDir)) {
                "$externalStorageDir/Android/data/$packageName"
            } else null

        DataClass.EXTERNAL_MEDIA ->
            if (isQuotableAbsolutePath(externalStorageDir)) {
                "$externalStorageDir/Android/media/$packageName"
            } else null
    }
}

/**
 * The capability probe: can the active channel read a private data directory at all?
 *
 * Run against **Thor's own** package, so it asks the question without touching the app being backed
 * up. Root passes; a shell-uid Shizuku fails; a root-started Shizuku passes; Dhizuku fails — all
 * without naming a privilege mode, which is why the refusal string this feeds must not say "requires
 * Root".
 */
internal fun capabilityProbeCommand(thorPackageName: String, userId: Int): String? {
    if (!isUsablePackageName(thorPackageName)) return null
    if (userId < 0) return null
    return "ls -1 '/data/user/$userId/$thorPackageName' >/dev/null 2>&1 && echo $THOR_OK"
}

/**
 * Believed only on a zero exit **and** the marker.
 *
 * `RootSystemGateway.execute()` folds a throw into `-1 to stackTraceToString()`, so an exit code on
 * its own can be Thor's stack trace rather than a shell verdict; and a gateway that returns 0 having
 * run nothing has not proved a capability.
 */
internal fun parseCapabilityProbe(exitCode: Int, output: String?): Boolean =
    exitCode == 0 && output?.contains(THOR_OK) == true

/**
 * `du` for the sizing UI, with an existence test in front of it.
 *
 * POSIX `-k`, never `-b`: `-b` is a GNU extension and is not safe to assume on toybox.
 */
internal fun classSizeCommand(root: String): String? {
    if (!isQuotableAbsolutePath(root)) return null
    return "if [ ! -d '$root' ]; then echo $THOR_ABSENT; else du -s -k '$root' 2>/dev/null; fi"
}

/**
 * The marker is tested **before** the exit code, and the exit code before the number.
 *
 * An absent root reported as [DataClassSize.Undetermined] puts "size unknown" beside a class that
 * holds nothing; an unreadable root reported as `Known(0)` is how a user deselects data they have.
 * Both directions are wrong, so the tri-state is decided in this order and nowhere else.
 */
internal fun parseClassSize(exitCode: Int, output: String?): DataClassSize {
    val text = output ?: return DataClassSize.Undetermined
    if (text.contains(THOR_ABSENT)) return DataClassSize.Empty
    if (exitCode != 0) return DataClassSize.Undetermined
    val kilobytes = text.lineSequence()
        .map { it.trim() }
        .lastOrNull { it.isNotEmpty() }
        ?.takeWhile { it.isDigit() }
        ?.toLongOrNull()
        ?: return DataClassSize.Undetermined
    return DataClassSize.Known(kilobytes * 1024)
}

/** `ls -A` the class root, with the same absent marker the size probe uses. */
internal fun listClassEntriesCommand(root: String): String? {
    if (!isQuotableAbsolutePath(root)) return null
    return "if [ ! -d '$root' ]; then echo $THOR_ABSENT; else ls -A '$root'; fi"
}

/** `cache`, `code_cache`, `no_backup` — volatile, and restoring them helps nothing. */
private val VOLATILE_DIRS = setOf("cache", "code_cache", "no_backup")

/**
 * What survived the filter, what was refused, and whether the root was there at all.
 *
 * [rootAbsent] and an empty [kept] both produce no member, but only the first is worth a warning.
 */
internal data class ClassEntries(
    val kept: List<String>,
    val skipped: List<ArchiveSkip>,
    val rootAbsent: Boolean,
)

/**
 * Turn one `ls -A` reply into the operands `tar` will be given.
 *
 * Filtering in Kotlin rather than with `tar --exclude` is deliberate: `--exclude` bets on toybox's
 * option surface, where this is a pure `String -> ClassEntries` function that a JVM test pins down.
 *
 * An excluded volatile directory is dropped **silently** — it was never going to be packed, and
 * three rows on every archive would bury the entries Thor actually refused. An entry Thor cannot
 * quote is recorded in [ClassEntries.skipped] and reaches the header, because a filename Thor
 * dropped is something the user is entitled to know about.
 */
internal fun filterBackupEntries(dataClass: DataClass, listing: String): ClassEntries {
    val lines = listing.lines().map { it.removeSuffix("\r") }
    if (lines.any { it.trim() == THOR_ABSENT }) {
        return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)
    }

    val kept = mutableListOf<String>()
    val skipped = mutableListOf<ArchiveSkip>()
    for (name in lines) {
        // Not trimmed: a trailing space is part of a real filename, and trimming it would hand tar
        // an operand that does not exist. Only a wholly blank line is dropped.
        if (name.isBlank()) continue
        if (name == "." || name == "..") continue
        if (dataClass.excludesVolatileDirs && name in VOLATILE_DIRS) continue
        if (!isQuotableEntryName(name)) {
            skipped += ArchiveSkip(
                dataClass = dataClass.id,
                name = name,
                reason = "name cannot be passed to the shell safely",
            )
            continue
        }
        kept += name
    }
    // Sorted so two runs over the same directory produce the same command, which is what makes a
    // failure reproducible.
    return ClassEntries(kept = kept.sorted(), skipped = skipped, rootAbsent = false)
}

/**
 * `tar` the survivors of [filterBackupEntries] into [outPath].
 *
 * `-C root` plus bare operands, so the archive holds paths relative to the class root and restore
 * can extract it anywhere. Refuses an empty [entries]: an empty class produces no member at all
 * rather than an empty tar the restore side would have to special-case.
 */
internal fun tarCreateCommand(
    root: String,
    outPath: String,
    entries: List<String>,
    compress: Boolean,
): String? {
    if (!isQuotableAbsolutePath(root)) return null
    if (!isQuotableAbsolutePath(outPath)) return null
    if (entries.isEmpty()) return null
    if (entries.any { !isQuotableEntryName(it) }) return null
    val flags = if (compress) "-czf" else "-cf"
    val operands = entries.joinToString(separator = " ") { "'$it'" }
    return "tar $flags '$outPath' -C '$root' $operands"
}

/**
 * Hand a shell-created file to Thor's own [uid] so Thor can read it back, and to nobody else.
 *
 * 600 rather than 644 because a staged tar is plaintext app data. Spec §7.1 stages in *internal*
 * cache for the same reason.
 */
internal fun chownFileCommand(path: String, uid: Int): String? {
    if (!isQuotableAbsolutePath(path)) return null
    if (uid < 0) return null
    return "chown $uid:$uid '$path' && chmod 600 '$path'"
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.AppDataCommandsTest"
```

Expected: PASS, 21 tests, counted from `app/build/test-results/testFossDebugUnitTest/*.xml`.

If `every command builder in this file refuses an unquotable path` reports `only checked []`, the reflective name match failed — print `cls.declaredMethods.map { it.name }` and widen the match; do not delete the assertion.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/AppDataCommands.kt \
  app/src/test/java/com/valhalla/thor/domain/model/AppDataCommandsTest.kt
git commit -m "feat(backup): pure shell surface for app-data backup"
```

---

### Task 4: `AppArchiveCipher` — key derivation, verifier, chunk framing

The unit that decides whether a corrupt archive is *detected* or *restored*. Read spec §5 in full before starting. The tamper test and the truncation test are non-negotiable: they are the two that would have caught the `CipherInputStream` trap.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/backup/AppArchiveCipher.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/AppArchiveCipherTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks — streams and bytes in and out, no Thor types.
- Produces: `AppArchiveCipher` (`@Single`), `MemberStats(plainBytes, cipherBytes, chunkCount)`, `ArchiveIntegrityException`, `KDF_ALGORITHM`, `KDF_ITERATIONS`, `KDF_SALT_BYTES`, `MEMBER_NONCE_BYTES`, `CHUNK_PLAINTEXT_BYTES`, `VERIFIER_BYTES`, and the methods `newSalt()`, `newNonce()`, `deriveKey(CharArray, ByteArray, Int)`, `verifier(SecretKey)`, `encryptMember(String, InputStream, OutputStream, SecretKey, ByteArray): MemberStats`, `decryptMember(String, InputStream, OutputStream, SecretKey, ByteArray, Int): Long`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/valhalla/thor/data/backup/AppArchiveCipherTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CipherInputStream` returns -1 instead of throwing on `AEADBadTagException`, so a tampered archive
 * decrypts to a silently short plaintext and restore writes a partial database over a real one.
 * Every test below that expects [ArchiveIntegrityException] exists because of that: the framing is
 * only worth having if the failures are loud.
 */
class AppArchiveCipherTest {

    private val cipher = AppArchiveCipher()
    private val member = "ce.tar.gz.enc"
    private val nonce = ByteArray(MEMBER_NONCE_BYTES) { it.toByte() }

    // 1,000 rather than the production 210,000: a test suite that derives real keys spends minutes
    // in PBKDF2. `the production iteration count` below is what pins the shipped value.
    private fun key(passphrase: String = "correct horse"): SecretKey =
        cipher.deriveKey(passphrase.toCharArray(), ByteArray(KDF_SALT_BYTES) { 7 }, iterations = 1_000)

    private fun encrypt(plain: ByteArray, name: String = member, k: SecretKey = key()): Pair<ByteArray, MemberStats> {
        val out = ByteArrayOutputStream()
        val stats = cipher.encryptMember(name, ByteArrayInputStream(plain), out, k, nonce)
        return out.toByteArray() to stats
    }

    private fun decrypt(
        bytes: ByteArray,
        chunkCount: Int,
        name: String = member,
        k: SecretKey = key(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        cipher.decryptMember(name, ByteArrayInputStream(bytes), out, k, nonce, chunkCount)
        return out.toByteArray()
    }

    @Test
    fun `a multi-chunk member round trips byte for byte`() {
        val plain = ByteArray(CHUNK_PLAINTEXT_BYTES * 2 + 12_345) { (it % 251).toByte() }

        val (bytes, stats) = encrypt(plain)

        assertEquals(3, stats.chunkCount)
        assertEquals(plain.size.toLong(), stats.plainBytes)
        assertArrayEquals(plain, decrypt(bytes, stats.chunkCount))
    }

    @Test
    fun `a payload of exactly one chunk is one chunk`() {
        val (_, stats) = encrypt(ByteArray(CHUNK_PLAINTEXT_BYTES))

        assertEquals(1, stats.chunkCount)
    }

    @Test
    fun `one byte past a chunk boundary is two chunks`() {
        val (bytes, stats) = encrypt(ByteArray(CHUNK_PLAINTEXT_BYTES + 1) { 9 })

        assertEquals(2, stats.chunkCount)
        assertEquals(CHUNK_PLAINTEXT_BYTES + 1, decrypt(bytes, stats.chunkCount).size)
    }

    @Test
    fun `one byte short of a chunk boundary is one chunk`() {
        val (_, stats) = encrypt(ByteArray(CHUNK_PLAINTEXT_BYTES - 1))

        assertEquals(1, stats.chunkCount)
    }

    @Test
    fun `an empty member is one authenticated empty chunk, never zero chunks`() {
        // Zero chunks would make `chunkCount` unable to distinguish "nothing was written" from
        // "everything was truncated", which is the check the whole format leans on.
        val (bytes, stats) = encrypt(ByteArray(0))

        assertEquals(1, stats.chunkCount)
        assertEquals(0L, stats.plainBytes)
        assertEquals(0, decrypt(bytes, stats.chunkCount).size)
    }

    @Test
    fun `a flipped ciphertext byte is detected`() {
        val (bytes, stats) = encrypt(ByteArray(4096) { 3 })
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()

        assertThrows(ArchiveIntegrityException::class.java) { decrypt(bytes, stats.chunkCount) }
    }

    @Test
    fun `a stream that ends a chunk early is detected`() {
        val (bytes, stats) = encrypt(ByteArray(CHUNK_PLAINTEXT_BYTES + 500) { 1 })

        // Truncation at a chunk boundary: the last frame is gone entirely, and every frame that
        // remains authenticates. Only `chunkCount` catches this — AAD alone does not.
        val cut = bytes.copyOf(bytes.size - 600)

        assertThrows(ArchiveIntegrityException::class.java) { decrypt(cut, stats.chunkCount) }
    }

    @Test
    fun `a stream truncated inside a frame is detected`() {
        val (bytes, stats) = encrypt(ByteArray(2048) { 5 })

        assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes.copyOf(bytes.size - 4), stats.chunkCount)
        }
    }

    @Test
    fun `a stream carrying more chunks than the header declares is detected`() {
        val (bytes, stats) = encrypt(ByteArray(64) { 2 })

        assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes + bytes, stats.chunkCount)
        }
    }

    @Test
    fun `a chunk replayed at the wrong index is detected`() {
        // The IV's chunk index and the AAD's chunk index both change, so a frame moved from one
        // position to another fails to authenticate at its new position.
        val (bytes, stats) = encrypt(ByteArray(CHUNK_PLAINTEXT_BYTES * 2) { 4 })
        val frameLength = 4 + CHUNK_PLAINTEXT_BYTES + 16
        val first = bytes.copyOfRange(0, frameLength)
        val second = bytes.copyOfRange(frameLength, frameLength * 2)
        val swapped = second + first + bytes.copyOfRange(frameLength * 2, bytes.size)

        assertThrows(ArchiveIntegrityException::class.java) { decrypt(swapped, stats.chunkCount) }
    }

    @Test
    fun `a member decrypted under another member's name is detected`() {
        // The AAD binds the entry name, so `de.tar.gz.enc` cannot be presented as `ce.tar.gz.enc` —
        // a swap that would otherwise restore device-encrypted data into the CE directory.
        val (bytes, stats) = encrypt(ByteArray(128) { 8 })

        assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes, stats.chunkCount, name = "de.tar.gz.enc")
        }
    }

    @Test
    fun `a wrong passphrase is detected`() {
        val (bytes, stats) = encrypt(ByteArray(128) { 8 })

        assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes, stats.chunkCount, k = key("wrong horse"))
        }
    }

    @Test
    fun `a declared chunk count of zero is refused`() {
        val (bytes, _) = encrypt(ByteArray(16))

        assertThrows(ArchiveIntegrityException::class.java) { decrypt(bytes, 0) }
    }

    @Test
    fun `the verifier rejects a wrong passphrase before a byte is streamed`() {
        val right = cipher.verifier(key("correct horse"))
        val wrong = cipher.verifier(key("wrong horse"))

        assertEquals(VERIFIER_BYTES, right.size)
        assertNotEquals(right.toList(), wrong.toList())
        // Stable: the same passphrase and salt must verify against an archive made yesterday.
        assertArrayEquals(right, cipher.verifier(key("correct horse")))
    }

    @Test
    fun `the same passphrase under a different salt yields a different key`() {
        // One reused passphrase must not mean one reused key, which is the whole point of a
        // per-archive salt.
        val a = cipher.deriveKey("pass".toCharArray(), ByteArray(KDF_SALT_BYTES) { 1 }, 1_000)
        val b = cipher.deriveKey("pass".toCharArray(), ByteArray(KDF_SALT_BYTES) { 2 }, 1_000)

        assertNotEquals(a.encoded.toList(), b.encoded.toList())
    }

    @Test
    fun `every salt and nonce is fresh`() {
        assertNotEquals(cipher.newSalt().toList(), cipher.newSalt().toList())
        assertNotEquals(cipher.newNonce().toList(), cipher.newNonce().toList())
        assertEquals(KDF_SALT_BYTES, cipher.newSalt().size)
        assertEquals(MEMBER_NONCE_BYTES, cipher.newNonce().size)
    }

    @Test
    fun `the shipped parameters are the ones the spec fixed`() {
        // The tests above run at 1,000 iterations for speed; this is what pins production.
        assertEquals(210_000, KDF_ITERATIONS)
        assertEquals("PBKDF2WithHmacSHA256", KDF_ALGORITHM)
        assertEquals(16, KDF_SALT_BYTES)
        assertEquals(8, MEMBER_NONCE_BYTES)
        assertEquals(1024 * 1024, CHUNK_PLAINTEXT_BYTES)
        assertEquals(16, VERIFIER_BYTES)
        // 256-bit key.
        assertTrue(key().encoded.size == 32)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.backup.AppArchiveCipherTest"
```

Expected: compilation failure — `AppArchiveCipher` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/valhalla/thor/data/backup/AppArchiveCipher.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.koin.core.annotation.Single

const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"

/** OWASP's 2023 floor for PBKDF2-HMAC-SHA256. Pinned by a test; do not lower it for test speed. */
const val KDF_ITERATIONS = 210_000

private const val KDF_KEY_BITS = 256

/** Fresh per archive, so one reused passphrase is not one reused key. */
const val KDF_SALT_BYTES = 16

/** Fresh per member. The GCM IV is this followed by a 4-byte big-endian chunk index. */
const val MEMBER_NONCE_BYTES = 8

const val CHUNK_PLAINTEXT_BYTES = 1024 * 1024

const val VERIFIER_BYTES = 16

private const val VERIFIER_MESSAGE = "thor-data-archive-v1"
private const val GCM_TAG_BITS = 128
private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
private const val FRAME_LENGTH_BYTES = 4

/**
 * The archive is not what it claims to be: a tag that does not verify, a stream that ends before its
 * declared chunk count, a frame that does not belong where it was found.
 *
 * An `IOException` so a caller that already handles I/O failure cannot accidentally not handle this
 * one — but always reported to the user as a refusal, never as a partial success.
 */
class ArchiveIntegrityException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** What one encrypted member turned out to be, for the header. */
data class MemberStats(
    val plainBytes: Long,
    val cipherBytes: Long,
    val chunkCount: Int,
)

/**
 * AES-256-GCM in 1 MiB frames.
 *
 * **`CipherInputStream` is not used and must never be.** It swallows `AEADBadTagException` and
 * returns -1, so a truncated or tampered member decrypts to a silently short plaintext — and restore
 * writes that over the user's real data. Every chunk here is its own `doFinal`, and every failure
 * throws [ArchiveIntegrityException].
 *
 * Framing: `4-byte big-endian ciphertext length ‖ ciphertext‖tag`, repeated `chunkCount` times.
 * - The IV is `nonce ‖ big-endian chunk index`, unique within a member, across members (fresh nonce)
 *   and across archives (fresh salt, so a fresh key).
 * - The AAD binds the member's entry name, the chunk index, and whether the chunk is the last one —
 *   so a frame cannot be moved, and a member cannot be presented as a different member.
 * - `chunkCount` comes from the header and closes truncation *at a chunk boundary*, which the AAD
 *   alone does not: a stream cut on a frame edge authenticates perfectly and is simply short.
 */
@Single
class AppArchiveCipher {

    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(KDF_SALT_BYTES).also(random::nextBytes)

    fun newNonce(): ByteArray = ByteArray(MEMBER_NONCE_BYTES).also(random::nextBytes)

    /**
     * @param iterations exposed only so tests can derive keys without spending minutes in PBKDF2.
     *   Production callers pass nothing and get [KDF_ITERATIONS].
     */
    fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = KDF_ITERATIONS,
    ): SecretKey {
        require(salt.size == KDF_SALT_BYTES) { "salt must be $KDF_SALT_BYTES bytes" }
        require(iterations > 0) { "iterations must be positive" }
        val spec = PBEKeySpec(passphrase, salt, iterations, KDF_KEY_BITS)
        try {
            val derived = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
            return SecretKeySpec(derived, "AES")
        } finally {
            // The spec holds a copy of the passphrase; the caller's CharArray is the caller's to
            // clear.
            spec.clearPassword()
        }
    }

    /**
     * `HMAC-SHA256(key, "thor-data-archive-v1")` truncated to [VERIFIER_BYTES].
     *
     * Lets a wrong passphrase be rejected after one key derivation, before a byte is streamed. It
     * leaks nothing the ciphertext does not already leak to an offline attacker.
     */
    fun verifier(key: SecretKey): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.encoded, "HmacSHA256"))
        return mac.doFinal(VERIFIER_MESSAGE.toByteArray(Charsets.UTF_8)).copyOf(VERIFIER_BYTES)
    }

    /**
     * Encrypt [plaintext] into [ciphertext], returning what the header must record.
     *
     * Neither stream is closed here: the output is one entry of a zip the caller keeps open for the
     * next member.
     */
    fun encryptMember(
        memberName: String,
        plaintext: InputStream,
        ciphertext: OutputStream,
        key: SecretKey,
        nonce: ByteArray,
    ): MemberStats {
        require(nonce.size == MEMBER_NONCE_BYTES) { "nonce must be $MEMBER_NONCE_BYTES bytes" }

        // Two buffers, swapped: the final-chunk flag is part of the AAD, so a chunk cannot be
        // written until it is known whether anything follows it. A short read already proves EOF, so
        // the lookahead only happens on a full one.
        val first = ByteArray(CHUNK_PLAINTEXT_BYTES)
        val second = ByteArray(CHUNK_PLAINTEXT_BYTES)
        var current = first
        var currentLength = fill(plaintext, current)
        var index = 0
        var plainBytes = 0L
        var cipherBytes = 0L

        while (true) {
            val next = if (current === first) second else first
            val nextLength =
                if (currentLength < CHUNK_PLAINTEXT_BYTES) 0 else fill(plaintext, next)
            val isFinal = nextLength == 0

            val frame = cipherFor(Cipher.ENCRYPT_MODE, key, nonce, index).run {
                updateAAD(aad(memberName, index, isFinal))
                doFinal(current, 0, currentLength)
            }
            writeFrame(ciphertext, frame)

            plainBytes += currentLength
            cipherBytes += FRAME_LENGTH_BYTES + frame.size
            index++
            if (isFinal) break
            current = next
            currentLength = nextLength
        }

        return MemberStats(plainBytes = plainBytes, cipherBytes = cipherBytes, chunkCount = index)
    }

    /**
     * Decrypt exactly [chunkCount] frames from [ciphertext] into [plaintext], returning the byte
     * count written.
     *
     * Throws [ArchiveIntegrityException] on anything that is not exactly that: a bad tag, a frame
     * that ends early, a declared length outside the format's bounds, or a byte after the last
     * frame.
     */
    fun decryptMember(
        memberName: String,
        ciphertext: InputStream,
        plaintext: OutputStream,
        key: SecretKey,
        nonce: ByteArray,
        chunkCount: Int,
    ): Long {
        require(nonce.size == MEMBER_NONCE_BYTES) { "nonce must be $MEMBER_NONCE_BYTES bytes" }
        if (chunkCount <= 0) {
            throw ArchiveIntegrityException("$memberName declares $chunkCount chunks")
        }

        var written = 0L
        for (index in 0 until chunkCount) {
            val isFinal = index == chunkCount - 1
            val length = frameLength(ciphertext, memberName, index)
            if (length < GCM_TAG_BYTES || length > CHUNK_PLAINTEXT_BYTES + GCM_TAG_BYTES) {
                throw ArchiveIntegrityException("$memberName chunk $index declares $length bytes")
            }
            val frame = ByteArray(length)
            if (fill(ciphertext, frame) != length) {
                throw ArchiveIntegrityException("$memberName chunk $index ended early")
            }
            val chunk = try {
                cipherFor(Cipher.DECRYPT_MODE, key, nonce, index).run {
                    updateAAD(aad(memberName, index, isFinal))
                    doFinal(frame)
                }
            } catch (e: GeneralSecurityException) {
                // AEADBadTagException arrives here — a wrong passphrase, a flipped byte, a frame
                // moved to another index, or a member presented under another member's name.
                throw ArchiveIntegrityException(
                    "$memberName chunk $index failed authentication",
                    e,
                )
            }
            plaintext.write(chunk)
            written += chunk.size
        }

        if (ciphertext.read() != -1) {
            throw ArchiveIntegrityException(
                "$memberName carries more data than its $chunkCount chunks"
            )
        }
        return written
    }

    private fun cipherFor(mode: Int, key: SecretKey, nonce: ByteArray, index: Int): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(GCM_TAG_BITS, iv(nonce, index)))
        }

    private fun iv(nonce: ByteArray, index: Int): ByteArray {
        val iv = ByteArray(MEMBER_NONCE_BYTES + 4)
        nonce.copyInto(iv)
        iv[MEMBER_NONCE_BYTES] = (index ushr 24).toByte()
        iv[MEMBER_NONCE_BYTES + 1] = (index ushr 16).toByte()
        iv[MEMBER_NONCE_BYTES + 2] = (index ushr 8).toByte()
        iv[MEMBER_NONCE_BYTES + 3] = index.toByte()
        return iv
    }

    private fun aad(memberName: String, index: Int, isFinal: Boolean): ByteArray =
        "$memberName|$index|${if (isFinal) 1 else 0}".toByteArray(Charsets.UTF_8)

    private fun writeFrame(out: OutputStream, frame: ByteArray) {
        out.write((frame.size ushr 24) and 0xFF)
        out.write((frame.size ushr 16) and 0xFF)
        out.write((frame.size ushr 8) and 0xFF)
        out.write(frame.size and 0xFF)
        out.write(frame)
    }

    private fun frameLength(input: InputStream, memberName: String, index: Int): Int {
        val header = ByteArray(FRAME_LENGTH_BYTES)
        if (fill(input, header) != FRAME_LENGTH_BYTES) {
            throw ArchiveIntegrityException("$memberName ended before chunk $index")
        }
        return ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
    }

    /** Read until [into] is full or the stream ends; returns how many bytes landed. */
    private fun fill(input: InputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val read = input.read(into, total, into.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.backup.AppArchiveCipherTest"
```

Expected: PASS, 17 tests, counted from the XML.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/backup/AppArchiveCipher.kt \
  app/src/test/java/com/valhalla/thor/data/backup/AppArchiveCipherTest.kt
git commit -m "feat(backup): chunk-framed AES-256-GCM codec with loud integrity failures"
```

---

### Task 5: `PassphraseVault` — a cache that is never the source of truth

Spec §5.4. The property that must hold: if the Keystore key is gone or invalidated, Thor **re-prompts**; it never fails to read an existing archive. The failure mode of a convenience layer is a prompt, not data loss.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/backup/PassphraseVault.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/PassphraseVaultTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `VaultKeyProvider` (interface: `wrap(ByteArray): ByteArray`, `unwrap(ByteArray): ByteArray`), `PassphraseVaultStore` (interface: `read(): String?`, `write(String?)`), `PassphraseVault` (`@Single`) with `suspend fun remember(CharArray)`, `suspend fun recall(): CharArray?`, `suspend fun forget()`, `val isRemembered: Flow<Boolean>`, `AndroidKeystoreVaultKeyProvider` (`@Single`), `DataStorePassphraseVaultStore` (`@Single`).
  Also produces `const val MIN_PASSPHRASE_LENGTH = 8`, top-level in the same file — Task 16 and Task 18 both import it, which is the point.
  Two later tasks widen these deliberately, each once a consumer exists; do not pre-empt either here. **Task 16** adds `val isSet: Flow<Boolean>` to `PassphraseVaultStore` (replacing the `as?` downcast Step 3 notes below). **Task 18** changes `remember` to return `Boolean`, because a settings screen that says "saved" must not say it when the wrap failed.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/valhalla/thor/data/backup/PassphraseVaultTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import java.security.GeneralSecurityException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The vault is a convenience cache over a passphrase the user could always type instead. Its whole
 * contract is that losing it costs a prompt — never an archive.
 *
 * `AndroidKeystoreVaultKeyProvider` is not testable on the JVM (it needs the `AndroidKeyStore`
 * provider), which is exactly why the Keystore sits behind [VaultKeyProvider]: the contract is
 * testable with a provider that throws the way a wiped key does.
 */
class PassphraseVaultTest {

    private class FakeStore(var blob: String? = null) : PassphraseVaultStore {
        override suspend fun read(): String? = blob
        override suspend fun write(value: String?) { blob = value }
    }

    /** Reversible "encryption" — the vault's logic is what is under test, not AES. */
    private class FakeProvider(var alive: Boolean = true) : VaultKeyProvider {
        override fun wrap(plaintext: ByteArray): ByteArray =
            if (alive) plaintext.map { (it + 1).toByte() }.toByteArray()
            else throw GeneralSecurityException("key gone")

        override fun unwrap(blob: ByteArray): ByteArray =
            if (alive) blob.map { (it - 1).toByte() }.toByteArray()
            else throw GeneralSecurityException("key gone")
    }

    @Test
    fun `a remembered passphrase comes back`() = runTest {
        val store = FakeStore()
        val vault = PassphraseVault(store, FakeProvider())

        vault.remember("correct horse".toCharArray())

        assertEquals("correct horse", vault.recall()?.concatToString())
    }

    @Test
    fun `the stored blob is not the passphrase`() = runTest {
        val store = FakeStore()
        val vault = PassphraseVault(store, FakeProvider())

        vault.remember("correct horse".toCharArray())

        assertEquals(false, store.blob!!.contains("correct horse"))
    }

    @Test
    fun `a vault whose key was wiped yields a prompt, not a failure`() = runTest {
        // App reinstall, factory reset, biometric enrolment change. The archive is still readable;
        // the user just has to type the passphrase again. This is the single most important
        // property in the section that specified it.
        val store = FakeStore()
        val provider = FakeProvider()
        val vault = PassphraseVault(store, provider)
        vault.remember("correct horse".toCharArray())

        provider.alive = false

        assertNull(vault.recall())
    }

    @Test
    fun `an undecryptable blob is cleared rather than retried forever`() = runTest {
        val store = FakeStore()
        val provider = FakeProvider()
        val vault = PassphraseVault(store, provider)
        vault.remember("correct horse".toCharArray())

        provider.alive = false
        vault.recall()

        // Nothing can ever read it again, and leaving it there means every launch re-derives the
        // same failure and every UI keeps claiming a passphrase is stored.
        assertNull(store.blob)
    }

    @Test
    fun `an empty vault recalls nothing`() = runTest {
        assertNull(PassphraseVault(FakeStore(), FakeProvider()).recall())
    }

    @Test
    fun `forgetting removes the blob`() = runTest {
        val store = FakeStore()
        val vault = PassphraseVault(store, FakeProvider())
        vault.remember("correct horse".toCharArray())

        vault.forget()

        assertNull(store.blob)
        assertNull(vault.recall())
    }

    @Test
    fun `a failure to wrap does not leave a half-written vault`() = runTest {
        val store = FakeStore()
        val vault = PassphraseVault(store, FakeProvider(alive = false))

        vault.remember("correct horse".toCharArray())

        assertNull(store.blob)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.backup.PassphraseVaultTest"
```

Expected: compilation failure — `PassphraseVault` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/valhalla/thor/data/backup/PassphraseVault.kt`. The DataStore is its own file, following `PreferenceRepositoryImpl`'s precedent of one `preferencesDataStore` delegate per concern:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.valhalla.thor.util.Logger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

/**
 * The shortest passphrase Thor accepts **where one is chosen** — the backup sheet (Task 16) and the
 * Settings screen (Task 18).
 *
 * Deliberately *not* applied where a passphrase is typed to **unlock** an archive. An archive written
 * before this rule existed may have a four-character passphrase, and a minimum on the unlock field
 * would lock it out permanently. Public and top-level so the two places that choose a passphrase
 * cannot drift to different numbers.
 */
const val MIN_PASSPHRASE_LENGTH = 8

/**
 * Wraps and unwraps a few bytes under a key Thor does not hold itself.
 *
 * A seam, not indirection for its own sake: `AndroidKeyStore` cannot be exercised on the JVM, and
 * the contract worth testing is what happens when the key is **gone**.
 */
interface VaultKeyProvider {
    fun wrap(plaintext: ByteArray): ByteArray

    /** @throws java.security.GeneralSecurityException when the key is gone or invalidated. */
    fun unwrap(blob: ByteArray): ByteArray
}

/** Where the wrapped passphrase lives. Separated so the vault's logic is JVM-testable. */
interface PassphraseVaultStore {
    suspend fun read(): String?
    suspend fun write(value: String?)
}

private const val KEY_ALIAS = "thor.archive.passphrase.v1"
private const val GCM_TAG_BITS = 128
private const val GCM_IV_BYTES = 12

@Single(binds = [VaultKeyProvider::class])
class AndroidKeystoreVaultKeyProvider : VaultKeyProvider {

    override fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        // The IV is generated by the Keystore and must be carried with the ciphertext.
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > GCM_IV_BYTES) { "wrapped passphrase is too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES),
        )
        return cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // API 28, which is minSdk. Backup and restore are foreground, user-initiated work,
                // so the device is unlocked by definition — see spec §9.3, where this and
                // WorkManager's deferral being off are the same decision.
                .setUnlockedDeviceRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

private val Context.passphraseVault by preferencesDataStore(name = "thor_passphrase_vault")
private val WRAPPED = stringPreferencesKey("wrapped_passphrase")

@Single(binds = [PassphraseVaultStore::class])
class DataStorePassphraseVaultStore(private val context: Context) : PassphraseVaultStore {

    val flow: Flow<String?> = context.passphraseVault.data.map { it[WRAPPED] }

    override suspend fun read(): String? = flow.first()

    override suspend fun write(value: String?) {
        context.passphraseVault.edit { prefs ->
            if (value == null) prefs.remove(WRAPPED) else prefs[WRAPPED] = value
        }
    }
}

/**
 * The remembered passphrase — **a cache, never the source of truth.**
 *
 * If the Keystore key is gone or invalidated (app reinstall, factory reset, biometric enrolment
 * change), [recall] returns null and the blob is cleared. Thor then prompts, and the archive is
 * still perfectly readable: it was only ever protected by the passphrase itself.
 *
 * Two consequences the UI must state, not imply:
 * - Changing the passphrase does **not** re-encrypt existing archives.
 * - On restore, a stored passphrase that fails the verifier means *prompt*, not *corrupt archive*.
 */
@Single
class PassphraseVault(
    private val store: PassphraseVaultStore,
    private val keyProvider: VaultKeyProvider,
) {

    val isRemembered: Flow<Boolean>
        get() = (store as? DataStorePassphraseVaultStore)?.flow?.map { it != null }
            ?: kotlinx.coroutines.flow.flowOf(false)

    suspend fun remember(passphrase: CharArray) {
        val wrapped = try {
            keyProvider.wrap(passphrase.concatToString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            // Nothing is written: a half-written vault would claim a passphrase is stored and then
            // fail to produce it on every use.
            Logger.e("PassphraseVault", "could not wrap the passphrase", e)
            return
        }
        store.write(Base64.encodeToString(wrapped, Base64.NO_WRAP))
    }

    suspend fun recall(): CharArray? {
        val blob = store.read() ?: return null
        return try {
            keyProvider.unwrap(Base64.decode(blob, Base64.NO_WRAP))
                .toString(Charsets.UTF_8)
                .toCharArray()
        } catch (e: Exception) {
            // The convenience layer failed, so the user types it again. Clearing it here is what
            // stops every later launch re-deriving the same failure while the UI keeps claiming a
            // passphrase is stored.
            Logger.e("PassphraseVault", "the vault key is gone; re-prompting", e)
            store.write(null)
            null
        }
    }

    suspend fun forget() = store.write(null)
}
```

**Note on `Base64`:** `android.util.Base64` is an Android type, and `PassphraseVaultTest` exercises `PassphraseVault` on the JVM — under unit tests, unmocked Android methods throw. Verify at Step 4: if the tests fail with "not mocked", replace `android.util.Base64` with `java.util.Base64` (`getEncoder().withoutPadding()` / `getDecoder()`), which is present from API 26 and needs no mocking. Do **not** add Robolectric for this.

**Note on `isRemembered`:** the `as?` cast is ugly. If it reads badly during implementation, move the `Flow<Boolean>` onto the `PassphraseVaultStore` interface as `val isSet: Flow<Boolean>` and have `FakeStore` back it with a `MutableStateFlow` — that is the better shape and the tests only get simpler. Either way, do not add a second source of truth for "is a passphrase stored".

- [ ] **Step 4: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.backup.PassphraseVaultTest"
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Build, so Koin's binding graph is checked**

```
./gradlew :app:assembleFossDebug
```

Expected: success. `strictSafety` will name any binding it cannot resolve — `Context` is provided by `androidContext()`, and both new interfaces are bound by their `@Single(binds = …)`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/backup/PassphraseVault.kt \
  app/src/test/java/com/valhalla/thor/data/backup/PassphraseVaultTest.kt
git commit -m "feat(backup): passphrase vault whose failure mode is a prompt"
```

---

### Task 6: The capability probe and class sizing, on the privileged surface

Spec §6 and §7.1. Task 3 built and tested the strings; this task is the wiring that runs them, plus the cache that stops a sheet re-probing on every open.

**Deviation from spec §6 — read before starting.** The spec puts `probeDataArchiveCapability()` and the sizing call on `SystemRepository`. They go on a **new narrow port, `AppDataProbe`, implemented by the same `SystemRepositoryImpl` object**, for two reasons:

1. Two hand-written test doubles implement `SystemRepository` in full — `app/src/test/java/com/valhalla/thor/domain/usecase/FreezeAppUseCaseTest.kt:33` and `app/src/test/java/com/valhalla/thor/data/freezer/BulkFreezeWorkerTest.kt:121`. Adding to that interface breaks both test files for a feature neither tests.
2. The capability cache's fake then has two members instead of twenty-five.

The property spec §6 actually cares about is preserved exactly: it is the **same object**, routing through the same `executeShellCommand` → `runGatewayAction` path, so a successful probe remains evidence that the capture that follows can really read those directories. Thor already has this shape — `PrivilegeStateProvider` and `StorageStatsProvider` are narrow ports over concrete classes.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/repository/AppDataProbe.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/SystemRepositoryImpl.kt:24-32` (the `@Single(binds = …)` annotation and the supertype list) and its end, next to `probeObb` at line 298
- Create: `app/src/main/java/com/valhalla/thor/data/backup/DataArchiveCapabilityCache.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/DataArchiveCapabilityCacheTest.kt`

**Interfaces:**
- Consumes: `DataClass`, `DataClassSize` (Task 2); `capabilityProbeCommand`, `parseCapabilityProbe`, `classSizeCommand`, `parseClassSize`, `dataClassRoot` (Task 3).
- Produces: `AppDataProbe` with `suspend fun probeDataArchiveCapability(): Boolean` and `suspend fun measureDataClass(packageName: String, dataClass: DataClass): DataClassSize`; `DataArchiveCapabilityCache` (`@Single`) with `suspend fun isSupported(): Boolean`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/valhalla/thor/data/backup/DataArchiveCapabilityCacheTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataArchiveCapabilityCacheTest {

    private class FakeProbe(var answer: Boolean = true) : AppDataProbe {
        var probes = 0
        override suspend fun probeDataArchiveCapability(): Boolean {
            probes++
            return answer
        }

        override suspend fun measureDataClass(packageName: String, dataClass: DataClass) =
            DataClassSize.Undetermined
    }

    private class FakePrivilege(initial: PrivilegeState) : PrivilegeStateProvider {
        val flow = MutableStateFlow(initial)
        override val state: StateFlow<PrivilegeState> get() = flow
    }

    private fun rooted() = PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)

    @Test
    fun `the answer is probed once and reused`() = runTest {
        // The backup sheet reads this on every open, and every read is a shell round trip through
        // the gateway.
        val probe = FakeProbe()
        val cache = DataArchiveCapabilityCache(probe, FakePrivilege(rooted()))

        assertTrue(cache.isSupported())
        assertTrue(cache.isSupported())

        assertEquals(1, probe.probes)
    }

    @Test
    fun `an unsupported answer is cached too`() = runTest {
        // Otherwise the device where this feature does not work is the one that shells out most.
        val probe = FakeProbe(answer = false)
        val cache = DataArchiveCapabilityCache(probe, FakePrivilege(rooted()))

        assertFalse(cache.isSupported())
        assertFalse(cache.isSupported())

        assertEquals(1, probe.probes)
    }

    @Test
    fun `a privilege change re-probes`() = runTest {
        // Shizuku answers this differently from root, and the user can switch modes while a sheet is
        // open. The cache key is the whole PrivilegeState, so `refresh()` landing a new state is
        // enough to invalidate it — there is no second invalidation path to keep in sync.
        val probe = FakeProbe(answer = false)
        val privilege = FakePrivilege(PrivilegeState(shizuku = true, active = PrivilegeMode.SHIZUKU, isReady = true))
        val cache = DataArchiveCapabilityCache(probe, privilege)
        assertFalse(cache.isSupported())

        probe.answer = true
        privilege.flow.value = rooted()

        assertTrue(cache.isSupported())
        assertEquals(2, probe.probes)
    }

    @Test
    fun `no privileged surface means no shell at all`() = runTest {
        // Not "probe and get false": there is nothing to probe *through*. Shelling out here would
        // spawn a `su` prompt on a device the user never granted anything on.
        val probe = FakeProbe()
        val cache = DataArchiveCapabilityCache(probe, FakePrivilege(PrivilegeState(isReady = true)))

        assertFalse(cache.isSupported())
        assertEquals(0, probe.probes)
    }

    @Test
    fun `a cold start that has not probed yet is not cached as unsupported`() = runTest {
        // `isReady = false` is "not known yet", and the derived answer must not outlive it.
        val probe = FakeProbe()
        val privilege = FakePrivilege(PrivilegeState(isReady = false))
        val cache = DataArchiveCapabilityCache(probe, privilege)
        assertFalse(cache.isSupported())

        privilege.flow.value = rooted()

        assertTrue(cache.isSupported())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.backup.DataArchiveCapabilityCacheTest"
```

Expected: compilation failure — `AppDataProbe` and `DataArchiveCapabilityCache` unresolved.

- [ ] **Step 3: Create the port**

Create `app/src/main/java/com/valhalla/thor/domain/repository/AppDataProbe.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize

/**
 * Read-only questions about another app's private data, answered through the active privilege
 * gateway.
 *
 * A narrow port over `SystemRepositoryImpl` rather than more surface on [SystemRepository]: the two
 * hand-written `RecordingSystemRepository` doubles in the test source set implement that interface in
 * full, and neither exercises backup. Same implementing object, so a capability answer from here is
 * still evidence about the surface the capture will use.
 */
interface AppDataProbe {

    /**
     * Can the active privileged surface read *another* app's private data directory?
     *
     * Deliberately a probe and not a privilege check. Root-started Shizuku can do this and plain
     * Shizuku (`shell` uid) cannot, so "requires Root" would be a lie on the first device and
     * `isRootAvailable()` would be the wrong question on both. Never throws — every failure is
     * `false`, because a maybe here has to read as "do not offer the feature".
     */
    suspend fun probeDataArchiveCapability(): Boolean

    /**
     * Apparent size of one storage class, via `du -s -k`.
     *
     * Returns `Undetermined` for anything that is not a number Thor asked for — a missing `du`, a
     * gateway failure, an unusable package name. `Empty` means the directory genuinely is not there.
     * A caller must not render `Undetermined` as `0 B`; that is the same rule `ObbProbe` and
     * `clearCache`'s nullable byte count already carry.
     */
    suspend fun measureDataClass(packageName: String, dataClass: DataClass): DataClassSize
}
```

- [ ] **Step 4: Implement it on `SystemRepositoryImpl`**

Change the annotation and supertype list at `app/src/main/java/com/valhalla/thor/data/repository/SystemRepositoryImpl.kt:24-32`:

```kotlin
@Single(binds = [SystemRepository::class, AppDataProbe::class])
class SystemRepositoryImpl(
    private val rootGateway: RootSystemGateway,
    private val shizukuGateway: ShizukuSystemGateway,
    private val dhizukuGateway: DhizukuSystemGateway,
    private val preferenceRepository: PreferenceRepository,
    private val storageStats: StorageStatsProvider,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : SystemRepository, AppDataProbe {
```

Add these imports:

```kotlin
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.repository.AppDataProbe
```

Add the two overrides directly after `probeObb` (which ends at line 318), keeping the same
`executeShellCommand`-not-`runGatewayAction` shape and the same no-extra-`withContext` note:

```kotlin
    /**
     * Built on [executeShellCommand] for the reason [probeObb] gives: the probe and the capture that
     * follows it must cross the *same* privileged surface, or a pass here stops being evidence.
     */
    override suspend fun probeDataArchiveCapability(): Boolean {
        // BuildConfig.APPLICATION_ID, not the namespace: `debug` carries an applicationIdSuffix, and
        // the data directory is named after the application id. The namespace would name a path that
        // exists in no build — the same trap `ComponentName`'s two halves set.
        val command = capabilityProbeCommand(BuildConfig.APPLICATION_ID, thorUserId) ?: return false
        return executeShellCommand(command).fold(
            onSuccess = { (exitCode, output) -> parseCapabilityProbe(exitCode, output) },
            onFailure = { false }
        )
    }

    override suspend fun measureDataClass(
        packageName: String,
        dataClass: DataClass
    ): DataClassSize {
        // Empty string rather than a bail-out when shared storage is unavailable: CE and DE do not
        // use it, and `dataClassRoot` refuses the two external classes on an unquotable root anyway.
        val externalRoot = Environment.getExternalStorageDirectory()?.absolutePath.orEmpty()
        val root = dataClassRoot(dataClass, packageName, thorUserId, externalRoot)
            ?: return DataClassSize.Undetermined
        val command = classSizeCommand(root) ?: return DataClassSize.Undetermined
        return executeShellCommand(command).fold(
            onSuccess = { (exitCode, output) -> parseClassSize(exitCode, output) },
            onFailure = { DataClassSize.Undetermined }
        )
    }
```

Thor's own package name reaches a shell here. `capabilityProbeCommand` validates it rather than trusting `BuildConfig` — Task 3's tests pin that.

- [ ] **Step 5: Write the cache**

Create `app/src/main/java/com/valhalla/thor/data/backup/DataArchiveCapabilityCache.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

/**
 * "Can this device back up app data?", answered once per privilege state.
 *
 * The probe is a shell round trip through the gateway, and the backup entry point asks on every
 * sheet open. Keyed on the whole [PrivilegeState] rather than on a TTL: `PrivilegeManager.refresh()`
 * landing a new state *is* the invalidation, so there is no second path to keep in sync and no
 * window where a freshly granted root still reads as unsupported.
 */
@Single
class DataArchiveCapabilityCache(
    private val probe: AppDataProbe,
    private val privilegeState: PrivilegeStateProvider,
) {

    private val mutex = Mutex()

    private var cached: Pair<PrivilegeState, Boolean>? = null

    suspend fun isSupported(): Boolean {
        val state = privilegeState.state.value
        // No surface to probe through. Shelling out would raise a `su` prompt on a device where the
        // user granted nothing — and the answer is derived, not measured, so it is not cached.
        if (!state.hasAnyPrivilege) return false

        mutex.withLock {
            cached?.let { (key, value) -> if (key == state) return value }
            val supported = probe.probeDataArchiveCapability()
            cached = state to supported
            return supported
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.backup.DataArchiveCapabilityCacheTest"
```

Expected: PASS, 5 tests.

- [ ] **Step 7: Build, then run the whole suite**

```
./gradlew :app:assembleFossDebug
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks
```

Expected: both succeed, and the pre-existing test count is unchanged apart from this task's additions — the point of the `AppDataProbe` port is that no existing double had to move.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/repository/AppDataProbe.kt \
  app/src/main/java/com/valhalla/thor/data/repository/SystemRepositoryImpl.kt \
  app/src/main/java/com/valhalla/thor/data/backup/DataArchiveCapabilityCache.kt \
  app/src/test/java/com/valhalla/thor/data/backup/DataArchiveCapabilityCacheTest.kt
git commit -m "feat(backup): probe the privileged surface for data-archive support"
```

---

### Task 7: `AppArchiveStore` — a streaming destination that only appears when complete

Spec §10. Every existing method on `AppBundleFileStore` takes a finished `File` and copies it; nothing opens a stream *at* the destination. A `.thorbak` can be gigabytes, and staging one in Thor's own cache before copying it out would double the peak disk cost of the whole feature. So this is a new port, not a method on the old one.

The rule it exists to enforce: **a partial archive must never be visible under its final name.** Each backend has its own way of getting there.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/repository/AppArchiveStore.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/repository/AppArchiveStoreImpl.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/repository/ArchiveDestinationTest.kt`

**Interfaces:**
- Consumes: `ExportTargetChoice`, `ExportTargetResolution`, `resolveExportTarget` (existing, `domain/model/ExportTarget.kt`); `AppBundleFileStore.isTreeWritable` / `currentTargetLabel` (existing); `THORBAK_MIME`, `thorbakFileName` (Task 2).
- Produces: `AppArchiveStore` with `suspend fun openArchive(fileName: String): ArchiveDestination?`, `suspend fun currentTargetLabel(): String`; `ArchiveDestination` with `val output: OutputStream`, `suspend fun publish(): Boolean`, `suspend fun discard()`; `partialName(fileName)`, `publishedName(fileName)`, `PARTIAL_SUFFIX`.

- [ ] **Step 1: Write the failing test**

The three backends need `ContentResolver`, `DocumentsContract` and real storage, so they are device-verified (checklist items 5 and 6), not JVM-tested. What *is* pure — and what actually broke `ObbInstaller` in review — is the naming: a partial name that collides with a real archive, or a publish that renames onto something already there.

Create `app/src/test/java/com/valhalla/thor/data/repository/ArchiveDestinationTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.THORBAK_EXTENSION
import com.valhalla.thor.domain.model.thorbakFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveDestinationTest {

    @Test
    fun `a partial name cannot be mistaken for a finished archive`() {
        val partial = partialName(thorbakFileName("com.example.game", 42))

        // The restore picker filters on the extension, and the launch-time sweep deletes what it
        // finds by this suffix. A partial that still ended in `.thorbak` would be offered as
        // restorable and, worse, a finished archive would be swept.
        assertFalse(partial.endsWith(".$THORBAK_EXTENSION"))
        assertTrue(partial.endsWith(PARTIAL_SUFFIX))
        assertTrue(partial.startsWith("com.example.game-42.$THORBAK_EXTENSION"))
    }

    @Test
    fun `publishing strips exactly the partial suffix`() {
        val finished = thorbakFileName("com.example.game", 42)

        assertEquals(finished, publishedName(partialName(finished)))
    }

    @Test
    fun `a name that is not partial publishes unchanged`() {
        // Defensive: a backend that already writes under the final name (MediaStore's IS_PENDING)
        // must not have its extension chewed off.
        assertEquals("a.thorbak", publishedName("a.thorbak"))
    }

    @Test
    fun `the partial suffix is not a valid archive extension`() {
        // One literal, two consumers — the sweep and the picker. Pinned so a later edit to either
        // cannot quietly make them disagree.
        assertFalse(PARTIAL_SUFFIX.endsWith(THORBAK_EXTENSION))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.ArchiveDestinationTest"
```

Expected: compilation failure — `partialName`, `publishedName`, `PARTIAL_SUFFIX` unresolved.

- [ ] **Step 3: Write the port**

Create `app/src/main/java/com/valhalla/thor/domain/repository/AppArchiveStore.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import java.io.OutputStream

/**
 * One archive being written, at its destination.
 *
 * Not visible under its final name until [publish]. Use it in a `try`/`finally` and call [discard]
 * on any path that is not a successful publish — an interrupted backup that leaves a plausible
 * `.thorbak` behind is worse than one that leaves nothing.
 */
interface ArchiveDestination {

    /** Where the zip goes. Closed by [publish] or [discard]; do not close it directly. */
    val output: OutputStream

    /**
     * Make the archive visible under its final name. False when it could not be promoted.
     *
     * Deliberately **not** returning the published `Uri`. Nothing downstream needs one — the
     * completion message names the destination label, not a path — and a port returning `Uri` cannot
     * be faked in a JVM test, because `android.net.Uri` throws "not mocked". That would leave the
     * backup use case's whole success path untestable in exchange for a value no caller reads.
     */
    suspend fun publish(): Boolean

    /** Delete the partial archive. Safe to call after [publish]; then it does nothing. */
    suspend fun discard()
}

/**
 * Opens a stream *at* the export destination.
 *
 * A separate port from `AppBundleFileStore`, whose every method takes an already-written `File` and
 * copies it. A `.thorbak` is as large as the app's data, so staging one in Thor's cache first would
 * double this feature's peak disk cost. The destination itself is the same one exports use —
 * `ExportTargetChoice`, the saved SAF tree or Downloads.
 */
interface AppArchiveStore {

    /**
     * @return a destination, or null when there is nowhere to write — no SAF tree and no writable
     *   Downloads. Callers surface that as "choose a folder", never as a failed backup.
     */
    suspend fun openArchive(fileName: String): ArchiveDestination?

    /** Human-readable destination, for the confirm sheet. Mirrors `AppBundleFileStore`'s. */
    suspend fun currentTargetLabel(): String
}
```

- [ ] **Step 4: Write the implementation**

Create `app/src/main/java/com/valhalla/thor/data/repository/AppArchiveStoreImpl.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.model.THORBAK_MIME
import com.valhalla.thor.domain.model.resolveExportTarget
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.AppBundleFileStore
import com.valhalla.thor.domain.repository.ArchiveDestination
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

private const val TAG = "AppArchiveStore"

/**
 * Suffix for an archive that is still being written.
 *
 * Deliberately does not end in `.thorbak`: the restore picker filters on that extension and the
 * launch-time orphan sweep deletes by this one. If a partial were `foo.thorbak.part.thorbak`, the
 * picker would offer a half-written archive and the sweep would delete a finished one.
 */
const val PARTIAL_SUFFIX = ".part"

fun partialName(fileName: String): String = fileName + PARTIAL_SUFFIX

fun publishedName(fileName: String): String = fileName.removeSuffix(PARTIAL_SUFFIX)

@Single(binds = [AppArchiveStore::class])
class AppArchiveStoreImpl(
    private val context: Context,
    private val preferenceRepository: PreferenceRepository,
    private val fileStore: AppBundleFileStore,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : AppArchiveStore {

    override suspend fun currentTargetLabel(): String = withContext(ioDispatcher) {
        fileStore.currentTargetLabel(preferenceRepository.userPreferences.first().exportDirUri)
    }

    override suspend fun openArchive(fileName: String): ArchiveDestination? =
        withContext(ioDispatcher) {
            // The same resolution `ExportAppUseCase.openSession` performs, including the stale-tree
            // clear: an export destination the user revoked must not silently become Downloads for
            // exports and stay broken for archives.
            val savedUri = preferenceRepository.userPreferences.first().exportDirUri
            val resolution = resolveExportTarget(savedUri, fileStore.isTreeWritable(savedUri))
            if (resolution.clearSavedDir) preferenceRepository.setExportDirUri(null)

            try {
                when (val choice = resolution.choice) {
                    is ExportTargetChoice.Custom -> openInTree(choice.treeUri, fileName)
                    ExportTargetChoice.Downloads ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            openInMediaStore(fileName)
                        } else {
                            openInLegacyDownloads(fileName)
                        }
                }
            } catch (e: Exception) {
                // "Nowhere to write" is a real state — a revoked tree, a denied legacy permission, a
                // full volume. The caller turns null into "choose a folder", never into a failure
                // that implies the backup itself went wrong.
                Logger.e(TAG, "could not open \"$fileName\" at the export destination", e)
                null
            }
        }

    /**
     * MediaStore, API 29+. `IS_PENDING = 1` already means "not visible to other apps", so this
     * backend writes under the **final** name and publishes by clearing the flag — no rename, and no
     * window in which a complete archive carries a partial name.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun openInMediaStore(fileName: String): ArchiveDestination? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, THORBAK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val stream = resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        return object : BaseDestination(stream) {
            override fun onPublish(): Boolean {
                val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                // `update` returns the number of rows changed. Zero means the row went away — a user
                // who deleted the pending entry from a file manager mid-backup — and reporting that as
                // a success would tell them a backup exists when nothing does.
                return resolver.update(uri, values, null, null) > 0
            }

            override fun onDiscard() {
                resolver.delete(uri, null, null)
            }
        }
    }

    /**
     * SAF, any API. `createDocument` has no pending concept, so the partial name is real: create
     * `<name>.part`, then rename on publish.
     *
     * `renameDocument` may return null on failure and a provider may de-duplicate a colliding name
     * (`foo (1).thorbak`) instead of failing — so success is "it returned something", not "the name is
     * the one Thor asked for".
     *
     * [treeUri] is a **String**: `ExportTargetChoice.Custom.treeUri` is a persisted string, and
     * `Uri.parse` takes a string. Typing this parameter as `Uri` and then calling `Uri.parse` on it
     * does not compile.
     */
    private fun openInTree(treeUri: String, fileName: String): ArchiveDestination? {
        val resolver = context.contentResolver
        val tree = Uri.parse(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val docUri = DocumentsContract.createDocument(
            resolver,
            parent,
            THORBAK_MIME,
            partialName(fileName),
        ) ?: return null
        val stream = resolver.openOutputStream(docUri) ?: run {
            DocumentsContract.deleteDocument(resolver, docUri)
            return null
        }
        return object : BaseDestination(stream) {
            override fun onPublish(): Boolean =
                DocumentsContract.renameDocument(resolver, docUri, fileName) != null

            override fun onDiscard() {
                DocumentsContract.deleteDocument(resolver, docUri)
            }
        }
    }

    /**
     * API 28's Downloads directory as a plain `File`. `renameTo` within one volume is atomic, which
     * is the same guarantee the other two backends reach by other means.
     */
    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory: deprecated at 29, and this branch
    // only runs below 29. minSdk is 28, which is the whole reason the branch exists.
    private fun openInLegacyDownloads(fileName: String): ArchiveDestination? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val partial = File(dir, partialName(fileName))
        val stream = FileOutputStream(partial)
        return object : BaseDestination(stream) {
            override fun onPublish(): Boolean = partial.renameTo(File(dir, fileName))

            override fun onDiscard() {
                partial.delete()
            }
        }
    }
}

/**
 * The half of [ArchiveDestination] that is identical across all three backends: close exactly once,
 * publish or discard exactly once, and never do both.
 *
 * Closing before publishing is not tidiness — it is what flushes the stream. Publishing while a
 * buffered chunk is still in memory produces an archive that passes every check except its own chunk
 * count.
 */
private abstract class BaseDestination(override val output: OutputStream) : ArchiveDestination {

    private var settled = false

    protected abstract fun onPublish(): Boolean

    protected abstract fun onDiscard()

    override suspend fun publish(): Boolean {
        if (settled) return false
        settled = true
        output.close()
        return onPublish()
    }

    /**
     * Idempotent, because the calling shape is `try { … publish() } finally { discard() }` — a
     * discard after a successful publish is the *normal* path and must do nothing.
     */
    override suspend fun discard() {
        if (settled) return
        settled = true
        runCatching { output.close() }
        onDiscard()
    }
}
```

Two things to check while writing this, because both are silent if wrong:
- `ExportTargetChoice`, `ExportTargetResolution` and `resolveExportTarget` live in **`domain/model/ExportTarget.kt`**, not in `ExportAppUseCase.kt`. `resolveExportTarget` returns a `Resolution` carrying `choice` **and** `clearSavedDir`; dropping the flag leaves a revoked tree in the preferences forever.
- The saved-tree preference is `exportDirUri` / `setExportDirUri`, and `AppBundleFileStore.currentTargetLabel` **takes** that string (`currentTargetLabel(savedTreeUriStr: String?)`).

- [ ] **Step 5: Run the test to verify it passes**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.ArchiveDestinationTest"
```

Expected: PASS, 4 tests.

- [ ] **Step 6: Build**

```
./gradlew :app:assembleFossDebug
```

Expected: success. Note the lint gate is fatal: `MediaStore.Downloads` and `IS_PENDING` are API 29, and `Environment.getExternalStoragePublicDirectory` is deprecated from 29 — the `Build.VERSION.SDK_INT` branch satisfies the first, and the second needs `@Suppress("DEPRECATION")` **on the legacy method only**, with a comment saying minSdk 28 is why the branch exists at all.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/repository/AppArchiveStore.kt \
  app/src/main/java/com/valhalla/thor/data/repository/AppArchiveStoreImpl.kt \
  app/src/test/java/com/valhalla/thor/data/repository/ArchiveDestinationTest.kt
git commit -m "feat(backup): streaming archive destination that publishes only when complete"
```

---

### Task 8: The job seam — key holder, progress model, notification, base worker

Spec §9. This is the reusable half of the WorkManager decision: the owner's requirement is that long tasks across Thor (exports, bulk actions) can move onto this seam later, so nothing here mentions backup except the two job kinds.

**Do not migrate `BulkFreezeRunner` in this plan.** It is prior art — a process-scoped `CoroutineScope` + `Semaphore` that dies with the process, which is exactly the problem this seam solves — and moving it is a follow-up row filed in Task 18. The base class must simply be general enough to host it.

**Two spec rules that shape everything below — get them wrong and the code looks fine:**

- **§9.2: progress does not go through `Data`.** `setProgress` is an SQLite write per call, so WorkManager throttles it to roughly 1/s. Progress goes to an in-memory `JobRegistry` the UI observes directly. `Data` is used only for the worker's **input** (package name, class ids, salt — none of it secret) and for one **failure output** string.
- **§9.3: one chain, `APPEND_OR_REPLACE`.** The unique work name does **not** contain the target. All archive jobs share one name so they *serialise*, which is what keeps peak disk at one storage class no matter how many the user starts. `APPEND_OR_REPLACE` rather than `APPEND` so a previously failed or cancelled job cannot wedge the chain forever.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/ThorJob.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/JobRegistry.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/ArchiveKeyHolder.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobNotifications.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobWorker.kt`
- Create: `app/src/main/res/values/strings_backup.xml`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/ThorJobTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/ArchiveKeyHolderTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/JobRegistryTest.kt`

**Interfaces:**
- Consumes: Task 1's dependencies and manifest entries.
- Produces: `ThorJobKind`, `ThorJobStage`, `ThorJobProgress` (with `percent`), `THOR_JOB_CHAIN`, `jobTag(kind, target)`, `JOB_ERROR_KEY`; `JobRegistry` (`@Single`) with `progressOf(jobId): StateFlow<ThorJobProgress?>`, `publish(jobId, progress)`, `clear(jobId)`; `ArchiveKeyHolder` (`@Single`) with `put`/`take`/`drop`; `ThorJobNotifications` (`@Single`) with `foregroundInfo(kind, progress, jobId)` and `update(kind, progress, jobId)`; `abstract class ThorJobWorker` with `abstract val kind`, `abstract val initialLabel`, `protected abstract suspend fun runJob(): Result`, `protected fun publish(progress: ThorJobProgress)` (not `suspend`).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/valhalla/thor/domain/model/ThorJobTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThorJobTest {

    @Test
    fun `the work chain name does not depend on the target`() {
        // §9.3: one chain for every archive job, so `APPEND_OR_REPLACE` *serialises* them and peak
        // disk stays one storage class however many the user starts. A per-package unique name would
        // let two multi-gigabyte captures run at once — the exact thing the format was shaped to
        // avoid.
        assertEquals(false, THOR_JOB_CHAIN.contains("com.example"))
        assertEquals(false, ThorJobKind.entries.any { THOR_JOB_CHAIN.contains(it.id) })
    }

    @Test
    fun `a job tag identifies the kind and the target`() {
        // Tags are how the UI answers "is this app already queued?" — the chain name cannot, because
        // every job shares it.
        assertEquals(
            jobTag(ThorJobKind.ARCHIVE_BACKUP, "com.example.game"),
            jobTag(ThorJobKind.ARCHIVE_BACKUP, "com.example.game"),
        )
        assertNotEquals(
            jobTag(ThorJobKind.ARCHIVE_BACKUP, "com.example.game"),
            jobTag(ThorJobKind.ARCHIVE_BACKUP, "com.example.other"),
        )
        assertNotEquals(
            jobTag(ThorJobKind.ARCHIVE_BACKUP, "com.example.game"),
            jobTag(ThorJobKind.ARCHIVE_RESTORE, "com.example.game"),
        )
    }

    @Test
    fun `no kind's id is a prefix of another's`() {
        // A tag is built by concatenation, so `restore` + `:x` colliding with `restore:x` + `` would
        // silently make two jobs one.
        val ids = ThorJobKind.entries.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        for (a in ids) for (b in ids) if (a != b) assertEquals(false, a.startsWith(b))
    }

    @Test
    fun `an unknown total reports no percentage rather than zero`() {
        // Same tri-state rule as DataClassSize and ObbProbe: "not known" is not "none". A bar pinned
        // at 0% for a job that is running reads as broken.
        val progress = ThorJobProgress(ThorJobStage.MEASURING, "Measuring", completedBytes = 0, totalBytes = 0)

        assertNull(progress.percent)
    }

    @Test
    fun `a known total reports a percentage`() {
        val progress = ThorJobProgress(ThorJobStage.WRITING, "Writing", completedBytes = 512, totalBytes = 2_048)

        assertEquals(25, progress.percent)
    }

    @Test
    fun `a percentage never exceeds one hundred`() {
        // `du` reports apparent size and the tar is built afterwards; the two disagree routinely, so
        // completed > total is an ordinary outcome, not a bug to assert against.
        val progress = ThorJobProgress(ThorJobStage.WRITING, "Writing", completedBytes = 900, totalBytes = 100)

        assertEquals(100, progress.percent)
    }

    @Test
    fun `a negative completed count cannot drive the bar below zero`() {
        // Nothing should produce one, but a `du` parse and a byte counter feed this from two
        // directions and the clamp is one expression.
        val progress = ThorJobProgress(ThorJobStage.WRITING, "Writing", completedBytes = -5, totalBytes = 100)

        assertEquals(0, progress.percent)
    }
}
```

Create `app/src/test/java/com/valhalla/thor/data/backup/job/ArchiveKeyHolderTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reason this class exists rather than a `Data` entry: **WorkManager persists `Data` to SQLite**,
 * so a passphrase or derived key put there is written to disk in the clear and survives the job.
 */
class ArchiveKeyHolderTest {

    private val holder = ArchiveKeyHolder()
    private fun key(byte: Byte) = SecretKeySpec(ByteArray(32) { byte }, "AES")

    @Test
    fun `a job gets back the key it was handed`() {
        holder.put("job-1", key(1))

        assertArrayEquals(ByteArray(32) { 1 }, holder.take("job-1")?.encoded)
    }

    @Test
    fun `a key is single-use`() {
        // Taken once, at the top of the worker. Anything left behind is key material sitting in
        // process memory with no job to use it.
        holder.put("job-1", key(1))
        holder.take("job-1")

        assertNull(holder.take("job-1"))
    }

    @Test
    fun `a job whose process died finds nothing`() {
        // This is the path that forbids Result.retry(): WorkManager would re-run the worker in a
        // fresh process where the key is gone, and a retry that cannot possibly succeed burns the
        // backoff chain and reports failure much later than the truth.
        assertNull(holder.take("job-that-never-ran"))
    }

    @Test
    fun `keys do not leak between jobs`() {
        holder.put("job-1", key(1))
        holder.put("job-2", key(2))

        assertArrayEquals(ByteArray(32) { 2 }, holder.take("job-2")?.encoded)
        assertArrayEquals(ByteArray(32) { 1 }, holder.take("job-1")?.encoded)
    }

    @Test
    fun `dropping a key that was never taken clears it`() {
        // The enqueue path can fail after putting the key — a rejected work request, a cancelled
        // confirm sheet.
        holder.put("job-1", key(1))
        holder.drop("job-1")

        assertNull(holder.take("job-1"))
    }
}
```

Create `app/src/test/java/com/valhalla/thor/data/backup/job/JobRegistryTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Progress lives here rather than in WorkManager's `Data` (§9.2): `setProgress` is an SQLite write
 * per call, throttled to roughly 1/s, so a byte-level bar routed through it is both slow and a write
 * amplifier on a job already saturating the disk.
 */
class JobRegistryTest {

    private val registry = JobRegistry()
    private val jobId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `an observer that subscribes before the job starts sees no progress`() = runTest {
        // The UI collects as soon as it enqueues, which is before the worker's first publish.
        assertNull(registry.progressOf(jobId).value)
    }

    @Test
    fun `published progress reaches an observer that subscribed first`() = runTest {
        val flow = registry.progressOf(jobId)
        val progress = ThorJobProgress(ThorJobStage.CAPTURING, "Capturing", 10, 100)

        registry.publish(jobId, progress)

        assertEquals(progress, flow.value)
    }

    @Test
    fun `one job id is one flow`() = runTest {
        // A second call handing back a different flow is the bug where the UI observes one instance
        // and the worker publishes to another — and it looks exactly like "progress never updates".
        assertSame(registry.progressOf(jobId), registry.progressOf(jobId))
    }

    @Test
    fun `jobs do not see each other's progress`() = runTest {
        val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
        registry.publish(jobId, ThorJobProgress(ThorJobStage.WRITING, "One", 1, 2))

        assertNull(registry.progressOf(other).value)
    }

    @Test
    fun `clearing a finished job drops its progress`() = runTest {
        // Otherwise every job Thor has ever run stays in memory until the process dies.
        registry.publish(jobId, ThorJobProgress(ThorJobStage.FINISHING, "Done", 2, 2))

        registry.clear(jobId)

        assertNull(registry.progressOf(jobId).value)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.ThorJobTest" --tests "com.valhalla.thor.data.backup.job.*"
```

Expected: compilation failure — `THOR_JOB_CHAIN`, `jobTag`, `ThorJobProgress`, `ArchiveKeyHolder`, `JobRegistry` unresolved.

- [ ] **Step 3: Write the pure job model**

Create `app/src/main/java/com/valhalla/thor/domain/model/ThorJob.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * The one unique work name every archive job shares.
 *
 * Deliberately does **not** name the target. With `ExistingWorkPolicy.APPEND_OR_REPLACE` this makes
 * runs *serialise*, which is what holds peak disk at one storage class however many backups the user
 * starts. A per-package name would let two multi-gigabyte captures run at once.
 *
 * `APPEND_OR_REPLACE` rather than `APPEND` because the chain must not be wedged by a job that failed
 * or was cancelled — replace is the escape hatch that keeps the queue usable.
 */
const val THOR_JOB_CHAIN = "thor.job.chain"

/** Set on a failed job's output `Data` so the UI can say what went wrong instead of "failed". */
const val JOB_ERROR_KEY = "thor.job.error"

/**
 * The long-running jobs Thor runs through WorkManager.
 *
 * Two for now. Exports and bulk actions are meant to join them — that is why nothing in this file or
 * in `ThorJobWorker` mentions archives.
 */
enum class ThorJobKind(val id: String) {
    ARCHIVE_BACKUP("archive-backup"),
    ARCHIVE_RESTORE("archive-restore"),
}

enum class ThorJobStage {
    PREPARING,
    MEASURING,
    CAPTURING,
    WRITING,
    INSTALLING,
    RESTORING,
    FINISHING,
}

/**
 * A tag naming one job's kind and target.
 *
 * Since every job shares [THOR_JOB_CHAIN], the chain name can no longer answer "is this package
 * already queued?". This tag can: `WorkManager.getWorkInfosByTag` finds it, which is how the UI both
 * refuses a double tap on the same app and reattaches to a running job after a rotation.
 */
fun jobTag(kind: ThorJobKind, target: String): String = "thor.job.${kind.id}.$target"

/**
 * What a running job reports.
 *
 * @param totalBytes 0 when the size is not known — an app whose `du` returned nothing usable. Then
 *   [percent] is null and the UI shows an indeterminate bar. Never render an unknown total as 0%.
 */
data class ThorJobProgress(
    val stage: ThorJobStage,
    val label: String,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
) {

    val percent: Int?
        get() = if (totalBytes > 0L) {
            // `du` reports apparent size; the tar that follows disagrees with it routinely, in both
            // directions. Clamping is the expected case, not a guard against a bug.
            ((completedBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
        } else {
            null
        }
}
```

There is no `toMap()`/`fromMap` here and none is to be added. Progress never travels through
`androidx.work.Data` (§9.2) — the next step's registry carries it in memory — so a serialisation pair
would be dead code that invites a future contributor to route progress back through SQLite.

- [ ] **Step 4: Write the progress registry**

Create `app/src/main/java/com/valhalla/thor/data/backup/job/JobRegistry.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.ThorJobProgress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.Single

/**
 * Where a running job's progress lives: in memory, for the life of the process.
 *
 * **Not `setProgress`.** Every `setProgress` call is a write to WorkManager's SQLite database, so
 * WorkManager throttles observers to roughly one update a second — a backup that copies a gigabyte in
 * 1 MiB chunks would try to write a thousand rows. §9.2 puts progress here instead: the worker
 * publishes, the ViewModel collects the same `StateFlow`, and nothing touches the disk.
 *
 * The cost of that choice is that progress does not survive process death. That is acceptable because
 * a killed archive job cannot resume anyway ([ArchiveKeyHolder] holds its key in this same process),
 * so there is no state worth persisting. WorkManager's own `WorkInfo.State` — which *is* persisted —
 * remains the source of truth for "is it running, did it succeed".
 */
@Single
class JobRegistry {

    private val flows = ConcurrentHashMap<UUID, MutableStateFlow<ThorJobProgress?>>()

    /**
     * The flow for [jobId], created on first use.
     *
     * Returns the same instance every call, so a collector that subscribes *before* the worker starts
     * still sees the first published value. A new flow per call would drop everything published in
     * between.
     */
    fun progressOf(jobId: UUID): StateFlow<ThorJobProgress?> = flow(jobId)

    fun publish(jobId: UUID, progress: ThorJobProgress) {
        flow(jobId).value = progress
    }

    /** Call when a job reaches a terminal state, or every job Thor ever ran stays in memory. */
    fun clear(jobId: UUID) {
        flows.remove(jobId)
    }

    private fun flow(jobId: UUID) = flows.getOrPut(jobId) { MutableStateFlow(null) }
}
```

- [ ] **Step 5: Write the key holder**

Create `app/src/main/java/com/valhalla/thor/data/backup/job/ArchiveKeyHolder.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import org.koin.core.annotation.Single

/**
 * Hands a derived key from the confirm sheet to the worker, in memory only.
 *
 * **Never put a passphrase or a derived key in a `WorkRequest`'s input `Data`.** WorkManager persists
 * `Data` to its SQLite database, so that writes key material to disk in the clear and leaves it there
 * after the job is pruned.
 *
 * The consequence is deliberate: a job whose process died has no key, so it **fails** rather than
 * retrying. `Result.retry()` is forbidden in every archive worker — WorkManager would re-run in a
 * fresh process where [take] returns null, and the user would be told much later that a backup they
 * watched start had failed.
 */
@Single
class ArchiveKeyHolder {

    private val keys = ConcurrentHashMap<String, SecretKey>()

    fun put(jobId: String, key: SecretKey) {
        keys[jobId] = key
    }

    /** Single-use: a key with no job to use it is key material held for nothing. */
    fun take(jobId: String): SecretKey? = keys.remove(jobId)

    /** For an enqueue that failed after [put] — a rejected request, a dismissed sheet. */
    fun drop(jobId: String) {
        keys.remove(jobId)
    }
}
```

- [ ] **Step 6: Write the notification**

Create `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobNotifications.kt`. Model it on `data/freezer/BulkResultNotifier.kt`, which already owns channel creation and a `NotificationManagerCompat` — but a **different channel and different notification ids**: `BulkResultNotifier` uses `"thor.bulk_result"` and id `1001`, and reusing either would make a finished bulk action replace a running backup's progress.

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import java.util.UUID
import org.koin.core.annotation.Single

@Single
class ThorJobNotifications(private val context: Context) {

    fun foregroundInfo(
        kind: ThorJobKind,
        progress: ThorJobProgress,
        jobId: UUID,
    ): ForegroundInfo {
        val notification = build(kind, progress, jobId)
        val id = notificationId(kind)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Must match the manifest's android:foregroundServiceType on WorkManager's
            // SystemForegroundService, or targetSdk 34+ throws MissingForegroundServiceTypeException
            // the moment setForeground() runs. Task 1 added that overlay.
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    fun update(kind: ThorJobKind, progress: ThorJobProgress, jobId: UUID) {
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(manager)
        // No POST_NOTIFICATIONS gate: a foreground service's own notification is exempt from that
        // permission, and gating it would silently drop the progress UI on API 33+ devices where the
        // user declined the prompt Thor shows for the bulk-result notification.
        if (!manager.areNotificationsEnabled()) return
        manager.notify(notificationId(kind), build(kind, progress, jobId))
    }

    private fun build(kind: ThorJobKind, progress: ThorJobProgress, jobId: UUID) =
        NotificationCompat.Builder(context, CHANNEL_ID).apply {
            setSmallIcon(R.drawable.ic_thor_notification)
            setContentTitle(context.getString(titleFor(kind)))
            setContentText(progress.label)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            // An unknown total is an indeterminate bar, never a bar sitting at 0%.
            val percent = progress.percent
            if (percent == null) setProgress(0, 0, true) else setProgress(100, percent, false)
            // createCancelPendingIntent needs no receiver of Thor's own, and it cancels the work
            // rather than just dismissing the notification.
            addAction(
                0,
                context.getString(android.R.string.cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(jobId),
            )
        }.build()

    private fun titleFor(kind: ThorJobKind) = when (kind) {
        ThorJobKind.ARCHIVE_BACKUP -> R.string.job_backing_up
        ThorJobKind.ARCHIVE_RESTORE -> R.string.job_restoring
    }

    private fun notificationId(kind: ThorJobKind) = BASE_NOTIFICATION_ID + kind.ordinal

    private fun ensureChannel(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_LOW)
                .setName(context.getString(R.string.job_channel_name))
                .setDescription(context.getString(R.string.job_channel_description))
                .build()
        )
    }

    companion object {
        /** Distinct from `BulkResultNotifier.CHANNEL_ID` — a silenced bulk channel must not silence this. */
        const val CHANNEL_ID = "thor.jobs"

        /** `BulkResultNotifier` owns 1001. */
        const val BASE_NOTIFICATION_ID = 1100
    }
}
```

Create `app/src/main/res/values/strings_backup.xml` — **not** `values/strings.xml`. Every string this feature adds goes in this one new file, for the reason in Global Constraints: `MissingTranslation` is a fatal lint check and the five locale files are all at exactly 544 strings, so a string added to `values/strings.xml` without four translations fails `./gradlew lint`. The file-level `tools:ignore` mirrors the precedent in `app/src/main/res/values/non-translatable.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Backup and restore strings (#51 phase 2). Untranslated for now: these ship
  English-only and are exempted from MissingTranslation at the file level, the
  same way values/non-translatable.xml is. Translating them is a follow-up row,
  not a blocker — see docs/follow-ups/app-data-backup-and-xapk-export.md.
  Do NOT move these into values/strings.xml until values-ar, values-es,
  values-fr and values-zh-rCN all carry them, or lint turns fatal.
-->
<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation">
    <string name="job_backing_up">Backing up</string>
    <string name="job_restoring">Restoring</string>
    <string name="job_channel_name">Backup and restore</string>
    <string name="job_channel_description">Progress for backups and restores running in the background</string>
</resources>
```

Check `R.drawable.ic_thor_notification` against what `BulkResultNotifier` actually passes to `setSmallIcon` and use that same drawable rather than inventing a name.

- [ ] **Step 7: Write the base worker**

Create `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobWorker.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.valhalla.thor.domain.model.JOB_ERROR_KEY
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException

private const val TAG = "ThorJobWorker"

/**
 * Base class for Thor's long-running work: foreground notification, progress reporting, and one
 * failure policy.
 *
 * Deliberately knows nothing about archives — exports and bulk actions are meant to move onto this.
 *
 * **No subclass may return `Result.retry()`.** Archive jobs hold their key in process memory
 * ([ArchiveKeyHolder]), so a retry after process death runs without a key and cannot succeed; and a
 * partially written destination is discarded on the way out, so there is nothing for a retry to
 * resume. Process death ends a run.
 */
abstract class ThorJobWorker(
    appContext: Context,
    params: WorkerParameters,
    private val notifications: ThorJobNotifications,
    private val registry: JobRegistry,
) : CoroutineWorker(appContext, params) {

    protected abstract val kind: ThorJobKind

    /** Shown before the job knows anything about sizes. */
    protected abstract val initialLabel: String

    protected abstract suspend fun runJob(): Result

    final override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(
            kind,
            ThorJobProgress(ThorJobStage.PREPARING, initialLabel),
            id,
        )

    final override suspend fun doWork(): Result {
        // On API 31+ a foreground service cannot be started from the background, and a user who
        // backgrounds Thor between tapping and this line makes that a real outcome. The job then runs
        // without a foreground notification — more killable, but running — rather than crashing
        // before it starts.
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Logger.e(TAG, "${kind.id}: continuing without a foreground notification", it) }

        return try {
            runJob()
        } catch (e: CancellationException) {
            // The user pressed Cancel, or WorkManager stopped the worker. Rethrow so the coroutine
            // machinery sees a cancellation; the subclass's own `finally` has already discarded the
            // partial destination.
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "${kind.id} failed", e)
            Result.failure(workDataOf(JOB_ERROR_KEY to (e.message ?: "unknown error")))
        } finally {
            // Runs on cancellation too, because the rethrow above passes through it. The UI reads
            // WorkManager's own persisted `WorkInfo.State` for the terminal outcome, so dropping the
            // in-memory progress here loses nothing and bounds the map.
            registry.clear(id)
        }
    }

    /**
     * Report progress to the UI and the notification.
     *
     * `setProgress` is deliberately absent — see [JobRegistry]. Calling it here would put an SQLite
     * write on the copy loop's hot path and cap observed updates at roughly one a second.
     */
    protected fun publish(progress: ThorJobProgress) {
        registry.publish(id, progress)
        notifications.update(kind, progress, id)
    }
}
```

`publish` is no longer `suspend`: both calls it makes are non-blocking, and an unnecessary `suspend`
would let a subclass believe it is a cancellation point.

- [ ] **Step 8: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.ThorJobTest" --tests "com.valhalla.thor.data.backup.job.*"
```

Expected: PASS, 17 tests (7 `ThorJobTest`, 5 `ArchiveKeyHolderTest`, 5 `JobRegistryTest`). Read the
count out of `app/build/test-results/testFossDebugUnitTest/*.xml`, not the Gradle log line.

- [ ] **Step 9: Build**

```
./gradlew :app:assembleFossDebug
```

Expected: success. There is no JVM test for `ThorJobWorker` or `ThorJobNotifications` and none is to be added — a `CoroutineWorker` needs `WorkerParameters`, and `NotificationManagerCompat` needs a real `Context`. Their gate is this build plus device checks 12 and 13. Do not add Robolectric, and do not write a test that asserts nothing in order to have one.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/ThorJob.kt \
  app/src/main/java/com/valhalla/thor/data/backup/job/JobRegistry.kt \
  app/src/main/java/com/valhalla/thor/data/backup/job/ArchiveKeyHolder.kt \
  app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobNotifications.kt \
  app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobWorker.kt \
  app/src/main/res/values/strings_backup.xml \
  app/src/test/java/com/valhalla/thor/domain/model/ThorJobTest.kt \
  app/src/test/java/com/valhalla/thor/data/backup/job/ArchiveKeyHolderTest.kt \
  app/src/test/java/com/valhalla/thor/data/backup/job/JobRegistryTest.kt
git commit -m "feat(jobs): reusable foreground job seam for Thor's long-running work"
```

---

### Task 9: `AppDataArchiveGateway` — the privileged surface, and `MeasureAppDataUseCase`

Spec §6, §7.1, §7.4. Task 3 produced the command *strings*; nothing has run one yet. This task builds the port that runs them, plus the use case the backup sheet calls to fill in sizes.

`classifyTarExit` is a pure function and gets real tests. **§7.3 is the reason it exists:** a `tar` exit of 1 with a non-empty archive is a *warning*, not a failure — GNU-family tar returns 1 for "a file changed while being read", which is routine on live app data even after a force-stop. Treating it as failure would fail most real backups.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/repository/AppDataArchiveGateway.kt`
- Create: `app/src/main/java/com/valhalla/thor/domain/model/TarOutcome.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImpl.kt`
- Create: `app/src/main/java/com/valhalla/thor/domain/usecase/MeasureAppDataUseCase.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/TarOutcomeTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/usecase/MeasureAppDataUseCaseTest.kt`

**Interfaces:**
- Consumes: `DataClass`, `DataClassSize`, `ArchiveSkip` (Task 2); `dataClassRoot`, `listClassEntriesCommand`, `filterBackupEntries`, `tarCreateCommand`, `chownFileCommand`, `ClassEntries` (Task 3); `AppDataProbe`, `DataArchiveCapabilityCache` (Task 6).
- Produces: `TarOutcome` (`Succeeded`/`SucceededWithWarning`/`Failed`), `classifyTarExit(exitCode, stagedBytes): TarOutcome`; `AppDataArchiveGateway` with `suspend fun thorUserId(): Int`, `suspend fun externalStorageDir(): String`, `suspend fun stagingFile(name: String): File`, `suspend fun forceStop(packageName: String)`, `suspend fun listClass(packageName, dataClass): ClassEntries`, `suspend fun tarClass(packageName, dataClass, entries, out, compress): TarOutcome`, `suspend fun appUid(packageName: String): Int?`, `suspend fun signerSha256(packageName: String): String?`; `MeasureAppDataUseCase` with `suspend operator fun invoke(packageName: String): AppDataMeasurement` and `data class AppDataMeasurement(val supported: Boolean, val sizes: Map<DataClass, DataClassSize>)`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/valhalla/thor/domain/model/TarOutcomeTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TarOutcomeTest {

    @Test
    fun `a clean exit is a plain success`() {
        assertEquals(TarOutcome.Succeeded, classifyTarExit(exitCode = 0, stagedBytes = 4096L))
    }

    @Test
    fun `exit 1 with bytes on disk is a warning, not a failure`() {
        // §7.3. GNU-family tar returns 1 for "a file changed while being read", which happens on
        // nearly every live app directory even after a force-stop. Failing here would fail most real
        // backups; the archive is complete enough to restore, and the header says so.
        val outcome = classifyTarExit(exitCode = 1, stagedBytes = 4096L)

        assertTrue(outcome.toString(), outcome is TarOutcome.SucceededWithWarning)
    }

    @Test
    fun `exit 1 with an empty archive is a failure`() {
        // Nothing was written, so there is nothing to warn about — this is tar giving up.
        assertTrue(classifyTarExit(exitCode = 1, stagedBytes = 0L) is TarOutcome.Failed)
    }

    @Test
    fun `exit 2 is a failure even with bytes on disk`() {
        // 2 is tar's fatal class. A partially written tar is worse than none: it would restore a
        // truncated directory tree over the app's real data.
        assertTrue(classifyTarExit(exitCode = 2, stagedBytes = 999L) is TarOutcome.Failed)
    }

    @Test
    fun `a folded exception is a failure, never a warning`() {
        // `RootSystemGateway.execute()` folds a *throw* into `-1 to stackTraceToString()`. A negative
        // code is Thor's own stack trace, not a tar verdict — and `-1` is not `> 1`, so a rule
        // written as "exitCode > 1 fails" would classify it as a success with a warning.
        assertTrue(classifyTarExit(exitCode = -1, stagedBytes = 4096L) is TarOutcome.Failed)
    }

    @Test
    fun `a warning carries text a header can hold`() {
        val outcome = classifyTarExit(exitCode = 1, stagedBytes = 4096L)

        val warning = (outcome as TarOutcome.SucceededWithWarning).warning
        assertTrue(warning, warning.isNotBlank())
    }
}
```

Create `app/src/test/java/com/valhalla/thor/domain/usecase/MeasureAppDataUseCaseTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.repository.AppDataProbe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AppDataProbe` is a two-method port precisely so this can be faked in six lines. Widening
 * `SystemRepository` instead would have made this test a 40-method stub — see deviation 7.
 */
private class FakeProbe(
    private val supported: Boolean,
    private val sizes: Map<DataClass, DataClassSize> = emptyMap(),
) : AppDataProbe {
    var measured = mutableListOf<DataClass>()

    override suspend fun probeDataArchiveCapability(): Boolean = supported

    override suspend fun measureDataClass(packageName: String, dataClass: DataClass): DataClassSize {
        measured += dataClass
        return sizes[dataClass] ?: DataClassSize.Undetermined
    }
}

class MeasureAppDataUseCaseTest {

    @Test
    fun `an unsupported channel measures nothing at all`() = runTest {
        // Not a cosmetic short-circuit: every measurement is a shell round trip, and on a
        // shell-uid Shizuku device all four would fail slowly and render as "unknown".
        val probe = FakeProbe(supported = false)

        val result = MeasureAppDataUseCase(probe)("com.example.app")

        assertFalse(result.supported)
        assertTrue(result.sizes.isEmpty())
        assertTrue(probe.measured.toString(), probe.measured.isEmpty())
    }

    @Test
    fun `a supported channel measures every class`() = runTest {
        val probe = FakeProbe(
            supported = true,
            sizes = mapOf(
                DataClass.CE to DataClassSize.Known(2048L),
                DataClass.DE to DataClassSize.Empty,
            ),
        )

        val result = MeasureAppDataUseCase(probe)("com.example.app")

        assertTrue(result.supported)
        assertEquals(DataClass.entries.toSet(), result.sizes.keys)
        assertEquals(DataClassSize.Known(2048L), result.sizes[DataClass.CE])
        assertEquals(DataClassSize.Empty, result.sizes[DataClass.DE])
    }

    @Test
    fun `a class that could not be measured stays Undetermined rather than becoming zero`() = runTest {
        // The whole point of the tri-state. `Known(0)` here is how a user unticks data they have.
        val probe = FakeProbe(supported = true, sizes = emptyMap())

        val result = MeasureAppDataUseCase(probe)("com.example.app")

        assertEquals(DataClassSize.Undetermined, result.sizes[DataClass.EXTERNAL_MEDIA])
    }

    @Test
    fun `an unusable package name is refused without a shell round trip`() = runTest {
        val probe = FakeProbe(supported = true, sizes = mapOf(DataClass.CE to DataClassSize.Known(1L)))

        val result = MeasureAppDataUseCase(probe)("com.example.app; rm -rf /")

        assertFalse(result.supported)
        assertTrue(probe.measured.isEmpty())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.TarOutcomeTest" --tests "com.valhalla.thor.domain.usecase.MeasureAppDataUseCaseTest"
```

Expected: compilation failure — `TarOutcome`, `classifyTarExit`, `MeasureAppDataUseCase` unresolved.

- [ ] **Step 3: Write the tar classifier**

Create `app/src/main/java/com/valhalla/thor/domain/model/TarOutcome.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** What one `tar` invocation amounted to. Three outcomes, because §7.3 needs the middle one. */
sealed interface TarOutcome {
    data object Succeeded : TarOutcome
    data class SucceededWithWarning(val warning: String) : TarOutcome
    data class Failed(val reason: String) : TarOutcome
}

/**
 * Decide what a `tar` exit code means, given how much it actually wrote.
 *
 * The exit code alone is not enough in either direction:
 *
 * - **1 is usually not a failure.** GNU-family tar uses it for "a file changed while being read",
 *   which live app data does constantly — even after a force-stop, because the system keeps touching
 *   an app's directories. §7.3 records it as a warning in the header and carries on.
 * - **A negative code is not a tar code at all.** `RootSystemGateway.execute()` folds a *throw* into
 *   `-1 to stackTraceToString()`, so `-1` means Thor's own exception. Note that any rule phrased as
 *   "`exitCode > 1` fails" silently classifies `-1` as a *success with a warning*; the check is
 *   therefore written as an explicit `0` / `1` / everything-else.
 *
 * @param stagedBytes the length of the file `tar` was told to write, read *after* it exited. Zero
 *   means nothing landed, which turns the exit-1 warning back into a failure.
 */
fun classifyTarExit(exitCode: Int, stagedBytes: Long): TarOutcome = when (exitCode) {
    0 -> TarOutcome.Succeeded
    1 -> if (stagedBytes > 0L) {
        TarOutcome.SucceededWithWarning(
            "tar reported files that changed while being read; the archive was written anyway"
        )
    } else {
        TarOutcome.Failed("tar exited 1 and wrote nothing")
    }

    else -> TarOutcome.Failed("tar exited $exitCode")
}
```

- [ ] **Step 4: Write the gateway port**

Create `app/src/main/java/com/valhalla/thor/domain/repository/AppDataArchiveGateway.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.ClassEntries
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.TarOutcome
import java.io.File

/**
 * Everything privileged or Android-specific that the archive use cases need, behind one port.
 *
 * Deliberately **not** added to `SystemRepository`: two test files hand-write a full implementation of
 * that interface (`FreezeAppUseCaseTest.kt:33`, `BulkFreezeWorkerTest.kt:121`), so every method added
 * there is a compile error in code that has nothing to do with backup. See deviation 7.
 *
 * Only `File`, `String` and domain types cross this boundary, so both use cases stay JVM-testable
 * against a fake.
 */
interface AppDataArchiveGateway {

    /**
     * The Android multi-user id whose data Thor reads.
     *
     * One value, not a choice: `am get-current-user` is denied without `INTERACT_ACROSS_USERS`, so
     * this is `Process.myUserHandle().hashCode()` — see `data/source/local/ThorUser.kt`. **This is
     * not a Linux uid.** The two are different numbers with the same nickname; [appUid] is the other
     * one.
     */
    suspend fun thorUserId(): Int

    /** `Environment.getExternalStorageDirectory()`, or `""` when it cannot be resolved. */
    suspend fun externalStorageDir(): String

    /**
     * A path in Thor's **internal** cache for the shell to write and Thor to read back.
     *
     * Internal, not `externalCacheDir` (§7.1): the staged file is a plaintext tar of someone's app
     * data, and on shared storage any all-files-access app could read it. The OBB feature staged
     * externally because Thor's own uid had to *write* there; here the shell writes and Thor only
     * reads, and root can write anywhere.
     */
    suspend fun stagingFile(name: String): File

    /**
     * `am force-stop`, once per job (§7.2 step 4).
     *
     * Not per class: stopping the app four times gives it three chances to be restarted by a
     * broadcast in between.
     */
    suspend fun forceStop(packageName: String)

    /** `ls -A` the class root and run the reply through `filterBackupEntries`. */
    suspend fun listClass(packageName: String, dataClass: DataClass): ClassEntries

    /**
     * `tar` [entries] out of the class root into [out], then hand [out] to Thor's own uid.
     *
     * @param compress try `-czf`. The caller retries with `false` on [TarOutcome.Failed] and records
     *   which one worked in the member's `compression` field (§7.2 step 7c).
     */
    suspend fun tarClass(
        packageName: String,
        dataClass: DataClass,
        entries: List<String>,
        out: File,
        compress: Boolean,
    ): TarOutcome

    /**
     * The app's **Linux** uid, read live from `PackageManager`.
     *
     * Null when the package is not installed. Restore must call this *after* the install lands: a
     * reinstalled app gets a new uid, so the archive's numeric owners are always wrong (§8.2).
     */
    suspend fun appUid(packageName: String): Int?

    /**
     * SHA-256 of the app's first signing certificate, uppercase hex, or null if it cannot be read.
     *
     * Load-bearing on the restore side: without it, restoring into a same-named but differently
     * signed package is a data-exfiltration primitive.
     */
    suspend fun signerSha256(packageName: String): String?
}
```

- [ ] **Step 5: Write the gateway implementation**

Create `app/src/main/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImpl.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.ClassEntries
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.TarOutcome
import com.valhalla.thor.domain.model.chownFileCommand
import com.valhalla.thor.domain.model.classifyTarExit
import com.valhalla.thor.domain.model.dataClassRoot
import com.valhalla.thor.domain.model.filterBackupEntries
import com.valhalla.thor.domain.model.listClassEntriesCommand
import com.valhalla.thor.domain.model.tarCreateCommand
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.Logger
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

private const val TAG = "AppDataArchiveGateway"

/** Where the shell writes the per-class tars. One directory so a crashed job's leftovers are findable. */
private const val STAGING_DIR = "data_archive_staging"

@Single(binds = [AppDataArchiveGateway::class])
class AppDataArchiveGatewayImpl(
    private val context: Context,
    private val packageManager: PackageManager,
    private val systemRepository: SystemRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : AppDataArchiveGateway {

    override suspend fun thorUserId(): Int = thorUserId

    override suspend fun externalStorageDir(): String = withContext(ioDispatcher) {
        Environment.getExternalStorageDirectory()?.absolutePath ?: ""
    }

    override suspend fun stagingFile(name: String): File = withContext(ioDispatcher) {
        val dir = File(context.cacheDir, STAGING_DIR)
        dir.mkdirs()
        File(dir, name)
    }

    override suspend fun forceStop(packageName: String) {
        // Routed through the same executeShellCommand every other command uses, so it follows the
        // active gateway rather than assuming root. A failure is logged, not fatal: an app that was
        // not running produces one, and refusing the backup over it would refuse most backups.
        systemRepository.executeShellCommand("am force-stop '$packageName'")
            .onFailure { Logger.e(TAG, "force-stop of $packageName failed", it) }
    }

    override suspend fun listClass(packageName: String, dataClass: DataClass): ClassEntries {
        val root = dataClassRoot(dataClass, packageName, thorUserId(), externalStorageDir())
            ?: return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)
        val command = listClassEntriesCommand(root)
            ?: return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)

        val (exitCode, output) = systemRepository.executeShellCommand(command).getOrElse {
            Logger.e(TAG, "listing ${dataClass.id} for $packageName failed", it)
            return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)
        }
        if (exitCode != 0 || output == null) {
            // Reported as absent rather than empty-and-fine: the caller writes no member either way,
            // but `rootAbsent` is what earns a warning in the header.
            return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)
        }
        return filterBackupEntries(dataClass, output)
    }

    override suspend fun tarClass(
        packageName: String,
        dataClass: DataClass,
        entries: List<String>,
        out: File,
        compress: Boolean,
    ): TarOutcome {
        val root = dataClassRoot(dataClass, packageName, thorUserId(), externalStorageDir())
            ?: return TarOutcome.Failed("no usable path for ${dataClass.id}")
        val command = tarCreateCommand(root, out.absolutePath, entries, compress)
            ?: return TarOutcome.Failed("refused to build a tar command for ${dataClass.id}")

        val (exitCode, _) = systemRepository.executeShellCommand(command).getOrElse {
            return TarOutcome.Failed("tar could not be run: ${it.message}")
        }

        // Read the length *after* tar exits, and on the IO dispatcher — this is a stat call.
        val staged = withContext(ioDispatcher) { if (out.isFile) out.length() else 0L }
        val outcome = classifyTarExit(exitCode, staged)
        if (outcome is TarOutcome.Failed) {
            // A partial tar must never survive to be encrypted: it would restore a truncated tree
            // over the app's real data.
            withContext(ioDispatcher) { out.delete() }
            return outcome
        }

        // The shell created the file as its own uid, so Thor cannot open it yet. 600, because the
        // contents are plaintext app data.
        val chown = chownFileCommand(out.absolutePath, android.os.Process.myUid())
            ?: return TarOutcome.Failed("refused to build a chown command for ${out.name}")
        systemRepository.executeShellCommand(chown).onFailure {
            Logger.e(TAG, "chown of ${out.name} failed", it)
        }
        return if (withContext(ioDispatcher) { out.canRead() }) {
            outcome
        } else {
            withContext(ioDispatcher) { out.delete() }
            TarOutcome.Failed("the staged archive for ${dataClass.id} could not be read back")
        }
    }

    override suspend fun appUid(packageName: String): Int? = withContext(ioDispatcher) {
        runCatching { packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
    }

    override suspend fun signerSha256(packageName: String): String? = withContext(ioDispatcher) {
        runCatching {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                // The *current* signer only. `signingCertificateHistory` would let a key rotation
                // match an archive taken before the rotation, which is the one thing this check is
                // for. `apkContentsSigners` is the current set.
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            }
            val first = signatures?.firstOrNull() ?: return@runCatching null
            MessageDigest.getInstance("SHA-256")
                .digest(first.toByteArray())
                .joinToString(separator = "") { byte -> "%02X".format(byte) }
        }.getOrElse {
            Logger.e(TAG, "reading the signer of $packageName failed", it)
            null
        }
    }
}
```

Note the two uid-shaped things in that file, which are different numbers: `thorUserId` is the Android **multi-user id** that appears in `/data/user/<id>/`, while `android.os.Process.myUid()` in the `chown` is Thor's **Linux uid**. Swapping them produces a `chown` that silently hands the staged tar to the wrong owner, and Thor then cannot open its own file.

- [ ] **Step 6: Write the measure use case**

Create `app/src/main/java/com/valhalla/thor/domain/usecase/MeasureAppDataUseCase.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.isUsablePackageName
import com.valhalla.thor.domain.repository.AppDataProbe
import org.koin.core.annotation.Factory

/**
 * What the backup sheet shows before the user commits to anything.
 *
 * @param supported false when the active channel cannot read private data at all. The sheet then
 *   disables the control and states the reason — it does **not** say "requires Root", because a
 *   root-started Shizuku passes and a Dhizuku device-owner does not (§6).
 */
data class AppDataMeasurement(
    val supported: Boolean,
    val sizes: Map<DataClass, DataClassSize>,
)

@Factory
class MeasureAppDataUseCase(private val probe: AppDataProbe) {

    suspend operator fun invoke(packageName: String): AppDataMeasurement {
        // Checked here as well as inside every command builder. The builders refuse individually and
        // would each cost a round trip to say so; and a package name this shape means the caller is
        // confused, not that one class is unreadable.
        if (!isUsablePackageName(packageName)) {
            return AppDataMeasurement(supported = false, sizes = emptyMap())
        }
        if (!probe.probeDataArchiveCapability()) {
            // Deliberately no measurements: four shell round trips that will each fail, rendered as
            // four "unknown" rows, is a worse answer than one honest refusal.
            return AppDataMeasurement(supported = false, sizes = emptyMap())
        }
        // Sequential, not parallel. These are `du -s -k` walks over potentially gigabytes; four at
        // once on one privileged shell queue is slower, not faster, and the shell serialises anyway.
        val sizes = DataClass.entries.associateWith { dataClass ->
            probe.measureDataClass(packageName, dataClass)
        }
        return AppDataMeasurement(supported = true, sizes = sizes)
    }
}
```

`isUsablePackageName` is `internal` in `AppDataCommands.kt` (same module, same `domain.model` package), so this import resolves. If it does not, the file was written with a different visibility — make it `internal`, not `public`.

- [ ] **Step 7: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.TarOutcomeTest" --tests "com.valhalla.thor.domain.usecase.MeasureAppDataUseCaseTest"
```

Expected: PASS, 10 tests (6 + 4), counted from the XML.

- [ ] **Step 8: Build**

```
./gradlew :app:assembleFossDebug
```

Expected: success. `AppDataArchiveGatewayImpl` has no JVM test — it is `Context`, `PackageManager` and `Environment` — and its logic is the command builders and `classifyTarExit`, both already pinned. Koin's `strictSafety` proves the binding.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/TarOutcome.kt \
  app/src/main/java/com/valhalla/thor/domain/repository/AppDataArchiveGateway.kt \
  app/src/main/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImpl.kt \
  app/src/main/java/com/valhalla/thor/domain/usecase/MeasureAppDataUseCase.kt \
  app/src/test/java/com/valhalla/thor/domain/model/TarOutcomeTest.kt \
  app/src/test/java/com/valhalla/thor/domain/usecase/MeasureAppDataUseCaseTest.kt
git commit -m "feat(backup): privileged archive gateway and per-class sizing"
```

---

### Task 10: `BackupAppArchiveUseCase` — the §7.2 sequence

Spec §7.2, §7.3, §7.4. This is the task where the whole backup half becomes real: one zip stream held open across every class, so peak disk is the largest single class rather than the sum.

**The single most important property, and the easiest to break:** `destination.output` is opened once and the `ZipOutputStream` wraps it for the whole run. Every member is appended to that one stream, and each staged tar is deleted before the next class starts. Writing all four tars first and then zipping them would be simpler code and would need four times the free space.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/ArchiveBackupRequest.kt`
- Create: `app/src/main/java/com/valhalla/thor/domain/usecase/BackupAppArchiveUseCase.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/ArchiveBackupRequestTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/usecase/BackupAppArchiveUseCaseTest.kt`

**Interfaces:**
- Consumes: `DataClass`, `ArchiveHeader`, `ArchiveMember`, `ArchiveKdf`, `ArchiveBundleInfo`, `ArchiveSkip`, `ArchiveCompression`, `THORBAK_HEADER_ENTRY`, `THORBAK_BUNDLE_ENTRY`, `thorbakFileName` (Task 2); `TarOutcome`, `AppDataArchiveGateway` (Task 9); `AppArchiveCipher`, `MemberStats`, `KDF_ITERATIONS` (Task 4); `AppDataProbe`, `DataClassSize` (Task 6); `AppArchiveStore`, `ArchiveDestination` (Task 7); `ThorJobProgress`, `ThorJobStage` (Task 8); `AppBundleBuilder`, `AppRepository`, `SystemRepository.probeObb` (existing).
- Produces: `ArchiveBackupRequest(packageName, classes, includeBundle, salt)` with `toMap()`/`fromMap(Map<String, Any?>)`; `ArchiveBackupOutcome` (`Completed(fileName, header, destinationLabel)` / `Failed(reason)` / `NoDestination`); `BackupAppArchiveUseCase(gateway, archiveStore, cipher, probe)` with `suspend operator fun invoke(request: ArchiveBackupRequest, key: SecretKey, bundle: File? = null, bundleObbCapture: String = "none", bundleObbCount: Int = 0, versionCode: Long = 0L, versionName: String? = null, usableStagingBytes: Long = 0L, onProgress: (ThorJobProgress) -> Unit = {}): ArchiveBackupOutcome`; `ARCHIVE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024` in `ArchiveBackupRequest.kt` (`BackupAppsUseCase`'s identical constant is in a **private** companion, so it cannot be reused; widening a shipped, tested class's visibility to share one number is the worse trade).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/valhalla/thor/domain/model/ArchiveBackupRequestTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This map becomes a `WorkRequest`'s input `Data`, which WorkManager writes to **SQLite**. The tests
 * that matter are therefore as much about what is *absent* as about what round trips.
 */
class ArchiveBackupRequestTest {

    private val request = ArchiveBackupRequest(
        packageName = "com.example.app",
        classes = setOf(DataClass.CE, DataClass.EXTERNAL_MEDIA),
        includeBundle = true,
        salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16),
    )

    @Test
    fun `a request round trips through the map`() {
        val restored = ArchiveBackupRequest.fromMap(request.toMap())!!

        assertEquals(request.packageName, restored.packageName)
        assertEquals(request.classes, restored.classes)
        assertEquals(request.includeBundle, restored.includeBundle)
        assertTrue(request.salt.contentEquals(restored.salt))
    }

    @Test
    fun `no value in the map is the passphrase or the key`() {
        // The contract this whole type exists to enforce. WorkManager persists input `Data` to its
        // database, so anything here is on disk in the clear and stays there until the job is pruned.
        // The salt is *not* secret — it is published in `thorbak.json` — so it may travel.
        val values = request.toMap().values.map { it.toString() }

        assertTrue(values.toString(), values.none { it.contains("pass", ignoreCase = true) })
        assertEquals(4, request.toMap().size)
    }

    @Test
    fun `every value is a type WorkManager Data accepts`() {
        // `Data` takes String, Boolean, Int, Long, Double, their arrays, and nothing else. A `Set` or
        // a `DataClass` would throw at `putAll` — at enqueue time, in production, not here.
        val accepted = setOf(String::class.java, Boolean::class.javaObjectType, Array<String>::class.java)

        request.toMap().forEach { (dataKey, value) ->
            assertTrue("$dataKey is ${value.javaClass}", value.javaClass in accepted)
        }
    }

    @Test
    fun `an unknown class id is dropped rather than crashing the worker`() {
        // A job enqueued by an older Thor, surviving a downgrade. Dropping the class it names is
        // recoverable; throwing inside `fromMap` is a worker that fails before it can say why.
        val map = request.toMap().toMutableMap()
        map[BACKUP_CLASSES_KEY] = arrayOf("ce", "ce-from-the-future")

        val restored = ArchiveBackupRequest.fromMap(map)!!

        assertEquals(setOf(DataClass.CE), restored.classes)
    }

    @Test
    fun `a request with no recognisable class is refused`() {
        // Not "back up nothing" — an archive with no members is a file that looks like a backup and
        // restores nothing.
        val map = request.toMap().toMutableMap()
        map[BACKUP_CLASSES_KEY] = arrayOf<String>()

        assertNull(ArchiveBackupRequest.fromMap(map))
    }

    @Test
    fun `a map missing the package name is refused`() {
        val map = request.toMap().toMutableMap()
        map.remove(BACKUP_PACKAGE_KEY)

        assertNull(ArchiveBackupRequest.fromMap(map))
    }

    @Test
    fun `a map whose salt is not a salt is refused`() {
        // Base64 that decodes to the wrong length would derive a key that no reader can reproduce, and
        // the failure would appear at *restore* time, months later.
        val map = request.toMap().toMutableMap()
        map[BACKUP_SALT_KEY] = "not base64 at all !!"

        assertNull(ArchiveBackupRequest.fromMap(map))
    }
}
```

Create `app/src/test/java/com/valhalla/thor/domain/usecase/BackupAppArchiveUseCaseTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.domain.model.ArchiveBackupOutcome
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveCompression
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveSkip
import com.valhalla.thor.domain.model.ClassEntries
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.TarOutcome
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.ArchiveDestination
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The use case is JVM-testable because every Android-specific thing it needs is behind
 * [AppDataArchiveGateway] or [AppArchiveStore]. `AppArchiveCipher` is used **for real** — PBKDF2 and
 * AES-GCM are JCE, not Android — so these tests exercise the actual framing.
 *
 * A four-iteration KDF keeps the suite fast; the shipped 210,000 is pinned by `AppArchiveCipherTest`.
 */
class BackupAppArchiveUseCaseTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = AppArchiveCipher()

    /** Collects the container in memory so a test can unzip it and see what was written. */
    private class RecordingDestination : ArchiveDestination {
        val bytes = ByteArrayOutputStream()
        var published = false
        var discarded = false

        override val output: OutputStream get() = bytes

        override suspend fun publish(): Boolean {
            published = true
            return true
        }

        override suspend fun discard() {
            discarded = true
        }
    }

    private class FakeStore(private val destination: ArchiveDestination?) : AppArchiveStore {
        var openedName: String? = null

        override suspend fun openArchive(fileName: String): ArchiveDestination? {
            openedName = fileName
            return destination
        }

        override suspend fun currentTargetLabel(): String = "Downloads/Thor"
    }

    /**
     * @param tarBehaviour what each class's `tar` does. A class absent from the map is reported as an
     *   absent root, which is how "this app has no external media" arrives.
     */
    private inner class FakeGateway(
        private val entries: Map<DataClass, List<String>> = emptyMap(),
        private val tarBehaviour: Map<DataClass, TarOutcome> = emptyMap(),
        private val skips: List<ArchiveSkip> = emptyList(),
    ) : AppDataArchiveGateway {
        var forceStops = 0
        val tarCalls = mutableListOf<Pair<DataClass, Boolean>>()

        override suspend fun thorUserId(): Int = 0

        override suspend fun externalStorageDir(): String = "/storage/emulated/0"

        override suspend fun stagingFile(name: String): File = temp.newFile(name)

        override suspend fun forceStop(packageName: String) {
            forceStops++
        }

        override suspend fun listClass(packageName: String, dataClass: DataClass): ClassEntries {
            val kept = entries[dataClass] ?: return ClassEntries(emptyList(), skips, rootAbsent = true)
            return ClassEntries(kept = kept, skipped = skips, rootAbsent = false)
        }

        override suspend fun tarClass(
            packageName: String,
            dataClass: DataClass,
            entries: List<String>,
            out: File,
            compress: Boolean,
        ): TarOutcome {
            tarCalls += dataClass to compress
            val outcome = tarBehaviour[dataClass] ?: TarOutcome.Succeeded
            if (outcome !is TarOutcome.Failed) out.writeBytes(ByteArray(2048) { it.toByte() })
            return outcome
        }

        override suspend fun appUid(packageName: String): Int? = 10123

        override suspend fun signerSha256(packageName: String): String? = "AB".repeat(32)
    }

    /**
     * §7.4's only input beyond the scalar the caller measures.
     *
     * Defaults to `Undetermined` for every class, which is the fail-open answer — so every test that
     * predates the space check keeps passing unchanged, and the two that care pass a size explicitly.
     */
    private class FakeProbe(
        private val sizes: Map<DataClass, DataClassSize> = emptyMap(),
    ) : AppDataProbe {
        override suspend fun probeDataArchiveCapability(): Boolean = true
        override suspend fun sizeOf(packageName: String, dataClass: DataClass): DataClassSize =
            sizes[dataClass] ?: DataClassSize.Undetermined
    }

    private fun useCase(
        gateway: AppDataArchiveGateway,
        store: AppArchiveStore,
        probe: AppDataProbe = FakeProbe(),
    ) = BackupAppArchiveUseCase(gateway, store, cipher, probe)

    private fun request(vararg classes: DataClass) = ArchiveBackupRequest(
        packageName = "com.example.app",
        classes = classes.toSet(),
        includeBundle = false,
        salt = cipher.newSalt(),
    )

    private fun key() = cipher.deriveKey("hunter2".toCharArray(), ByteArray(16), iterations = 4)

    /** Entry names in the order the container holds them. */
    private fun entryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zip.nextEntry
            }
        }
        return names
    }

    private fun header(bytes: ByteArray): ArchiveHeader {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == THORBAK_HEADER_ENTRY) {
                    return ArchiveHeader.decode(zip.readBytes().decodeToString())
                }
                entry = zip.nextEntry
            }
        }
        error("no $THORBAK_HEADER_ENTRY in the container")
    }

    @Test
    fun `a selected class becomes one encrypted member and one header entry`() = runTest {
        val destination = RecordingDestination()
        val gateway = FakeGateway(entries = mapOf(DataClass.CE to listOf("databases", "files")))

        val outcome = useCase(gateway, FakeStore(destination))(request(DataClass.CE), key()) {}

        assertTrue(outcome.toString(), outcome is ArchiveBackupOutcome.Completed)
        assertTrue(destination.published)
        val names = entryNames(destination.bytes.toByteArray())
        assertEquals(listOf("ce.tar.gz.enc", THORBAK_HEADER_ENTRY), names)
        assertNotNull(header(destination.bytes.toByteArray()).member(DataClass.CE))
    }

    @Test
    fun `the header is the last entry in the container`() = runTest {
        // Load-bearing for the streaming design: the header names every member's nonce and chunk
        // count, and those are only known after the member is written. A reader seeks to it; it must
        // not be first.
        val destination = RecordingDestination()
        val gateway = FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files"), DataClass.DE to listOf("files")),
        )

        useCase(gateway, FakeStore(destination))(request(DataClass.CE, DataClass.DE), key()) {}

        assertEquals(THORBAK_HEADER_ENTRY, entryNames(destination.bytes.toByteArray()).last())
    }

    @Test
    fun `the app is force-stopped exactly once no matter how many classes are selected`() = runTest {
        // §7.2 step 4. Stopping it per class gives it three chances to be restarted in between.
        val gateway = FakeGateway(
            entries = DataClass.entries.associateWith { listOf("files") },
        )

        useCase(gateway, FakeStore(RecordingDestination()))(
            request(*DataClass.entries.toTypedArray()),
            key(),
        ) {}

        assertEquals(1, gateway.forceStops)
    }

    @Test
    fun `a class whose root is absent produces no member`() = runTest {
        // An empty class must not produce an empty tar the restore side has to special-case.
        val destination = RecordingDestination()
        val gateway = FakeGateway(entries = mapOf(DataClass.CE to listOf("files")))

        useCase(gateway, FakeStore(destination))(request(DataClass.CE, DataClass.DE), key()) {}

        val parsed = header(destination.bytes.toByteArray())
        assertNotNull(parsed.member(DataClass.CE))
        assertEquals(null, parsed.member(DataClass.DE))
        assertEquals(listOf(DataClass.CE), parsed.heldClasses())
    }

    @Test
    fun `a gzip tar that fails is retried without compression and recorded as such`() = runTest {
        // §7.2 step 7c. Some toybox builds have no gzip; the member is then stored uncompressed and
        // the header says `none`, so the reader does not try to gunzip it.
        val destination = RecordingDestination()
        var firstCall = true
        val gateway = object : AppDataArchiveGateway by FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files")),
        ) {
            override suspend fun tarClass(
                packageName: String,
                dataClass: DataClass,
                entries: List<String>,
                out: File,
                compress: Boolean,
            ): TarOutcome {
                calls += compress
                return if (compress && firstCall) {
                    firstCall = false
                    TarOutcome.Failed("no gzip on this device")
                } else {
                    out.writeBytes(ByteArray(1024))
                    TarOutcome.Succeeded
                }
            }
        }

        useCase(gateway, FakeStore(destination))(request(DataClass.CE), key()) {}

        val member = header(destination.bytes.toByteArray()).member(DataClass.CE)!!
        assertEquals(ArchiveCompression.NONE.id, member.compression)
        assertEquals("ce.tar.enc", member.fileName)
    }

    @Test
    fun `a tar exit of one with bytes on disk still produces a member, plus a warning`() = runTest {
        // §7.3. This is the common case on live data, not an edge case.
        val destination = RecordingDestination()
        val gateway = FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files")),
            tarBehaviour = mapOf(DataClass.CE to TarOutcome.SucceededWithWarning("files changed")),
        )

        val outcome = useCase(gateway, FakeStore(destination))(request(DataClass.CE), key()) {}

        assertTrue(outcome is ArchiveBackupOutcome.Completed)
        val parsed = header(destination.bytes.toByteArray())
        assertNotNull(parsed.member(DataClass.CE))
        assertTrue(parsed.warnings.toString(), parsed.warnings.any { it.contains("changed") })
    }

    @Test
    fun `every class failing to tar discards the archive rather than publishing an empty one`() =
        runTest {
            val destination = RecordingDestination()
            val gateway = FakeGateway(
                entries = mapOf(DataClass.CE to listOf("files")),
                tarBehaviour = mapOf(DataClass.CE to TarOutcome.Failed("out of space")),
            )

            val outcome = useCase(gateway, FakeStore(destination))(request(DataClass.CE), key()) {}

            assertTrue(outcome.toString(), outcome is ArchiveBackupOutcome.Failed)
            assertFalse("an archive with no members must not be published", destination.published)
            assertTrue(destination.discarded)
        }

    @Test
    fun `refused entry names reach the header instead of vanishing`() = runTest {
        val destination = RecordingDestination()
        val skip = ArchiveSkip(DataClass.CE.id, "bad\nname", "name cannot be passed to the shell safely")
        val gateway = FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files")),
            skips = listOf(skip),
        )

        useCase(gateway, FakeStore(destination))(request(DataClass.CE), key()) {}

        assertEquals(listOf(skip), header(destination.bytes.toByteArray()).skippedEntries)
    }

    @Test
    fun `nowhere to write is its own outcome, not a failure`() = runTest {
        // The sheet turns this into "choose a folder". Reporting it as a failed backup would send the
        // user looking for a problem with their data.
        val outcome = useCase(FakeGateway(), FakeStore(null))(request(DataClass.CE), key()) {}

        assertTrue(outcome.toString(), outcome is ArchiveBackupOutcome.NoDestination)
    }

    @Test
    fun `the staged tar is deleted before the next class is staged`() = runTest {
        // The property that keeps peak disk at one class. Checked by observing that no staging file
        // survives the run.
        val gateway = FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files"), DataClass.DE to listOf("files")),
        )

        useCase(gateway, FakeStore(RecordingDestination()))(
            request(DataClass.CE, DataClass.DE),
            key(),
        ) {}

        val leftovers = temp.root.listFiles()?.filter { it.isFile && it.length() > 0L }.orEmpty()
        assertTrue(leftovers.map { it.name }.toString(), leftovers.isEmpty())
    }

    @Test
    fun `progress is reported for each class and never as a fake zero percent`() = runTest {
        val seen = mutableListOf<Int?>()
        val gateway = FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files"), DataClass.DE to listOf("files")),
        )

        useCase(gateway, FakeStore(RecordingDestination()))(
            request(DataClass.CE, DataClass.DE),
            key(),
        ) { progress -> seen += progress.percent }

        assertTrue(seen.size.toString(), seen.size >= 2)
        // Null is "indeterminate" and is allowed; a literal 0 while work is in flight is the bug the
        // tri-state discipline exists to prevent.
        assertTrue(seen.toString(), seen.none { it == 0 })
    }

    // --- §7.4 pre-flight space -----------------------------------------------------------------

    @Test
    fun `a class that will not fit is skipped with a warning while the others are captured`() =
        runTest {
            // Per class, not per run: peak disk is one class at a time, so one class that cannot be
            // staged is no reason to abandon the one that can. BackupAppsUseCase rejects the whole
            // batch instead, and is right to — a batch of N apps has nothing partial to salvage.
            val destination = RecordingDestination()
            val gateway = FakeGateway(
                entries = mapOf(
                    DataClass.CE to listOf("files"),
                    DataClass.EXTERNAL_MEDIA to listOf("Pictures"),
                ),
            )
            val probe = FakeProbe(
                mapOf(DataClass.EXTERNAL_MEDIA to DataClassSize.Known(8L * 1024 * 1024 * 1024))
            )

            val outcome = useCase(gateway, FakeStore(destination), probe)(
                request = request(DataClass.CE, DataClass.EXTERNAL_MEDIA),
                key = key(),
                usableStagingBytes = 512L * 1024 * 1024,
            ) as ArchiveBackupOutcome.Completed

            assertEquals(listOf(DataClass.CE.id), outcome.header.members.map { it.dataClass })
            assertTrue(
                outcome.header.warnings.toString(),
                outcome.header.warnings.any { it.contains(DataClass.EXTERNAL_MEDIA.id) },
            )
            // Skipped, never silently omitted: a class the user ticked and did not get has to be
            // findable in the header.
            assertTrue(gateway.tarCalls.none { it.first == DataClass.EXTERNAL_MEDIA })
        }

    @Test
    fun `a partition that cannot be measured captures everything`() = runTest {
        // Fails open, exactly as BackupAppsUseCase does. Refusing on a number we could not read would
        // block devices that had room all along.
        val gateway = FakeGateway(entries = mapOf(DataClass.CE to listOf("files")))
        val probe = FakeProbe(mapOf(DataClass.CE to DataClassSize.Known(8L * 1024 * 1024 * 1024)))

        val outcome = useCase(gateway, FakeStore(RecordingDestination()), probe)(
            request = request(DataClass.CE),
            key = key(),
            usableStagingBytes = 0L,
        ) as ArchiveBackupOutcome.Completed

        assertEquals(listOf(DataClass.CE.id), outcome.header.members.map { it.dataClass })
    }

    @Test
    fun `a class whose size is undetermined is captured rather than refused`() = runTest {
        // `du` declining to answer is not evidence the class is too big — the same discipline that
        // forbids rendering Undetermined as 0 B.
        val gateway = FakeGateway(entries = mapOf(DataClass.CE to listOf("files")))

        val outcome = useCase(gateway, FakeStore(RecordingDestination()), FakeProbe())(
            request = request(DataClass.CE),
            key = key(),
            usableStagingBytes = 1024L,
        ) as ArchiveBackupOutcome.Completed

        assertEquals(listOf(DataClass.CE.id), outcome.header.members.map { it.dataClass })
    }
}
```

The three new tests use named arguments for `usableStagingBytes` because `invoke`'s trailing parameters are all defaulted — passing it positionally would land it in `bundle`.

- [ ] **Step 2: Run the tests to verify they fail**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.ArchiveBackupRequestTest" --tests "com.valhalla.thor.domain.usecase.BackupAppArchiveUseCaseTest"
```

Expected: compilation failure — `ArchiveBackupRequest`, `ArchiveBackupOutcome`, `BackupAppArchiveUseCase`, `BACKUP_*_KEY` unresolved.

- [ ] **Step 3: Write the request model**

Create `app/src/main/java/com/valhalla/thor/domain/model/ArchiveBackupRequest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import java.util.Base64

const val BACKUP_PACKAGE_KEY = "thor.backup.package"
const val BACKUP_CLASSES_KEY = "thor.backup.classes"
const val BACKUP_BUNDLE_KEY = "thor.backup.bundle"
const val BACKUP_SALT_KEY = "thor.backup.salt"

/**
 * §7.4's headroom: room for the container being written and for ordinary cache churn from the rest of
 * the app while a long run is in flight.
 *
 * The same 64 MB `BackupAppsUseCase` uses, copied rather than shared because that constant sits in a
 * **private** companion — widening a shipped, tested class's visibility to publish one number is the
 * worse trade. If either value ever moves, both are `git grep`-able from this comment.
 */
const val ARCHIVE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024

/**
 * Everything the backup worker needs that is safe to persist.
 *
 * **What is not here is the point.** This becomes a `WorkRequest`'s input `Data`, which WorkManager
 * writes to its SQLite database — so a passphrase or a derived key placed here would be on disk in the
 * clear, surviving until the job is pruned. The key travels through `ArchiveKeyHolder`, in memory.
 *
 * [salt] *is* here, deliberately. A KDF salt is not a secret: it is published in `thorbak.json`, where
 * every reader needs it to derive the same key. Its job is to make one reused passphrase produce a
 * different key per archive, and that works in the open.
 */
data class ArchiveBackupRequest(
    val packageName: String,
    val classes: Set<DataClass>,
    val includeBundle: Boolean,
    val salt: ByteArray,
) {

    /**
     * Values are limited to the types `androidx.work.Data` accepts — String, Boolean and `Array<String>`
     * here. A `Set` or an enum would throw at `putAll`, at enqueue time in production.
     *
     * `java.util.Base64`, not `android.util.Base64`: the latter is a stubbed framework class under JVM
     * tests and throws "not mocked", which would make this whole type untestable. minSdk is 28 and
     * `java.util.Base64` is API 26.
     */
    fun toMap(): Map<String, Any> = mapOf(
        BACKUP_PACKAGE_KEY to packageName,
        BACKUP_CLASSES_KEY to classes.map { it.id }.toTypedArray(),
        BACKUP_BUNDLE_KEY to includeBundle,
        BACKUP_SALT_KEY to Base64.getEncoder().encodeToString(salt),
    )

    // A ByteArray field means the generated equals/hashCode compare identity, which silently breaks
    // any assertEquals on this type. Overridden so the data class behaves the way its call sites read.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArchiveBackupRequest) return false
        return packageName == other.packageName &&
            classes == other.classes &&
            includeBundle == other.includeBundle &&
            salt.contentEquals(other.salt)
    }

    override fun hashCode(): Int {
        var result = packageName.hashCode()
        result = 31 * result + classes.hashCode()
        result = 31 * result + includeBundle.hashCode()
        result = 31 * result + salt.contentHashCode()
        return result
    }

    companion object {

        /**
         * @return null when the map cannot describe a runnable backup. The worker turns that into
         *   `Result.failure()` with a reason — never `Result.retry()`, which would re-read the same
         *   unusable map forever.
         */
        fun fromMap(map: Map<String, Any?>): ArchiveBackupRequest? {
            val packageName = (map[BACKUP_PACKAGE_KEY] as? String)?.takeIf { it.isNotBlank() }
                ?: return null
            val ids = (map[BACKUP_CLASSES_KEY] as? Array<*>)?.mapNotNull { it as? String } ?: return null
            // An id this Thor does not know is dropped, not fatal: a job enqueued by a newer build and
            // run after a downgrade should still back up the classes it *can*.
            val classes = ids.mapNotNull { id -> DataClass.entries.firstOrNull { it.id == id } }.toSet()
            if (classes.isEmpty()) return null
            val salt = runCatching { Base64.getDecoder().decode(map[BACKUP_SALT_KEY] as? String ?: "") }
                .getOrNull()
                ?.takeIf { it.size == KDF_SALT_BYTES }
                ?: return null
            return ArchiveBackupRequest(
                packageName = packageName,
                classes = classes,
                includeBundle = map[BACKUP_BUNDLE_KEY] as? Boolean ?: false,
                salt = salt,
            )
        }
    }
}

/** What a backup run amounted to. Three outcomes, because "nowhere to write" is not a failure. */
sealed interface ArchiveBackupOutcome {
    data class Completed(
        val fileName: String,
        val header: ArchiveHeader,
        val destinationLabel: String,
    ) : ArchiveBackupOutcome

    data class Failed(val reason: String) : ArchiveBackupOutcome

    /** No SAF tree and no writable Downloads. The UI says "choose a folder". */
    data object NoDestination : ArchiveBackupOutcome
}
```

**Two constants move layers in this step.** `KDF_SALT_BYTES` and `KDF_ITERATIONS` were declared in `AppArchiveCipher.kt` (`com.valhalla.thor.data.backup`), but both are now needed by `domain` code — `fromMap` validates the salt length, and the header records the iteration count — and a `domain` file importing from `data` inverts the layering the module is built on. So:

1. Delete `KDF_SALT_BYTES` and `KDF_ITERATIONS` from `AppArchiveCipher.kt`.
2. Declare them at the top of `ArchiveBackupRequest.kt`: `const val KDF_SALT_BYTES = 16` and `const val KDF_ITERATIONS = 210_000`, each keeping the KDoc it had.
3. Add `import com.valhalla.thor.domain.model.KDF_ITERATIONS` and `import com.valhalla.thor.domain.model.KDF_SALT_BYTES` to `AppArchiveCipher.kt`.
4. `AppArchiveCipherTest`'s `assertEquals(210_000, KDF_ITERATIONS)` and its salt-length assertion now import from `com.valhalla.thor.domain.model` — the assertions themselves do not change, and **must not be deleted**: they are what stops a future "make the tests faster" commit from lowering the shipped iteration count.

The remaining cipher constants (`MEMBER_NONCE_BYTES`, `CHUNK_PLAINTEXT_BYTES`, `VERIFIER_BYTES`, `KDF_ALGORITHM`) stay in `AppArchiveCipher.kt`; nothing in `domain` reads them.

- [ ] **Step 4: Write the use case**

Create `app/src/main/java/com/valhalla/thor/domain/usecase/BackupAppArchiveUseCase.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.domain.model.ARCHIVE_SPACE_MARGIN_BYTES
import com.valhalla.thor.domain.model.ArchiveBackupOutcome
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveBundleInfo
import com.valhalla.thor.domain.model.ArchiveCompression
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveKdf
import com.valhalla.thor.domain.model.ArchiveMember
import com.valhalla.thor.domain.model.ArchiveSkip
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.KDF_ITERATIONS
import com.valhalla.thor.domain.model.TarOutcome
import com.valhalla.thor.domain.model.THORBAK_BUNDLE_ENTRY
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.model.thorbakFileName
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.util.Logger
import java.io.File
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey
import org.koin.core.annotation.Factory

private const val TAG = "BackupAppArchive"

/**
 * §7.2, as one function.
 *
 * The invariant that shapes the whole body: **one zip stream, held open across every class.** Members
 * are appended to it and each staged tar is deleted as soon as it has been encrypted into it, so peak
 * disk is the largest single class rather than the sum of all four. Staging every tar first and then
 * zipping would be shorter and would need four times the space.
 *
 * Nothing here touches WorkManager. The worker owns the job lifecycle; this owns the sequence, which is
 * why it is testable at all.
 */
@Factory
class BackupAppArchiveUseCase(
    private val gateway: AppDataArchiveGateway,
    private val archiveStore: AppArchiveStore,
    private val cipher: AppArchiveCipher,
    /** §7.4 only: the pre-flight space check needs a size before it stages a class. */
    private val probe: AppDataProbe,
) {

    /**
     * @param key derived by the caller from the passphrase and [ArchiveBackupRequest.salt], handed over
     *   in memory. This function never sees a passphrase.
     * @param onProgress called on the calling coroutine. The worker forwards it to `JobRegistry`.
     * @param bundle an already-built `.xapk` to embed, or null. Built by the caller because
     *   `AppBundleBuilder` needs an `AppInfo`, which is the worker's to resolve.
     * @param bundleObbCapture `ObbProbe`'s tri-state name for what the bundle holds, and
     *   [bundleObbCount] how many `.obb` files it carries. Recorded verbatim so restore never implies
     *   game data it does not have.
     * @param usableStagingBytes §7.4. Free bytes on the staging volume, **measured by the caller** —
     *   the same division of labour as [com.valhalla.thor.domain.usecase.BackupAppsUseCase], where the
     *   number comes from `data` (which has a `Context`) and the rule lives here. `0` means "could not
     *   be measured", and the rule fails open on it, which is why `0` is also the default: a caller
     *   that does not measure gets today's behaviour rather than a refusal.
     */
    suspend operator fun invoke(
        request: ArchiveBackupRequest,
        key: SecretKey,
        bundle: File? = null,
        bundleObbCapture: String = "none",
        bundleObbCount: Int = 0,
        versionCode: Long = 0L,
        versionName: String? = null,
        usableStagingBytes: Long = 0L,
        onProgress: (ThorJobProgress) -> Unit = {},
    ): ArchiveBackupOutcome {
        val fileName = thorbakFileName(request.packageName, versionCode)
        val destination = archiveStore.openArchive(fileName) ?: return ArchiveBackupOutcome.NoDestination

        // Read before anything is written. Without a signer the archive cannot carry the check that
        // stops a restore into a same-named, differently-signed package, and an archive missing that
        // field is one a later Thor would have to either refuse or trust.
        val signer = gateway.signerSha256(request.packageName)
        if (signer == null) {
            destination.discard()
            return ArchiveBackupOutcome.Failed("the app's signing certificate could not be read")
        }

        val members = mutableListOf<ArchiveMember>()
        val skipped = mutableListOf<ArchiveSkip>()
        val warnings = mutableListOf<String>()
        var published = false

        try {
            onProgress(ThorJobProgress(ThorJobStage.PREPARING, request.packageName))
            // §7.2 step 4: once, before the first class. Not per class.
            gateway.forceStop(request.packageName)

            // Level 0: the members are ciphertext and the bundle is already compressed, so deflate
            // would spend CPU to occasionally grow the file. STORED is not an option — it demands the
            // CRC before `putNextEntry`, which is unknowable for a stream being generated.
            val zip = ZipOutputStream(destination.output).apply { setLevel(Deflater.NO_COMPRESSION) }

            if (bundle != null) {
                onProgress(ThorJobProgress(ThorJobStage.CAPTURING, bundle.name))
                zip.putNextEntry(ZipEntry(THORBAK_BUNDLE_ENTRY))
                bundle.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            // Iterated in DataClass order, not the request's set order, so two runs over the same
            // selection produce members in the same order.
            val selected = DataClass.entries.filter { it in request.classes }
            for ((index, dataClass) in selected.withIndex()) {
                onProgress(
                    ThorJobProgress(
                        stage = ThorJobStage.CAPTURING,
                        label = dataClass.id,
                        completedBytes = index.toLong(),
                        totalBytes = selected.size.toLong(),
                    )
                )

                // §7.4, per class rather than per run: peak disk is one class, so one class that will
                // not fit is not a reason to abandon the three that would. Recorded as a warning and
                // skipped, exactly as an unreadable root is — never a silent omission.
                val refusal = spaceRefusal(request.packageName, dataClass, usableStagingBytes)
                if (refusal != null) {
                    warnings += "${dataClass.id}: $refusal"
                    continue
                }

                val listing = gateway.listClass(request.packageName, dataClass)
                skipped += listing.skipped
                if (listing.rootAbsent) {
                    warnings += "${dataClass.id}: the directory could not be read or does not exist"
                    continue
                }
                // §7.2 step 7a: an empty class root produces no member at all.
                if (listing.kept.isEmpty()) continue

                val member = captureClass(request, dataClass, listing.kept, key, zip, warnings)
                    ?: continue
                members += member
            }

            if (members.isEmpty()) {
                // An archive with no members is a file that looks like a backup and restores nothing.
                return ArchiveBackupOutcome.Failed("no data could be captured for ${request.packageName}")
            }

            onProgress(ThorJobProgress(ThorJobStage.FINISHING, request.packageName))
            val header = ArchiveHeader(
                createdAt = System.currentTimeMillis(),
                thorVersionCode = BuildConfig.VERSION_CODE,
                packageName = request.packageName,
                versionCode = versionCode,
                versionName = versionName,
                userId = gateway.thorUserId(),
                signerSha256 = signer,
                appBundle = bundle?.let {
                    ArchiveBundleInfo(
                        bytes = it.length(),
                        obbCapture = bundleObbCapture,
                        obbCount = bundleObbCount,
                    )
                },
                kdf = ArchiveKdf(
                    iterations = KDF_ITERATIONS,
                    salt = Base64.getEncoder().encodeToString(request.salt),
                ),
                verifier = Base64.getEncoder().encodeToString(cipher.verifier(key)),
                members = members,
                skippedEntries = skipped,
                warnings = warnings,
            )

            // The header is the **last** entry, because it names every member's nonce and chunk count
            // and those are only known once the member is written. This is the one entry that can be
            // STORED: it is in memory, so its size and CRC are both known — but level-0 deflate is
            // already in force for the stream, and mixing methods buys nothing.
            val headerBytes = header.encode().encodeToByteArray()
            zip.putNextEntry(ZipEntry(THORBAK_HEADER_ENTRY))
            zip.write(headerBytes)
            zip.closeEntry()
            // `finish()`, never `close()`. `close()` would close `destination.output` underneath the
            // destination, which owns that stream and closes it inside `publish()`. `finish()` writes
            // the central directory — without it the container has no index and unzips as empty.
            zip.finish()

            published = destination.publish()
            return if (published) {
                ArchiveBackupOutcome.Completed(
                    fileName = fileName,
                    header = header,
                    destinationLabel = archiveStore.currentTargetLabel(),
                )
            } else {
                ArchiveBackupOutcome.Failed("the archive could not be moved to its final name")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "backup of ${request.packageName} failed", e)
            return ArchiveBackupOutcome.Failed(e.message ?: "the backup failed")
        } finally {
            // Runs on cancellation too. A `.part` left behind is fine — the launch-time sweep removes
            // it — but a *published* `.thorbak` that is half-written is not, so discard is
            // unconditional here and idempotent in `BaseDestination`.
            if (!published) destination.discard()
        }
    }

    /**
     * Stage one class as a tar, encrypt it into [zip], delete the tar.
     *
     * @return the member to record, or null when the class could not be captured. A failure here is
     *   per-class: an unreadable `Android/media` must not lose the CE data already in the container.
     */
    private suspend fun captureClass(
        request: ArchiveBackupRequest,
        dataClass: DataClass,
        entries: List<String>,
        key: SecretKey,
        zip: ZipOutputStream,
        warnings: MutableList<String>,
    ): ArchiveMember? {
        // Named by class, so a crashed job leaves one findable file per class rather than a temp name.
        val staged = gateway.stagingFile("${request.packageName}-${dataClass.id}.tar")
        try {
            var compressed = true
            var outcome = gateway.tarClass(request.packageName, dataClass, entries, staged, compress = true)
            if (outcome is TarOutcome.Failed) {
                // §7.2 step 7c: some toybox builds have no gzip. Retry without it and record which one
                // worked, so the reader does not try to gunzip a plain tar.
                compressed = false
                outcome = gateway.tarClass(request.packageName, dataClass, entries, staged, compress = false)
            }
            when (outcome) {
                is TarOutcome.Failed -> {
                    warnings += "${dataClass.id}: ${outcome.reason}"
                    return null
                }

                is TarOutcome.SucceededWithWarning -> warnings += "${dataClass.id}: ${outcome.warning}"
                TarOutcome.Succeeded -> Unit
            }

            val memberName = dataClass.memberName(compressed)
            val nonce = cipher.newNonce()
            zip.putNextEntry(ZipEntry(memberName))
            val stats = staged.inputStream().use { input ->
                cipher.encryptMember(memberName, input, zip, key, nonce)
            }
            zip.closeEntry()

            return ArchiveMember(
                dataClass = dataClass.id,
                fileName = memberName,
                nonce = Base64.getEncoder().encodeToString(nonce),
                plainBytes = stats.plainBytes,
                chunkCount = stats.chunkCount,
                compression = if (compressed) ArchiveCompression.GZIP.id else ArchiveCompression.NONE.id,
            )
        } finally {
            // §7.2 step 7e — the line that keeps peak disk at one class. Deleting it in `finally` means
            // a cancellation mid-encrypt does not leave a plaintext tar of app data in Thor's cache.
            staged.delete()
        }
    }

    /**
     * §7.4. Why this class cannot be staged, or null to go ahead.
     *
     * Follows `BackupAppsUseCase.checkStagingSpace`'s rule verbatim, including the part that looks like
     * a bug and is not: `usableStagingBytes > 0 &&` means an unmeasurable partition **fails open**.
     * Refusing on a number we could not read would block working devices, and the real safety net is
     * downstream — `tar` exits nonzero when it runs out of room and the staged file is deleted on any
     * failure.
     *
     * An [DataClassSize.Undetermined] size also fails open, for the same reason: `du` not answering is
     * not evidence that the class is too big.
     */
    private suspend fun spaceRefusal(
        packageName: String,
        dataClass: DataClass,
        usableStagingBytes: Long,
    ): String? {
        if (usableStagingBytes <= 0L) return null
        val size = probe.sizeOf(packageName, dataClass)
        if (size !is DataClassSize.Known) return null
        val required = size.bytes + ARCHIVE_SPACE_MARGIN_BYTES
        return if (usableStagingBytes < required) {
            "needs about ${required / (1024 * 1024)} MB free to stage and only " +
                "${usableStagingBytes / (1024 * 1024)} MB is available"
        } else {
            null
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.ArchiveBackupRequestTest" --tests "com.valhalla.thor.domain.usecase.BackupAppArchiveUseCaseTest"
```

Expected: PASS, 21 tests (7 + 14), counted from the XML.

If `the staged tar is deleted before the next class is staged` fails, the `finally` in `captureClass` was folded into the `try` in `invoke` — that makes deletion happen once at the end, which is the bug the test exists to catch.

- [ ] **Step 6: Build**

```
./gradlew :app:assembleFossDebug
```

Expected: success.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/ArchiveBackupRequest.kt \
  app/src/main/java/com/valhalla/thor/domain/usecase/BackupAppArchiveUseCase.kt \
  app/src/main/java/com/valhalla/thor/data/backup/AppArchiveCipher.kt \
  app/src/test/java/com/valhalla/thor/data/backup/AppArchiveCipherTest.kt \
  app/src/test/java/com/valhalla/thor/domain/model/ArchiveBackupRequestTest.kt \
  app/src/test/java/com/valhalla/thor/domain/usecase/BackupAppArchiveUseCaseTest.kt
git commit -m "feat(backup): the §7.2 capture sequence, one class of peak disk at a time"
```

---

### Task 11: The restore gate (§8.1) and the restore-half shell surface (§8.3)

Two pure files, both fully JVM-tested, before anything destructive is written. The gate is the security boundary; the commands are the destructive boundary.

**Naming constraint:** `RestoreRequest.kt` already exists in this codebase and owns the bare word *restore* in the installer's sense. Nothing in this task may be named `Restore*` — hence `ArchiveRestoreGate`, `ArchiveRestoreDecision`, `ArchiveRestoreRefusal`, `ArchiveRestoreWarning`.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/ArchiveRestoreGate.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/model/AppDataCommands.kt` (append the restore-half builders)
- Test: `app/src/test/java/com/valhalla/thor/domain/model/ArchiveRestoreGateTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/AppDataRestoreCommandsTest.kt`

**Interfaces:**
- Consumes: `ArchiveHeader`, `DataClass` (Task 2); `isQuotableAbsolutePath` (Task 3, already `internal` in the same file).
- Produces: `InstalledAppFacts(signerSha256, versionCode, versionName)`; `ArchiveRestoreDecision` (`Allowed(installFirst, warnings)` / `Refused(reason)`); `ArchiveRestoreRefusal`; `ArchiveRestoreWarning`; `evaluateArchiveRestoreGate(header, installed, selectedClasses)`; and in `AppDataCommands.kt`: `STAGING_DIR_NAME`, `stagingDirPath(root)`, `extractCommand(root, tarPath, compressed)`, `swapStagedEntriesCommand(root)`, `chownRecursiveCommand(root, uid)`, `restoreconCommand(root)`.

- [ ] **Step 1: Write the failing gate tests**

Create `app/src/test/java/com/valhalla/thor/domain/model/ArchiveRestoreGateTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8.1's table, row by row. Every row gets a named test, so a change to the table is a change to a
 * test name rather than a silently relaxed check.
 */
class ArchiveRestoreGateTest {

    private val signer = "AB".repeat(32)

    private fun header(
        versionCode: Long = 100L,
        signerSha256: String = signer,
        withBundle: Boolean = true,
        classes: List<DataClass> = listOf(DataClass.CE, DataClass.DE),
    ) = ArchiveHeader(
        createdAt = 1_000L,
        thorVersionCode = 1950,
        packageName = "com.example.app",
        versionCode = versionCode,
        userId = 0,
        signerSha256 = signerSha256,
        appBundle = if (withBundle) ArchiveBundleInfo(bytes = 10L, obbCapture = "none", obbCount = 0) else null,
        kdf = ArchiveKdf(iterations = 210_000, salt = "c2FsdHNhbHRzYWx0c2E="),
        verifier = "dmVyaWZpZXI=",
        members = classes.map { dataClass ->
            ArchiveMember(
                dataClass = dataClass.id,
                fileName = dataClass.memberName(compressed = true),
                nonce = "bm9uY2U=",
                plainBytes = 10L,
                chunkCount = 1,
                compression = ArchiveCompression.GZIP.id,
            )
        },
    )

    private fun installed(
        versionCode: Long = 100L,
        signerSha256: String? = signer,
    ) = InstalledAppFacts(signerSha256 = signerSha256, versionCode = versionCode, versionName = "1.0")

    @Test
    fun `an absent app with a bundle installs first, and that is not a refusal`() {
        val decision = evaluateArchiveRestoreGate(header(), installed = null, setOf(DataClass.CE, DataClass.DE))

        val allowed = decision as ArchiveRestoreDecision.Allowed
        assertTrue(allowed.installFirst)
    }

    @Test
    fun `an absent app with no bundle is refused, and the reason says the archive is data-only`() {
        val decision = evaluateArchiveRestoreGate(
            header(withBundle = false),
            installed = null,
            setOf(DataClass.CE, DataClass.DE),
        )

        assertEquals(
            ArchiveRestoreRefusal.DATA_ONLY_AND_APP_ABSENT,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }

    @Test
    fun `a signer mismatch is refused with no override`() {
        // The one absolute refusal. Without it, sideloading a fake `com.whatsapp` and restoring into it
        // reads out everything the real one held.
        val decision = evaluateArchiveRestoreGate(
            header(),
            installed(signerSha256 = "CD".repeat(32)),
            setOf(DataClass.CE),
        )

        assertEquals(
            ArchiveRestoreRefusal.SIGNER_MISMATCH,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }

    @Test
    fun `a signer that could not be read is refused, not waved through`() {
        // "I could not check" is not "it matches". This is the tri-state discipline applied to the one
        // check that exists to stop data exfiltration.
        val decision = evaluateArchiveRestoreGate(header(), installed(signerSha256 = null), setOf(DataClass.CE))

        assertEquals(
            ArchiveRestoreRefusal.SIGNER_UNVERIFIABLE,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }

    @Test
    fun `signer comparison ignores hex case`() {
        // Both sides are produced by Thor today, but a header written by a future build — or read from
        // a file a user edited — must not fail on casing and be reported as an attack.
        val decision = evaluateArchiveRestoreGate(
            header(signerSha256 = signer.lowercase()),
            installed(signerSha256 = signer),
            setOf(DataClass.CE),
        )

        assertTrue(decision.toString(), decision is ArchiveRestoreDecision.Allowed)
    }

    @Test
    fun `an installed version older than the archive warns hard but is allowed`() {
        // Newer data on older code is the classic permanent-crash-on-launch. The user is told, in those
        // words, and may proceed.
        val decision = evaluateArchiveRestoreGate(header(versionCode = 200L), installed(versionCode = 100L), setOf(DataClass.CE))

        val allowed = decision as ArchiveRestoreDecision.Allowed
        assertTrue(allowed.warnings.toString(), ArchiveRestoreWarning.INSTALLED_VERSION_OLDER in allowed.warnings)
    }

    @Test
    fun `an installed version newer than the archive proceeds quietly`() {
        // Forward migration is what apps are built for. A warning here would train users to ignore
        // warnings.
        val decision = evaluateArchiveRestoreGate(header(versionCode = 100L), installed(versionCode = 200L), setOf(DataClass.CE))

        val allowed = decision as ArchiveRestoreDecision.Allowed
        assertFalse(
            allowed.warnings.toString(),
            ArchiveRestoreWarning.INSTALLED_VERSION_OLDER in allowed.warnings,
        )
    }

    @Test
    fun `an equal version proceeds quietly`() {
        val decision = evaluateArchiveRestoreGate(header(versionCode = 100L), installed(versionCode = 100L), setOf(DataClass.CE))

        assertTrue((decision as ArchiveRestoreDecision.Allowed).warnings.isEmpty())
    }

    @Test
    fun `CE selected without DE warns, because DE carries first-run state`() {
        val decision = evaluateArchiveRestoreGate(header(), installed(), setOf(DataClass.CE))

        val allowed = decision as ArchiveRestoreDecision.Allowed
        assertTrue(allowed.warnings.toString(), ArchiveRestoreWarning.CE_WITHOUT_DE in allowed.warnings)
    }

    @Test
    fun `CE without DE does not warn when the archive holds no DE member`() {
        // The warning is about a *choice* the user made. An archive with nothing to select cannot be
        // faulted for the selection, and warning anyway is noise the user cannot act on.
        val decision = evaluateArchiveRestoreGate(
            header(classes = listOf(DataClass.CE)),
            installed(),
            setOf(DataClass.CE),
        )

        val allowed = decision as ArchiveRestoreDecision.Allowed
        assertFalse(allowed.warnings.toString(), ArchiveRestoreWarning.CE_WITHOUT_DE in allowed.warnings)
    }

    @Test
    fun `selecting a class the archive does not hold is refused`() {
        // Not silently dropped: the sheet built its checkboxes from `heldClasses()`, so a selection
        // outside that set means the caller and the header disagree about the file.
        val decision = evaluateArchiveRestoreGate(
            header(classes = listOf(DataClass.CE)),
            installed(),
            setOf(DataClass.CE, DataClass.EXTERNAL_MEDIA),
        )

        assertEquals(
            ArchiveRestoreRefusal.CLASS_NOT_IN_ARCHIVE,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }

    @Test
    fun `selecting nothing is refused`() {
        val decision = evaluateArchiveRestoreGate(header(), installed(), emptySet())

        assertEquals(
            ArchiveRestoreRefusal.NOTHING_SELECTED,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }

    @Test
    fun `an archive from a newer schema is refused rather than half-read`() {
        val future = header().copy(schemaVersion = ARCHIVE_SCHEMA_VERSION + 1)

        val decision = evaluateArchiveRestoreGate(future, installed(), setOf(DataClass.CE))

        assertEquals(
            ArchiveRestoreRefusal.SCHEMA_TOO_NEW,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }

    @Test
    fun `an absent app is not signer-checked, because there is no signer yet`() {
        // Order matters: checking the signer first would refuse every install-then-restore, which §8.1
        // explicitly calls "not a refusal". The install path re-checks after the install lands.
        val decision = evaluateArchiveRestoreGate(header(), installed = null, setOf(DataClass.CE, DataClass.DE))

        assertTrue(decision.toString(), decision is ArchiveRestoreDecision.Allowed)
    }
}
```

- [ ] **Step 2: Write the failing restore-command tests**

Create `app/src/test/java/com/valhalla/thor/domain/model/AppDataRestoreCommandsTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The destructive half. Every one of these commands runs as root against a real app's data directory,
 * so the same rule as `ObbPlacementTest` applies: an input that cannot be quoted produces **no
 * command**, not a quoted-and-hoped-for-the-best one.
 */
class AppDataRestoreCommandsTest {

    private val root = "/data/user/0/com.example.app"

    @Test
    fun `the staging directory is a hidden child of the class root`() {
        assertEquals("$root/.thorbak-staging", stagingDirPath(root))
    }

    @Test
    fun `extraction creates the staging directory and extracts into it`() {
        val command = extractCommand(root, "/data/data/com.valhalla.thor/cache/x/ce.tar", compressed = true)!!

        assertTrue(command, command.contains("mkdir -p '$root/.thorbak-staging'"))
        assertTrue(command, command.contains("-C '$root/.thorbak-staging'"))
        assertTrue(command, command.contains("'/data/data/com.valhalla.thor/cache/x/ce.tar'"))
    }

    @Test
    fun `extraction uses the flags matching how the member was written`() {
        assertTrue(extractCommand(root, "/tmp/a.tar", compressed = true)!!.contains("-xzf"))
        assertTrue(extractCommand(root, "/tmp/a.tar", compressed = false)!!.contains("-xf"))
    }

    @Test
    fun `extraction refuses a staging directory that is a symlink`() {
        // `mkdir -p` succeeds silently on a symlink to a directory, and the extraction would then land
        // wherever it points — with root's privilege, from a path the target app owns.
        val command = extractCommand(root, "/tmp/a.tar", compressed = true)!!

        assertTrue(command, command.contains("[ ! -L '$root/.thorbak-staging' ]"))
    }

    @Test
    fun `the swap deletes every entry in the class root except the staging directory`() {
        // The one thing a naive `rm -rf <root>/*` destroys is the staging directory holding the data
        // being restored — mid-restore, after the original is already gone.
        val command = swapStagedEntriesCommand(root)!!

        assertTrue(command, command.contains("! -name '.thorbak-staging'"))
        assertTrue(command, command.contains("-mindepth 1"))
        assertTrue(command, command.contains("-maxdepth 1"))
    }

    @Test
    fun `the name the swap protects is exactly the name the extraction creates`() {
        // Two string literals that must agree. If they drift, the swap deletes the staged data and the
        // restore reports success over an empty directory.
        val extract = extractCommand(root, "/tmp/a.tar", compressed = true)!!
        val swap = swapStagedEntriesCommand(root)!!

        assertTrue(extract.contains("'$root/$STAGING_DIR_NAME'"))
        assertTrue(swap.contains("! -name '$STAGING_DIR_NAME'"))
    }

    @Test
    fun `the swap moves entries with find rather than a glob`() {
        // A shell glob does not match dotfiles, and app data is full of them — `.config`, `.cache`,
        // per-library dot directories. `mv <staging>/* <root>/` silently leaves every one behind.
        val command = swapStagedEntriesCommand(root)!!

        assertTrue(command, command.contains("find '$root/.thorbak-staging'"))
        assertTrue(command, command.contains("mv"))
    }

    @Test
    fun `the swap removes the staging directory when it is done`() {
        assertTrue(swapStagedEntriesCommand(root)!!.contains("rmdir '$root/.thorbak-staging'"))
    }

    @Test
    fun `chown is recursive and applies one id to both owner and group`() {
        assertEquals("chown -R 10123:10123 '$root'", chownRecursiveCommand(root, 10123))
    }

    @Test
    fun `restorecon is recursive and forced`() {
        // -F, not just -R: without the force, an already-labelled file keeps whatever context it was
        // extracted with, and the app still cannot read it. Omitting this is the most common reason a
        // restore "succeeds" and the app crashes on launch.
        assertEquals("restorecon -RF '$root'", restoreconCommand(root))
    }

    @Test
    fun `every restore command refuses a root that is not a quotable absolute path`() {
        val hostile = listOf("/data/user/0/it's", "relative/path", "/data\n/user", "")

        hostile.forEach { bad ->
            assertNull(bad, stagingDirPath(bad))
            assertNull(bad, extractCommand(bad, "/tmp/a.tar", compressed = true))
            assertNull(bad, swapStagedEntriesCommand(bad))
            assertNull(bad, chownRecursiveCommand(bad, 10123))
            assertNull(bad, restoreconCommand(bad))
        }
    }

    @Test
    fun `extraction refuses a tar path that is not a quotable absolute path`() {
        assertNull(extractCommand(root, "cache/a.tar", compressed = true))
        assertNull(extractCommand(root, "/tmp/it's.tar", compressed = true))
        assertNull(extractCommand(root, "", compressed = true))
    }

    @Test
    fun `chown refuses a negative uid`() {
        // `appUid` returns null for a missing package; a caller that turned that into -1 would emit
        // `chown -R -1:-1`, which some toybox builds accept as an option.
        assertNull(chownRecursiveCommand(root, -1))
    }
}
```

- [ ] **Step 3: Run both test files to verify they fail**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.ArchiveRestoreGateTest" --tests "com.valhalla.thor.domain.model.AppDataRestoreCommandsTest"
```

Expected: compilation failure — `evaluateArchiveRestoreGate`, `InstalledAppFacts`, `stagingDirPath`, `extractCommand`, `swapStagedEntriesCommand`, `chownRecursiveCommand`, `restoreconCommand`, `STAGING_DIR_NAME` unresolved.

- [ ] **Step 4: Write the gate**

Create `app/src/main/java/com/valhalla/thor/domain/model/ArchiveRestoreGate.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * What `PackageManager` says about the app right now.
 *
 * @param signerSha256 null when it could not be read. **Not the same as "no signer"** — the gate
 *   refuses on null rather than treating an unverifiable app as a match.
 */
data class InstalledAppFacts(
    val signerSha256: String?,
    val versionCode: Long,
    val versionName: String?,
)

/** Why a restore will not be attempted. Every one of these is shown to the user in words. */
enum class ArchiveRestoreRefusal {
    /** The installed app is signed by a different key. No override exists for this one. */
    SIGNER_MISMATCH,

    /** The installed app's signer could not be read, so the mismatch check could not run. */
    SIGNER_UNVERIFIABLE,

    /** The app is not installed and the archive holds no `.xapk` to install it from. */
    DATA_ONLY_AND_APP_ABSENT,

    /** A selected class has no member in this archive. */
    CLASS_NOT_IN_ARCHIVE,

    NOTHING_SELECTED,

    /** Written by a newer Thor. Reading it partially would restore an incomplete tree. */
    SCHEMA_TOO_NEW,
}

/** A condition the user is told about and may proceed through. */
enum class ArchiveRestoreWarning {
    /** Newer data onto older code — the classic permanent-crash-on-launch. */
    INSTALLED_VERSION_OLDER,

    /** `DE` holds first-run state; restoring `CE` alone can leave the app in a half-migrated state. */
    CE_WITHOUT_DE,
}

sealed interface ArchiveRestoreDecision {

    /**
     * @param installFirst the app is absent and will be installed from the archive's `.xapk` before
     *   any data is written. §8.1 is explicit that this is not a refusal.
     */
    data class Allowed(
        val installFirst: Boolean,
        val warnings: List<ArchiveRestoreWarning>,
    ) : ArchiveRestoreDecision

    data class Refused(val reason: ArchiveRestoreRefusal) : ArchiveRestoreDecision
}

/**
 * §8.1's table as one function.
 *
 * @param installed null when the app is not installed. That is the branch that must be tested
 *   **before** the signer, because an absent app has no signer and checking it first would refuse
 *   every install-then-restore.
 */
fun evaluateArchiveRestoreGate(
    header: ArchiveHeader,
    installed: InstalledAppFacts?,
    selectedClasses: Set<DataClass>,
): ArchiveRestoreDecision {
    if (header.schemaVersion > ARCHIVE_SCHEMA_VERSION) {
        return ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.SCHEMA_TOO_NEW)
    }
    if (selectedClasses.isEmpty()) {
        return ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.NOTHING_SELECTED)
    }
    val held = header.heldClasses().toSet()
    if (!held.containsAll(selectedClasses)) {
        return ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.CLASS_NOT_IN_ARCHIVE)
    }

    val warnings = mutableListOf<ArchiveRestoreWarning>()
    // The warning is about the user's *selection*, so it only applies when DE was there to select.
    if (DataClass.CE in selectedClasses &&
        DataClass.DE !in selectedClasses &&
        DataClass.DE in held
    ) {
        warnings += ArchiveRestoreWarning.CE_WITHOUT_DE
    }

    if (installed == null) {
        return if (header.appBundle == null) {
            ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.DATA_ONLY_AND_APP_ABSENT)
        } else {
            // No version warning: the version about to be installed *is* the archive's.
            ArchiveRestoreDecision.Allowed(installFirst = true, warnings = warnings)
        }
    }

    val installedSigner = installed.signerSha256
        ?: return ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.SIGNER_UNVERIFIABLE)
    if (!installedSigner.equals(header.signerSha256, ignoreCase = true)) {
        return ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.SIGNER_MISMATCH)
    }

    if (installed.versionCode < header.versionCode) {
        warnings += ArchiveRestoreWarning.INSTALLED_VERSION_OLDER
    }
    // An installed version *newer* than the archive gets no warning at all. Forward migration is what
    // apps are built for, and a warning on the common case trains users past the one that matters.

    return ArchiveRestoreDecision.Allowed(installFirst = false, warnings = warnings)
}
```

- [ ] **Step 5: Append the restore commands**

Append to `app/src/main/java/com/valhalla/thor/domain/model/AppDataCommands.kt`:

```kotlin
/**
 * The directory a restore extracts into, inside the class root it is replacing.
 *
 * Inside the class root, not in cache, so the promotion in [swapStagedEntriesCommand] is a series of
 * same-filesystem renames rather than a second full copy. Hidden, so a user who looks at the directory
 * mid-restore does not see it as app content.
 *
 * **This literal appears in two commands** — the extract creates it, the swap excludes it from the
 * deletion. If the two ever disagree, the swap deletes the staged data after the original is already
 * gone. `AppDataRestoreCommandsTest` pins them to each other for that reason.
 */
const val STAGING_DIR_NAME = ".thorbak-staging"

internal fun stagingDirPath(root: String): String? {
    if (!isQuotableAbsolutePath(root)) return null
    return "$root/$STAGING_DIR_NAME"
}

/**
 * Extract a decrypted tar into the staging directory under [root].
 *
 * @param compressed must match the member's recorded `compression`. Guessing would either fail on a
 *   plain tar or, worse, succeed partially.
 *
 * The `-L` test is not redundant with `mkdir -p`: `mkdir -p` exits 0 when the path is a symlink to a
 * directory, and the extraction would then write through it with root's privilege, into a path the
 * target app controls.
 */
internal fun extractCommand(root: String, tarPath: String, compressed: Boolean): String? {
    val staging = stagingDirPath(root) ?: return null
    if (!isQuotableAbsolutePath(tarPath)) return null
    val flags = if (compressed) "-xzf" else "-xf"
    return "mkdir -p '$staging' && [ ! -L '$staging' ] && tar $flags '$tarPath' -C '$staging'"
}

/**
 * Replace the class root's contents with the staged extraction, then remove the staging directory.
 *
 * Three properties, each of which has a test:
 *
 * - The deletion **excludes [STAGING_DIR_NAME]**. A `rm -rf <root>/*` would delete the very directory
 *   holding the data being restored, after the original is already gone.
 * - Both halves use `find`, not a glob. A shell glob does not match dotfiles, and app data is full of
 *   them; `mv <staging>/* <root>/` silently leaves every dot entry behind.
 * - `-mindepth 1 -maxdepth 1` so the walk is one level: the entries, not their contents, and not the
 *   root itself.
 *
 * `-exec … +` for the delete (one `rm` for many paths) and `-exec … \;` for the move (`mv` needs its
 * destination last). Both forms are on the toybox checklist for exactly this reason.
 */
internal fun swapStagedEntriesCommand(root: String): String? {
    val staging = stagingDirPath(root) ?: return null
    return "find '$root' -mindepth 1 -maxdepth 1 ! -name '$STAGING_DIR_NAME' -exec rm -rf {} + && " +
        "find '$staging' -mindepth 1 -maxdepth 1 -exec mv -f {} '$root/' \\; && " +
        "rmdir '$staging'"
}

/**
 * Give the whole class root to the app's **live Linux uid**.
 *
 * A reinstalled app has a *new* uid, so the numeric owners inside the archive are always wrong; the
 * caller reads this from `PackageManager` **after** the install lands (§8.2). Not called for the two
 * external classes: `Android/data` on FUSE has synthesized ownership and `chown` there is meaningless.
 */
internal fun chownRecursiveCommand(root: String, uid: Int): String? {
    if (!isQuotableAbsolutePath(root)) return null
    // A negative uid is what `appUid()`'s null becomes if a caller coerces it. `chown -R -1:-1` is
    // parsed as an option by some toybox builds.
    if (uid < 0) return null
    return "chown -R $uid:$uid '$root'"
}

/**
 * Relabel the restored tree for SELinux.
 *
 * `-F` as well as `-R`: without the force, a file that already carries a context keeps whatever it was
 * extracted with, and the app still cannot read it. Skipping this step is the most common reason a
 * restore reports success and the app crashes on launch — which is precisely the failure mode this
 * feature exists to avoid.
 */
internal fun restoreconCommand(root: String): String? {
    if (!isQuotableAbsolutePath(root)) return null
    return "restorecon -RF '$root'"
}
```

- [ ] **Step 6: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.ArchiveRestoreGateTest" --tests "com.valhalla.thor.domain.model.AppDataRestoreCommandsTest"
```

Expected: PASS, 27 tests (14 gate + 13 commands), counted from the XML.

Task 3's `every command builder in this file refuses an unquotable path` test uses reflection over the file's declared methods, so it will now also cover the five new builders. If it reports a name it does not recognise, widen the match rather than excluding the method.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/ArchiveRestoreGate.kt \
  app/src/main/java/com/valhalla/thor/domain/model/AppDataCommands.kt \
  app/src/test/java/com/valhalla/thor/domain/model/ArchiveRestoreGateTest.kt \
  app/src/test/java/com/valhalla/thor/domain/model/AppDataRestoreCommandsTest.kt
git commit -m "feat(backup): restore gates and the destructive shell surface, both pure"
```

---

### Task 12: Reading a `.thorbak` back — `ArchiveSource`, header read, passphrase unlock

Task 10 wrote the container. This task reads it, and stops short of anything destructive: the restore screen must be able to show a user what an archive holds, and reject a wrong passphrase, before Tasks 13 and 14 touch a single app directory.

Three shapes, in order of how much Android they need:

1. `ArchiveSource` — a domain port in `File`/`String` terms, the same discipline as `AppArchiveStore` (Task 7): no `android.net.Uri` anywhere in it.
2. `ZipArchiveSource` — the implementation, over a `java.io.File`. **JVM-testable**, which is the whole reason the `Uri` resolution is a separate class.
3. `UriArchiveSourceFactory` — the ~30 lines that are genuinely Android: `content://` → `ParcelFileDescriptor` → `/proc/self/fd/N`.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/repository/ArchiveSource.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/repository/ZipArchiveSource.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/repository/UriArchiveSourceFactory.kt`
- Create: `app/src/main/java/com/valhalla/thor/domain/usecase/OpenArchiveUseCase.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/repository/ZipArchiveSourceTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/usecase/OpenArchiveUseCaseTest.kt`

**Interfaces:**
- Consumes: `ArchiveHeader`, `ArchiveHeader.decode`, `ArchiveKdf`, `THORBAK_HEADER_ENTRY`, `ARCHIVE_SCHEMA_VERSION` (Task 2); `AppArchiveCipher.deriveKey`, `AppArchiveCipher.verifier`, `KDF_SALT_BYTES` (Tasks 4 and 10).
- Produces: `ArchiveSource` (`displayName`, `entryNames()`, `openEntry(name): InputStream?`, `close()`); `ArchiveSourceFactory.open(uriString): ArchiveSource?`; `ArchiveHeaderOutcome` (`Read(header)` / `NotAnArchive(reason)`); `ArchiveUnlockOutcome` (`Unlocked(key)` / `WrongPassphrase` / `Unsupported(reason)`); `OpenArchiveUseCase.readHeader(source)`, `OpenArchiveUseCase.unlock(header, passphrase)`; `MAX_KDF_ITERATIONS`.

- [ ] **Step 1: Write the failing `ZipArchiveSource` tests**

Create `app/src/test/java/com/valhalla/thor/data/repository/ZipArchiveSourceTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArchiveSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = temp.newFile("a.thorbak")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, body) ->
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `entry names come back in the order the container stores them`() {
        // The header is written last (Task 10), so a reader that assumed "first entry" would find a
        // data member instead.
        val source = ZipArchiveSource(zip("ce.tar.gz.enc" to "x", "thorbak.json" to "{}"), "a.thorbak")

        source.use { assertEquals(listOf("ce.tar.gz.enc", "thorbak.json"), it.entryNames()) }
    }

    @Test
    fun `an entry opens by exact name`() {
        val source = ZipArchiveSource(zip("thorbak.json" to "{\"a\":1}"), "a.thorbak")

        source.use {
            assertEquals("{\"a\":1}", it.openEntry("thorbak.json")!!.readBytes().decodeToString())
        }
    }

    @Test
    fun `a name that is not in the container returns null rather than throwing`() {
        // "no DE member" is an ordinary, expected answer — a header can legitimately hold three
        // classes out of four. Throwing would make the common case an exception path.
        val source = ZipArchiveSource(zip("thorbak.json" to "{}"), "a.thorbak")

        source.use { assertNull(it.openEntry("de.tar.gz.enc")) }
    }

    @Test
    fun `lookup is by exact name, so a traversal entry name is unreachable`() {
        // Nothing in the restore path ever writes a file named after a zip entry — every destination
        // is computed from the *class*, not from the container. This test pins that: an entry called
        // `../../evil` is visible in the listing and openable only under its literal name, and no
        // caller asks for that name.
        val source = ZipArchiveSource(zip("../../evil" to "x", "thorbak.json" to "{}"), "a.thorbak")

        source.use {
            assertTrue(it.entryNames().contains("../../evil"))
            assertNull(it.openEntry("evil"))
        }
    }

    @Test
    fun `two entries can be read in sequence from one source`() {
        // `ZipFile`, not `ZipInputStream`: the header is read first to learn the member list, then the
        // members are read. A sequential-only reader would need a second full pass over the file.
        val source = ZipArchiveSource(zip("a" to "one", "b" to "two"), "a.thorbak")

        source.use {
            assertEquals("one", it.openEntry("a")!!.readBytes().decodeToString())
            assertEquals("two", it.openEntry("b")!!.readBytes().decodeToString())
        }
    }

    @Test
    fun `close runs the caller's cleanup exactly once`() {
        // The cleanup closes the `ParcelFileDescriptor` the factory opened. Running it twice would
        // close an fd number the process may have already reused for something else.
        var closes = 0
        val source = ZipArchiveSource(zip("a" to "x"), "a.thorbak", onClose = { closes++ })

        source.close()
        source.close()

        assertEquals(1, closes)
    }

    @Test
    fun `a file that is not a zip fails at construction, not on first read`() {
        val notAZip = temp.newFile("b.thorbak").apply { writeText("this is not a zip") }

        assertThrows(IOException::class.java) { ZipArchiveSource(notAZip, "b.thorbak") }
    }
}
```

- [ ] **Step 2: Write the failing `OpenArchiveUseCase` tests**

Create `app/src/test/java/com/valhalla/thor/domain/usecase/OpenArchiveUseCaseTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.domain.model.ARCHIVE_SCHEMA_VERSION
import com.valhalla.thor.domain.model.ArchiveBundleInfo
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveKdf
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.repository.ArchiveSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64

class OpenArchiveUseCaseTest {

    private val cipher = AppArchiveCipher()
    private val useCase = OpenArchiveUseCase(cipher)
    private val salt = ByteArray(16) { it.toByte() }

    /** In-memory [ArchiveSource]. The port is `File`/`String`-only precisely so this is four lines. */
    private class FakeSource(private val entries: Map<String, ByteArray>) : ArchiveSource {
        override val displayName = "fake.thorbak"
        override fun entryNames() = entries.keys.toList()
        override fun openEntry(name: String): InputStream? = entries[name]?.let(::ByteArrayInputStream)
        override fun close() = Unit
    }

    private fun header(iterations: Int = 4, passphrase: String = "correct horse"): ArchiveHeader {
        val key = cipher.deriveKey(passphrase.toCharArray(), salt, iterations)
        return ArchiveHeader(
            createdAt = 1_000L,
            thorVersionCode = 1950,
            packageName = "com.example.app",
            versionCode = 100L,
            userId = 0,
            signerSha256 = "AB".repeat(32),
            appBundle = ArchiveBundleInfo(bytes = 4L, obbCapture = "none", obbCount = 0),
            kdf = ArchiveKdf(iterations = iterations, salt = Base64.getEncoder().encodeToString(salt)),
            verifier = Base64.getEncoder().encodeToString(cipher.verifier(key)),
            members = emptyList(),
        )
    }

    private fun sourceFor(header: ArchiveHeader) =
        FakeSource(mapOf(THORBAK_HEADER_ENTRY to header.encode().toByteArray()))

    @Test
    fun `a well-formed container yields its header`() = runTest {
        val expected = header()

        val outcome = useCase.readHeader(sourceFor(expected))

        assertEquals(expected, (outcome as ArchiveHeaderOutcome.Read).header)
    }

    @Test
    fun `a container with no header entry is not an archive`() = runTest {
        val outcome = useCase.readHeader(FakeSource(mapOf("app.xapk" to byteArrayOf(1))))

        val reason = (outcome as ArchiveHeaderOutcome.NotAnArchive).reason
        // The message names the entry, because "not a Thor backup" on a file the user believes is one
        // is the moment they need to know what Thor looked for.
        assertTrue(reason, reason.contains(THORBAK_HEADER_ENTRY))
    }

    @Test
    fun `a header that is not valid JSON is not an archive`() = runTest {
        val outcome = useCase.readHeader(FakeSource(mapOf(THORBAK_HEADER_ENTRY to "{ nope".toByteArray())))

        assertTrue(outcome.toString(), outcome is ArchiveHeaderOutcome.NotAnArchive)
    }

    @Test
    fun `the header is read without a passphrase`() = runTest {
        // §8.1: the restore screen shows package, version, date and classes held *before* it asks for
        // anything. If reading the header needed the key, the gate could not run first.
        val outcome = useCase.readHeader(sourceFor(header()))

        assertEquals("com.example.app", (outcome as ArchiveHeaderOutcome.Read).header.packageName)
    }

    @Test
    fun `the right passphrase unlocks the archive`() = runTest {
        val outcome = useCase.unlock(header(), "correct horse".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unlocked)
    }

    @Test
    fun `a wrong passphrase is rejected after one derivation, before any member is read`() = runTest {
        val outcome = useCase.unlock(header(), "wrong horse".toCharArray())

        assertEquals(ArchiveUnlockOutcome.WrongPassphrase, outcome)
    }

    @Test
    fun `the key that comes back is the one the verifier matched`() = runTest {
        val head = header()

        val key = (useCase.unlock(head, "correct horse".toCharArray()) as ArchiveUnlockOutcome.Unlocked).key

        assertEquals(head.verifier, Base64.getEncoder().encodeToString(cipher.verifier(key)))
    }

    @Test
    fun `an absurd iteration count is refused instead of derived`() = runTest {
        // A header is attacker-controlled data. `iterations = 2_000_000_000` is a PBKDF2 call that
        // never returns, on the UI thread's coroutine, with a progress notification already showing.
        val outcome = useCase.unlock(header().copy(kdf = ArchiveKdf(MAX_KDF_ITERATIONS + 1, "AAAA")), "x".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unsupported)
    }

    @Test
    fun `a non-positive iteration count is refused`() = runTest {
        val outcome = useCase.unlock(header().copy(kdf = ArchiveKdf(0, "AAAA")), "x".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unsupported)
    }

    @Test
    fun `a salt that is not base64 is refused rather than throwing`() = runTest {
        val outcome = useCase.unlock(header().copy(kdf = ArchiveKdf(4, "not base64 !!")), "x".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unsupported)
    }

    @Test
    fun `a salt of the wrong length is refused rather than passed to deriveKey`() = runTest {
        // `deriveKey` has a `require` on the salt length, and an IllegalArgumentException escaping a
        // worker is a crash, not a message.
        val short = Base64.getEncoder().encodeToString(ByteArray(8))

        val outcome = useCase.unlock(header().copy(kdf = ArchiveKdf(4, short)), "x".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unsupported)
    }

    @Test
    fun `a verifier that is not base64 is refused rather than throwing`() = runTest {
        val outcome = useCase.unlock(header().copy(verifier = "not base64 !!"), "correct horse".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unsupported)
    }

    @Test
    fun `unlock does not clear the caller's passphrase`() = runTest {
        // The vault owns that array and may still need it to `remember` the passphrase on success.
        // `deriveKey`'s KDoc says the caller clears it; this pins that unlock did not.
        val passphrase = "correct horse".toCharArray()

        useCase.unlock(header(), passphrase)

        assertEquals("correct horse", String(passphrase))
    }

    @Test
    fun `the schema version travels on the header for the gate to check`() = runTest {
        // The gate (Task 11) refuses a newer schema. It can only do that if the read does not.
        val future = header().copy(schemaVersion = ARCHIVE_SCHEMA_VERSION + 1)

        val outcome = useCase.readHeader(sourceFor(future))

        assertEquals(
            ARCHIVE_SCHEMA_VERSION + 1,
            (outcome as ArchiveHeaderOutcome.Read).header.schemaVersion,
        )
    }
}
```

- [ ] **Step 3: Run both test files to verify they fail**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.ZipArchiveSourceTest" --tests "com.valhalla.thor.domain.usecase.OpenArchiveUseCaseTest"
```

Expected: compilation failure — `ZipArchiveSource`, `ArchiveSource`, `OpenArchiveUseCase`, `ArchiveHeaderOutcome`, `ArchiveUnlockOutcome`, `MAX_KDF_ITERATIONS` unresolved.

- [ ] **Step 4: Write the domain port**

Create `app/src/main/java/com/valhalla/thor/domain/repository/ArchiveSource.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import java.io.Closeable
import java.io.InputStream

/**
 * A `.thorbak` container open for reading, addressed by **exact entry name**.
 *
 * Random access, not sequential: the header is the container's *last* entry (Task 10 writes it last
 * so its byte counts can be final), and the members are read after it. A `ZipInputStream` view would
 * need a second full pass over a file that can be tens of gigabytes.
 *
 * No `android.net.Uri` here, deliberately — the same rule as [AppArchiveStore]. A port that returns
 * or accepts a `Uri` cannot be faked in a JVM test, because `android.net.Uri` throws "not mocked",
 * and that would take the whole restore happy path off the test classpath.
 */
interface ArchiveSource : Closeable {

    /** What to call this file in a message to the user. Never a path. */
    val displayName: String

    /** Every entry in the container, in stored order. */
    fun entryNames(): List<String>

    /**
     * Open one entry by its exact name, or null if the container has no such entry.
     *
     * Null is an ordinary answer, not an error: a header can legitimately hold three of the four
     * classes.
     */
    fun openEntry(name: String): InputStream?
}

/** Resolves whatever the platform handed Thor — a `content://` URI, usually — into an [ArchiveSource]. */
interface ArchiveSourceFactory {

    /**
     * @param uriString the URI as a string. String rather than `Uri` for the reason in
     *   [ArchiveSource]'s KDoc; the implementation parses it.
     * @return null when the URI cannot be opened or does not contain a zip. The caller reports that
     *   as "this file could not be opened", which is all a user can act on.
     */
    suspend fun open(uriString: String): ArchiveSource?
}
```

- [ ] **Step 5: Write `ZipArchiveSource`**

Create `app/src/main/java/com/valhalla/thor/data/repository/ZipArchiveSource.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.repository.ArchiveSource
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * [ArchiveSource] over a real file.
 *
 * Split from [UriArchiveSourceFactory] so that everything except the `content://` resolution is
 * JVM-testable: this class takes a `java.io.File`, and `ZipFile` is a JDK type.
 *
 * @param onClose runs once, when this source is closed. [UriArchiveSourceFactory] uses it to close
 *   the `ParcelFileDescriptor` whose `/proc/self/fd` entry [file] names — closing that fd twice would
 *   close a number the process may have already reused.
 */
class ZipArchiveSource(
    file: File,
    override val displayName: String,
    private val onClose: () -> Unit = {},
) : ArchiveSource {

    // Constructed eagerly, so a file that is not a zip fails here rather than on the first read —
    // by which point the UI has already told the user the archive is being opened.
    private val zip = ZipFile(file)
    private val closed = AtomicBoolean(false)

    override fun entryNames(): List<String> = zip.entries().toList().map { it.name }

    override fun openEntry(name: String): InputStream? =
        zip.getEntry(name)?.let(zip::getInputStream)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { zip.close() }
        onClose()
    }
}
```

- [ ] **Step 6: Write `UriArchiveSourceFactory`**

Create `app/src/main/java/com/valhalla/thor/data/repository/UriArchiveSourceFactory.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.valhalla.thor.domain.repository.ArchiveSource
import com.valhalla.thor.domain.repository.ArchiveSourceFactory
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File

/**
 * Turns a `content://` URI into a randomly-accessible zip.
 *
 * `ZipFile` needs a path, and a `content://` URI is not one. The route is
 * `openFileDescriptor` → `ParcelFileDescriptor` → `/proc/self/fd/<n>`, which for a provider backed by
 * a regular file is a seekable path to the same inode. That is the cheap case and the common one: no
 * copy, no second disk cost on a file that may be tens of gigabytes.
 *
 * **A provider is not obliged to give a regular file.** `openFileDescriptor` may hand back a pipe —
 * some cloud and media providers do. Opening it succeeds and the first seek fails, so `ZipFile`
 * throws, and the only remaining option is to copy the whole thing into cache first. That costs the
 * archive's size in free space, so it is a fallback with a log line, never the default.
 */
@Single(binds = [ArchiveSourceFactory::class])
class UriArchiveSourceFactory(
    private val context: Context,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : ArchiveSourceFactory {

    override suspend fun open(uriString: String): ArchiveSource? = withContext(ioDispatcher) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
        val name = displayNameOf(uri) ?: uri.lastPathSegment ?: "backup"

        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }
            .getOrNull()
            ?: return@withContext null

        runCatching {
            ZipArchiveSource(
                file = File("/proc/self/fd/${descriptor.fd}"),
                displayName = name,
                onClose = { runCatching { descriptor.close() } },
            )
        }.getOrElse { direct ->
            // Two-argument `w`, because that is the only overload this codebase uses. `Logger` is a
            // typealias onto `thor-extension-api`'s; every `w` call site in `app/` passes tag and
            // message only, and `e` is the one that takes a throwable.
            Logger.w(TAG, "fd path unusable for $name (${direct.message}), copying to cache")
            copyThenOpen(uri, name).also { runCatching { descriptor.close() } }
        }
    }

    /**
     * The fallback. One fixed file name, in Thor's own cache, so the launch-time orphan sweep can
     * delete it by exact name if the process dies mid-read.
     */
    private fun copyThenOpen(uri: Uri, name: String): ArchiveSource? {
        val copy = File(context.cacheDir, COPY_FILE_NAME)
        return runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                copy.outputStream().use(input::copyTo)
            }
            ZipArchiveSource(copy, name, onClose = { copy.delete() })
        }.getOrElse {
            Logger.e(TAG, "could not read $name", it)
            copy.delete()
            null
        }
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    companion object {
        private const val TAG = "UriArchiveSource"

        /** Also named in the orphan sweep (Task 15). Exact name, never a wildcard. */
        const val COPY_FILE_NAME = "thorbak_read_copy.zip"
    }
}
```

- [ ] **Step 7: Write `OpenArchiveUseCase`**

Create `app/src/main/java/com/valhalla/thor/domain/usecase/OpenArchiveUseCase.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.KDF_SALT_BYTES
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.repository.ArchiveSource
import org.koin.core.annotation.Factory
import java.util.Base64
import javax.crypto.SecretKey

/**
 * The ceiling on a header's declared PBKDF2 iteration count.
 *
 * A header is attacker-controlled data. Two billion iterations is a derivation that never returns,
 * inside a worker, with a progress notification already showing — a hang the user can only escape by
 * force-stopping Thor. The ceiling is generous: ~20x the shipped 210,000, so an archive written by a
 * future Thor that raised its own count still opens.
 */
const val MAX_KDF_ITERATIONS = 4_000_000

sealed interface ArchiveHeaderOutcome {
    data class Read(val header: ArchiveHeader) : ArchiveHeaderOutcome

    /** @param reason shown to the user verbatim; it names what Thor looked for. */
    data class NotAnArchive(val reason: String) : ArchiveHeaderOutcome
}

sealed interface ArchiveUnlockOutcome {
    data class Unlocked(val key: SecretKey) : ArchiveUnlockOutcome

    data object WrongPassphrase : ArchiveUnlockOutcome

    /** The header is readable but its KDF parameters are not ones Thor will act on. */
    data class Unsupported(val reason: String) : ArchiveUnlockOutcome
}

/**
 * Reads a container's header, and turns a passphrase into a key.
 *
 * Two calls, not one, because §8.1 needs the header **before** it asks for anything: the restore
 * screen shows package, version, date and classes held, runs the gate, and only then prompts.
 */
@Factory
class OpenArchiveUseCase(private val cipher: AppArchiveCipher) {

    suspend fun readHeader(source: ArchiveSource): ArchiveHeaderOutcome {
        val bytes = runCatching { source.openEntry(THORBAK_HEADER_ENTRY)?.use { it.readBytes() } }
            .getOrNull()
            ?: return ArchiveHeaderOutcome.NotAnArchive(
                "this file has no $THORBAK_HEADER_ENTRY, so it is not a Thor backup"
            )

        return runCatching { ArchiveHeader.decode(bytes.decodeToString()) }
            .fold(
                onSuccess = { ArchiveHeaderOutcome.Read(it) },
                onFailure = {
                    ArchiveHeaderOutcome.NotAnArchive(
                        "this file's $THORBAK_HEADER_ENTRY could not be read: ${it.message}"
                    )
                },
            )
    }

    /**
     * Derive the key and check it against the header's verifier.
     *
     * One derivation decides it, before a byte of ciphertext is touched — which matters because the
     * alternative is discovering a wrong passphrase after streaming several gigabytes.
     *
     * @param passphrase **not cleared here.** The caller owns it, and on success the vault may still
     *   need it to remember the passphrase. Same contract as `AppArchiveCipher.deriveKey`.
     */
    suspend fun unlock(header: ArchiveHeader, passphrase: CharArray): ArchiveUnlockOutcome {
        val iterations = header.kdf.iterations
        if (iterations <= 0 || iterations > MAX_KDF_ITERATIONS) {
            return ArchiveUnlockOutcome.Unsupported(
                "this archive declares $iterations key-derivation rounds, which Thor will not run"
            )
        }

        // Decoded rather than trusted: `deriveKey` has a `require` on the salt length, and an
        // IllegalArgumentException escaping a worker is a crash, not a message.
        val salt = header.kdf.salt.decodeBase64()
            ?: return ArchiveUnlockOutcome.Unsupported("this archive's salt could not be read")
        if (salt.size != KDF_SALT_BYTES) {
            return ArchiveUnlockOutcome.Unsupported(
                "this archive's salt is ${salt.size} bytes, not $KDF_SALT_BYTES"
            )
        }
        val expected = header.verifier.decodeBase64()
            ?: return ArchiveUnlockOutcome.Unsupported("this archive's verifier could not be read")

        val key = cipher.deriveKey(passphrase, salt, iterations)
        return if (cipher.verifier(key).contentEquals(expected)) {
            ArchiveUnlockOutcome.Unlocked(key)
        } else {
            ArchiveUnlockOutcome.WrongPassphrase
        }
    }

    // `java.util.Base64`, never `android.util.Base64` — the latter throws "not mocked" under JVM
    // tests, which would take this whole class off the test classpath.
    private fun String.decodeBase64(): ByteArray? =
        runCatching { Base64.getDecoder().decode(this) }.getOrNull()
}
```

- [ ] **Step 8: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.ZipArchiveSourceTest" --tests "com.valhalla.thor.domain.usecase.OpenArchiveUseCaseTest"
```

Expected: PASS, 21 tests (7 source + 14 use case), counted from the XML.

- [ ] **Step 9: Build, so the Koin compiler plugin checks the new binding**

```
./gradlew :app:assembleFossDebug
```

Expected: BUILD SUCCESSFUL. `UriArchiveSourceFactory` is the first `@Single(binds = [...])` in this feature to take both a `Context` and a `@Named("io")` dispatcher; with `strictSafety` on, a missing `@Named` fails the build rather than resolving the wrong dispatcher at runtime.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/repository/ArchiveSource.kt \
  app/src/main/java/com/valhalla/thor/data/repository/ZipArchiveSource.kt \
  app/src/main/java/com/valhalla/thor/data/repository/UriArchiveSourceFactory.kt \
  app/src/main/java/com/valhalla/thor/domain/usecase/OpenArchiveUseCase.kt \
  app/src/test/java/com/valhalla/thor/data/repository/ZipArchiveSourceTest.kt \
  app/src/test/java/com/valhalla/thor/domain/usecase/OpenArchiveUseCaseTest.kt
git commit -m "feat(backup): read a .thorbak container and unlock it"
```

---

### Task 13: Installing from the archive (§8.2) and placing OBB one file at a time (§8.4)

The two mutations restore performs *before* it touches app data, both wrapping machinery this codebase already has on hardware.

§8.2's two ordering constraints are the whole reason this is a port rather than a direct call: `session.commit()` is fire-and-forget, so `isInstalled()` immediately afterwards returns false — this codebase has been bitten by it before — and the app's Linux uid does not exist until the install lands.

§8.4 needs `ObbInstaller` to place expansions **one at a time**. It currently extracts all of them to staging and then places them, so peak disk is every OBB at once — a 4 GB game costs 8 GB. The refactor keeps `place`'s existing behaviour for the install path exactly as PR #376 verified it on hardware, and adds a streaming sibling over one shared placement step.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/repository/AppArchiveInstaller.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/repository/AppArchiveInstallerImpl.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/ObbInstaller.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/repository/ObbInstallerStreamingTest.kt`

**Interfaces:**
- Consumes: `InstallerRepository.installPackage`, `InstallMode`, `StagedPackage`, `InstallerEventBus`, `InstallState`, `AppRepository.getAppDetails`, `ObbPlacement`, `obbMkdirCommand`, `obbPlaceCommand`, `SystemRepository.executeShellCommand` (all existing).
- Produces: `AppArchiveInstaller` with `suspend fun installBundle(bundle: File, packageName: String): ArchiveInstallOutcome` and `suspend fun placeBundleObb(bundle: File, packageName: String, onFile: (String, Int, Int) -> Unit): ObbPlacement`; `ArchiveInstallOutcome` (`Installed` / `Failed(reason)` / `Unconfirmed`); `ObbInstaller.placeStreaming(bundle, packageName, onFile)`.

- [ ] **Step 1: Add `DataClass.isInternal`, with a test**

Restore's `chown` and `restorecon` apply to `CE` and `DE` only (§8.3 d/e). Putting that on `DataClass` means the *use case* decides it, which is where the assertion belongs — a gateway that silently no-ops for external classes cannot be tested for having been asked.

Append to `app/src/main/java/com/valhalla/thor/domain/model/AppDataArchive.kt`, inside `enum class DataClass`, after `excludesVolatileDirs`:

```kotlin
    /**
     * True for the two classes under `/data`, where numeric ownership and SELinux labels are real.
     *
     * The external pair live on FUSE, which synthesizes ownership from the caller — `chown` there
     * changes nothing and `restorecon` has no label to apply. Restore uses this to decide whether
     * §8.3's steps d and e run at all.
     */
    val isInternal: Boolean get() = this == CE || this == DE
```

Append to `app/src/test/java/com/valhalla/thor/domain/model/AppDataArchiveTest.kt`:

```kotlin
    @Test
    fun `only the two internal classes are chownable and relabellable`() {
        // Not a tautology restating the getter: it pins *which* classes, so adding a fifth class
        // forces a decision here rather than defaulting it to "external, skip ownership".
        assertEquals(
            listOf(DataClass.CE, DataClass.DE),
            DataClass.entries.filter { it.isInternal },
        )
    }
```

- [ ] **Step 2: Write the failing `ObbInstaller` streaming tests**

`ObbInstaller` itself needs a `Context` and `Environment`, so the testable part is the placement *loop*'s decision-making. Extract it into an internal seam and test that, the same trade `ObbPlacementTest` already makes.

Create `app/src/test/java/com/valhalla/thor/data/repository/ObbInstallerStreamingTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The streaming placement loop, tested through [ObbStreamStep] so no `Context` is needed.
 *
 * What matters here is disk: §8.4 exists because the existing `place` extracts every expansion before
 * placing any, so a 4 GB game costs 8 GB. These tests assert the peak, not just the outcome.
 */
class ObbInstallerStreamingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun step(
        placed: MutableList<String> = mutableListOf(),
        failOn: String? = null,
        peaks: MutableList<Long> = mutableListOf(),
    ) = object : ObbStreamStep {
        override suspend fun extract(leafName: String, into: File): File? {
            val file = File(into, leafName).apply { parentFile?.mkdirs(); writeBytes(ByteArray(1024)) }
            peaks += into.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            return file
        }

        override suspend fun place(source: File, leafName: String): Boolean {
            if (leafName == failOn) return false
            placed += leafName
            return true
        }
    }

    @Test
    fun `each expansion is extracted, placed, then deleted before the next is extracted`() = runTest {
        // The invariant §8.4 asks for, expressed as a measurement: the staging directory never holds
        // two files at once. A `finally`-less delete, or a delete moved after the loop, breaks this
        // and nothing else.
        val peaks = mutableListOf<Long>()
        val staging = temp.newFolder("staging")

        val result = streamObbEntries(listOf("main.obb", "patch.obb", "extra.obb"), staging, step(peaks = peaks))

        assertEquals(3, (result as ObbPlacement.Placed).count)
        assertEquals(listOf(1024L, 1024L, 1024L), peaks)
    }

    @Test
    fun `the staging directory is empty when the loop finishes`() = runTest {
        val staging = temp.newFolder("staging")

        streamObbEntries(listOf("main.obb", "patch.obb"), staging, step())

        assertEquals(emptyList<File>(), staging.walkTopDown().filter { it.isFile }.toList())
    }

    @Test
    fun `a placement failure names the file and stops the loop`() = runTest {
        val placed = mutableListOf<String>()
        val staging = temp.newFolder("staging")

        val result = streamObbEntries(
            listOf("main.obb", "patch.obb", "extra.obb"),
            staging,
            step(placed = placed, failOn = "patch.obb"),
        )

        val reason = (result as ObbPlacement.Failed).reason
        assertTrue(reason, reason.contains("patch.obb"))
        // Stops rather than carrying on: a game missing one expansion is broken, and continuing would
        // spend the remaining minutes and disk producing the same broken outcome.
        assertEquals(listOf("main.obb"), placed)
    }

    @Test
    fun `a failed placement still clears the staging directory`() = runTest {
        // Otherwise a full volume plus a failed placement leaves the partial bytes behind, and the
        // *next* attempt fails for lack of space with a message about game data.
        val staging = temp.newFolder("staging")

        streamObbEntries(listOf("main.obb", "patch.obb"), staging, step(failOn = "patch.obb"))

        assertEquals(emptyList<File>(), staging.walkTopDown().filter { it.isFile }.toList())
    }

    @Test
    fun `an extraction that produces nothing is a failure naming the file`() = runTest {
        val staging = temp.newFolder("staging")
        val brokenStep = object : ObbStreamStep {
            override suspend fun extract(leafName: String, into: File): File? = null
            override suspend fun place(source: File, leafName: String) = true
        }

        val result = streamObbEntries(listOf("main.obb"), staging, brokenStep)

        assertTrue(result.toString(), (result as ObbPlacement.Failed).reason.contains("main.obb"))
    }

    @Test
    fun `no expansions is not needed rather than a placement of zero`() = runTest {
        // `Placed(0)` would render as "0 game data files placed", which reads as a failure for an app
        // that simply has no expansions.
        val result = streamObbEntries(emptyList(), temp.newFolder("staging"), step())

        assertEquals(ObbPlacement.NotNeeded, result)
    }

    @Test
    fun `progress reports each file with its position in the set`() = runTest {
        val seen = mutableListOf<Triple<String, Int, Int>>()

        streamObbEntries(listOf("main.obb", "patch.obb"), temp.newFolder("staging"), step()) { name, i, total ->
            seen += Triple(name, i, total)
        }

        // 1-based: "1 of 2", not "0 of 2". A progress line that starts at zero reads as not started.
        assertEquals(listOf(Triple("main.obb", 1, 2), Triple("patch.obb", 2, 2)), seen)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.ObbInstallerStreamingTest" --tests "com.valhalla.thor.domain.model.AppDataArchiveTest"
```

Expected: compilation failure — `ObbStreamStep`, `streamObbEntries` unresolved; `isInternal` unresolved if Step 1 was skipped.

- [ ] **Step 4: Add the streaming loop to `ObbInstaller.kt`**

Append to `app/src/main/java/com/valhalla/thor/data/repository/ObbInstaller.kt`, at file scope below the class:

```kotlin
/**
 * The two device-touching halves of one streaming placement, so [streamObbEntries] can be tested
 * without a `Context`.
 *
 * Same trade `ObbPlacementTest` makes for the command builders: the decisions are hoisted out of the
 * class that needs `Environment` and `Context`, and only the decisions are tested.
 */
internal interface ObbStreamStep {

    /** Extract one entry into [into], returning the file written, or null if it did not appear. */
    suspend fun extract(leafName: String, into: File): File?

    /** Copy it into `Android/obb/<pkg>/` with the shell. False means the copy did not land. */
    suspend fun place(source: File, leafName: String): Boolean
}

/**
 * Extract → place → **delete**, one expansion at a time (§8.4).
 *
 * The delete is in a `finally` inside the loop. That single placement is what holds peak disk at one
 * expansion file: move it after the loop and a 4 GB game needs 8 GB, which is the behaviour this
 * function exists to avoid.
 *
 * Stops at the first failure. A game missing one expansion is broken, so spending the remaining
 * minutes and gigabytes to reach the same broken outcome helps nobody.
 *
 * @param onFile called before each entry with its leaf name and 1-based position. One-based because a
 *   progress line reading "0 of 2" reads as not started.
 */
internal suspend fun streamObbEntries(
    leafNames: List<String>,
    staging: File,
    step: ObbStreamStep,
    onFile: (String, Int, Int) -> Unit = { _, _, _ -> },
): ObbPlacement {
    if (leafNames.isEmpty()) return ObbPlacement.NotNeeded

    leafNames.forEachIndexed { index, leafName ->
        onFile(leafName, index + 1, leafNames.size)
        var extracted: File? = null
        try {
            extracted = step.extract(leafName, staging)
                ?: return ObbPlacement.Failed("$leafName could not be read out of the archive")
            if (!step.place(extracted, leafName)) {
                return ObbPlacement.Failed("$leafName could not be copied into place")
            }
        } finally {
            extracted?.delete()
        }
    }
    return ObbPlacement.Placed(leafNames.size)
}
```

- [ ] **Step 5: Add `placeStreaming` to the `ObbInstaller` class**

Add inside `class ObbInstaller`, beside the existing `place`:

```kotlin
    /**
     * Place a bundle's expansions **without extracting them all first** (§8.4).
     *
     * Distinct from [place] rather than a flag on it: [place] is the install path PR #376 verified on
     * hardware, and a defaulted parameter changing its behaviour is exactly the shape that hides the
     * call site that matters. The follow-up row in Task 18 proposes converging the two once this one
     * has its own hardware pass.
     *
     * Restore reaches here for an app that is **already installed** — the install path already places
     * OBB itself. Skipping the bundle for an installed app would leave a game whose expansions were
     * wiped with no way to get them back.
     */
    suspend fun placeStreaming(
        bundle: File,
        packageName: String,
        onFile: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): ObbPlacement = withContext(ioDispatcher) {
        val resolved = declaredExpansions(bundle, packageName)
        if (resolved.isEmpty()) return@withContext ObbPlacement.NotNeeded

        val externalRoot = Environment.getExternalStorageDirectory()?.absolutePath
            ?: return@withContext ObbPlacement.Failed("shared storage is unavailable")
        val mkdirCommand = obbMkdirCommand(externalRoot, packageName)
            ?: return@withContext ObbPlacement.Failed(
                "this app's game data folder is not a path Thor will create"
            )
        val externalCache = context.externalCacheDir
            ?: return@withContext ObbPlacement.Failed("shared storage is unavailable")

        val staging = File(externalCache, "$OBB_INSTALL_STAGING_DIR/$packageName")
        if (!staging.deleteRecursively()) {
            return@withContext ObbPlacement.Failed(
                "the leftovers of an earlier attempt could not be cleared"
            )
        }
        val mkdir = systemRepository.executeShellCommand(mkdirCommand).getOrNull()
        if (mkdir == null || mkdir.first != 0) {
            return@withContext ObbPlacement.Failed("the game data folder could not be created")
        }

        try {
            streamObbEntries(
                leafNames = resolved.map { it.leafName },
                staging = staging,
                onFile = onFile,
                step = object : ObbStreamStep {
                    override suspend fun extract(leafName: String, into: File): File? =
                        extractExpansions(bundle, resolved.filter { it.leafName == leafName }, into)
                            .firstOrNull()
                            ?.file

                    override suspend fun place(source: File, leafName: String): Boolean {
                        val command = obbPlaceCommand(
                            externalStorageDir = externalRoot,
                            packageName = packageName,
                            leaf = leafName,
                            sourcePath = source.absolutePath,
                            expectedBytes = source.length(),
                        ) ?: return false
                        val move = systemRepository.executeShellCommand(command).getOrNull()
                        return move != null && move.first == 0
                    }
                },
            )
        } finally {
            staging.deleteRecursively()
        }
    }
```

**If `extractExpansions`'s return type does not expose a `file` and a `leafName`,** read its declaration and adapt the two lambda bodies to whatever it does return — do not change `extractExpansions` itself, and do not change `place`. The shape being preserved is: extract exactly the one entry, get a `File` back, place it, delete it.

- [ ] **Step 6: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.ObbInstallerStreamingTest" --tests "com.valhalla.thor.data.repository.ObbPlacementTest" --tests "com.valhalla.thor.domain.model.AppDataArchiveTest"
```

Expected: PASS. `ObbPlacementTest` is in the run deliberately — it is the existing coverage of the code this task touched, and it must be unchanged and green.

- [ ] **Step 7: Write the installer port**

Create `app/src/main/java/com/valhalla/thor/domain/repository/AppArchiveInstaller.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.ObbPlacement
import java.io.File

/** How an install-from-archive ended. */
sealed interface ArchiveInstallOutcome {

    data object Installed : ArchiveInstallOutcome

    data class Failed(val reason: String) : ArchiveInstallOutcome

    /**
     * The install neither succeeded nor reported an error inside the timeout.
     *
     * Its own outcome, not folded into [Failed]: restoring data into a package whose install Thor
     * could not confirm is how you write someone's data into a half-installed app. The caller stops,
     * and says so.
     */
    data object Unconfirmed : ArchiveInstallOutcome
}

/**
 * Installs an app from the `.xapk` inside an archive, and places that bundle's expansions.
 *
 * A narrow port rather than a call into `InstallerRepository` for two reasons. `installPackage` takes
 * an `android.net.Uri`, which would take the restore use case off the JVM test classpath (see
 * [ArchiveSource]). And the install result does not come back from `installPackage` at all — it
 * arrives later on `InstallerEventBus`, because `session.commit()` returns before the platform has
 * installed anything. Both of those are implementation facts, and this is where they stay.
 */
interface AppArchiveInstaller {

    /**
     * Install [bundle], then wait for the outcome.
     *
     * Waits on the install result rather than polling `isInstalled()`: §8.2. Returns only once the
     * install has landed, failed, or the wait has run out.
     */
    suspend fun installBundle(bundle: File, packageName: String): ArchiveInstallOutcome

    /**
     * Place [bundle]'s expansions into `Android/obb/<pkg>/` for an app that is **already installed**
     * (§8.4), one file at a time.
     *
     * Not called after [installBundle] — that path places OBB itself.
     *
     * @param onFile leaf name, 1-based position, total.
     */
    suspend fun placeBundleObb(
        bundle: File,
        packageName: String,
        onFile: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): ObbPlacement
}
```

**Note on `ObbPlacement`'s package.** It is declared **at the top of `ObbInstaller.kt`**, in `com.valhalla.thor.data.repository` — it has no file of its own today. Cut it out into a new `app/src/main/java/com/valhalla/thor/domain/model/ObbPlacement.kt` (keeping its KDoc verbatim), then add `import com.valhalla.thor.domain.model.ObbPlacement` to `ObbInstaller.kt`, `InstallerRepositoryImpl.kt`, and any test that names it. This is not tidying: a `domain` port cannot reference a `data` type, and `AppArchiveInstaller` returns one. Do not rename or change its members — `NotNeeded`, `Placed(count)`, `Failed(reason)` are what this task's tests and the shipped install path both use.

- [ ] **Step 8: Write the installer implementation**

Create `app/src/main/java/com/valhalla/thor/data/repository/AppArchiveInstallerImpl.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.net.Uri
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.StagedPackage
import com.valhalla.thor.domain.repository.AppArchiveInstaller
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File

@Single(binds = [AppArchiveInstaller::class])
class AppArchiveInstallerImpl(
    private val installerRepository: InstallerRepository,
    private val eventBus: InstallerEventBus,
    private val appRepository: AppRepository,
    private val obbInstaller: ObbInstaller,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : AppArchiveInstaller {

    override suspend fun installBundle(
        bundle: File,
        packageName: String,
    ): ArchiveInstallOutcome = withContext(ioDispatcher) {
        if (!bundle.isFile || bundle.length() == 0L) {
            return@withContext ArchiveInstallOutcome.Failed("the archive's app bundle is missing")
        }

        // The bus is a replaying SharedFlow shared with PortableInstallerActivity. Without a reset, a
        // stale `Success` from an earlier install in this process is read as this one's outcome.
        eventBus.reset()

        val mode = InstallMode.ROOT
        runCatching {
            installerRepository.installPackage(
                staged = StagedPackage(file = bundle, displayName = bundle.name),
                // Only InstallMode.EXTERNAL reads this, and EXTERNAL is not reachable here — restore
                // never hands the job to another installer app.
                uri = Uri.fromFile(bundle),
                mode = mode,
                canDowngrade = true,
            )
        }.onFailure {
            Logger.e(TAG, "install of $packageName threw", it)
            return@withContext ArchiveInstallOutcome.Failed(it.message ?: "the install failed")
        }

        awaitOutcome(packageName)
    }

    /**
     * Wait on the bus, then confirm against `PackageManager`.
     *
     * Both, not either. `session.commit()` is fire-and-forget, so the bus is the only thing that knows
     * the install finished; and the bus is a process-wide flow, so a `Success` on it is confirmed
     * against the package actually being there before any data is written into it.
     */
    private suspend fun awaitOutcome(packageName: String): ArchiveInstallOutcome {
        val settled = withTimeoutOrNull(INSTALL_WAIT_MS) {
            eventBus.events.first { it is InstallState.Success || it is InstallState.Error }
        } ?: return ArchiveInstallOutcome.Unconfirmed

        if (settled is InstallState.Error) {
            // The bus already carries the platform's own reason; a second sentence about restore
            // would bury the cause under one of its consequences.
            return ArchiveInstallOutcome.Failed("the app could not be installed")
        }
        return if (appRepository.getAppDetails(packageName) != null) {
            ArchiveInstallOutcome.Installed
        } else {
            ArchiveInstallOutcome.Unconfirmed
        }
    }

    override suspend fun placeBundleObb(
        bundle: File,
        packageName: String,
        onFile: (String, Int, Int) -> Unit,
    ): ObbPlacement = obbInstaller.placeStreaming(bundle, packageName, onFile)

    companion object {
        private const val TAG = "AppArchiveInstaller"

        /**
         * Generous, because this covers a multi-hundred-megabyte split install on a slow device. It
         * ends in [ArchiveInstallOutcome.Unconfirmed] rather than in silence — the same choice
         * `InstallerRepositoryImpl.awaitInstalled` makes.
         */
        private const val INSTALL_WAIT_MS = 10 * 60 * 1000L
    }
}
```

**Two things to verify against the real code while writing this,** rather than assuming:

1. **`InstallMode` for restore.** `ROOT` is written above because the archive feature is root-gated by the capability probe (Task 6). If `SystemRepositoryImpl` exposes the active `PrivilegeMode`, map it instead — Shizuku's session install is gateway-routed and works. What must not happen is `InstallMode.NORMAL`, which shows the platform's own confirmation dialog in the middle of a background job.
2. **`eventBus.events` replay.** `InstallerEventBus` configures a replay; if `first { … }` returns a *replayed* terminal state despite the `reset()` above, collect with a drop of the replay cache instead. The failure mode to avoid is reading a previous install's `Success` as this one's — which is why the reset is there and why this is called out.

- [ ] **Step 9: Build, then run the whole suite**

```
./gradlew :app:assembleFossDebug
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks
```

Expected: BUILD SUCCESSFUL, and every test green. The full run matters here because Step 7 moved `ObbPlacement` between packages — a test file that imported it from `data.repository` fails to compile, and that is the point of running everything.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/repository/AppArchiveInstaller.kt \
  app/src/main/java/com/valhalla/thor/data/repository/AppArchiveInstallerImpl.kt \
  app/src/main/java/com/valhalla/thor/data/repository/ObbInstaller.kt \
  app/src/main/java/com/valhalla/thor/domain/model/ObbPlacement.kt \
  app/src/main/java/com/valhalla/thor/domain/model/AppDataArchive.kt \
  app/src/main/java/com/valhalla/thor/data/repository/InstallerRepositoryImpl.kt \
  app/src/test/java/com/valhalla/thor/data/repository/ObbInstallerStreamingTest.kt \
  app/src/test/java/com/valhalla/thor/domain/model/AppDataArchiveTest.kt
git commit -m "feat(backup): install from an archive's bundle, and stream its OBB"
```

Add the `ObbPlacement.kt` deletion from its old location and any test file the package move touched — check `git status` before committing and stage explicit paths only.

---

### Task 14: `RestoreAppArchiveUseCase` — §8.3's sequence, and the breadcrumb

The destructive task. Every ordering decision in it is one that, got wrong, deletes someone's data and reports success.

Three of them are worth naming before the code:

- **A member is decrypted in full before its class root is touched.** Integrity failure is the common case for a corrupt archive, and the only safe place to discover it is with the original still on disk.
- **The breadcrumb is written before the first destructive call and cleared only on success.** A failure that clears it converts "your restore was interrupted" into silence.
- **The staged tar is deleted per class, in a `finally` inside the loop.** Same invariant as the backup side: peak disk is one class.

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/domain/repository/AppDataArchiveGateway.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImpl.kt`
- Create: `app/src/main/java/com/valhalla/thor/domain/repository/ArchiveBreadcrumbStore.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/FileArchiveBreadcrumbStore.kt`
- Create: `app/src/main/java/com/valhalla/thor/domain/usecase/RestoreAppArchiveUseCase.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/FileArchiveBreadcrumbStoreTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/usecase/RestoreAppArchiveUseCaseTest.kt`

**Interfaces:**
- Consumes: `DataClass.isInternal`, `ArchiveHeader`, `ArchiveMember`, `ArchiveCompression`, `THORBAK_BUNDLE_ENTRY` (Tasks 2, 13); `extractCommand`, `swapStagedEntriesCommand`, `chownRecursiveCommand`, `restoreconCommand`, `dataClassRoot` (Tasks 3, 11); `AppArchiveCipher.decryptMember`, `ArchiveIntegrityException` (Task 4); `ThorJobProgress`, `ThorJobStage` (Task 8); `AppDataArchiveGateway` (Task 9); `ArchiveSource` (Task 12); `AppArchiveInstaller`, `ArchiveInstallOutcome`, `ObbPlacement` (Task 13).
- Produces: four gateway methods (`extractInto`, `swapStaged`, `chownClass`, `relabelClass`); `ArchiveBreadcrumb`, `ArchiveBreadcrumbStore`; `ArchiveRestoreOutcome` (`Completed(classesRestored, warnings, obb)` / `Failed(reason, classesRestored)`); `RestoreAppArchiveUseCase.invoke(...)`.

- [ ] **Step 1: Extend the gateway port**

Append these four to `interface AppDataArchiveGateway` in `app/src/main/java/com/valhalla/thor/domain/repository/AppDataArchiveGateway.kt`:

```kotlin
    /**
     * Extract [tar] into `<class root>/.thorbak-staging/` (§8.3 b).
     *
     * The tar is a file Thor wrote in its own internal cache, so it is mode 600 owned by Thor's uid.
     * Root reads it without ceremony. A shell-uid channel could not — which costs nothing, because the
     * capability probe (Task 6) already refuses a channel that cannot read a private data directory,
     * and that is the same refusal.
     *
     * @param compressed must match the member's recorded compression. `extractCommand` picks `-xzf`
     *   or `-xf` from it; guessing would fail on a plain tar or, worse, half-succeed.
     */
    suspend fun extractInto(
        packageName: String,
        dataClass: DataClass,
        tar: File,
        compressed: Boolean,
    ): Boolean

    /**
     * Replace the class root's contents with the staged extraction, then remove the staging directory
     * (§8.3 c).
     *
     * This is the destructive step. Everything before it is recoverable.
     */
    suspend fun swapStaged(packageName: String, dataClass: DataClass): Boolean

    /**
     * `chown -R <uid>:<uid>` over the class root (§8.3 d).
     *
     * @param uid the app's **live Linux** uid, read after any install. Not [thorUserId] — see its
     *   KDoc for why the two must never be swapped.
     */
    suspend fun chownClass(packageName: String, dataClass: DataClass, uid: Int): Boolean

    /** `restorecon -RF` over the class root (§8.3 e). */
    suspend fun relabelClass(packageName: String, dataClass: DataClass): Boolean
```

- [ ] **Step 2: Implement them**

Add to `class AppDataArchiveGatewayImpl` in `app/src/main/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImpl.kt`. Reuse whatever private helper the class already has for "resolve the class root, refuse if it is not a path we will quote"; if it does not have one, add it and route `listClass`/`tarClass` through it too rather than leaving two copies of the resolution.

```kotlin
    override suspend fun extractInto(
        packageName: String,
        dataClass: DataClass,
        tar: File,
        compressed: Boolean,
    ): Boolean = runClassCommand(packageName, dataClass, "extract") { root ->
        extractCommand(root, tar.absolutePath, compressed)
    }

    override suspend fun swapStaged(packageName: String, dataClass: DataClass): Boolean =
        runClassCommand(packageName, dataClass, "swap") { root -> swapStagedEntriesCommand(root) }

    override suspend fun chownClass(packageName: String, dataClass: DataClass, uid: Int): Boolean =
        runClassCommand(packageName, dataClass, "chown") { root -> chownRecursiveCommand(root, uid) }

    override suspend fun relabelClass(packageName: String, dataClass: DataClass): Boolean =
        runClassCommand(packageName, dataClass, "restorecon") { root -> restoreconCommand(root) }

    /**
     * Resolve the class root, build the command, run it, and report whether it exited 0.
     *
     * `exitCode == 0`, not `!= -1`: `RootSystemGateway.execute()` folds a *throw* into
     * `-1 to stackTraceToString()`, so any rule phrased as "not the failure code" reads Thor's own
     * stack trace as a success.
     */
    private suspend fun runClassCommand(
        packageName: String,
        dataClass: DataClass,
        what: String,
        build: (String) -> String?,
    ): Boolean = withContext(ioDispatcher) {
        val root = classRootOf(packageName, dataClass) ?: return@withContext false
        val command = build(root) ?: run {
            Logger.e(TAG, "$what refused for ${dataClass.id} of $packageName")
            return@withContext false
        }
        val result = systemRepository.executeShellCommand(command).getOrNull()
        if (result == null || result.first != 0) {
            Logger.e(TAG, "$what of ${dataClass.id} for $packageName exited ${result?.first}")
            return@withContext false
        }
        true
    }
```

- [ ] **Step 3: Write the failing breadcrumb tests**

Create `app/src/test/java/com/valhalla/thor/data/backup/FileArchiveBreadcrumbStoreTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileArchiveBreadcrumbStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(dir: File = temp.newFolder("files")) = FileArchiveBreadcrumbStore(dir)

    @Test
    fun `a written breadcrumb reads back`() = runTest {
        val store = store()

        store.write("com.example.app", "Example")

        val crumb = store.read()!!
        assertEquals("com.example.app", crumb.packageName)
        assertEquals("Example", crumb.appLabel)
    }

    @Test
    fun `a breadcrumb is stamped with a real time`() = runTest {
        val store = store()

        store.write("com.example.app", "Example")

        assertTrue(store.read()!!.startedAt > 0L)
    }

    @Test
    fun `no breadcrumb reads as null, not as an empty one`() = runTest {
        assertNull(store().read())
    }

    @Test
    fun `clear removes it`() = runTest {
        val store = store()
        store.write("com.example.app", "Example")

        store.clear()

        assertNull(store.read())
    }

    @Test
    fun `clearing when there is nothing is not an error`() = runTest {
        // Called on every success path and from the launch sweep. Throwing here would turn a clean
        // restore into a crash on its last line.
        store().clear()
    }

    @Test
    fun `an unreadable breadcrumb reads as null and is removed`() = runTest {
        // A truncated write — the process died mid-`write` — must not make Thor report an interrupted
        // restore of a package it cannot name, forever.
        val dir = temp.newFolder("files")
        File(dir, FileArchiveBreadcrumbStore.FILE_NAME).writeText("{ truncated")

        assertNull(store(dir).read())
        assertFalse(File(dir, FileArchiveBreadcrumbStore.FILE_NAME).exists())
    }

    @Test
    fun `a second write replaces the first`() = runTest {
        val store = store()
        store.write("com.first.app", "First")

        store.write("com.second.app", "Second")

        assertEquals("com.second.app", store.read()!!.packageName)
    }
}
```

- [ ] **Step 4: Write the failing restore tests**

Create `app/src/test/java/com/valhalla/thor/domain/usecase/RestoreAppArchiveUseCaseTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.domain.model.ArchiveBundleInfo
import com.valhalla.thor.domain.model.ArchiveCompression
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveKdf
import com.valhalla.thor.domain.model.ArchiveMember
import com.valhalla.thor.domain.model.ClassEntries
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.TarOutcome
import com.valhalla.thor.domain.model.THORBAK_BUNDLE_ENTRY
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.repository.AppArchiveInstaller
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.domain.repository.ArchiveSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Base64
import javax.crypto.SecretKey

class RestoreAppArchiveUseCaseTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = AppArchiveCipher()
    private val salt = ByteArray(16) { it.toByte() }
    private val key: SecretKey = cipher.deriveKey("pass".toCharArray(), salt, iterations = 4)

    /** Every gateway call, in order, as `"<verb>:<class>"` — the assertion surface for §8.3's sequence. */
    private val calls = mutableListOf<String>()

    private class FakeSource(private val entries: Map<String, ByteArray>) : ArchiveSource {
        override val displayName = "com.example.app-100.thorbak"
        override fun entryNames() = entries.keys.toList()
        override fun openEntry(name: String): InputStream? = entries[name]?.let(::ByteArrayInputStream)
        override fun close() = Unit
    }

    /** One encrypted member plus the stats the header must record for it. */
    private fun member(dataClass: DataClass, body: String = "tar bytes for ${dataClass.id}"): Pair<ArchiveMember, ByteArray> {
        val nonce = cipher.newNonce()
        val out = ByteArrayOutputStream()
        val name = dataClass.memberName(compressed = true)
        val stats = cipher.encryptMember(name, ByteArrayInputStream(body.toByteArray()), out, key, nonce)
        return ArchiveMember(
            dataClass = dataClass.id,
            fileName = name,
            nonce = Base64.getEncoder().encodeToString(nonce),
            plainBytes = stats.plainBytes,
            chunkCount = stats.chunkCount,
            compression = ArchiveCompression.GZIP.id,
        ) to out.toByteArray()
    }

    private fun archive(
        classes: List<DataClass>,
        withBundle: Boolean = true,
    ): Pair<ArchiveHeader, FakeSource> {
        val built = classes.map(::member)
        val entries = built.associate { (m, bytes) -> m.fileName to bytes }.toMutableMap()
        if (withBundle) entries[THORBAK_BUNDLE_ENTRY] = "xapk bytes".toByteArray()
        val header = ArchiveHeader(
            createdAt = 1_000L,
            thorVersionCode = 1950,
            packageName = "com.example.app",
            versionCode = 100L,
            userId = 0,
            signerSha256 = SIGNER,
            appBundle = if (withBundle) ArchiveBundleInfo(bytes = 10L, obbCapture = "present", obbCount = 2) else null,
            kdf = ArchiveKdf(iterations = 4, salt = Base64.getEncoder().encodeToString(salt)),
            verifier = Base64.getEncoder().encodeToString(cipher.verifier(key)),
            members = built.map { it.first },
        )
        return header to FakeSource(entries)
    }

    private inner class FakeGateway(
        private val failOn: String? = null,
        private val uid: Int? = 10123,
        private val signer: String? = SIGNER,
    ) : AppDataArchiveGateway {
        val stagedFiles = mutableListOf<File>()

        override suspend fun thorUserId() = 0
        override suspend fun externalStorageDir() = "/storage/emulated/0"
        override suspend fun stagingFile(name: String): File =
            temp.newFile("staged-${stagedFiles.size}-$name").also(stagedFiles::add)

        override suspend fun forceStop(packageName: String) { calls += "force-stop" }
        override suspend fun listClass(packageName: String, dataClass: DataClass) =
            ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)

        override suspend fun tarClass(
            packageName: String,
            dataClass: DataClass,
            entries: List<String>,
            out: File,
            compress: Boolean,
        ) = TarOutcome.Succeeded

        override suspend fun appUid(packageName: String) = uid
        override suspend fun signerSha256(packageName: String) = signer

        override suspend fun extractInto(packageName: String, dataClass: DataClass, tar: File, compressed: Boolean) =
            record("extract", dataClass)

        override suspend fun swapStaged(packageName: String, dataClass: DataClass) = record("swap", dataClass)
        override suspend fun chownClass(packageName: String, dataClass: DataClass, uid: Int) = record("chown", dataClass)
        override suspend fun relabelClass(packageName: String, dataClass: DataClass) = record("relabel", dataClass)

        private fun record(verb: String, dataClass: DataClass): Boolean {
            val call = "$verb:${dataClass.id}"
            calls += call
            return call != failOn
        }
    }

    private class FakeInstaller(
        private val outcome: ArchiveInstallOutcome = ArchiveInstallOutcome.Installed,
        private val placement: ObbPlacement = ObbPlacement.Placed(2),
        private val calls: MutableList<String>,
    ) : AppArchiveInstaller {
        override suspend fun installBundle(bundle: File, packageName: String): ArchiveInstallOutcome {
            calls += "install"
            return outcome
        }

        override suspend fun placeBundleObb(
            bundle: File,
            packageName: String,
            onFile: (String, Int, Int) -> Unit,
        ): ObbPlacement {
            calls += "obb"
            return placement
        }
    }

    private class RecordingBreadcrumbs : ArchiveBreadcrumbStore {
        var current: ArchiveBreadcrumb? = null
        val history = mutableListOf<String>()

        override suspend fun write(packageName: String, appLabel: String) {
            current = ArchiveBreadcrumb(packageName, appLabel, startedAt = 1L)
            history += "write"
        }

        override suspend fun read(): ArchiveBreadcrumb? = current
        override suspend fun clear() {
            current = null
            history += "clear"
        }
    }

    private fun useCase(
        gateway: AppDataArchiveGateway,
        installer: AppArchiveInstaller,
        breadcrumbs: ArchiveBreadcrumbStore,
    ) = RestoreAppArchiveUseCase(gateway, installer, breadcrumbs, cipher)

    @Test
    fun `each internal class is extracted, swapped, chowned and relabelled in that order`() = runTest {
        val (header, source) = archive(listOf(DataClass.CE, DataClass.DE))
        val crumbs = RecordingBreadcrumbs()

        val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), crumbs)(
            source = source,
            header = header,
            key = key,
            classes = listOf(DataClass.CE, DataClass.DE),
            installFirst = false,
            restoreObb = false,
        )

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Completed)
        assertEquals(
            listOf(
                "force-stop",
                "extract:ce", "swap:ce", "chown:ce", "relabel:ce",
                "extract:de", "swap:de", "chown:de", "relabel:de",
                "force-stop",
            ),
            calls,
        )
    }

    @Test
    fun `external classes are neither chowned nor relabelled`() = runTest {
        // FUSE synthesizes ownership from the caller, so `chown` there changes nothing and
        // `restorecon` has no label to apply. Issuing them anyway would produce two failed commands
        // per class and a restore reported as partial when it was complete.
        val (header, source) = archive(listOf(DataClass.EXTERNAL_DATA, DataClass.EXTERNAL_MEDIA))

        useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key,
            listOf(DataClass.EXTERNAL_DATA, DataClass.EXTERNAL_MEDIA),
            installFirst = false,
            restoreObb = false,
        )

        assertFalse(calls.toString(), calls.any { it.startsWith("chown") || it.startsWith("relabel") })
    }

    @Test
    fun `the app is force-stopped before the first destructive call and once more at the end`() = runTest {
        // Twice, not per class: §8.3 steps 2 and 5. The second one is there because a broadcast can
        // wake the app mid-restore, and an app running on top of half-replaced data writes over it.
        val (header, source) = archive(listOf(DataClass.CE))

        useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
        )

        assertEquals(2, calls.count { it == "force-stop" })
        assertEquals("force-stop", calls.first())
        assertEquals("force-stop", calls.last())
    }

    @Test
    fun `the breadcrumb is written before the first destructive call and cleared on success`() = runTest {
        val (header, source) = archive(listOf(DataClass.CE))
        val crumbs = RecordingBreadcrumbs()

        useCase(FakeGateway(), FakeInstaller(calls = calls), crumbs)(
            source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
        )

        assertEquals(listOf("write", "clear"), crumbs.history)
        assertNull(crumbs.current)
    }

    @Test
    fun `a failure leaves the breadcrumb in place`() = runTest {
        // This is the whole point of §8.5. Clearing on failure converts "the restore of X was
        // interrupted and its data may be incomplete" into silence, and the user finds out when the
        // app crashes.
        val (header, source) = archive(listOf(DataClass.CE))
        val crumbs = RecordingBreadcrumbs()

        val outcome = useCase(FakeGateway(failOn = "swap:ce"), FakeInstaller(calls = calls), crumbs)(
            source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
        )

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertNotNull(crumbs.current)
        assertFalse(crumbs.history.toString(), crumbs.history.contains("clear"))
    }

    @Test
    fun `a member that fails integrity leaves its class root untouched`() = runTest {
        // The ordering that matters most: decrypt fully, *then* extract, *then* swap. A corrupt
        // archive discovered after the swap has already deleted the data it was replacing.
        val (header, source) = archive(listOf(DataClass.CE))
        val corrupted = FakeSource(
            source.entryNames().associateWith { name ->
                source.openEntry(name)!!.readBytes().also { if (name.endsWith(".enc")) it[it.size - 1]++ }
            }
        )

        val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            corrupted, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
        )

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertFalse(calls.toString(), calls.any { it.startsWith("extract") || it.startsWith("swap") })
    }

    @Test
    fun `a member the container does not hold is a failure, not a silent skip`() = runTest {
        val (header, _) = archive(listOf(DataClass.CE))
        val empty = FakeSource(emptyMap())

        val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            empty, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
        )

        val reason = (outcome as ArchiveRestoreOutcome.Failed).reason
        assertTrue(reason, reason.contains(DataClass.CE.memberName(compressed = true)))
    }

    @Test
    fun `install-first installs before any data call`() = runTest {
        val (header, source) = archive(listOf(DataClass.CE))

        useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false,
        )

        assertEquals("install", calls.first())
    }

    @Test
    fun `an install that does not land writes no data and leaves no breadcrumb`() = runTest {
        // Nothing was destroyed, so a breadcrumb saying otherwise would make the user go looking for
        // damage that is not there.
        val (header, source) = archive(listOf(DataClass.CE))
        val crumbs = RecordingBreadcrumbs()

        val outcome = useCase(
            FakeGateway(),
            FakeInstaller(outcome = ArchiveInstallOutcome.Unconfirmed, calls = calls),
            crumbs,
        )(source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false)

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertEquals(listOf("install"), calls)
        assertNull(crumbs.current)
    }

    @Test
    fun `a signer mismatch after the install stops the restore`() = runTest {
        // The gate (Task 11) cannot check an absent app's signer, so this is the only place that check
        // can happen for an install-first restore. Without it, "app not installed" is a hole straight
        // through the one refusal §8.1 allows no override for.
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(
            FakeGateway(signer = "CD".repeat(32)),
            FakeInstaller(calls = calls),
            RecordingBreadcrumbs(),
        )(source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false)

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertFalse(calls.toString(), calls.any { it.startsWith("swap") })
    }

    @Test
    fun `an unreadable uid stops the restore before anything is force-stopped`() = runTest {
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(FakeGateway(uid = null), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
        )

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the staged tar of one class is gone before the next is decrypted`() = runTest {
        // Peak disk is one class, same invariant the backup side holds. A `finally` folded up into the
        // outer `try` breaks this and only this.
        val gateway = FakeGateway()
        val (header, source) = archive(listOf(DataClass.CE, DataClass.DE, DataClass.EXTERNAL_DATA))

        useCase(gateway, FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key,
            listOf(DataClass.CE, DataClass.DE, DataClass.EXTERNAL_DATA),
            installFirst = false,
            restoreObb = false,
        )

        assertEquals(
            emptyList<File>(),
            gateway.stagedFiles.filter { it.exists() && it.length() > 0L },
        )
    }

    @Test
    fun `OBB is placed for an already-installed app when asked`() = runTest {
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = true,
        )

        assertTrue(calls.toString(), calls.contains("obb"))
        assertEquals(ObbPlacement.Placed(2), (outcome as ArchiveRestoreOutcome.Completed).obb)
    }

    @Test
    fun `OBB is not placed twice after an install`() = runTest {
        // The install path places the bundle's expansions itself. Doing it again would re-copy every
        // gigabyte for no change.
        val (header, source) = archive(listOf(DataClass.CE))

        useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = true,
        )

        assertFalse(calls.toString(), calls.contains("obb"))
    }

    @Test
    fun `a failed OBB placement is a warning, not a failed restore`() = runTest {
        // The data landed. Reporting the whole restore as failed would send the user to try it again,
        // which destroys and rewrites the data that is already correct.
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(
            FakeGateway(),
            FakeInstaller(placement = ObbPlacement.Failed("no space"), calls = calls),
            RecordingBreadcrumbs(),
        )(source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = true)

        val completed = outcome as ArchiveRestoreOutcome.Completed
        assertTrue(completed.warnings.toString(), completed.warnings.any { it.contains("no space") })
    }

    @Test
    fun `a failure reports the classes that did land`() = runTest {
        // "Restore failed" over a CE that is already replaced tells the user nothing they can act on.
        val (header, source) = archive(listOf(DataClass.CE, DataClass.DE))

        val outcome = useCase(FakeGateway(failOn = "swap:de"), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key, listOf(DataClass.CE, DataClass.DE), installFirst = false, restoreObb = false,
        )

        assertEquals(listOf(DataClass.CE), (outcome as ArchiveRestoreOutcome.Failed).classesRestored)
    }

    @Test
    fun `progress never reports a literal zero percent`() = runTest {
        val (header, source) = archive(listOf(DataClass.CE))
        val seen = mutableListOf<ThorJobProgress>()

        useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key, listOf(DataClass.CE),
            installFirst = false, restoreObb = false,
            onProgress = { seen += it },
        )

        assertTrue(seen.isNotEmpty())
        assertFalse(seen.toString(), seen.any { it.percent == 0 })
    }

    private companion object {
        const val SIGNER = "AB"
    }
}
```

**`SIGNER` is deliberately short.** The gate's tests use a full 64-character hex string because they are about hex; here it is an opaque token being compared for equality, and a realistic-looking one adds sixty characters of noise to every failure message. Replace `"AB"` with `"AB".repeat(32)` only if the implementation ever validates the *shape* of a signer, which it must not — that belongs in the gate.

- [ ] **Step 5: Run both test files to verify they fail**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.backup.FileArchiveBreadcrumbStoreTest" --tests "com.valhalla.thor.domain.usecase.RestoreAppArchiveUseCaseTest"
```

Expected: compilation failure — `FileArchiveBreadcrumbStore`, `ArchiveBreadcrumbStore`, `ArchiveBreadcrumb`, `RestoreAppArchiveUseCase`, `ArchiveRestoreOutcome` unresolved.

- [ ] **Step 6: Write the breadcrumb port and its file store**

Create `app/src/main/java/com/valhalla/thor/domain/repository/ArchiveBreadcrumbStore.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

/**
 * A record that a restore was in flight.
 *
 * @param appLabel carried rather than looked up later: by the time this is read, the app may be in
 *   whatever state the interruption left it, and a label resolved then could be blank.
 */
data class ArchiveBreadcrumb(
    val packageName: String,
    val appLabel: String,
    val startedAt: Long,
)

/**
 * §8.5. Written before the destructive phase, deleted on success.
 *
 * A breadcrumb surviving to the next launch means Thor can say *"the restore of X was interrupted and
 * its data may be incomplete"* instead of letting the user discover it when the app crashes.
 */
interface ArchiveBreadcrumbStore {

    suspend fun write(packageName: String, appLabel: String)

    /** Null when no restore is recorded as in flight. */
    suspend fun read(): ArchiveBreadcrumb?

    /** Idempotent: called on every success path and again from the launch sweep. */
    suspend fun clear()
}
```

Create `app/src/main/java/com/valhalla/thor/data/backup/FileArchiveBreadcrumbStore.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import android.content.Context
import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import java.io.File

/**
 * One small JSON file in `filesDir`.
 *
 * `filesDir`, **not** `cacheDir`: the platform evicts cache under pressure, and a breadcrumb that can
 * vanish is a breadcrumb that lies. DataStore would also work and would be the house style for
 * preferences, but this is not a preference — it is a flag that has to survive a process kill during
 * a multi-gigabyte write, and one atomic-ish file write is easier to reason about than a coroutine
 * flushing an unrelated store.
 *
 * @param directory `filesDir` in production; a [File] parameter rather than the `Context` so the
 *   whole class is JVM-testable.
 */
@Single(binds = [ArchiveBreadcrumbStore::class])
class FileArchiveBreadcrumbStore(private val directory: File) : ArchiveBreadcrumbStore {

    /** Koin's constructor: `filesDir` off the `Context` the scan already provides. */
    constructor(context: Context) : this(context.filesDir)

    private val file: File get() = File(directory, FILE_NAME)

    @Serializable
    private data class Stored(val packageName: String, val appLabel: String, val startedAt: Long)

    override suspend fun write(packageName: String, appLabel: String) {
        runCatching {
            directory.mkdirs()
            file.writeText(json.encodeToString(Stored(packageName, appLabel, System.currentTimeMillis())))
        }
    }

    override suspend fun read(): ArchiveBreadcrumb? {
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<Stored>(file.readText())
        }.fold(
            onSuccess = { ArchiveBreadcrumb(it.packageName, it.appLabel, it.startedAt) },
            onFailure = {
                // A truncated write — the process died mid-`write`. Left in place it would report an
                // interrupted restore of a package Thor cannot name, on every launch, forever.
                file.delete()
                null
            },
        )
    }

    override suspend fun clear() {
        runCatching { file.delete() }
    }

    companion object {
        const val FILE_NAME = "restore-in-progress.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
```

**On the two constructors.** Koin's compiler plugin picks the *primary* constructor, which takes `File` — and there is no `File` in the graph, so the build fails. Give the `Context` one the annotation instead: move `@Single(binds = [ArchiveBreadcrumbStore::class])` off the class and declare the binding as a `@Single` function in `di/Modules.kt` returning `FileArchiveBreadcrumbStore(get<Context>().filesDir)`. That is what `AppModule` exists for — "things the scan cannot see" — and it keeps the `File` constructor as the only one.

- [ ] **Step 7: Write the restore use case**

Create `app/src/main/java/com/valhalla/thor/domain/usecase/RestoreAppArchiveUseCase.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.ArchiveIntegrityException
import com.valhalla.thor.domain.model.ArchiveCompression
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveMember
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.THORBAK_BUNDLE_ENTRY
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.AppArchiveInstaller
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.domain.repository.ArchiveSource
import com.valhalla.thor.util.Logger
import org.koin.core.annotation.Factory
import java.io.File
import java.util.Base64
import javax.crypto.SecretKey

sealed interface ArchiveRestoreOutcome {

    /**
     * @param warnings things the user should know that are not failures — a failed OBB placement, a
     *   class the archive skipped. Shown alongside §8.6's "launch the app to check".
     * @param obb null when OBB was not part of this restore.
     */
    data class Completed(
        val classesRestored: List<DataClass>,
        val warnings: List<String>,
        val obb: ObbPlacement?,
    ) : ArchiveRestoreOutcome

    /**
     * @param classesRestored the classes that **did** land before the failure. "Restore failed" over a
     *   `CE` that is already replaced tells the user nothing they can act on.
     */
    data class Failed(
        val reason: String,
        val classesRestored: List<DataClass>,
    ) : ArchiveRestoreOutcome
}

/**
 * §8.3, in order.
 *
 * Restore **replaces** a class wholesale; it does not merge. Stale files from the current install
 * would otherwise survive a restore that the user believes returned the app to a known state. The
 * confirmation before this runs says so in those words.
 */
@Factory
class RestoreAppArchiveUseCase(
    private val gateway: AppDataArchiveGateway,
    private val installer: AppArchiveInstaller,
    private val breadcrumbs: ArchiveBreadcrumbStore,
    private val cipher: AppArchiveCipher,
) {

    suspend operator fun invoke(
        source: ArchiveSource,
        header: ArchiveHeader,
        key: SecretKey,
        classes: List<DataClass>,
        installFirst: Boolean,
        restoreObb: Boolean,
        appLabel: String = header.packageName,
        onProgress: (ThorJobProgress) -> Unit = {},
    ): ArchiveRestoreOutcome {
        val pkg = header.packageName
        val restored = mutableListOf<DataClass>()
        val warnings = mutableListOf<String>()

        // The bundle is needed for an install and for OBB, and only then. Extracting it otherwise
        // would cost the app's whole download for nothing.
        val bundle = if (installFirst || restoreObb) extractBundle(source, header) else null
        if ((installFirst || restoreObb) && bundle == null) {
            return ArchiveRestoreOutcome.Failed(
                "this archive's app bundle could not be read", restored
            )
        }

        if (installFirst) {
            onProgress(ThorJobProgress(ThorJobStage.INSTALLING, appLabel))
            when (val outcome = installer.installBundle(bundle!!, pkg)) {
                ArchiveInstallOutcome.Installed -> Unit
                is ArchiveInstallOutcome.Failed ->
                    return ArchiveRestoreOutcome.Failed(outcome.reason, restored)

                ArchiveInstallOutcome.Unconfirmed -> return ArchiveRestoreOutcome.Failed(
                    "Thor could not confirm $appLabel finished installing, so it wrote no data",
                    restored,
                )
            }
            // The gate could not check an absent app's signer (Task 11), so this is the only place the
            // check can happen for an install-first restore. Skipping it would be a hole straight
            // through the one refusal §8.1 allows no override for.
            val signer = gateway.signerSha256(pkg)
            if (signer == null || !signer.equals(header.signerSha256, ignoreCase = true)) {
                return ArchiveRestoreOutcome.Failed(
                    "the app that installed is not signed by the key this archive was made from",
                    restored,
                )
            }
        }

        // After the install, never from the archive: a reinstalled app has a new uid (§8.2).
        val uid = gateway.appUid(pkg)
            ?: return ArchiveRestoreOutcome.Failed(
                "Thor could not read $appLabel's user id, so it wrote no data", restored
            )

        gateway.forceStop(pkg)
        breadcrumbs.write(pkg, appLabel)

        val totalBytes = classes.sumOf { header.member(it)?.plainBytes ?: 0L }
        var doneBytes = 0L

        for (dataClass in classes) {
            val member = header.member(dataClass)
                ?: return failWithBreadcrumbKept(
                    "this archive has no ${dataClass.id} data", restored
                )
            onProgress(
                ThorJobProgress(ThorJobStage.RESTORING, appLabel, doneBytes, totalBytes)
            )

            val failure = restoreClass(source, dataClass, member, key, pkg, uid)
            if (failure != null) return failWithBreadcrumbKept(failure, restored)

            restored += dataClass
            doneBytes += member.plainBytes
        }

        if (restoreObb && !installFirst && header.appBundle != null) {
            onProgress(ThorJobProgress(ThorJobStage.RESTORING, appLabel, doneBytes, totalBytes))
            // A failed placement is a warning: the data landed, and telling the user the restore
            // failed sends them to run it again, which destroys and rewrites data that is correct.
            when (val placement = installer.placeBundleObb(bundle!!, pkg)) {
                is ObbPlacement.Failed -> warnings += "the game data could not be placed: ${placement.reason}"
                else -> Unit
            }
            gateway.forceStop(pkg)
            breadcrumbs.clear()
            bundle.delete()
            return ArchiveRestoreOutcome.Completed(restored, warnings, ObbPlacement.NotNeeded)
        }

        onProgress(ThorJobProgress(ThorJobStage.FINISHING, appLabel, totalBytes, totalBytes))
        gateway.forceStop(pkg)
        breadcrumbs.clear()
        bundle?.delete()
        return ArchiveRestoreOutcome.Completed(restored, warnings, obb = null)
    }

    /**
     * One class, in §8.3's order. Returns null on success, or the reason it failed.
     *
     * The whole member is decrypted **before** [AppDataArchiveGateway.extractInto] runs, and the swap
     * comes after that. A corrupt archive therefore fails with the original data still in place —
     * which is the difference between "that archive is bad" and "your data is gone".
     */
    private suspend fun restoreClass(
        source: ArchiveSource,
        dataClass: DataClass,
        member: ArchiveMember,
        key: SecretKey,
        packageName: String,
        uid: Int,
    ): String? {
        val staged = gateway.stagingFile("restore-${dataClass.id}.tar")
        try {
            val ciphertext = source.openEntry(member.fileName)
                ?: return "this archive is missing ${member.fileName}"
            val nonce = runCatching { Base64.getDecoder().decode(member.nonce) }.getOrNull()
                ?: return "this archive's ${dataClass.id} member has an unreadable nonce"

            try {
                ciphertext.use { input ->
                    staged.outputStream().use { output ->
                        cipher.decryptMember(member.fileName, input, output, key, nonce, member.chunkCount)
                    }
                }
            } catch (e: ArchiveIntegrityException) {
                Logger.e(TAG, "${member.fileName} failed integrity", e)
                return "this archive's ${dataClass.id} data is damaged and was not restored"
            }

            val compressed = ArchiveCompression.fromId(member.compression) == ArchiveCompression.GZIP
            if (!gateway.extractInto(packageName, dataClass, staged, compressed)) {
                return "${dataClass.id} could not be unpacked"
            }
            // Past this line the original is gone.
            if (!gateway.swapStaged(packageName, dataClass)) {
                return "${dataClass.id} could not be put into place"
            }
            if (dataClass.isInternal) {
                if (!gateway.chownClass(packageName, dataClass, uid)) {
                    return "${dataClass.id} was restored but its ownership could not be set"
                }
                if (!gateway.relabelClass(packageName, dataClass)) {
                    return "${dataClass.id} was restored but its security labels could not be set"
                }
            }
            return null
        } finally {
            // Inside the loop, so peak disk is one class. Folding this up into `invoke` is the one
            // edit that breaks that and nothing else.
            staged.delete()
        }
    }

    private suspend fun failWithBreadcrumbKept(
        reason: String,
        restored: List<DataClass>,
    ): ArchiveRestoreOutcome.Failed {
        // Deliberately no `breadcrumbs.clear()`. §8.5: a surviving breadcrumb is how the next launch
        // tells the user their data may be incomplete.
        return ArchiveRestoreOutcome.Failed(reason, restored.toList())
    }

    private suspend fun extractBundle(source: ArchiveSource, header: ArchiveHeader): File? {
        if (header.appBundle == null) return null
        val entry = source.openEntry(header.appBundle.fileName) ?: return null
        val out = gateway.stagingFile(THORBAK_BUNDLE_ENTRY)
        return runCatching {
            entry.use { input -> out.outputStream().use(input::copyTo) }
            out
        }.getOrElse {
            Logger.e(TAG, "could not stage the app bundle", it)
            out.delete()
            null
        }
    }

    private companion object {
        const val TAG = "RestoreAppArchive"
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.backup.FileArchiveBreadcrumbStoreTest" --tests "com.valhalla.thor.domain.usecase.RestoreAppArchiveUseCaseTest"
```

Expected: PASS, 24 tests (7 breadcrumb + 17 restore), counted from the XML.

If `OBB is placed for an already-installed app when asked` fails on the returned `obb` value, the reason is the two return paths above: the OBB branch returns `ObbPlacement.NotNeeded` where the test expects `Placed(2)`. Carry the real placement through — assign it to a local in the `when` and return that — rather than changing the test. The test is asserting the thing a user sees ("2 game data files placed"), and a hard-coded `NotNeeded` would report nothing happened.

- [ ] **Step 9: Build**

```
./gradlew :app:assembleFossDebug
```

Expected: BUILD SUCCESSFUL. Watch for the Koin failure the Step 6 note predicts — if `FileArchiveBreadcrumbStore` is still annotated on the class, the compiler plugin reports no binding for `java.io.File`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/repository/AppDataArchiveGateway.kt \
  app/src/main/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImpl.kt \
  app/src/main/java/com/valhalla/thor/domain/repository/ArchiveBreadcrumbStore.kt \
  app/src/main/java/com/valhalla/thor/data/backup/FileArchiveBreadcrumbStore.kt \
  app/src/main/java/com/valhalla/thor/domain/usecase/RestoreAppArchiveUseCase.kt \
  app/src/main/java/com/valhalla/thor/di/Modules.kt \
  app/src/test/java/com/valhalla/thor/data/backup/FileArchiveBreadcrumbStoreTest.kt \
  app/src/test/java/com/valhalla/thor/domain/usecase/RestoreAppArchiveUseCaseTest.kt
git commit -m "feat(backup): restore a .thorbak, replacing one class at a time"
```

---

### Task 15: The two workers, the enqueue seam, and the launch-time sweep

Everything so far runs only from a test. This task makes it runnable: two `@KoinWorker`s on Task 8's base, one launcher that derives the key in the foreground and enqueues, and the §10 sweep that cleans up after a process that died mid-job.

Task 1 already did the manifest and `workManagerFactory()`. Nothing here touches either.

**One decision worth stating up front: the restore worker re-reads the header and re-runs the gate.** Neither travels in the `WorkRequest`. `androidx.work.Data` caps at 10 KiB and an encoded header with a long `skippedEntries` list can exceed it — but the real reason is staleness: a gate decision made at enqueue time and executed after the chain drains describes an app that may have been installed, uninstalled or updated in between. Re-running the gate against a fresh read costs one zip-entry read and closes that whole class of bug.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/ArchiveRestoreRequest.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/PartialArchiveLedger.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/ArchiveOrphanSweeper.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/AppArchiveWorker.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobLauncher.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/model/AppDataArchive.kt` (add `ObbProbe.captureName()`)
- Modify: `app/src/main/java/com/valhalla/thor/domain/repository/AppArchiveStore.kt` (add `discardOrphans`)
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/AppArchiveStoreImpl.kt` (record and forget partials, implement `discardOrphans`)
- Modify: `app/src/main/java/com/valhalla/thor/di/Modules.kt`
- Modify: `app/src/main/java/com/valhalla/thor/ThorApplication.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/ArchiveRestoreRequestTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/PartialArchiveLedgerTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/ArchiveOrphanSweeperTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/AppDataArchiveTest.kt` (add the `captureName` cases)

**Interfaces:**
- Consumes: everything Tasks 8–14 produced; `AppRepository.getAppDetails`, `AppBundleBuilder.build`, `SystemRepository.probeObb`, `BundleFormat.XAPK` (existing).
- Produces: `ArchiveRestoreRequest(uriString, packageName, classes, restoreObb)` with `toMap()`/`fromMap`; `ObbProbe.captureName(): String`; `AppArchiveStore.discardOrphans(names): Set<String>`; `PartialArchiveLedger` (`add`/`forget`/`names`); `ArchiveOrphanSweeper.sweep(): SweepReport` with `SweepReport(interrupted, containersRemoved, stagedFilesRemoved)`; `ArchiveBackupWorker`, `ArchiveRestoreWorker`; `ThorJobLauncher` with `suspend fun startBackup(...): UUID?` and `suspend fun startRestore(...): UUID?`.

- [ ] **Step 1: Write the failing tests for the request, the ledger, and the sweep**

Create `app/src/test/java/com/valhalla/thor/domain/model/ArchiveRestoreRequestTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveRestoreRequestTest {

    private val request = ArchiveRestoreRequest(
        uriString = "content://com.android.providers.downloads.documents/document/42",
        packageName = "com.example.app",
        classes = setOf(DataClass.CE, DataClass.EXTERNAL_MEDIA),
        restoreObb = true,
    )

    @Test
    fun `a request survives a round trip through the map`() {
        assertEquals(request, ArchiveRestoreRequest.fromMap(request.toMap()))
    }

    @Test
    fun `the map holds only types androidx work Data accepts`() {
        // A Set or an enum here throws at putAll — at enqueue time, in production, with no test
        // between here and there to catch it.
        val allowed = setOf(String::class.java, java.lang.Boolean::class.java, Array<String>::class.java)
        request.toMap().forEach { (key, value) ->
            assertTrue("$key is a ${value.javaClass}", value.javaClass in allowed)
        }
    }

    @Test
    fun `the map carries no gate decision`() {
        // installFirst is deliberately absent: the worker re-runs the gate against a fresh read of
        // the archive and of what is installed. A decision persisted at enqueue time describes an app
        // that may have been installed or removed while the job sat in the chain.
        assertTrue(
            request.toMap().keys.none { it.contains("install", ignoreCase = true) },
        )
    }

    @Test
    fun `a map with no uri is not a runnable restore`() {
        assertNull(ArchiveRestoreRequest.fromMap(request.toMap() - RESTORE_URI_KEY))
        assertNull(ArchiveRestoreRequest.fromMap(request.toMap() + (RESTORE_URI_KEY to "")))
    }

    @Test
    fun `a map with no package is not a runnable restore`() {
        // The package the archive claims, checked against the header the worker re-reads. Without it
        // a re-resolved URI pointing at a different archive would restore the wrong app's data.
        assertNull(ArchiveRestoreRequest.fromMap(request.toMap() - RESTORE_PACKAGE_KEY))
    }

    @Test
    fun `a map with no classes is not a runnable restore`() {
        assertNull(ArchiveRestoreRequest.fromMap(request.toMap() - RESTORE_CLASSES_KEY))
        assertNull(
            ArchiveRestoreRequest.fromMap(request.toMap() + (RESTORE_CLASSES_KEY to emptyArray<String>()))
        )
    }

    @Test
    fun `an unknown class id is dropped rather than fatal`() {
        // Same rule as ArchiveBackupRequest: a job enqueued by a newer build and run after a
        // downgrade restores the classes this build understands.
        val map = request.toMap() + (RESTORE_CLASSES_KEY to arrayOf(DataClass.CE.id, "ce-v2"))

        assertEquals(setOf(DataClass.CE), ArchiveRestoreRequest.fromMap(map)!!.classes)
    }

    @Test
    fun `classes come back in DataClass order, not map order`() {
        // The restore loop's order is the order CE/DE/ext-data/ext-media are declared, because DE
        // holding a key CE needs is the ordering that matters. A Set built from an arbitrary array
        // would leave that to chance.
        val map = request.toMap() + (
            RESTORE_CLASSES_KEY to arrayOf(DataClass.EXTERNAL_MEDIA.id, DataClass.CE.id, DataClass.DE.id)
            )

        assertEquals(
            listOf(DataClass.CE, DataClass.DE, DataClass.EXTERNAL_MEDIA),
            ArchiveRestoreRequest.fromMap(map)!!.orderedClasses(),
        )
    }

    @Test
    fun `restoreObb defaults to false when absent`() {
        assertEquals(false, ArchiveRestoreRequest.fromMap(request.toMap() - RESTORE_OBB_KEY)!!.restoreObb)
    }
}
```

Create `app/src/test/java/com/valhalla/thor/data/backup/PartialArchiveLedgerTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PartialArchiveLedgerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun ledger(dir: File = temp.newFolder("files")) = PartialArchiveLedger(dir)

    @Test
    fun `an added name reads back`() = runTest {
        val ledger = ledger()

        ledger.add("Thor-com.example.app-100.thorbak.part")

        assertEquals(setOf("Thor-com.example.app-100.thorbak.part"), ledger.names())
    }

    @Test
    fun `two backups in flight are both recorded`() = runTest {
        // Jobs serialise on one chain, so this is not the common case — but a job cancelled after
        // `add` and before `forget` leaves its name behind, and the next backup must not erase it.
        val ledger = ledger()
        ledger.add("a.thorbak.part")

        ledger.add("b.thorbak.part")

        assertEquals(setOf("a.thorbak.part", "b.thorbak.part"), ledger.names())
    }

    @Test
    fun `forget removes one name and leaves the others`() = runTest {
        val ledger = ledger()
        ledger.add("a.thorbak.part")
        ledger.add("b.thorbak.part")

        ledger.forget("a.thorbak.part")

        assertEquals(setOf("b.thorbak.part"), ledger.names())
    }

    @Test
    fun `an empty ledger reads as an empty set`() = runTest {
        assertEquals(emptySet<String>(), ledger().names())
    }

    @Test
    fun `an unreadable ledger reads as empty and is removed`() = runTest {
        // Otherwise a truncated write makes every launch attempt to delete names it cannot parse.
        val dir = temp.newFolder("files")
        File(dir, PartialArchiveLedger.FILE_NAME).writeText("[ truncated")

        assertEquals(emptySet<String>(), ledger(dir).names())
        assertEquals(false, File(dir, PartialArchiveLedger.FILE_NAME).exists())
    }

    @Test
    fun `forgetting a name that was never added is not an error`() = runTest {
        ledger().forget("never-there.part")
    }
}
```

Create `app/src/test/java/com/valhalla/thor/data/backup/ArchiveOrphanSweeperTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveDestination
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveOrphanSweeperTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeStore(private val removable: Set<String>) : AppArchiveStore {
        var asked: Set<String> = emptySet()

        override suspend fun openArchive(fileName: String): ArchiveDestination? = null
        override suspend fun currentTargetLabel(): String = "Downloads/Thor"
        override suspend fun discardOrphans(names: Set<String>): Set<String> {
            asked = names
            return names intersect removable
        }
    }

    private class FakeBreadcrumbs(private var crumb: ArchiveBreadcrumb?) : ArchiveBreadcrumbStore {
        var cleared = false
        override suspend fun write(packageName: String, appLabel: String) = Unit
        override suspend fun read(): ArchiveBreadcrumb? = crumb
        override suspend fun clear() {
            cleared = true
            crumb = null
        }
    }

    private fun cacheWith(vararg staged: String): Pair<File, File> {
        val cache = temp.newFolder("cache")
        val staging = File(cache, AppDataArchiveStagingDir.NAME).apply { mkdirs() }
        staged.forEach { File(staging, it).writeText("x") }
        return cache to staging
    }

    @Test
    fun `staged tars left by a dead process are deleted`() = runTest {
        val (cache, staging) = cacheWith("ce.tar", "restore-de.tar", "app.xapk")
        val sweeper = ArchiveOrphanSweeper(
            ledger = PartialArchiveLedger(temp.newFolder("files1")),
            archiveStore = FakeStore(emptySet()),
            breadcrumbs = FakeBreadcrumbs(null),
            cacheDir = cache,
        )

        val report = sweeper.sweep()

        assertEquals(emptyList<File>(), staging.listFiles()?.toList() ?: emptyList<File>())
        assertEquals(3, report.stagedFilesRemoved)
    }

    @Test
    fun `the staging directory itself survives the sweep`() = runTest {
        // The gateway's `stagingFile` does `mkdirs()` on every call, so removing the directory would
        // not break anything — but a sweep that deletes a directory it was asked to empty is one
        // refactor away from being pointed at a directory it should not delete.
        val (cache, staging) = cacheWith("ce.tar")
        val sweeper = ArchiveOrphanSweeper(
            PartialArchiveLedger(temp.newFolder("files2")), FakeStore(emptySet()), FakeBreadcrumbs(null), cache,
        )

        sweeper.sweep()

        assertTrue(staging.isDirectory)
    }

    @Test
    fun `the read-copy of an opened archive is deleted by its exact name`() = runTest {
        val (cache, _) = cacheWith()
        val copy = File(cache, UriArchiveSourceFactory.COPY_FILE_NAME).apply { writeText("zip") }
        val keep = File(cache, "image_cache.bin").apply { writeText("keep me") }
        val sweeper = ArchiveOrphanSweeper(
            PartialArchiveLedger(temp.newFolder("files3")), FakeStore(emptySet()), FakeBreadcrumbs(null), cache,
        )

        sweeper.sweep()

        assertEquals(false, copy.exists())
        // Coil, Room and the bundle builder all keep files in cacheDir. A pattern sweep here would
        // delete another subsystem's working set.
        assertTrue(keep.exists())
    }

    @Test
    fun `only the container names the ledger recorded are offered for deletion`() = runTest {
        val (cache, _) = cacheWith()
        val ledger = PartialArchiveLedger(temp.newFolder("files4"))
        ledger.add("Thor-com.example.app-100.thorbak.part")
        val store = FakeStore(setOf("Thor-com.example.app-100.thorbak.part"))
        val sweeper = ArchiveOrphanSweeper(ledger, store, FakeBreadcrumbs(null), cache)

        val report = sweeper.sweep()

        assertEquals(setOf("Thor-com.example.app-100.thorbak.part"), store.asked)
        assertEquals(1, report.containersRemoved)
    }

    @Test
    fun `a removed container is forgotten and a surviving one is kept for the next launch`() = runTest {
        // A SAF tree on a volume that is not mounted yet fails the delete. Forgetting the name anyway
        // would leave the `.part` in the user's folder forever with nothing left that knows its name.
        val (cache, _) = cacheWith()
        val ledger = PartialArchiveLedger(temp.newFolder("files5"))
        ledger.add("gone.thorbak.part")
        ledger.add("still-there.thorbak.part")
        val sweeper = ArchiveOrphanSweeper(
            ledger, FakeStore(setOf("gone.thorbak.part")), FakeBreadcrumbs(null), cache,
        )

        sweeper.sweep()

        assertEquals(setOf("still-there.thorbak.part"), ledger.names())
    }

    @Test
    fun `an interrupted restore is reported and the breadcrumb is left for the UI`() = runTest {
        // The sweep must not be what silences the warning. Only the screen that has told the user
        // clears it — see Task 17.
        val (cache, _) = cacheWith()
        val crumbs = FakeBreadcrumbs(ArchiveBreadcrumb("com.example.app", "Example", startedAt = 5L))
        val sweeper = ArchiveOrphanSweeper(
            PartialArchiveLedger(temp.newFolder("files6")), FakeStore(emptySet()), crumbs, cache,
        )

        val report = sweeper.sweep()

        assertNotNull(report.interrupted)
        assertEquals("Example", report.interrupted!!.appLabel)
        assertEquals(false, crumbs.cleared)
    }

    @Test
    fun `a clean launch sweeps nothing and reports nothing`() = runTest {
        val cache = temp.newFolder("cache-clean")
        val sweeper = ArchiveOrphanSweeper(
            PartialArchiveLedger(temp.newFolder("files7")), FakeStore(emptySet()), FakeBreadcrumbs(null), cache,
        )

        val report = sweeper.sweep()

        assertEquals(ArchiveOrphanSweeper.SweepReport(null, 0, 0), report)
    }
}
```

Add to `app/src/test/java/com/valhalla/thor/domain/model/AppDataArchiveTest.kt`:

```kotlin
    @Test
    fun `every ObbProbe answer has its own capture name`() {
        assertEquals("none", ObbProbe.None.captureName())
        assertEquals("present", ObbProbe.Present(emptyList(), otherEntryCount = 0).captureName())
        assertEquals("undetermined", ObbProbe.Undetermined("no privilege").captureName())
        // Three names for three answers. Folding Undetermined onto "none" is the exact mistake
        // ObbProbe exists to prevent, and it would make a restore claim game data it does not hold.
        assertEquals(
            3,
            setOf(
                ObbProbe.None.captureName(),
                ObbProbe.Present(emptyList(), 0).captureName(),
                ObbProbe.Undetermined("x").captureName(),
            ).size,
        )
    }
```

with `import com.valhalla.thor.domain.model.ObbProbe` already satisfied by the package, and `assertEquals` already imported by that file.

- [ ] **Step 2: Run the tests to verify they fail**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.ArchiveRestoreRequestTest" --tests "com.valhalla.thor.data.backup.PartialArchiveLedgerTest" --tests "com.valhalla.thor.data.backup.ArchiveOrphanSweeperTest" --tests "com.valhalla.thor.domain.model.AppDataArchiveTest"
```

Expected: compilation failure — `ArchiveRestoreRequest`, `PartialArchiveLedger`, `ArchiveOrphanSweeper`, `AppDataArchiveStagingDir`, `captureName`, `discardOrphans` unresolved.

- [ ] **Step 3: Write the restore request**

Create `app/src/main/java/com/valhalla/thor/domain/model/ArchiveRestoreRequest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

const val RESTORE_URI_KEY = "thor.restore.uri"
const val RESTORE_PACKAGE_KEY = "thor.restore.package"
const val RESTORE_CLASSES_KEY = "thor.restore.classes"
const val RESTORE_OBB_KEY = "thor.restore.obb"

/**
 * Everything the restore worker needs that is safe to persist.
 *
 * **What is absent is again the point.** No passphrase, no derived key (those go through
 * `ArchiveKeyHolder`, in memory) — and no `installFirst`, no header. The worker re-reads the header
 * from [uriString] and re-runs `evaluateArchiveRestoreGate` against what is installed *at the moment it
 * runs*. A gate decision persisted at enqueue time describes an app that may have been installed,
 * removed or updated while the job waited its turn on the chain.
 *
 * @param packageName what the enqueuing screen believed the archive holds. The worker compares it
 *   against the header it re-reads and refuses on a mismatch: a `content://` URI is a handle to a
 *   document, not to bytes, and a provider is free to have a different file behind it by then.
 */
data class ArchiveRestoreRequest(
    val uriString: String,
    val packageName: String,
    val classes: Set<DataClass>,
    val restoreObb: Boolean,
) {

    /**
     * The classes in [DataClass] declaration order.
     *
     * Restore order is not cosmetic: `DE` routinely holds the keyset an app needs to read `CE`, so a
     * `Set`'s iteration order is not something to leave to whatever order the UI's checkboxes were
     * ticked in.
     */
    fun orderedClasses(): List<DataClass> = DataClass.entries.filter { it in classes }

    fun toMap(): Map<String, Any> = mapOf(
        RESTORE_URI_KEY to uriString,
        RESTORE_PACKAGE_KEY to packageName,
        RESTORE_CLASSES_KEY to classes.map { it.id }.toTypedArray(),
        RESTORE_OBB_KEY to restoreObb,
    )

    companion object {

        /** @return null when the map cannot describe a runnable restore. The worker fails, never retries. */
        fun fromMap(map: Map<String, Any?>): ArchiveRestoreRequest? {
            val uriString = (map[RESTORE_URI_KEY] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val packageName = (map[RESTORE_PACKAGE_KEY] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val ids = (map[RESTORE_CLASSES_KEY] as? Array<*>)?.mapNotNull { it as? String } ?: return null
            val classes = ids.mapNotNull { id -> DataClass.entries.firstOrNull { it.id == id } }.toSet()
            if (classes.isEmpty()) return null
            return ArchiveRestoreRequest(
                uriString = uriString,
                packageName = packageName,
                classes = classes,
                restoreObb = map[RESTORE_OBB_KEY] as? Boolean ?: false,
            )
        }
    }
}
```

- [ ] **Step 4: Add `captureName()` and the staging directory's name**

Append to `app/src/main/java/com/valhalla/thor/domain/model/AppDataArchive.kt`:

```kotlin
/**
 * [ObbProbe]'s answer as the string `ArchiveBundleInfo.obbCapture` records — three names for three
 * answers.
 *
 * Lowercase ids, matching how `DataClass.id` and `ArchiveCompression.id` are spelled in the same
 * format. **Never fold `Undetermined` onto `"none"`:** an archive that records "no OBB" when Thor
 * merely could not look is one a restore will happily call complete.
 */
fun ObbProbe.captureName(): String = when (this) {
    is ObbProbe.None -> "none"
    is ObbProbe.Present -> "present"
    is ObbProbe.Undetermined -> "undetermined"
}

/**
 * The one name for Thor's private archive staging directory under `cacheDir`.
 *
 * `AppDataArchiveGatewayImpl` creates files in it; `ArchiveOrphanSweeper` empties it at launch. Those
 * two live in different layers and neither may hold its own copy of the name — a sweep pointed at the
 * wrong directory either deletes nothing or deletes something else.
 */
object AppDataArchiveStagingDir {
    const val NAME = "archive_staging"
}
```

Then, in `app/src/main/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImpl.kt`, delete its private `STAGING_DIR` constant and use `AppDataArchiveStagingDir.NAME`:

```kotlin
    override suspend fun stagingFile(name: String): File = withContext(ioDispatcher) {
        val dir = File(context.cacheDir, AppDataArchiveStagingDir.NAME)
        dir.mkdirs()
        File(dir, name)
    }
```

- [ ] **Step 5: Write the ledger**

Create `app/src/main/java/com/valhalla/thor/data/backup/PartialArchiveLedger.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The names of `.part` containers Thor has opened in the user's folder and not yet published.
 *
 * §10 requires the launch sweep to match **exact names, never a wildcard** — Thor writes into a
 * directory the user chose, which may hold anything. `ArchiveDestination.discard()` covers every
 * failure Thor survives; this exists for the one it does not, a process killed mid-write, where
 * nothing runs to clean up and nothing else remembers the name.
 *
 * @param directory `filesDir`, for the same reason [FileArchiveBreadcrumbStore] uses it: a record the
 *   platform may evict is a record that lies.
 */
class PartialArchiveLedger(private val directory: File) {

    private val mutex = Mutex()
    private val file: File get() = File(directory, FILE_NAME)

    suspend fun add(name: String) = mutex.withLock {
        write(read() + name)
    }

    suspend fun forget(name: String) = mutex.withLock {
        write(read() - name)
    }

    suspend fun names(): Set<String> = mutex.withLock { read() }

    private fun read(): Set<String> {
        if (!file.exists()) return emptySet()
        return runCatching { json.decodeFromString<Set<String>>(file.readText()) }
            .getOrElse {
                // A truncated write. Left in place, every launch would try to parse it again.
                file.delete()
                emptySet()
            }
    }

    private fun write(names: Set<String>) {
        runCatching {
            directory.mkdirs()
            if (names.isEmpty()) file.delete() else file.writeText(json.encodeToString(names))
        }
    }

    companion object {
        const val FILE_NAME = "partial-archives.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
```

`Mutex` rather than `synchronized`: `add` and `forget` are called from the worker's coroutine and from `AppArchiveStoreImpl`'s `withContext(ioDispatcher)`, and a read-modify-write across a file needs the whole pair held, not each half.

- [ ] **Step 6: Add `discardOrphans` to the store**

In `app/src/main/java/com/valhalla/thor/domain/repository/AppArchiveStore.kt`, append to the interface:

```kotlin
    /**
     * Delete the named `.part` containers from wherever this store writes.
     *
     * @param names exact file names, from `PartialArchiveLedger`. Never a pattern: this store writes
     *   into a folder the user chose, and §10 is explicit that the sweep must not guess.
     * @return the subset actually removed. A name that could not be deleted — an unmounted volume, a
     *   revoked SAF grant — stays in the ledger for the next launch rather than being forgotten with
     *   the file still there.
     */
    suspend fun discardOrphans(names: Set<String>): Set<String>
```

- [ ] **Step 7: Implement it, and record partials as they are opened**

In `app/src/main/java/com/valhalla/thor/data/repository/AppArchiveStoreImpl.kt`:

1. Take `PartialArchiveLedger` as a constructor parameter (Koin resolves it from the `@Single` function added in Step 11).
2. In `openArchive`, after the partial is successfully created and before returning the destination, `ledger.add(partialName(fileName))`.
3. In the `ArchiveDestination` this returns, call `ledger.forget(partialName(fileName))` at the end of **both** `publish()` and `discard()`. Both, not just `discard()`: a published archive's `.part` name no longer exists, and leaving it in the ledger makes the next launch ask the store to delete a file that is now the user's finished backup under its real name — which the `PARTIAL_SUFFIX` check in Task 7 Step 1 (`assertFalse(PARTIAL_SUFFIX.endsWith(THORBAK_EXTENSION))`) stops from matching, but only by accident of naming. Forget it explicitly.
4. Implement `discardOrphans` with the same three-branch resolution `openArchive` already uses (SAF tree / MediaStore / legacy `Downloads`), deleting by exact display name and collecting the successes:

```kotlin
    override suspend fun discardOrphans(names: Set<String>): Set<String> = withContext(ioDispatcher) {
        if (names.isEmpty()) return@withContext emptySet()
        names.filterTo(mutableSetOf()) { name ->
            runCatching { deleteByName(name) }
                .onFailure { Logger.e(TAG, "could not delete the orphan $name", it) }
                .getOrDefault(false)
        }
    }
```

where `deleteByName` reuses whichever resolution `openArchive` performs and returns false — never throws — when the destination cannot be reached at all. **A destination Thor cannot reach yet is not an orphan that does not exist**; returning true there would drop the name and abandon the file.

- [ ] **Step 8: Write the sweeper**

Create `app/src/main/java/com/valhalla/thor/data/backup/ArchiveOrphanSweeper.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.model.AppDataArchiveStagingDir
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.util.Logger
import java.io.File

/**
 * §10's launch sweep, plus §8.5's interruption report.
 *
 * Three things get cleaned, by three different rules:
 * 1. **Thor's own staging directory** under `cacheDir` — emptied wholesale. Nothing but staged tars
 *    and staged bundles is ever written there, and the Odin shell dies with the process, so anything
 *    surviving a restart is garbage.
 * 2. **The read-copy** `UriArchiveSourceFactory` may leave in `cacheDir` — deleted by its exact name.
 *    `cacheDir` itself is shared with Coil, Room and the bundle builder; a pattern sweep there would
 *    delete another subsystem's working set.
 * 3. **`.part` containers in the user's folder** — only the names `PartialArchiveLedger` recorded, and
 *    a name is forgotten only once the file is gone.
 *
 * What it does **not** do is clear the breadcrumb. It reports it. Clearing it here would make the
 * sweep the thing that silences the warning a user is owed.
 */
class ArchiveOrphanSweeper(
    private val ledger: PartialArchiveLedger,
    private val archiveStore: AppArchiveStore,
    private val breadcrumbs: ArchiveBreadcrumbStore,
    private val cacheDir: File,
) {

    data class SweepReport(
        /** Non-null when a restore was in flight when Thor last stopped. §8.5. */
        val interrupted: ArchiveBreadcrumb?,
        val containersRemoved: Int,
        val stagedFilesRemoved: Int,
    )

    suspend fun sweep(): SweepReport {
        val staged = sweepStaging() + sweepReadCopy()
        val containers = sweepContainers()
        val interrupted = breadcrumbs.read()
        if (interrupted != null) {
            Logger.w(TAG, "a restore of ${interrupted.packageName} did not finish")
        }
        if (staged > 0 || containers > 0) {
            Logger.w(TAG, "swept $staged staged files and $containers partial containers")
        }
        return SweepReport(interrupted, containers, staged)
    }

    /** The directory survives; only its contents go. See the test that pins this. */
    private fun sweepStaging(): Int {
        val staging = File(cacheDir, AppDataArchiveStagingDir.NAME)
        val children = staging.listFiles() ?: return 0
        return children.count { it.deleteRecursively() }
    }

    private fun sweepReadCopy(): Int {
        val copy = File(cacheDir, UriArchiveSourceFactory.COPY_FILE_NAME)
        return if (copy.exists() && copy.delete()) 1 else 0
    }

    private suspend fun sweepContainers(): Int {
        val names = ledger.names()
        if (names.isEmpty()) return 0
        val removed = archiveStore.discardOrphans(names)
        removed.forEach { ledger.forget(it) }
        return removed.size
    }

    private companion object {
        const val TAG = "ArchiveOrphanSweeper"
    }
}
```

- [ ] **Step 9: Write the two workers**

Create `app/src/main/java/com/valhalla/thor/data/backup/job/AppArchiveWorker.kt`. Both workers live in one file because they share every one of these decisions and change together.

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.valhalla.thor.domain.model.ArchiveBackupOutcome
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreDecision
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.InstalledAppFacts
import com.valhalla.thor.domain.model.JOB_ERROR_KEY
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.captureName
import com.valhalla.thor.domain.model.evaluateArchiveRestoreGate
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.ArchiveHeaderOutcome
import com.valhalla.thor.domain.repository.ArchiveSourceFactory
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ArchiveRestoreOutcome
import com.valhalla.thor.domain.usecase.BackupAppArchiveUseCase
import com.valhalla.thor.domain.usecase.OpenArchiveUseCase
import com.valhalla.thor.domain.usecase.RestoreAppArchiveUseCase
import com.valhalla.thor.util.Logger
import org.koin.android.annotation.KoinWorker

private const val TAG = "AppArchiveWorker"

/**
 * §7.2 behind a foreground service.
 *
 * The use case owns the sequence; this owns everything that needs a `Context` or a repository — the
 * `AppInfo` lookup, the `.xapk` build, the OBB probe — and hands the results down.
 */
@KoinWorker
internal class ArchiveBackupWorker(
    appContext: Context,
    params: WorkerParameters,
    notifications: ThorJobNotifications,
    registry: JobRegistry,
    private val keys: ArchiveKeyHolder,
    private val backup: BackupAppArchiveUseCase,
    private val appRepository: AppRepository,
    private val bundleBuilder: AppBundleBuilder,
    private val systemRepository: SystemRepository,
) : ThorJobWorker(appContext, params, notifications, registry, keys) {

    override val kind = ThorJobKind.ARCHIVE_BACKUP

    /**
     * The package name, not the label.
     *
     * `getForegroundInfo()` runs before `doWork` and cannot afford a `PackageManager` round trip on
     * the path that has to promote the service within a few seconds. The first `publish()` from the
     * use case replaces it with the label, well before a user reads the shade.
     */
    override val initialLabel: String
        get() = inputData.getString(com.valhalla.thor.domain.model.BACKUP_PACKAGE_KEY).orEmpty()

    override suspend fun runJob(): Result {
        val request = ArchiveBackupRequest.fromMap(inputData.keyValueMap)
            ?: return fail("this backup's request could not be read")
        // Single-use, and gone if the process died: see ArchiveKeyHolder. No retry, ever.
        val key = keys.take(id.toString())
            ?: return fail("this backup's key is no longer in memory — start it again")
        val appInfo = appRepository.getAppDetails(request.packageName)
            ?: return fail("${request.packageName} is not installed")

        var bundle: java.io.File? = null
        return try {
            val probe = if (request.includeBundle) {
                systemRepository.probeObb(request.packageName)
            } else {
                ObbProbe.None
            }
            if (request.includeBundle) {
                bundle = bundleBuilder.build(
                    appInfo = appInfo,
                    cacheSubDir = "archive_bundle",
                    format = BundleFormat.XAPK,
                ).getOrElse { return fail("the app's installer bundle could not be built: ${it.message}") }
            }

            when (
                val outcome = backup(
                    request = request,
                    key = key,
                    bundle = bundle,
                    bundleObbCapture = probe.captureName(),
                    bundleObbCount = (probe as? ObbProbe.Present)?.files?.size ?: 0,
                    versionCode = appInfo.versionCode,
                    versionName = appInfo.versionName,
                    usableStagingBytes = usableStagingBytes(),
                    onProgress = ::publish,
                )
            ) {
                is ArchiveBackupOutcome.Completed -> Result.success()
                is ArchiveBackupOutcome.Failed -> fail(outcome.reason)
                ArchiveBackupOutcome.NoDestination -> fail("choose a folder for Thor's backups first")
            }
        } finally {
            // The bundle can be gigabytes and it is already inside the container. Deleted here rather
            // than in the use case, because this is what created it.
            bundle?.delete()
        }
    }

    /**
     * §7.4's measurement. `data` measures, `domain` decides — the same split as `BackupRunner` and
     * `BackupAppsUseCase`.
     *
     * `usableSpace`, not `getAllocatableBytes`, and therefore `@Suppress("UsableSpace")`: the bytes have
     * to be there for the whole of a multi-gigabyte `tar` the platform is not participating in, so the
     * cache quota `getAllocatableBytes` adds back is not spendable here. Same reasoning as
     * `ObbInstaller.usableBytes` and `AppBundleBuilderImpl`, and the reason #373's cache-clear bug is
     * the cautionary tale attached to obeying that hint.
     *
     * The **larger** of the two candidate volumes on purpose. `AppDataArchiveGatewayImpl.stagingFile`
     * picks internal cache and falls back to external, and this cannot see which it chose; over-reporting
     * costs a `tar` that fails and is retried, while under-reporting would skip a class the device could
     * have held. Zero from both is "unmeasurable", which the rule fails open on.
     */
    @Suppress("UsableSpace")
    private fun usableStagingBytes(): Long = maxOf(
        applicationContext.cacheDir?.usableSpace ?: 0L,
        applicationContext.externalCacheDir?.usableSpace ?: 0L,
    )
}

/**
 * §8.3 behind a foreground service.
 *
 * Re-reads the header and re-runs the gate — see [ArchiveRestoreRequest]. That is the whole reason
 * `installFirst` is not an input.
 */
@KoinWorker
internal class ArchiveRestoreWorker(
    appContext: Context,
    params: WorkerParameters,
    notifications: ThorJobNotifications,
    registry: JobRegistry,
    private val keys: ArchiveKeyHolder,
    private val sources: ArchiveSourceFactory,
    private val openArchive: OpenArchiveUseCase,
    private val restore: RestoreAppArchiveUseCase,
    private val appRepository: AppRepository,
    private val gateway: AppDataArchiveGateway,
) : ThorJobWorker(appContext, params, notifications, registry, keys) {

    override val kind = ThorJobKind.ARCHIVE_RESTORE

    override val initialLabel: String
        get() = inputData.getString(com.valhalla.thor.domain.model.RESTORE_PACKAGE_KEY).orEmpty()

    override suspend fun runJob(): Result {
        val request = ArchiveRestoreRequest.fromMap(inputData.keyValueMap)
            ?: return fail("this restore's request could not be read")
        val key = keys.take(id.toString())
            ?: return fail("this restore's key is no longer in memory — start it again")

        val source = sources.open(request.uriString)
            ?: return fail("Thor could not open that backup file")

        return source.use {
            val header = when (val read = openArchive.readHeader(source)) {
                is ArchiveHeaderOutcome.Read -> read.header
                is ArchiveHeaderOutcome.NotAnArchive -> return@use fail(read.reason)
            }
            // The URI named a different archive than the screen was looking at. A `content://` URI is
            // a handle to a document, not to bytes.
            if (header.packageName != request.packageName) {
                return@use fail("that backup file is not ${request.packageName}'s any more")
            }

            val installed = appRepository.getAppDetails(request.packageName)?.let { app ->
                InstalledAppFacts(
                    signerSha256 = gateway.signerSha256(request.packageName),
                    versionCode = app.versionCode,
                    versionName = app.versionName,
                )
            }
            // Re-run, not replay. The app may have arrived or gone while this waited on the chain.
            val decision = evaluateArchiveRestoreGate(header, installed, request.classes)
            val allowed = decision as? ArchiveRestoreDecision.Allowed
                ?: return@use fail("this backup can no longer be restored: ${(decision as ArchiveRestoreDecision.Refused).reason}")

            when (
                val outcome = restore(
                    source = source,
                    header = header,
                    key = key,
                    classes = request.orderedClasses(),
                    installFirst = allowed.installFirst,
                    restoreObb = request.restoreObb,
                    appLabel = appRepository.getAppDetails(request.packageName)?.appName
                        ?: request.packageName,
                    onProgress = ::publish,
                )
            ) {
                is ArchiveRestoreOutcome.Completed -> {
                    outcome.warnings.forEach { Logger.w(TAG, it) }
                    Result.success()
                }

                is ArchiveRestoreOutcome.Failed -> fail(
                    if (outcome.classesRestored.isEmpty()) {
                        outcome.reason
                    } else {
                        // Partial is not failure-with-nothing-done, and a user who is told only
                        // "failed" will not know their app is now holding half-restored data.
                        "${outcome.reason} (${outcome.classesRestored.joinToString { it.id }} was already replaced)"
                    }
                )
            }
        }
    }
}

/**
 * `Result.failure` carrying a sentence, never `Result.retry`.
 *
 * A retry re-runs in a process where `ArchiveKeyHolder.take` returns null, so it cannot succeed — and
 * it would report the failure much later than the moment the user was watching.
 */
private fun fail(reason: String) = androidx.work.ListenableWorker.Result.failure(
    workDataOf(JOB_ERROR_KEY to reason)
)
```

Two things to check against real code while implementing, both of which change a line here:

1. **`ThorJobWorker.publish` is `protected`,** so `::publish` from inside `runJob` is fine, but if the compiler objects to the callable reference on a protected member, pass `{ publish(it) }` instead. Do not widen `publish` to `public`.
2. **`ListenableWorker.Result` is nested,** so the file-level `fail` helper cannot return the unqualified `Result`. Either keep the fully-qualified form above, or make `fail` a `protected fun` on `ThorJobWorker` — which is the better home for it and removes the qualification from both workers. Prefer that; the version above is written file-level only so this task does not silently re-open Task 8's file without saying so.

- [ ] **Step 10: Write the launcher**

Create `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobLauncher.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.THOR_JOB_CHAIN
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.jobTag
import com.valhalla.thor.util.Logger
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * The one place a Thor archive job is started.
 *
 * **Key derivation happens here, in the foreground, not in the worker.** PBKDF2 at 210 000 iterations
 * takes a noticeable moment, and the screen that has the passphrase is the only place with something
 * to show while it runs. It is also the only way the worker never sees a passphrase: the derived key
 * goes into [ArchiveKeyHolder] under the request's id and the passphrase stays with the caller.
 */
@Single
class ThorJobLauncher(
    private val context: Context,
    private val keys: ArchiveKeyHolder,
    private val cipher: AppArchiveCipher,
    @Named("default") private val defaultDispatcher: CoroutineDispatcher,
) {

    /**
     * @param passphrase **not cleared here.** The caller owns it — the backup sheet may still need it
     *   to write the vault, and a `CharArray` zeroed under a caller that holds a reference is a bug
     *   that only shows up on the second use.
     * @return the enqueued job's id, or null if the request could not be enqueued.
     */
    suspend fun startBackup(request: ArchiveBackupRequest, passphrase: CharArray): UUID? {
        // On `default`, not `io`: PBKDF2 is CPU-bound, and `io`'s pool exists for threads that block.
        val key = withContext(defaultDispatcher) {
            runCatching { cipher.deriveKey(passphrase, request.salt) }.getOrNull()
        } ?: run {
            Logger.e("ThorJobLauncher", "key derivation failed for ${request.packageName}")
            return null
        }

        val work = OneTimeWorkRequestBuilder<ArchiveBackupWorker>()
            .setInputData(workDataOf(*request.toMap().toList().toTypedArray()))
            .addTag(jobTag(ThorJobKind.ARCHIVE_BACKUP, request.packageName))
            .build()

        // Before enqueue, not after: the worker can start the instant enqueue returns, and a worker
        // that starts before its key is in the holder fails for the one reason it must never fail for.
        keys.put(work.id.toString(), key)
        return enqueue(work.id) {
            WorkManager.getInstance(context).beginUniqueWork(
                THOR_JOB_CHAIN,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                work,
            ).enqueue()
        }
    }

    suspend fun startRestore(request: ArchiveRestoreRequest, passphrase: CharArray, salt: ByteArray): UUID? {
        val key = withContext(defaultDispatcher) {
            runCatching { cipher.deriveKey(passphrase, salt) }.getOrNull()
        } ?: run {
            Logger.e("ThorJobLauncher", "key derivation failed for ${request.packageName}")
            return null
        }

        val work = OneTimeWorkRequestBuilder<ArchiveRestoreWorker>()
            .setInputData(workDataOf(*request.toMap().toList().toTypedArray()))
            .addTag(jobTag(ThorJobKind.ARCHIVE_RESTORE, request.packageName))
            .build()

        keys.put(work.id.toString(), key)
        return enqueue(work.id) {
            WorkManager.getInstance(context).beginUniqueWork(
                THOR_JOB_CHAIN,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                work,
            ).enqueue()
        }
    }

    /** Drops the key if the enqueue itself throws — otherwise key material sits in memory for a job that will never run. */
    private inline fun enqueue(id: UUID, block: () -> Unit): UUID? =
        runCatching { block(); id }.getOrElse {
            Logger.e("ThorJobLauncher", "enqueue failed", it)
            keys.drop(id.toString())
            null
        }
}
```

**`salt` is a separate parameter on `startRestore`** because it comes from the archive's own header (`header.kdf.salt`, Base64-decoded), not from anything Thor generated — and a restore derives the key from *that* archive's salt or it derives the wrong key. `startBackup` reads it off `request.salt`, which the sheet generated with `cipher.newSalt()`.

- [ ] **Step 11: Wire the four non-scannable bindings into Koin**

`di/Modules.kt`. The component scan finds `ArchiveOrphanSweeper`? No — it takes a `File`, and so do `FileArchiveBreadcrumbStore` and `PartialArchiveLedger`. Three `@Single` functions, and none of the three classes carries a class-level annotation:

```kotlin
    /**
     * `filesDir`, not `cacheDir`: both of these are records the platform must not be free to evict.
     * They take a `File` rather than a `Context` so they stay JVM-testable, which is why the scan
     * cannot construct them and they are declared here.
     */
    @Single
    fun provideArchiveBreadcrumbStore(context: Context): ArchiveBreadcrumbStore =
        FileArchiveBreadcrumbStore(context.filesDir)

    @Single
    fun providePartialArchiveLedger(context: Context): PartialArchiveLedger =
        PartialArchiveLedger(context.filesDir)

    @Single
    fun provideArchiveOrphanSweeper(
        ledger: PartialArchiveLedger,
        archiveStore: AppArchiveStore,
        breadcrumbs: ArchiveBreadcrumbStore,
        context: Context,
    ): ArchiveOrphanSweeper = ArchiveOrphanSweeper(ledger, archiveStore, breadcrumbs, context.cacheDir)
```

Remove the `@Single(binds = [ArchiveBreadcrumbStore::class])` annotation from `FileArchiveBreadcrumbStore` and its secondary `Context` constructor — both were written in Task 14 Step 6 with a note saying this step would replace them. Leaving the annotation on gives Koin two ways to build the same type, which `unsafeDslChecks` reports as ambiguous at build time.

`Context` is already a resolvable dependency in `AppModule` (`androidContext()`), so these read it the same way `ThorJobNotifications` does.

- [ ] **Step 12: Run the sweep at launch**

In `app/src/main/java/com/valhalla/thor/ThorApplication.kt`, inject the sweeper beside the other `inject<>()` handles and run it inside the existing `appScope.launch { }` in `onCreate`, **after** the locale reconciliation block rather than before it: the sweep touches the filesystem and nothing in it is on the path to first frame.

```kotlin
    private val archiveOrphanSweeper by inject<ArchiveOrphanSweeper>()
```

```kotlin
            // §10. Deliberately not awaited and deliberately not fatal: a launch that cannot delete
            // a stale temp file is still a launch. The interruption warning is the restore screen's
            // to show — the report here is a log line, because Application has no UI.
            runCatching { archiveOrphanSweeper.sweep() }
                .onFailure { Logger.e("ThorApp", "archive orphan sweep failed", it) }
```

The breadcrumb it may find is **not** cleared. Task 17 reads `ArchiveBreadcrumbStore` from the restore screen's ViewModel, shows the §8.5 warning, and clears it there.

- [ ] **Step 13: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.model.*" --tests "com.valhalla.thor.data.backup.*" --tests "com.valhalla.thor.domain.usecase.*"
```

Expected: PASS. This is the first run of every archive test at once — count from the XML, and if a test that passed in an earlier task now fails, the cause is Step 4's constant move or Step 11's Koin change, not the new code.

- [ ] **Step 14: Build**

```
./gradlew :app:assembleFossDebug
```

Expected: BUILD SUCCESSFUL. This is the build that proves the whole graph resolves: `@KoinWorker` on two workers, three hand-written `@Single` functions, and `strictSafety` on. A failure here names the exact missing or ambiguous binding — read it rather than guessing.

- [ ] **Step 15: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/ArchiveRestoreRequest.kt \
  app/src/main/java/com/valhalla/thor/domain/model/AppDataArchive.kt \
  app/src/main/java/com/valhalla/thor/domain/repository/AppArchiveStore.kt \
  app/src/main/java/com/valhalla/thor/data/backup/PartialArchiveLedger.kt \
  app/src/main/java/com/valhalla/thor/data/backup/ArchiveOrphanSweeper.kt \
  app/src/main/java/com/valhalla/thor/data/backup/FileArchiveBreadcrumbStore.kt \
  app/src/main/java/com/valhalla/thor/data/backup/job/AppArchiveWorker.kt \
  app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobLauncher.kt \
  app/src/main/java/com/valhalla/thor/data/repository/AppArchiveStoreImpl.kt \
  app/src/main/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImpl.kt \
  app/src/main/java/com/valhalla/thor/di/Modules.kt \
  app/src/main/java/com/valhalla/thor/ThorApplication.kt \
  app/src/test/java/com/valhalla/thor/domain/model/ArchiveRestoreRequestTest.kt \
  app/src/test/java/com/valhalla/thor/domain/model/AppDataArchiveTest.kt \
  app/src/test/java/com/valhalla/thor/data/backup/PartialArchiveLedgerTest.kt \
  app/src/test/java/com/valhalla/thor/data/backup/ArchiveOrphanSweeperTest.kt
git commit -m "feat(backup): run archive jobs on WorkManager, and sweep what a crash leaves behind"
```

---

### Task 16: The backup sheet

§10's first half: a *Back up* action on `AppInfoSheet` opening `AppBackupSheet` — bundle checkbox, four class checkboxes with asynchronous sizes, destination label, passphrase field on first use only, and live progress.

**Three decisions to state before the code, because each one is a place a reviewer would reasonably expect the opposite.**

1. **No new `AppClickAction` member.** *Export* — the closest existing action — is not an `AppClickAction`: `AppActionRow` takes a plain `onExport: () -> Unit` and `AppInfoSheet` hosts `ExportBottomSheet` itself, injecting its own dependencies through `koinInject`. Backup is the same shape and follows the same route. Adding a sealed member would instead break the exhaustive `when (action)` at `presentation/main/MainViewModel.kt:501` and force three hosts to acquire an opinion about a sheet that is entirely self-contained.
2. **The view model depends on a port, `ArchiveJobLauncher`, not on `ThorJobLauncher`.** `ThorJobLauncher` calls `WorkManager.getInstance(context)`, which puts any consumer of it beyond a JVM test. This is the same reason `AppInfoDetailsViewModel` takes `AppShortcutController` rather than `FreezerShortcutManager` — a precedent worth matching rather than re-deciding. And it is a **real** seam, not a fake one: what the view model needs is "enqueue this and tell me how it goes", which is the whole port. (Contrast the trap recorded in `docs/audit/`: an injectable dispatcher at a site whose contract forbids using it buys nothing.)
3. **Class labels live in `presentation`, not on `DataClass`.** A `@StringRes` on a `domain/model` enum would put `R` in the layer that has no Android dependencies. A `dataClassLabel(DataClass): Int` function in the sheet's own file costs one `when` and keeps that rule.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/repository/ArchiveJobLauncher.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/backup/AppBackupViewModel.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/backup/AppBackupSheet.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobLauncher.kt` (implement the port, add `status` and `runningJobFor`)
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/PassphraseVault.kt` (`isSet` onto the store interface — the fix Task 5 Step 3's note asked for, now that there is a consumer)
- Modify: `app/src/test/java/com/valhalla/thor/data/backup/PassphraseVaultTest.kt` (its `FakeStore` gains `isSet`, or Task 5's test stops compiling)
- Modify: `app/src/main/java/com/valhalla/thor/presentation/widgets/AppActionRow.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/widgets/AppInfoSheet.kt`
- Modify: `app/src/main/res/values/strings_backup.xml` (the file Task 8 created — **not** `values/strings.xml`)
- Test: `app/src/test/java/com/valhalla/thor/presentation/backup/AppBackupViewModelTest.kt`

**Interfaces:**
- Consumes: `MeasureAppDataUseCase`, `AppDataMeasurement` (Task 9); `AppArchiveStore.currentTargetLabel()` (Task 7); `PassphraseVault` (Task 5); `AppArchiveCipher.newSalt()` (Task 4); `ArchiveBackupRequest`, `DataClass`, `DataClassSize`, `labelKind()`, `SizeLabelKind`, `ThorJobKind`, `ThorJobProgress` (Tasks 2, 8, 10); `JobRegistry` (Task 8); `PreferenceRepository.setExportDirUri` (existing).
- Produces: `ArchiveJobLauncher` (interface) and `ThorJobStatus`; `AppBackupViewModel` (`@KoinViewModel`) with `start(packageName, appLabel)`, `toggleClass(DataClass)`, `setIncludeBundle(Boolean)`, `useDifferentPassphrase()`, `beginBackup(passphrase: CharArray, remember: Boolean)`, `dismissResult()`, and `uiState: StateFlow<AppBackupUiState>`; `AppBackupSheet(packageName, appLabel, onDismiss)`.

- [ ] **Step 1: Write the failing view-model test**

Create `app/src/test/java/com/valhalla/thor/presentation/backup/AppBackupViewModelTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.PassphraseVault
import com.valhalla.thor.data.backup.PassphraseVaultStore
import com.valhalla.thor.data.backup.VaultKeyProvider
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.ArchiveDestination
import com.valhalla.thor.domain.repository.ArchiveJobLauncher
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.domain.usecase.MeasureAppDataUseCase
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppBackupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --- doubles -------------------------------------------------------------------------------

    private class FakeProbe(
        val capable: Boolean = true,
        val sizes: Map<DataClass, DataClassSize> = DataClass.entries.associateWith {
            DataClassSize.Known(1024L)
        },
    ) : AppDataProbe {
        override suspend fun probeDataArchiveCapability(): Boolean = capable
        override suspend fun sizeOf(packageName: String, dataClass: DataClass): DataClassSize =
            sizes[dataClass] ?: DataClassSize.Undetermined
    }

    private class FakeStore(val label: String = "Downloads/Thor") : AppArchiveStore {
        override suspend fun openArchive(fileName: String): ArchiveDestination? = null
        override suspend fun currentTargetLabel(): String = label
        override suspend fun discardOrphans(names: Set<String>): Set<String> = emptySet()
    }

    private class FakeVaultStore(initial: String? = null) : PassphraseVaultStore {
        private val state = MutableStateFlow(initial)
        override val isSet: Flow<Boolean> = state.map { it != null }
        override suspend fun read(): String? = state.value
        override suspend fun write(value: String?) {
            state.value = value
        }
    }

    /** Reversible and trivial: the vault's own wrapping is Task 5's subject, not this one's. */
    private class PlainKeyProvider : VaultKeyProvider {
        override fun wrap(plaintext: ByteArray): ByteArray = plaintext
        override fun unwrap(blob: ByteArray): ByteArray = blob
    }

    private class FakeLauncher(
        val jobId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000beef"),
        val statuses: MutableStateFlow<ThorJobStatus> = MutableStateFlow(ThorJobStatus.Running),
        val running: MutableStateFlow<UUID?> = MutableStateFlow(null),
        val fail: Boolean = false,
    ) : ArchiveJobLauncher {
        var started: ArchiveBackupRequest? = null
        var startedWith: String? = null

        override suspend fun startBackup(request: ArchiveBackupRequest, passphrase: CharArray): UUID? {
            started = request
            startedWith = passphrase.concatToString()
            return if (fail) null else jobId
        }

        override suspend fun startRestore(
            request: ArchiveRestoreRequest,
            passphrase: CharArray,
            salt: ByteArray,
        ): UUID? = null

        override fun status(jobId: UUID): Flow<ThorJobStatus> = statuses
        override fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?> = running
    }

    private fun viewModel(
        probe: AppDataProbe = FakeProbe(),
        launcher: ArchiveJobLauncher = FakeLauncher(),
        vaultStore: PassphraseVaultStore = FakeVaultStore(),
        registry: JobRegistry = JobRegistry(),
        archiveStore: AppArchiveStore = FakeStore(),
    ) = AppBackupViewModel(
        measure = MeasureAppDataUseCase(probe),
        archiveStore = archiveStore,
        vault = PassphraseVault(vaultStore, PlainKeyProvider()),
        cipher = AppArchiveCipher(),
        launcher = launcher,
        registry = registry,
    )

    // --- measurement ---------------------------------------------------------------------------

    @Test
    fun `supported is null until the probe answers`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.start("com.example.app", "Example")

        // Not `false`. False means "Thor asked and cannot" and hides the whole sheet body; null means
        // "still asking". Collapsing the two shows the refusal for a frame on every open.
        assertNull(vm.uiState.value.supported)
    }

    @Test
    fun `every class is selected once the measurement lands`() = runTest(dispatcher) {
        // §4.2: "All default on."
        val vm = viewModel()

        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertEquals(DataClass.entries.toSet(), vm.uiState.value.selected)
        assertEquals(true, vm.uiState.value.includeBundle)
        assertEquals(true, vm.uiState.value.supported)
    }

    @Test
    fun `an unmeasurable class is still offered and still selected`() = runTest(dispatcher) {
        // Undetermined is not "empty" and not "absent" — `du` may have failed on a directory holding
        // gigabytes. Dropping the checkbox would silently narrow the backup.
        val probe = FakeProbe(sizes = mapOf(DataClass.CE to DataClassSize.Undetermined))
        val vm = viewModel(probe = probe)

        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertTrue(DataClass.CE in vm.uiState.value.selected)
        assertEquals(DataClassSize.Undetermined, vm.uiState.value.sizes[DataClass.CE])
    }

    @Test
    fun `an incapable privilege state reports unsupported and measures nothing`() = runTest(dispatcher) {
        val vm = viewModel(probe = FakeProbe(capable = false))

        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.supported)
        assertEquals(emptyMap<DataClass, DataClassSize>(), vm.uiState.value.sizes)
    }

    @Test
    fun `the destination label comes from the store, not from a hardcoded folder`() = runTest(dispatcher) {
        val vm = viewModel(archiveStore = FakeStore(label = "SD card/Backups"))

        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertEquals("SD card/Backups", vm.uiState.value.destinationLabel)
    }

    // --- selection -----------------------------------------------------------------------------

    @Test
    fun `unticking a class removes it and re-ticking puts it back`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        vm.toggleClass(DataClass.EXTERNAL_MEDIA)
        assertTrue(DataClass.EXTERNAL_MEDIA !in vm.uiState.value.selected)

        vm.toggleClass(DataClass.EXTERNAL_MEDIA)
        assertTrue(DataClass.EXTERNAL_MEDIA in vm.uiState.value.selected)
    }

    @Test
    fun `a backup with nothing ticked cannot be started`() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        DataClass.entries.forEach { vm.toggleClass(it) }
        vm.setIncludeBundle(false)

        assertEquals(false, vm.uiState.value.canStart)
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        assertNull(launcher.started)
    }

    @Test
    fun `the bundle alone is a valid backup`() = runTest(dispatcher) {
        // A data-only archive is explicitly supported (§4.2), so its mirror image has to be too: an
        // installer-only archive is a perfectly good "let me reinstall this app later".
        val vm = viewModel()
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        DataClass.entries.forEach { vm.toggleClass(it) }

        assertEquals(true, vm.uiState.value.canStart)
    }

    // --- passphrase ----------------------------------------------------------------------------

    @Test
    fun `an empty vault asks for a passphrase`() = runTest(dispatcher) {
        val vm = viewModel(vaultStore = FakeVaultStore(initial = null))

        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.passphraseNeeded)
    }

    @Test
    fun `a filled vault does not ask`() = runTest(dispatcher) {
        val vm = viewModel(vaultStore = FakeVaultStore(initial = "d29yZA"))

        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.passphraseNeeded)
    }

    @Test
    fun `use a different passphrase asks again even with a filled vault`() = runTest(dispatcher) {
        val vm = viewModel(vaultStore = FakeVaultStore(initial = "d29yZA"))
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        vm.useDifferentPassphrase()

        assertEquals(true, vm.uiState.value.passphraseNeeded)
    }

    @Test
    fun `the remembered passphrase is what reaches the launcher`() = runTest(dispatcher) {
        val store = FakeVaultStore()
        PassphraseVault(store, PlainKeyProvider()).remember("stored one".toCharArray())
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher, vaultStore = store)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        // No passphrase from the UI: the field was not shown, so there is nothing to pass.
        vm.beginBackup(CharArray(0), remember = false)
        testScheduler.advanceUntilIdle()

        assertEquals("stored one", launcher.startedWith)
    }

    @Test
    fun `a vault that cannot be unwrapped prompts instead of failing the backup`() = runTest(dispatcher) {
        // §5.4: a stored passphrase that no longer works means *ask*, never "this archive is broken".
        val store = FakeVaultStore(initial = "not base64 at all !!")
        val failing = object : VaultKeyProvider {
            override fun wrap(plaintext: ByteArray) = plaintext
            override fun unwrap(blob: ByteArray): ByteArray = throw java.security.GeneralSecurityException()
        }
        val launcher = FakeLauncher()
        val vm = AppBackupViewModel(
            measure = MeasureAppDataUseCase(FakeProbe()),
            archiveStore = FakeStore(),
            vault = PassphraseVault(store, failing),
            cipher = AppArchiveCipher(),
            launcher = launcher,
            registry = JobRegistry(),
        )
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        vm.beginBackup(CharArray(0), remember = false)
        testScheduler.advanceUntilIdle()

        assertNull(launcher.started)
        assertEquals(true, vm.uiState.value.passphraseNeeded)
        assertEquals(false, vm.uiState.value.running)
    }

    @Test
    fun `remember stores the typed passphrase and not remembering leaves the vault empty`() =
        runTest(dispatcher) {
            val store = FakeVaultStore()
            val vm = viewModel(vaultStore = store)
            vm.start("com.example.app", "Example")
            testScheduler.advanceUntilIdle()

            vm.beginBackup("typed one".toCharArray(), remember = true)
            testScheduler.advanceUntilIdle()
            assertEquals("typed one", PassphraseVault(store, PlainKeyProvider()).recall()?.concatToString())

            val other = FakeVaultStore()
            val vm2 = viewModel(vaultStore = other)
            vm2.start("com.example.app", "Example")
            testScheduler.advanceUntilIdle()
            vm2.beginBackup("not stored".toCharArray(), remember = false)
            testScheduler.advanceUntilIdle()
            assertNull(other.read())
        }

    // --- the request ---------------------------------------------------------------------------

    @Test
    fun `the request carries the selection, the bundle choice and a fresh salt`() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.toggleClass(DataClass.EXTERNAL_MEDIA)
        vm.setIncludeBundle(false)

        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        val request = launcher.started!!
        assertEquals("com.example.app", request.packageName)
        assertEquals(DataClass.entries.toSet() - DataClass.EXTERNAL_MEDIA, request.classes)
        assertEquals(false, request.includeBundle)
        assertEquals(com.valhalla.thor.domain.model.KDF_SALT_BYTES, request.salt.size)
    }

    @Test
    fun `two backups of the same app get different salts`() = runTest(dispatcher) {
        // One passphrase reused across every archive must not mean one key reused across every
        // archive — the salt is the only thing standing between those two sentences.
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()
        val first = launcher.started!!.salt.toList()
        vm.dismissResult()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        assertTrue(first != launcher.started!!.salt.toList())
    }

    // --- progress and outcome ------------------------------------------------------------------

    @Test
    fun `progress published by the job reaches the state`() = runTest(dispatcher) {
        val registry = JobRegistry()
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher, registry = registry)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        registry.publish(
            launcher.jobId,
            ThorJobProgress(ThorJobStage.WRITING, "Example", completedBytes = 5L, totalBytes = 10L),
        )
        testScheduler.advanceUntilIdle()

        assertEquals(50, vm.uiState.value.progress?.percent)
        assertEquals(true, vm.uiState.value.running)
    }

    @Test
    fun `an indeterminate stage reports a null percent rather than zero`() = runTest(dispatcher) {
        val registry = JobRegistry()
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher, registry = registry)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        registry.publish(launcher.jobId, ThorJobProgress(ThorJobStage.MEASURING, "Example"))
        testScheduler.advanceUntilIdle()

        // A determinate bar sitting at 0% for the whole of `tar` is how a working backup gets
        // reported as hung. The sheet renders an indeterminate bar for null.
        assertNull(vm.uiState.value.progress?.percent)
    }

    @Test
    fun `a succeeded job stops the running state and reports success`() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        launcher.statuses.value = ThorJobStatus.Succeeded
        testScheduler.advanceUntilIdle()

        assertEquals(BackupFinish.Succeeded, vm.uiState.value.finished)
        assertEquals(false, vm.uiState.value.running)
    }

    @Test
    fun `a failed job carries the worker's own sentence`() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        launcher.statuses.value = ThorJobStatus.Failed("choose a folder for Thor's backups first")
        testScheduler.advanceUntilIdle()

        assertEquals(
            BackupFinish.Failed("choose a folder for Thor's backups first"),
            vm.uiState.value.finished,
        )
    }

    @Test
    fun `a failure with no reason still reports a failure`() = runTest(dispatcher) {
        // `Result.failure()` with no output data is reachable — WorkManager's own cancellation path
        // produces it. A null reason must not read as "no failure".
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        launcher.statuses.value = ThorJobStatus.Failed(null)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.finished is BackupFinish.Failed)
    }

    @Test
    fun `an enqueue that fails does not leave the sheet spinning`() = runTest(dispatcher) {
        val launcher = FakeLauncher(fail = true)
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.running)
        assertTrue(vm.uiState.value.finished is BackupFinish.Failed)
    }

    @Test
    fun `a job already running for this app is picked up on open`() = runTest(dispatcher) {
        // The rotation case. `jobTag` exists for exactly this; without it the sheet reopens showing
        // an idle Start button over a backup that is still writing, and a second tap queues a
        // duplicate.
        val launcher = FakeLauncher()
        launcher.running.value = launcher.jobId
        val vm = viewModel(launcher = launcher)

        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.running)
        assertEquals(false, vm.uiState.value.canStart)
        assertNull(launcher.started)
    }

    @Test
    fun `start is idempotent across recomposition`() = runTest(dispatcher) {
        // `LaunchedEffect(packageName)` re-runs after a configuration change; measuring twice is a
        // pair of `du` sweeps over gigabytes for nothing.
        var probes = 0
        val probe = object : AppDataProbe {
            override suspend fun probeDataArchiveCapability(): Boolean {
                probes++
                return true
            }

            override suspend fun sizeOf(packageName: String, dataClass: DataClass) =
                DataClassSize.Known(1L)
        }
        val vm = viewModel(probe = probe)

        vm.start("com.example.app", "Example")
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertEquals(1, probes)
    }
}
```

Note the `flowOf` import is used by no test above — drop it if the compiler warns. It is listed because `runningJobFor` is a `Flow` returning method a simpler fake might implement with it.

- [ ] **Step 2: Run the test to verify it fails**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.presentation.backup.AppBackupViewModelTest"
```

Expected: compilation failure — `ArchiveJobLauncher`, `ThorJobStatus`, `AppBackupViewModel`, `AppBackupUiState`, `BackupFinish`, `PassphraseVaultStore.isSet` unresolved.

- [ ] **Step 3: Write the port**

Create `app/src/main/java/com/valhalla/thor/domain/repository/ArchiveJobLauncher.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.ThorJobKind
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Where a job got to, as a screen needs to see it. WorkManager's `WorkInfo.State` narrowed to this. */
sealed interface ThorJobStatus {
    data object Pending : ThorJobStatus
    data object Running : ThorJobStatus
    data object Succeeded : ThorJobStatus

    /**
     * @param reason the sentence the worker put in `JOB_ERROR_KEY`, or null.
     *
     * **Null is still a failure.** WorkManager produces a bare `Result.failure()` on some of its own
     * paths, and a UI that keys "did it fail?" off a non-null reason reports those as nothing at all.
     */
    data class Failed(val reason: String?) : ThorJobStatus
    data object Cancelled : ThorJobStatus

    /** No such job — WorkManager prunes finished work, so this is the normal answer for an old id. */
    data object Gone : ThorJobStatus
}

/**
 * Start an archive job and watch it.
 *
 * A port because the implementation calls `WorkManager.getInstance(context)`, which would put every
 * consumer beyond the reach of a JVM test — the same reason `AppShortcutController` exists next to
 * `FreezerShortcutManager`. The whole surface is "enqueue this, and tell me how it goes".
 */
interface ArchiveJobLauncher {

    /** @param passphrase not cleared here; the caller owns it. @return the job id, or null if it could not be enqueued. */
    suspend fun startBackup(request: ArchiveBackupRequest, passphrase: CharArray): UUID?

    /** @param salt the archive's own, from its header — never a freshly generated one. */
    suspend fun startRestore(
        request: ArchiveRestoreRequest,
        passphrase: CharArray,
        salt: ByteArray,
    ): UUID?

    fun status(jobId: UUID): Flow<ThorJobStatus>

    /**
     * The id of an unfinished job for this kind and target, or null.
     *
     * How a screen reattaches after a rotation and how a second tap is refused. Backed by
     * `jobTag(kind, target)`, since every job shares one chain name.
     */
    fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?>
}
```

- [ ] **Step 4: Make `ThorJobLauncher` implement it**

In `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobLauncher.kt`:

1. Change the annotation to `@Single(binds = [ArchiveJobLauncher::class])` and the declaration to `class ThorJobLauncher(...) : ArchiveJobLauncher`.
2. Mark `startBackup` and `startRestore` `override`. Their signatures already match.
3. Add the two observers:

```kotlin
    override fun status(jobId: UUID): Flow<ThorJobStatus> =
        WorkManager.getInstance(context).getWorkInfoByIdFlow(jobId).map { info ->
            when (info?.state) {
                null -> ThorJobStatus.Gone
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ThorJobStatus.Pending
                WorkInfo.State.RUNNING -> ThorJobStatus.Running
                WorkInfo.State.SUCCEEDED -> ThorJobStatus.Succeeded
                WorkInfo.State.CANCELLED -> ThorJobStatus.Cancelled
                // `outputData` is where the worker's own sentence is; `getString` gives null when the
                // failure came from WorkManager rather than from `fail(...)`, which Failed allows.
                WorkInfo.State.FAILED -> ThorJobStatus.Failed(info.outputData.getString(JOB_ERROR_KEY))
            }
        }

    override fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?> =
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(jobTag(kind, target))
            .map { infos -> infos.firstOrNull { !it.state.isFinished }?.id }
```

Both `getWorkInfoByIdFlow` and `getWorkInfosByTagFlow` arrived in WorkManager 2.9.0; the catalog pins 2.11.2, so no version guard is needed. `ThorJobKind` and `jobTag` are already imported by this file.

- [ ] **Step 5: Move `isSet` onto the vault store**

The fix Task 5 Step 3's closing note asked for, now that something consumes it. In `app/src/main/java/com/valhalla/thor/data/backup/PassphraseVault.kt`:

```kotlin
/** Where the wrapped passphrase lives. Separated so the vault's logic is JVM-testable. */
interface PassphraseVaultStore {
    /** Whether a blob is stored. Not whether it can still be unwrapped — see [PassphraseVault.recall]. */
    val isSet: Flow<Boolean>
    suspend fun read(): String?
    suspend fun write(value: String?)
}
```

`DataStorePassphraseVaultStore` implements it as `override val isSet: Flow<Boolean> = flow.map { it != null }`, and `PassphraseVault.isRemembered` becomes `val isRemembered: Flow<Boolean> get() = store.isSet` — the `as? DataStorePassphraseVaultStore` downcast goes away entirely. Add `override val isSet` (a `MutableStateFlow` mapped, as in Step 1's `FakeVaultStore`) to `PassphraseVaultTest`'s `FakeStore`, or Task 5's test stops compiling.

Note the distinction the KDoc draws, because the view-model test above pins it: `isSet` answers "is there a blob", not "will it unwrap". A Keystore key invalidated by a biometric re-enrolment leaves `isSet` true and `recall()` null, and the sheet must then prompt rather than fail.

- [ ] **Step 6: Write the view model**

Create `app/src/main/java/com/valhalla/thor/presentation/backup/AppBackupViewModel.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.PassphraseVault
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.ArchiveJobLauncher
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.domain.usecase.MeasureAppDataUseCase
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

/** How a finished job is reported once. Cleared by [AppBackupViewModel.dismissResult]. */
sealed interface BackupFinish {
    data object Succeeded : BackupFinish
    data class Failed(val reason: String?) : BackupFinish
}

data class AppBackupUiState(
    val packageName: String = "",
    val appLabel: String = "",
    /**
     * Null while the capability probe is in flight; false only once Thor has asked and cannot.
     *
     * Three states, not two, for the same reason [DataClassSize] has three: a "not supported" panel
     * shown for one frame on every open is a lie the user reads before the truth arrives.
     */
    val supported: Boolean? = null,
    val sizes: Map<DataClass, DataClassSize> = emptyMap(),
    val selected: Set<DataClass> = emptySet(),
    val includeBundle: Boolean = true,
    /** Null until the store answers. Never a hardcoded "Downloads". */
    val destinationLabel: String? = null,
    val passphraseNeeded: Boolean = false,
    val progress: ThorJobProgress? = null,
    val running: Boolean = false,
    val finished: BackupFinish? = null,
) {
    /** The bundle alone is a valid backup, as is data alone. Nothing at all is not. */
    val canStart: Boolean
        get() = supported == true && !running && (selected.isNotEmpty() || includeBundle)
}

@KoinViewModel
class AppBackupViewModel(
    private val measure: MeasureAppDataUseCase,
    private val archiveStore: AppArchiveStore,
    private val vault: PassphraseVault,
    // Injected for `newSalt()` and nothing else. The cipher is pure JCE with no Android and no Thor
    // types (see deviation 10), and one fresh salt per archive is the invariant that keeps one reused
    // passphrase from meaning one reused key — so the generator belongs at the site that builds the
    // request, not somewhere it can be forgotten.
    private val cipher: AppArchiveCipher,
    private val launcher: ArchiveJobLauncher,
    private val registry: JobRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppBackupUiState())
    val uiState = _uiState.asStateFlow()

    private var started = false
    private var watching: Job? = null

    /** Idempotent: `LaunchedEffect` re-runs after a configuration change and `du` is not cheap. */
    fun start(packageName: String, appLabel: String) {
        if (started) return
        started = true
        _uiState.update { it.copy(packageName = packageName, appLabel = appLabel) }

        viewModelScope.launch {
            val measurement = measure(packageName)
            _uiState.update { state ->
                state.copy(
                    supported = measurement.supported,
                    sizes = measurement.sizes,
                    // §4.2: all default on. Including a class whose size is Undetermined — that is a
                    // failed measurement, not an empty directory, and dropping it would narrow the
                    // backup without saying so.
                    selected = if (measurement.supported) DataClass.entries.toSet() else emptySet(),
                )
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(destinationLabel = archiveStore.currentTargetLabel()) }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(passphraseNeeded = !vault.isRemembered.first()) }
        }
        // The rotation case: a job for this app may already be running, in which case this sheet is a
        // progress view rather than a form.
        viewModelScope.launch {
            launcher.runningJobFor(ThorJobKind.ARCHIVE_BACKUP, packageName).collect { id ->
                if (id != null && watching == null) watch(id)
            }
        }
    }

    fun toggleClass(dataClass: DataClass) = _uiState.update { state ->
        state.copy(
            selected = if (dataClass in state.selected) {
                state.selected - dataClass
            } else {
                state.selected + dataClass
            }
        )
    }

    fun setIncludeBundle(include: Boolean) = _uiState.update { it.copy(includeBundle = include) }

    /** §10's "use a different passphrase" affordance. Shows the field even with a filled vault. */
    fun useDifferentPassphrase() = _uiState.update { it.copy(passphraseNeeded = true) }

    fun dismissResult() = _uiState.update { it.copy(finished = null, progress = null) }

    /**
     * @param typed what the user entered, or an empty array when the field was not shown.
     * @param remember whether to cache [typed] in the vault. Ignored when [typed] is empty.
     */
    fun beginBackup(typed: CharArray, remember: Boolean) {
        val state = _uiState.value
        if (!state.canStart) return
        _uiState.update { it.copy(running = true, finished = null) }

        viewModelScope.launch {
            // An empty array means the field was not shown, so the vault is the source. A vault that
            // cannot be unwrapped is a *prompt*, not a failure: the archive would be perfectly
            // readable, it is the convenience layer that broke (§5.4).
            val passphrase = typed.takeIf { it.isNotEmpty() } ?: vault.recall()
            if (passphrase == null || passphrase.isEmpty()) {
                _uiState.update { it.copy(running = false, passphraseNeeded = true) }
                return@launch
            }
            if (remember && typed.isNotEmpty()) vault.remember(typed)

            val request = ArchiveBackupRequest(
                packageName = state.packageName,
                classes = state.selected,
                includeBundle = state.includeBundle,
                salt = cipher.newSalt(),
            )
            val id = launcher.startBackup(request, passphrase)
            if (id == null) {
                _uiState.update {
                    it.copy(running = false, finished = BackupFinish.Failed(null))
                }
            } else {
                watch(id)
            }
        }
    }

    private fun watch(jobId: UUID) {
        watching?.cancel()
        watching = viewModelScope.launch {
            _uiState.update { it.copy(running = true) }
            launch {
                registry.progressOf(jobId).collect { progress ->
                    if (progress != null) _uiState.update { it.copy(progress = progress) }
                }
            }
            launcher.status(jobId).collect { status ->
                when (status) {
                    is ThorJobStatus.Pending, is ThorJobStatus.Running ->
                        _uiState.update { it.copy(running = true) }

                    is ThorJobStatus.Succeeded -> finish(BackupFinish.Succeeded)
                    is ThorJobStatus.Failed -> finish(BackupFinish.Failed(status.reason))
                    is ThorJobStatus.Cancelled -> finish(BackupFinish.Failed(null))
                    // Reached when a finished job's record has been pruned — which is what a
                    // reattach after a long absence sees. Not a failure to report.
                    is ThorJobStatus.Gone -> _uiState.update { it.copy(running = false) }
                }
            }
        }
    }

    private fun finish(result: BackupFinish) {
        _uiState.update { it.copy(running = false, finished = result) }
        watching?.cancel()
        watching = null
    }
}
```

Two notes for the implementer:

- **`finish` cancels the coroutine it is called from.** That is intended — the status flow never completes — but it means nothing after the `collect` in `watch` will run, so do not add cleanup there. The `progressOf` child collector is cancelled with it.
- `registry.progressOf(jobId)` emits null for an unknown id, which is why the collector filters rather than assigning: an assignment would wipe a real progress value the moment the registry clears at the end of the job, replacing a full bar with an empty one for the frame before the status arrives.

- [ ] **Step 7: Write the sheet**

Create `app/src/main/java/com/valhalla/thor/presentation/backup/AppBackupSheet.kt`. It follows `ExportBottomSheet` exactly — its own `ModalBottomSheet`, its own SAF picker, dependencies through Koin rather than through parameters:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.data.backup.MIN_PASSPHRASE_LENGTH
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.SizeLabelKind
import com.valhalla.thor.domain.model.labelKind
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.presentation.utils.formatBytes
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * §10's backup sheet.
 *
 * `R` lives here rather than on [DataClass] — a `@StringRes` on a `domain/model` enum would put an
 * Android type in the layer that is defined by not having any.
 */
@StringRes
private fun dataClassLabel(dataClass: DataClass): Int = when (dataClass) {
    DataClass.CE -> R.string.backup_class_ce
    DataClass.DE -> R.string.backup_class_de
    DataClass.EXTERNAL_DATA -> R.string.backup_class_external_data
    DataClass.EXTERNAL_MEDIA -> R.string.backup_class_external_media
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBackupSheet(packageName: String, appLabel: String, onDismiss: () -> Unit) {
    val viewModel = koinViewModel<AppBackupViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val preferenceRepository = koinInject<PreferenceRepository>()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(packageName) { viewModel.start(packageName, appLabel) }

    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var rememberIt by remember { mutableStateOf(true) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // The same preference the export flow writes: one chosen folder, not two.
            scope.launch { preferenceRepository.setExportDirUri(uri.toString()) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.action_backup).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                text = state.appLabel.ifBlank { packageName },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            when (state.supported) {
                // Still probing. A spinner, never the refusal panel — see AppBackupUiState.supported.
                null -> CircularProgressIndicator()

                false -> Text(
                    text = stringResource(R.string.backup_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                true -> {
                    CheckRow(
                        checked = state.includeBundle,
                        enabled = !state.running,
                        label = stringResource(R.string.backup_include_bundle),
                        detail = stringResource(R.string.backup_include_bundle_desc),
                        onCheckedChange = viewModel::setIncludeBundle
                    )

                    DataClass.entries.forEach { dataClass ->
                        CheckRow(
                            checked = dataClass in state.selected,
                            enabled = !state.running,
                            label = stringResource(dataClassLabel(dataClass)),
                            detail = sizeLabel(state.sizes[dataClass]),
                            onCheckedChange = { viewModel.toggleClass(dataClass) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = state.destinationLabel?.let {
                                stringResource(R.string.backup_destination, it)
                            } ?: stringResource(R.string.backup_destination_pending),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { picker.launch(null) }, enabled = !state.running) {
                            Text(stringResource(R.string.backup_change_destination))
                        }
                    }

                    if (state.passphraseNeeded) {
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text(stringResource(R.string.backup_passphrase)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !state.running,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            label = { Text(stringResource(R.string.backup_passphrase_confirm)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !state.running,
                            isError = confirmation.isNotEmpty() && confirmation != passphrase,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            // §5.4, stated rather than implied: Thor cannot recover this.
                            text = stringResource(R.string.backup_passphrase_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        CheckRow(
                            checked = rememberIt,
                            enabled = !state.running,
                            label = stringResource(R.string.backup_remember_passphrase),
                            detail = null,
                            onCheckedChange = { rememberIt = it }
                        )
                    } else {
                        TextButton(
                            onClick = viewModel::useDifferentPassphrase,
                            enabled = !state.running
                        ) {
                            Text(stringResource(R.string.backup_use_different_passphrase))
                        }
                    }

                    if (state.running) {
                        val percent = state.progress?.percent
                        if (percent == null) {
                            // Indeterminate, never a determinate bar pinned at 0 — see the view-model
                            // test that names this.
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        state.progress?.let {
                            Text(
                                text = it.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val passphraseUsable = !state.passphraseNeeded ||
                        (passphrase.length >= MIN_PASSPHRASE_LENGTH && passphrase == confirmation)

                    Button(
                        onClick = {
                            viewModel.beginBackup(passphrase.toCharArray(), rememberIt)
                        },
                        enabled = state.canStart && passphraseUsable,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.backup_start))
                    }

                    state.finished?.let { finish ->
                        Text(
                            text = when (finish) {
                                BackupFinish.Succeeded -> stringResource(R.string.backup_done)
                                is BackupFinish.Failed -> stringResource(
                                    R.string.backup_failed,
                                    finish.reason ?: stringResource(R.string.backup_failed_unknown)
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (finish is BackupFinish.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sizeLabel(size: DataClassSize?): String = when (val kind = size?.labelKind()) {
    // Null is "not measured yet", which is not the same claim as Unknown ("measured, and failed").
    null -> stringResource(R.string.backup_size_measuring)
    is SizeLabelKind.Bytes -> formatBytes(kind.bytes)
    SizeLabelKind.Empty -> stringResource(R.string.backup_size_empty)
    // Never "0 B". §10.
    SizeLabelKind.Unknown -> stringResource(R.string.backup_size_unknown)
}

@Composable
private fun CheckRow(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    detail: String?,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

Two things to resolve against real code while implementing, neither of which changes the design:

1. **`formatBytes`** — this codebase already formats byte counts somewhere for the storage figures on the app detail body. Find it (`rg -n 'fun formatBytes|Formatter.formatFileSize' --glob '*.kt'`) and use it rather than adding a second one. If the existing one is a `Context` extension, wrap it here; do not reimplement the arithmetic.
2. **`LinearProgressIndicator(progress = { … })`** is the lambda-taking overload in current material3; if the project's version still takes a `Float`, drop the lambda. `rg -n 'LinearProgressIndicator' --glob '*.kt'` shows which shape the codebase already uses.

- [ ] **Step 8: Add the strings**

Append to `app/src/main/res/values/strings_backup.xml` — the file Task 8 created — inside its existing `<resources>` element. **Not** `values/strings.xml`; see Global Constraints.

```xml
    <string name="action_backup">Back up</string>
    <string name="backup_unsupported">Thor cannot read another app\'s private data with the access it has right now. This needs root, or Shizuku started from root.</string>
    <string name="backup_include_bundle">Include the app installer</string>
    <string name="backup_include_bundle_desc">Lets this backup reinstall the app if it is gone.</string>
    <string name="backup_class_ce">App data</string>
    <string name="backup_class_de">Startup data</string>
    <string name="backup_class_external_data">Files on shared storage</string>
    <string name="backup_class_external_media">Media on shared storage</string>
    <string name="backup_size_measuring">Checking size…</string>
    <string name="backup_size_empty">Empty</string>
    <string name="backup_size_unknown">Size unknown</string>
    <string name="backup_destination">Saving to %1$s</string>
    <string name="backup_destination_pending">Finding the backup folder…</string>
    <string name="backup_change_destination">Change</string>
    <string name="backup_passphrase">Passphrase</string>
    <string name="backup_passphrase_confirm">Confirm passphrase</string>
    <string name="backup_passphrase_warning">Thor cannot recover this passphrase. Without it, this backup cannot be restored.</string>
    <string name="backup_remember_passphrase">Remember it on this device</string>
    <string name="backup_use_different_passphrase">Use a different passphrase</string>
    <string name="backup_start">Back up</string>
    <string name="backup_done">Backup saved.</string>
    <string name="backup_failed">Backup failed: %1$s</string>
    <string name="backup_failed_unknown">it stopped without saying why</string>
```

`backup_class_de` is *"Startup data"* rather than "Device-encrypted data": the class exists because apps keep first-run and keyset state there, which is what a user needs to know, and the encryption mode is not.

- [ ] **Step 9: Hang the action off the sheet**

In `app/src/main/java/com/valhalla/thor/presentation/widgets/AppActionRow.kt`, add a parameter beside `onExport`:

```kotlin
    /** Null hides the tile — the same convention as `onToggleFreezerMembership` and `onOpenDetails`. */
    onBackup: (() -> Unit)? = null,
```

and a tile immediately after the Export one:

```kotlin
        // Gated on privilege *as well as* on the callback: there is no unprivileged path to another
        // app's data at all, so a host that offers this without a shell would be offering a sheet
        // whose only possible content is a refusal. Same shape as the Fix Store gate below.
        if (hasPrivilege) {
            onBackup?.let { backup ->
                ActionItem(
                    icon = R.drawable.settings_backup_restore,
                    label = stringResource(R.string.action_backup),
                    onClick = backup
                )
            }
        }
```

In `app/src/main/java/com/valhalla/thor/presentation/widgets/AppInfoSheet.kt`:

```kotlin
    var showBackupSheet by remember { mutableStateOf(false) }
```

pass `onBackup = { showBackupSheet = true }` in the `AppActionRow` call, and host it beside the export sheet at the bottom of the composable:

```kotlin
    if (showBackupSheet) {
        AppBackupSheet(
            packageName = appInfo.packageName,
            appLabel = appInfo.appName ?: appInfo.packageName,
            onDismiss = { showBackupSheet = false }
        )
    }
```

`AppInfoSheet` does **not** dismiss itself when the backup sheet opens, matching what it already does for `ExportBottomSheet`: the job runs in a foreground service, so closing the sheet is not what cancels it, and leaving the host up means the user lands back on the app they were looking at.

- [ ] **Step 10: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.presentation.backup.AppBackupViewModelTest" --tests "com.valhalla.thor.data.backup.PassphraseVaultTest"
```

Expected: PASS. Both files, because Step 5 changed an interface `PassphraseVaultTest` implements.

- [ ] **Step 11: Build**

```
./gradlew :app:assembleFossDebug
```

Expected: BUILD SUCCESSFUL. `@Single(binds = [ArchiveJobLauncher::class])` plus `@KoinViewModel` with a six-dependency constructor is the part `strictSafety` will have an opinion about; read the message rather than adding a binding on instinct.

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/repository/ArchiveJobLauncher.kt \
  app/src/main/java/com/valhalla/thor/presentation/backup/AppBackupViewModel.kt \
  app/src/main/java/com/valhalla/thor/presentation/backup/AppBackupSheet.kt \
  app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobLauncher.kt \
  app/src/main/java/com/valhalla/thor/data/backup/PassphraseVault.kt \
  app/src/main/java/com/valhalla/thor/presentation/widgets/AppActionRow.kt \
  app/src/main/java/com/valhalla/thor/presentation/widgets/AppInfoSheet.kt \
  app/src/main/res/values/strings_backup.xml \
  app/src/test/java/com/valhalla/thor/presentation/backup/AppBackupViewModelTest.kt \
  app/src/test/java/com/valhalla/thor/data/backup/PassphraseVaultTest.kt
git commit -m "feat(backup): a backup sheet with per-class selection and live progress"
```

---

### Task 17: The restore screen, its entry points, and the interruption notice

§10's second half: *"Restore is its own entry point — from Settings, or from opening a `.thorbak`. It shows the header's contents and every gate outcome from §8.1 before anything destructive happens."* Plus §8.5's breadcrumb, which until now is written, swept and logged but never shown to anyone.

**Five decisions to state up front.**

1. **Two entry points, one screen.** `ThorRoute.ArchiveRestore(uriString: String?)` — null means "the user came from Settings and still has to pick a file". The VIEW path supplies the URI. One screen, one view model, one gate.
2. **The `.thorbak` VIEW filter carries a `pathPattern`, and it is a partial capability on purpose.** `PortableInstallerActivity` **already** claims `content:` + `application/octet-stream` and `application/zip` (`AndroidManifest.xml:96-120`), which is what a file manager reports for a `.thorbak`. A second filter on the same mime with no path constraint would put *two* Thor entries in the chooser for every zip and every unknown binary on the device. `android:pathPattern=".*\\.thorbak"` narrows it to the file Thor means, at the cost of only matching providers whose URI path ends in the file name — `com.android.externalstorage.documents` does, `com.android.providers.downloads.documents` does not. A follow-up row covers registering the extension properly; a chooser that stays clean for every other file is worth more than a filter that fires from Downloads.
3. **A wrong passphrase is rejected before anything is enqueued, and that costs a second PBKDF2 run.** `OpenArchiveUseCase.unlock` derives a key to check the header's verifier; `ThorJobLauncher.startRestore` then derives it again from the passphrase. Roughly 150–400 ms paid twice on a job that moves gigabytes, in exchange for one path in and out of the launcher. The key `unlock` returns is used as a yes/no answer and discarded — the alternative, a `startRestoreWithKey` overload, adds a second enqueue path to keep in sync forever.
4. **The interruption notice is surfaced in Settings and cleared from the restore screen.** Task 15's launch sweep deliberately does not clear the breadcrumb; something has to tell the user. Settings reads the store through `produceState` (one small file read, off the main thread, no new view model), and the restore screen reads it in `init` and clears it when the user acknowledges. A launch-time notice on Home is a follow-up row, not this task.
5. **`RestoreFinish` is its own type, not Task 16's `BackupFinish`.** They are structurally identical two-member sealed interfaces. Sharing one would mean a restore reporting through a type named *Backup*, and the plan would rather carry six duplicated lines than that.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/usecase/ReadInstalledAppFactsUseCase.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/backup/ArchiveRestoreViewModel.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/backup/ArchiveRestoreScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/model/AppDataArchive.kt` (add `ArchiveKdf.saltBytes()`)
- Modify: `app/src/main/java/com/valhalla/thor/domain/usecase/OpenArchiveUseCase.kt` (use it, so the salt has one decoder)
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/AppArchiveWorker.kt` (the restore worker takes the new use case instead of building the facts inline)
- Modify: `app/src/main/java/com/valhalla/thor/presentation/navigation/ThorRoute.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/HomeActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings_backup.xml` (the file Task 8 created — **not** `values/strings.xml`)
- Test: `app/src/test/java/com/valhalla/thor/presentation/backup/ArchiveRestoreViewModelTest.kt`

**Interfaces:**
- Consumes: `ArchiveSourceFactory`, `ArchiveSource`, `OpenArchiveUseCase.readHeader`/`unlock`, `ArchiveHeaderOutcome`, `ArchiveUnlockOutcome` (Task 12); `evaluateArchiveRestoreGate`, `ArchiveRestoreDecision`, `ArchiveRestoreRefusal`, `ArchiveRestoreWarning`, `InstalledAppFacts` (Task 11); `ArchiveHeader`, `ArchiveMember`, `ArchiveKdf`, `DataClass`, `THORBAK_EXTENSION` (Task 2); `ArchiveBreadcrumbStore`, `ArchiveBreadcrumb` (Task 14); `ArchiveRestoreRequest`, `ArchiveJobLauncher`, `ThorJobStatus`, `ThorJobKind`, `JobRegistry`, `ThorJobProgress` (Tasks 15, 16); `PassphraseVault` (Task 5); `AppDataProbe` (Task 9); `AppRepository`, `AppDataArchiveGateway` (existing / Task 9).
- Produces: `ReadInstalledAppFactsUseCase.invoke(packageName): InstalledAppFacts?`; `ArchiveKdf.saltBytes(): ByteArray?`; `ArchiveRestoreViewModel` with `open(uriString)`, `toggleClass`, `setRestoreObb`, `setConfirmed`, `submitPassphrase(CharArray)`, `useDifferentPassphrase()`, `beginRestore()`, `acknowledgeInterruption()`, `dismissResult()`, `uiState: StateFlow<ArchiveRestoreUiState>`; `RestoreFinish`; `ArchiveRestoreScreen(uriString, onBack)`; `ThorRoute.ArchiveRestore`.

- [ ] **Step 1: Write the failing view-model test**

Create `app/src/test/java/com/valhalla/thor/presentation/backup/ArchiveRestoreViewModelTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.PassphraseVault
import com.valhalla.thor.data.backup.PassphraseVaultStore
import com.valhalla.thor.data.backup.VaultKeyProvider
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveBundleInfo
import com.valhalla.thor.domain.model.ArchiveBreadcrumb
import com.valhalla.thor.domain.model.ArchiveCompression
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveKdf
import com.valhalla.thor.domain.model.ArchiveMember
import com.valhalla.thor.domain.model.ArchiveRestoreRefusal
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.ArchiveRestoreWarning
import com.valhalla.thor.domain.model.ClassEntries
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.model.TarOutcome
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveJobLauncher
import com.valhalla.thor.domain.repository.ArchiveSource
import com.valhalla.thor.domain.repository.ArchiveSourceFactory
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.domain.usecase.OpenArchiveUseCase
import com.valhalla.thor.domain.usecase.ReadInstalledAppFactsUseCase
import com.valhalla.thor.presentation.FakeAppRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveRestoreViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val cipher = AppArchiveCipher()
    private val salt = ByteArray(16) { it.toByte() }
    private val passphrase = "correct horse"

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --- the archive under test ------------------------------------------------------------------

    /**
     * `iterations = 1000`, not [com.valhalla.thor.data.backup.KDF_ITERATIONS].
     *
     * `unlock` derives with whatever the header declares, so a low count keeps this suite fast — at
     * 210 000 rounds the derivations in these tests would add several seconds. The production floor is
     * Task 4's concern; what this file tests is which *answer* a derivation produces.
     */
    private fun header(
        packageName: String = "com.example.game",
        versionCode: Long = 100L,
        signer: String = SIGNER,
        classes: List<DataClass> = listOf(DataClass.CE, DataClass.DE),
        bundle: ArchiveBundleInfo? = ArchiveBundleInfo(
            bytes = 4_096L,
            obbCapture = "present",
            obbCount = 2,
        ),
        schemaVersion: Int = 1,
    ): ArchiveHeader {
        val key = cipher.deriveKey(passphrase.toCharArray(), salt, 1000)
        return ArchiveHeader(
            schemaVersion = schemaVersion,
            createdAt = 1_700_000_000_000L,
            thorVersionCode = 1950,
            packageName = packageName,
            versionCode = versionCode,
            versionName = "1.0",
            userId = 0,
            signerSha256 = signer,
            appBundle = bundle,
            kdf = ArchiveKdf(iterations = 1000, salt = Base64.getEncoder().encodeToString(salt)),
            verifier = Base64.getEncoder().encodeToString(cipher.verifier(key)),
            members = classes.map {
                ArchiveMember(
                    dataClass = it.id,
                    fileName = "${it.id}.tar.gz.enc",
                    nonce = Base64.getEncoder().encodeToString(ByteArray(8)),
                    plainBytes = 2_048L,
                    chunkCount = 1,
                    compression = ArchiveCompression.GZIP.id,
                )
            },
        )
    }

    private companion object {
        const val SIGNER = "ABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABAB"
        const val URI = "content://com.example.docs/document/1"
    }

    // --- doubles -------------------------------------------------------------------------------

    private class FakeSource(private val headerJson: String?) : ArchiveSource {
        var closed = false
        override val displayName = "com.example.game-100.thorbak"
        override fun entryNames(): List<String> = listOfNotNull(headerJson?.let { THORBAK_HEADER_ENTRY })
        override fun openEntry(name: String): InputStream? =
            if (name == THORBAK_HEADER_ENTRY && headerJson != null) {
                ByteArrayInputStream(headerJson.encodeToByteArray())
            } else {
                null
            }

        override fun close() {
            closed = true
        }
    }

    private class FakeSources(val source: FakeSource?) : ArchiveSourceFactory {
        override suspend fun open(uriString: String): ArchiveSource? = source
    }

    private class FakeProbe(val capable: Boolean = true) : AppDataProbe {
        override suspend fun probeDataArchiveCapability(): Boolean = capable
        override suspend fun sizeOf(packageName: String, dataClass: DataClass): DataClassSize =
            DataClassSize.Undetermined
    }

    /**
     * Only [signerSha256] is exercised here; the rest are inert.
     *
     * A third hand-written copy of this fake (Tasks 9 and 14 have the others). Task 18 carries a
     * follow-up row to hoist one shared double — deliberately not done mid-plan, because it would
     * reopen two already-green test files.
     */
    private class FakeArchiveGateway(private val signer: String?) : AppDataArchiveGateway {
        override suspend fun thorUserId(): Int = 0
        override suspend fun externalStorageDir(): String = "/storage/emulated/0"
        override suspend fun stagingFile(name: String): File = File("/tmp/$name")
        override suspend fun forceStop(packageName: String) = Unit
        override suspend fun listClass(packageName: String, dataClass: DataClass) =
            ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)

        override suspend fun tarClass(
            packageName: String,
            dataClass: DataClass,
            entries: List<String>,
            out: File,
            compress: Boolean,
        ): TarOutcome = TarOutcome.Failed("not used in this test")

        override suspend fun appUid(packageName: String): Int? = null
        override suspend fun signerSha256(packageName: String): String? = signer
    }

    private class FakeVaultStore(initial: String? = null) : PassphraseVaultStore {
        private val state = MutableStateFlow(initial)
        override val isSet: Flow<Boolean> = state.map { it != null }
        override suspend fun read(): String? = state.value
        override suspend fun write(value: String?) {
            state.value = value
        }
    }

    private class PlainKeyProvider : VaultKeyProvider {
        override fun wrap(plaintext: ByteArray): ByteArray = plaintext
        override fun unwrap(blob: ByteArray): ByteArray = blob
    }

    private class FakeLauncher(
        val jobId: UUID = UUID.fromString("00000000-0000-0000-0000-0000deadbeef"),
        val statuses: MutableStateFlow<ThorJobStatus> = MutableStateFlow(ThorJobStatus.Running),
        val running: MutableStateFlow<UUID?> = MutableStateFlow(null),
    ) : ArchiveJobLauncher {
        var started: ArchiveRestoreRequest? = null
        var startedSalt: ByteArray? = null

        override suspend fun startBackup(request: ArchiveBackupRequest, passphrase: CharArray): UUID? = null

        override suspend fun startRestore(
            request: ArchiveRestoreRequest,
            passphrase: CharArray,
            salt: ByteArray,
        ): UUID {
            started = request
            startedSalt = salt
            return jobId
        }

        override fun status(jobId: UUID): Flow<ThorJobStatus> = statuses
        override fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?> = running
    }

    private class FakeBreadcrumbs(var current: ArchiveBreadcrumb? = null) : ArchiveBreadcrumbStore {
        var cleared = false
        override suspend fun write(packageName: String, appLabel: String) {
            current = ArchiveBreadcrumb(packageName, appLabel, startedAt = 1L)
        }

        override suspend fun read(): ArchiveBreadcrumb? = current
        override suspend fun clear() {
            cleared = true
            current = null
        }
    }

    private fun viewModel(
        head: ArchiveHeader? = header(),
        installedApps: List<AppInfo> = listOf(
            AppInfo(packageName = "com.example.game", appName = "Game", versionCode = 100L)
        ),
        signer: String? = SIGNER,
        probe: AppDataProbe = FakeProbe(),
        launcher: ArchiveJobLauncher = FakeLauncher(),
        vaultStore: PassphraseVaultStore = FakeVaultStore(),
        breadcrumbs: ArchiveBreadcrumbStore = FakeBreadcrumbs(),
        registry: JobRegistry = JobRegistry(),
        sources: ArchiveSourceFactory = FakeSources(FakeSource(head?.encode())),
    ) = ArchiveRestoreViewModel(
        sources = sources,
        openArchive = OpenArchiveUseCase(cipher, dispatcher),
        probe = probe,
        installedFacts = ReadInstalledAppFactsUseCase(
            appRepository = FakeAppRepository(installedApps),
            gateway = FakeArchiveGateway(signer),
        ),
        vault = PassphraseVault(vaultStore, PlainKeyProvider()),
        launcher = launcher,
        registry = registry,
        breadcrumbs = breadcrumbs,
    )

    // --- opening the file -----------------------------------------------------------------------

    @Test
    fun `the header is read and every class it holds is selected`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("com.example.game", state.header?.packageName)
        assertEquals(setOf(DataClass.CE, DataClass.DE), state.selected)
        assertEquals("com.example.game-100.thorbak", state.fileName)
        assertNull(state.error)
    }

    @Test
    fun `the source is closed once the header has been read`() = runTest(dispatcher) {
        // An unclosed ArchiveSource is a leaked ParcelFileDescriptor. The screen only needs the
        // header; the worker opens the container again for itself.
        val source = FakeSource(header().encode())
        val vm = viewModel(sources = FakeSources(source))

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertTrue(source.closed)
    }

    @Test
    fun `a file that is not a thorbak is reported without a gate decision`() = runTest(dispatcher) {
        val vm = viewModel(sources = FakeSources(FakeSource(headerJson = null)))

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.error!!.contains(THORBAK_HEADER_ENTRY))
        assertNull(vm.uiState.value.header)
        assertEquals(false, vm.uiState.value.canStart)
    }

    @Test
    fun `a file that cannot be opened at all is reported`() = runTest(dispatcher) {
        val vm = viewModel(sources = FakeSources(source = null))

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.error!!.isNotBlank())
        assertEquals(false, vm.uiState.value.loading)
    }

    @Test
    fun `an incapable privilege state is reported and nothing can start`() = runTest(dispatcher) {
        val vm = viewModel(probe = FakeProbe(capable = false))

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.supported)
        assertEquals(false, vm.uiState.value.canStart)
    }

    // --- the gate, shown before anything destructive ---------------------------------------------

    @Test
    fun `a signer mismatch refuses and no passphrase is even asked for`() = runTest(dispatcher) {
        val vm = viewModel(signer = "CD".repeat(32))

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(ArchiveRestoreRefusal.SIGNER_MISMATCH, vm.uiState.value.refusal)
        assertEquals(false, vm.uiState.value.canStart)
        // Asking for a passphrase for an archive that will never be read is a question with no
        // purpose, and it invites the user to believe the refusal is about the passphrase.
        assertEquals(false, vm.uiState.value.passphraseNeeded)
    }

    @Test
    fun `an unreadable signer refuses rather than being treated as a match`() = runTest(dispatcher) {
        val vm = viewModel(signer = null)

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(ArchiveRestoreRefusal.SIGNER_UNVERIFIABLE, vm.uiState.value.refusal)
    }

    @Test
    fun `an absent app with a bundle is allowed and says it will install first`() = runTest(dispatcher) {
        val vm = viewModel(installedApps = emptyList(), signer = null)

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        // signer = null does not matter here: an app that is not installed has no signer to compare,
        // and the gate tests absence *before* the signer for exactly this case.
        assertNull(vm.uiState.value.refusal)
        assertEquals(true, vm.uiState.value.installFirst)
    }

    @Test
    fun `an absent app and a data-only archive refuses`() = runTest(dispatcher) {
        val vm = viewModel(head = header(bundle = null), installedApps = emptyList())

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(ArchiveRestoreRefusal.DATA_ONLY_AND_APP_ABSENT, vm.uiState.value.refusal)
    }

    @Test
    fun `an older installed version warns without refusing`() = runTest(dispatcher) {
        val vm = viewModel(
            head = header(versionCode = 200L),
            installedApps = listOf(AppInfo(packageName = "com.example.game", versionCode = 100L)),
        )

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.refusal)
        assertEquals(
            listOf(ArchiveRestoreWarning.INSTALLED_VERSION_OLDER),
            vm.uiState.value.warnings,
        )
    }

    @Test
    fun `deselecting DE raises the CE-without-DE warning as soon as the box is unticked`() =
        runTest(dispatcher) {
            // The gate is re-run on every selection change, not once at open: its warnings are about
            // the *selection*, and a warning that only appears after the destructive step begins is
            // not a warning.
            val vm = viewModel()
            vm.open(URI)
            testScheduler.advanceUntilIdle()
            assertTrue(vm.uiState.value.warnings.isEmpty())

            vm.toggleClass(DataClass.DE)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(ArchiveRestoreWarning.CE_WITHOUT_DE), vm.uiState.value.warnings)
        }

    @Test
    fun `unticking everything refuses instead of quietly disabling the button`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.open(URI)
        testScheduler.advanceUntilIdle()

        vm.toggleClass(DataClass.CE)
        vm.toggleClass(DataClass.DE)
        testScheduler.advanceUntilIdle()

        assertEquals(ArchiveRestoreRefusal.NOTHING_SELECTED, vm.uiState.value.refusal)
    }

    @Test
    fun `an archive from a newer Thor refuses`() = runTest(dispatcher) {
        val vm = viewModel(head = header(schemaVersion = 99))

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(ArchiveRestoreRefusal.SCHEMA_TOO_NEW, vm.uiState.value.refusal)
    }

    // --- passphrase ----------------------------------------------------------------------------

    @Test
    fun `an empty vault asks for the passphrase`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.passphraseNeeded)
        assertEquals(false, vm.uiState.value.unlocked)
    }

    @Test
    fun `a remembered passphrase that opens the archive unlocks it without a prompt`() =
        runTest(dispatcher) {
            val store = FakeVaultStore()
            PassphraseVault(store, PlainKeyProvider()).remember(passphrase.toCharArray())
            val vm = viewModel(vaultStore = store)

            vm.open(URI)
            testScheduler.advanceUntilIdle()

            assertEquals(true, vm.uiState.value.unlocked)
            assertEquals(false, vm.uiState.value.passphraseNeeded)
        }

    @Test
    fun `a remembered passphrase that does not open this archive prompts, and says nothing about corruption`() =
        runTest(dispatcher) {
            // §5.4. The vault is a cache: the archive was made with whatever passphrase was current
            // then, and "wrong stored passphrase" must never be reported as a damaged backup.
            val store = FakeVaultStore()
            PassphraseVault(store, PlainKeyProvider()).remember("some other one".toCharArray())
            val vm = viewModel(vaultStore = store)

            vm.open(URI)
            testScheduler.advanceUntilIdle()

            assertEquals(true, vm.uiState.value.passphraseNeeded)
            assertEquals(false, vm.uiState.value.unlocked)
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun `a wrong typed passphrase reports itself and leaves the screen usable`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.open(URI)
        testScheduler.advanceUntilIdle()

        vm.submitPassphrase("wrong one".toCharArray())
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.unlocked)
        assertTrue(vm.uiState.value.passphraseError!!.isNotBlank())
        assertEquals(true, vm.uiState.value.passphraseNeeded)
    }

    @Test
    fun `the right typed passphrase unlocks it`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.open(URI)
        testScheduler.advanceUntilIdle()

        vm.submitPassphrase(passphrase.toCharArray())
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.unlocked)
        assertNull(vm.uiState.value.passphraseError)
    }

    // --- starting ------------------------------------------------------------------------------

    @Test
    fun `an unlocked archive still cannot start until the replacement is confirmed`() =
        runTest(dispatcher) {
            // Restore replaces a class wholesale. The confirmation is the only place the user is told
            // that in those words, so it gates the button rather than decorating it.
            val launcher = FakeLauncher()
            val vm = viewModel(launcher = launcher)
            vm.open(URI)
            testScheduler.advanceUntilIdle()
            vm.submitPassphrase(passphrase.toCharArray())
            testScheduler.advanceUntilIdle()

            assertEquals(false, vm.uiState.value.canStart)
            vm.beginRestore()
            testScheduler.advanceUntilIdle()
            assertNull(launcher.started)

            vm.setConfirmed(true)
            assertEquals(true, vm.uiState.value.canStart)
        }

    @Test
    fun `the request carries the selection, the OBB choice, and the archive's own salt`() =
        runTest(dispatcher) {
            val launcher = FakeLauncher()
            val vm = viewModel(launcher = launcher)
            vm.open(URI)
            testScheduler.advanceUntilIdle()
            vm.submitPassphrase(passphrase.toCharArray())
            testScheduler.advanceUntilIdle()
            vm.toggleClass(DataClass.DE)
            vm.setRestoreObb(false)
            vm.setConfirmed(true)

            vm.beginRestore()
            testScheduler.advanceUntilIdle()

            val request = launcher.started!!
            assertEquals(URI, request.uriString)
            assertEquals("com.example.game", request.packageName)
            assertEquals(setOf(DataClass.CE), request.classes)
            assertEquals(false, request.restoreObb)
            // The archive's salt, not a fresh one — a restore derives the key the backup used or it
            // derives the wrong key.
            assertTrue(salt.contentEquals(launcher.startedSalt))
        }

    @Test
    fun `OBB defaults on only when the archive actually holds some`() = runTest(dispatcher) {
        val withObb = viewModel()
        withObb.open(URI)
        testScheduler.advanceUntilIdle()
        assertEquals(true, withObb.uiState.value.restoreObb)

        val withoutObb = viewModel(
            head = header(bundle = ArchiveBundleInfo(bytes = 10L, obbCapture = "none", obbCount = 0))
        )
        withoutObb.open(URI)
        testScheduler.advanceUntilIdle()
        assertEquals(false, withoutObb.uiState.value.restoreObb)
        assertEquals(false, withoutObb.uiState.value.obbOffered)
    }

    @Test
    fun `a refused archive cannot be started even if everything else is ticked`() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val vm = viewModel(signer = "CD".repeat(32), launcher = launcher)
        vm.open(URI)
        testScheduler.advanceUntilIdle()
        vm.setConfirmed(true)

        vm.beginRestore()
        testScheduler.advanceUntilIdle()

        assertNull(launcher.started)
    }

    // --- progress and outcome ------------------------------------------------------------------

    @Test
    fun `progress reaches the state and a success is reported`() = runTest(dispatcher) {
        val registry = JobRegistry()
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher, registry = registry)
        vm.open(URI)
        testScheduler.advanceUntilIdle()
        vm.submitPassphrase(passphrase.toCharArray())
        testScheduler.advanceUntilIdle()
        vm.setConfirmed(true)
        vm.beginRestore()
        testScheduler.advanceUntilIdle()

        registry.publish(
            launcher.jobId,
            ThorJobProgress(ThorJobStage.RESTORING, "Game", completedBytes = 1L, totalBytes = 4L),
        )
        testScheduler.advanceUntilIdle()
        assertEquals(25, vm.uiState.value.progress?.percent)

        launcher.statuses.value = ThorJobStatus.Succeeded
        testScheduler.advanceUntilIdle()
        assertEquals(RestoreFinish.Succeeded, vm.uiState.value.finished)
        assertEquals(false, vm.uiState.value.running)
    }

    @Test
    fun `a failure carries the worker's sentence`() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.open(URI)
        testScheduler.advanceUntilIdle()
        vm.submitPassphrase(passphrase.toCharArray())
        testScheduler.advanceUntilIdle()
        vm.setConfirmed(true)
        vm.beginRestore()
        testScheduler.advanceUntilIdle()

        launcher.statuses.value = ThorJobStatus.Failed("ce was already replaced")
        testScheduler.advanceUntilIdle()

        assertEquals(RestoreFinish.Failed("ce was already replaced"), vm.uiState.value.finished)
    }

    @Test
    fun `a restore already running for this app is picked up on open`() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        launcher.running.value = launcher.jobId
        val vm = viewModel(launcher = launcher)

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.running)
        assertEquals(false, vm.uiState.value.canStart)
    }

    // --- the interruption breadcrumb -------------------------------------------------------------

    @Test
    fun `an interrupted restore is surfaced and is not cleared just by being read`() =
        runTest(dispatcher) {
            // Task 15's launch sweep deliberately leaves the breadcrumb alone. If merely reading it
            // cleared it, a user who rotated the screen would never see the warning again.
            val crumbs = FakeBreadcrumbs(ArchiveBreadcrumb("com.example.other", "Other", startedAt = 7L))
            val vm = viewModel(breadcrumbs = crumbs)

            vm.open(URI)
            testScheduler.advanceUntilIdle()

            assertEquals("com.example.other", vm.uiState.value.interrupted?.packageName)
            assertFalse(crumbs.cleared)
        }

    @Test
    fun `acknowledging the interruption clears it`() = runTest(dispatcher) {
        val crumbs = FakeBreadcrumbs(ArchiveBreadcrumb("com.example.other", "Other", startedAt = 7L))
        val vm = viewModel(breadcrumbs = crumbs)
        vm.open(URI)
        testScheduler.advanceUntilIdle()

        vm.acknowledgeInterruption()
        testScheduler.advanceUntilIdle()

        assertTrue(crumbs.cleared)
        assertNull(vm.uiState.value.interrupted)
    }

    @Test
    fun `opening no file still reports an interruption`() = runTest(dispatcher) {
        // The Settings entry point arrives with no URI. That is the most likely way a user reaches
        // this screen after a crash, so the notice cannot depend on a file having been picked.
        val crumbs = FakeBreadcrumbs(ArchiveBreadcrumb("com.example.other", "Other", startedAt = 7L))
        val vm = viewModel(breadcrumbs = crumbs)

        testScheduler.advanceUntilIdle()

        assertEquals("com.example.other", vm.uiState.value.interrupted?.packageName)
        assertNull(vm.uiState.value.header)
    }
}
```

Two details worth naming, because both will look like mistakes:

- `AppInfo(packageName = …, versionCode = …)` relies on `AppInfo`'s defaults for every other field. That is how the existing `FakeAppRepository`-based tests build one; check `AppInfo`'s constructor while implementing and add whatever is genuinely required.
- The last test never calls `open`. The breadcrumb read is in `init`, so it must land with no file at all.

- [ ] **Step 2: Run the test to verify it fails**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.presentation.backup.ArchiveRestoreViewModelTest"
```

Expected: compilation failure — `ReadInstalledAppFactsUseCase`, `ArchiveRestoreViewModel`, `ArchiveRestoreUiState`, `RestoreFinish` unresolved.

- [ ] **Step 3: Write `ReadInstalledAppFactsUseCase` and converge the worker onto it**

Create `app/src/main/java/com/valhalla/thor/domain/usecase/ReadInstalledAppFactsUseCase.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.InstalledAppFacts
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.AppRepository
import org.koin.core.annotation.Factory

/**
 * What the §8.1 gate needs to know about the app as it is installed right now, or null if it is not.
 *
 * Two call sites — the restore screen, which shows the gate's answer, and the restore worker, which
 * re-runs it after the chain drains. They have to agree on what "installed" means, so they share this
 * rather than each assembling the facts from a repository and a gateway.
 */
@Factory
class ReadInstalledAppFactsUseCase(
    private val appRepository: AppRepository,
    private val gateway: AppDataArchiveGateway,
) {

    suspend operator fun invoke(packageName: String): InstalledAppFacts? {
        val app = appRepository.getAppDetails(packageName) ?: return null
        return InstalledAppFacts(
            // Null here is *not* "no signer" — the gate refuses on it. See InstalledAppFacts.
            signerSha256 = gateway.signerSha256(packageName),
            versionCode = app.versionCode,
            versionName = app.versionName,
        )
    }
}
```

In `app/src/main/java/com/valhalla/thor/data/backup/job/AppArchiveWorker.kt`, replace `ArchiveRestoreWorker`'s `appRepository` **and** `gateway` constructor parameters with `private val installedFacts: ReadInstalledAppFactsUseCase` plus a retained `private val appRepository: AppRepository` (the worker still reads `appName` for the progress label), and replace the inline block:

```kotlin
            val installed = installedFacts(request.packageName)
```

`gateway` had no other use in that worker, so it comes out of the constructor entirely. Task 15's own note about not silently reopening a neighbouring file applies in reverse here: this edit is named, and its point is that "installed" is defined once.

- [ ] **Step 4: Give the salt one decoder**

Append to `app/src/main/java/com/valhalla/thor/domain/model/AppDataArchive.kt`:

```kotlin
/**
 * The KDF salt as bytes, or null when the header's Base64 will not decode.
 *
 * `java.util.Base64`, never `android.util.Base64`: the latter throws "not mocked" under a JVM test,
 * and this is called from `domain`, which every test can reach.
 *
 * Null is an answer, not an exception — a header from a corrupted download reaches this, and
 * `deriveKey` has a `require` on the salt length that would otherwise crash a worker.
 */
fun ArchiveKdf.saltBytes(): ByteArray? =
    runCatching { java.util.Base64.getDecoder().decode(salt) }.getOrNull()
```

and in `app/src/main/java/com/valhalla/thor/domain/usecase/OpenArchiveUseCase.kt`, replace the salt decode inside `unlock` with it:

```kotlin
        val salt = header.kdf.saltBytes()
            ?: return ArchiveUnlockOutcome.Unsupported("this archive's salt could not be read")
```

The private `decodeBase64` helper stays — `verifier` still uses it. One field, one decoder; the restore screen needs the same bytes to hand to the launcher, and a second copy of that decode is how the two drift.

- [ ] **Step 5: Write the view model**

Create `app/src/main/java/com/valhalla/thor/presentation/backup/ArchiveRestoreViewModel.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.backup.PassphraseVault
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.domain.model.ArchiveBreadcrumb
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveRestoreDecision
import com.valhalla.thor.domain.model.ArchiveRestoreRefusal
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.ArchiveRestoreWarning
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.InstalledAppFacts
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.evaluateArchiveRestoreGate
import com.valhalla.thor.domain.model.saltBytes
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveJobLauncher
import com.valhalla.thor.domain.repository.ArchiveSourceFactory
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.domain.usecase.ArchiveHeaderOutcome
import com.valhalla.thor.domain.usecase.ArchiveUnlockOutcome
import com.valhalla.thor.domain.usecase.OpenArchiveUseCase
import com.valhalla.thor.domain.usecase.ReadInstalledAppFactsUseCase
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

sealed interface RestoreFinish {
    data object Succeeded : RestoreFinish
    data class Failed(val reason: String?) : RestoreFinish
}

data class ArchiveRestoreUiState(
    val loading: Boolean = false,
    /** The archive's own display name, for a message. Never a path. */
    val fileName: String? = null,
    val header: ArchiveHeader? = null,
    /** Why this file cannot be used at all — not a gate refusal, which needs a readable header. */
    val error: String? = null,
    val supported: Boolean? = null,
    val refusal: ArchiveRestoreRefusal? = null,
    val warnings: List<ArchiveRestoreWarning> = emptyList(),
    val installFirst: Boolean = false,
    val selected: Set<DataClass> = emptySet(),
    /** False when the archive holds no OBB, in which case the checkbox is not drawn at all. */
    val obbOffered: Boolean = false,
    val restoreObb: Boolean = false,
    val passphraseNeeded: Boolean = false,
    val passphraseError: String? = null,
    val unlocked: Boolean = false,
    /** The user has read what "replace" means and agreed to it. */
    val confirmed: Boolean = false,
    val progress: ThorJobProgress? = null,
    val running: Boolean = false,
    val finished: RestoreFinish? = null,
    /** §8.5: a restore that never finished, from this launch or an earlier one. */
    val interrupted: ArchiveBreadcrumb? = null,
) {
    val canStart: Boolean
        get() = supported == true &&
            header != null &&
            refusal == null &&
            unlocked &&
            confirmed &&
            !running
}

/**
 * §10's restore screen.
 *
 * Reads the header, runs §8.1's gate, and re-runs it on every selection change — a warning that only
 * appears once the destructive step has begun is not a warning. Nothing here writes to the device:
 * the button hands a request to [ArchiveJobLauncher] and the worker does the work.
 */
@KoinViewModel
class ArchiveRestoreViewModel(
    private val sources: ArchiveSourceFactory,
    private val openArchive: OpenArchiveUseCase,
    private val probe: AppDataProbe,
    private val installedFacts: ReadInstalledAppFactsUseCase,
    private val vault: PassphraseVault,
    private val launcher: ArchiveJobLauncher,
    private val registry: JobRegistry,
    private val breadcrumbs: ArchiveBreadcrumbStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArchiveRestoreUiState())
    val uiState = _uiState.asStateFlow()

    private var installed: InstalledAppFacts? = null
    private var passphrase: CharArray? = null
    private var uriString: String? = null
    private var opened = false
    private var watching: Job? = null

    init {
        // Not gated on a file being picked: the Settings entry point arrives with no URI, and after a
        // crash that is exactly how the user gets here.
        viewModelScope.launch {
            breadcrumbs.read()?.let { crumb -> _uiState.update { it.copy(interrupted = crumb) } }
        }
    }

    /** Idempotent, for the same reason [AppBackupViewModel.start] is. */
    fun open(uriString: String) {
        if (opened) return
        opened = true
        this.uriString = uriString
        _uiState.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            val supported = probe.probeDataArchiveCapability()
            _uiState.update { it.copy(supported = supported) }

            val source = sources.open(uriString)
            if (source == null) {
                _uiState.update {
                    it.copy(loading = false, error = "Thor could not open that file")
                }
                return@launch
            }

            // Closed as soon as the header is out. Holding it open would hold a ParcelFileDescriptor
            // for as long as the screen lives, and the worker opens the container again anyway.
            val outcome = source.use { openArchive.readHeader(it) }
            val header = when (outcome) {
                is ArchiveHeaderOutcome.Read -> outcome.header
                is ArchiveHeaderOutcome.NotAnArchive -> {
                    _uiState.update { it.copy(loading = false, error = outcome.reason) }
                    return@launch
                }
            }

            installed = installedFacts(header.packageName)
            val obbCount = header.appBundle?.obbCount ?: 0
            _uiState.update { state ->
                state.copy(
                    loading = false,
                    fileName = source.displayName,
                    header = header,
                    // Everything the archive holds, selected. The user narrows from there.
                    selected = header.heldClasses().toSet(),
                    obbOffered = obbCount > 0,
                    restoreObb = obbCount > 0,
                )
            }
            evaluate()
            watchForExistingJob(header.packageName)
            tryRememberedPassphrase(header)
        }
    }

    fun toggleClass(dataClass: DataClass) {
        _uiState.update { state ->
            state.copy(
                selected = if (dataClass in state.selected) {
                    state.selected - dataClass
                } else {
                    state.selected + dataClass
                }
            )
        }
        evaluate()
    }

    fun setRestoreObb(restore: Boolean) = _uiState.update { it.copy(restoreObb = restore) }

    fun setConfirmed(confirmed: Boolean) = _uiState.update { it.copy(confirmed = confirmed) }

    fun useDifferentPassphrase() =
        _uiState.update { it.copy(passphraseNeeded = true, unlocked = false, passphraseError = null) }

    fun dismissResult() = _uiState.update { it.copy(finished = null, progress = null) }

    fun acknowledgeInterruption() {
        viewModelScope.launch {
            breadcrumbs.clear()
            _uiState.update { it.copy(interrupted = null) }
        }
    }

    fun submitPassphrase(typed: CharArray) {
        val header = _uiState.value.header ?: return
        viewModelScope.launch {
            when (val outcome = openArchive.unlock(header, typed)) {
                // The key is discarded: this call is a yes/no answer. `ThorJobLauncher` derives the
                // real one, so there is one enqueue path rather than two.
                is ArchiveUnlockOutcome.Unlocked -> {
                    passphrase = typed
                    _uiState.update {
                        it.copy(unlocked = true, passphraseNeeded = false, passphraseError = null)
                    }
                }

                is ArchiveUnlockOutcome.WrongPassphrase -> _uiState.update {
                    it.copy(
                        unlocked = false,
                        passphraseNeeded = true,
                        passphraseError = "that passphrase does not open this backup",
                    )
                }

                // A property of the archive, not of the passphrase, so it goes to `error` where the
                // screen shows it instead of blaming what the user typed.
                is ArchiveUnlockOutcome.Unsupported -> _uiState.update {
                    it.copy(unlocked = false, passphraseNeeded = false, error = outcome.reason)
                }
            }
        }
    }

    fun beginRestore() {
        val state = _uiState.value
        if (!state.canStart) return
        val header = state.header ?: return
        val uri = uriString ?: return
        val key = passphrase ?: return
        val salt = header.kdf.saltBytes() ?: run {
            _uiState.update {
                it.copy(finished = RestoreFinish.Failed("this archive's salt could not be read"))
            }
            return
        }

        _uiState.update { it.copy(running = true, finished = null) }
        viewModelScope.launch {
            val request = ArchiveRestoreRequest(
                uriString = uri,
                packageName = header.packageName,
                classes = state.selected,
                restoreObb = state.restoreObb,
            )
            val id = launcher.startRestore(request, key, salt)
            if (id == null) {
                _uiState.update { it.copy(running = false, finished = RestoreFinish.Failed(null)) }
            } else {
                watch(id)
            }
        }
    }

    /** §8.1 as the screen sees it. Called on open and after every selection change. */
    private fun evaluate() {
        val header = _uiState.value.header ?: return
        when (val decision = evaluateArchiveRestoreGate(header, installed, _uiState.value.selected)) {
            is ArchiveRestoreDecision.Allowed -> _uiState.update {
                it.copy(
                    refusal = null,
                    warnings = decision.warnings,
                    installFirst = decision.installFirst,
                )
            }

            is ArchiveRestoreDecision.Refused -> _uiState.update {
                // Warnings are cleared with the refusal: a refused restore has no warnings to heed,
                // and leaving them on screen reads as two problems where there is one.
                it.copy(refusal = decision.reason, warnings = emptyList(), installFirst = false)
            }
        }
    }

    private suspend fun tryRememberedPassphrase(header: ArchiveHeader) {
        val stored = vault.recall()
        if (stored == null) {
            _uiState.update { it.copy(passphraseNeeded = true) }
            return
        }
        // Only when the gate would allow it: deriving a key for an archive that will not be read is
        // work for nothing, and a prompt beside a refusal suggests the passphrase is the problem.
        if (_uiState.value.refusal != null) return

        when (openArchive.unlock(header, stored)) {
            is ArchiveUnlockOutcome.Unlocked -> {
                passphrase = stored
                _uiState.update { it.copy(unlocked = true, passphraseNeeded = false) }
            }
            // §5.4: the vault is a cache. This archive was made with a different passphrase, which is
            // an ordinary state and says nothing about the archive's health — so prompt, silently.
            is ArchiveUnlockOutcome.WrongPassphrase ->
                _uiState.update { it.copy(passphraseNeeded = true) }

            is ArchiveUnlockOutcome.Unsupported ->
                _uiState.update { it.copy(passphraseNeeded = true) }
        }
    }

    private fun watchForExistingJob(packageName: String) {
        viewModelScope.launch {
            launcher.runningJobFor(ThorJobKind.ARCHIVE_RESTORE, packageName).collect { id ->
                if (id != null && watching == null) watch(id)
            }
        }
    }

    private fun watch(jobId: UUID) {
        watching?.cancel()
        watching = viewModelScope.launch {
            _uiState.update { it.copy(running = true) }
            launch {
                registry.progressOf(jobId).collect { progress ->
                    if (progress != null) _uiState.update { it.copy(progress = progress) }
                }
            }
            launcher.status(jobId).collect { status ->
                when (status) {
                    is ThorJobStatus.Pending, is ThorJobStatus.Running ->
                        _uiState.update { it.copy(running = true) }

                    is ThorJobStatus.Succeeded -> finish(RestoreFinish.Succeeded)
                    is ThorJobStatus.Failed -> finish(RestoreFinish.Failed(status.reason))
                    is ThorJobStatus.Cancelled -> finish(RestoreFinish.Failed(null))
                    is ThorJobStatus.Gone -> _uiState.update { it.copy(running = false) }
                }
            }
        }
    }

    private fun finish(result: RestoreFinish) {
        _uiState.update { it.copy(running = false, finished = result) }
        watching?.cancel()
        watching = null
    }
}
```

Same two caveats as Task 16 Step 6: `finish` cancels the coroutine it runs in, so nothing may follow the `collect`; and `progressOf` emits null for an unknown id, hence the filter rather than an assignment.

- [ ] **Step 6: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.presentation.backup.*" --tests "com.valhalla.thor.domain.usecase.OpenArchiveUseCaseTest"
```

Expected: PASS. `OpenArchiveUseCaseTest` is in the run because Step 4 changed how `unlock` decodes the salt; its "a salt that is not base64 is refused" case is the one that proves the swap kept the behaviour.

- [ ] **Step 7: Write the screen**

Create `app/src/main/java/com/valhalla/thor/presentation/backup/ArchiveRestoreScreen.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.ArchiveRestoreRefusal
import com.valhalla.thor.domain.model.ArchiveRestoreWarning
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.presentation.utils.formatBytes
import java.text.DateFormat
import java.util.Date
import org.koin.androidx.compose.koinViewModel

@StringRes
private fun refusalLabel(refusal: ArchiveRestoreRefusal): Int = when (refusal) {
    ArchiveRestoreRefusal.SIGNER_MISMATCH -> R.string.restore_refused_signer_mismatch
    ArchiveRestoreRefusal.SIGNER_UNVERIFIABLE -> R.string.restore_refused_signer_unverifiable
    ArchiveRestoreRefusal.DATA_ONLY_AND_APP_ABSENT -> R.string.restore_refused_app_absent
    ArchiveRestoreRefusal.CLASS_NOT_IN_ARCHIVE -> R.string.restore_refused_class_missing
    ArchiveRestoreRefusal.NOTHING_SELECTED -> R.string.restore_refused_nothing_selected
    ArchiveRestoreRefusal.SCHEMA_TOO_NEW -> R.string.restore_refused_schema_too_new
    ArchiveRestoreRefusal.INVALID_PACKAGE_NAME -> R.string.restore_refused_invalid_package_name
    ArchiveRestoreRefusal.INVALID_USER_ID -> R.string.restore_refused_invalid_user_id
}

@StringRes
private fun warningLabel(warning: ArchiveRestoreWarning): Int = when (warning) {
    ArchiveRestoreWarning.INSTALLED_VERSION_OLDER -> R.string.restore_warning_version_older
    ArchiveRestoreWarning.CE_WITHOUT_DE -> R.string.restore_warning_ce_without_de
}

/**
 * §10's restore entry point.
 *
 * @param uriString null when the user arrived from Settings and has yet to pick a file. Not defaulted:
 *   a defaulted parameter here would let a call site silently forget the VIEW-delivered URI, and the
 *   only symptom would be a screen that always asks for a file.
 */
@Composable
fun ArchiveRestoreScreen(uriString: String?, onBack: () -> Unit) {
    val viewModel = koinViewModel<ArchiveRestoreViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var passphrase by remember { mutableStateOf("") }

    LaunchedEffect(uriString) { uriString?.let(viewModel::open) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // No takePersistableUriPermission: OpenDocument's grant lasts for this task, which is all the
        // worker needs, and asking for persistence Thor never uses would be a permission held for
        // nothing. See the known limitation in Step 10.
        if (uri != null) viewModel.open(uri.toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.restore_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        state.interrupted?.let { crumb ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    // §8.5, in the words the spec uses: the user is told the data may be incomplete
                    // rather than discovering it when the app crashes.
                    text = stringResource(R.string.restore_interrupted, crumb.appLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                TextButton(onClick = viewModel::acknowledgeInterruption) {
                    Text(stringResource(R.string.restore_interrupted_dismiss))
                }
            }
        }

        if (state.supported == false) {
            Text(
                text = stringResource(R.string.backup_unsupported),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        state.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (state.loading) CircularProgressIndicator()

        val header = state.header
        if (header == null) {
            Text(
                text = stringResource(R.string.restore_pick_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // */* rather than THORBAK_MIME: providers report a .thorbak as octet-stream, zip or
            // nothing at all depending on which one is answering, and a narrow filter greys out the
            // file the user is looking straight at.
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.restore_pick_file))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = header.packageName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(
                        R.string.restore_archive_version,
                        header.versionName ?: "?",
                        header.versionCode
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.restore_archive_created,
                        DateFormat.getDateTimeInstance().format(Date(header.createdAt))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.fileName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.installFirst) {
                Text(
                    // §8.1 is explicit that this is not a refusal, so it is stated as a plan rather
                    // than as a problem.
                    text = stringResource(R.string.restore_will_install_first),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            header.heldClasses().forEach { dataClass ->
                val member = header.member(dataClass)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = dataClass in state.selected,
                        onCheckedChange = { viewModel.toggleClass(dataClass) },
                        enabled = !state.running
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(restoreClassLabel(dataClass)),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        // No tri-state here, unlike the backup sheet: an archive records what it
                        // packed, so every member's size is known.
                        Text(
                            text = formatBytes(member?.plainBytes ?: 0L),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (state.obbOffered) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = state.restoreObb,
                        onCheckedChange = viewModel::setRestoreObb,
                        enabled = !state.running
                    )
                    // pluralStringResource, not stringResource — this is R.plurals, and the count is
                    // passed twice on purpose: once to pick the quantity, once to fill %1$d.
                    val obbCount = header.appBundle?.obbCount ?: 0
                    Text(
                        text = pluralStringResource(
                            R.plurals.restore_include_obb,
                            obbCount,
                            obbCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            state.refusal?.let {
                Text(
                    text = stringResource(refusalLabel(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            state.warnings.forEach {
                Text(
                    text = stringResource(warningLabel(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (state.refusal == null) {
                if (state.passphraseNeeded) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.backup_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = state.passphraseError != null,
                        supportingText = state.passphraseError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { viewModel.submitPassphrase(passphrase.toCharArray()) },
                        enabled = passphrase.isNotEmpty() && !state.running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.restore_unlock))
                    }
                } else if (state.unlocked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.restore_unlocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::useDifferentPassphrase) {
                            Text(stringResource(R.string.backup_use_different_passphrase))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = state.confirmed,
                        onCheckedChange = viewModel::setConfirmed,
                        enabled = !state.running
                    )
                    Text(
                        // "Replaces", not "restores". A merge is what a user assumes, and it is not
                        // what happens: whatever the app holds now for a selected class is deleted.
                        text = stringResource(R.string.restore_confirm_replace),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (state.running) {
                    val percent = state.progress?.percent
                    if (percent == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Button(
                    onClick = viewModel::beginRestore,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.restore_start))
                }
            }

            state.finished?.let { finish ->
                Text(
                    text = when (finish) {
                        // §8.6: the honest instruction is "open it and check", because no amount of
                        // shell exit codes proves the app is happy with what it was handed.
                        RestoreFinish.Succeeded -> stringResource(R.string.restore_done)
                        is RestoreFinish.Failed -> stringResource(
                            R.string.restore_failed,
                            finish.reason ?: stringResource(R.string.backup_failed_unknown)
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (finish is RestoreFinish.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back))
        }
    }
}

@StringRes
private fun restoreClassLabel(dataClass: DataClass): Int = when (dataClass) {
    DataClass.CE -> R.string.backup_class_ce
    DataClass.DE -> R.string.backup_class_de
    DataClass.EXTERNAL_DATA -> R.string.backup_class_external_data
    DataClass.EXTERNAL_MEDIA -> R.string.backup_class_external_media
}
```

`R.string.back` almost certainly exists — check (`rg -n '"back"' app/src/main/res/values/strings.xml`) and add it in Step 8 if not. Same for the `formatBytes` and `LinearProgressIndicator` shapes flagged in Task 16 Step 7; this screen follows whatever that task settled on.

- [ ] **Step 8: Add the strings**

Append to `app/src/main/res/values/strings_backup.xml`, after Task 16's block, inside the same `<resources>` element. **Not** `values/strings.xml`; see Global Constraints.

```xml
    <string name="restore_title">Restore a backup</string>
    <string name="restore_pick_prompt">Choose a Thor backup file (.thorbak) to see what it holds.</string>
    <string name="restore_pick_file">Choose a file</string>
    <!-- %2$d is a version code, an identifier, not a count. -->
    <string name="restore_archive_version" tools:ignore="PluralsCandidate">Version %1$s (%2$d)</string>
    <string name="restore_archive_created">Backed up %1$s</string>
    <string name="restore_will_install_first">This app is not installed. Thor will install it from the backup first.</string>
    <plurals name="restore_include_obb">
        <item quantity="one">Also restore game data (%1$d file)</item>
        <item quantity="other">Also restore game data (%1$d files)</item>
    </plurals>
    <string name="restore_unlock">Unlock</string>
    <string name="restore_unlocked">Unlocked with the saved passphrase.</string>
    <string name="restore_confirm_replace">I understand this replaces the app\'s current data. Anything not in this backup is deleted.</string>
    <string name="restore_start">Restore</string>
    <string name="restore_done">Restore finished. Open the app to check it works.</string>
    <string name="restore_failed">Restore failed: %1$s</string>
    <string name="restore_interrupted">The restore of %1$s did not finish. That app\'s data may be incomplete — restoring it again is the fix.</string>
    <string name="restore_interrupted_dismiss">Got it</string>
    <string name="restore_refused_signer_mismatch">This backup was made from an app signed by a different developer. Restoring it would hand one app another\'s data, so Thor will not do it.</string>
    <string name="restore_refused_signer_unverifiable">Thor could not read the installed app\'s signature, so it cannot check that this backup belongs to it.</string>
    <string name="restore_refused_app_absent">This app is not installed and this backup holds no installer to add it from.</string>
    <string name="restore_refused_class_missing">This backup does not hold one of the things selected.</string>
    <string name="restore_refused_nothing_selected">Nothing is selected.</string>
    <string name="restore_refused_schema_too_new">This backup was made by a newer version of Thor. Update Thor to restore it.</string>
    <string name="restore_refused_invalid_package_name">This backup names an app in a way Thor will not accept. The file is damaged, or it was not written by Thor.</string>
    <string name="restore_refused_invalid_user_id">This backup names a user profile Thor will not accept. The file is damaged, or it was not written by Thor.</string>
    <string name="restore_warning_version_older">The installed app is older than this backup. Apps can crash on data from a newer version — update the app first if it does.</string>
    <string name="restore_warning_ce_without_de">Startup data is in this backup but not selected. Some apps need both, and may behave as though half-migrated.</string>
    <string name="restore_settings_title">Restore a backup</string>
    <string name="restore_settings_desc">Read a .thorbak file and put an app\'s data back.</string>
```

Two lint checks are in play here, and both are fatal because `warningsAsErrors = true`:

- **`PluralsCandidate`** fires on a translatable string carrying a `%d`. `app/src/main/res/values/strings.xml` already carries `<string name="export_bulk_progress" tools:ignore="PluralsCandidate">` for exactly this reason, so treat it as certain rather than possible. `restore_include_obb` is a genuine count, so it becomes a real `<plurals>` — the base file already has 15 of them, so this is an established idiom here, not a new one. `restore_archive_version`'s `%2$d` is a version code and is suppressed with the reason written next to it.
- **`MissingTranslation`**, handled by the file this block lives in.

`tools:` is already bound on `strings_backup.xml`'s root element (Task 8 wrote the `xmlns:tools` declaration), so no second declaration is needed here.

- [ ] **Step 9: Add the route, the Settings entry, and the interruption banner**

In `app/src/main/java/com/valhalla/thor/presentation/navigation/ThorRoute.kt`:

```kotlin
    /** @param uriString null when the user came from Settings and still has to pick a file. */
    @Serializable
    data class ArchiveRestore(val uriString: String? = null) : ThorRoute
```

In `app/src/main/java/com/valhalla/thor/presentation/main/MainScreen.kt`, add an entry beside `ThorRoute.ExtensionBrowse`'s:

```kotlin
                entry<ThorRoute.ArchiveRestore>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) { route ->
                    ArchiveRestoreScreen(
                        uriString = route.uriString,
                        onBack = {
                            if (currentBackStack.size > 1) {
                                currentBackStack.removeLastOrNull()
                            }
                        }
                    )
                }
```

and pass the Settings callback:

```kotlin
                entry<ThorRoute.Settings> {
                    SettingsScreen(
                        onNavigateToExtensionManager = {
                            settingsBackStack.add(ThorRoute.ExtensionManager)
                        },
                        onNavigateToRestore = {
                            settingsBackStack.add(ThorRoute.ArchiveRestore())
                        }
                    )
                }
```

In `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt`, add `onNavigateToRestore: () -> Unit` to the signature — **not defaulted**, so the one call site has to supply it — and a section before the EXTENSIONS one, following that section's chrome exactly:

```kotlin
        // ── BACKUP & RESTORE ────────────────────────────────────────────────
        // Root/Shizuku/Dhizuku only: there is no unprivileged path to another app's data, so an
        // unprivileged user offered this would reach a screen whose only content is a refusal.
        if (hasPrivilege) {
            Spacer(Modifier.height(32.dp))

            SettingsSectionLabel(stringResource(R.string.action_backup))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(8.dp)
            ) {
                // §8.5's notice, where a user who is not looking for it will still see it. Read
                // through produceState rather than through SettingsViewModel: it is one small file
                // read that no other part of Settings needs, and threading it through the view model
                // would put a backup concern in the screen that owns every other preference.
                val breadcrumbs = koinInject<ArchiveBreadcrumbStore>()
                val interrupted by produceState<ArchiveBreadcrumb?>(initialValue = null) {
                    value = breadcrumbs.read()
                }
                interrupted?.let { crumb ->
                    Text(
                        text = stringResource(R.string.restore_interrupted, crumb.appLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
                SettingsClickRow(
                    icon = R.drawable.settings_backup_restore,
                    title = stringResource(R.string.restore_settings_title),
                    subtitle = stringResource(R.string.restore_settings_desc),
                    onClick = onNavigateToRestore
                )
            }
        }
```

`produceState`'s block runs in a coroutine on the composition's context, and `ArchiveBreadcrumbStore.read()` is a `suspend` function whose implementation moves to IO itself (`FileArchiveBreadcrumbStore`, Task 14) — so this does not read a file on the main thread. The banner is not dismissible here on purpose: the row beneath it leads to the screen that can clear it, and a dismiss in two places is two chances to lose the notice.

- [ ] **Step 10: Add the VIEW filter and the HomeActivity path**

In `app/src/main/AndroidManifest.xml`, add a second `<intent-filter>` to `.HomeActivity`:

```xml
            <!-- Opening a .thorbak. The pathPattern is load-bearing, not decoration:
                 PortableInstallerActivity already claims content:+application/octet-stream and
                 application/zip (below), which is what providers report for a .thorbak, so a filter
                 without a path constraint would put two Thor entries in the chooser for every zip
                 and every unknown binary on the device.

                 Known limitation: Intent matching tests Uri.getPath(), so this fires for providers
                 whose document URI ends in the file name (com.android.externalstorage.documents) and
                 not for those whose URI is an opaque id (the Downloads provider). Restore from
                 Settings is unaffected. Registering the extension properly is a follow-up. -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />

                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />

                <data android:scheme="content" />
                <data android:scheme="file" />
                <data android:mimeType="application/octet-stream" />
                <data android:mimeType="application/zip" />
                <data android:pathPattern=".*\\.thorbak" />
            </intent-filter>
```

In `app/src/main/java/com/valhalla/thor/HomeActivity.kt`, read the URI once in `onCreate` and hand it to `MainScreen`:

```kotlin
    /**
     * The `.thorbak` this activity was opened on, or null for an ordinary launch.
     *
     * Read from `intent` in `onCreate` rather than in `onNewIntent`: `HomeActivity` has no
     * `launchMode`, so it is `standard` — a VIEW intent creates a new instance rather than delivering
     * to the existing one, and that new instance's `intent` is the one carrying the URI.
     *
     * The grant that comes with a VIEW intent lives as long as this **task**, not as long as the
     * process, and it is not persistable. A restore whose task the user swipes away mid-job fails
     * with "Thor could not open that backup file" — recorded as a known limitation, and the reason
     * §8.5's breadcrumb covers the data side.
     */
    private val pendingRestoreUri: String? by lazy {
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toString()
    }
```

and at the `MainScreen` call (line 221):

```kotlin
                            MainScreen(
                                startDestination = tab,
                                pendingRestoreUri = pendingRestoreUri,
```

In `MainScreen`, take it as a **required** parameter — `pendingRestoreUri: String?` with no default. A default here would compile at the one call site that must pass it, and the only symptom would be that opening a `.thorbak` lands on Home. Then, beside the existing `rememberSaveable` state:

```kotlin
    // Consumed once. rememberSaveable, not remember: after a rotation the route is already on the
    // back stack, and re-adding it would stack a second copy of the restore screen.
    var restoreUriConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pendingRestoreUri) {
        val uri = pendingRestoreUri
        if (uri != null && !restoreUriConsumed) {
            restoreUriConsumed = true
            activeDestination = AppDestinations.SETTINGS
            settingsBackStack.add(ThorRoute.ArchiveRestore(uri))
        }
    }
```

Settings is the host tab because that is where the Settings entry point pushes the same route; one tab owns restore.

- [ ] **Step 11: Build, and check the merged manifest**

```
./gradlew :app:assembleFossDebug
```

then, against the APK rather than the source:

```
$ANDROID_HOME/build-tools/*/aapt2 dump xmltree --file AndroidManifest.xml \
  app/build/outputs/apk/foss/debug/app-foss-debug.apk | grep -B4 -A4 "thorbak"
```

Expected: one `pathPattern` of `.*\.thorbak` (single backslash in the compiled tree — the doubled one in XML is the escape), inside a filter on `HomeActivity`, alongside `application/octet-stream` and `application/zip`. If `pathPattern` is absent, the `\\.` was collapsed by an editor; retype it.

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/usecase/ReadInstalledAppFactsUseCase.kt \
  app/src/main/java/com/valhalla/thor/presentation/backup/ArchiveRestoreViewModel.kt \
  app/src/main/java/com/valhalla/thor/presentation/backup/ArchiveRestoreScreen.kt \
  app/src/main/java/com/valhalla/thor/domain/model/AppDataArchive.kt \
  app/src/main/java/com/valhalla/thor/domain/usecase/OpenArchiveUseCase.kt \
  app/src/main/java/com/valhalla/thor/data/backup/job/AppArchiveWorker.kt \
  app/src/main/java/com/valhalla/thor/presentation/navigation/ThorRoute.kt \
  app/src/main/java/com/valhalla/thor/presentation/main/MainScreen.kt \
  app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt \
  app/src/main/java/com/valhalla/thor/HomeActivity.kt \
  app/src/main/AndroidManifest.xml \
  app/src/main/res/values/strings_backup.xml \
  app/src/test/java/com/valhalla/thor/presentation/backup/ArchiveRestoreViewModelTest.kt
git commit -m "feat(backup): restore screen showing every gate outcome before anything destructive"
```

---

### Task 18: Passphrase management in Settings, the lint gate, and the record

**Spec:** §5.4 (the warning, and what changing a passphrase does *not* do), §13 (release checklist), §14 (limitations), §16 (follow-ups).

This is the last task, and it carries three unrelated-looking things for one reason: each is a thing a reviewer would reject the feature for missing, and none is large enough to gate on its own. The passphrase screen is the only place a user can undo "remember it on this device". The lint run is the gate this feature is most likely to fail, because `warningsAsErrors = true` turns two string-resource checks and one storage-API hint into red builds. The docs are the record — without them row 23 stays open and §13's Play Console step is discovered by a rejected release.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/settings/PassphraseSettingsViewModel.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/settings/PassphraseSettingsSheet.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/PassphraseVault.kt` (`remember` returns `Boolean`)
- Modify: `app/src/test/java/com/valhalla/thor/data/backup/PassphraseVaultTest.kt` (the existing wrap-failure test now asserts the return value too)
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt` (a passphrase row inside the *Backup & restore* section Task 17 added)
- Modify: `app/src/main/res/values/strings_backup.xml`
- Modify: `docs/follow-ups/README.md`
- Modify: `docs/follow-ups/app-data-backup-and-xapk-export.md`
- Test: `app/src/test/java/com/valhalla/thor/presentation/settings/PassphraseSettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `PassphraseVault` (`remember`, `recall`, `forget`, `isRemembered`) and `MIN_PASSPHRASE_LENGTH` (Task 5); the *Backup & restore* section and `SettingsClickRow` (Task 17); `strings_backup.xml` (Task 8).
- Produces: `PassphraseError` (enum: `TOO_SHORT`, `MISMATCH`, `STORE_FAILED`); `PassphraseSettingsUiState` (`remembered`, `saved`, `error`, `busy`); `PassphraseSettingsViewModel` (`@KoinViewModel`) with `save(passphrase: CharArray, confirmation: CharArray)`, `forget()`, `dismiss()`, `uiState: StateFlow<PassphraseSettingsUiState>`; `PassphraseSettingsSheet(onDismiss: () -> Unit)`. `PassphraseVault.remember` changes from `Unit` to `Boolean`.

- [ ] **Step 1: Write the failing view-model test**

Create `app/src/test/java/com/valhalla/thor/presentation/settings/PassphraseSettingsViewModelTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import com.valhalla.thor.data.backup.MIN_PASSPHRASE_LENGTH
import com.valhalla.thor.data.backup.PassphraseVault
import com.valhalla.thor.data.backup.PassphraseVaultStore
import com.valhalla.thor.data.backup.VaultKeyProvider
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PassphraseSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --- doubles -------------------------------------------------------------------------------

    private class FakeVaultStore(initial: String? = null) : PassphraseVaultStore {
        private val state = MutableStateFlow(initial)

        /** What is actually stored, so a test can assert that nothing was written. */
        val blob: String? get() = state.value

        override val isSet: Flow<Boolean> = state.map { it != null }
        override suspend fun read(): String? = state.value
        override suspend fun write(value: String?) {
            state.value = value
        }
    }

    /** Reversible and trivial: the vault's own wrapping is Task 5's subject, not this one's. */
    private class PlainKeyProvider : VaultKeyProvider {
        override fun wrap(plaintext: ByteArray): ByteArray = plaintext
        override fun unwrap(blob: ByteArray): ByteArray = blob
    }

    /** A Keystore that is not there: never created, or invalidated by an enrolment change. */
    private class DeadKeyProvider : VaultKeyProvider {
        override fun wrap(plaintext: ByteArray): ByteArray =
            throw GeneralSecurityException("no key")

        override fun unwrap(blob: ByteArray): ByteArray =
            throw GeneralSecurityException("no key")
    }

    private fun pass(value: String) = value.toCharArray()

    // --- what is stored ------------------------------------------------------------------------

    @Test
    fun `an empty vault reports nothing remembered`() = runTest(dispatcher) {
        val vm = PassphraseSettingsViewModel(PassphraseVault(FakeVaultStore(), PlainKeyProvider()))

        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.remembered)
        assertEquals(false, vm.uiState.value.saved)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a saved passphrase is remembered and comes back out`() = runTest(dispatcher) {
        val store = FakeVaultStore()
        val vm = PassphraseSettingsViewModel(PassphraseVault(store, PlainKeyProvider()))

        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()

        assertEquals(true, vm.uiState.value.saved)
        assertEquals(true, vm.uiState.value.remembered)
        assertNull(vm.uiState.value.error)
        // Through a second vault over the same store, because "the flag flipped" is not the claim —
        // "the passphrase can be recalled" is.
        assertEquals(
            "correct horse",
            PassphraseVault(store, PlainKeyProvider()).recall()?.concatToString()
        )
    }

    @Test
    fun `a passphrase of exactly the minimum length is accepted`() = runTest(dispatcher) {
        // The boundary, because `>=` and `>` are one character apart and both look right.
        val typed = "a".repeat(MIN_PASSPHRASE_LENGTH)
        val vm = PassphraseSettingsViewModel(PassphraseVault(FakeVaultStore(), PlainKeyProvider()))

        vm.save(pass(typed), pass(typed))
        advanceUntilIdle()

        assertEquals(true, vm.uiState.value.saved)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `saving a second passphrase replaces the first`() = runTest(dispatcher) {
        // §5.4: this does NOT re-encrypt anything. Every .thorbak already written still opens only
        // with the passphrase it was made with, which is why the sheet says so in words.
        val store = FakeVaultStore()
        val vm = PassphraseSettingsViewModel(PassphraseVault(store, PlainKeyProvider()))

        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()
        vm.save(pass("battery staple"), pass("battery staple"))
        advanceUntilIdle()

        assertEquals(
            "battery staple",
            PassphraseVault(store, PlainKeyProvider()).recall()?.concatToString()
        )
    }

    @Test
    fun `forgetting clears the vault and the flag`() = runTest(dispatcher) {
        val store = FakeVaultStore()
        val vm = PassphraseSettingsViewModel(PassphraseVault(store, PlainKeyProvider()))
        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()

        vm.forget()
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.remembered)
        assertNull(store.blob)
    }

    // --- refusals ------------------------------------------------------------------------------

    @Test
    fun `a passphrase shorter than the minimum is refused without touching the vault`() =
        runTest(dispatcher) {
            val store = FakeVaultStore()
            val vm = PassphraseSettingsViewModel(PassphraseVault(store, PlainKeyProvider()))

            vm.save(pass("short"), pass("short"))
            advanceUntilIdle()

            assertEquals(PassphraseError.TOO_SHORT, vm.uiState.value.error)
            assertEquals(false, vm.uiState.value.saved)
            assertNull(store.blob)
        }

    @Test
    fun `a confirmation that does not match is refused without touching the vault`() =
        runTest(dispatcher) {
            val store = FakeVaultStore()
            val vm = PassphraseSettingsViewModel(PassphraseVault(store, PlainKeyProvider()))

            vm.save(pass("correct horse"), pass("correct horss"))
            advanceUntilIdle()

            assertEquals(PassphraseError.MISMATCH, vm.uiState.value.error)
            assertEquals(false, vm.uiState.value.saved)
            assertNull(store.blob)
        }

    @Test
    fun `the length rule is reported ahead of the match rule`() = runTest(dispatcher) {
        // Both fields are wrong. "They do not match" would send the user to fix a typo and then refuse
        // them again for the length; the length is the rule they have to satisfy either way.
        val vm = PassphraseSettingsViewModel(PassphraseVault(FakeVaultStore(), PlainKeyProvider()))

        vm.save(pass("abc"), pass("xyz"))
        advanceUntilIdle()

        assertEquals(PassphraseError.TOO_SHORT, vm.uiState.value.error)
    }

    @Test
    fun `a vault that cannot store the passphrase reports a failure rather than success`() =
        runTest(dispatcher) {
            // The whole reason PassphraseVault.remember returns Boolean. A screen that says "saved"
            // when the Keystore refused sends the user away believing they need not write it down.
            val store = FakeVaultStore()
            val vm = PassphraseSettingsViewModel(PassphraseVault(store, DeadKeyProvider()))

            vm.save(pass("correct horse"), pass("correct horse"))
            advanceUntilIdle()

            assertEquals(PassphraseError.STORE_FAILED, vm.uiState.value.error)
            assertEquals(false, vm.uiState.value.saved)
            assertNull(store.blob)
        }

    // --- the outcome is per visit --------------------------------------------------------------

    @Test
    fun `a successful save clears an earlier refusal`() = runTest(dispatcher) {
        val vm = PassphraseSettingsViewModel(PassphraseVault(FakeVaultStore(), PlainKeyProvider()))
        vm.save(pass("short"), pass("short"))
        advanceUntilIdle()

        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()

        assertNull(vm.uiState.value.error)
        assertEquals(true, vm.uiState.value.saved)
    }

    @Test
    fun `dismissing clears the outcome but not what is stored`() = runTest(dispatcher) {
        val vm = PassphraseSettingsViewModel(PassphraseVault(FakeVaultStore(), PlainKeyProvider()))
        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()

        vm.dismiss()

        assertEquals(false, vm.uiState.value.saved)
        assertNull(vm.uiState.value.error)
        // `remembered` is a fact about the device, not an outcome of this visit, so it survives.
        assertEquals(true, vm.uiState.value.remembered)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.presentation.settings.PassphraseSettingsViewModelTest"
```

Expected: compilation failure — `PassphraseSettingsViewModel`, `PassphraseSettingsUiState` and `PassphraseError` unresolved. The `a vault that cannot store the passphrase` test additionally cannot compile against `remember`'s current `Unit` return until Step 3.

- [ ] **Step 3: Make `remember` tell the caller whether it worked**

In `app/src/main/java/com/valhalla/thor/data/backup/PassphraseVault.kt`, change `remember`'s signature and its two exits:

```kotlin
    /**
     * Wrap [passphrase] and store it.
     *
     * @return true when the passphrase is now in the vault. **False means nothing was written** — the
     * Keystore key is gone or was never creatable — and a caller must not tell the user it was saved.
     * Task 18's settings screen is the reason this is not `Unit`: "Saved" on a screen where nothing
     * was saved is how a user stops writing their passphrase down.
     */
    suspend fun remember(passphrase: CharArray): Boolean {
        val wrapped = try {
            keyProvider.wrap(passphrase.concatToString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            // Nothing is written: a half-written vault would claim a passphrase is stored and then
            // fail to produce it on every use.
            Logger.e("PassphraseVault", "could not wrap the passphrase", e)
            return false
        }
        store.write(Base64.encodeToString(wrapped, Base64.NO_WRAP))
        return true
    }
```

Task 16's one call site — `if (remember && typed.isNotEmpty()) vault.remember(typed)` in `AppBackupViewModel.beginBackup` — keeps compiling unchanged: an `if` used as a statement discards the value. Leave it that way rather than surfacing a second failure path in the backup sheet; the backup itself is not affected by the vault refusing, and the sheet's own "remember it" checkbox is a convenience, not part of the archive.

Then tighten the existing test in `app/src/test/java/com/valhalla/thor/data/backup/PassphraseVaultTest.kt` so the new return value is covered where the behaviour lives:

```kotlin
    @Test
    fun `a failure to wrap does not leave a half-written vault`() = runTest {
        val store = FakeStore()
        val vault = PassphraseVault(store, FakeProvider(alive = false))

        val stored = vault.remember("correct horse".toCharArray())

        assertEquals(false, stored)
        assertNull(store.blob)
    }
```

`assertEquals` is already imported in that file.

- [ ] **Step 4: Write the view model**

Create `app/src/main/java/com/valhalla/thor/presentation/settings/PassphraseSettingsViewModel.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.backup.MIN_PASSPHRASE_LENGTH
import com.valhalla.thor.data.backup.PassphraseVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

/**
 * Why a passphrase was not accepted, as an enum rather than a string.
 *
 * No `@StringRes` here: an `R` reference would put an Android type in the class the JVM tests
 * construct, and the wording belongs to the screen that draws it.
 */
enum class PassphraseError { TOO_SHORT, MISMATCH, STORE_FAILED }

data class PassphraseSettingsUiState(
    /**
     * Whether a passphrase is stored on this device. A fact about the device, not an outcome of this
     * visit — [PassphraseSettingsViewModel.dismiss] leaves it alone.
     */
    val remembered: Boolean = false,
    /** The outcome of *this* visit. Cleared by [PassphraseSettingsViewModel.dismiss]. */
    val saved: Boolean = false,
    val error: PassphraseError? = null,
    val busy: Boolean = false,
)

/**
 * §5.4's surface: choose a passphrase to remember, replace it, or forget it.
 *
 * The vault is the only dependency, and the only source of truth for "is a passphrase stored" —
 * [remembered] is collected from it rather than set beside it, because two writers to that flag is
 * exactly how a UI ends up offering to recall a passphrase that is not there.
 */
@KoinViewModel
class PassphraseSettingsViewModel(private val vault: PassphraseVault) : ViewModel() {

    private val _uiState = MutableStateFlow(PassphraseSettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            vault.isRemembered.collect { remembered ->
                _uiState.update { it.copy(remembered = remembered) }
            }
        }
    }

    fun save(passphrase: CharArray, confirmation: CharArray) {
        // Length before match: see the test that names this. Both checks run before the vault is
        // touched, so a refused attempt cannot replace a passphrase that was already stored.
        val refusal = when {
            passphrase.size < MIN_PASSPHRASE_LENGTH -> PassphraseError.TOO_SHORT
            !passphrase.contentEquals(confirmation) -> PassphraseError.MISMATCH
            else -> null
        }
        if (refusal != null) {
            _uiState.update { it.copy(error = refusal, saved = false) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null, saved = false) }
            val stored = vault.remember(passphrase)
            _uiState.update {
                it.copy(
                    busy = false,
                    saved = stored,
                    error = if (stored) null else PassphraseError.STORE_FAILED,
                )
            }
        }
    }

    fun forget() {
        viewModelScope.launch {
            vault.forget()
            // `remembered` is deliberately not written here — the collector in `init` owns it.
            _uiState.update { it.copy(saved = false, error = null) }
        }
    }

    fun dismiss() = _uiState.update { it.copy(saved = false, error = null) }
}
```

**On zeroing the `CharArray`s:** this class does not, and adding it here would be theatre rather than a fix. The sheet holds each field as a Compose `String` state, so the array handed to `save` is never the only copy in memory — an `Arrays.fill` on it would leave the `String`s (and every `String` the recomposition interned) untouched. `CharArray` is used across these signatures because `PassphraseVault` and `AppArchiveCipher` need one anyway, not as a secrets-hygiene claim. Do not add a `fill` and a comment claiming otherwise.

- [ ] **Step 5: Run the tests to verify they pass**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.presentation.settings.PassphraseSettingsViewModelTest" --tests "com.valhalla.thor.data.backup.PassphraseVaultTest"
```

Expected: PASS, 11 + 8 tests. Read the count out of `app/build/test-results/testFossDebugUnitTest/*.xml`, not the Gradle log line.

- [ ] **Step 6: Write the sheet**

Create `app/src/main/java/com/valhalla/thor/presentation/settings/PassphraseSettingsSheet.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.data.backup.MIN_PASSPHRASE_LENGTH
import org.koin.androidx.compose.koinViewModel

/** §5.4. The one place a stored passphrase can be replaced or removed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassphraseSettingsSheet(onDismiss: () -> Unit) {
    val viewModel = koinViewModel<PassphraseSettingsViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = {
            // Clear the outcome as the sheet goes, or reopening it shows the last visit's "Saved".
            viewModel.dismiss()
            onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.passphrase_settings_title).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            // §5.4's two consequences, stated where the choice is made rather than after it. Both are
            // properties of the format, so neither can be softened by a later UI change.
            Text(
                text = stringResource(R.string.passphrase_settings_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = stringResource(R.string.passphrase_settings_no_reencrypt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.remembered) {
                Text(
                    text = stringResource(R.string.passphrase_settings_stored),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(stringResource(R.string.backup_passphrase)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = confirmation,
                onValueChange = { confirmation = it },
                label = { Text(stringResource(R.string.backup_passphrase_confirm)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            )

            state.error?.let { error ->
                Text(
                    text = stringResource(passphraseErrorLabel(error), MIN_PASSPHRASE_LENGTH),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (state.saved) {
                Text(
                    text = stringResource(R.string.passphrase_settings_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.save(passphrase.toCharArray(), confirmation.toCharArray())
                    },
                    enabled = !state.busy && passphrase.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.passphrase_settings_save))
                }
                if (state.remembered) {
                    TextButton(
                        onClick = {
                            viewModel.forget()
                            passphrase = ""
                            confirmation = ""
                        },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.passphrase_settings_forget))
                    }
                }
            }
        }
    }
}

/**
 * The enum-to-copy mapping, kept here rather than in the view model so the view model stays free of
 * `R` (and therefore JVM-testable).
 *
 * Every arm takes the same one format argument — the minimum length — so the call site can pass it
 * unconditionally; the two strings that do not use `%1$d` simply ignore it.
 */
private fun passphraseErrorLabel(error: PassphraseError): Int = when (error) {
    PassphraseError.TOO_SHORT -> R.string.passphrase_error_too_short
    PassphraseError.MISMATCH -> R.string.passphrase_error_mismatch
    PassphraseError.STORE_FAILED -> R.string.passphrase_error_store_failed
}
```

One thing to resolve against real code, which does not change the design: **`stringResource(id, arg)` with an unused argument** is legal at runtime and does not warn at compile time. If lint's `StringFormatMatches` objects to the two arms that carry no `%1$d`, replace the single call with a `when (error)` that supplies the argument only to `passphrase_error_too_short` — do **not** drop the argument from the arm that needs it, which would print a literal `%1$d` to the user.

- [ ] **Step 7: Add the row to Settings**

In `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt`, inside the *Backup & restore* `Column` Task 17 added, beneath the `SettingsClickRow` that navigates to restore:

```kotlin
                var showPassphrase by remember { mutableStateOf(false) }

                SettingsClickRow(
                    icon = R.drawable.round_key,
                    title = stringResource(R.string.passphrase_settings_title),
                    subtitle = stringResource(R.string.passphrase_settings_desc),
                    onClick = { showPassphrase = true }
                )

                if (showPassphrase) {
                    PassphraseSettingsSheet(onDismiss = { showPassphrase = false })
                }
```

`R.drawable.round_key` is real — `app/src/main/res/drawable/round_key.xml` — and is the only key/lock asset in the tree, so no new vector is needed. `SettingsClickRow` is `private` in `SettingsScreen.kt` with exactly the parameters used here (`icon: Int, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit`, at `SettingsScreen.kt:1000`), and `hasPrivilege` is already in scope at `SettingsScreen.kt:99`.

The sheet is hosted here rather than at the screen root because `SettingsScreen` already hosts its other sheets at their row, and a `ModalBottomSheet` composed inside a `Column` still draws over the whole window.

- [ ] **Step 8: Add the strings**

Append to `app/src/main/res/values/strings_backup.xml`, inside the same `<resources>` element:

```xml
    <string name="passphrase_settings_title">Backup passphrase</string>
    <string name="passphrase_settings_desc">Choose, change or remove the passphrase Thor remembers.</string>
    <string name="passphrase_settings_stored">A passphrase is saved on this device.</string>
    <string name="passphrase_settings_warning">Thor cannot recover this passphrase. Without it, a backup made with it cannot be restored.</string>
    <string name="passphrase_settings_no_reencrypt">Changing it does not change any backup you already made. Each one still opens only with the passphrase it was made with.</string>
    <string name="passphrase_settings_save">Save</string>
    <string name="passphrase_settings_forget">Forget it</string>
    <string name="passphrase_settings_saved">Saved on this device.</string>
    <!-- %1$d is a minimum, not a count of anything, so it is not a plural. -->
    <string name="passphrase_error_too_short" tools:ignore="PluralsCandidate">Use at least %1$d characters.</string>
    <string name="passphrase_error_mismatch">The two passphrases do not match.</string>
    <string name="passphrase_error_store_failed">Thor could not save the passphrase on this device. The backup will still work — you will just have to type it each time.</string>
```

- [ ] **Step 9: Build both flavours**

```
./gradlew :app:assembleFossDebug :app:assembleStoreDebug
```

Expected: BUILD SUCCESSFUL. Both, not one: the `store` flavour is on **no** test classpath, so a `store`-only breakage is invisible to every test in this plan. Nothing in this feature is flavour-conditional, which is exactly why an accidental divergence would go unnoticed.

- [ ] **Step 10: Run the whole suite**

```
rm -rf app/build/test-results/testFossDebugUnitTest
./gradlew :app:testFossDebugUnitTest --rerun-tasks
```

Expected: PASS. Count the `<testcase` elements in `app/build/test-results/testFossDebugUnitTest/*.xml`; the pre-feature baseline is 842 (band B), and this plan adds roughly 300. The number is not the gate — **zero failures and zero skips** is. A test that reports as skipped is a test that did not run, which the `--rerun-tasks` flag exists to prevent.

- [ ] **Step 11: Run lint, and expect it to have opinions**

```
./gradlew :app:lintFossDebug
```

Expected: BUILD SUCCESSFUL. This is the step most likely to fail, because `app/build.gradle.kts` sets `abortOnError = true`, `warningsAsErrors = true` and `checkTestSources = true`, and `app/lint.xml` downgrades only five checks (`VectorPath`, `GradleDependency`, `NewerVersionAvailable`, `AndroidGradlePluginVersion`, and `SdCardPath` outside `src/test/**`). Four checks to expect, in the order they are most likely to bite:

1. **`MissingTranslation`** — should be silent, because every string this feature added lives in `strings_backup.xml` under a file-level `tools:ignore`. If it fires, a string was added to `values/strings.xml` by mistake; move it rather than adding a second suppression.
2. **`PluralsCandidate`** — should be silent for the same reason. If it fires on a string not already suppressed, decide honestly whether the number is a count: a count becomes `<plurals>`, anything else gets `tools:ignore` **with the reason written beside it**.
3. **`UsableSpace`** — expected on `ArchiveBackupWorker.usableStagingBytes()`, already carrying `@Suppress("UsableSpace")`. **Do not follow lint's advice here.** Its recommendation — `getAllocatableBytes` — is the API that produced the #373 cache-clear bug, where the platform's answer made a check pass that should have failed. If the suppression is missing, add it with that reason.
4. **`ForegroundServiceType` / `ForegroundServicePermission`** — these concern the `dataSync` overlay on `SystemForegroundService` and the two permissions Task 1 added. If either fires, the manifest and the `ForegroundInfo` disagree about the type, and the fix is in whichever of them is wrong — never a suppression. A suppressed foreground-service check is a crash on Android 14, not a quiet warning.

Anything else lint reports is a real finding in code this plan wrote. Fix it; do not widen `lint.xml`.

- [ ] **Step 12: Record it**

Two documents, and **no `release-notes/` file** — the notes for this feature ship in the `chore(release)` commit that bumps `versionCode`, which this work does not touch.

First, `docs/follow-ups/README.md`. Replace row 23's cells (currently *"Highest-impact item left (4/5), and `README.md` has promised it for a year. Root-only, hard-gated"*) with the shipped record, keeping the row in place — the retention rule keeps a shipped row when its remaining value is stopping the work being redone, and everything about this row's device checks and its Play Console step is exactly that:

```markdown
| 23 | [#51 phase 2 — app **data** backup](app-data-backup-and-xapk-export.md) | feature | 5–8 d | ✅ **shipped desk-verified, zero device checks run.** `.thorbak` = a zip holding `thorbak.json` + `app.xapk` + one AES-256-GCM member per storage class, on a reusable WorkManager foreground-job seam. ⚠️ **Two things block a `store` release**: the Play Console **Foreground Service declaration** for `FOREGROUND_SERVICE_DATA_SYNC` (§13 — demo bulk APK export, *not* backup, since Play cannot root a device), and the 14 device checks in the linked doc |
```

Then the preamble at line 175. Change **"Bands A and B are built — start at #23."** to **"Bands A and B are built, and #23 is built but unverified — start at #24."**, and add to that sentence: rows 24 and 25 are the same Room migration described twice, so they are sequenced together or neither. That last point is already in row 25's cell; putting it in the preamble is what stops the next session picking 24 alone.

Second, `docs/follow-ups/app-data-backup-and-xapk-export.md`. Its `## Phase 2 — Root-only data backup (5–8 days)` section (line 100) is now a description of shipped work, so rewrite that heading as `## Phase 2 — shipped 2026-08-10 (desk-verified only)` and, under it, record exactly three things and nothing else:

- **§13's release checklist**, verbatim as obligations rather than prose: file the Play Console Foreground Service declaration **before** the first `store` upload that carries `FOREGROUND_SERVICE_DATA_SYNC`; declare the type as `dataSync`; record the demonstration video on **bulk APK export on an unrooted device**, because that is the path a Play reviewer can actually run — a data backup needs root and Play will not review on a rooted device; and make the release notes state that `.thorbak` is encrypted and that the passphrase is not recoverable.
- **§14's limitations**, as the known-issues list a bug report should be triaged against: a provider that hands back a pipe forces a copy to `cacheDir` (peak disk = archive size, once); the `.thorbak` VIEW filter matches on `Uri.getPath()` and so misses providers with opaque document ids; a VIEW-intent grant lives as long as the **task**, so a restore whose task is swiped away fails to reopen the file; progress does not survive process death, and neither does a job, because the derived key is process-scoped by design; `strings_backup.xml` is English-only.
- **§16's follow-ups**, each as one row with the reason it was deferred: migrate APK/XAPK export onto the job seam; move bulk actions onto it as `shortService`; the same for clear-all-cache; multi-app batch backup; a streaming bundle build (`OutputStream` + `DEFLATED` at level 0) to drop the staged `.xapk` copy; **and translate `strings_backup.xml` into `values-ar`, `values-es`, `values-fr` and `values-zh-rCN`** — roughly 50 strings, which is the debt the file-level `tools:ignore="MissingTranslation"` is holding open.

Leave `## Still to verify, on a device` (line 69) alone: it belongs to phase 1 and the `.xapk` work, and none of it was verified by this task either. Add this feature's 14 checks as their own subsection beneath it rather than merging the two lists — a device check that passed for phase 1 says nothing about this one.

- [ ] **Step 13: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/settings/PassphraseSettingsViewModel.kt \
  app/src/main/java/com/valhalla/thor/presentation/settings/PassphraseSettingsSheet.kt \
  app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt \
  app/src/main/java/com/valhalla/thor/data/backup/PassphraseVault.kt \
  app/src/main/res/values/strings_backup.xml \
  app/src/test/java/com/valhalla/thor/presentation/settings/PassphraseSettingsViewModelTest.kt \
  app/src/test/java/com/valhalla/thor/data/backup/PassphraseVaultTest.kt \
  docs/follow-ups/README.md \
  docs/follow-ups/app-data-backup-and-xapk-export.md
git commit -m "feat(backup): passphrase management in Settings, and the phase-2 record"
```

Explicit paths, never `git add -A`: `docs/audit/`, `docs/enforcement/` and `docs/discussions/` must not be committed.

---

## What this plan does not do

Named so a reviewer does not read an omission as an oversight:

- **No device verification.** All 14 checks in the §12 list above are the owner's, on hardware, and none of them is a step in any task. Every task's gate is a build plus JVM tests.
- **No `release-notes/` entry.** Notes ship in the `chore(release)` commit that bumps `versionCode`; this work does not touch `gradle.properties`.
- **No translations.** `strings_backup.xml` is English-only behind a file-level `tools:ignore="MissingTranslation"`, and translating it is a follow-up row filed in Task 18.
- **No Room work.** Nothing here is persisted in the database — the breadcrumb is one JSON file, the passphrase is one DataStore key, and there is no history table.
- **No migration of the existing APK/XAPK export onto the new job seam.** The seam is built to carry it, and §16 files the move, but doing it here would put a regression risk on the shipped export path inside a feature branch that cannot be device-tested.
