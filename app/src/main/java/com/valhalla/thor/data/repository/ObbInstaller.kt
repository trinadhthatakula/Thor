// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.os.Environment
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File

/** Where the install side unpacks an archive's expansions before the shell moves them into place. */
internal const val OBB_INSTALL_STAGING_DIR = "obb_in"

/**
 * Places a `.xapk`'s expansion files into `Android/obb/<pkg>/`.
 *
 * Split out of `InstallerRepositoryImpl` because it is the only code in the install path that
 * writes outside app storage, and it is easier to reason about with that boundary visible.
 *
 * Two entry points, and the order matters. [refusalReason] runs **before** anything is installed,
 * so an archive whose game data cannot be placed does not leave a half-installed game behind;
 * [place] runs after the install is confirmed, because the destination directory's ownership is
 * synthesised from the installed package and the platform is entitled to wipe
 * `Android/obb/<pkg>/` when that package is (re)installed.
 *
 * Both entry points are silent for an archive that carries no expansions: they answer from the
 * archive's own central directory and never reach the shell, so an ordinary `.xapk`, an `.apks` and
 * a plain APK install exactly as they did before this class existed.
 */
@Single
class ObbInstaller(
    private val context: Context,
    private val systemRepository: SystemRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) {

    /**
     * Why this archive must not be installed, or null when it is fine to proceed.
     *
     * Only ever non-null when the archive actually carries expansions. An ordinary `.apks` or a
     * `.xapk` without game data is never blocked by this feature, and does not cost it a shell
     * invocation either — the expansion list is read from the archive first, and an empty one
     * returns before [canListObbRoot] is asked anything.
     */
    suspend fun refusalReason(bundle: File, packageName: String): String? =
        withContext(ioDispatcher) {
            val expansions = declaredExpansions(bundle, packageName)
            if (expansions.isEmpty()) return@withContext null

            if (!canListObbRoot()) {
                return@withContext "this file carries game data, and the current access mode " +
                    "cannot reach the game data folder. Installing it would produce a game " +
                    "that starts and then fails. Switch to root or Shizuku and try again."
            }

            val externalRoot = Environment.getExternalStorageDirectory()
            if (externalRoot == null || usableBytes(externalRoot) <= 0L) {
                return@withContext "this device's shared storage is unavailable, so the game " +
                    "data in this file cannot be placed."
            }
            null
        }

    /**
     * Whether this archive carries game data at all.
     *
     * Exists so the caller can tell the two silences apart before it commits to waiting for an
     * install to finish: an archive with no expansions must cost nothing, while one that has them
     * has to be followed through to a placement or to a stated failure. Reads the central directory
     * and never the shell.
     */
    suspend fun carriesExpansions(bundle: File, packageName: String): Boolean =
        withContext(ioDispatcher) { declaredExpansions(bundle, packageName).isNotEmpty() }

    /**
     * Unpack the archive's expansions and move them into place. Call only after the package is
     * confirmed installed.
     *
     * The destination path is built from [packageName] — the package that was just installed —
     * never from the archive, and [obbMkdirCommand] re-validates it here rather than trusting that
     * `resolveExpansions` already refused an unusable one further up the chain. This is the site
     * where a package name becomes a directory a root shell creates.
     *
     * The destination directory is not cleared first. A user reinstalling a game over an existing
     * copy keeps whatever the archive does not replace; deleting the directory would throw away
     * data an already-installed game depends on to satisfy a tidiness nobody asked for.
     *
     * The staging directory is cleared on the way in as well as on the way out. `extractExpansions`
     * creates it but does not empty it, so a run killed mid-install leaves files there that are not
     * in the list this method iterates — invisible to the placement loop, and (before the leading
     * clear) still on shared storage after it.
     */
    suspend fun place(bundle: File, packageName: String): ObbPlacement = withContext(ioDispatcher) {
        val resolved = declaredExpansions(bundle, packageName)
        if (resolved.isEmpty()) return@withContext ObbPlacement.NotNeeded

        val externalRoot = Environment.getExternalStorageDirectory()?.absolutePath
            ?: return@withContext ObbPlacement.Failed("shared storage is unavailable")
        val mkdirCommand = obbMkdirCommand(externalRoot, packageName)
            ?: return@withContext ObbPlacement.Failed(
                "this app's game data folder is not a path Thor will create"
            )

        val externalCache = context.externalCacheDir
            ?: return@withContext ObbPlacement.Failed("shared storage is unavailable")
        // Distinct from the export side's obb_out/<pkg>, deliberately: sharing one directory would
        // let an install race an export's cleanup and delete bytes the other one is still reading.
        val staging = File(externalCache, "$OBB_INSTALL_STAGING_DIR/$packageName")
        if (!staging.deleteRecursively()) {
            return@withContext ObbPlacement.Failed(
                "the leftovers of an earlier attempt could not be cleared"
            )
        }

        try {
            val extracted = extractExpansions(bundle, resolved, staging)
            val totalBytes = extracted.sumOf { it.file.length() }
            // Measured after extraction, so this is the second copy — the one the shell is about to
            // write into Android/obb — being checked against what is left.
            if (totalBytes > 0 && usableBytes(File(externalRoot)) < totalBytes) {
                return@withContext ObbPlacement.Failed(
                    "there is not enough free space for the game data"
                )
            }

            val mkdir = systemRepository.executeShellCommand(mkdirCommand).getOrNull()
            if (mkdir == null || mkdir.first != 0) {
                return@withContext ObbPlacement.Failed("the game data folder could not be created")
            }

            extracted.forEach { item ->
                val command = obbPlaceCommand(
                    externalStorageDir = externalRoot,
                    packageName = packageName,
                    leaf = item.leafName,
                    sourcePath = item.file.absolutePath,
                    expectedBytes = item.file.length()
                ) ?: return@withContext ObbPlacement.Failed(
                    "the archive names a game data file Thor will not create"
                )

                val move = systemRepository.executeShellCommand(command).getOrNull()
                if (move == null || move.first != 0) {
                    return@withContext ObbPlacement.Failed(
                        "${item.leafName} could not be copied into place"
                    )
                }
            }
            ObbPlacement.Placed(extracted.size)
        } catch (e: InstallRefusedException) {
            ObbPlacement.Failed(e.message ?: "the game data in this file could not be unpacked")
        } catch (e: Exception) {
            ObbPlacement.Failed(e.message ?: "the game data in this file could not be unpacked")
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Place a bundle's expansions **without extracting them all first** (§8.4).
     *
     * Distinct from [place] rather than a flag on it: [place] is the install path PR #376 verified on
     * hardware, and a defaulted parameter changing its behaviour is exactly the shape that hides the
     * call site that matters. The follow-up row in Task 18 proposes converging the two once this one
     * has its own hardware pass.
     *
     * Restore reaches here for an app that is **already installed** — the install path already places
     * OBB itself. Skipping the bundle for an installed app would leave a game whose expansions were
     * wiped with no way to get them back.
     *
     * Two guards [place] gets for free from `extractExpansions` have to be restated here, because
     * this path hands that function one expansion at a time and both of its limits are per-call:
     *
     *  - **The entry count.** [MAX_EXPANSION_ENTRIES] is checked against the whole resolved set
     *    below. A one-element list never trips it, so without this line a manifest-free archive
     *    carrying a million `*.obb` entries would stream every one of them — a million inodes and a
     *    million privileged `cp` invocations, which is precisely what that constant exists to stop.
     *  - **The byte budget** genuinely does become per-file, and that is the correct bound here
     *    rather than a lost one: it caps the staging directory, and streaming holds one file there.
     *
     * There is no upfront free-space check, unlike [place], because the total is not known until the
     * expansions have been extracted and the whole point is not to extract them all. A volume that
     * fills mid-run surfaces as that file failing to copy — `obbPlaceCommand` verifies the written
     * size inside the same invocation, so a short write is a reported failure, not a silent one.
     *
     * It reads its expansion list through [readDeclaredExpansions] rather than [declaredExpansions],
     * because the two silences differ here in a way they do not on the install path: this is the
     * *terminal* operation for an app that is already installed, so there is no later step to fail
     * on an unreadable archive with a better message. Reporting one as "nothing to place" would end
     * a restore in "restored" and leave a game that starts and immediately crashes.
     */
    suspend fun placeStreaming(
        bundle: File,
        packageName: String,
        onFile: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): ObbPlacement = withContext(ioDispatcher) {
        val resolved = readDeclaredExpansions(bundle, packageName)
            ?: return@withContext ObbPlacement.Failed(
                "the app bundle in this archive could not be read, so its game data could not be placed"
            )
        if (resolved.isEmpty()) return@withContext ObbPlacement.NotNeeded
        if (resolved.size > MAX_EXPANSION_ENTRIES) {
            return@withContext ObbPlacement.Failed(
                "this archive lists more game data files than Thor will unpack"
            )
        }

        val externalRoot = Environment.getExternalStorageDirectory()?.absolutePath
            ?: return@withContext ObbPlacement.Failed("shared storage is unavailable")
        val mkdirCommand = obbMkdirCommand(externalRoot, packageName)
            ?: return@withContext ObbPlacement.Failed(
                "this app's game data folder is not a path Thor will create"
            )
        val externalCache = context.externalCacheDir
            ?: return@withContext ObbPlacement.Failed("shared storage is unavailable")

        val staging = File(externalCache, "$OBB_INSTALL_STAGING_DIR/$packageName")
        if (!staging.deleteRecursively()) {
            return@withContext ObbPlacement.Failed(
                "the leftovers of an earlier attempt could not be cleared"
            )
        }
        val mkdir = systemRepository.executeShellCommand(mkdirCommand).getOrNull()
        if (mkdir == null || mkdir.first != 0) {
            return@withContext ObbPlacement.Failed("the game data folder could not be created")
        }

        try {
            streamObbEntries(
                leafNames = resolved.map { it.leafName },
                staging = staging,
                onFile = onFile,
                step = object : ObbStreamStep {
                    override suspend fun extract(leafName: String, into: File): File? =
                        extractExpansions(bundle, resolved.filter { it.leafName == leafName }, into)
                            .firstOrNull()
                            ?.file

                    override suspend fun place(source: File, leafName: String): Boolean {
                        val command = obbPlaceCommand(
                            externalStorageDir = externalRoot,
                            packageName = packageName,
                            leaf = leafName,
                            sourcePath = source.absolutePath,
                            expectedBytes = source.length(),
                        ) ?: return false
                        val move = systemRepository.executeShellCommand(command).getOrNull()
                        return move != null && move.first == 0
                    }
                },
            )
        } catch (e: CancellationException) {
            // Physically before the `Exception` catch or it is dead code, and swallowing it would
            // report a cancelled restore as a game-data failure while leaving the coroutine looking
            // as though it completed.
            throw e
        } catch (e: Exception) {
            // `extractExpansions` refuses an unusable archive by throwing, and this returns an
            // `ObbPlacement` across a domain port that has a `Failed` case for exactly that. Letting
            // the throw out would hand the restore use case an `internal` data-layer exception type
            // to interpret instead of the reason string it is meant to render. [place] converts the
            // same throws for the same reason.
            ObbPlacement.Failed(e.message ?: "the game data in this file could not be unpacked")
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * The archive's expansions, already validated against [packageName]. Empty for a plain APK, and
     * **also empty for an archive that could not be read at all.**
     *
     * That conflation is deliberate on the install path, which is the only path that calls this:
     * an unreadable archive is not this class's problem to report, because the install ahead will
     * fail on it with a better message, and claiming "no expansions" here only means this feature
     * adds nothing to that failure. The restore path has no install ahead of it and so calls
     * [readDeclaredExpansions] directly — see [placeStreaming].
     */
    private fun declaredExpansions(bundle: File, packageName: String): List<ResolvedExpansion> =
        readDeclaredExpansions(bundle, packageName).orEmpty()

    /**
     * Whether the active privilege can reach `Android/obb` at all.
     *
     * Listing the parent is the capability assertion, exactly as in `obbProbeCommand`, and for the
     * same reason: it must be the *privileged surface* answering, not a `File` API that returns
     * false for reasons unrelated to privilege. Root and the Shizuku shell uid can list it and can
     * write into it; the Dhizuku device-owner app process can do neither, which is what makes an
     * OBB-carrying archive refuse under Dhizuku instead of installing a game that cannot start.
     */
    private suspend fun canListObbRoot(): Boolean {
        val root = Environment.getExternalStorageDirectory()?.absolutePath ?: return false
        if (!root.startsWith('/') || root.any { it == '\'' || it == '\n' }) return false
        val result = systemRepository
            .executeShellCommand("ls -1 '$root/Android/obb' >/dev/null 2>&1 && echo THOR_OK")
            .getOrNull() ?: return false
        return result.first == 0 && result.second?.contains("THOR_OK") == true
    }

    /**
     * Free bytes on [dir]'s volume, pessimistically.
     *
     * Its own function only to keep the lint suppression on one small declaration. `usableSpace` is
     * the deliberate choice: lint's `UsableSpace` check points at `getAllocatableBytes`, which adds
     * back the cache quota the platform *would* clear if asked, and these bytes have to be there for
     * the whole of a multi-gigabyte copy the platform is not participating in. See
     * `AppBundleBuilderImpl`'s mirror of this on the export side and `StorageStatsHelper.kt:89`.
     */
    @Suppress("UsableSpace")
    private fun usableBytes(dir: File): Long = dir.usableSpace
}

/**
 * The expansions [bundle] declares for [packageName], or **null when the archive could not be read**.
 *
 * The nullable return is the whole point: an empty list and a failed read are different answers, and
 * folding them together is how a truncated `.thorbak` reports "nothing to place" and a restore ends
 * in "restored" for a game that will crash on launch. Callers that have a later step to fail on the
 * archive can flatten it with `orEmpty()`; the terminal ones must not.
 *
 * Empty — not null — for an archive that is simply expansion-free. A plain APK is a perfectly
 * readable zip with no `manifest.json` and no `Android/obb/…` entries, and it must stay a
 * "nothing to do", not become a failure.
 *
 * One pass over the central directory, not two: `BundleZip.read` returns every entry name alongside
 * the sidecar bytes it was asked for, so calling `BundleZip.entryNames` as well would reopen and
 * reparse the archive to rebuild a list already in hand.
 */
internal fun readDeclaredExpansions(bundle: File, packageName: String): List<ResolvedExpansion>? =
    try {
        val contents = BundleZip.read(bundle, setOf("manifest.json"))
        val manifest = contents.bytes["manifest.json"]
            ?.let { parseXapkManifest(it.decodeToString()) }
        resolveExpansions(
            packageName = packageName,
            declared = manifest?.expansions.orEmpty(),
            entryNames = contents.entryNames
        )
    } catch (_: Exception) {
        null
    }

/**
 * The two device-touching halves of one streaming placement, so [streamObbEntries] can be tested
 * without a `Context`.
 *
 * Same trade `ObbPlacementTest` makes for the command builders: the decisions are hoisted out of the
 * class that needs `Environment` and `Context`, and only the decisions are tested.
 */
internal interface ObbStreamStep {

    /** Extract one entry into [into], returning the file written, or null if it did not appear. */
    suspend fun extract(leafName: String, into: File): File?

    /** Copy it into `Android/obb/<pkg>/` with the shell. False means the copy did not land. */
    suspend fun place(source: File, leafName: String): Boolean
}

/**
 * Extract → place → **delete**, one expansion at a time (§8.4).
 *
 * The delete is in a `finally` inside the loop. That single placement is what holds peak disk at one
 * expansion file: move it after the loop and a 4 GB game needs 8 GB, which is the behaviour this
 * function exists to avoid.
 *
 * The `finally` is genuinely reachable on the cancellation path here, unlike the shape
 * [openOrRelease] had to work around: both [ObbStreamStep] members are `suspend` and both really do
 * suspend (a shell round trip, a multi-gigabyte read), so the `try` has suspension points for a
 * cancellation to be delivered at. No `ensureActive()` checkpoint is needed to make the cleanup
 * reachable, and adding one would only move the delete earlier by an instant.
 *
 * Stops at the first failure. A game missing one expansion is broken, so spending the remaining
 * minutes and gigabytes to reach the same broken outcome helps nobody.
 *
 * @param onFile called before each entry with its leaf name and 1-based position. One-based because a
 *   progress line reading "0 of 2" reads as not started.
 */
internal suspend fun streamObbEntries(
    leafNames: List<String>,
    staging: File,
    step: ObbStreamStep,
    onFile: (String, Int, Int) -> Unit = { _, _, _ -> },
): ObbPlacement {
    if (leafNames.isEmpty()) return ObbPlacement.NotNeeded

    leafNames.forEachIndexed { index, leafName ->
        onFile(leafName, index + 1, leafNames.size)
        var extracted: File? = null
        try {
            extracted = step.extract(leafName, staging)
                ?: return ObbPlacement.Failed("$leafName could not be read out of the archive")
            if (!step.place(extracted, leafName)) {
                return ObbPlacement.Failed("$leafName could not be copied into place")
            }
        } finally {
            extracted?.delete()
        }
    }
    return ObbPlacement.Placed(leafNames.size)
}

/**
 * `Android/obb/<packageName>` under [externalStorageDir], or null when either half is not safe to
 * interpolate into a single-quoted shell command.
 *
 * The package name is validated **here**, at the point where it becomes a path a privileged shell
 * creates and writes into, rather than upstream in `resolveExpansions`. Both call sites already have
 * a reason to believe the name is sound — it comes from `PackageManager` or from a manifest that
 * `isUsablePackageName` has already screened — and neither of those reasons is visible from the line
 * that builds the command.
 */
internal fun obbDestinationDir(externalStorageDir: String, packageName: String): String? {
    if (!isUsablePackageName(packageName)) return null
    if (externalStorageDir.isBlank() || !externalStorageDir.startsWith('/')) return null
    // Single-quoted below, so the one character that could break out is the single quote itself;
    // a newline is refused with it because it would split the command in two.
    if (externalStorageDir.any { it == '\'' || it == '\n' }) return null
    return "$externalStorageDir/${expansionDirFor(packageName)}"
}

/**
 * `mkdir -p` for [obbDestinationDir], or null when that directory is not one Thor will create.
 *
 * The `-L` test is the point of the `&&`: `mkdir -p` succeeds silently when the path already exists
 * *as a symlink to a directory*, and every subsequent placement would then land wherever that link
 * points — with the shell's privilege, from a path the target app owns. Failing here turns that into
 * a reported install failure instead.
 */
internal fun obbMkdirCommand(externalStorageDir: String, packageName: String): String? =
    obbDestinationDir(externalStorageDir, packageName)?.let { "mkdir -p '$it' && [ ! -L '$it' ]" }

/**
 * The privileged copy that puts one staged expansion into `Android/obb/<packageName>/[leaf]`, or
 * null when any part of it is not safe to interpolate.
 *
 * The mirror image of [obbCopyCommand] on the export side, and validated the same way: the package
 * name, the leaf and both paths are each checked here rather than assumed from having passed through
 * `resolveExpansions` and `extractExpansions` first.
 *
 *  - **The destination directory is re-tested here**, not trusted from [obbMkdirCommand], which ran
 *    in a previous shell invocation. A `-L` test only examines a path's final component, so the leaf
 *    guard below says nothing about a link at `<packageName>`.
 *
 *    What this does **not** close is the race itself: the directory could be swapped between this
 *    test and the `cp` two commands later. Closing that needs `openat(O_NOFOLLOW)` per component,
 *    which is not expressible as a shell command — and a shell command is the only tool available,
 *    since the reason this code exists is that Thor's own uid cannot open these paths at all. The
 *    residual window is one shell invocation wide, against a directory whose owner would first have
 *    to be able to create a symlink on external storage at all. Recorded rather than hidden.
 *  - **The destination is unlinked first, not overwritten.** `cp -f` only unlinks when the *open*
 *    fails, so an existing `<leaf>` that is a symlink is followed — and this runs as root into a
 *    directory the target app owns, which makes it an arbitrary write, and `chmod 644` an arbitrary
 *    chmod, on whatever the link names. `rm -f` does not follow links, so it removes the link itself;
 *    on a path that does not exist it succeeds, and on a directory it fails and the install reports
 *    it. This is the write-side twin of the read-side guard in `obbCopyCommand`.
 *  - **644, not the shell's default.** The file is created by the shell's uid and read by the game's.
 *  - **[expectedBytes] is verified inside the same invocation.** `cp` can exit 0 having written
 *    short when the volume fills, and from API 30 Thor cannot stat `Android/obb/<pkg>/` itself to
 *    notice — a truncated expansion file is a game that installs and then crashes, which is the
 *    whole of GH#164. The check is written so that a shell with no usable `stat -c` passes it (empty
 *    output, not a mismatch): it must turn a silent truncation into a reported failure, never a
 *    working placement into a false one.
 */
internal fun obbPlaceCommand(
    externalStorageDir: String,
    packageName: String,
    leaf: String,
    sourcePath: String,
    expectedBytes: Long
): String? {
    val destDir = obbDestinationDir(externalStorageDir, packageName) ?: return null
    if (!isSafeObbLeafName(leaf)) return null
    if (!sourcePath.startsWith('/')) return null
    if (expectedBytes < 0L) return null
    // `leaf` is in this sum too, even though isSafeObbLeafName already rejects a quote: this command
    // runs as root, and the redundant check costs nothing next to the predicate being relaxed some
    // day by someone who did not read why it is strict.
    if ((sourcePath + leaf).any { it == '\'' || it == '\n' }) return null

    val dest = "$destDir/$leaf"
    // `obbMkdirCommand` already refused a symlinked directory, but that ran in an *earlier* shell
    // invocation and this one runs once per expansion — so the check is repeated here, inside the
    // same invocation as the `rm`/`cp`/`chmod` it protects. See the KDoc on what that does and does
    // not buy.
    return "[ ! -L '$destDir' ] && rm -f '$dest' && cp -f '$sourcePath' '$dest' && chmod 644 '$dest' && " +
        "{ S=\$(stat -c %s '$dest' 2>/dev/null); [ -z \"\$S\" ] || [ \"\$S\" = \"$expectedBytes\" ]; }"
}
