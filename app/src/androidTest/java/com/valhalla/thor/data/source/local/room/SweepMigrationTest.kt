// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SweepMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
    )

    @Test
    fun migrate7To8_preservesExistingRowsAndAddsCascadingSweepTables() {
        helper.createDatabase(TEST_DATABASE, 7).apply {
            seedEveryVersion7Table()
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DATABASE, 8, true)
        migrated.setForeignKeyConstraintsEnabled(true)

        VERSION_7_TABLES.forEach { table ->
            assertEquals("$table row was not preserved", 1, migrated.rowCount(table))
        }

        migrated.execSQL(
            """
            INSERT INTO sweep_requests (
                request_id, work_id, operation, freezer_mode, user_id, source_surface,
                created_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>("request-1", "work-1", "FREEZE", "SUSPEND", 0, "FREEZER", 1234L),
        )
        migrated.execSQL(
            "INSERT INTO sweep_targets (request_id, ordinal, package_name) VALUES (?, ?, ?)",
            arrayOf<Any?>("request-1", 0, "a.pkg"),
        )
        migrated.execSQL(
            "INSERT INTO sweep_targets (request_id, ordinal, package_name) VALUES (?, ?, ?)",
            arrayOf<Any?>("request-1", 1, "z.pkg"),
        )
        migrated.execSQL(
            """
            INSERT INTO sweep_request_sources (
                request_id, source_surface, associated_at_epoch_ms
            ) VALUES (?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>("request-1", "FREEZER", 1234L),
        )
        migrated.execSQL(
            """
            INSERT INTO sweep_request_sources (
                request_id, source_surface, associated_at_epoch_ms
            ) VALUES (?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>("request-1", "QS_TILE", 2345L),
        )

        assertEquals(1, migrated.rowCount("sweep_requests"))
        assertEquals(2, migrated.rowCount("sweep_targets"))
        assertEquals(2, migrated.rowCount("sweep_request_sources"))

        migrated.execSQL(
            "DELETE FROM sweep_requests WHERE request_id = ?",
            arrayOf<Any?>("request-1")
        )

        assertEquals(0, migrated.rowCount("sweep_requests"))
        assertEquals(0, migrated.rowCount("sweep_targets"))
        assertEquals(0, migrated.rowCount("sweep_request_sources"))
        migrated.close()
    }

    @Test
    fun migrate8To9_createsDataTablesAndPreservesSweepRows() {
        helper.createDatabase(TEST_DATABASE, 8).apply {
            seedVersion8Sweep(
                requestId = "request-preserved",
                workId = "work-preserved",
                terminalState = null,
                succeeded = null,
                failed = null,
                busy = null,
                unresolved = null,
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            9,
            true,
            AppDatabase.MIGRATION_8_9,
        )

        DATA_TABLES.forEach { table -> assertEquals(0, migrated.rowCount(table)) }
        assertEquals(1, migrated.rowCount("sweep_requests"))
        assertEquals(2, migrated.rowCount("sweep_targets"))
        assertEquals(2, migrated.rowCount("sweep_request_sources"))
        assertEquals("work-preserved", migrated.stringValue("sweep_requests", "work_id"))
        assertEquals("CLEAR_CACHE", migrated.stringValue("sweep_requests", "operation"))
        assertEquals("request-preserved", migrated.stringValue("sweep_requests", "execution_id"))
        assertEquals(1, migrated.intValue("sweep_requests", "payload_schema_version"))
        assertTrue(migrated.longValue("sweep_requests", "queue_sequence") > 0L)
        migrated.close()
    }

    @Test
    fun migrate8To9_nonterminalSweepPreservesOrdinalAndSourcesAndMapsEveryTargetLegacyUnknown() {
        helper.createDatabase(TEST_DATABASE, 8).apply {
            seedVersion8Sweep(
                requestId = "request-active",
                workId = "work-active",
                terminalState = null,
                succeeded = 1,
                failed = 0,
                busy = 0,
                unresolved = 1,
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            9,
            true,
            AppDatabase.MIGRATION_8_9,
        )

        assertEquals("BLOCKED", migrated.stringValue("sweep_requests", "state"))
        assertEquals(0, migrated.intValue("sweep_requests", "succeeded"))
        assertEquals(0, migrated.intValue("sweep_requests", "failed"))
        assertEquals(0, migrated.intValue("sweep_requests", "busy"))
        assertEquals(2, migrated.intValue("sweep_requests", "unresolved"))
        assertEquals(
            listOf(0 to "a.pkg", 1 to "z.pkg"),
            migrated.targetOrdinalsAndPackages("request-active"),
        )
        assertEquals(
            listOf("LEGACY_UNKNOWN", "LEGACY_UNKNOWN"),
            migrated.targetStates("request-active"),
        )
        assertEquals(
            listOf("FREEZER", "QS_TILE"),
            migrated.sourceSurfaces("request-active"),
        )
        migrated.close()
    }

    @Test
    fun migrate8To9_terminalSweepPreservesHistoryWithoutInventingPerTargetSuccess() {
        helper.createDatabase(TEST_DATABASE, 8).apply {
            seedVersion8Sweep(
                requestId = "request-terminal",
                workId = "work-terminal",
                terminalState = "PARTIAL",
                succeeded = 1,
                failed = 1,
                busy = 0,
                unresolved = 0,
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            9,
            true,
            AppDatabase.MIGRATION_8_9,
        )

        assertEquals("PARTIAL", migrated.stringValue("sweep_requests", "state"))
        assertEquals("PARTIAL", migrated.stringValue("sweep_requests", "terminal_state"))
        assertEquals(1, migrated.intValue("sweep_requests", "succeeded"))
        assertEquals(1, migrated.intValue("sweep_requests", "failed"))
        assertEquals(0, migrated.intValue("sweep_requests", "busy"))
        assertEquals(0, migrated.intValue("sweep_requests", "unresolved"))
        assertEquals(
            listOf("LEGACY_UNKNOWN", "LEGACY_UNKNOWN"),
            migrated.targetStates("request-terminal"),
        )
        migrated.close()
    }

    @Test
    fun migrate9To10_defaultsExistingSweepMembershipIntentToFalse() {
        helper.createDatabase(TEST_DATABASE, 9).apply {
            seedVersion9Sweep(requestId = "request-legacy-membership")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            10,
            true,
            AppDatabase.MIGRATION_9_10,
        )

        assertEquals(0, migrated.intValue("sweep_requests", "add_to_freezer"))
        migrated.execSQL(
            "UPDATE sweep_requests SET add_to_freezer = 1 WHERE request_id = ?",
            arrayOf<Any?>("request-legacy-membership"),
        )
        assertEquals(1, migrated.intValue("sweep_requests", "add_to_freezer"))
        migrated.close()
    }

    @Test
    fun everySupportedStartingVersionMigratesThroughTheRealChainToSchema10() {
        (1..9).forEach { startVersion ->
            val databaseName = "$TEST_DATABASE-chain-$startVersion"
            helper.createDatabase(databaseName, startVersion).close()

            helper.runMigrationsAndValidate(
                databaseName,
                10,
                true,
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
            ).close()
        }
    }

    private fun SupportSQLiteDatabase.seedVersion9Sweep(requestId: String) {
        execSQL(
            """
            INSERT INTO sweep_requests (
                request_id, work_id, operation, freezer_mode, user_id, source_surface,
                created_at_epoch_ms, terminal_state, succeeded, failed, busy, unresolved,
                terminal_at_epoch_ms, retain_until_epoch_ms, payload_schema_version,
                queue_sequence, state, execution_id, service_session_token, claim_token,
                claim_lease_expires_at_epoch_ms, claimed_at_epoch_ms, started_at_epoch_ms,
                updated_at_epoch_ms, attempt_count, cancel_requested_at_epoch_ms,
                block_reason, acknowledged_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                requestId, "work-legacy-membership", "FREEZE", "FREEZE", 0, "APP_LIST",
                1L, null, 0, 0, 0, 1, null, null, 1, 1L, "QUEUED",
                "work-legacy-membership", null, null, null, null, null, 1L, 0, null, null, null,
            ),
        )
    }

    private fun SupportSQLiteDatabase.seedVersion8Sweep(
        requestId: String,
        workId: String,
        terminalState: String?,
        succeeded: Int?,
        failed: Int?,
        busy: Int?,
        unresolved: Int?,
    ) {
        execSQL(
            """
            INSERT INTO sweep_requests (
                request_id, work_id, operation, freezer_mode, user_id, source_surface,
                created_at_epoch_ms, terminal_state, succeeded, failed, busy, unresolved,
                terminal_at_epoch_ms, retain_until_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                requestId,
                workId,
                "CLEAR_CACHE",
                null,
                10,
                "FREEZER",
                1_234L,
                terminalState,
                succeeded,
                failed,
                busy,
                unresolved,
                terminalState?.let { 2_000L },
                terminalState?.let { 3_000L },
            ),
        )
        listOf(0 to "a.pkg", 1 to "z.pkg").forEach { (ordinal, packageName) ->
            execSQL(
                "INSERT INTO sweep_targets (request_id, ordinal, package_name) VALUES (?, ?, ?)",
                arrayOf<Any?>(requestId, ordinal, packageName),
            )
        }
        listOf("FREEZER" to 1_234L, "QS_TILE" to 1_500L).forEach { (source, time) ->
            execSQL(
                """
                INSERT INTO sweep_request_sources (
                    request_id, source_surface, associated_at_epoch_ms
                ) VALUES (?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(requestId, source, time),
            )
        }
    }

    private fun SupportSQLiteDatabase.seedEveryVersion7Table() {
        execSQL(
            """
            INSERT INTO apps (
                packageName, versionCode, minSdk, targetSdk, isSystem,
                splitPublicSourceDirs, enabled, sharedDataDir, lastUpdateTime,
                firstInstallTime, isDebuggable, isSuspended
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>("seed.app", 1L, 28, 37, 0, "[]", 1, "", 20L, 10L, 0, 0),
        )
        execSQL("INSERT INTO freezer_apps (packageName) VALUES (?)", arrayOf<Any?>("seed.frozen"))
        execSQL(
            "INSERT INTO extension_data (extensionPackageName, `key`, value) VALUES (?, ?, ?)",
            arrayOf<Any?>("seed.extension", "seed-key", "seed-value"),
        )
        execSQL(
            "INSERT INTO freeze_profiles (id, name, createdAt) VALUES (?, ?, ?)",
            arrayOf<Any?>(1L, "Seed profile", 30L),
        )
        execSQL(
            "INSERT INTO freeze_profile_apps (profileId, packageName) VALUES (?, ?)",
            arrayOf<Any?>(1L, "seed.profile.app"),
        )
        execSQL(
            """
            INSERT INTO component_overrides (
                packageName, className, userId, componentType, restoreToEnabled, disabledAt
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>("seed.component.app", "seed.Component", 0, "ACTIVITY", 1, 40L),
        )
    }

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst()) { "COUNT(*) returned no row for $table" }
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.stringValue(table: String, column: String): String =
        query("SELECT `$column` FROM `$table` LIMIT 1").use { cursor ->
            check(cursor.moveToFirst()) { "No row in $table" }
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.intValue(table: String, column: String): Int =
        query("SELECT `$column` FROM `$table` LIMIT 1").use { cursor ->
            check(cursor.moveToFirst()) { "No row in $table" }
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.longValue(table: String, column: String): Long =
        query("SELECT `$column` FROM `$table` LIMIT 1").use { cursor ->
            check(cursor.moveToFirst()) { "No row in $table" }
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.targetOrdinalsAndPackages(requestId: String): List<Pair<Int, String>> =
        query(
            "SELECT ordinal, package_name FROM sweep_targets WHERE request_id = ? ORDER BY ordinal",
            arrayOf(requestId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getInt(0) to cursor.getString(1))
            }
        }

    private fun SupportSQLiteDatabase.targetStates(requestId: String): List<String> =
        query(
            "SELECT state FROM sweep_targets WHERE request_id = ? ORDER BY ordinal",
            arrayOf(requestId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun SupportSQLiteDatabase.sourceSurfaces(requestId: String): List<String> =
        query(
            """
            SELECT source_surface FROM sweep_request_sources
            WHERE request_id = ? ORDER BY associated_at_epoch_ms, source_surface
            """.trimIndent(),
            arrayOf(requestId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private companion object {
        const val TEST_DATABASE = "sweep-migration-test"
        val VERSION_7_TABLES = listOf(
            "apps",
            "freezer_apps",
            "extension_data",
            "freeze_profiles",
            "freeze_profile_apps",
            "component_overrides",
        )
        val DATA_TABLES = listOf(
            "data_tasks",
            "archive_task_details",
            "export_task_details",
            "data_task_items",
            "data_task_outputs",
        )
    }
}
