// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A source-text sweep over the GH#445 grant: every place that builds an install command must decide
 * the `-g` question on purpose, and none may decide it by accident.
 *
 * [InstallSessionCommandsTest] proves what [installViaSessionCommand] *does* with the answer it is
 * given. Nothing there — and nothing anywhere reachable from a JVM test — proves that the five
 * callers hand it the right one. Each of those five needs a live root shell or a Shizuku/Dhizuku
 * binder to run, and [com.valhalla.thor.presentation.installer.InstallerViewModel] cannot be
 * constructed off-device at all: it takes a `PackageManager`, which is abstract, and this module
 * carries no mocking library. So the wiring is exactly the part with no behavioural coverage, and
 * it is also the part where the regressions are one-token edits that compile:
 *
 *  - dropping the `grantAllPermissions =` argument, which silently falls back to the parameter
 *    default and stops honouring the setting;
 *  - writing `grantAllPermissions == true` or `?: false` instead of
 *    `?: shouldGrantAllPermissionsOnInstall()`, which reads "nobody asked" as "the user said no"
 *    and overrides someone who had turned the setting *on*;
 *  - sending the installer's displayed value instead of the user's override, which turns a
 *    WhileSubscribed StateFlow's `false` initial value into an answer.
 *
 * None of those is subtle logic. All three would have passed the suite that shipped GH#445 — that
 * suite was green, and two of its assertions described the defect as correct behaviour.
 *
 * ### What it is worth, and what it is not
 *
 * It proves a shape, not a behaviour: that the argument is present and is not one of the known
 * wrong spellings. It cannot tell whether `-g` reaches `pm`, whether the shell accepts it, or what
 * the platform does with it. It will not survive a large refactor of these call sites either — and
 * that is intended. If the shape stops describing the code, somebody has to come back here and
 * decide again, deliberately, what each install rung asks for.
 *
 * ### Anti-vacuity
 *
 * A sweep that reads nothing is green and proves nothing, which is the failure mode of this whole
 * genre. Three guards stand against it, each its own test so that losing one is visible: the sweep
 * asserts *which* files it found call sites in (not merely that it found some), the forbidden-form
 * detector is run against a synthetic body that carries each wrong spelling, and the extractor is
 * pointed at a call that does not exist so that "found nothing" and "found everything" cannot look
 * alike.
 */
class InstallGrantCallSitesTest {

    /** How a call site is allowed to arrive at its answer. */
    private enum class Resolution {
        /** Takes a nullable answer and falls back to `shouldGrantAllPermissionsOnInstall()`. */
        FROM_SETTING,

        /**
         * Takes a required, non-nullable answer from its caller.
         *
         * For a class that cannot read the setting — no `PreferenceRepository`, no `suspend` — and
         * so would have to invent one. Requiring the answer pushes the decision to somebody who can
         * make it; defaulting it would answer for them, silently.
         */
        FROM_CALLER,
    }

    /**
     * Every file that may contain a call to [installViaSessionCommand], relative to
     * `src/main/java/com/valhalla/thor`, and how each is allowed to decide the grant.
     *
     * Asserted as a set equality rather than a lower bound: a *new* file building an install command
     * is precisely the event this list exists to interrupt. `data/source/local/
     * InstallSessionCommands.kt` is absent on purpose — it declares the function rather than calling
     * it, and is filtered out by name below.
     *
     * `ShizukuReflector.kt` is on this list because this sweep put it there. It builds an install
     * command and had no caller, so nobody noticed when the GH#445 fix turned the constant `-g` into
     * a parameter and left this one site taking the default — which is to say, silently ignoring the
     * setting. The fix that shipped in five places had missed a sixth. That is the entire argument
     * for a sweep over a hand-written list of call sites.
     */
    private val expectedCallSites = mapOf(
        "data/gateway/RootSystemGateway.kt" to Resolution.FROM_SETTING,
        "data/gateway/ShizukuSystemGateway.kt" to Resolution.FROM_SETTING,
        "data/gateway/DhizukuSystemGateway.kt" to Resolution.FROM_SETTING,
        "data/repository/InstallerRepositoryImpl.kt" to Resolution.FROM_SETTING,
        "data/source/local/shizuku/ShizukuReflector.kt" to Resolution.FROM_CALLER,
    )

    /**
     * Spellings that turn "the caller gave no answer" into "the caller said no".
     *
     * The parameter is `Boolean?` for one reason: `null` means the caller has nobody to ask and the
     * saved setting decides. Every form here erases that third state, and every one of them
     * compiles, type-checks and reads as reasonable at a glance.
     */
    private val forbiddenForms = listOf(
        "grantAllPermissions == true",
        "grantAllPermissions != true",
        "grantAllPermissions == false",
        "grantAllPermissions ?: false",
        "grantAllPermissions ?: true",
    )

    @Test
    fun `every install command names the grant explicitly`() {
        val sites = callSites()
        assertTrue("the sweep found no call sites at all", sites.isNotEmpty())

        for ((path, call) in sites) {
            assertTrue(
                "$path builds an install command without saying what to do about the permission " +
                    "grant, so it falls back to the parameter default and stops honouring the " +
                    "setting (GH#445):\n$call",
                call.contains("grantAllPermissions =")
            )
        }
    }

    @Test
    fun `the sweep read the files it claims to have read`() {
        assertEquals(
            "the set of files building an install command has changed; each new one has to decide " +
                "the GH#445 grant question deliberately, so add it here once you have checked it",
            expectedCallSites.keys,
            callSites().map { it.first }.toSet()
        )
    }

    @Test
    fun `no caller collapses a missing answer into a refusal`() {
        for (path in expectedCallSites.keys + VIEW_MODEL) {
            val source = stripComments(sourceOf(path))
            for (form in forbiddenForms) {
                assertFalse(
                    "$path writes `$form`. A null grantAllPermissions means nobody was asked, not " +
                        "that the user said no — this spelling overrides anyone who had turned the " +
                        "setting on. Resolve with `?: shouldGrantAllPermissionsOnInstall()`.",
                    source.contains(form)
                )
            }
        }
    }

    @Test
    fun `the resolution goes through the saved setting`() {
        // Every rung that can receive a null has to say what a null means, and there is exactly one
        // right answer. Checked per file rather than globally so that a rung which stopped
        // resolving — and started passing the raw nullable somewhere that reads it as false — is
        // named rather than hidden by its neighbours still doing it correctly.
        for ((path, resolution) in expectedCallSites) {
            if (resolution != Resolution.FROM_SETTING) continue
            val source = stripComments(sourceOf(path))
            assertTrue(
                "$path takes a nullable grant answer but never falls back to the saved setting",
                source.contains("?: preferenceRepository.shouldGrantAllPermissionsOnInstall()")
            )
        }
    }

    @Test
    fun `a site that cannot read the setting demands an answer instead of inventing one`() {
        for ((path, resolution) in expectedCallSites) {
            if (resolution != Resolution.FROM_CALLER) continue
            val source = stripComments(sourceOf(path))
            assertTrue(
                "$path no longer takes a required grant answer",
                source.contains("grantAllPermissions: Boolean,")
            )
            // A default is the whole failure mode: this class has no way to read the setting, so
            // any default it carries is an answer given on the user's behalf by whoever last edited
            // this line. `= false` reads as the cautious choice and is not — it disagrees with a
            // user who turned the setting on, and does so without saying anything.
            assertFalse(
                "$path defaults its grant answer. It cannot read the setting, so a default here " +
                    "answers for the user rather than deferring to them — make the caller say.",
                source.contains("grantAllPermissions: Boolean =") ||
                    source.contains("grantAllPermissions: Boolean? ")
            )
        }
    }

    @Test
    fun `the installer sends the user's answer, not the value on screen`() {
        val source = stripComments(sourceOf(VIEW_MODEL))

        assertFalse(
            "InstallerViewModel sends `grantAllPermissions.value` to the repository. That is a " +
                "WhileSubscribed StateFlow: with no collector it holds its `false` initial value, " +
                "so an install started without a live screen would claim the user declined. Send " +
                "`_grantAllOverride.value`, which is null until they actually answer.",
            source.contains("grantAllPermissions.value")
        )
        assertTrue(
            "InstallerViewModel no longer reads the per-install override",
            source.contains("_grantAllOverride.value")
        )
    }

    @Test
    fun `the installer never writes the setting`() {
        // The user's requirement, and not something the type system defends: the setter is on the
        // same repository the screen already injects, so writing it back is one line away and would
        // turn a one-off decision about one APK into the default for every install that follows.
        for (path in listOf(VIEW_MODEL, "presentation/installer/PortableInstaller.kt")) {
            assertFalse(
                "$path calls setGrantAllPermissionsOnInstall. The per-install checkbox is seeded " +
                    "from the setting and must never write back to it.",
                stripComments(sourceOf(path)).contains("setGrantAllPermissionsOnInstall")
            )
        }
    }

    // ---- anti-vacuity ----

    @Test
    fun `the sweep can actually fail`() {
        val missingArgument = """
            val command = installViaSessionCommand(
                apks = staged,
                userId = thorUserId,
                canDowngrade = canDowngrade,
                installerArg = installerArg,
            )
        """.trimIndent()
        val call = extractCall(missingArgument, 0)
        assertFalse(
            "the detector accepts a call with no grantAllPermissions argument, so the sweep proves " +
                "nothing",
            call.contains("grantAllPermissions =")
        )

        for (form in forbiddenForms) {
            assertTrue(
                "the detector does not notice `$form`",
                stripComments("val x = $form\n").contains(form)
            )
        }
    }

    @Test
    fun `the extractor reports nothing when there is nothing to find`() {
        assertTrue(
            "the extractor invents call sites, so an empty result and a full one look alike",
            occurrencesOf("thisFunctionDoesNotExist(", "val a = 1\nval b = 2\n").isEmpty()
        )
    }

    @Test
    fun `comment stripping does not read Thor's opinion of the code`() {
        // These files argue about `-g` at length in prose, including quoting the forbidden forms to
        // explain why they are wrong. A sweep that reads the comments would fail on the very
        // sentences warning against the bug.
        val prose = "// never write grantAllPermissions == true here\nval ok = 1\n"
        assertFalse(stripComments(prose).contains("grantAllPermissions == true"))
        assertTrue(stripComments(prose).contains("val ok = 1"))

        val block = "/* grantAllPermissions ?: false */\nval ok = 1\n"
        assertFalse(stripComments(block).contains("grantAllPermissions ?: false"))
        assertTrue(stripComments(block).contains("val ok = 1"))
    }

    // ---- machinery ----

    /** Every `installViaSessionCommand(` call in main source, as `relative path to call text`. */
    private fun callSites(): List<Pair<String, String>> =
        mainSourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != DECLARATION_FILE }
            .sortedBy { it.path }
            .flatMap { file ->
                val relative = file.relativeTo(mainSourceRoot).path
                val source = stripComments(file.readText())
                occurrencesOf(NEEDLE, source).map { relative to extractCall(source, it) }
            }
            .toList()

    private fun occurrencesOf(needle: String, source: String): List<Int> {
        val found = mutableListOf<Int>()
        var from = source.indexOf(needle)
        while (from >= 0) {
            found += from
            from = source.indexOf(needle, from + needle.length)
        }
        return found
    }

    /**
     * The call starting at [start], up to and including the parenthesis that closes its argument
     * list.
     *
     * Depth counting rather than a line budget: `installViaSessionCommand` is called with nested
     * calls in its arguments (`tempFiles.map { SessionApk(…) }`), so a fixed number of lines would
     * either truncate one site or run into the next statement. Comments are stripped before this
     * runs, so the only parentheses left are code; a `(` inside a string literal would fool it, and
     * none of these five calls contains one.
     */
    private fun extractCall(source: String, start: Int): String {
        val open = source.indexOf('(', start)
        if (open < 0) return source.substring(start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        return source.substring(start)
    }

    /**
     * [source] with `//` and `/* … */` comments removed.
     *
     * A few lines rather than a Kotlin lexer, and it would mishandle `//` inside a string literal —
     * none of the swept files has one on a line that mentions the grant, and
     * `the sweep read the files it claims to have read` would notice if stripping ever ate a real
     * call site.
     */
    private fun stripComments(source: String): String {
        val withoutBlocks = source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        return withoutBlocks.lines().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }

    private fun sourceOf(relativePath: String): String {
        val file = File(mainSourceRoot, relativePath)
        assertTrue("$relativePath does not exist at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /**
     * `src/main/java/com/valhalla/thor`, found by walking up from the working directory.
     *
     * Gradle runs unit tests from the module directory — `<repo>/app` — but nothing guarantees it;
     * Android Studio and `--tests` runs have both been seen starting a level up. Returning a
     * plausible-looking missing directory instead of throwing would turn every assertion in this
     * file into a green no-op, which is the precise failure this class exists to avoid.
     */
    private val mainSourceRoot: File by lazy {
        val marker = "src/main/java/com/valhalla/thor"
        val tried = mutableListOf<String>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile

        var hops = 0
        while (hops < 8) {
            val here = dir ?: break
            for (candidate in listOf(File(here, marker), File(here, "app/$marker"))) {
                if (candidate.isDirectory) return@lazy candidate
                tried += candidate.path
            }
            dir = here.parentFile
            hops++
        }

        throw AssertionError(
            "could not locate $marker from ${System.getProperty("user.dir")}; tried:\n" +
                tried.joinToString("\n")
        )
    }

    private companion object {
        const val NEEDLE = "installViaSessionCommand("
        const val DECLARATION_FILE = "InstallSessionCommands.kt"
        const val VIEW_MODEL = "presentation/installer/InstallerViewModel.kt"
    }
}
