// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import com.valhalla.thor.data.repository.INTEGRITY_CHECK_EXIT_CODE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one question this file exists to answer: **does a privileged install ever name a path to
 * `pm`?**
 *
 * It must not. `pm install <path>` does not open the file in the shell that typed the command —
 * `PackageManagerShellCommand` resolves the argument through `ShellCommand.openFileForSystem`,
 * which asks the *client* to open it and then has system_server check the result against
 * `u:r:system_server:s0`. So the read has to satisfy two conditions at once: the privileged shell
 * must be able to open the path, **and** the opened file's SELinux label must be readable by
 * system_server. The platform even says so in its own failure text —
 * `"Consider using a file under /data/local/tmp/"`.
 *
 * Streaming the bytes in (`cat <apk> | pm install-write -S <n> "$SID" <name> -`) drops the second
 * condition entirely: the privileged shell's own `cat` does the only open that happens, and
 * `pm install-write` receives a pipe. That is why the root gateway installs flawlessly while the
 * Shizuku and Dhizuku shell rungs did not — root was fixed for exactly this (GH#159, `pm` exit 255)
 * and the fix was never swept onto the other two, which were written *afterwards* still naming a
 * path.
 *
 * The second defect pinned here is narrower and absolute: **`install-multiple` is not a `pm` verb.**
 * It appears nowhere in `PackageManagerShellCommand.java`, not even in its help text — `adb
 * install-multiple` is implemented inside adb, on the host. So every split/`.apks`/`.xapk` install
 * issued as `pm install-multiple` failed on an unknown verb, on every device, regardless of who
 * could read what. A session (`install-create` → per-split `install-write` → `install-commit`) is
 * the only way a shell installs a split set at all.
 *
 * ### Why this is a string test, and what it therefore cannot say
 *
 * None of the three gateways is reachable from a JVM test — each needs a live root shell, a Shizuku
 * binder or a device-owner identity, and there is no mockk and no Robolectric on this source set.
 * That is the same boundary [PerUserCommandsTest] documents, and the same answer applies: the
 * command is lifted out so that the part of the bug which *is* a string question can be asked here.
 * "Does the Shizuku rung actually call this builder?" cannot be answered here and is not faked.
 *
 * What that boundary does **not** excuse is drift. Before this builder existed the streaming script
 * was written out by hand in `RootSystemGateway` and nowhere else, which is precisely how the
 * Shizuku and Dhizuku rungs came to spell the same operation a different — broken — way. One
 * builder for all three gateways is the point, and the golden-script test at the bottom of this file
 * is what keeps the extraction honest.
 */
class InstallSessionCommandsTest {

    /** Digit-free names, so a `10` in an assertion can only have come from the user id. */
    private val base = SessionApk("/data/cache/install/base.apk", sizeBytes = 4096)
    private val split = SessionApk("/data/cache/install/split_config.en.apk", sizeBytes = 512)

    /** Exactly what `PreferenceRepository.getInstallerArg()` returns when auto-reinstall is on. */
    private val playInstallerArg = " -i com.android.vending"

    private fun command(
        apks: List<SessionApk> = listOf(base),
        userId: Int = 10,
        canDowngrade: Boolean = false,
        installerArg: String = "",
    ) = installViaSessionCommand(apks, userId, canDowngrade, installerArg)

    // --- the defect itself: no path may reach pm ---

    /**
     * The whole bug, in one assertion. `pm install` and `pm install-multiple` both take the APK as
     * a **path argument**, and that path is opened for system_server rather than by the shell — so
     * on the Shizuku and Dhizuku rungs, whose staged files live under `Android/data`, the install
     * died before `pm` had read a byte. Neither verb may appear here at all.
     */
    @Test
    fun `no APK is ever named as a path argument to pm`() {
        for (apks in listOf(listOf(base), listOf(base, split))) {
            val script = command(apks)
            assertFalse(
                "the script hands a path to `pm install`, which opens it for system_server: $script",
                Regex("""pm\s+install\s""").containsMatchIn(script)
            )
            assertFalse(
                "`pm install-multiple` is not a verb any Android implements",
                script.contains("install-multiple")
            )
        }
    }

    /**
     * `install-multiple` deserves its own assertion rather than only the negative above, because it
     * is not a permissions problem that some device somewhere might not have: the verb is absent
     * from `PackageManagerShellCommand`, so the command failed **unconditionally** — every split
     * bundle, every device, root included had it not used a session. The three verbs that do exist
     * are the ones a session is made of.
     */
    @Test
    fun `a split set installs through a session rather than a non-existent verb`() {
        val script = command(listOf(base, split))
        assertTrue(script.contains("pm install-create "))
        assertTrue(script.contains("pm install-write "))
        assertTrue(script.contains("pm install-commit "))
    }

    /**
     * Every staged APK is streamed, in the order given, and each path appears **once** — as the
     * argument to `cat`. Order matters: a split set must present its base first, and the count
     * matters because a bundle installs as a set, so a dropped split fails the commit rather than
     * installing something smaller.
     */
    @Test
    fun `every APK is streamed in over stdin, in order, exactly once`() {
        val script = command(listOf(base, split))

        for (apk in listOf(base, split)) {
            val quoted = "'${apk.path}'"
            assertEquals(
                "${apk.path} appears somewhere other than as `cat`'s argument: $script",
                1,
                Regex(Regex.escape(quoted)).findAll(script).count()
            )
            assertTrue(
                "${apk.path} is not streamed into the session",
                script.contains("cat $quoted | pm install-write ")
            )
        }
        assertTrue(
            "the base APK must be written before its splits",
            script.indexOf(base.path) < script.indexOf(split.path)
        )
    }

    /**
     * `pm install-write` cannot size a stream it is handed on stdin, so `-S <bytes>` is what tells
     * the session how much to expect. Wrong, and the commit fails on a short or over-long write
     * with a reason that says nothing about the size; absent, and `install-write` rejects `-`
     * outright.
     */
    @Test
    fun `each stream declares its own byte count`() {
        val script = command(listOf(base, split))
        assertTrue(script.contains("pm install-write -S 4096 \"\$SID\" 'base.apk' -"))
        assertTrue(script.contains("pm install-write -S 512 \"\$SID\" 'split_config.en.apk' -"))
    }

    /** The split name defaults to the file's own leaf, which is what a session keys splits by. */
    @Test
    fun `the split name defaults to the file name`() {
        assertEquals("base.apk", SessionApk("/a/b/base.apk", 1).name)
        assertEquals(
            "an explicit name must win, for a staged file whose leaf is not its split name",
            "config.arm64_v8a.apk",
            SessionApk("/a/b/tmp0.apk", 1, name = "config.arm64_v8a.apk").name
        )
    }

    // --- the flags that decide who gets the app ---

    /**
     * The `--user` trap the deleted `installCommand` documented, entered through `install-create`
     * instead.
     * `makeInstallParams` opens with `params.userId = UserHandle.USER_ALL` and leaves it there when
     * the option loop never sees a `--user`, after which the session is created with `USER_SYSTEM`
     * plus `INSTALL_ALL_USERS` — so a bare `install-create` puts the package on **every user of the
     * device** and exits 0. Switching the rung from `install` to a session must not lose that.
     */
    @Test
    fun `the session is created for a named user`() {
        assertEquals(
            10,
            Regex("--user (\\d+)").find(command(userId = 10))?.groupValues?.get(1)?.toIntOrNull()
        )
    }

    /**
     * `-r` is what makes this an update rather than `INSTALL_FAILED_ALREADY_EXISTS`, and `-g` is
     * what grants the runtime permissions the installed app declares. They belong on
     * `install-create`, not on `install-write`: the session's parameters are fixed when it is
     * created, so a flag written on a later verb is silently ignored.
     */
    @Test
    fun `the update and grant flags are set when the session is created`() {
        val createLine = command().lineSequence().first { it.startsWith("CREATE_OUT=") }
        assertTrue("-r was dropped: this stops being an update", createLine.contains(" -r"))
        assertTrue("-g was dropped: the app installs with no permissions", createLine.contains(" -g"))
    }

    /** `-d` is permissive-only, so it must appear exactly when it was asked for and never otherwise. */
    @Test
    fun `downgrade is opt-in`() {
        assertFalse(command().contains(" -d"))
        assertTrue(command(canDowngrade = true).contains(" -d"))
    }

    /**
     * The same fusion hazard the deleted `installCommand` guarded against, which the root script
     * did not. `getInstallerArg()` returns `" -i com.android.vending"` with a leading space; a
     * caller passing it trimmed — or a future `getInstallerArg` that stops padding — would emit
     * `-g-i com.android.vending`, which `pm` answers with a usage error that surfaces to the user as
     * a plain install failure with no hint that the attribution flag is what broke it.
     */
    @Test
    fun `the installer attribution cannot fuse onto the preceding flag`() {
        val padded = command(installerArg = playInstallerArg)
        val trimmed = command(installerArg = playInstallerArg.trim())

        assertEquals(padded, trimmed)
        assertFalse("the -i argument fused onto -g", padded.contains("-g-i"))
        assertTrue(padded.contains(" -i com.android.vending "))
    }

    /** Auto-reinstall off is the default, and must add nothing at all — not even a stray space. */
    @Test
    fun `no installer argument means no installer argument`() {
        assertTrue(command().contains("pm install-create -r -g --user 10 2>&1"))
    }

    // --- the failure handling that keeps a failure legible and a session from leaking ---

    /**
     * `set -o pipefail` is load-bearing, not hygiene. In `cat <apk> | pm install-write … -` the
     * `cat` is the half that fails when the staged file cannot be read — which is the entire failure
     * mode this rung exists to survive — and without `pipefail` the pipeline reports
     * `install-write`'s status instead, which is 0 for a session that faithfully received zero
     * bytes. The install would then fail later, at commit, for a reason that names neither the file
     * nor the read.
     */
    @Test
    fun `a failed read cannot be masked by the writer's exit code`() {
        assertTrue(command().contains("set -o pipefail"))
    }

    /**
     * Every abort after `install-create` succeeded has to abandon the session. `PackageInstaller`
     * caps concurrent sessions per installer, so a rung that gives up without abandoning spends one
     * of them — and this rung is *designed* to give up and fall through, so the leak is on the
     * expected path, not an exotic one. A few dozen failed installs then block every later install
     * until the sessions age out.
     */
    @Test
    fun `both failure paths after create abandon the session`() {
        val script = command()
        assertEquals(
            "one abandon per post-create failure path (write, commit): $script",
            2,
            script.split("pm install-abandon ").size - 1
        )
    }

    /**
     * Three distinct codes, and none of them 90. The caller reads a single `Pair<Int, String?>`, so
     * the exit code is the only structured thing it gets: create-failed, write-failed and
     * commit-failed are three different problems with three different next steps, and 90 already
     * means "the staged APK failed its integrity check" ([INTEGRITY_CHECK_EXIT_CODE]) on the two
     * rungs that wrap this script in that guard. A collision there would report a hash mismatch for
     * a session that simply could not be created.
     */
    @Test
    fun `each failure stage has its own exit code and none collides with the integrity guard`() {
        val script = command()
        val codes = Regex("""exit (\d+)""").findAll(script).map { it.groupValues[1].toInt() }.toSet()

        assertEquals(setOf(0, 101, 102, 103), codes)
        assertFalse(
            "an install failure would be reported as an integrity-check failure",
            INTEGRITY_CHECK_EXIT_CODE in codes
        )
    }

    /**
     * Success is `pm install-commit` printing `Success`, not its exit code. `install-commit` returns
     * 0 for a session it merely *handed off*; the outcome arrives on stdout. Reading the code alone
     * reports every rejected commit as an installed app.
     */
    @Test
    fun `success is read from the commit output, not its exit status`() {
        assertTrue(command().contains("*Success*) exit 0 ;;"))
    }

    /**
     * The script ends its own subshell, so the transport stops being something a caller has to know.
     * Odin's root channel is one long-lived `su` session fed on stdin: a top-level `exit` there kills
     * the *session*, libsu never appends its end marker, the real exit code is lost and the next
     * unrelated privileged command fails too. Shizuku spawns a fresh `sh` per command and Dhizuku
     * uses `sh -c`, so both tolerate a bare `exit` — meaning a script that omits the wrap passes on
     * two transports and breaks the third. That asymmetry is why this is asserted here rather than
     * left to each caller to remember.
     */
    @Test
    fun `the whole script runs in a subshell`() {
        val script = command()
        assertTrue(script.startsWith("(\n"))
        assertTrue(script.trimEnd().endsWith(")"))
    }

    // --- inputs ---

    /**
     * Paths are passed **raw** and quoted here, exactly once. The two privileged rungs also hand
     * these paths to `integrityGuardedInstall`, which escapes them itself; when this builder took
     * pre-escaped paths, the two spellings had to be threaded through the same call site without
     * ever crossing — a comment in `installWithShizuku` used to warn about it. Escaping in one place
     * removes the hazard rather than documenting it.
     */
    @Test
    fun `paths are escaped here, and only here`() {
        val awkward = SessionApk("/data/cache/it's here/base.apk", sizeBytes = 1)
        val script = installViaSessionCommand(listOf(awkward), userId = 0)

        assertTrue(
            "single quotes in a path must be neutralised, not passed through: $script",
            script.contains("""cat '/data/cache/it'\''s here/base.apk' | pm install-write""")
        )
    }

    /**
     * An empty set is a programming error, not a runtime condition — every caller returns early when
     * staging produced no files. Failing here keeps the mistake in the stack trace of whoever made
     * it, rather than handing a privileged shell a session it will create, write nothing into and
     * commit.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `an install with no APK is refused`() {
        installViaSessionCommand(emptyList(), userId = 0)
    }

    // --- the extraction is behaviour-preserving for the one rung that already worked ---

    /**
     * The root gateway installs flawlessly today, and this builder replaces the script it used to
     * assemble by hand. So the extraction is pinned against that script verbatim: if this test has
     * to be edited, root's behaviour changed, and root's behaviour changing is a regression of the
     * only rung the bug report says works.
     *
     * Spelled out in full rather than asserted piecewise on purpose. Everything above checks one
     * property at a time and would pass for a script that satisfied all of them separately while
     * being a different script; this is the one place the whole thing is visible at once.
     */
    @Test
    fun `the script the root gateway used to build by hand`() {
        val expected = """
            (
            set -o pipefail
            CREATE_OUT=${'$'}(pm install-create -r -g --user 0 2>&1)
            SID=${'$'}(printf '%s\n' "${'$'}CREATE_OUT" | sed -n 's/.*\[\([0-9]*\)\].*/\1/p')
            if [ -z "${'$'}SID" ]; then echo "pm install-create failed: ${'$'}CREATE_OUT" 1>&2; exit 101; fi
            WERR=${'$'}(cat '/data/cache/install/base.apk' | pm install-write -S 4096 "${'$'}SID" 'base.apk' - 2>&1 1>/dev/null) || { pm install-abandon "${'$'}SID" 2>/dev/null; echo "pm install-write failed: ${'$'}WERR" 1>&2; exit 102; }
            COMMIT=${'$'}(pm install-commit "${'$'}SID" 2>&1)
            case "${'$'}COMMIT" in
              *Success*) exit 0 ;;
              *) pm install-abandon "${'$'}SID" 2>/dev/null; echo "pm install-commit failed: ${'$'}COMMIT" 1>&2; exit 103 ;;
            esac
            )

        """.trimIndent()

        assertEquals(expected, installViaSessionCommand(listOf(base), userId = 0))
    }
}
