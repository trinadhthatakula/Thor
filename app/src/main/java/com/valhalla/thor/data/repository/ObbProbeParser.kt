// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.ObbFile
import com.valhalla.thor.domain.model.ObbProbe

// These five are `internal` rather than `private` so a test fixture that synthesises probe output
// references the same constant the parser reads. A fixture that spells the literal out again can go
// stale against the parser and still pass, which is how a wrong rule hides behind a green test.

/** Emitted when the parent `Android/obb` cannot be listed — i.e. this privilege cannot see it. */
internal const val SENTINEL_NOPRIV = "THOR_NOPRIV"

/** Emitted when the parent listed fine but the package has no OBB directory. */
internal const val SENTINEL_NODIR = "THOR_NODIR"

/**
 * Prefix of a size+path line for one `*.obb` file.
 *
 * The shell emits this prefix only for a `*.obb` glob match, so a line carrying it is proof an
 * expansion file exists. That is why a *malformed* one is fatal — see [parseObbProbe].
 */
internal const val PREFIX_OBB = "THOR_OBB "

/** Prefix of the count of directory entries that are not depth-1 `*.obb` files. */
internal const val PREFIX_OTHER = "THOR_OTHER "

/**
 * Proof the script ran to completion.
 *
 * Output without it is [ObbProbe.Undetermined], never [ObbProbe.None]. A truncated reply and an
 * empty directory look identical otherwise, and one of those two readings silently drops a game's
 * data out of the bundle.
 */
internal const val SENTINEL_END = "THOR_END"

/**
 * The ordinary package-name shape. Deliberately stricter than the platform: this string is
 * interpolated into a shell command, and a validator is cheaper than trusting every caller to
 * have got it from `PackageManager`.
 */
private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")

/**
 * The probe command, or null when [packageName] or [externalStorageDir] is not safe to interpolate.
 *
 * Every line is load-bearing:
 *  - **Listing the parent is the capability assertion.** Root and the Shizuku shell uid succeed;
 *    the Dhizuku device-owner app process does not. This is what separates `None` from
 *    `Undetermined`, and nothing else in the output can.
 *  - **`stat -c 'THOR_OBB %s %n'` puts the size first**, so a name containing spaces parses by
 *    splitting on the *first* space only.
 *  - **The prefixes are checked, not assumed.** A name containing a newline splits across lines;
 *    the tail then fails the prefix test and the head fails the `.obb` extension test.
 *  - **[SENTINEL_END] is printed last** so a truncated reply is detectable.
 *
 * [externalStorageDir] comes from `Environment.getExternalStorageDirectory().absolutePath` rather
 * than a hardcoded `/storage/emulated/0` — but Thor runs against a single `thorUserId` (see
 * `data/source/local/thorUserId`; `am get-current-user` is denied without `INTERACT_ACROSS_USERS`),
 * so there is no multi-user path to juggle.
 */
internal fun obbProbeCommand(externalStorageDir: String, packageName: String): String? {
    if (!isUsablePackageName(packageName)) return null
    if (externalStorageDir.isBlank() || !externalStorageDir.startsWith('/')) return null
    // Single-quoted below, so the one character that could break out is the single quote itself.
    if (externalStorageDir.any { it == '\'' || it == '\n' }) return null

    val parent = "$externalStorageDir/Android/obb"
    val dir = "$parent/$packageName"
    return buildString {
        append("ls -1 '").append(parent).append("' >/dev/null 2>&1 || { echo ")
        append(SENTINEL_NOPRIV).append("; exit 0; }\n")
        append("[ -d '").append(dir).append("' ] || { echo ")
        append(SENTINEL_NODIR).append("; exit 0; }\n")
        append("n=0\n")
        append("for f in '").append(dir).append("'/*; do\n")
        append("  if [ -f \"\$f\" ]; then\n")
        append("    case \"\$f\" in\n")
        append("      *.obb) stat -c 'THOR_OBB %s %n' \"\$f\" ;;\n")
        append("      *) n=\$((n+1)) ;;\n")
        append("    esac\n")
        append("  elif [ -e \"\$f\" ]; then\n")
        append("    n=\$((n+1))\n")
        append("  fi\n")
        append("done\n")
        append("echo \"THOR_OTHER \$n\"\n")
        append("echo ").append(SENTINEL_END).append("\n")
    }
}

/** True when [value] is safe to interpolate into a shell command as a package name. */
internal fun isUsablePackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

/**
 * Turn one probe run into a verdict.
 *
 * The order of the checks is the contract. `THOR_NOPRIV` and `THOR_NODIR` are both tested before
 * the end sentinel because both of those branches `exit 0` without ever reaching the `echo
 * THOR_END` at the bottom of the script. Only the listing path prints the sentinel, so only the
 * listing path may require it.
 */
internal fun parseObbProbe(exitCode: Int, output: String?): ObbProbe {
    if (exitCode != 0) {
        return ObbProbe.Undetermined("the privileged shell exited with code $exitCode")
    }
    val text = output?.takeIf { it.isNotBlank() }
        ?: return ObbProbe.Undetermined("the privileged shell returned no output")

    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    if (lines.any { it == SENTINEL_NOPRIV }) {
        return ObbProbe.Undetermined("this access mode cannot list Android/obb")
    }
    if (lines.any { it == SENTINEL_NODIR }) return ObbProbe.None
    if (lines.none { it == SENTINEL_END }) {
        return ObbProbe.Undetermined("the privileged shell reply was truncated")
    }

    // A malformed [PREFIX_OBB] line fails the whole probe closed, and the distinction that makes
    // that safe rather than brittle is which lines are Thor's own:
    //
    //  - A line *without* the prefix is shell noise — a toybox warning, a merged stderr stream — and
    //    is skipped. Letting noise be fatal would refuse good listings on ROMs that chatter.
    //  - A line *with* the prefix came from `stat` on a `*.obb` glob match, so it is proof an
    //    expansion file exists. If it will not parse, we know a file is there and cannot
    //    characterise it — most plausibly a filename containing a newline split across two lines and
    //    we lost data.
    //
    // Dropping such a line would leave `Present` reporting fewer expansions than the directory
    // holds, so the builder would pack an incomplete `.xapk` and ship a game without its data —
    // GH#164 again, reached from a new direction. Worse, when it was the only line, `Present(emptyList(), 0)`
    // would claim the directory holds nothing at all. `Undetermined` costs a disabled `.xapk` chip
    // in a rare case; the alternative costs a silently broken bundle.
    val files = mutableListOf<ObbFile>()
    for (line in lines) {
        if (!line.startsWith(PREFIX_OBB)) continue
        val rest = line.removePrefix(PREFIX_OBB)
        val space = rest.indexOf(' ')
        if (space <= 0) {
            return ObbProbe.Undetermined("an expansion file listing carried no size")
        }
        val size = rest.substring(0, space).toLongOrNull()
            ?: return ObbProbe.Undetermined("an expansion file listing had an unreadable size")
        val name = rest.substring(space + 1).substringAfterLast('/')
        // The same predicate the copy command is gated on, deliberately. A name this rejects — not
        // an .obb, or one Thor cannot safely hand to a shell — would pass here and then fail during
        // staging, which makes Present a verdict the export cannot honour. The export sheet reads
        // Present as "capturable", so anything uncapturable has to be caught here or that promise
        // is a lie. The reason string names no filename: it is diagnostic, and the name is
        // attacker-chosen.
        if (!isSafeObbLeafName(name)) {
            return ObbProbe.Undetermined("an expansion file listing named a file Thor cannot capture")
        }
        files += ObbFile(name, size)
    }

    val other = lines.firstOrNull { it.startsWith(PREFIX_OTHER) }
        ?.removePrefix(PREFIX_OTHER)
        ?.toIntOrNull()
        ?: 0

    // Every malformed listing has already returned above, so empty-and-zero here genuinely means the
    // directory existed and held nothing.
    return if (files.isEmpty() && other == 0) ObbProbe.None else ObbProbe.Present(files, other)
}
