// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.os.Environment
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File

/** Where the install side unpacks an archive's expansions before the shell moves them into place. */
internal const val OBB_INSTALL_STAGING_DIR = "obb_in"

/** What happened to an archive's game data. */
sealed interface ObbPlacement {

    /** The archive declared no expansions, so there was nothing to do. */
    data object NotNeeded : ObbPlacement

    /** [count] expansion files are now in `Android/obb/<pkg>/`. */
    data class Placed(val count: Int) : ObbPlacement

    /**
     * The app installed but its game data did not land.
     *
     * Reported to the user rather than swallowed: an installed game that crashes on first launch
     * with no explanation is the failure mode GH#164 describes, and silence here would reproduce
     * it from the other direction.
     */
    data class Failed(val reason: String) : ObbPlacement
}

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
     * The archive's expansions, already validated against [packageName]. Empty for a plain APK.
     *
     * One pass over the central directory, not two: `BundleZip.read` returns every entry name
     * alongside the sidecar bytes it was asked for, so calling `BundleZip.entryNames` as well would
     * reopen and reparse the archive to rebuild a list already in hand.
     */
    private fun declaredExpansions(bundle: File, packageName: String): List<ResolvedExpansion> =
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
            // An unreadable archive is not this class's problem to report — the install path
            // ahead will fail on it with a better message. Claiming "no expansions" here only
            // means this feature adds nothing to that failure.
            emptyList()
        }

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
    return "rm -f '$dest' && cp -f '$sourcePath' '$dest' && chmod 644 '$dest' && " +
        "{ S=\$(stat -c %s '$dest' 2>/dev/null); [ -z \"\$S\" ] || [ \"\$S\" = \"$expectedBytes\" ]; }"
}
