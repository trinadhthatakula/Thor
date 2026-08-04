// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.shizuku

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method

/**
 * A lock on what [Packages] is allowed to be: observers only, never an unprivileged mutator.
 *
 * `forceStopApp`, `setAppDisabled` and `setAppRestricted` were deleted from this class as one edit,
 * and they had to be one edit rather than three. `setAppDisabled` was the **only** caller of
 * `forceStopApp`, and `setAppDisabled` itself had **no** callers anywhere in the repo — so removing
 * `forceStopApp` alone leaves a dangling call that does not compile, and removing `setAppDisabled`
 * alone leaves `forceStopApp` dead with nothing pointing at it. Three functions, one deletion.
 *
 * *Why* they went matters more than the ordering, and is what this test is really defending. All
 * three were `Bypass` reflection performed from Thor's own uid with no privilege behind it, wrapped
 * in `runCatching { ...; true }` — so they answered **true whenever the call merely did not throw**.
 * A refusal that returns quietly and a stop that actually happened produce the identical `true`, and
 * a caller has no way to tell them apart. That is precisely the class of lie the privileged gateways
 * (`Shizuku`, `Dhizuku`, `RootSystemGateway`) exist to avoid by re-reading state after acting, and
 * it is exactly what a well-meaning future edit would re-introduce here by reaching for the
 * "obvious" missing setter next to `isAppDisabled`.
 *
 * The tombstone comment left in `Packages.kt` says the same thing in prose. This test is the part
 * that fails a build.
 *
 * It does that from two directions, because neither one is enough alone. Reflection is exact about
 * what [Packages] declares and inherits, and completely blind to a name that compiles into some
 * *other* class — which is what an extension function, a companion member and a `@JvmName` rename
 * all do, while leaving the call site reading `packages.forceStopApp(pkg)` unchanged. So the second
 * direction reads the source text, where those three are visible and only there. Each direction
 * carries its own anti-vacuity guard, because "assert this name is absent" is the one test shape
 * that passes best when it is looking at nothing at all.
 */
class PackagesSurfaceTest {

    /**
     * The three names, hoisted because two tests check for them and a list that can drift between
     * them is worse than no list at all.
     */
    private val deletedNames = setOf("forceStopApp", "setAppDisabled", "setAppRestricted")

    /**
     * The reflection lock: none of the three is on the class's runtime surface.
     *
     * Matched on the de-mangled name so that a member re-added with a different visibility — Kotlin
     * mangles an `internal` member's JVM name with a module suffix, e.g.
     * `setAppRestricted$app_fossDebug` — cannot slip past a plain string comparison. See
     * [readableName] for the second mangling, which nothing on this class exercises today and which
     * must survive anyway.
     */
    @Test
    fun `the unprivileged mutators are gone`() {
        val names = surfaceNames()

        for (deleted in deletedNames) {
            assertTrue(
                "Packages.$deleted is back. It was deleted because unprivileged Bypass reflection " +
                    "reports success whenever the call did not throw, which is indistinguishable " +
                    "from a silent refusal — and because these three only ever called each other. " +
                    "If a caller now needs this operation, it belongs on a privileged gateway that " +
                    "verifies it by reading the state back, not here. Visible members: $names",
                deleted !in names
            )
        }
    }

    /**
     * The anti-vacuity floor under the assertion above, and the only reason to trust it.
     *
     * "Assert some names are absent" is the one shape of test that passes perfectly when it is
     * looking at nothing at all: rename or relocate the class, have the reflection come back empty
     * for any reason, and every `deleted !in names` check above is trivially satisfied with a green
     * tick. Naming members that are known to still exist proves the reflection resolved a real class
     * with real methods on it.
     *
     * `containsAll`, never an equality — adding an observer to [Packages] must not have to be
     * remembered here. The four named are the ones the privileged paths actually call this class
     * for: `Shizuku`/`Dhizuku` read `getApplicationInfoOrNull` and `isAppStopped` to judge their own
     * rungs, `ShizukuReflector` reads `isAppUninstalled`, and `isAppDisabled` is the canonical freeze
     * predicate shared with `AppFreezeStateReader`.
     *
     * Where this guard is weaker than its sibling in `SystemRepositorySurfaceTest`, stated plainly
     * so nobody reads more into a green tick than it earned: all four names return plain types, so
     * none of them travels through the `-<hash>` strip in [readableName], and this test would still
     * pass with that strip deleted. There is no member of [Packages] to pin it to — `javap` on the
     * compiled class shows every method here emitted unmangled. The strip's justification therefore
     * lives in prose on [readableName] rather than in an assertion, and that is a known limit of
     * this file, not an oversight.
     */
    @Test
    fun `the reflection actually sees the class`() {
        val names = surfaceNames()

        assertTrue(
            "the sweep found $names, which is missing observers that are known to exist — it is " +
                "looking at the wrong class, or the reflection returned nothing and the absence " +
                "assertions above are passing vacuously",
            names.containsAll(
                setOf(
                    "getApplicationInfoOrNull",
                    "isAppDisabled",
                    "isAppStopped",
                    "isAppUninstalled",
                )
            )
        )
    }

    /**
     * The source-text lock, covering the three re-adds reflection structurally cannot see.
     *
     * `fun Packages.forceStopApp(pkg: String)` compiles to `PackagesKt.forceStopApp`, a different
     * class entirely, so [surfaceNames] never sees it — while every call site still reads
     * `packages.forceStopApp(pkg)`, exactly as before the deletion. That is not an exotic dodge; it
     * is how a helper "next to `isAppStopped`" gets written once the class itself looks closed. Two
     * more land in the same blind spot: a companion member without `@JvmStatic`, which lands on
     * `Packages$Companion`, and `@JvmName("stopApp")` on an otherwise straight re-add, where
     * reflection sees `stopApp` and Kotlin callers still write `forceStopApp`.
     *
     * Two patterns, both matched on declaration shape rather than the bare name — the tombstone
     * comment in `Packages.kt` names all three functions in prose and must not trip this:
     *  - inside `Packages.kt`, any `fun … <name>(`, which covers a member, a companion member, a
     *    top-level function in that file, and a `@JvmName`d one, because the Kotlin name survives in
     *    the source whatever the JVM name becomes;
     *  - anywhere in the production source sets, any `fun … Packages.<name>(`, which covers an
     *    extension on the class or on its companion, in any file and any package.
     *
     * Comments are deliberately not excluded. A commented-out `fun forceStopApp(` sitting in
     * `Packages.kt` is a re-add waiting for someone to delete two slashes, and the tombstone there
     * describes the deletion in prose without quoting a signature — it should stay that way.
     *
     * The guards run first and are the whole reason to believe the assertions after them. A sweep
     * over an empty file list satisfies every absence check it is given, which is the same failure
     * the reflection guard exists to catch, one layer down: a working directory that is not what
     * this test assumed, a moved module, a `listFiles()` that came back null. So the corpus must be
     * more than a handful of files, must contain `Packages.kt` itself, must yield a hit for a
     * declaration known to be in that file, and the extension pattern must be shown to match the
     * shape it was written for and to have real extension declarations in the corpus to find.
     *
     * The boundary, stated rather than implied. This is a regex over text, not a parse. A name
     * re-added as a function-typed property (`val forceStopApp: (String) -> Boolean`), reached
     * through a `typealias` for [Packages], or produced by a compiler plugin, goes straight through
     * both locks. Those were judged not worth a parser: none of them is how this mistake actually
     * gets made, and each would be a deliberate act rather than an autocomplete.
     */
    @Test
    fun `no source declaration brings the names back`() {
        val sources = productionKotlinSources()

        assertTrue(
            "the sweep read only ${sources.size} Kotlin files, which cannot be Thor's production " +
                "source tree — the working directory is not what this test assumed, or app/src " +
                "moved. Every absence check below would pass on a corpus this small",
            sources.size >= 20
        )

        val packagesSource = sources.entries
            .firstOrNull { it.key.invariantSeparatorsPath.endsWith(PACKAGES_KT) }
            ?.value
            .orEmpty()
        assertTrue(
            "the sweep never read $PACKAGES_KT, so the in-file assertions below are looking at an " +
                "empty string and pass no matter what that file says",
            packagesSource.isNotEmpty()
        )
        assertTrue(
            "the declaration pattern cannot find `fun isAppDisabled(` in $PACKAGES_KT, which is " +
                "certainly there — the pattern no longer matches how this codebase declares a " +
                "function, so its failure to find the deleted three proves nothing",
            declarationOf("isAppDisabled").containsMatchIn(packagesSource)
        )
        assertTrue(
            "the extension pattern does not match the declaration it was written for — it can " +
                "never fire, and the tree-wide assertion below is decoration",
            deletedNames.all {
                extensionOnPackages(it).containsMatchIn("fun Packages.$it(pkg: String) = true")
            }
        )
        assertTrue(
            "no file in the corpus declares an extension function at all, which is not true of " +
                "this codebase — the corpus is not the source tree, or extensions are no longer " +
                "written as `fun Receiver.name(` and the tree-wide pattern is looking for a shape " +
                "that stopped existing",
            sources.values.any { ANY_EXTENSION.containsMatchIn(it) }
        )

        for (deleted in deletedNames) {
            assertTrue(
                "$PACKAGES_KT declares `fun $deleted(` again. Whether it is a member, a companion " +
                    "member or renamed on the JVM with @JvmName, Kotlin callers reach it as " +
                    "Packages.$deleted and it is the unprivileged mutator this class deleted. It " +
                    "belongs on a privileged gateway that verifies the operation, not here",
                !declarationOf(deleted).containsMatchIn(packagesSource)
            )

            val offenders = sources.filterValues { extensionOnPackages(deleted).containsMatchIn(it) }
                .keys
                .map { it.invariantSeparatorsPath }
            assertTrue(
                "an extension function puts Packages.$deleted back: $offenders. It compiles into a " +
                    "different class, so the reflection lock in this file cannot see it, but every " +
                    "call site reads packages.$deleted(...) exactly as it did before the deletion. " +
                    "An unprivileged mutator is no less unprivileged for being an extension",
                offenders.isEmpty()
            )
        }
    }

    /**
     * Every method [Packages] exposes, by Kotlin name: what it declares itself at any visibility,
     * plus what it inherits.
     *
     * `declaredMethods` alone sees only methods declared on this exact class. Moving a name onto a
     * superclass takes it out of that view entirely while `packages.forceStopApp(pkg)` keeps
     * compiling at every call site, so `methods` is unioned in to close that. Additive rather than a
     * replacement, because the two see different things: `methods` is public-only, `declaredMethods`
     * is visibility-blind, and a `private` or `protected` re-add appears in exactly one of them.
     *
     * `java.lang.Object`'s own methods arrive with `methods` on a class and are dropped — they are
     * not this class's surface and would bury the real names in the failure message. Verified rather
     * than assumed: on a plain class `getMethods()` returns `equals`, `hashCode`, `toString`,
     * `getClass`, `notify`, `notifyAll` and three `wait` overloads, all with `declaringClass ==
     * Object`, and filtering on that leaves exactly the class's own public members.
     *
     * A class literal rather than a `Class.forName` string, so a rename is a compile error here
     * instead of a silently empty result set. Synthetic and bridge members are dropped because
     * Kotlin emits a `$default` bridge for every function with a default argument —
     * `getInstalledApplications`, `getUnhiddenPackageInfoOrNull` and `getApplicationInfoOrNull` each
     * have one — and those are a compiler detail, not this class's surface.
     */
    private fun surfaceNames(): Set<String> {
        val type = Packages::class.java
        return (type.declaredMethods.asSequence() + type.methods.asSequence())
            .filterNot { it.declaringClass == Any::class.java }
            .filterNot { it.isSynthetic || it.isBridge || it.name.contains("\$default") }
            .map { it.readableName() }
            .toSet()
    }

    /**
     * Every Kotlin file in the production source sets, as text, keyed by file.
     *
     * `app/src` minus `test` and `androidTest`, rather than a hardcoded `main` plus flavour list: a
     * new flavour directory gets swept the day it appears, and — the part that matters — this file
     * is excluded, which it has to be. Its own KDoc spells out `fun Packages.forceStopApp(...)` as
     * the example of the thing it is hunting for, and only a production declaration is a defeat
     * anyway. Widening this to the test source sets would make the sweep fail on its own prose.
     *
     * The root is found by walking up from the working directory, because a unit test's working
     * directory is the Gradle module in one runner and the repository root in another, and neither
     * is a thing this test should be encoding. `error()` rather than an empty map when the walk
     * finds nothing: a sweep that cannot locate its own sources has to fail loudly, not quietly
     * agree that there are no offenders.
     */
    private fun productionKotlinSources(): Map<File, String> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val src = File(dir, "app/src")
            if (src.isDirectory) {
                return src.listFiles().orEmpty()
                    .filter { it.isDirectory && it.name != "test" && it.name != "androidTest" }
                    .flatMap { root ->
                        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                    }
                    .associateWith { it.readText() }
            }
            dir = dir.parentFile
        }
        error(
            "could not locate app/src by walking up from ${System.getProperty("user.dir")}. The " +
                "source sweep has nothing to read and must not report a clean result"
        )
    }

    /**
     * `fun … <name>(` on a single line: any modifiers, any annotations, any receiver, and no
     * intervening `(` — which is what keeps `fun other(x) = name(y)` from matching, since the
     * character class cannot cross the first parenthesis.
     */
    private fun declarationOf(name: String) = Regex("""\bfun\b[^(\n]*\b$name\s*\(""")

    /**
     * `fun … Packages.<name>(` — an extension on the class, and via the second wildcard on
     * `Packages.Companion` too.
     */
    private fun extensionOnPackages(name: String) =
        Regex("""\bfun\b[^(\n]*\bPackages\s*\.[^(\n]*\b$name\s*\(""")

    /**
     * The Kotlin name, with both JVM manglings taken back off: the `$module` suffix `internal` adds
     * and the `-<hash>` suffix an inline-value-class return type adds. Neither character is legal in
     * a source-level Kotlin identifier without backticks, so cutting at the first of either cannot
     * truncate a real method name.
     *
     * **Do not delete the second strip on the grounds that nothing on [Packages] needs it.** Nothing
     * here does — `javap` on the compiled class shows every member emitted unmangled, because they
     * all return plain types — which means the guard above cannot pin itself to a mangled member and
     * this file stays green with the strip removed. That is the reason it has to stay, not a reason
     * to drop it. The strip is not protecting today's surface; it is protecting the absence
     * assertions against tomorrow's. The day someone re-adds `forceStopApp` returning
     * `Result<Boolean>` — the obvious shape for a mutator, and the shape the rest of Thor already
     * uses — it is emitted as `forceStopApp-gIAlu-s`, `"forceStopApp" !in names` is trivially true,
     * and this test reports a clean surface while the thing it exists to stop ships.
     *
     * That is evidence, not theory. The sibling `SystemRepositorySurfaceTest` was written without
     * this strip; every method on that interface is `Result`-returning, so every name de-mangled to
     * nothing that matched, and its own anti-vacuity guard caught it on the first run. Its guard can
     * pin itself to a mangled member and permanently prove the strip works. This one cannot, so this
     * paragraph is what stands in for the assertion that file gets to make.
     */
    private fun Method.readableName(): String = name.substringBefore('$').substringBefore('-')

    private companion object {
        /** Path suffix of the anchor file, matched with `/` so it also holds on Windows. */
        const val PACKAGES_KT = "com/valhalla/thor/data/source/local/shizuku/Packages.kt"

        /**
         * Any extension declaration at all, used only as a control: it proves the corpus really is
         * Kotlin source in which extensions are still written as `fun Receiver.name(`, which is the
         * shape [extensionOnPackages] pins itself to.
         */
        val ANY_EXTENSION = Regex("""\bfun\b[^(\n]*\b\w+\s*\.\s*\w+\s*\(""")
    }
}
