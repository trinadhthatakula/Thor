// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * Every shell string app-data backup and restore send to a privileged shell.
 *
 * Pure, and each builder returns `String?` where null means *refused* — the shape
 * `PerUserCommands.kt` and `obbPlaceCommand` already use, for the same reason: none of this is
 * validated by the thing that runs it, so it is validated at the site rather than inherited from
 * some earlier call having refused it.
 *
 * **Glossary, because two different numbers in this file are called "uid".** `userId` is the Android
 * multi-user id (0, 10, …) that appears *in a path*. `uid` is the app's Linux uid from
 * `ApplicationInfo.uid` and appears only in `chown`. They are never the same value.
 */

/** Printed on a probe's success path. */
const val THOR_OK = "THOR_OK"

/** Printed when the directory being measured or listed does not exist at all. */
const val THOR_ABSENT = "THOR_ABSENT"

/**
 * Deliberately a copy of `ObbProbeParser`'s regex rather than an import of it.
 *
 * That one is `internal` in `com.valhalla.thor.data.repository`, and a `domain/` file importing from
 * `data/` inverts the layering the module is built on. Four lines of duplication is the cheaper
 * price. The two must stay identical: a name one accepts and the other refuses is a bug in whichever
 * path is more permissive.
 */
private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")

internal fun isUsablePackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

/**
 * True when [path] can be interpolated inside single quotes in a shell command.
 *
 * Absolute because every path here names a system location and a relative one would resolve against
 * whatever directory the shell happens to be in; no `'` because that is what closes the quoting; no
 * newline because that ends the command.
 */
internal fun isQuotableAbsolutePath(path: String): Boolean =
    path.startsWith('/') && path.none { it == '\'' || it == '\n' }

/** True when [name] is safe as a quoted `tar` operand. */
private fun isQuotableEntryName(name: String): Boolean =
    name.isNotEmpty() &&
        !name.startsWith('-') &&
        name.none { it == '\'' || it == '\n' || it == '/' }

/**
 * The directory [dataClass] lives in for [packageName] under [userId], or null when any input is
 * unsafe to interpolate.
 *
 * [externalStorageDir] is only consulted by the two external classes, so a device that cannot
 * resolve it still backs up CE and DE.
 */
internal fun dataClassRoot(
    dataClass: DataClass,
    packageName: String,
    userId: Int,
    externalStorageDir: String,
): String? {
    if (!isUsablePackageName(packageName)) return null
    if (userId < 0) return null
    return when (dataClass) {
        DataClass.CE -> "/data/user/$userId/$packageName"
        DataClass.DE -> "/data/user_de/$userId/$packageName"
        DataClass.EXTERNAL_DATA ->
            if (isQuotableAbsolutePath(externalStorageDir)) {
                "$externalStorageDir/Android/data/$packageName"
            } else null

        DataClass.EXTERNAL_MEDIA ->
            if (isQuotableAbsolutePath(externalStorageDir)) {
                "$externalStorageDir/Android/media/$packageName"
            } else null
    }
}

/**
 * The capability probe: can the active channel read a private data directory at all?
 *
 * Run against **Thor's own** package, so it asks the question without touching the app being backed
 * up. Root passes; a shell-uid Shizuku fails; a root-started Shizuku passes; Dhizuku fails — all
 * without naming a privilege mode, which is why the refusal string this feeds must not say "requires
 * Root".
 */
internal fun capabilityProbeCommand(thorPackageName: String, userId: Int): String? {
    if (!isUsablePackageName(thorPackageName)) return null
    if (userId < 0) return null
    return "ls -1 '/data/user/$userId/$thorPackageName' >/dev/null 2>&1 && echo $THOR_OK"
}

/**
 * Believed only on a zero exit **and** the marker.
 *
 * `RootSystemGateway.execute()` folds a throw into `-1 to stackTraceToString()`, so an exit code on
 * its own can be Thor's stack trace rather than a shell verdict; and a gateway that returns 0 having
 * run nothing has not proved a capability.
 */
internal fun parseCapabilityProbe(exitCode: Int, output: String?): Boolean =
    exitCode == 0 && output?.contains(THOR_OK) == true

/**
 * `du` for the sizing UI, with an existence test in front of it.
 *
 * POSIX `-k`, never `-b`: `-b` is a GNU extension and is not safe to assume on toybox.
 */
internal fun classSizeCommand(root: String): String? {
    if (!isQuotableAbsolutePath(root)) return null
    return "if [ ! -d '$root' ]; then echo $THOR_ABSENT; else du -s -k '$root' 2>/dev/null; fi"
}

/**
 * The marker is tested **before** the exit code, and the exit code before the number.
 *
 * An absent root reported as [DataClassSize.Undetermined] puts "size unknown" beside a class that
 * holds nothing; an unreadable root reported as `Known(0)` is how a user deselects data they have.
 * Both directions are wrong, so the tri-state is decided in this order and nowhere else.
 */
internal fun parseClassSize(exitCode: Int, output: String?): DataClassSize {
    val text = output ?: return DataClassSize.Undetermined
    if (text.contains(THOR_ABSENT)) return DataClassSize.Empty
    if (exitCode != 0) return DataClassSize.Undetermined
    val kilobytes = text.lineSequence()
        .map { it.trim() }
        .lastOrNull { it.isNotEmpty() }
        ?.takeWhile { it.isDigit() }
        ?.toLongOrNull()
        ?: return DataClassSize.Undetermined
    return DataClassSize.Known(kilobytes * 1024)
}

/** `ls -A` the class root, with the same absent marker the size probe uses. */
internal fun listClassEntriesCommand(root: String): String? {
    if (!isQuotableAbsolutePath(root)) return null
    return "if [ ! -d '$root' ]; then echo $THOR_ABSENT; else ls -A '$root'; fi"
}

/** `cache`, `code_cache`, `no_backup` — volatile, and restoring them helps nothing. */
private val VOLATILE_DIRS = setOf("cache", "code_cache", "no_backup")

/**
 * What survived the filter, what was refused, and whether the root was there at all.
 *
 * [rootAbsent] and an empty [kept] both produce no member, but only the first is worth a warning.
 */
internal data class ClassEntries(
    val kept: List<String>,
    val skipped: List<ArchiveSkip>,
    val rootAbsent: Boolean,
)

/**
 * Turn one `ls -A` reply into the operands `tar` will be given.
 *
 * Filtering in Kotlin rather than with `tar --exclude` is deliberate: `--exclude` bets on toybox's
 * option surface, where this is a pure `String -> ClassEntries` function that a JVM test pins down.
 *
 * An excluded volatile directory is dropped **silently** — it was never going to be packed, and
 * three rows on every archive would bury the entries Thor actually refused. An entry Thor cannot
 * quote is recorded in [ClassEntries.skipped] and reaches the header, because a filename Thor
 * dropped is something the user is entitled to know about.
 */
internal fun filterBackupEntries(dataClass: DataClass, listing: String): ClassEntries {
    val lines = listing.lines().map { it.removeSuffix("\r") }
    if (lines.any { it.trim() == THOR_ABSENT }) {
        return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)
    }

    val kept = mutableListOf<String>()
    val skipped = mutableListOf<ArchiveSkip>()
    for (name in lines) {
        // Not trimmed: a trailing space is part of a real filename, and trimming it would hand tar
        // an operand that does not exist. Only a wholly blank line is dropped.
        if (name.isBlank()) continue
        if (name == "." || name == "..") continue
        if (dataClass.excludesVolatileDirs && name in VOLATILE_DIRS) continue
        if (!isQuotableEntryName(name)) {
            skipped += ArchiveSkip(
                dataClass = dataClass.id,
                name = name,
                reason = "name cannot be passed to the shell safely",
            )
            continue
        }
        kept += name
    }
    // Sorted so two runs over the same directory produce the same command, which is what makes a
    // failure reproducible.
    return ClassEntries(kept = kept.sorted(), skipped = skipped, rootAbsent = false)
}

/**
 * `tar` the survivors of [filterBackupEntries] into [outPath].
 *
 * `-C root` plus bare operands, so the archive holds paths relative to the class root and restore
 * can extract it anywhere. Refuses an empty [entries]: an empty class produces no member at all
 * rather than an empty tar the restore side would have to special-case.
 */
internal fun tarCreateCommand(
    root: String,
    outPath: String,
    entries: List<String>,
    compress: Boolean,
): String? {
    if (!isQuotableAbsolutePath(root)) return null
    if (!isQuotableAbsolutePath(outPath)) return null
    if (entries.isEmpty()) return null
    if (entries.any { !isQuotableEntryName(it) }) return null
    val flags = if (compress) "-czf" else "-cf"
    // joinToString with prefix/separator/postfix — no transform lambda, so no synthetic
    // `tarCreateCommand$lambda$N` method that would confuse the reflective sweep in the tests.
    val operands = entries.joinToString("' '", "'", "'")
    return "tar $flags '$outPath' -C '$root' $operands"
}

/**
 * Hand a shell-created file to Thor's own [uid] so Thor can read it back, and to nobody else.
 *
 * 600 rather than 644 because a staged tar is plaintext app data. Spec §7.1 stages in *internal*
 * cache for the same reason.
 */
internal fun chownFileCommand(path: String, uid: Int): String? {
    if (!isQuotableAbsolutePath(path)) return null
    if (uid < 0) return null
    return "chown $uid:$uid '$path' && chmod 600 '$path'"
}
