// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import java.util.Base64

const val BACKUP_PACKAGE_KEY = "thor.backup.package"
const val BACKUP_CLASSES_KEY = "thor.backup.classes"
const val BACKUP_BUNDLE_KEY = "thor.backup.bundle"
const val BACKUP_SALT_KEY = "thor.backup.salt"

/** OWASP's 2023 floor for PBKDF2-HMAC-SHA256. Pinned by a test; do not lower it for test speed. */
const val KDF_ITERATIONS = 210_000

/** Fresh per archive, so one reused passphrase is not one reused key. */
const val KDF_SALT_BYTES = 16

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
