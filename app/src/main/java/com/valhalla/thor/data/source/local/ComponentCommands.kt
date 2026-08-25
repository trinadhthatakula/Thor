// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.domain.gateway.ComponentEnabledState

/**
 * The privileged commands that act on a single **component** rather than on a whole package.
 *
 * Kept beside [clearAppDataCommand] and friends in [PerUserCommands] for the same reason those
 * exist: every one of these has to name a user or it silently acts on user 0. `pm`'s
 * enable/disable/default-state trio seeds `UserHandle.USER_SYSTEM`, and `am`'s `stopservice` seeds
 * the current user — neither is "the user Thor is running in".
 *
 * The second trap these builders exist to close is the shell word itself. A component spec is
 * `<package>/<class>`, and an inner class arrives from `PackageManager` with a **`$`** in it —
 * `com.foo.Widget$Receiver` is an entirely ordinary receiver name. Interpolated into a
 * double-quoted or bare shell word, `$Receiver` expands to nothing and the command acts on
 * `com.foo.Widget`, which either does not exist (harmless) or is a *different, real* component
 * (not harmless). [componentSpec] therefore returns the raw `pkg/cls` pair and every caller passes
 * it through `escapeForShell()`, which single-quotes, before it reaches a builder here.
 *
 * As in [PerUserCommands], the builders take the user id and the already-escaped spec so that
 * "does this command name a user, and is the spec quoted?" is assertable from a plain JVM test —
 * `Process.myUserHandle()` is not callable there.
 */

/** The raw `<package>/<class>` pair. Escape it before it reaches any builder in this file. */
internal fun componentSpec(packageName: String, className: String): String =
    "$packageName/$className"

/**
 * What a class name may contain.
 *
 * Wider than the package regex the gateways already use, by exactly one character: `$`. An inner
 * class is reported by `PackageManager` as `com.foo.Widget$Receiver`, and validating a class name
 * against the package pattern would reject every one of them — turning a legitimate, common
 * component into "Invalid package name" and making the feature look broken on the apps most likely
 * to have interesting receivers.
 */
internal val COMPONENT_CLASS_REGEX = Regex("^[a-zA-Z0-9._\$]+\$")

/** The package half. Same pattern the three gateways enforce for every other privileged verb. */
internal val COMPONENT_PACKAGE_REGEX = Regex("^[a-zA-Z0-9._]+\$")

/**
 * The escaped `<package>/<class>` shell word, or `null` if either half is not a plausible name.
 *
 * Validation and escaping in one place because they are one decision: the escape makes a hostile
 * name inert, and the pattern makes an implausible one visible as a bug rather than as a command
 * that runs and does nothing.
 */
internal fun escapedComponentSpecOrNull(packageName: String, className: String): String? {
    if (!packageName.matches(COMPONENT_PACKAGE_REGEX)) return null
    if (!className.matches(COMPONENT_CLASS_REGEX)) return null
    return componentSpec(packageName, className).escapeForShell()
}

/**
 * The three states `pm` can put a component in, and the sub-command that reaches each.
 *
 * [DEFAULT] is not a synonym for [ENABLED]. `default-state` *removes* the override and lets the
 * manifest's `android:enabled` decide again, which for a component that ships disabled means the
 * component goes back to being off. That distinction is the whole reason
 * `ComponentDetail.manifestDefaultEnabled` is carried next to `enabled`, and why
 * `ComponentControlUseCase.enableTargetState` exists rather than a bare "enable" verb.
 *
 * [DISABLED] uses `disable` rather than `disable-user`. Both are accepted for a component, but
 * `DISABLED_USER` is the state Settings' own "disable app" writes at *package* level, and reusing
 * it here would make Thor's per-component rows indistinguishable from a user-disabled app in
 * `dumpsys package`.
 */
internal enum class ComponentState(val pmVerb: String) {
    DISABLED("disable"),
    ENABLED("enable"),
    DEFAULT("default-state"),
}

/**
 * `pm enable` / `pm disable` / `pm default-state` on one component, scoped to [userId].
 *
 * Reachable at uid 0 only. `PackageManagerService.setEnabledSetting` allows `Process.SHELL_UID`
 * through a carve-out that requires `className == null`; with a class name present it throws
 * `SecurityException("Shell cannot change component state for …")`. There is no reflective way
 * around it — `IPackageManager.setComponentEnabledSetting` lands on the same check with the same
 * calling uid.
 */
internal fun setComponentStateCommand(
    escapedComponent: String,
    userId: Int,
    state: ComponentState,
): String = "pm ${state.pmVerb} --user $userId $escapedComponent"

/**
 * `am start` on one component, scoped to [userId].
 *
 * Deliberately carries no action, no category and no flags: an explicit component makes the intent
 * filter irrelevant, and an invented `MAIN`/`LAUNCHER` action is visible to the target — several
 * apps branch on `intent.action` in `onCreate` and would take a different path than the one the
 * user asked to see. A null caller means `ActivityStarter` has no task to attach to and creates
 * one, so `FLAG_ACTIVITY_NEW_TASK` is not needed either.
 *
 * Reachable at uid 0 only when the target is unexported or permission-guarded:
 * `ActivityManager.canAccessUnexportedComponents` waives those checks for `ROOT_UID` and
 * `SYSTEM_UID` and nothing else.
 */
internal fun startActivityCommand(escapedComponent: String, userId: Int): String =
    "am start --user $userId -n $escapedComponent"

/**
 * `am stopservice` on one component, scoped to [userId].
 *
 * `-n` is required. `Intent.parseCommandArgs` treats a trailing bare argument as data or a package,
 * never as a component, so `am stopservice pkg/cls` parses to an intent with no component and stops
 * nothing while still exiting 0.
 */
internal fun stopServiceCommand(escapedComponent: String, userId: Int): String =
    "am stopservice --user $userId -n $escapedComponent"

/**
 * Which rule [componentCommandFailure] should judge a command's output by.
 *
 * There are two, not three, because `pm enable|disable|default-state` and `am start` agree on the
 * only thing that matters here: a non-zero exit code means something went wrong. `am stopservice`
 * does not agree, so it gets its own rule rather than a special case bolted into the shared one.
 */
internal enum class ComponentCommandKind {
    /** `pm enable|disable|default-state` and `am start`. Exit code *and* output markers. */
    STANDARD,

    /** `am stopservice`, whose exit code carries no information at all. Markers only. */
    STOP_SERVICE,
}

/** The `pm` verb for a state the gateway contract names. */
internal fun ComponentEnabledState.asComponentState(): ComponentState = when (this) {
    ComponentEnabledState.ENABLED -> ComponentState.ENABLED
    ComponentEnabledState.DISABLED -> ComponentState.DISABLED
    ComponentEnabledState.DEFAULT -> ComponentState.DEFAULT
}

/**
 * Substrings that name *what* went wrong, and are therefore worth showing the user verbatim.
 *
 * `Warning:` is deliberately absent. `am start` answers a perfectly successful launch of an
 * already-running activity with "Warning: Activity not started, its current task has been brought
 * to the front", and treating that as a failure would report an error for the single most common
 * repeat press in the whole feature.
 */
private val SPECIFIC_FAILURE_MARKERS = listOf(
    "Security exception",
    "SecurityException",
    // Any thrown exception that carries a message: `java.lang.IllegalArgumentException: Component
    // class … does not exist in …` is what a "Restore all" hits for a component an app update has
    // removed, and it is the most useful sentence in the whole output. The colon is what keeps this
    // from also matching the content-free header below, which reads "Exception occurred while …".
    "Exception:",
    "Error:",
    "Error type",
    "Error stopping service",
)

/**
 * The header `ShellCommand.exec` prints *above* a thrown exception.
 *
 * "Exception occurred while executing 'start':" names no cause, and it is the line a naive
 * first-match scan picks because it comes first. It is searched for only after
 * [SPECIFIC_FAILURE_MARKERS] have failed to match anywhere, and even then the line *after* it is
 * preferred — that is where the exception itself is.
 */
private const val FAILURE_HEADER_MARKER = "Exception occurred while executing"

/** A stack frame, which is never worth reporting on its own. */
private const val STACK_FRAME_PREFIX = "at "

/**
 * The failure line from a component command's output, or `null` if it succeeded.
 *
 * **The exit code alone cannot answer this**, in either direction:
 *  - `am start` refused for a permission denial prints `Security exception:` followed by a stack
 *    trace and still exits **0** on most releases; from Android 14 the same refusal is reported as
 *    `Error: Activity class {…} does not exist.` instead, because `ActivityStarter` folds the
 *    security failure into `START_CLASS_NOT_FOUND`. Trusting the code means reporting "Launched" for
 *    a launch that visibly did not happen.
 *  - a non-zero code with no marker in the output is still a failure, which is why the code is
 *    checked too rather than replaced.
 *
 * …and for [ComponentCommandKind.STOP_SERVICE] the exit code is not merely unreliable, it is
 * **constant**. `am stopservice` exits **255 in every case** — verified on Android 17 for a service
 * that was stopped, a service that was not running, and a component that does not exist, through
 * both `am` and `cmd activity stop-service`, with and without `--user`. The only thing that
 * distinguishes them is a sentence `ActivityManagerShellCommand` writes to **stderr**:
 *
 * ```
 * Service stopped                          → stopped it
 * Service not stopped: was not running.    → nothing to stop
 * Error stopping service                   → the one real failure
 * ```
 *
 * So that kind judges on markers alone. "Was not running" counts as success rather than as an
 * error: the button says stop the service, and a service that is not running is stopped. Reporting
 * a failure there would put an error Toast on the *most* likely outcome of the press, since most
 * services in a component list are not running when the user is looking at them.
 *
 * The corollary is that stderr has to reach this function. It does for root — `RootSystemGateway`
 * concatenates `ShellResult.stdout + stderr` — and for Shizuku only through
 * `ShizukuHelper.executeCombined`; plain `execute()` returns stdout *or* stderr, and `stopservice`
 * always writes to both.
 *
 * Returns the first marked line rather than the whole output: `ShellCommand.exec` prints the entire
 * stack trace on an exception, and a Toast is not the place for forty frames. Activity Launcher's
 * `Toast.makeText(ctx, "…: " + e, LENGTH_LONG)` is the shape being avoided.
 */
internal fun componentCommandFailure(
    exitCode: Int,
    output: String,
    kind: ComponentCommandKind = ComponentCommandKind.STANDARD,
): String? {
    val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    if (kind == ComponentCommandKind.STOP_SERVICE &&
        lines.any { line -> STOP_SERVICE_SUCCESS_MARKERS.any { line.startsWith(it) } }
    ) {
        return null
    }

    lines.firstOrNull { line -> SPECIFIC_FAILURE_MARKERS.any { line.contains(it) } }
        ?.let { return it.take(MAX_FAILURE_LINE_CHARS) }

    val headerIndex = lines.indexOfFirst { it.contains(FAILURE_HEADER_MARKER) }
    if (headerIndex >= 0) {
        // The line after the header is the exception itself — reported in preference to the header,
        // which names no cause. Guarded against an output whose next line is already a stack frame.
        val cause = lines.getOrNull(headerIndex + 1)?.takeUnless { it.startsWith(STACK_FRAME_PREFIX) }
        return (cause ?: lines[headerIndex]).take(MAX_FAILURE_LINE_CHARS)
    }

    // Only the standard kind may draw a conclusion from the code; see the note above.
    if (kind == ComponentCommandKind.STANDARD && exitCode != 0) {
        return lines.firstOrNull()?.take(MAX_FAILURE_LINE_CHARS) ?: "exit $exitCode"
    }
    return null
}

/**
 * The stderr sentences that mean `am stopservice` did its job.
 *
 * "Service not stopped: was not running." is in here on purpose — it is the *no-op* answer, not the
 * failure one, and the failure answer has its own marker in [SPECIFIC_FAILURE_MARKERS]. Note that
 * it does not contain "Service stopped" as a substring ("Service **not** stopped"), so the two
 * cannot be confused by a `contains` check either.
 */
private val STOP_SERVICE_SUCCESS_MARKERS = listOf(
    "Service stopped",
    "Service stopping",
    "Service not stopped: was not running",
)

private const val MAX_FAILURE_LINE_CHARS = 200
