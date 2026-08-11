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
