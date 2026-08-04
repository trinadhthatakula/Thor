// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.shizuku

import org.junit.Assert.assertTrue
import org.junit.Test
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
 */
class PackagesSurfaceTest {

    /**
     * The three deleted names, and the reason each one cannot come back on its own.
     *
     * Matched on the de-mangled name so that a member re-added with a different visibility — Kotlin
     * mangles an `internal` member's JVM name with a module suffix, e.g.
     * `setAppRestricted$app_fossDebug` — cannot slip past a plain string comparison.
     */
    @Test
    fun `the unprivileged mutators are gone`() {
        val names = declaredNames()

        for (deleted in setOf("forceStopApp", "setAppDisabled", "setAppRestricted")) {
            assertTrue(
                "Packages.$deleted is back. It was deleted because unprivileged Bypass reflection " +
                    "reports success whenever the call did not throw, which is indistinguishable " +
                    "from a silent refusal — and because these three only ever called each other. " +
                    "If a caller now needs this operation, it belongs on a privileged gateway that " +
                    "verifies it by reading the state back, not here. Declared members: $names",
                deleted !in names
            )
        }
    }

    /**
     * The anti-vacuity floor under the assertion above, and the only reason to trust it.
     *
     * "Assert some names are absent" is the one shape of test that passes perfectly when it is
     * looking at nothing at all: rename or relocate the class, have `declaredMethods` come back
     * empty for any reason, and every `deleted !in names` check above is trivially satisfied with a
     * green tick. Naming members that are known to still exist proves the reflection resolved a real
     * class with real methods on it.
     *
     * `containsAll`, never an equality — adding an observer to [Packages] must not have to be
     * remembered here. The four named are the ones the privileged paths actually call this class
     * for: `Shizuku`/`Dhizuku` read `getApplicationInfoOrNull` and `isAppStopped` to judge their own
     * rungs, `ShizukuReflector` reads `isAppUninstalled`, and `isAppDisabled` is the canonical freeze
     * predicate shared with `AppFreezeStateReader`.
     */
    @Test
    fun `the reflection actually sees the class`() {
        val names = declaredNames()

        assertTrue(
            "the sweep found $names, which is missing observers that are known to exist — it is " +
                "looking at the wrong class, or declaredMethods returned nothing and the absence " +
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
     * Every method declared on [Packages], by Kotlin name.
     *
     * A class literal rather than a `Class.forName` string, so a rename is a compile error here
     * instead of a silently empty result set. Synthetic and bridge members are dropped because
     * Kotlin emits a `$default` bridge for every function with a default argument — both
     * `getInstalledApplications` and `getApplicationInfoOrNull` have one — and those are a compiler
     * detail, not this class's surface.
     */
    private fun declaredNames(): Set<String> =
        Packages::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.isBridge || it.name.contains("\$default") }
            .map { it.readableName() }
            .toSet()

    /**
     * The Kotlin name, with both JVM manglings taken back off: the `$module` suffix `internal` adds
     * and the `-<hash>` suffix an inline-value-class return type adds. Neither character is legal in
     * a source-level Kotlin identifier without backticks, so cutting at the first of either cannot
     * truncate a real method name.
     *
     * The second strip is not needed by anything on [Packages] today — every member here returns a
     * plain type — and is here because the absence assertions above would go silently vacuous the
     * day one of the three comes back returning `Result`. That is not hypothetical: the sibling
     * `SystemRepositorySurfaceTest` was written without it and its own anti-vacuity guard caught it
     * on the first run, where every method is `Result`-returning and de-mangled to nothing that
     * matched.
     */
    private fun Method.readableName(): String = name.substringBefore('$').substringBefore('-')
}
