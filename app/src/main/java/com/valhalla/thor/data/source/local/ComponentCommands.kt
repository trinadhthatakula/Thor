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

/** The `pm` verb for a state the gateway contract names. */
internal fun ComponentEnabledState.asComponentState(): ComponentState = when (this) {
    ComponentEnabledState.ENABLED -> ComponentState.ENABLED
    ComponentEnabledState.DISABLED -> ComponentState.DISABLED
    ComponentEnabledState.DEFAULT -> ComponentState.DEFAULT
}

/**
 * Substrings that mark a component command as having failed, whatever its exit code said.
 *
 * `Warning:` is deliberately absent. `am start` answers a perfectly successful launch of an
 * already-running activity with "Warning: Activity not started, its current task has been brought
 * to the front", and treating that as a failure would report an error for the single most common
 * repeat press in the whole feature.
 */
private val COMPONENT_FAILURE_MARKERS = listOf(
    "Security exception",
    "SecurityException",
    "Exception occurred while executing",
    "Error:",
    "Error type",
)

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
 * Returns the first marked line rather than the whole output: `ShellCommand.exec` prints the entire
 * stack trace on an exception, and a Toast is not the place for forty frames. Activity Launcher's
 * `Toast.makeText(ctx, "…: " + e, LENGTH_LONG)` is the shape being avoided.
 */
internal fun componentCommandFailure(exitCode: Int, output: String): String? {
    val marked = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { line -> COMPONENT_FAILURE_MARKERS.any { line.contains(it) } }
    if (marked != null) return marked.take(MAX_FAILURE_LINE_CHARS)
    if (exitCode != 0) {
        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.take(MAX_FAILURE_LINE_CHARS)
            ?: "exit $exitCode"
    }
    return null
}

private const val MAX_FAILURE_LINE_CHARS = 200
