// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

/** The external-storage-relative directory the platform reserves for one package's expansions. */
internal fun expansionDirFor(packageName: String): String = "Android/obb/$packageName"

/**
 * Where an expansion file lives, both as a zip entry and as an install path.
 *
 * Thor writes the two equal. The reference installers assume that, and it means an archive Thor
 * produced is readable by an installer that ignores the manifest entirely and just looks for
 * `*.obb` entries.
 */
internal fun expansionEntryName(packageName: String, leaf: String): String =
    "${expansionDirFor(packageName)}/$leaf"

/**
 * `NAME_MAX`, which is 255 on every filesystem an OBB can be written to.
 *
 * Counted in characters rather than bytes, which makes the bound *looser* than the kernel's for a
 * non-ASCII name and is the right direction: this rule exists to stop an absurd name, not to
 * second-guess what a real volume accepted.
 */
private const val MAX_OBB_LEAF_NAME_CHARS = 255

/**
 * A file name Thor is willing to create inside `Android/obb/<pkg>/`.
 *
 * Stricter than [isSafeEntryFileName] by four rules, each paying for itself:
 *
 *  - **At most [MAX_OBB_LEAF_NAME_CHARS] characters**, so this rejects nothing that could ever have
 *    been read off a device — a name this long can only have come from a hand-written manifest,
 *    since `cp` answers `ENAMETOOLONG` before it creates one. It is here because the leaf is the
 *    one attacker-controlled string that reaches a user-visible *message*: a placement failure
 *    becomes `"<leaf> could not be copied into place"`, that becomes a restore warning, and warnings
 *    are handed to WorkManager as `Data`, which throws above 10 KB. A 10 KB leaf therefore turned a
 *    restore that had already succeeded into one reported as failed — and telling the user a restore
 *    failed sends them to run it again, over data that is already correct.
 *  - **The `.obb` extension**, because that is what the platform's own expansion loader looks for,
 *    and because it keeps a hostile archive from dropping an arbitrarily-typed file into a
 *    world-readable directory.
 *  - **No control characters.** A NUL truncates the name at the syscall boundary, so what the
 *    filesystem creates stops matching what was validated here.
 *  - **No single quote.** This is the only rule that is not about the filesystem, and it is the
 *    reason this predicate has to be the *shared* definition rather than a per-direction one: both
 *    directions interpolate the leaf into a single-quoted shell command run at root or shell uid —
 *    `cp -f '<externalStorageDir>/Android/obb/<pkg>/<leaf>' …` when packing, and
 *    `… '<destDir>/<leaf>'` when installing. A `'` in the name closes the quote and the rest of the
 *    name becomes a command. Nothing else can break out of single quotes, which is why the list
 *    stops here and why an interior *space* stays legal — `main 1.obb` is a real file name, and the
 *    probe splits on the first space only so that it survives.
 *
 * Note what this predicate is *not* asked to do: it never sees a directory component, because
 * [resolveExpansions] strips the `Android/obb/<pkg>/` prefix first and rejects anything with a
 * separator left in it.
 */
internal fun isSafeObbLeafName(name: String): Boolean =
    name.isNotBlank() &&
        name.length <= MAX_OBB_LEAF_NAME_CHARS &&
        name != "." &&
        name != ".." &&
        !name.contains('/') &&
        !name.contains('\\') &&
        !name.contains('\'') &&
        name.none { it.isISOControl() } &&
        name.endsWith(".obb", ignoreCase = true)

/** One expansion cleared for extraction: which zip entry to read, and what to name the result. */
internal data class ResolvedExpansion(val entryName: String, val leafName: String)

/**
 * Decide which expansions in an archive are safe to extract and place.
 *
 * Both inputs are attacker-controlled — the manifest and the entry list come from a file the user
 * picked, possibly downloaded from anywhere. The rules, in order:
 *
 *  1. An unusable package name resolves nothing. The name is interpolated into a shell command
 *     downstream, and it is also the authority for rule 2.
 *  2. `install_path` must be exactly `Android/obb/<packageName>/<leaf>` — same package, depth 1,
 *     no traversal, not absolute. A `.xapk` may only write into its own package's OBB directory.
 *     The prefix test is a plain `startsWith`, so it is deliberately case- and shape-exact: nothing
 *     here normalises, because a normaliser is one more thing that can disagree with the kernel.
 *  3. `<leaf>` must satisfy [isSafeObbLeafName].
 *  4. The declared `file` must actually be an entry in the archive. A declaration with no entry is
 *     dropped rather than treated as an error, so a manifest listing an optional patch file that
 *     was not shipped still installs.
 *  5. Two declarations resolving to the same leaf keep the first. Later ones would silently
 *     overwrite the earlier extraction. **The comparison is case-insensitive**, because the volumes
 *     this feature writes to are not case-sensitive — emulated external storage, and any FAT or
 *     exFAT card — so `main.obb` and `MAIN.OBB` name one file there and a case-sensitive dedup
 *     would wave the second one through to do exactly the overwrite this rule exists to stop.
 *  6. **Manifest-free fallback:** when nothing is declared, any entry already at
 *     `Android/obb/<packageName>/<leaf>.obb` is taken at its own path. APKPure archives in the
 *     wild omit the block while carrying the files, and the reference installer does exactly this.
 *     The fallback applies only when `declared` is empty — a manifest that declares *some*
 *     expansions is treated as authoritative about all of them, so an archive cannot buy itself a
 *     permissive scan by declaring one entry that gets rejected.
 *
 * Only `leafName` is ever used to build a path. `entryName` is used only to look an entry up inside
 * the archive, which is why rule 4 (it must already be in the archive) is the whole of its
 * validation — a traversal there resolves to nothing and reads nothing.
 */
internal fun resolveExpansions(
    packageName: String,
    declared: List<XapkExpansionInfo>,
    entryNames: List<String>
): List<ResolvedExpansion> {
    if (!isUsablePackageName(packageName)) return emptyList()

    val prefix = "${expansionDirFor(packageName)}/"
    val present = entryNames.toSet()
    // Lowercased keys, per rule 5. `lowercase()` with no argument is locale-invariant; the
    // locale-sensitive overload would make a Turkish device resolve a different set of files.
    val claimedLeaves = HashSet<String>()
    val out = mutableListOf<ResolvedExpansion>()

    fun accept(entryName: String, installPath: String) {
        if (!installPath.startsWith(prefix)) return
        val leaf = installPath.removePrefix(prefix)
        if (!isSafeObbLeafName(leaf)) return
        if (entryName !in present) return
        if (!claimedLeaves.add(leaf.lowercase())) return
        out += ResolvedExpansion(entryName, leaf)
    }

    if (declared.isEmpty()) {
        entryNames.forEach { name -> accept(name, name) }
    } else {
        declared.forEach { info ->
            val entryName = info.file ?: return@forEach
            val installPath = info.installPath ?: return@forEach
            accept(entryName, installPath)
        }
    }
    return out
}

/**
 * Bytes a bundle build needs, split by the volume they land on.
 *
 * [internalBytes] is the peak on Thor's own cache directory: the APKs are copied out of `/data/app`
 * into a staging directory and then written into the final zip, so both copies coexist — plus one
 * copy of the OBB, which ends up inside that same zip.
 *
 * [externalBytes] is the peak in `externalCacheDir`, the only place Thor and the privileged shell
 * can both reach. Thor cannot open `Android/obb/<other-pkg>/` itself, so the shell copies each
 * expansion there first and Thor streams it into the zip from there.
 */
internal data class BundleSpace(val internalBytes: Long, val externalBytes: Long)

/** @see BundleSpace for why the APKs count twice and the OBB counts once per volume. */
internal fun bundleSpaceRequirement(apkBytes: Long, obbBytes: Long): BundleSpace =
    BundleSpace(internalBytes = 2 * apkBytes + obbBytes, externalBytes = obbBytes)

/**
 * How many bytes short the device is, or 0 when there is room.
 *
 * [sameVolume] must be true when internal and external storage are the same filesystem, which is
 * the normal case on a phone with no SD card: external storage is emulated on the data partition,
 * so the two free-space figures are the same bytes counted twice and the requirements add rather
 * than overlap. Checking them independently on such a device passes a build that then dies
 * halfway through the copy. Callers derive it by comparing `totalSpace` on the two directories.
 */
internal fun spaceShortfall(
    need: BundleSpace,
    internalFree: Long,
    externalFree: Long,
    sameVolume: Boolean
): Long = if (sameVolume) {
    (need.internalBytes + need.externalBytes - internalFree).coerceAtLeast(0L)
} else {
    maxOf(
        (need.internalBytes - internalFree).coerceAtLeast(0L),
        (need.externalBytes - externalFree).coerceAtLeast(0L)
    )
}
