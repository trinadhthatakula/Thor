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

/**
 * The exit status [classSizeCommand] uses to say *the directory is not there*.
 *
 * **Out of band on purpose.** The marker this replaced (`THOR_ABSENT`, printed on stdout) shared a
 * channel with text the app being backed up chooses: a file called `THOR_ABSENT` in a class root made
 * the listing read as "absent" and dropped a whole storage class from the archive, and `du` echoes the
 * root path — which carries the package name — into the very text the size parser searched. An exit
 * status cannot be forged by a filename, so the collision is designed out rather than defended
 * against.
 *
 * 44 is arbitrary but not free: it has to be a value neither `du` nor the shell produces. POSIX
 * utilities report failure as 1 or 2, `126`/`127` are the shell's own "cannot execute", and `128+n` is
 * a signal death, so the free range is 3–125. Anything not 0 and not this is [DataClassSize.Undetermined].
 *
 * The command that raises it wraps the `exit` in a **subshell**. Thor's root channel is a long-lived
 * shell (Odin keeps one `su` session), so a bare `exit` would terminate the session rather than the
 * command; `( … exit 44 )` exits the subshell and hands 44 to the caller as `$?`.
 */
const val THOR_ABSENT_EXIT = 44

/**
 * Echoed *into the listing stream* when `tar` cannot list an archive Thor is about to extract.
 *
 * A guard that passes because its own input failed to be produced is worse than no guard, because the
 * doc and the tests both read as though a check happened. Folding the failure into the same stream the
 * refusal pattern greps makes a failed listing and a hostile member name take the **same** path: both
 * match, and extraction does not run.
 */
const val THOR_LIST_FAILED = "THOR_LIST_FAILED"

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

/**
 * True when [root] is safe to use as the root of a destructive command.
 *
 * Extends [isQuotableAbsolutePath] with two additional rejections that matter for commands that
 * delete or move the root's entire contents:
 *
 * - **`..` components**: a root like `/data/user/0/pkg/../../../system` passes the quotability check
 *   but `find` resolves the traversal and `rm -rf`s `/system`'s children.
 * - **`/` and bare top-level directories**: `swapStagedEntriesCommand("/")` would delete the device
 *   root filesystem.
 *
 * **The minimum segment count is 4, which is the shallowest root any caller can legitimately
 * produce.** Every shorter path names a directory whose *children* belong to the system or to other
 * apps, and every command guarded by this predicate deletes or re-owns exactly those children:
 *
 * | Path | Segments | What `swapStagedEntriesCommand` would delete |
 * |---|---|---|
 * | `/` | 0 | the device |
 * | `/data` | 1 | every data partition tree |
 * | `/data/user`, `/data/user_de`, `/data/media` | 2 | every user's entire data tree |
 * | `/data/user/0` | 3 | every app's data for that user |
 * | `/data/user/0/<pkg>` | 4 | that one app's data — the intended target |
 *
 * The two external roots are deeper still: `<externalStorageDir>/Android/data/<pkg>` is 4 segments
 * with the shortest real storage root (`/sdcard`) and 6 with `/storage/emulated/0`. So 4 refuses
 * every system tree above without refusing anything [dataClassRoot] builds.
 *
 * A count is a proxy and it is deliberately the *only* rule here: an allow-list of prefixes would
 * have to know every OEM's storage root, and `..` is already refused, so nothing can climb back out
 * of a 4-segment path into a 2-segment one.
 */
private fun isNormalisedRoot(root: String): Boolean {
    if (!isQuotableAbsolutePath(root)) return false
    val segments = root.split('/').filter { it.isNotEmpty() }
    return segments.size >= MIN_ROOT_SEGMENTS && segments.none { it == ".." }
}

/** See [isNormalisedRoot]'s table: `/data/user/0/<pkg>` is the shallowest legitimate root. */
private const val MIN_ROOT_SEGMENTS = 4

/**
 * True when [name] is safe as a quoted `tar` operand.
 *
 * Protects [tarCreateCommand]'s `entries`, [verifyEntriesCommand]'s `entries` and
 * [classSizeCommand]'s `excludedChildren` — the three places a name Thor did not choose is
 * interpolated into a command. The `'\n'` clause is **not** dead: a listing is line-split before it
 * reaches [filterBackupEntries], so a name arriving from there can never hold one, but nothing stops
 * a future caller passing a name from somewhere that is not line-split, and this is the guard that
 * would catch it. (What happens to a filename that *does* contain a newline is
 * [verifyEntriesCommand]'s subject, not this one's.)
 */
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
 *
 * `SdCardPath` suppressed: these paths name **another app's** private data directory, which a root
 * shell reads directly. `Context.getFilesDir()` and similar APIs can only name Thor's own data
 * directory and cannot express what this function returns, so the check's suggested remedy does not
 * apply here.
 */
@Suppress("SdCardPath")
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

/** Probe whether shell can read and traverse external shared storage ($externalStorageDir/Android). */
internal fun sharedDataCapabilityProbeCommand(externalStorageDir: String): String? {
    if (!isQuotableAbsolutePath(externalStorageDir)) return null
    return "ls -1 '$externalStorageDir/Android' >/dev/null 2>&1 && echo $THOR_OK"
}

/**
 * Believed only on a zero exit **and** the marker.
 *
 * `RootSystemGateway.execute()` folds a throw into `-1 to stackTraceToString()`, so an exit code on
 * its own can be Thor's stack trace rather than a shell verdict; and a gateway that returns 0 having
 * run nothing has not proved a capability.
 */
internal fun parseCapabilityProbe(exitCode: Int, output: String?): Boolean =
    exitCode == 0 && output?.lineSequence()?.any { it.trim() == THOR_OK } == true

/**
 * `du` for the sizing UI: the class root, minus every child the archive will not pack.
 *
 * **The measurement has to exclude what the archive excludes.** The same number is shown as the
 * class size *and* drives §7.4's staging-space refusal, so counting a 3 GB browser cache that
 * [filterBackupEntries] then drops refuses a backup of 20 MB of real data. [excludedChildren] is
 * therefore the caller's copy of that filter — see [measuredExclusions], which derives it from the
 * same constant the filter uses.
 *
 * Shape of the reply, and why it is in this order:
 *
 * ```
 * ( [ -d ROOT ] || exit 44 ; [ -e ROOT/cache ] && du -s -k ROOT/cache ; … ; du -s -k ROOT )
 * ```
 *
 * - The excluded children come **first** and the root **last**, so [parseClassSize] can keep reading
 *   the total off the last numeric line. That is the rule the single-line version already used, and it
 *   is what makes a shell banner printed *before* the output harmless.
 * - Each child is guarded by `[ -e ]` because `du` exits nonzero on a missing operand and a missing
 *   `no_backup` is the common case, not an error. `-e`, not `-d`: an app that made `cache` a regular
 *   file still has it dropped from the archive, so it must be dropped from the measurement too.
 * - `du` of the root is last and unguarded, so its failure is the subshell's status and the caller
 *   reads [DataClassSize.Undetermined] rather than a wrong number.
 * - The whole thing is one subshell, so [THOR_ABSENT_EXIT]'s `exit` cannot kill a long-lived root
 *   shell.
 *
 * The native-library link is **not** subtracted even though the archive drops it: `du` does not follow
 * a symlink operand, so `lib` contributes nothing to the root's total and subtracting it would be a
 * no-op at best and an under-count on a build that does follow. See [PLATFORM_ENTRIES].
 *
 * POSIX `-k`, never `-b`: `-b` is a GNU extension and is not safe to assume on toybox.
 */
internal fun classSizeCommand(root: String, excludedChildren: List<String>): String? {
    if (!isQuotableAbsolutePath(root)) return null
    if (excludedChildren.any { !isQuotableEntryName(it) }) return null
    // `buildString`, not `joinToString(transform)`: `buildString` is inline, so no synthetic
    // `classSizeCommand$lambda$N` method is emitted. One would be, with a `String` first parameter and
    // "Command" in its name — which is exactly what `AppDataCommandsTest`'s reflective sweep picks up,
    // and it then invokes the *lambda* instead of the builder and reads the fragment it returns as an
    // unguarded command. `tarCreateCommand` avoids the same trap the same way.
    val exclusions = buildString {
        for (child in excludedChildren) {
            append("[ -e '$root/$child' ] && du -s -k '$root/$child' 2>/dev/null ; ")
        }
    }
    return "( [ -d '$root' ] || exit $THOR_ABSENT_EXIT ; ${exclusions}du -s -k '$root' 2>/dev/null )"
}

/**
 * The exit status is read **before** any text, and the last number is the total.
 *
 * An absent root reported as [DataClassSize.Undetermined] puts "size unknown" beside a class that
 * holds nothing; an unreadable root reported as `Known(0)` is how a user deselects data they have.
 * Both directions are wrong, so the tri-state is decided in this order and nowhere else.
 *
 * Absence arrives as [THOR_ABSENT_EXIT] and not as a word in the output, because the output carries
 * the root path — which carries the package name — and a package name may legally contain any marker
 * text this file could invent (see `PACKAGE_NAME`, which allows `_` and uppercase).
 *
 * Every numeric line *before* the last is a child [classSizeCommand] was told to exclude, so it is
 * subtracted. A non-numeric line is ignored rather than treated as a failure: that is what keeps a
 * `su` banner from being read as a size. The result is clamped at zero — a hard-linked file counted in
 * both a child and the root would otherwise produce a negative size.
 */
internal fun parseClassSize(exitCode: Int, output: String?): DataClassSize {
    if (exitCode == THOR_ABSENT_EXIT) return DataClassSize.Empty
    if (exitCode != 0) return DataClassSize.Undetermined
    val text = output ?: return DataClassSize.Undetermined
    val numbers = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.takeWhile { char -> char.isDigit() }.toLongOrNull() }
        .toList()
    val total = numbers.lastOrNull() ?: return DataClassSize.Undetermined
    val excluded = numbers.dropLast(1).filterNotNull().sum()
    return DataClassSize.Known((total - excluded).coerceAtLeast(0L) * 1024)
}

/**
 * `ls -A` the class root.
 *
 * No marker and no `[ -d ]` test: `ls` on a directory that is not there exits nonzero, and the caller
 * already reads any nonzero exit as "absent or unreadable" — which is the same sentence its warning
 * says. Printing a marker instead put Thor's control word in the one stream that carries filenames the
 * app chose, and a file called `THOR_ABSENT` then dropped the whole class from the archive.
 *
 * `2>/dev/null` for the reason [classSizeCommand] has always carried it: `RootSystemGateway`
 * substitutes stderr for a blank stdout, so on an **empty** class root any shell chatter would be
 * handed to [filterBackupEntries] and parsed as filenames.
 */
internal fun listClassEntriesCommand(root: String): String? {
    if (!isQuotableAbsolutePath(root)) return null
    return "ls -A '$root' 2>/dev/null"
}

/** `cache`, `code_cache`, `no_backup` — volatile, and restoring them helps nothing. */
private val VOLATILE_DIRS = setOf("cache", "code_cache", "no_backup")

/**
 * Top-level entries the **platform** puts in an app's data directory that are not the app's data.
 *
 * `lib` is the symlink `installd` points at `/data/app/~~<hash>==/<pkg>-<hash>==/lib/<abi>`. Its
 * target is **absolute**, and that is what makes it a correctness bug rather than a size one:
 * `tarCreateCommand` passes no `-h`, so tar stores it as a symlink member carrying that absolute link
 * name, and [ARCHIVE_MEMBER_REFUSAL_PATTERN]'s `(^| )/` alternative then refuses the **whole class**
 * at restore. Thor was writing archives its own restore rejects, for every app that has this link —
 * and CE is the class holding the databases the feature exists for.
 *
 * Excluded rather than dereferenced: `-h` would pack the app's `.so` files, which the reinstall
 * already provides, and would restore real files where a symlink belongs. AOSP's own
 * `FullBackup.backupToTar` excludes `nativeLibraryDir` for the same reason it excludes the three in
 * [VOLATILE_DIRS].
 *
 * **Internal classes only.** `installd` creates this link under `/data/user*` only; a `lib` directory
 * under `Android/data/<pkg>` would be the app's own, and dropping it would be silent data loss.
 *
 * This closes the class *at the root of the data directory*. It does not close the general one: a
 * symlink nested inside a kept subtree whose target is absolute or contains `..` is still packed and
 * still refused at restore, in whole, for that class. Fixing that needs the backup half to read what
 * it wrote (`tar -tv` over the staged tar, through the same pattern) — recorded as a follow-up rather
 * than done here, because it is a round trip per class and a design decision about what to do when it
 * fires.
 */
private val PLATFORM_ENTRIES = setOf("lib")

/**
 * Every top-level entry [filterBackupEntries] drops for [dataClass], for a caller that needs the same
 * list — [classSizeCommand] is the one that does.
 *
 * Excludes [PLATFORM_ENTRIES] deliberately: see [classSizeCommand] for why the library link is
 * dropped from the archive but not subtracted from the measurement.
 */
internal fun measuredExclusions(dataClass: DataClass): List<String> =
    if (dataClass.excludesVolatileDirs) VOLATILE_DIRS.sorted() else emptyList()

/**
 * What survived the filter, what was refused, and whether the root was there at all.
 *
 * [rootAbsent] and an empty [kept] both produce no member, but only the first is worth a warning.
 *
 * [filterBackupEntries] never sets [rootAbsent]: absence is the listing command's **exit status**, not
 * a word in its output, so only the caller that has the status can decide it.
 */
internal data class ClassEntries(
    val kept: List<String>,
    val skipped: List<ArchiveSkip>,
    val rootAbsent: Boolean,
)

/** Why an entry the shell listed is not in the archive. Pinned as constants so tests can name them. */
internal const val SKIP_UNQUOTABLE = "name cannot be passed to the shell safely"

/** See [filterBackupEntries]: Thor's own staging directory, left behind by an interrupted restore. */
internal const val SKIP_STAGING_DIR =
    "left by an interrupted restore of this app; it is Thor's staging directory, not app data"

/** See [applyEntryVerification]. */
internal const val SKIP_LISTED_BUT_ABSENT =
    "listed by the shell but not present when the archive was written — a name containing a line " +
        "break appears this way, as does a file the app deleted mid-backup"

/**
 * Turn one `ls -A` reply into the operands `tar` will be given.
 *
 * Filtering in Kotlin rather than with `tar --exclude` is deliberate: `--exclude` bets on toybox's
 * option surface, where this is a pure `String -> ClassEntries` function that a JVM test pins down.
 *
 * **What is dropped silently, and what is recorded.** A volatile directory ([VOLATILE_DIRS]) and the
 * platform's native-library link ([PLATFORM_ENTRIES]) are dropped without a row: they are on every
 * app, they were never going to be packed, and rows for them on every archive would bury the entries
 * Thor actually refused. Everything else that is dropped gets an [ArchiveSkip] and reaches the header,
 * because a filename Thor dropped is something the user is entitled to know about — including the
 * staging directory, which is *not* routine: it exists only after a restore died, and it can hold the
 * only copy of the half that was never swapped in.
 *
 * **What this function cannot see.** The reply is line-split before it arrives, so a filename
 * containing a line break has already become two lines and reaches here as two plausible names. The
 * `'\n'` clause in [isQuotableEntryName] therefore never fires for that name, and no rule written here
 * could make it fire. That case is caught after the fact by [verifyEntriesCommand] and
 * [applyEntryVerification]; this KDoc says so rather than claiming a completeness the split destroyed.
 */
internal fun filterBackupEntries(dataClass: DataClass, listing: String): ClassEntries {
    val lines = listing.lines().map { it.removeSuffix("\r") }
    val kept = mutableListOf<String>()
    val skipped = mutableListOf<ArchiveSkip>()
    for (name in lines) {
        // Not trimmed: a trailing space is part of a real filename, and trimming it would hand tar
        // an operand that does not exist. Only a wholly blank line is dropped.
        if (name.isBlank()) continue
        if (name == "." || name == "..") continue
        // Thor's own restore staging directory, never app content. It exists only when a restore died
        // between extracting and swapping, and packing it would produce an archive that
        // ARCHIVE_MEMBER_REFUSAL_PATTERN refuses **in whole** — one interrupted restore would make
        // every later backup of that app permanently unrestorable. Unconditional, unlike VOLATILE_DIRS:
        // this is not a per-class choice. It is *recorded*, unlike the routine exclusions, because at
        // this moment it holds data the class root does not: the entries the dead restore extracted but
        // never swapped in. An archive that omits them without saying so reads as a complete backup.
        if (name == STAGING_DIR_NAME) {
            skipped += ArchiveSkip(dataClass = dataClass.id, name = name, reason = SKIP_STAGING_DIR)
            continue
        }
        if (dataClass.excludesVolatileDirs && name in VOLATILE_DIRS) continue
        if (dataClass.isInternal && name in PLATFORM_ENTRIES) continue
        if (!isQuotableEntryName(name)) {
            skipped += ArchiveSkip(
                dataClass = dataClass.id,
                name = name,
                reason = SKIP_UNQUOTABLE,
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
 * Ask the shell which of [entries] is actually there, so a name that only *looks* like a file is not
 * handed to `tar`.
 *
 * The case this exists for: `ls -A`'s reply is line-split, so a file called `"report\n2024.pdf"`
 * arrives as `report` and `2024.pdf`. Both pass every filter, neither exists, and `tar` is then given
 * two operands it cannot find. Without this step the outcome is a backup that is *missing a file* and
 * says nothing, reported through `tar`'s exit 1 as "files that changed while being read" — a cause
 * that is not the cause.
 *
 * Shape: `[ -e 'R/n' ] || [ -L 'R/n' ] || echo 'n' ; …`, one clause per entry, so the reply names only
 * the entries that are missing. `-L` as well as `-e` because `-e` is false for a **dangling** symlink,
 * which is a real member `tar` can and should pack.
 *
 * Returns null for an empty [entries] — there is nothing to verify, and the caller has nothing to pack
 * either.
 */
internal fun verifyEntriesCommand(root: String, entries: List<String>): String? {
    if (!isQuotableAbsolutePath(root)) return null
    if (entries.isEmpty()) return null
    if (entries.any { !isQuotableEntryName(it) }) return null
    // Inline `buildString` rather than a `joinToString` transform, for the reason spelled out in
    // [classSizeCommand]: a non-inline lambda here becomes a synthetic method the reflective sweep
    // mistakes for a command builder.
    return buildString {
        for (name in entries) {
            if (isNotEmpty()) append(" ; ")
            append("[ -e '$root/$name' ] || [ -L '$root/$name' ] || echo '$name'")
        }
    }
}

/**
 * Fold [verifyEntriesCommand]'s reply back into [listing].
 *
 * **Fails open.** A non-zero [exitCode] or a null [output] returns [listing] untouched: the
 * verification is a *refinement*, and a channel that could not answer must not be able to empty an
 * archive. The only thing lost in that case is the row.
 *
 * Only names already in [ClassEntries.kept] are removed. The reply arrives on the same stdout as any
 * shell chatter, and `RootSystemGateway` substitutes stderr for a blank stdout, so an unfiltered read
 * would let a banner line delete an entry from the backup. Intersecting with what was sent makes the
 * worst case a no-op.
 */
internal fun applyEntryVerification(
    dataClass: DataClass,
    listing: ClassEntries,
    exitCode: Int,
    output: String?,
): ClassEntries {
    if (exitCode != 0) return listing
    val text = output ?: return listing
    val reported = text.lines().map { it.removeSuffix("\r") }.toSet()
    val missing = listing.kept.filter { it in reported }
    if (missing.isEmpty()) return listing
    return listing.copy(
        kept = listing.kept - missing.toSet(),
        skipped = listing.skipped + missing.map { name ->
            ArchiveSkip(dataClass = dataClass.id, name = name, reason = SKIP_LISTED_BUT_ABSENT)
        },
    )
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

/**
 * The directory a restore extracts into, inside the class root it is replacing.
 *
 * Inside the class root, not in cache, so the promotion in [swapStagedEntriesCommand] is a series of
 * same-filesystem renames rather than a second full copy. Hidden, so a user who looks at the directory
 * mid-restore does not see it as app content.
 *
 * **This literal appears in two commands** — the extract creates it, the swap excludes it from the
 * deletion. If the two ever disagree, the swap deletes the staged data after the original is already
 * gone. `AppDataRestoreCommandsTest` pins them to each other for that reason.
 */
const val STAGING_DIR_NAME = ".thorbak-staging"

internal fun stagingDirPath(root: String): String? {
    if (!isNormalisedRoot(root)) return null
    return "$root/$STAGING_DIR_NAME"
}

private val ERE_METACHARACTERS = "\\^\$.[]|()*+?{}".toSet()

/**
 * [STAGING_DIR_NAME] escaped for use inside an ERE.
 *
 * Escapes **every** ERE metacharacter, not just the `.` today's name happens to contain. A targeted
 * `.replace(".", "\\.")` reads as sufficient and silently under-escapes the moment the constant gains
 * another metacharacter — and the natural repair for the resulting red test is to paste the new literal
 * into the expected string, which restores green over a pattern that no longer means what it says.
 */
private val STAGING_NAME_ERE = STAGING_DIR_NAME
    .map { if (it in ERE_METACHARACTERS) "\\$it" else "$it" }
    .joinToString("")

/**
 * One ERE that refuses a `tar -tv` line naming a member Thor will not extract.
 *
 * Four alternatives:
 *
 * 1. **[THOR_LIST_FAILED]** — the sentinel [extractCommand] echoes when `tar` cannot list. Matching it
 *    here is what turns a failed listing into a refusal instead of a pass.
 * 2. **`(^| )/`** — an absolute member name, or a link target that is absolute.
 *
 *    A `-tv` line's fixed fields normally hold no space before a slash (`root/root`, the size, the ISO
 *    date), but that is **not** a soundness argument and must not be written as one: `uname` and
 *    `gname` are 32-byte fields in the tar header, so an attacker chooses them and can put `" /"` in
 *    the owner column. What makes the pattern sound is the *direction*: matching is per line and every
 *    alternative only ever **adds** a match, so a crafted field can cause an extra refusal and can
 *    never suppress a real one. Nothing in a `-tv` line can mask a hostile name.
 * 3. **`(^| |/)\.\.(/|$| )`** — `..` as a whole path component, wherever it appears: bare `..`,
 *    `../x`, `x/..`, `x/../y`, and the same four as a link target. Anchoring on the component
 *    boundary rather than on hand-written slash cases is what makes a member named exactly `..`
 *    match; `a..b` and `...` are legal names and do not.
 * 4. **the staging directory's own name as a component** — [swapStagedEntriesCommand] excludes exactly
 *    that name from its deletion, so an archive carrying a member by that name would wipe the class
 *    root and then fail the `mv` onto itself: a destructive no-op leaving the app with nothing. The
 *    two guards have to agree about the name, and this is the extraction half of that agreement.
 *
 * Alternatives 2 and 3 cover link **targets** as well as member names, which is why there is no
 * separate `-> ` alternative: an absolute target is preceded by a space, and a `..` target is either
 * preceded by a space or contains `/../`.
 *
 * A literal space rather than `[[:space:]]`: GNU, toybox and busybox all separate `-tv` fields with
 * spaces, and a literal space means the same thing to POSIX ERE and to `java.util.regex` — so
 * `AppDataRestoreCommandsTest` can pin this exact string's *behaviour* rather than only its text.
 *
 * The pattern over-refuses for a filename containing a space immediately followed by `/` or `..`
 * (`"My Dir /f"`). That direction is the safe one, and such a name is far rarer than the symlink this
 * exists to stop.
 */
internal val ARCHIVE_MEMBER_REFUSAL_PATTERN: String =
    "$THOR_LIST_FAILED|(^| )/|(^| |/)\\.\\.(/|\$| )|(^| |/)$STAGING_NAME_ERE(/|\$| )"

/**
 * Extract a decrypted tar into the staging directory under [root].
 *
 * @param compressed must match the member's recorded `compression`. Guessing would either fail on a
 *   plain tar or, worse, succeed partially.
 *
 * Four safeguards, in order:
 *
 * 1. **`rm -rf` before `mkdir -p`.** `mkdir -p` exits 0 on an existing directory, so without the
 *    removal, debris from a restore that died between extraction and the swap survives — and
 *    [swapStagedEntriesCommand] then moves *that* into the class root alongside this archive's
 *    contents, silently mixing two backups into one app. It also means the tree being extracted into
 *    is one this command created, rather than one an interrupted run left behind with whatever the
 *    previous archive planted in it. On a symlinked staging path `rm -rf` removes the link, not its
 *    target.
 * 2. The `-L` test is still not redundant, but be exact about which window it closes: it is the
 *    `rm`→`mkdir` one. `mkdir -p` exits 0 on a symlink to a directory, so if anything recreates the
 *    link between the removal and the `mkdir`, this test catches it and the chain stops. It does
 *    **not** close the `mkdir`→extraction window — a race that recreates the path there is not
 *    detected, and if what lands is a real directory rather than a symlink its contents are promoted
 *    by [swapStagedEntriesCommand]. Winning that race needs write access to Thor's own data
 *    directory, which is the assumption every other guard here already rests on.
 * 3. **The listing must be producible.** `tar -tv` is listed with the *same* compression the
 *    extraction will use — never `-tf` against a gzipped archive on the hope that the implementation
 *    auto-detects — and `|| echo $THOR_LIST_FAILED` injects a sentinel line if it does not succeed.
 * 4. **[ARCHIVE_MEMBER_REFUSAL_PATTERN] over that listing, and the extraction runs only on grep's
 *    exit 1.** `( … | grep -qE … ; [ $? -eq 1 ] )` is deliberate and the `!` it replaced was a bug of
 *    its own: `!` inverts *every* non-zero status, so `grep` exiting **2** (a pattern it cannot
 *    compile) or **127** (no grep on the device) read as "no bad member" and extraction ran with no
 *    member check at all. Exit 1 — and only exit 1 — means grep read the whole listing and found
 *    nothing. The `;` inside the subshell does not break the outer `&&` chain, because a subshell's
 *    status is its last command's.
 *
 * What `-C '$staging'` is **not**: a containment guarantee. It bounds where relative member names
 * land, and it does nothing at all about a symlink member whose target escapes the tree — the next
 * member written *through* that link lands wherever the link points, with root's privilege. That is
 * why the refusal pattern reads link targets, and why the rule is targeted rather than blanket: a
 * symlink or hardlink is refused when its target is absolute or contains `..`, and a relative,
 * containment-safe target is allowed. Thor's own backup half tars whatever the app had, so refusing
 * every symlink would leave Thor unable to restore its own archives.
 *
 * **Inherited, not established here:** the listing and the extraction are two independent reads of
 * [tarPath], so the guard binds the second read only while nothing can substitute that file in
 * between. That holds because the caller stages the decrypted tar in Thor's own internal cache; a
 * caller that ever points this at a path another process can write makes the check advisory.
 */
internal fun extractCommand(root: String, tarPath: String, compressed: Boolean): String? {
    val staging = stagingDirPath(root) ?: return null
    if (!isQuotableAbsolutePath(tarPath)) return null
    val listFlags = if (compressed) "-tvzf" else "-tvf"
    val extractFlags = if (compressed) "-xzf" else "-xf"
    return "rm -rf '$staging' && mkdir -p '$staging' && [ ! -L '$staging' ] && " +
        "( ( tar $listFlags '$tarPath' || echo $THOR_LIST_FAILED ) | " +
        "grep -qE '$ARCHIVE_MEMBER_REFUSAL_PATTERN' ; [ \$? -eq 1 ] ) && " +
        "tar $extractFlags '$tarPath' -C '$staging'"
}

/**
 * Replace the class root's contents with the staged extraction, then remove the staging directory.
 *
 * Four properties, each of which has a test:
 *
 * - **Staging must be non-empty.** The guard `[ -n "$(ls -A '$staging')" ]` runs first. An empty
 *   staging — caused by an empty tar, a tar of only excluded entries, or a `tar` that exits 0 having
 *   written nothing — makes `ls -A` produce no output, `[ -n "" ]` evaluates false, and the `&&`
 *   chain short-circuits before any data is deleted. Without this guard, an attacker-chosen empty
 *   archive destroys the app's live data, exits 0, and reports success.
 * - The deletion **excludes [STAGING_DIR_NAME]**. A `rm -rf <root>/\*` would delete the very directory
 *   holding the data being restored, after the original is already gone.
 * - Both halves use `find`, not a glob. A shell glob does not match dotfiles, and app data is full of
 *   them; `mv <staging>/\*` silently leaves every dot entry behind.
 * - `-mindepth 1 -maxdepth 1` so the walk is one level: the entries, not their contents, and not the
 *   root itself.
 *
 * `-exec … +` for the delete (one `rm` for many paths) and `-exec … \;` for the move (`mv` needs its
 * destination last). Both forms are on the toybox checklist for exactly this reason.
 */
internal fun swapStagedEntriesCommand(root: String): String? {
    val staging = stagingDirPath(root) ?: return null
    return "[ -n \"\$(ls -A '$staging')\" ] && " +
        "find '$root' -mindepth 1 -maxdepth 1 ! -name '$STAGING_DIR_NAME' -exec rm -rf {} + && " +
        "find '$staging' -mindepth 1 -maxdepth 1 -exec mv -f {} '$root/' \\; && " +
        "rmdir '$staging'"
}

/**
 * Give the whole class root to the app's **live Linux uid**.
 *
 * A reinstalled app has a *new* uid, so the numeric owners inside the archive are always wrong; the
 * caller reads this from `PackageManager` **after** the install lands (§8.2). Not called for the two
 * external classes: `Android/data` on FUSE has synthesized ownership and `chown` there is meaningless.
 *
 * `-h` (`--no-dereference`) is required: without it, toybox's `chown` follows a symlink and chowns
 * the *target*, so a symlink planted inside the extracted tree hands another app's data directory to
 * the restored uid. With `-h`, the symlink itself is re-owned and the target is untouched.
 */
internal fun chownRecursiveCommand(root: String, uid: Int): String? {
    if (!isNormalisedRoot(root)) return null
    // A negative uid is what `appUid()`'s null becomes if a caller coerces it. `chown -Rh -1:-1` is
    // parsed as an option by some toybox builds.
    if (uid < 0) return null
    return "chown -Rh $uid:$uid '$root'"
}

/**
 * Relabel the restored tree for SELinux.
 *
 * `-F` as well as `-R`: without the force, a file that already carries a context keeps whatever it was
 * extracted with, and the app still cannot read it. Skipping this step is the most common reason a
 * restore reports success and the app crashes on launch — which is precisely the failure mode this
 * feature exists to avoid.
 */
internal fun restoreconCommand(root: String): String? {
    if (!isNormalisedRoot(root)) return null
    return "restorecon -RF '$root'"
}
