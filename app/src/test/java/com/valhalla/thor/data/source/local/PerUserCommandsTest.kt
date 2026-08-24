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
 * [uninstallCommand]?" is unanswerable here and is deliberately not faked.
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

    /**
     * A digit-free APK path, so a `10` in an assertion can only be the user id. Its one remaining
     * use is the reflective fabricator below, which needs *some* `List<String>` to pass; the split
     * path and the installer argument that used to sit beside it went with `installCommand`.
     */
    private val baseApk = "/data/local/tmp/base.apk"

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
     * The list is the three branches of `InstalldNativeService::clearAppData` under
     * `FLAG_CLEAR_CACHE_ONLY` — CE, DE, external — and this pins all three in order.
     *
     * The CE and external entries are also what `/data/data/<pkg>/cache` and
     * `/sdcard/Android/data/<pkg>/cache` resolve to at user 0, which is the other half of the
     * alias claim above: on a single-user device those two commands are byte-for-byte unchanged.
     */
    @Test
    fun `the paths are installd's three cache-only branches, in order`() {
        assertEquals(
            listOf(
                "/data/user/0/$pkg/cache",
                "/data/user_de/0/$pkg/cache",
                "/storage/emulated/0/Android/data/$pkg/cache",
            ),
            clearCachePaths(pkg, 0)
        )
    }

    /**
     * The device-encrypted directory is the entry this list spent its whole life missing, so it
     * gets an assertion that fails for a reason rather than only inside a list comparison. PMS
     * creates a `user_de` package directory for every installed app, so this is not a direct-boot
     * special case — omitting it left real cache behind while reporting the clear complete.
     */
    @Test
    fun `the device-encrypted cache directory is covered`() {
        assertTrue(
            "no /data/user_de path — device-encrypted cache would survive the clear",
            clearCachePaths(pkg, 10).any { it == "/data/user_de/10/$pkg/cache" }
        )
    }

    /**
     * `code_cache` is a different installd flag (`FLAG_CLEAR_CODE_CACHE_ONLY`) that Settings' Clear
     * cache does not touch, and it holds compiled artifacts apps expect to persist. Freeing more
     * bytes is not a reason to diverge from the platform, so this pins the omission as deliberate.
     */
    @Test
    fun `code_cache is deliberately not cleared`() {
        assertTrue(clearCachePaths(pkg, 0).none { it.contains("code_cache") })
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

    // `installCommand` used to be tested here, across ten cases. It is gone, and so are they —
    // its invariants moved to `InstallSessionCommandsTest` along with the builder that replaced it,
    // `installViaSessionCommand`. That file asserts the same `--user`, the same constant `-r -g`,
    // the same opt-in `-d`, the same installer-argument fusion guard, the same path ordering and the
    // same refusal of an empty set.
    //
    // Worth recording why the move happened, because these tests were green the whole time. They
    // pinned `pm install-multiple --user 10 -r -g …` exactly, character for character — and
    // `install-multiple` is not a verb `pm` has ever implemented. A test that pins the wrong command
    // precisely is indistinguishable, from the outside, from one that pins the right command
    // precisely; the assertion cannot tell you which it did. What that assertion needed was the one
    // question a string test cannot ask, so `InstallSessionCommandsTest` asks it the only way
    // available: negatively, by asserting the verb appears nowhere at all.

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

    @Test
    fun `the installed apps appops grant names the user and covers OEM op names`() {
        val commands = installedAppsAppOpGrantCommands(pkg, 10)
        assertEquals(3, commands.size)
        commands.forEach { cmd ->
            assertEquals(10, userArgOf(cmd))
            assertTrue(cmd.endsWith(" allow"))
        }
        assertEquals("appops set --user 10 $pkg GET_INSTALLED_APPS allow", commands[0])
        assertEquals("appops set --user 10 $pkg android:get_installed_apps allow", commands[1])
        assertEquals("appops set --user 10 $pkg 10022 allow", commands[2])
    }

    /**
     * The revoke exists at all because `pm revoke` does not undo the grant: an app-op left at
     * `allow` keeps answering `MODE_ALLOWED` after the runtime permission is gone, so package
     * visibility stayed open while Thor reported the revoke as successful.
     *
     * `default`, not `ignore` — asserted rather than implied, because `ignore` is the plausible
     * wrong answer. It pins the op to hard-denied independently of the permission, and an explicit
     * app-op mode is not something the ROM's own permission toggle can lift, so a later grant from
     * Settings would look applied and do nothing. `default` hands the answer back to the platform,
     * where the accompanying `pm revoke` has already denied the permission.
     */
    @Test
    fun `the installed apps appops revoke resets the same three ops to default`() {
        val commands = installedAppsAppOpRevokeCommands(pkg, 10)
        assertEquals(3, commands.size)
        commands.forEach { cmd ->
            assertEquals(10, userArgOf(cmd))
            assertTrue("$cmd does not reset the op to the platform default", cmd.endsWith(" default"))
            assertFalse("$cmd hard-denies the op instead of resetting it", cmd.endsWith(" ignore"))
        }
        assertEquals("appops set --user 10 $pkg GET_INSTALLED_APPS default", commands[0])
        assertEquals("appops set --user 10 $pkg android:get_installed_apps default", commands[1])
        assertEquals("appops set --user 10 $pkg 10022 default", commands[2])
    }

    /**
     * The pair has to name the *same* ops, in the same order, or a revoke leaves whichever spelling
     * the grant used and the ROM answers to still set to `allow` — the exact defect the revoke was
     * added to close, reintroduced by a one-line edit to either list. Both builders read the same
     * private spelling list today; this is what makes that a requirement rather than a coincidence.
     */
    @Test
    fun `grant and revoke cover the same ops in the same order`() {
        val opOf = { command: String -> command.removePrefix("appops set --user 10 $pkg ").substringBefore(' ') }

        assertEquals(
            installedAppsAppOpGrantCommands(pkg, 10).map(opOf),
            installedAppsAppOpRevokeCommands(pkg, 10).map(opOf)
        )
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
     * tick. Naming the seven builders that existed when it was written proves it is looking at the
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
     * module name mangled onto the end (`uninstallCommand$app_fossDebug`), so the sweep matches on
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
