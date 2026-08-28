// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.data.repository.resultPreservingCancellation
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.presentation.FakeSystemRepository
import java.io.File
import java.util.concurrent.CancellationException
import java.lang.reflect.Method
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A lock on the one method [SystemRepository] is not allowed to grow back.
 *
 * `aggressiveCleanup(packageName)` was declared here and implemented in `SystemRepositoryImpl` as
 * force-stop followed by clear-cache. It discarded **both** `Result`s and returned
 * `Result.success(Unit)` unconditionally — a composite that reports success no matter what either of
 * its two steps did. It had zero production callers: the only references in the whole repo were this
 * declaration, that implementation, and three test overrides which existed purely because the
 * interface forced them. Not one of them exercised the behaviour.
 *
 * It was deleted rather than fixed, and the interface declaration went with it. An interface method
 * with no implementations and no callers is not a harmless stub — it is a shape that reads like an
 * unfinished feature, and the next sweep implements it. This test is cheap insurance against the
 * cheapest way for it to return: an IDE autocomplete on a repository that "obviously" ought to have
 * a cleanup composite.
 *
 * The two operations remain available individually — `forceStopApp` and `clearCache` — each
 * returning its own real `Result`, which is what a caller that genuinely wants both should use so it
 * can see which half failed.
 *
 * Two locks, because neither is enough alone. Reflection is exact about what the interface declares
 * and inherits and blind to a name that compiles into some other class — which is what an extension
 * function does, while `systemRepository.aggressiveCleanup(pkg)` keeps reading the same at every
 * call site. The source sweep covers that. Each lock carries its own anti-vacuity guard, because
 * "assert this name is absent" is the one test shape that passes best when it is looking at nothing.
 */
class SystemRepositorySurfaceTest {

    @Test
    fun `package and shell methods preserve the complete execution context`() = runTest {
        val execution = PrivilegeExecutionContext(
            lane = PrivilegeExecutionLane.SWEEP,
            commandClass = PrivilegeCommandClass("sweep.structural"),
            packageName = "com.example.target",
            workRequestId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            sweepRequestId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            commandTimeout = 17.seconds,
        )
        val repository = FakeSystemRepository()

        repository.isRootAvailable(execution)
        repository.clearAllCaches(execution)
        repository.rebootDevice("review", execution)
        repository.probeObb("com.example.target", execution)
        repository.setAppDisabled("com.example.target", true, execution)
        repository.setAppSuspended("com.example.target", true, execution)
        repository.forceStopApp("com.example.target", execution)
        repository.clearCache("com.example.target", execution)
        repository.reinstallAppWithGoogle("com.example.target", execution)
        repository.executeShellCommand("true", execution)

        assertEquals(
            List(10) { execution },
            repository.executions.map { it.second },
        )
    }

    /**
     * Matched on the de-mangled name, and there are **two** manglings in play here — the second one
     * is why the guard below exists and it caught this on the first run.
     *
     * Kotlin mangles an `internal` member's JVM name with a module suffix
     * (`aggressiveCleanup$app_fossDebug`), so a raw `Method.name` comparison could be dodged by
     * re-adding the method with a narrower visibility. It *also* mangles any function returning an
     * inline value class with a `-<hash>` suffix, and `Result` is one: every method on this
     * interface is emitted as `forceStopApp-gIAlu-s`, `setAppDisabled-0E7RQCE` and so on.
     * `aggressiveCleanup` returned `Result<Unit>` too, so against raw names the assertion below was
     * green whether or not the method was there.
     */
    @Test
    fun `ShellCommandCancelled remains structured cancellation`() = runTest {
        val cancellation = ShellCommandCancelled(
            PrivilegeCommandClass("sweep.cancelled"),
            CancellationException("cancelled by caller"),
        )

        val caught = try {
            resultPreservingCancellation<Unit> { throw cancellation }
            null
        } catch (actual: CancellationException) {
            actual
        }

        assertSame(cancellation, caught)
    }

    @Test
    fun `CancellationException returned inside Result failure remains structured cancellation`() =
        runTest {
            val cancellation = ShellCommandCancelled(
                PrivilegeCommandClass("archive.cancelled"),
                CancellationException("cancelled by caller"),
            )

            val caught = try {
                resultPreservingCancellation<Unit> { Result.failure(cancellation) }
            null
        } catch (actual: CancellationException) {
            actual
        }

        assertSame(cancellation, caught)
    }

    @Test
    fun `aggressiveCleanup is gone`() {
        val names = surfaceNames()

        assertTrue(
            "SystemRepository.aggressiveCleanup is back. It was deleted because it discarded the " +
                "Results of both operations it composed and then reported unconditional success, " +
                "and because nothing called it. If a caller now needs force-stop plus clear-cache, " +
                "it should call forceStopApp and clearCache and decide for itself what a partial " +
                "failure means. Visible methods: $names",
            "aggressiveCleanup" !in names
        )
    }

    /**
     * The anti-vacuity floor under the assertion above.
     *
     * An absence assertion is the one shape that passes best when it is looking at nothing: the
     * reflection coming back empty — a moved interface, a change in how Kotlin emits `suspend`
     * members, anything — satisfies `"aggressiveCleanup" !in names` trivially and green. Naming
     * methods that are known to still be declared proves the reflection resolved a real interface
     * with real members on it.
     *
     * `containsAll`, never an equality: adding a capability to [SystemRepository] must not have to be
     * remembered here. The two named are the exact pair `aggressiveCleanup` used to compose, which
     * makes this double as the record that deleting the composite did not take the parts with it.
     *
     * Both of them return `Result`, and that is load-bearing rather than incidental: it is the same
     * signature shape `aggressiveCleanup` had, so this passing means [readableName] survives the
     * exact mangling the deleted method would have been hiding behind. Do not swap either for a
     * plain-returning member — it would still read as an anti-vacuity check while no longer
     * exercising the one thing that can make the assertion above vacuous.
     */
    @Test
    fun `the reflection actually sees the interface`() {
        val names = surfaceNames()

        assertTrue(
            "the sweep found $names, which is missing methods that are known to be declared — it " +
                "is looking at the wrong type, or the reflection returned nothing and the " +
                "absence assertion above is passing vacuously",
            names.containsAll(setOf("forceStopApp", "clearCache"))
        )
    }

    /**
     * A presence lock, the mirror of the absence lock above.
     *
     * `probeObb` is the seam three consumers depend on — the export sheet's chip gating, the bundle
     * builder's pack step and the app-info OBB card — and none of those can be unit-tested on the
     * JVM (they need gateways, and `rikka.shizuku.Shizuku`'s static initialiser throws "not
     * mocked"). This reflection check is the cheapest thing that fails if the method is renamed or
     * dropped, and it reuses [surfaceNames]'s de-mangling, so a `Result`-returning or `internal`
     * redeclaration would not slip past it.
     *
     * It rides on the anti-vacuity guard above rather than repeating one: the same [surfaceNames]
     * call that has to see `forceStopApp` and `clearCache` is the one asked about `probeObb` here,
     * so a reflection that came back empty fails that test rather than passing this one quietly.
     */
    @Test
    fun `probeObb is declared`() {
        val names = surfaceNames()

        assertTrue(
            "SystemRepository no longer declares probeObb; the sweep found $names. The export " +
                "sheet, the bundle builder and the app-info OBB card all read OBB state through " +
                "it, and each of them silently degrades to \"no OBB\" without it",
            "probeObb" in names
        )
    }

    /**
     * The source-text lock, for the re-add that never appears on this interface at all.
     *
     * `fun SystemRepository.aggressiveCleanup(pkg: String): Result<Unit>` compiles into whatever
     * file-class holds it, so [surfaceNames] cannot see it, while every call site reads
     * `systemRepository.aggressiveCleanup(pkg)` exactly as it did before the deletion — and an
     * extension has to have a body, so it would be the same discard-both-`Result`s composite,
     * written somewhere the interface's own guard cannot reach. The same sweep also catches a
     * `@JvmName` rename on a re-added declaration, which would show reflection some other name while
     * Kotlin callers still write this one.
     *
     * Two patterns, both matched on declaration shape rather than the bare name — `SystemRepository`
     * and `SystemRepositoryImpl` both carry tombstone comments naming this method in prose, and
     * neither must trip it:
     *  - inside `SystemRepository.kt`, any `fun … aggressiveCleanup(`;
     *  - anywhere in the production source sets, any `fun … SystemRepository.aggressiveCleanup(`.
     *
     * The guards run first and are the whole reason to believe the assertions after them. A sweep
     * over an empty file list satisfies every absence check it is given — the same failure the
     * reflection guard exists to catch, one layer down: a working directory that is not what this
     * test assumed, a moved module, a `listFiles()` that came back null. So the corpus must be more
     * than a handful of files, must contain `SystemRepository.kt` itself, must yield a hit for a
     * declaration known to be in that file, and the extension pattern must be shown to match the
     * shape it was written for and to have real extension declarations in the corpus to find.
     *
     * The boundary, stated rather than implied. This is a regex over text, not a parse: the name
     * re-added as a function-typed property, reached through a `typealias`, or generated by a
     * compiler plugin goes through both locks untouched. Not worth a parser — none of those is how
     * an unfinished-looking interface method actually comes back.
     */
    @Test
    fun `no source declaration brings aggressiveCleanup back`() {
        val sources = productionKotlinSources()

        assertTrue(
            "the sweep read only ${sources.size} Kotlin files, which cannot be Thor's production " +
                "source tree — the working directory is not what this test assumed, or app/src " +
                "moved. Every absence check below would pass on a corpus this small",
            sources.size >= 20
        )

        val interfaceSource = sources.entries
            .firstOrNull { it.key.invariantSeparatorsPath.endsWith(SYSTEM_REPOSITORY_KT) }
            ?.value
            .orEmpty()
        assertTrue(
            "the sweep never read $SYSTEM_REPOSITORY_KT, so the in-file assertion below is looking " +
                "at an empty string and passes no matter what that file declares",
            interfaceSource.isNotEmpty()
        )
        assertTrue(
            "the declaration pattern cannot find `fun forceStopApp(` in $SYSTEM_REPOSITORY_KT, " +
                "which is certainly there — the pattern no longer matches how this codebase " +
                "declares a function, so its failure to find aggressiveCleanup proves nothing",
            declarationOf("forceStopApp").containsMatchIn(interfaceSource)
        )
        assertTrue(
            "the extension pattern does not match the declaration it was written for — it can " +
                "never fire, and the tree-wide assertion below is decoration",
            EXTENSION_ON_REPOSITORY.containsMatchIn(
                "fun SystemRepository.$DELETED(pkg: String) = Result.success(Unit)"
            )
        )
        assertTrue(
            "no file in the corpus declares an extension function at all, which is not true of " +
                "this codebase — the corpus is not the source tree, or extensions are no longer " +
                "written as `fun Receiver.name(` and the tree-wide pattern is looking for a shape " +
                "that stopped existing",
            sources.values.any { ANY_EXTENSION.containsMatchIn(it) }
        )

        assertTrue(
            "$SYSTEM_REPOSITORY_KT declares `fun $DELETED(` again. Renaming it on the JVM with " +
                "@JvmName would hide it from reflection and change nothing for Kotlin callers, so " +
                "the source text is what decides. It was deleted because it swallowed the Results " +
                "of both operations it composed, and nothing called it",
            !declarationOf(DELETED).containsMatchIn(interfaceSource)
        )

        val offenders = sources.filterValues { EXTENSION_ON_REPOSITORY.containsMatchIn(it) }
            .keys
            .map { it.invariantSeparatorsPath }
        assertTrue(
            "an extension function puts SystemRepository.$DELETED back: $offenders. It compiles " +
                "into a different class, so the reflection lock in this file cannot see it, but " +
                "every call site reads systemRepository.$DELETED(...) exactly as it did before. An " +
                "extension has a body, so it is the discard-both-Results composite all over again",
            offenders.isEmpty()
        )
    }

    /**
     * Every method [SystemRepository] exposes, by Kotlin name: what it declares itself, plus what it
     * inherits from a super-interface.
     *
     * `declaredMethods` alone sees only methods declared on this exact type, so splitting the
     * interface into roles and putting `aggressiveCleanup` on a super-interface would take it out of
     * view while `systemRepository.aggressiveCleanup(pkg)` kept compiling everywhere. `methods` adds
     * the inherited ones and closes that. Additive rather than a replacement, because the two see
     * different things: `methods` is public-only and `declaredMethods` is visibility-blind, so a
     * `private` member appears in exactly one of them.
     *
     * The `Object` filter is verified to be a no-op here and is kept as insurance. `getMethods()` on
     * an *interface* returns nothing from `java.lang.Object` — an interface has no superclass, and
     * the contract is explicit in `Class.getMethods()`: "if this Class object represents an
     * interface then the returned array does not contain any implicitly declared methods from
     * Object". On a class it does return them, and this same filter is what drops them there, so
     * both files strip on the same rule rather than on a per-type assumption.
     *
     * A class literal rather than a `Class.forName` string, so a rename or a package move is a
     * compile error here instead of a silently empty result set. `suspend` members compile to
     * methods taking a trailing `Continuation`, which changes their signature but not their name, so
     * matching on names alone is stable across that.
     */
    private fun surfaceNames(): Set<String> {
        val type = SystemRepository::class.java
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
     * is excluded, which it has to be. Its own KDoc spells out the extension declaration it is
     * hunting for as the example of the defeat, and only a production declaration is a defeat
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
     * The Kotlin name, with both JVM manglings taken back off: the `$module` suffix `internal` adds
     * and the `-<hash>` suffix an inline value class return type adds. Neither character is legal in
     * a source-level Kotlin identifier without backticks, so cutting at the first of either cannot
     * truncate a real method name.
     */
    private fun Method.readableName(): String = name.substringBefore('$').substringBefore('-')

    private companion object {
        const val DELETED = "aggressiveCleanup"

        /** Path suffix of the anchor file, matched with `/` so it also holds on Windows. */
        const val SYSTEM_REPOSITORY_KT = "com/valhalla/thor/domain/repository/SystemRepository.kt"

        /** `fun … SystemRepository.aggressiveCleanup(`, in any file and any package. */
        val EXTENSION_ON_REPOSITORY =
            Regex("""\bfun\b[^(\n]*\bSystemRepository\s*\.[^(\n]*\b$DELETED\s*\(""")

        /**
         * Any extension declaration at all, used only as a control: it proves the corpus really is
         * Kotlin source in which extensions are still written as `fun Receiver.name(`, which is the
         * shape [EXTENSION_ON_REPOSITORY] pins itself to.
         */
        val ANY_EXTENSION = Regex("""\bfun\b[^(\n]*\b\w+\s*\.\s*\w+\s*\(""")
    }
}
