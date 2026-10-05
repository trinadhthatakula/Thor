# Room 3 migration assessment

Recorded 2026-10-05 against `dev` at `6967de7d5ce636d9ab66553a6d0e6df1e75c2231`
(after PR #560). Status: **assessment recorded; implementation not scheduled**.
The maintainer requested this follow-up after discussing the benefits and costs. This does
not authorize a database migration or make it part of the APK-size optimization work.

## Recommendation

Keep Room 2.8.5 for the current APK-size work. Plan a staged Room 3 migration as database
maintenance when its benefits justify the validation effort. The stable version reviewed
was **Room 3.0.3**; recheck releases before implementation rather than choosing the 3.1 alpha
or treating this version snapshot as permanent.

There is no measured Thor APK-size, memory, startup, or query-performance benefit from
Room 3. WorkManager currently depends on Room 2, so switching Thor's own database to Room 3
would retain both runtime families. Choose `AndroidSQLiteDriver` for an initial migration
unless a separate requirement justifies shipping a bundled SQLite engine.

See [APK optimization status](apk-size-optimization-status.md) for completed work, dropped
experiments, and the separate legacy WorkManager retirement candidate.

## Current Thor implementation

- [Version catalog](../../gradle/libs.versions.toml): Room **2.8.5**, KSP **2.3.12**, Kotlin
  **2.4.20**, WorkManager **2.12.0**, and minSdk **28** at this assessment.
- Room dependencies and its Gradle plugin are confined to `:app`.
  [`:bypass`](../../bypass/build.gradle.kts) and
  [`:vm-runtime`](../../vm-runtime/build.gradle.kts) have no direct Room dependency.
- [AppDatabase](../../app/src/main/java/com/valhalla/thor/data/source/local/room/AppDatabase.kt)
  has **14 entities, seven DAOs, and schema version 10**, with exported schemas 1–10.
  Six automatic migrations cover 2→8; manual migrations cover 1→2, 8→9, and 9→10.
- [Database construction](../../app/src/main/java/com/valhalla/thor/di/Modules.kt) uses
  `thor_database`, with no explicit `SQLiteDriver`. Destructive fallback is **debug-only**.
- DAOs already use suspend functions and Flow. The audit found no blocking query signatures,
  Room RxJava/LiveData/Paging integrations, production `@RawQuery`, or direct production
  `openHelper` access. There are **62 `@Transaction` annotations**.
- The redundant direct `room-ktx` declaration was removed in PR #558. Its Kotlin APIs already
  belong to Room runtime; Room 3 does not unlock an additional KTX-removal saving.

## Differences and practical benefits

| Area | Room 3 change | Impact on Thor |
| --- | --- | --- |
| Core model | Keeps entities, DAOs, SQL queries, and a database builder | Most application logic remains applicable. |
| Names and build configuration | Uses `androidx.room3`, `room3-*` artifacts, the `androidx.room3` plugin, and `room3 {}` | Update dependencies, imports, schema configuration, and test artifacts together. |
| Kotlin and asynchronous work | Requires KSP, generates Kotlin, and requires coroutine-based database operations | Thor already follows this model. Java source input is still supported by KSP. |
| SQLite access | Requires `SQLiteDriver`; core APIs drop `SupportSQLiteDatabase` and Android Cursor access | Port manual migrations and legacy test infrastructure. Migration/callback overrides become suspend functions using `SQLiteConnection`. |
| Additional capabilities | FTS5, composite relations, Kotlin default values in query results, and `WITHOUT ROWID` tables | Potential future features; no automatic benefit to current queries or schema. |
| Platforms | Adds JavaScript/Wasm support; KMP and driver support already exist in Room 2.8 | Little immediate value to Thor's Android-specific app. |

Google's feature-development focus is Room 3; Room 2 entered maintenance mode. Room 2.8.5
was still released on 2026-09-09. This supports planning the transition without presenting
our current version as abandoned or promising indefinite Room 2 support.

## APK and driver tradeoffs

The cached published Gradle metadata for `androidx.work:work-runtime:2.12.0` declares a
runtime dependency on `androidx.room:room-runtime:2.7.0`. Thor currently selects Room 2.8.5
through its direct declaration. Room 3 uses different coordinates and packages, so it does
not replace WorkManager's Room 2 dependency. Both runtime families would remain unless
that dependency changes or WorkManager is safely retired. R8 can trim unused code, but
the exact minified APK delta has **not** been measured. Do not exclude Room 2 from
WorkManager to force a smaller graph.

| Driver | Benefit | Cost or limitation |
| --- | --- | --- |
| `AndroidSQLiteDriver` (`sqlite-framework`) | Uses the system SQLite engine without bundling a replacement engine | SQLite version and capabilities remain dependent on Android/OEM software. |
| `BundledSQLiteDriver` (`sqlite-bundled`) | Ships a known SQLite version consistently across devices | Adds native payload and its ABI/page-size/loading validation responsibilities. Measure APK and installed-storage impact. |

Bundled SQLite is optional in Room 3 and is already available with Room 2.8. It is a separate
decision, not an inherent benefit or requirement of the major-version upgrade.

## Migration risks specific to Thor

The database contains durable user and operation state: freezer membership/profiles,
extension values, component overrides used for restoration, and the data-task and privilege
sweep queues. It cannot be treated as a disposable app cache.

1. The 8→9 migration rebuilds sweep tables while preserving ordering, history, and source
   associations. Convert its SQL plumbing without losing the historical migration paths.
2. Transaction behavior protects claims, cancellation, recovery, and destructive-restore
   checkpoints. In particular, `finishDrainIfQueueEmpty` in
   [DataTaskDao](../../app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskDao.kt)
   and
   [PrivilegeSweepDao](../../app/src/main/java/com/valhalla/thor/data/source/local/room/PrivilegeSweepDao.kt)
   must serialize the stop decision against concurrent inserts. Preserve the existing
   cross-connection test's behavioral guarantee when replacing its lock-observation mechanism.
3. Seventeen test files directly import Room/SQLite; nine use legacy `openHelper`,
   `SupportSQLite`, or `runInTransaction` APIs. The DAO suites include open-helper wrappers
   that observe writer-lock acquisition. Porting tests is a meaningful part of the work.
4. [SweepMigrationTest](../../app/src/androidTest/java/com/valhalla/thor/data/source/local/room/SweepMigrationTest.kt)
   covers important later migrations, but the audit did not establish full 1→10 coverage.
5. [FreezerViewModel](../../app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt)
   maps Android `SQLiteConstraintException` to user-facing profile errors. Check the chosen
   driver's actual exception behavior and adapt the mapping/tests where necessary; do not
   assume the same exception class for every driver.

Keep the existing database and user state. Changing the Room library version does not by
itself justify destructive migration or an application schema-version bump. Verify schema
compatibility and exported-schema differences rather than assuming them.

## Proposed stages and acceptance

- [ ] Recheck current Room/SQLite releases, WorkManager dependencies, toolchain compatibility,
      and this assessment. Create a topic branch from current `dev` in
      `~/StudioProject/Thor-worktrees` before changing code.
- [ ] While on Room 2.8.5, migrate legacy SQLite APIs and tests, then explicitly configure
      `AndroidSQLiteDriver`. Set the driver after porting incompatible calls. Avoid adding
      a compatibility wrapper unless a concrete remaining dependency needs it.
- [ ] Validate existing-database upgrades and preservation, queue concurrency, cancellation,
      process-death recovery, Flow invalidation, and profile constraint handling on the
      emulator and physical device.
- [ ] Switch artifacts, plugin/DSL, imports, column-converter annotations, test APIs, and
      suspend migration/callback overrides to the chosen stable Room 3 version. Retain
      historical schema exports and compare newly generated schemas.
- [ ] Repeat meaningful persistence/device checks with minification enabled. Run the
      repository's `test lintFossDebug lintStoreRelease` gates and both release builds
      before opening a PR to `dev`.
- [ ] Compare equivalent before/after FOSS and Store APKs and record source/artifact hashes,
      toolchain, signing state, selected dependency graph, and measured performance if a
      performance claim is proposed. Do not treat raw library sizes as APK deltas.

No prototype, build, or device test of Room 3 was performed for this assessment. The staged
sequence above is a recommendation, not completed work or a requirement to retire
WorkManager first.

## Sources reviewed

- [Room 3 release notes](https://developer.android.com/jetpack/androidx/releases/room3)
- [Room 2 release notes](https://developer.android.com/jetpack/androidx/releases/room)
- [Official Room 2→3 migration guide](https://developer.android.com/training/data-storage/room/migration-2-to-3)
- [Room modernization and maintenance policy](https://developer.android.com/blog/posts/modernizing-the-room)
- [SQLite driver implementations](https://developer.android.com/kotlin/multiplatform/sqlite)

Repository and dependency facts above were checked on the recorded source snapshot;
external release information was reviewed on 2026-10-05.
