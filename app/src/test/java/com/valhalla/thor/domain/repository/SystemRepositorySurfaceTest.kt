// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

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
 */
class SystemRepositorySurfaceTest {

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
    fun `aggressiveCleanup is gone`() {
        val names = declaredNames()

        assertTrue(
            "SystemRepository.aggressiveCleanup is back. It was deleted because it discarded the " +
                "Results of both operations it composed and then reported unconditional success, " +
                "and because nothing called it. If a caller now needs force-stop plus clear-cache, " +
                "it should call forceStopApp and clearCache and decide for itself what a partial " +
                "failure means. Declared methods: $names",
            "aggressiveCleanup" !in names
        )
    }

    /**
     * The anti-vacuity floor under the assertion above.
     *
     * An absence assertion is the one shape that passes best when it is looking at nothing:
     * `declaredMethods` coming back empty — a moved interface, a change in how Kotlin emits `suspend`
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
        val names = declaredNames()

        assertTrue(
            "the sweep found $names, which is missing methods that are known to be declared — it " +
                "is looking at the wrong type, or declaredMethods returned nothing and the " +
                "absence assertion above is passing vacuously",
            names.containsAll(setOf("forceStopApp", "clearCache"))
        )
    }

    /**
     * Every method declared on [SystemRepository], by Kotlin name.
     *
     * A class literal rather than a `Class.forName` string, so a rename or a package move is a
     * compile error here instead of a silently empty result set. `suspend` members compile to
     * methods taking a trailing `Continuation`, which changes their signature but not their name, so
     * matching on names alone is stable across that.
     */
    private fun declaredNames(): Set<String> =
        SystemRepository::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.isBridge || it.name.contains("\$default") }
            .map { it.readableName() }
            .toSet()

    /**
     * The Kotlin name, with both JVM manglings taken back off: the `$module` suffix `internal` adds
     * and the `-<hash>` suffix an inline-value-class return type adds. Neither character is legal in
     * a source-level Kotlin identifier without backticks, so cutting at the first of either cannot
     * truncate a real method name.
     */
    private fun Method.readableName(): String = name.substringBefore('$').substringBefore('-')
}
