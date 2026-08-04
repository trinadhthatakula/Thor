// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The commands and paths that decide *whose* data a privileged operation destroys.
 *
 * None of the three gateways is reachable from a JVM test — each needs a live root shell, a Shizuku
 * binder or a device-owner identity — which is exactly why the strings they issue were extracted:
 * "does this command name an Android user at all?" is the whole of the bug and it is a plain string
 * question. Every assertion below uses a **work-profile** id (10, and 11 where two are needed to
 * tell one user from another), because at user 0 a missing `--user` and a correct one produce the
 * same effect and the defect is invisible; the handful of tests that pin user 0 exist to prove the
 * fix changes nothing on a single-user device.
 *
 * `Process.myUserHandle()` is not callable here, so [thorUserId] itself is not under test. What is
 * under test is that the builders spell out whatever number they are given.
 *
 * ### Where the reachable boundary is, stated plainly
 *
 * The defect this file was written for did **not** live in these builders — they were correct the
 * moment they were typed. It lived at the call sites, which named the foreground user, or user 0, or
 * no user at all. Nothing below can see a call site: `RootSystemGateway`, `ShizukuHelper` and
 * `DhizukuHelper` all reach `android.*` in their constructors and there is no mockk and no
 * Robolectric on this source set to stand in for them, so "does `installApp` actually call
 * [installCommand]?" is unanswerable here and is deliberately not faked.
 *
 * Two things close as much of that gap as a JVM test honestly can:
 *
 *  - the per-builder tests pin the *whole* command, not just its `--user`. A regression that keeps
 *    the user and drops `-g`, or flips `allow` for `ignore`, is a different bug with the same blast
 *    radius, and the builder is the one place both are visible at once.
 *  - `every builder in the file names a user` walks the file's compiled class by reflection instead
 *    of a hand-kept roster, so a builder added tomorrow is covered without anyone remembering to
 *    come here. A roster would have been the same defect as the one under repair: a list that has to
 *    be updated by hand, next to code that compiles fine when it is not.
 *
 * What is left uncovered is the wiring, and it is uncovered on purpose. It needs a device.
 */
class PerUserCommandsTest {

    private val pkg = "com.example.app"

    /** Two APK paths that are digit-free, so a `10` in an assertion can only be the user id. */
    private val baseApk = "/data/local/tmp/base.apk"
    private val splitApk = "/data/local/tmp/split_config.en.apk"

    /** Exactly what `PreferenceRepository.getInstallerArg()` returns when auto-reinstall is on. */
    private val playInstallerArg = " -i com.android.vending"

    /** The `--user <id>` a command names, or null if it names none — which is the bug. */
    private fun userArgOf(command: String): Int? =
        Regex("--user (\\d+)").find(command)?.groupValues?.get(1)?.toIntOrNull()

    // --- pm clear: the irreversible one ---

    /**
     * `PackageManagerShellCommand.runClear` seeds `userId = UserHandle.USER_SYSTEM`, so a bare
     * `pm clear <pkg>` issued by a Thor running in user 10 wipes **user 0's** copy — irreversibly,
     * and with exit code 0, which every caller reads as success.
     */
    @Test
    fun `clearing app data names the user`() {
        assertEquals(10, userArgOf(clearAppDataCommand(pkg, 10)))
    }

    /**
     * The invariant the two rungs of `clearAppData` have to satisfy, in all three privilege modes:
     * the user named in the shell rung is the number handed to `clearApplicationUserData` in the
     * reflection rung. Both take the same parameter now — this pins that the shell half actually
     * spells it out, which is the half that was missing, and is why one function's two rungs used to
     * wipe two different users' data depending on which one happened to run.
     */
    @Test
    fun `the shell rung names the same user the reflection rung is handed`() {
        for (userId in listOf(0, 10, 11)) {
            assertEquals(userId, userArgOf(clearAppDataCommand(pkg, userId)))
        }
    }

    // --- pm disable / pm enable ---

    @Test
    fun `disabling and enabling both name the user`() {
        assertEquals(10, userArgOf(setAppEnabledCommand(pkg, 10, isDisabled = true)))
        assertEquals(10, userArgOf(setAppEnabledCommand(pkg, 10, isDisabled = false)))
    }

    /**
     * A freeze that names user 0 while the verifying re-read looks at user 10 does not merely fail —
     * it reports failure *and* disables another profile's copy, and every retry repeats the damage.
     * So the verb has to be right as well as the user.
     */
    @Test
    fun `the verb follows the requested state`() {
        assertTrue(setAppEnabledCommand(pkg, 10, isDisabled = true).startsWith("pm disable "))
        assertTrue(setAppEnabledCommand(pkg, 10, isDisabled = false).startsWith("pm enable "))
    }

    /**
     * `disable`, never `disable-user`. Only the root gateway builds this line and uid 0 may set the
     * stronger COMPONENT_ENABLED_STATE_DISABLED; the Shizuku and Dhizuku helpers build their own
     * `pm disable-user` because a shell-uid or device-owner caller is refused the stronger state.
     * `startsWith("pm disable ")` above would pass for `pm disable-user` if the trailing space ever
     * went missing, so the distinction is asserted rather than implied.
     */
    @Test
    fun `disable is the strong state and not disable-user`() {
        assertFalse(setAppEnabledCommand(pkg, 10, isDisabled = true).contains("disable-user"))
    }

    // --- pm uninstall: the one that used to hit every user at once ---

    /**
     * The worst of the three, because the bare form does not merely target the wrong user.
     * `PackageManagerShellCommand.runUninstall` seeds `userId = UserHandle.USER_ALL` and then does
     * `if (userId == UserHandle.USER_ALL) flags |= DELETE_ALL_USERS`, so `pm uninstall <pkg>` removes
     * the package **and its data for every user on the device** and exits 0. Naming the user is what
     * stops one profile's uninstall reaching into another's.
     */
    @Test
    fun `uninstalling names the user`() {
        assertEquals(10, userArgOf(uninstallCommand(pkg, 10)))
    }

    /**
     * And it is a plain removal: `-k` is `DELETE_KEEP_DATA`, which belongs to the system-app freeze
     * fallback. If it ever leaked into this builder the uninstall button would leave the app's data
     * directories behind, which is the opposite of what the user asked for and invisible until
     * somebody measured free space.
     */
    @Test
    fun `uninstalling does not keep data`() {
        assertFalse(uninstallCommand(pkg, 10).contains(" -k"))
        assertTrue(uninstallCommand(pkg, 10).startsWith("pm uninstall --user "))
    }

    /** The single-user half of the claim: at user 0 the effect is what it always was. */
    @Test
    fun `at user 0 uninstall names 0`() {
        assertEquals("pm uninstall --user 0 $pkg", uninstallCommand(pkg, 0))
    }

    // --- cache paths ---

    /**
     * `/data/data/<pkg>` and `/sdcard` are per-user aliases resolved against the *shell's* user, so
     * a privileged `rm -rf` expanding them deletes user 0's cache no matter which user Thor runs as.
     * They used to sit either side of the correct path in all three gateways, so the operation both
     * missed its target and hit somebody else's.
     */
    @Test
    fun `no cache path is a user-0 alias`() {
        val paths = clearCachePaths(pkg, 10)
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            assertFalse("$path is an alias for user 0", path.startsWith("/data/data/"))
            assertFalse("$path is an alias for user 0", path.startsWith("/sdcard"))
            assertTrue("$path does not name user 10", path.contains("/10/"))
        }
    }

    /**
     * And the other half of the claim: on a single-user device this changes nothing. These are the
     * exact directories `/data/data/<pkg>/cache` and `/sdcard/Android/data/<pkg>/cache` resolve to
     * when the shell belongs to user 0, so the commands issued on the overwhelmingly common device
     * are byte-for-byte the ones issued before the fix.
     */
    @Test
    fun `at user 0 the paths are what the old aliases pointed at`() {
        assertEquals(
            listOf(
                "/data/user/0/$pkg/cache",
                "/storage/emulated/0/Android/data/$pkg/cache",
            ),
            clearCachePaths(pkg, 0)
        )
    }

    /**
     * The package name is interpolated verbatim: callers pass an already-escaped value, and a
     * builder that quoted it again would produce a path the shell cannot expand.
     */
    @Test
    fun `the package name is passed through untouched`() {
        assertTrue(clearAppDataCommand("'weird.pkg'", 10).endsWith(" 'weird.pkg'"))
        assertTrue(clearCachePaths("'weird.pkg'", 10).all { it.contains("'weird.pkg'") })
    }

    // --- pm install / install-multiple: uninstall's trap entered from the other side ---

    /**
     * `makeInstallParams` opens with `params.userId = UserHandle.USER_ALL` and leaves it there when
     * the option loop never sees a `--user`; the session is then created with `USER_SYSTEM` plus
     * `INSTALL_ALL_USERS`. So the bare form is not "install for the shell's user" and not "install
     * for user 0" — it pushes the package onto **every user of the device** and exits 0, which from
     * a work profile means an APK appearing in the personal profile nobody chose to install it into.
     */
    @Test
    fun `installing names the user`() {
        assertEquals(10, userArgOf(installCommand(listOf(baseApk), 10)))
        assertEquals(10, userArgOf(installCommand(listOf(baseApk, splitApk), 10)))
    }

    /**
     * The verb follows the path count rather than a parameter of its own, because the four call
     * sites this replaced each branched on that count and built two separate command strings from
     * it — and one operation reaching `pm` down two hand-written paths is precisely where a flag
     * ends up present on one of them only.
     */
    @Test
    fun `the verb follows the number of APKs`() {
        assertTrue(installCommand(listOf(baseApk), 10).startsWith("pm install --user "))
        assertTrue(
            installCommand(listOf(baseApk, splitApk), 10).startsWith("pm install-multiple --user ")
        )
    }

    /**
     * A split install that loses one of its APKs is not a smaller install: `install-multiple`
     * commits a session that has to contain every split the base declares, so a dropped path fails
     * the commit outright. Every path given is passed on, in order.
     */
    @Test
    fun `every APK path survives`() {
        val command = installCommand(listOf(baseApk, splitApk), 10)
        assertTrue(command.endsWith(" $baseApk $splitApk"))
    }

    /**
     * The flags that were constants at all six call sites, and stayed constants. `-r` is what makes
     * this an update rather than an `INSTALL_FAILED_ALREADY_EXISTS`, and `-g` is what grants the
     * runtime permissions the installed app declares — losing either turns a working install into a
     * failure or into an app that silently cannot do anything, neither of which the `--user` fix is
     * allowed to cost.
     */
    @Test
    fun `the constant install flags survive alongside the user`() {
        val command = installCommand(listOf(baseApk), 10)
        assertTrue("-r was dropped: this stops being an update", command.contains(" -r"))
        assertTrue("-g was dropped: the app installs with no permissions", command.contains(" -g"))
    }

    /**
     * `-d` is `--downgrade`: permissive only, so it must appear exactly when it was asked for.
     * Present unasked it lets a lower versionCode silently replace a higher one; absent when asked
     * it turns Thor's own "install anyway" confirmation into `INSTALL_FAILED_VERSION_DOWNGRADE`.
     */
    @Test
    fun `downgrade is opt-in`() {
        assertFalse(installCommand(listOf(baseApk), 10).contains(" -d"))
        assertTrue(installCommand(listOf(baseApk), 10, canDowngrade = true).contains(" -d"))
    }

    /**
     * The installer argument arrives from `getInstallerArg()` already carrying its leading space,
     * and the builder re-spaces it so that a caller passing it trimmed — or a future
     * `getInstallerArg` that stops padding — cannot emit `-g-i com.android.vending`. `pm` answers
     * that with a usage error, which the installer surfaces to the user as a plain install failure
     * with no hint that the attribution flag is what broke it. Both spellings therefore have to
     * produce the same command.
     */
    @Test
    fun `the installer attribution cannot fuse onto the preceding flag`() {
        val padded = installCommand(listOf(baseApk), 10, installerArg = playInstallerArg)
        val trimmed = installCommand(listOf(baseApk), 10, installerArg = playInstallerArg.trim())

        assertEquals(padded, trimmed)
        assertFalse("the -i argument fused onto -g", padded.contains("-g-i"))
        assertTrue(padded.contains(" -i com.android.vending "))
    }

    /** Auto-reinstall off is the default, and it must add nothing at all — not even a stray space. */
    @Test
    fun `no installer argument means no installer argument`() {
        assertEquals(
            "pm install --user 10 -r -g $baseApk",
            installCommand(listOf(baseApk), 10)
        )
    }

    /** All four options together, in the order `pm` parses them, spelled out once. */
    @Test
    fun `the fully loaded install command`() {
        assertEquals(
            "pm install-multiple --user 10 -r -g -d -i com.android.vending $baseApk $splitApk",
            installCommand(
                escapedApkPaths = listOf(baseApk, splitApk),
                userId = 10,
                canDowngrade = true,
                installerArg = playInstallerArg,
            )
        )
    }

    /**
     * `PackageManagerShellCommand` parses options until the first positional and treats everything
     * after it as an APK path, so a `--user` that drifted behind the paths would not be an error —
     * it would be read as another file to install, and the install would fall back to the
     * all-users seed with no diagnostic at all.
     */
    @Test
    fun `the user is named before the APK paths`() {
        val command = installCommand(listOf(baseApk, splitApk), 10, canDowngrade = true)
        assertTrue(command.indexOf("--user") < command.indexOf(baseApk))
    }

    /**
     * An empty path list is a programming error, not a runtime condition — every caller returns
     * early when the copy-to-temp step produced no files. Failing here rather than handing
     * `pm install --user 10 -r -g ` to a privileged shell keeps the mistake in the stack trace of
     * whoever made it.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `an install with no APK is refused`() {
        installCommand(emptyList(), 10)
    }

    // --- pm path: the read half of Fix Store ---

    /**
     * `PackageManagerShellCommand.runPath` seeds `USER_SYSTEM` and then asks
     * `getPackageInfo(pkg, …, userId)`, so the bare form answers for **user 0's** record whichever
     * user the shell belongs to. Its only caller feeds the answer straight into a
     * `pm install … --user <thorUserId>`, so read and write named different users while every
     * command in the chain exited 0. The APK bytes are device-wide — what a user id selects here is
     * visibility — which is why nothing surfaced the mismatch.
     */
    @Test
    fun `asking for a package's APK paths names the user`() {
        assertEquals("pm path --user 10 $pkg", pmPathCommand(pkg, 10))
    }

    /** And on a single-user device it is the query it always was. */
    @Test
    fun `at user 0 pm path names 0`() {
        assertEquals("pm path --user 0 $pkg", pmPathCommand(pkg, 0))
    }

    // --- am force-stop: a different binary with the same USER_ALL seed ---

    /**
     * `ActivityManagerShellCommand.runForceStop` seeds `UserHandle.USER_ALL`, so the bare form kills
     * the package on **every user of the device**. The stakes are lower than the `pm` cases —
     * force-stop destroys no data and the process returns on the next launch — but the cost is
     * another profile's app losing live state: a scheduled alarm, a foreground service, an unsaved
     * draft. It is fixed because the Shizuku and Dhizuku helpers already pass `--user` on this exact
     * command, and one gateway spelling an operation differently from the other two is how "that is
     * fixed" quietly stops being true.
     */
    @Test
    fun `force-stop names the user`() {
        assertEquals("am force-stop --user 10 $pkg", forceStopCommand(pkg, 10))
    }

    /**
     * And it is `am`, not `pm`. The two shell commands seed different defaults from different
     * source files, so a builder that drifted onto the wrong binary would not merely fail — `pm`
     * has no `force-stop` subcommand and answers with a usage dump on stdout and exit 0, which
     * every caller in this codebase reads as a successful kill.
     */
    @Test
    fun `force-stop goes through am`() {
        assertTrue(forceStopCommand(pkg, 10).startsWith("am "))
    }

    // --- appops: neither user 0 nor all users, but whoever is in the foreground ---

    /**
     * `AppOpsService.Shell.parseUserPackageOp` seeds `UserHandle.USER_CURRENT` and resolves it with
     * `ActivityManager.getCurrentUser()` *inside system_server*, so the bare form targeted the
     * globally foreground user. That is why this one failed the way it did: on a managed profile the
     * foreground user is the parent, so a restriction set from the work profile landed on the
     * personal profile's copy — while in a Xiaomi Second Space the space you switched into *is* the
     * foreground user and the same command happened to be right. Which user it hit depended on who
     * was in the foreground at the instant the command ran, a value that can differ between the
     * write and the read-back meant to confirm it.
     */
    @Test
    fun `the background restriction names the user`() {
        assertEquals(10, userArgOf(backgroundRestrictionCommand(pkg, 10, restricted = true)))
        assertEquals(10, userArgOf(backgroundRestrictionCommand(pkg, 10, restricted = false)))
    }

    /**
     * `ignore` restricts, `allow` lifts. Inverted, the freezer's "restrict background" would grant
     * `RUN_ANY_IN_BACKGROUND` to every app it was pointed at — the exact opposite of the request,
     * and invisible until somebody read the op's mode back by hand.
     */
    @Test
    fun `ignore restricts and allow lifts`() {
        assertEquals(
            "appops set --user 10 $pkg RUN_ANY_IN_BACKGROUND ignore",
            backgroundRestrictionCommand(pkg, 10, restricted = true)
        )
        assertEquals(
            "appops set --user 10 $pkg RUN_ANY_IN_BACKGROUND allow",
            backgroundRestrictionCommand(pkg, 10, restricted = false)
        )
    }

    /**
     * `parseUserPackageOp` consumes its options before the package and op positionals, so a `--user`
     * written after the package name is not a syntax error — it is parsed as the *op* argument, and
     * the command fails with "Unknown operation string" long after the caller has stopped looking.
     */
    @Test
    fun `the user precedes the package in the appops command`() {
        val command = backgroundRestrictionCommand(pkg, 10, restricted = true)
        assertTrue(command.indexOf("--user") < command.indexOf(pkg))
    }

    /**
     * The usage-access grant names the user for a reason the other builders do not share, so it is
     * pinned separately rather than folded into the background-restriction tests.
     *
     * Its package is Thor's own, and `UsageAccessManager.isGranted` confirms the grant with an
     * in-process `unsafeCheckOpNoThrow(…, Process.myUid(), …)` — which answers for *Thor's* user
     * unconditionally. The bare form was resolved against `USER_CURRENT`, so on a managed profile
     * the write and the confirming read addressed two different users and the op was granted to the
     * parent profile's copy of Thor. Unlike the freezer's restriction toggle this never reported a
     * false success — `isGranted()` simply kept saying no — which is exactly why it survived: a
     * defect whose only symptom is a feature that never works looks like a feature that is not
     * supported.
     */
    @Test
    fun `the usage stats grant names the user`() {
        assertEquals(10, userArgOf(usageStatsGrantCommand(pkg, 10)))
        assertEquals("appops set --user 10 $pkg GET_USAGE_STATS allow", usageStatsGrantCommand(pkg, 10))
    }

    /**
     * `allow`, never `ignore`. Inverted, Thor would issue a privileged command that *denies* itself
     * the op it is trying to acquire, and `isGranted()` would then report failure — indistinguishable
     * from "no privilege is active", which is the case this whole path is written to tolerate.
     */
    @Test
    fun `the usage stats grant allows rather than ignores`() {
        assertTrue(usageStatsGrantCommand(pkg, 10).endsWith(" GET_USAGE_STATS allow"))
    }

    // --- the shape of the whole file, not one instance of it ---

    /**
     * Every builder in `PerUserCommands.kt` has to take a user id and has to let that id reach its
     * output. Asserted over the file's compiled class rather than a list written out here, because a
     * hand-kept roster is the same defect this whole file exists to repair — a list that has to be
     * updated by hand, sitting next to code that compiles perfectly well when it is not. A builder
     * added tomorrow is covered by this test on the day it is written.
     *
     * The evidence is a *difference*, not a substring: each builder is rendered twice, once for user
     * 10 and once for user 11, and the two renderings have to disagree. That works without knowing
     * any command's syntax, which matters because the file already emits two unrelated shapes —
     * `--user 10` for the shell commands and `/data/user/10/…` for the cache paths — and a third
     * would otherwise need this test edited to recognise it. A builder that ignores the user id it
     * was handed renders identically for both and fails here, which is exactly the regression the
     * per-builder tests above cannot catch on a builder they do not know about.
     *
     * The id is then also required to *appear* as its own token, so a builder that varied its output
     * with the user by some means other than naming it — hashing it into a session name, say — is
     * not accepted as per-user. Neither fixture string contains a digit, so a matched `10` can only
     * have come from the user id.
     *
     * Each boolean combination is compared against its own counterpart rather than joined into one
     * blob first. A builder that named the user on only one of its branches still renders two
     * different joins for users 10 and 11 — the single branch that does vary is enough to make the
     * whole strings differ — so one assertion over the join accepts it. For the mirrored builder,
     * the one that names the user only on its `false` branch, joining is *weaker* than the
     * single-value render this enumeration was written to replace: defaulting the booleans would
     * have caught it. Branch against matching branch is what makes enumerating them mean what the
     * rationale in [renderEach] says it means.
     */
    @Test
    fun `every builder in the file names a user`() {
        for (builder in perUserBuilders()) {
            val forTen = renderEach(builder, userId = 10)
            val forEleven = renderEach(builder, userId = 11)

            for ((ten, eleven) in forTen.zip(forEleven)) {
                val branch =
                    if (ten.booleans.isEmpty()) "" else " on the branch ${ten.booleans}"

                assertNotEquals(
                    "${builder.readableName()} emits the same thing for user 10 and user 11" +
                        "$branch, so the user id it was handed reaches nothing. That is the bug " +
                        "this file exists to fix, in a builder that has not been given its own " +
                        "test yet.",
                    ten.text,
                    eleven.text
                )
                assertTrue(
                    "${builder.readableName()} varies with the user id but never names it" +
                        "$branch: ${ten.text}",
                    userTokenTen.containsMatchIn(ten.text)
                )
            }
        }
    }

    /**
     * A floor under the sweep above, and deliberately not a roster of what it covers.
     *
     * Reflection that finds nothing passes every assertion in a `for` loop, so a renamed file (the
     * compiled class is `PerUserCommandsKt`, derived from the file name) or a Kotlin release that
     * changes how top-level functions are emitted would turn the sweep into a no-op with a green
     * tick. Naming the eight builders that existed when it was written proves it is looking at the
     * right class and seeing real methods. It is `containsAll`, never an equality: adding a builder
     * must not have to be remembered here, which is the entire point of doing this by reflection.
     */
    @Test
    fun `the reflective sweep actually sees the builders`() {
        val found = perUserBuilders().map { it.readableName() }.toSet()

        assertTrue(
            "the sweep found $found, which is missing builders that are known to exist — it is " +
                "looking at the wrong class or filtering out real methods",
            found.containsAll(
                setOf(
                    "clearAppDataCommand",
                    "clearCachePaths",
                    "uninstallCommand",
                    "setAppEnabledCommand",
                    "installCommand",
                    "pmPathCommand",
                    "forceStopCommand",
                    "backgroundRestrictionCommand",
                )
            )
        )
    }

    // --- reflection plumbing for the sweep above ---

    /**
     * `10` as a standalone token. Anchored on non-digits at both ends so it cannot match inside
     * `101` or `/data/user/100/`, and so it matches equally in `--user 10 ` and in `/10/`.
     */
    private val userTokenTen = Regex("(^|\\D)10(\\D|$)")

    /**
     * Every builder declared in `PerUserCommands.kt`.
     *
     * `internal` top-level functions compile to public static methods on the file class, with the
     * module name mangled onto the end (`installCommand$app_fossDebug`), so the sweep matches on
     * shape rather than on names it would otherwise have to predict. Synthetic members are dropped
     * because Kotlin emits a `$default` bridge for every function with a default argument and it
     * takes a bitmask and a marker this fabricator has no business filling in — the name is checked
     * as well as the flag, because which of the two carries that bridge is a compiler detail. Private
     * helpers are dropped because they are not the file's contract.
     */
    private fun perUserBuilders(): List<Method> =
        Class.forName("com.valhalla.thor.data.source.local.PerUserCommandsKt")
            .declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) }
            .filterNot { it.isSynthetic || it.isBridge || it.name.contains("\$default") }
            .sortedBy { it.name }

    /** The Kotlin name, with the `internal` module-mangling suffix taken back off. */
    private fun Method.readableName(): String = name.substringBefore('$')

    /** One boolean combination's rendering, tagged with the combination that produced it. */
    private data class Rendering(val booleans: List<Boolean>, val text: String)

    /**
     * One rendering of [builder] per boolean combination, for [userId], in a stable order.
     *
     * Booleans are enumerated rather than defaulted: `setAppEnabledCommand` and
     * `backgroundRestrictionCommand` both pick between two verbs, and a builder that named the user
     * on only one branch would slip through a single-value render. The rows are returned separately
     * rather than concatenated because a join hands that hole straight back — one varying branch
     * carries the whole string, and the caller can no longer tell which branch it came from.
     */
    private fun renderEach(builder: Method, userId: Int): List<Rendering> =
        booleanCombinations(builder.parameterTypes.count { it == Boolean::class.javaPrimitiveType })
            .map { booleans ->
                val text =
                    when (val output = builder.invoke(null, *argumentsFor(builder, userId, booleans))) {
                        is String -> output
                        is List<*> -> output.joinToString(" ")
                        null -> throw AssertionError(
                            "${builder.readableName()} returned null. A command builder that can " +
                                "produce no command is not a shape this sweep can check."
                        )
                        else -> throw AssertionError(
                            "${builder.readableName()} returns a ${output.javaClass.simpleName}, " +
                                "which this sweep cannot read. Teach renderEach() about it."
                        )
                    }
                Rendering(booleans, text)
            }

    /**
     * Plausible arguments for [builder], with every `Int` parameter set to [userId].
     *
     * Setting *every* `Int` is the deliberate simplification: a builder with a second numeric
     * parameter would move both at once, which weakens the difference test into "some number
     * reaches the output" for that builder alone. No builder has one today, and the alternative —
     * guessing which parameter is the user from a name reflection does not reliably carry — would be
     * a worse kind of wrong.
     *
     * An unrecognised parameter type is a hard failure rather than a skipped builder. A sweep that
     * quietly stopped covering a builder because somebody added an enum to it would be the roster
     * problem again, wearing a `filter`.
     */
    private fun argumentsFor(builder: Method, userId: Int, booleans: List<Boolean>): Array<Any?> {
        if (builder.parameterTypes.none { it == Int::class.javaPrimitiveType }) {
            throw AssertionError(
                "${builder.readableName()} takes no user id at all. Everything in " +
                    "PerUserCommands.kt is a per-user builder by definition; a command that needs " +
                    "no user belongs beside its caller, not in this file."
            )
        }
        val arguments = arrayOfNulls<Any>(builder.parameterTypes.size)
        var nextBoolean = 0
        builder.parameterTypes.forEachIndexed { index, type ->
            arguments[index] = when {
                type == Int::class.javaPrimitiveType -> userId
                type == Boolean::class.javaPrimitiveType -> booleans[nextBoolean++]
                type == String::class.java -> pkg
                List::class.java.isAssignableFrom(type) -> listOf(baseApk)
                else -> throw AssertionError(
                    "${builder.readableName()} takes a ${type.simpleName}, which argumentsFor() " +
                        "cannot fabricate. Teach it that type — do not exclude the builder."
                )
            }
        }
        return arguments
    }

    /** Every assignment of [count] booleans, in a stable order. `count = 0` yields one empty row. */
    private fun booleanCombinations(count: Int): List<List<Boolean>> =
        (0 until (1 shl count)).map { mask ->
            (0 until count).map { bit -> (mask shr bit) and 1 == 1 }
        }
}
