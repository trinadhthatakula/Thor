// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppEntity::class,
        FreezerEntity::class,
        ExtensionDataEntity::class,
        FreezeProfileEntity::class,
        FreezeProfileAppEntity::class,
        ComponentOverrideEntity::class,
        SweepRequestEntity::class,
        SweepTargetEntity::class,
        SweepRequestSourceEntity::class,
        DataTaskEntity::class,
        ArchiveTaskDetailEntity::class,
        ExportTaskDetailEntity::class,
        DataTaskItemEntity::class,
        DataTaskOutputEntity::class,
    ],
    version = 9,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        // 5 → 6 adds the two freeze-profile tables and touches nothing that already exists, so
        // Room can generate it: a shipped database gets the new tables and keeps its watchlist.
        AutoMigration(from = 5, to = 6),
        // 6 → 7 is the same shape: one new table, `component_overrides`, and no change to any
        // existing column. No `spec =` because there is nothing for a spec to describe — a
        // pure table-add needs no @DeleteColumn/@RenameTable hint.
        AutoMigration(from = 6, to = 7),
        // 7 → 8 adds only the durable sweep request, target, and source-association tables.
        AutoMigration(from = 7, to = 8),
    ],
    exportSchema = true,
)
@TypeConverters(AppTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao
    abstract fun freezerDao(): FreezerDao
    abstract fun extensionDataDao(): ExtensionDataDao
    abstract fun freezeProfileDao(): FreezeProfileDao
    abstract fun componentOverrideDao(): ComponentOverrideDao
    abstract fun privilegeSweepDao(): PrivilegeSweepDao
    abstract fun dataTaskDao(): DataTaskDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE apps ADD COLUMN isSuspended INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createDataTaskTables(db)
                migrateSweepTables(db)
            }
        }

        private fun createDataTaskTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `data_tasks` (
                    `task_id` TEXT NOT NULL,
                    `queue_sequence` INTEGER NOT NULL,
                    `payload_schema_version` INTEGER NOT NULL,
                    `kind` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `target_key` TEXT NOT NULL,
                    `service_session_token` TEXT,
                    `claim_token` TEXT,
                    `claim_lease_expires_at_epoch_ms` INTEGER,
                    `attempt_count` INTEGER NOT NULL,
                    `cancel_requested_at_epoch_ms` INTEGER,
                    `stage` TEXT,
                    `interruption` TEXT,
                    `completed` INTEGER NOT NULL,
                    `total` INTEGER NOT NULL,
                    `result_code` TEXT,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    `claimed_at_epoch_ms` INTEGER,
                    `started_at_epoch_ms` INTEGER,
                    `updated_at_epoch_ms` INTEGER NOT NULL,
                    `terminal_at_epoch_ms` INTEGER,
                    `retain_until_epoch_ms` INTEGER,
                    `acknowledged_at_epoch_ms` INTEGER,
                    PRIMARY KEY(`task_id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_data_tasks_queue_sequence` " +
                        "ON `data_tasks` (`queue_sequence`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `archive_task_details` (
                    `task_id` TEXT NOT NULL,
                    `package_name` TEXT NOT NULL,
                    `data_class_ids_json` TEXT NOT NULL,
                    `include_bundle` INTEGER,
                    `kdf_salt_base64` TEXT,
                    `destination_kind` TEXT,
                    `destination_grant_identity` TEXT,
                    `restore_obb` INTEGER,
                    `restore_source_kind` TEXT,
                    `restore_source_grant_identity` TEXT,
                    `restore_source_private_relative_path` TEXT,
                    `deterministic_staging_identity` TEXT NOT NULL,
                    `destructive_started` INTEGER NOT NULL,
                    `mutation_package_name` TEXT,
                    `mutation_app_label` TEXT,
                    `mutation_started_at_epoch_ms` INTEGER,
                    PRIMARY KEY(`task_id`),
                    FOREIGN KEY(`task_id`) REFERENCES `data_tasks`(`task_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `export_task_details` (
                    `task_id` TEXT NOT NULL,
                    `requested_format` TEXT NOT NULL,
                    `destination_kind` TEXT,
                    `destination_grant_identity` TEXT,
                    `naming_label` TEXT,
                    `publication_policy` TEXT NOT NULL,
                    `deterministic_staging_identity` TEXT NOT NULL,
                    PRIMARY KEY(`task_id`),
                    FOREIGN KEY(`task_id`) REFERENCES `data_tasks`(`task_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `data_task_items` (
                    `task_id` TEXT NOT NULL,
                    `ordinal` INTEGER NOT NULL,
                    `package_name` TEXT NOT NULL,
                    `display_label` TEXT,
                    `state` TEXT NOT NULL,
                    `claim_token` TEXT,
                    `claim_lease_expires_at_epoch_ms` INTEGER,
                    `attempt_count` INTEGER NOT NULL,
                    `result_code` TEXT,
                    `deterministic_staging_identity` TEXT NOT NULL,
                    `started_at_epoch_ms` INTEGER,
                    `finished_at_epoch_ms` INTEGER,
                    PRIMARY KEY(`task_id`, `ordinal`),
                    FOREIGN KEY(`task_id`) REFERENCES `data_tasks`(`task_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_data_task_items_task_id` " +
                        "ON `data_task_items` (`task_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_data_task_items_state` " +
                        "ON `data_task_items` (`state`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `data_task_outputs` (
                    `output_id` TEXT NOT NULL,
                    `task_id` TEXT NOT NULL,
                    `item_ordinal` INTEGER NOT NULL,
                    `private_relative_path` TEXT,
                    `display_name` TEXT NOT NULL,
                    `mime_type` TEXT NOT NULL,
                    `byte_size` INTEGER NOT NULL,
                    `state` TEXT NOT NULL,
                    `expires_at_epoch_ms` INTEGER,
                    PRIMARY KEY(`output_id`),
                    FOREIGN KEY(`task_id`) REFERENCES `data_tasks`(`task_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`task_id`, `item_ordinal`)
                        REFERENCES `data_task_items`(`task_id`, `ordinal`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_data_task_outputs_task_id_item_ordinal` " +
                        "ON `data_task_outputs` (`task_id`, `item_ordinal`)",
            )
        }

        private fun migrateSweepTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE `sweep_requests_new` (
                    `request_id` TEXT NOT NULL,
                    `work_id` TEXT NOT NULL,
                    `operation` TEXT NOT NULL,
                    `freezer_mode` TEXT,
                    `user_id` INTEGER NOT NULL,
                    `source_surface` TEXT NOT NULL,
                    `created_at_epoch_ms` INTEGER NOT NULL,
                    `terminal_state` TEXT,
                    `succeeded` INTEGER NOT NULL,
                    `failed` INTEGER NOT NULL,
                    `busy` INTEGER NOT NULL,
                    `unresolved` INTEGER NOT NULL,
                    `terminal_at_epoch_ms` INTEGER,
                    `retain_until_epoch_ms` INTEGER,
                    `payload_schema_version` INTEGER NOT NULL,
                    `queue_sequence` INTEGER NOT NULL,
                    `state` TEXT NOT NULL,
                    `execution_id` TEXT NOT NULL,
                    `service_session_token` TEXT,
                    `claim_token` TEXT,
                    `claim_lease_expires_at_epoch_ms` INTEGER,
                    `claimed_at_epoch_ms` INTEGER,
                    `started_at_epoch_ms` INTEGER,
                    `updated_at_epoch_ms` INTEGER NOT NULL,
                    `attempt_count` INTEGER NOT NULL,
                    `cancel_requested_at_epoch_ms` INTEGER,
                    `block_reason` TEXT,
                    `acknowledged_at_epoch_ms` INTEGER,
                    PRIMARY KEY(`request_id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `sweep_requests_new` (
                    request_id, work_id, operation, freezer_mode, user_id, source_surface,
                    created_at_epoch_ms, terminal_state, succeeded, failed, busy, unresolved,
                    terminal_at_epoch_ms, retain_until_epoch_ms, payload_schema_version,
                    queue_sequence, state, execution_id, service_session_token, claim_token,
                    claim_lease_expires_at_epoch_ms, claimed_at_epoch_ms, started_at_epoch_ms,
                    updated_at_epoch_ms, attempt_count, cancel_requested_at_epoch_ms,
                    block_reason, acknowledged_at_epoch_ms
                )
                SELECT
                    old.request_id,
                    old.work_id,
                    old.operation,
                    old.freezer_mode,
                    old.user_id,
                    old.source_surface,
                    old.created_at_epoch_ms,
                    old.terminal_state,
                    CASE WHEN old.terminal_state IS NULL THEN 0 ELSE COALESCE(old.succeeded, 0) END,
                    CASE WHEN old.terminal_state IS NULL THEN 0 ELSE COALESCE(old.failed, 0) END,
                    CASE WHEN old.terminal_state IS NULL THEN 0 ELSE COALESCE(old.busy, 0) END,
                    CASE WHEN old.terminal_state IS NULL THEN (
                        SELECT COUNT(*) FROM sweep_targets
                        WHERE sweep_targets.request_id = old.request_id
                    ) ELSE COALESCE(old.unresolved, 0) END,
                    old.terminal_at_epoch_ms,
                    old.retain_until_epoch_ms,
                    1,
                    1 + (
                        SELECT COUNT(*) FROM sweep_requests AS earlier
                        WHERE earlier.created_at_epoch_ms < old.created_at_epoch_ms
                           OR (earlier.created_at_epoch_ms = old.created_at_epoch_ms
                               AND earlier.request_id < old.request_id)
                    ),
                    CASE WHEN old.terminal_state IS NULL THEN 'BLOCKED' ELSE old.terminal_state END,
                    old.request_id,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    COALESCE(old.terminal_at_epoch_ms, old.created_at_epoch_ms),
                    0,
                    NULL,
                    NULL,
                    NULL
                FROM sweep_requests AS old
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE `sweep_targets_new` (
                    `request_id` TEXT NOT NULL,
                    `ordinal` INTEGER NOT NULL,
                    `package_name` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `claim_token` TEXT,
                    `claim_lease_expires_at_epoch_ms` INTEGER,
                    `attempt_count` INTEGER NOT NULL,
                    `started_at_epoch_ms` INTEGER,
                    `finished_at_epoch_ms` INTEGER,
                    `result_code` TEXT,
                    `root_lane_degraded` INTEGER NOT NULL,
                    PRIMARY KEY(`request_id`, `ordinal`),
                    FOREIGN KEY(`request_id`) REFERENCES `sweep_requests_new`(`request_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `sweep_targets_new` (
                    request_id, ordinal, package_name, state, claim_token,
                    claim_lease_expires_at_epoch_ms, attempt_count, started_at_epoch_ms,
                    finished_at_epoch_ms, result_code, root_lane_degraded
                )
                SELECT request_id, ordinal, package_name, 'LEGACY_UNKNOWN', NULL, NULL, 0,
                    NULL, NULL, NULL, 0
                FROM sweep_targets
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE `sweep_request_sources_new` (
                    `request_id` TEXT NOT NULL,
                    `source_surface` TEXT NOT NULL,
                    `associated_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`request_id`, `source_surface`),
                    FOREIGN KEY(`request_id`) REFERENCES `sweep_requests_new`(`request_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `sweep_request_sources_new` (
                    request_id, source_surface, associated_at_epoch_ms
                )
                SELECT request_id, source_surface, associated_at_epoch_ms
                FROM sweep_request_sources
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `sweep_request_sources`")
            db.execSQL("DROP TABLE `sweep_targets`")
            db.execSQL("DROP TABLE `sweep_requests`")
            db.execSQL("ALTER TABLE `sweep_requests_new` RENAME TO `sweep_requests`")
            db.execSQL(
                """
                CREATE TABLE `sweep_targets` (
                    `request_id` TEXT NOT NULL,
                    `ordinal` INTEGER NOT NULL,
                    `package_name` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `claim_token` TEXT,
                    `claim_lease_expires_at_epoch_ms` INTEGER,
                    `attempt_count` INTEGER NOT NULL,
                    `started_at_epoch_ms` INTEGER,
                    `finished_at_epoch_ms` INTEGER,
                    `result_code` TEXT,
                    `root_lane_degraded` INTEGER NOT NULL,
                    PRIMARY KEY(`request_id`, `ordinal`),
                    FOREIGN KEY(`request_id`) REFERENCES `sweep_requests`(`request_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `sweep_targets` (
                    request_id, ordinal, package_name, state, claim_token,
                    claim_lease_expires_at_epoch_ms, attempt_count, started_at_epoch_ms,
                    finished_at_epoch_ms, result_code, root_lane_degraded
                )
                SELECT request_id, ordinal, package_name, state, claim_token,
                    claim_lease_expires_at_epoch_ms, attempt_count, started_at_epoch_ms,
                    finished_at_epoch_ms, result_code, root_lane_degraded
                FROM `sweep_targets_new`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `sweep_targets_new`")
            db.execSQL(
                """
                CREATE TABLE `sweep_request_sources` (
                    `request_id` TEXT NOT NULL,
                    `source_surface` TEXT NOT NULL,
                    `associated_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`request_id`, `source_surface`),
                    FOREIGN KEY(`request_id`) REFERENCES `sweep_requests`(`request_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `sweep_request_sources` (
                    request_id, source_surface, associated_at_epoch_ms
                )
                SELECT request_id, source_surface, associated_at_epoch_ms
                FROM `sweep_request_sources_new`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `sweep_request_sources_new`")
            db.execSQL(
                "CREATE UNIQUE INDEX `index_sweep_requests_work_id` ON `sweep_requests` (`work_id`)",
            )
            db.execSQL(
                "CREATE INDEX `index_sweep_requests_queue_sequence` " +
                        "ON `sweep_requests` (`queue_sequence`)",
            )
            db.execSQL(
                "CREATE INDEX `index_sweep_targets_request_id` ON `sweep_targets` (`request_id`)",
            )
            db.execSQL(
                "CREATE INDEX `index_sweep_targets_state` ON `sweep_targets` (`state`)",
            )
            db.execSQL(
                "CREATE INDEX `index_sweep_request_sources_request_id` " +
                        "ON `sweep_request_sources` (`request_id`)",
            )
        }
    }
}
