// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import androidx.compose.runtime.Immutable

/**
 * Why per-component control is unavailable, so the UI can say something true instead of showing a
 * row of dead switches.
 *
 * There is no `SHIZUKU_UNSUPPORTED`: Shizuku *can* do all of this when it was itself started as
 * root, which is why the blocker for the ordinary shell-uid case names the uid rather than the
 * transport.
 */
enum class ComponentControlBlocker {
    /** Nothing is in the way. */
    NONE,

    /** The privilege probe has not settled yet — transient, and never a reason to say "no". */
    NOT_READY,

    /** No privilege transport at all. */
    NO_PRIVILEGE,

    /** Shizuku is connected but running at the shell uid (2000), not uid 0. */
    SHIZUKU_NOT_ROOT,

    /** Dhizuku's Device Owner API exposes no route to any of these operations. */
    DHIZUKU_UNSUPPORTED,
}

/**
 * What the active privilege transport may do to an individual component.
 *
 * Every privileged verb in this feature collapses onto one fact — **does the transport execute at
 * uid 0** — so that is the only thing stored; the named accessors exist to say *why* at each call
 * site rather than to record three independent facts that could drift apart.
 *
 * - **Setting component state** is uid 0 only. `PackageManagerService.setEnabledSetting` has a
 *   carve-out for `Process.SHELL_UID` that permits *package*-level changes and explicitly rejects
 *   anything with a non-null class name — `SecurityException("Shell cannot change component state
 *   for …")`. Reaching `IPackageManager.setComponentEnabledSetting` by reflection instead of by
 *   `pm` arrives at the same check with the same uid, so there is no fallback to write.
 * - **Force-launching** is uid 0 only. `ActivityManager.canAccessUnexportedComponents` waives the
 *   export and permission checks for `ROOT_UID` and `SYSTEM_UID` alone;
 *   `START_ANY_ACTIVITY` is a `signature` permission that the Shell package does not declare in any
 *   release from 9 to 16, and `pm grant` refuses it.
 * - **Stopping a service** is uid 0 only *here*. `ActiveServices.retrieveServiceLocked` would in
 *   fact let any uid stop an **exported** service, so a shell-uid Shizuku could stop some of them —
 *   that narrower path is deliberately not offered, because Thor cannot verify it across OEMs and a
 *   "Stop" that silently does nothing on half the rows is worse than one that is honestly absent.
 *
 * Dhizuku is not a partial case but an empty one: `DevicePolicyManager` has no component-enabled
 * API at all, and a Device Owner's only launch-related privilege is a background-activity-launch
 * exemption, which is not an export waiver.
 */
@Immutable
data class ComponentCapability(
    /** Whether the active transport executes at uid 0 — root, or Shizuku that was started as root. */
    val hasUid0: Boolean,
    val blocker: ComponentControlBlocker,
) {
    val canSetComponentState: Boolean get() = hasUid0
    val canForceLaunch: Boolean get() = hasUid0
    val canStopService: Boolean get() = hasUid0

    /**
     * Whether [component] can be launched at all — the one question whose answer varies per row.
     *
     * An exported, unguarded activity is launchable by anybody, Thor included, with no privilege
     * whatsoever; everything else needs uid 0.
     */
    fun canLaunch(component: ComponentDetail): Boolean =
        !component.launchRequiresRoot || hasUid0

    companion object {
        val None = ComponentCapability(hasUid0 = false, blocker = ComponentControlBlocker.NOT_READY)
    }
}

/**
 * Derive the capability from the settled privilege state.
 *
 * @param shizukuUid the uid Shizuku's own service runs as, or `null` when it could not be read.
 * An unreadable uid resolves to **not capable**. That is the opposite of the fold used when picking
 * a privileged installer, where an unknown uid is optimistically treated as root: a wrong guess
 * there costs a failed install with a clear error, whereas a wrong guess here paints enabled Force
 * Open and Disable controls that throw a `SecurityException` on every press.
 */
fun componentCapability(
    mode: PrivilegeMode?,
    isReady: Boolean,
    shizukuUid: Int?,
): ComponentCapability {
    if (!isReady) return ComponentCapability(false, ComponentControlBlocker.NOT_READY)
    return when (mode) {
        null, PrivilegeMode.NONE -> ComponentCapability(false, ComponentControlBlocker.NO_PRIVILEGE)
        PrivilegeMode.ROOT -> ComponentCapability(true, ComponentControlBlocker.NONE)
        PrivilegeMode.SHIZUKU ->
            if (shizukuUid == ROOT_UID) ComponentCapability(true, ComponentControlBlocker.NONE)
            else ComponentCapability(false, ComponentControlBlocker.SHIZUKU_NOT_ROOT)

        PrivilegeMode.DHIZUKU ->
            ComponentCapability(false, ComponentControlBlocker.DHIZUKU_UNSUPPORTED)
    }
}

/** `android.os.Process.ROOT_UID`, which is `@hide`. */
const val ROOT_UID: Int = 0
