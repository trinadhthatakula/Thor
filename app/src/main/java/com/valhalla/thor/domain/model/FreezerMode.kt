package com.valhalla.thor.domain.model

/** What the Freezer does when it "freezes" an app. GH#239. */
enum class FreezerMode { FREEZE, SUSPEND }

/** Freezer treats an app as "frozen" when it is disabled OR suspended. */
fun isFrozen(enabled: Boolean, isSuspended: Boolean): Boolean = !enabled || isSuspended

/** "Active" = not frozen: enabled and not suspended (i.e. freezable). */
fun isActive(enabled: Boolean, isSuspended: Boolean): Boolean = enabled && !isSuspended

/** The inverse ops needed to bring an app fully back to active. */
data class RestorePlan(val unsuspend: Boolean, val enable: Boolean)

/**
 * Plan to restore an app to active. Clears BOTH freeze dimensions when both are set —
 * an app can be disabled AND suspended (e.g. after a mode switch), and reversing only one
 * leaves it frozen. For an already-active app it defaults to a harmless enable so
 * "remove from freezer" keeps its prior always-enable behavior.
 */
fun restorePlanFor(enabled: Boolean, isSuspended: Boolean): RestorePlan =
    RestorePlan(unsuspend = isSuspended, enable = !enabled || !isSuspended)

// Ergonomic call-site helpers over AppInfo.
val AppInfo.isFrozen: Boolean get() = isFrozen(enabled, isSuspended)
val AppInfo.isActive: Boolean get() = isActive(enabled, isSuspended)
