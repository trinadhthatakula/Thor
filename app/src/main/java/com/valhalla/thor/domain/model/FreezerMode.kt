package com.valhalla.thor.domain.model

/** What the Freezer does when it "freezes" an app. GH#239. */
enum class FreezerMode { FREEZE, SUSPEND }

/** How a frozen app is restored to active, based on its actual current state. */
enum class FreezerRestore { ENABLE, UNSUSPEND, NONE }

/** Freezer treats an app as "frozen" when it is disabled OR suspended. */
fun isFrozen(enabled: Boolean, isSuspended: Boolean): Boolean = !enabled || isSuspended

/** "Active" = not frozen: enabled and not suspended (i.e. freezable). */
fun isActive(enabled: Boolean, isSuspended: Boolean): Boolean = enabled && !isSuspended

/** Pick the inverse op to bring a frozen app back to active (suspended wins if somehow both). */
fun restoreActionFor(enabled: Boolean, isSuspended: Boolean): FreezerRestore = when {
    isSuspended -> FreezerRestore.UNSUSPEND
    !enabled -> FreezerRestore.ENABLE
    else -> FreezerRestore.NONE
}

// Ergonomic call-site helpers over AppInfo.
val AppInfo.isFrozen: Boolean get() = isFrozen(enabled, isSuspended)
val AppInfo.isActive: Boolean get() = isActive(enabled, isSuspended)
val AppInfo.restoreAction: FreezerRestore get() = restoreActionFor(enabled, isSuspended)
