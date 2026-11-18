# Room 3 migration

This migration is separate from the approved October UI/UX audit changes.

## Database compatibility

- Use stable Room 3.0.3 and SQLite 2.7.1 with the platform `AndroidSQLiteDriver`.
- Keep the database file `thor_database`, logical schema version 10, and all ten exported schemas.
- The generated v10 identity remains `93f2710b497290df9e85cca01a341a2d`.
- Preserve the SQL and migration graph: manual 1→2, 8→9 and 9→10, with automatic 2→8 steps.
- Use suspend `SQLiteConnection` migration callbacks and the asynchronous SQLite SQL helper.
- Update column converter annotations and relation column arrays. Existing DAO transaction boundaries remain.
- Remove the debug destructive fallback, so a missing migration cannot silently discard profiles or queued work.

No database version or app versionCode bump is needed for the unchanged schema. Unsupported downgrade paths continue to fail rather than recreating data.

## Dependencies and fixtures

The app uses Room 3 imports, artifacts, compiler and Gradle plugin. WorkManager can retain its own Room 2 dependency; it is not excluded or forced to Room 3.

Legacy low-level SQL fixtures use the official `room3-sqlite-wrapper` in test configurations only. Production code uses driver APIs. The two concurrent-writer probes now wrap the driver and observe `BEGIN IMMEDIATE` acquisition, preserving the driver's connection-pool capability. Migration fixtures use `SQLiteConnection` directly and retain the previous strict unexpected-table check.

## Validation

All ten exported schemas were compared byte-for-byte with dev, and the generated Room 3 database identity was compared with the existing v10 export.

- `test lintFossDebug lintStoreRelease` passed with serial workers and a local 6 GiB Kotlin compiler heap.
- FOSS and Store each passed 3,673 JVM tests, with no failures, errors or skips. Both lint gates reported no errors or warnings.
- FOSS/Store debug compilation and the FOSS instrumentation APK build passed.
- The SDK 37.2 AVD passed all 108 tests in `SweepMigrationTest`, `DataTaskDaoTest`, `DataTaskIdentityDaoTest` and `PrivilegeSweepDaoTest`.
- The same AVD passed all nine tests in `PrivilegeSweepWorkerIntegrationTest`, `PrivilegeSweepServiceIntegrationTest` and `LegacyWorkManagerCutoverIntegrationTest`.
- Both release dependency graphs retain WorkManager's Room 2.7.0 and the app's Room 3.0.3, with no `room3-sqlite-wrapper` in either runtime configuration.

The initial instrumentation run exposed a raw-SQL fixture that bypassed Room's invalidation boundary. The fixture now uses `withWriteTransaction`; its original Flow assertions pass without relaxation.

The instrumentation suite covers each starting schema version 1–9 through v10, historical rows and cascades, task ownership and queue ordering. SDK 37.2 testing does not establish behavior on every supported Android version or OEM SQLite implementation.

## References

- [Room 2 to Room 3 migration guide](https://developer.android.com/training/data-storage/room/migration-2-to-3)
- [Room 3 releases](https://developer.android.com/jetpack/androidx/releases/room3)
