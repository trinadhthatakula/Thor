// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

const val EXPORT_PACKAGE_KEY = "thor.export.pkg"
const val EXPORT_FORMAT_KEY = "thor.export.format"
const val EXPORT_LABEL_KEY = "thor.export.label"
const val EXPORT_TREE_KEY = "thor.export.tree"

/**
 * Everything the export worker needs, as four short strings.
 *
 * This becomes a `WorkRequest`'s input `Data`, which WorkManager writes to its own SQLite database
 * and hands back to a re-run in a fresh process. So the rule for what belongs here is not "would it
 * fit" — it is "will it still be true tomorrow".
 *
 * **[AppInfo] is `@Serializable` and would fit, and must not be here.** `publicSourceDir`,
 * `sourceDir`, `splitPublicSourceDirs` and `lastUpdateTime` are snapshots of an installed package,
 * and a worker re-run after an update would export paths that no longer exist. Only the package name
 * travels; the worker re-resolves through `appRepository.getAppDetails(pkg)`, which is what
 * `AppArchiveWorker` already does. If the app was updated meanwhile the export is of the new bytes,
 * which is correct; if it was uninstalled the lookup returns null and the worker words that.
 *
 * @param format a [BundleFormat] name, and **never** recomputed with [BundleFormat.autoFor] on the
 *   worker side. `autoFor` can never return [BundleFormat.XAPK], so recomputing would silently
 *   downgrade the one format the user has to ask for by name — and it is the format with the game
 *   data in it, so the downgrade would be invisible until the reinstall failed to run.
 * @param label the app's label as it read at tap time. Display-only, and here rather than resolved
 *   in the worker because it is read on the `setForeground` deadline path, where a `PackageManager`
 *   call is not something to be spending that budget on.
 * @param treeUri the persisted SAF tree, or null for Downloads. **Absence, not null**, once this is
 *   a map: `workDataOf` throws on a null value in production, exactly as it does on a `Set`. There is
 *   deliberately no "is Downloads" boolean beside it — the foreground resolved a definite choice and
 *   two fields could disagree.
 */
data class AppExportRequest(
    val packageName: String,
    val format: BundleFormat,
    val label: String,
    val treeUri: String? = null,
) {

    fun toMap(): Map<String, Any> = buildMap {
        put(EXPORT_PACKAGE_KEY, packageName)
        put(EXPORT_FORMAT_KEY, format.name)
        put(EXPORT_LABEL_KEY, label)
        treeUri?.let { put(EXPORT_TREE_KEY, it) }
    }

    /** Where this request writes. Rebuilt from [treeUri]'s presence, which is the only record of it. */
    val target: ExportTargetChoice
        get() = treeUri?.let(ExportTargetChoice::Custom) ?: ExportTargetChoice.Downloads

    companion object {

        /**
         * @return null when the map cannot describe a runnable export. The worker turns that into a
         *   worded `Result.failure()` — never `Result.retry()`, which would re-read the same
         *   unusable map forever.
         *
         * An unrecognised format is null rather than a fallback. The alternative is to guess, and
         * the only guess available is `autoFor`, which cannot produce XAPK — so the fallback would
         * quietly hand back a different file from the one that was asked for. A job enqueued by a
         * newer build and run after a downgrade is the case this covers, and refusing it is the
         * honest answer.
         */
        fun fromMap(map: Map<String, Any?>): AppExportRequest? {
            val packageName = (map[EXPORT_PACKAGE_KEY] as? String)?.takeIf { it.isNotBlank() }
                ?: return null
            val format = (map[EXPORT_FORMAT_KEY] as? String)
                ?.let { name -> BundleFormat.entries.firstOrNull { it.name == name } }
                ?: return null
            val label = (map[EXPORT_LABEL_KEY] as? String)?.takeIf { it.isNotBlank() }
                ?: return null
            return AppExportRequest(
                packageName = packageName,
                format = format,
                label = label,
                // Blank is treated as absent, not as a tree named "". A blank grant is not
                // resolvable, and Downloads is the destination the user gets when there is no
                // usable folder anyway.
                treeUri = (map[EXPORT_TREE_KEY] as? String)?.takeIf { it.isNotBlank() },
            )
        }
    }
}
