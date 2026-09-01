// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
    }
}
