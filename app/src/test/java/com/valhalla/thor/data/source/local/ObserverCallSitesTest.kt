// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A source-text sweep: every privileged clear-data / clear-cache call must hand
 * `PackageManagerService` a real `IPackageDataObserver`, and none may go back to passing `null`.
 *
 * This is the only test in the repo that reads `src/main` as text rather than calling it, so it is
 * worth being explicit about why. The three rewired call sites are unreachable from a JVM test —
 * each one needs a live `IPackageManager` binder — and [DataClearOutcomeTest] can only prove that
 * [awaitDataObserver] draws the right conclusion *once it is used*. Nothing enforces that it is
 * used. The regression this guards is not subtle logic, it is a one-token edit: `observer` back to
 * `null`, which compiles, runs, and restores exactly the "the binder call did not throw" success
 * this change exists to retire. A text sweep is a blunt instrument, but it is pointed at the one
 * thing that is genuinely at risk.
 *
 * ### What it is worth, and what it is not
 *
 * It proves a shape, not a behaviour. It cannot tell whether the observer is ever called, whether
 * the framework can marshal it, or whether R8 left the callback's name alone in release. It will
 * also not survive a large refactor of these functions — and that is intended: if the shape it
 * pins stops describing the code, somebody has to come back here and decide again, deliberately,
 * that the observer is still wired up.
 *
 * ### Anti-vacuity
 *
 * A sweep that reads nothing passes green and proves nothing, which is the failure mode of this
 * entire genre of test. Three separate guards stand against it, each its own test so that losing
 * one is visible: `the sweep read the files it claims to have read` proves the files and function
 * bodies were actually found and are actually the ones named; `the sweep can actually fail` runs
 * the same detector over a synthetic body carrying the old `null` and asserts it complains; and the
 * extractor is checked against a function name that does not exist, so that "found nothing" and
 * "found everything" cannot look alike.
 */
class ObserverCallSitesTest {

    /**
     * A function whose observer argument this test is responsible for.
     *
     * [control] is a substring known to sit inside *that function* and nowhere near the others —
     * the anti-vacuity guard for the body extraction, distinct from [frameworkMethods] so that the
     * guard is not simply asserting the thing under test twice.
     */
    private data class ClearSite(
        val relativePath: String,
        val functionName: String,
        val control: String,
        val frameworkMethods: List<String>,
    )

    /**
     * The two rungs that now wait for a verdict.
     *
     * There were three. `Shizuku.kt#clearCache` was deleted rather than kept honest: its
     * `deleteApplicationCacheFiles` reflection needed `INTERNAL_DELETE_CACHE_FILES`, which is
     * `protectionLevel="signature"` and therefore ungrantable to `com.android.shell`, so
     * PackageManagerService logged "Calling uid 2000 does not have
     * android.permission.INTERNAL_DELETE_CACHE_FILES, silently ignoring" and returned. The observer
     * was wired up correctly and waited on a verdict that never came. Per-app cache clearing is
     * root-only now; Shizuku gets `pm trim-caches`, which uses no observer at all.
     *
     * `RootSystemGateway` is not here and does not belong here: it mentions `IPackageDataObserver`
     * only in comments explaining why its cache clear goes through the shell instead. `DhizukuHelper`
     * is excluded deliberately, and the last test in this file is what keeps that exclusion honest.
     */
    private val verifiedSites = listOf(
        ClearSite(
            relativePath = "data/source/local/shizuku/Shizuku.kt",
            functionName = "clearAppData",
            control = "clearAppDataCommand(",
            frameworkMethods = listOf("clearApplicationUserData"),
        ),
        ClearSite(
            relativePath = "rootservice/ThorRootService.kt",
            functionName = "clearAppData",
            control = "android.os.ServiceManager",
            frameworkMethods = listOf("clearApplicationUserData"),
        ),
    )

    // -- the sweep itself ---------------------------------------------------------------------

    /**
     * The invariant: after the observer is created, every reflective call in that function is
     * handed it.
     *
     * "After the observer is created" is doing real work in that sentence. `ThorRootService`
     * legitimately calls `Method.invoke(null, …)` twice before it gets that far — `getService` and
     * `asInterface` are both static, and `null` is their receiver, not an observer. Scoping the
     * check to the text following `awaitDataObserver(` is what separates those from the argument
     * that matters, without needing to understand any of it.
     */
    @Test
    fun `every verified clear hands PackageManagerService a real observer`() {
        verifiedSites.forEach { site ->
            val body = bodyOf(site)

            site.frameworkMethods.forEach { method ->
                assertTrue(
                    "${site.functionName} in ${site.relativePath} no longer calls $method — the " +
                        "sweep is pointed at the wrong function, or the rung is gone",
                    body.contains("\"$method\"")
                )
            }

            val complaints = observerArgumentComplaints(body)
            assertTrue(
                "${site.relativePath}#${site.functionName}: ${complaints.joinToString("; ")}",
                complaints.isEmpty()
            )
        }
    }

    /**
     * The exact shape the fix removed must not come back by copy-paste.
     *
     * `null /* IPackageDataObserver */` is how the argument was spelled at four of the five sites,
     * comment and all, and it is still spelled that way in `DhizukuHelper` — which is precisely why
     * it is the thing most likely to be pasted back in while "making the gateways consistent". The
     * check is on the raw text including comments, unlike the sweep above, because here the comment
     * is the fingerprint.
     */
    @Test
    fun `the old null-observer spelling is gone from the verified files`() {
        verifiedSites.map { it.relativePath }.distinct().forEach { path ->
            val source = sourceOf(path)
            assertFalse(
                "$path still carries the pre-fix `null /* IPackageDataObserver */` argument",
                source.contains("null /* IPackageDataObserver */")
            )
        }
    }

    // -- anti-vacuity -------------------------------------------------------------------------

    /**
     * Proof that the sweep read what it says it read.
     *
     * Every assertion above is of the form "this text does not contain that", and all of them pass
     * trivially over an empty string. So: the source root resolves, each file is present and
     * substantial, each named function is found, each extracted body carries its own control
     * substring, and — the part that is easy to leave out — the extractor returns nothing for a
     * function that does not exist. Without that last one, an extractor that silently returned the
     * whole file, or the empty string, would look identical to one that worked.
     */
    @Test
    fun `the sweep read the files it claims to have read`() {
        assertTrue(
            "the main source root did not resolve to a directory: $mainSourceRoot",
            mainSourceRoot.isDirectory
        )

        assertEquals("the site list has been edited without updating this guard", 2, verifiedSites.size)

        verifiedSites.forEach { site ->
            val source = sourceOf(site.relativePath)
            assertTrue(
                "${site.relativePath} read as ${source.length} characters, which is not a file " +
                    "this sweep could have been reading",
                source.length > 2_000
            )
            assertTrue(
                "${site.relativePath} is missing Thor's SPDX header — this is not the file it " +
                    "was supposed to be",
                source.contains("SPDX-License-Identifier: GPL-3.0-or-later")
            )

            val body = bodyOf(site)
            assertTrue(
                "the extracted body of ${site.functionName} is ${body.length} characters long",
                body.length > 200
            )
            assertTrue(
                "the extracted body does not start with the signature of ${site.functionName}",
                body.trimStart().substringBefore('\n').contains("fun ${site.functionName}(")
            )
            assertTrue(
                "the extracted body of ${site.relativePath}#${site.functionName} does not contain " +
                    "\"${site.control}\" — the wrong region was extracted",
                body.contains(site.control)
            )
        }

        assertNull(
            "the extractor returned something for a function that does not exist, so \"found it\" " +
                "and \"found nothing\" are indistinguishable and every assertion above is worthless",
            extractFunctionBody(
                sourceOf("rootservice/ThorRootService.kt"),
                "clearAppDataThisFunctionDoesNotExist"
            )
        )
    }

    /**
     * Proof that the detector can say no.
     *
     * The regression is simulated rather than waited for: this is the `ThorRootService` rung as it
     * would read if somebody put the `null` back, and the sweep must complain about it. A sweep that
     * cannot be made to fail on demand is not evidence about the code, only about itself.
     *
     * The second half is the mirror image and matters just as much — a detector that complains about
     * everything is as useless as one that complains about nothing, so the fixed version of the same
     * body has to come back clean.
     */
    @Test
    fun `the sweep can actually fail`() {
        val regressed = """
            private fun clearAppData(packageName: String, userId: Int): Boolean {
                val outcome = runCatching {
                    awaitDataObserver("Odin", packageName) { observer ->
                        method.invoke(pm, packageName, null, userId)
                    }
                }
                return outcome == DataClearOutcome.CLEARED
            }
        """.trimIndent()

        assertFalse(
            "a body passing null in the observer position was swept clean",
            observerArgumentComplaints(regressed).isEmpty()
        )

        assertTrue(
            "the same body with the observer restored was still complained about, so the detector " +
                "does not discriminate",
            observerArgumentComplaints(
                regressed.replace("packageName, null, userId", "packageName, observer, userId")
            ).isEmpty()
        )
    }

    /**
     * `observerClass` is not an observer, and the detector must not be fooled by the prefix.
     *
     * Both Shizuku rungs pass a `Class` object called `observerClass` into `arrayOf(…)` as part of
     * the method signature, in the same argument list as the observer itself. A substring test for
     * "observer" would be satisfied by that alone, so the `null` regression would sweep clean at
     * exactly the two sites where it is most likely. The detector matches a standalone token; this
     * is the test that says so.
     */
    @Test
    fun `a lookalike identifier does not stand in for the observer`() {
        val lookalike = """
            fun clearCache(packageName: String): Boolean {
                awaitDataObserver("Shizuku", packageName) { observer ->
                    Bypass.invoke<Any?>(
                        pm.javaClass,
                        pm,
                        "deleteApplicationCacheFilesAsUser",
                        arrayOf(String::class.java, Int::class.javaPrimitiveType!!, observerClass),
                        packageName,
                        thorUserId,
                        null
                    )
                }
            }
        """.trimIndent()

        assertFalse(
            "an argument list containing observerClass and a null observer was accepted",
            observerArgumentComplaints(lookalike).isEmpty()
        )
    }

    // -- the deliberate exclusion -------------------------------------------------------------

    /**
     * `DhizukuHelper` still passes `null`, still says why, and is still outside this sweep.
     *
     * Its reflection rung was made honest rather than verified: on a Dhizuku-only device the call
     * dies inside a `ShizukuBinderWrapper` before it ever reaches `PackageManagerService`, so a real
     * observer would buy one guaranteed 15-second timeout per package — an always-red answer that
     * teaches nobody anything — and the rung now simply returns `false`. That is a decision, not an
     * oversight, and this test is what keeps the difference legible.
     *
     * One rung, not two. The other was `clearCache`, deleted along with Shizuku's for the same
     * reason: no privilege mode short of a platform signature can clear one package's cache through
     * `PackageManagerService`, so there was nothing left for it to be honest about.
     *
     * It is deliberately two-sided. If the comment marking the rung disappears, the reasoning has
     * been lost and this fails. If [awaitDataObserver] ever appears in that file, the transport has
     * been fixed and Dhizuku belongs in [verifiedSites] — which is also a failure, and the right
     * one: the sweep should widen rather than quietly not cover the new code.
     */
    @Test
    fun `Dhizuku is excluded on purpose, and says so`() {
        val dhizuku = sourceOf("data/source/local/dhizuku/Dhizuku.kt")
        val marker = "issued, and deliberately never believed"

        assertEquals(
            "DhizukuHelper's reflection rung no longer carries \"$marker\" — either it was " +
                "rewired, in which case add it to verifiedSites, or the reasoning was deleted",
            1,
            dhizuku.split(marker).size - 1
        )

        assertFalse(
            "DhizukuHelper now uses awaitDataObserver, so it is no longer excluded and must be " +
                "added to verifiedSites instead of being asserted about here",
            dhizuku.contains("awaitDataObserver")
        )
    }

    // -- machinery ----------------------------------------------------------------------------

    /**
     * `<module>/src/main/java/com/valhalla/thor`, found by walking up from wherever the runner
     * happened to start.
     *
     * Gradle runs unit tests with the working directory set to the module — `<repo>/app` — but
     * nothing guarantees that: Android Studio and `--tests` invocations have both been observed
     * starting a level up. Rather than hard-code either, this tries the marker path at each ancestor
     * both directly and under `app/`, and throws with everything it tried if none of them exist.
     * Returning a plausible-looking missing directory instead would turn every assertion in this
     * file into a green no-op, which is the precise failure this class is built to avoid.
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

    private fun sourceOf(relativePath: String): String {
        val file = File(mainSourceRoot, relativePath)
        assertTrue("$relativePath does not exist at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun bodyOf(site: ClearSite): String =
        extractFunctionBody(sourceOf(site.relativePath), site.functionName)
            ?: throw AssertionError(
                "no function named ${site.functionName} was found in ${site.relativePath}"
            )

    /**
     * The text of a member function, from its `fun` line to the `}` that closes it.
     *
     * The closing brace is found by indentation rather than by counting braces, and that is the
     * whole trick: a member of a top-level `class` or `object` closes on a line that is exactly four
     * spaces and a brace, while everything nested inside it is indented further. Brace counting
     * would have to understand string templates (`${…}` opens and closes one each, so it survives)
     * and comments (which do not), and this file only ever looks at three functions in a codebase
     * that is uniformly formatted. Returns `null` when the function is not there, which
     * `the sweep read the files it claims to have read` depends on.
     */
    private fun extractFunctionBody(source: String, functionName: String): String? {
        val signature = Regex("""^ {4}(?:\w+\s+)*fun\s+${Regex.escape(functionName)}\s*\(""")
        val lines = source.lines()

        val start = lines.indexOfFirst { signature.containsMatchIn(it) }
        if (start < 0) return null

        val end = (start + 1 until lines.size).firstOrNull { lines[it].trimEnd() == "    }" }
            ?: return null

        return lines.subList(start, end + 1).joinToString("\n")
    }

    /**
     * Everything wrong with the observer arguments in [body], as sentences; empty means clean.
     *
     * Comments are stripped first, and not out of tidiness: the prose around these rungs discusses
     * `invoke`, `observer` and `null` at length, and a sweep that reads it is reading Thor's opinion
     * of the code rather than the code. The stripper is a few lines rather than a Kotlin lexer — it
     * would mishandle a `//` inside a string literal, which none of the three bodies contains and
     * the control substrings would catch if one appeared.
     */
    private fun observerArgumentComplaints(body: String): List<String> {
        val code = stripComments(body)
        val dispatchAt = code.indexOf("awaitDataObserver(")
        if (dispatchAt < 0) return listOf("no awaitDataObserver( call — the observer is never created")

        val arguments = invocationArgumentsIn(code.substring(dispatchAt))
        if (arguments.isEmpty()) {
            return listOf("awaitDataObserver( is called but nothing is invoked inside it")
        }

        // Whole-token matches, which is the difference between reading `observer` and reading the
        // `observerClass` that sits two arguments away from it in both Shizuku rungs. The argument
        // text is padded rather than anchored so that a token at either end still has a delimiter to
        // match against.
        val observerToken = Regex("""[(,\s]observer[,)\s]""")
        val nullArgument = Regex("""[(,\s]null[,)\s]""")

        return arguments.flatMap { args ->
            val padded = " $args "
            val oneLine = args.replace(Regex("""\s+"""), " ").trim()
            buildList {
                if (!observerToken.containsMatchIn(padded)) {
                    add("an invoke after the observer was created does not pass it: ($oneLine)")
                }
                if (nullArgument.containsMatchIn(padded)) {
                    add("an invoke after the observer was created still passes null: ($oneLine)")
                }
            }
        }
    }

    /**
     * The argument text of every `invoke(…)` in [code], paren-balanced.
     *
     * Balanced rather than line-windowed because the real call sites wrap their arguments over eight
     * lines and nest an `arrayOf(…)` in the middle of them, so any fixed window is either too short
     * to reach the observer or long enough to swallow the next statement.
     */
    private fun invocationArgumentsIn(code: String): List<String> {
        val found = mutableListOf<String>()
        var from = 0

        while (true) {
            val at = code.indexOf("invoke", from)
            if (at < 0) break
            val open = code.indexOf('(', at)
            if (open < 0) break

            var depth = 0
            var i = open
            while (i < code.length) {
                if (code[i] == '(') depth++
                if (code[i] == ')') {
                    depth--
                    if (depth == 0) break
                }
                i++
            }
            if (i >= code.length) break

            found += code.substring(open + 1, i)
            from = i + 1
        }

        return found
    }

    private fun stripComments(source: String): String =
        source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines()
            .joinToString("\n") { line ->
                val cut = line.indexOfLineComment()
                if (cut < 0) line else line.substring(0, cut)
            }

    private fun String.indexOfLineComment(): Int {
        var quotes = 0
        var i = 0
        while (i < length - 1) {
            val c = this[i]
            if (c == '\\') {
                // Skip whatever it escapes, so an escaped quote inside a string literal does not
                // flip the parity and hand the rest of the line to the stripper.
                i += 2
            } else {
                if (c == '"') quotes++
                if (c == '/' && this[i + 1] == '/' && quotes % 2 == 0) return i
                i++
            }
        }
        return -1
    }
}
