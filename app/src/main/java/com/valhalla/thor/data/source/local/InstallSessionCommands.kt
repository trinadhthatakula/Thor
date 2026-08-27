// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import com.valhalla.superuser.utils.escapeForShell

/**
 * One staged APK, as the shell needs to describe it to a `PackageInstaller` session.
 *
 * [sizeBytes] is not a convenience: `pm install-write` reading from `-` cannot size a stream, so
 * `-S <bytes>` is the only thing that tells the session how much to expect. It must be the length
 * of the file as staged, read at build time — not a value carried over from before the copy.
 *
 * [name] is the split name the session keys this file by, defaulting to the file's own leaf. A
 * caller that stages under generated file names must pass the real split name explicitly, or the
 * commit will reject the set.
 */
internal data class SessionApk(
    val path: String,
    val sizeBytes: Long,
    val name: String = path.substringAfterLast('/'),
)

/** `pm install-create` did not return a session id. */
internal const val SESSION_CREATE_FAILED_EXIT_CODE = 101

/** A staged APK could not be streamed into the session; the session was abandoned. */
internal const val SESSION_WRITE_FAILED_EXIT_CODE = 102

/** The session was created and written but `pm install-commit` refused it. */
internal const val SESSION_COMMIT_FAILED_EXIT_CODE = 103

/**
 * Builds the privileged shell install: a `PackageInstaller` session whose bytes are **streamed in**,
 * so no path is ever handed to `pm`.
 *
 * ### Why not `pm install <path>`
 *
 * Because `pm` does not open that path itself. `PackageManagerShellCommand` resolves the argument
 * through `ShellCommand.openFileForSystem`, which calls back to the *client* to `open()` the file
 * with the caller's credentials and then has system_server check the returned fd against
 * `u:r:system_server:s0`. Two conditions, both of which must hold:
 *
 *  1. the privileged shell can open the path, and
 *  2. what it opened is readable by system_server.
 *
 * The platform's own advice when this fails is `"Consider using a file under /data/local/tmp/"`.
 * Streaming drops condition 2 entirely — the shell's `cat` performs the only `open()` that happens
 * and `pm install-write` is handed a pipe. That is the difference between the root rung, which has
 * installed flawlessly since it was moved to a session (GH#159, `pm` exiting 255), and the Shizuku
 * and Dhizuku rungs, which were written *afterwards* still naming an absolute path in shared storage
 * and failed for the whole time they existed.
 *
 * ### Why not `pm install-multiple`
 *
 * Because there is no such verb. It appears nowhere in `PackageManagerShellCommand`, not even in its
 * help text — `adb install-multiple` is implemented on the host, inside adb, as a sequence of the
 * three session verbs used below. Every split, `.apks` and `.xapk` install issued as
 * `pm install-multiple` therefore failed on an unknown verb, on every device and every API level,
 * independently of who could read what. A session is the only way a shell installs a split set.
 *
 * ### Contract
 *
 * Paths and split names are passed **raw** and escaped here, exactly once. Callers that also wrap
 * this script in [com.valhalla.thor.data.repository.integrityGuardedInstall] pass the same raw paths
 * to that builder, which escapes them itself; keeping both escapes on this side of the call would
 * mean threading two spellings of one path through a single call site.
 *
 * The script wraps itself in `( … )` so the transport stops being something a caller has to know.
 * Odin's root channel is a single long-lived `su` session fed on stdin, where a top-level `exit`
 * kills the session rather than the script: libsu never sees its end marker, the real exit code is
 * lost, and the next unrelated privileged command fails too. Shizuku spawns a fresh `sh` per command
 * and Dhizuku uses `sh -c`, so both tolerate a bare `exit` — which means an unwrapped script works
 * on two transports and breaks the third.
 *
 * `set -o pipefail` is load-bearing for the same reason the streaming exists. In
 * `cat <apk> | pm install-write … -` the `cat` is the half that fails when a staged file cannot be
 * read, and without `pipefail` the pipeline reports `install-write`'s status — which is 0 for a
 * session that faithfully received nothing. The install would then fail at commit, for a reason
 * naming neither the file nor the read.
 *
 * @param apks the staged APKs, base first. Must not be empty.
 * @param userId the user the package is installed for. Never omit it: `makeInstallParams` starts at
 *   `UserHandle.USER_ALL` and, if the option loop never sees a `--user`, creates the session with
 *   `USER_SYSTEM` plus `INSTALL_ALL_USERS` — installing on every user of the device, and exiting 0.
 * @param canDowngrade adds `-d`. Permissive only, so it is opt-in.
 * @param grantAllPermissions adds `-g`, granting every runtime permission the package declares at
 *   install time without asking the user. **Required, with no default**, so that a caller cannot
 *   omit the user's answer: this used to say "every caller must pass it explicitly" and leave a
 *   `false` default in the signature, which is a rule a reviewer enforces rather than the compiler.
 *   The safe direction is not the point — a caller that silently takes `false` ignores a user who
 *   turned the setting on, which is the same shape of unasked decision, pointing the other way. It
 *   was unconditional until GH#445: an app
 *   installed through Thor came up with location, contacts and microphone already granted, which is
 *   neither what the platform installer does nor anything the UI said was happening. `-r` on its own
 *   does not touch permissions already granted, so an update keeps what the user had chosen —
 *   dropping `-g` costs nothing on the update path and only stops the silent grant on the new-install
 *   one.
 * @param installerArg the attribution flag, e.g. `" -i com.android.vending"`, with or without its
 *   leading space. Re-spaced here so it cannot fuse onto the flag before it — which used to be a
 *   constant `-g` and is now `-g` or `-r`, so the hazard did not go away with the constant.
 * @return a script whose exit code is 0 on success, or one of
 *   [SESSION_CREATE_FAILED_EXIT_CODE] / [SESSION_WRITE_FAILED_EXIT_CODE] /
 *   [SESSION_COMMIT_FAILED_EXIT_CODE], with the `pm` output on stderr.
 */
internal fun installViaSessionCommand(
    apks: List<SessionApk>,
    userId: Int,
    canDowngrade: Boolean = false,
    grantAllPermissions: Boolean,
    installerArg: String = "",
): String {
    require(apks.isNotEmpty()) { "installViaSessionCommand needs at least one APK" }

    val downgrade = if (canDowngrade) " -d" else ""
    val grant = if (grantAllPermissions) " -g" else ""
    val installer = installerArg.trim().let { if (it.isEmpty()) "" else " $it" }

    val sb = StringBuilder()
    sb.append("(\n")
    sb.append("set -o pipefail\n")
    sb.append("CREATE_OUT=\$(pm install-create -r").append(grant).append(installer)
        .append(" --user ").append(userId).append(downgrade).append(" 2>&1)\n")
    // install-create prints "Success: created install session [<id>]".
    sb.append("SID=\$(printf '%s\\n' \"\$CREATE_OUT\" | sed -n 's/.*\\[\\([0-9]*\\)\\].*/\\1/p')\n")
    sb.append("if [ -z \"\$SID\" ]; then echo \"pm install-create failed: \$CREATE_OUT\" 1>&2; exit ")
        .append(SESSION_CREATE_FAILED_EXIT_CODE).append("; fi\n")

    for (apk in apks) {
        // 2>&1 1>/dev/null keeps pm's reason in WERR while discarding its chatter, and every abort
        // past this point must abandon the session: PackageInstaller caps concurrent sessions per
        // installer, and this rung is *designed* to give up and fall through to the next one.
        sb.append("WERR=\$(cat ").append(apk.path.escapeForShell())
            .append(" | pm install-write -S ").append(apk.sizeBytes)
            .append(" \"\$SID\" ").append(apk.name.escapeForShell())
            .append(" - 2>&1 1>/dev/null)")
            .append(" || { pm install-abandon \"\$SID\" 2>/dev/null;")
            .append(" echo \"pm install-write failed: \$WERR\" 1>&2; exit ")
            .append(SESSION_WRITE_FAILED_EXIT_CODE).append("; }\n")
    }

    // install-commit exits 0 for a session it merely handed off; the outcome is on stdout.
    sb.append("COMMIT=\$(pm install-commit \"\$SID\" 2>&1)\n")
    sb.append("case \"\$COMMIT\" in\n")
    sb.append("  *Success*) exit 0 ;;\n")
    sb.append("  *) pm install-abandon \"\$SID\" 2>/dev/null;")
        .append(" echo \"pm install-commit failed: \$COMMIT\" 1>&2; exit ")
        .append(SESSION_COMMIT_FAILED_EXIT_CODE).append(" ;;\n")
    sb.append("esac\n")
    sb.append(")\n")
    return sb.toString()
}
