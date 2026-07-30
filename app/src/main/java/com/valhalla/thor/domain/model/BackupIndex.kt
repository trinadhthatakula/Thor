// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Manifest of one multi-app export, written beside the bundles it describes as
 * `thor-backup-<timestamp>.json` — see [fileNameFor].
 *
 * The reader is deliberately assumed **not** to be Thor: a human opening the folder, a `jq`
 * one-liner, or a restore tool written years from now against schema v1. That is what
 * [schemaVersion] is for, and why nothing here is a Kotlin-shaped construct a foreign reader
 * would have to understand.
 */
@Serializable
data class BackupIndex(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Epoch millis at which the run finished writing its entries. */
    val createdAt: Long,
    /** The Thor build that produced this folder, for diagnosing a bundle a later Thor rejects. */
    val thorVersionCode: Int,
    val entries: List<BackupEntry>,
) {
    fun encode(): String = json.encodeToString(this)

    companion object {
        const val SCHEMA_VERSION = 1

        /** What a reader globs for to find every manifest in a folder: `thor-backup-*.json`. */
        const val FILE_NAME_PREFIX = "thor-backup-"
        const val FILE_NAME_SUFFIX = ".json"
        const val MIME = "application/json"

        // Local time, not UTC: the only consumer of the *name* is a human sorting a folder, and
        // "the export I ran last night" is a local-clock notion. Machines read createdAt, which
        // is unambiguous epoch millis. Sortable field order means lexical sort == chronological
        // sort for anyone who only has the name.
        //
        // Milliseconds, not seconds, and that is not decoration. Two runs can finish in the same
        // *second* — cancel-and-replace is the ordinary way it happens, since the cancelled run
        // writes its manifest under NonCancellable while the replacement is already going — and
        // both file-store paths write by name after deleting a collision. At second granularity
        // that silently destroys one of the two manifests and leaves its bundles undescribed,
        // which is the exact failure the timestamped name exists to prevent.
        private val fileStamp: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())

        /**
         * One manifest per run, named for when the run finished.
         *
         * A single fixed name would have each export overwrite the last one's manifest in a folder
         * the user keeps exporting into, leaving every earlier bundle undescribed — the manifest
         * would then be a *worse* record than the file listing it sits next to.
         *
         * Collision is now bounded by the clock's resolution rather than by a second: two runs
         * would have to finish their manifest write within the same millisecond, which no pair of
         * SAF writes does. It is not *impossible*, so a reader that finds one manifest where it
         * expected two should treat that as the known limit, not as a missing run.
         */
        fun fileNameFor(createdAt: Long): String =
            FILE_NAME_PREFIX + fileStamp.format(Instant.ofEpochMilli(createdAt)) + FILE_NAME_SUFFIX

        // prettyPrint because this file is meant to be opened and read by a person; it is a few
        // hundred bytes next to gigabytes of APK, so the whitespace costs nothing. encodeDefaults
        // so schemaVersion — the one field a foreign reader must see to know how to parse the
        // rest — is written even though it equals its default.
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun decode(text: String): BackupIndex = json.decodeFromString<BackupIndex>(text)
    }
}

/**
 * One app the run attempted.
 *
 * A partial batch is the *normal* case — a system app whose source cannot be read, a device that
 * fills up halfway — so an index listing only what succeeded would be a silent lie about the
 * folder it sits in. Failure is therefore carried as a nullable [error] on the same flat shape
 * rather than as a second variant of a sealed hierarchy. Two reasons:
 *
 * - a polymorphic hierarchy puts a `"type"` discriminator on every entry and makes a failed entry
 *   structurally different, so a reader that just wants "which file is which app" has to learn
 *   Kotlin's discriminator convention before it can skip the ones it does not care about;
 * - polymorphic decoding fails hard on an unknown discriminator, so a third outcome added in
 *   schema v2 (say "skipped, no space") would break every v1 reader. Another nullable field
 *   would not.
 *
 * [fileName] and [sizeBytes] are null exactly when [error] is non-null: there is no file to name
 * and no bytes to count.
 */
@Serializable
data class BackupEntry(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String,
    val format: BundleFormat,
    val fileName: String? = null,
    /**
     * Size of the APK payload in bytes, measured at the source.
     *
     * Exact for a monolithic [BundleFormat.APK], which is a plain copy. For a zip container it is
     * the summed length of the APKs that went in and so excludes zip framing and the JSON/icon
     * sidecars — the export path returns a location label, not the written file, so the real
     * on-disk length is not observable from here.
     */
    val sizeBytes: Long? = null,
    /**
     * Technical description of why this app produced no file, or null on success.
     *
     * Not user-facing copy and not localised — this is a diagnostic in a JSON file. The UI maps
     * its own wording from the structured run result instead.
     */
    val error: String? = null,
)

/**
 * The name the bundle builder will give [appInfo]'s bundle in [format].
 *
 * This mirrors the sanitiser in `AppBundleBuilderImpl.build` because the export path hands back a
 * location *label*, not the file it wrote, so there is no way to observe the real name from here.
 * The duplication is the reason it is a named function rather than an inline expression: when the
 * builder's naming rule changes, this is the one other place that has to change with it, and the
 * right cleanup is to have the builder call this instead of rolling its own.
 */
fun bundleFileNameFor(appInfo: AppInfo, format: BundleFormat): String {
    val safeName = "${appInfo.formattedAppName()}_${appInfo.versionName}"
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    return "$safeName.${format.extension}"
}
