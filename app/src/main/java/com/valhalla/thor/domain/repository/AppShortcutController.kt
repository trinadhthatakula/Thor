// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

/**
 * Domain port for the launcher-shortcut side effects a view model has to trigger.
 *
 * Exists because the concrete `FreezerShortcutManager` needs a `Context` and `ShortcutManager`, so
 * depending on the class puts the whole view model out of reach of a JVM test. It covers pinning as
 * well as retiring/re-rendering because the Freezer screen drives all three; callers that only
 * retire a shortcut when its app disappears simply ignore the rest.
 *
 * What stays off this port is everything with no view-model caller: dynamic-shortcut sync, the bulk
 * run, and the wholesale pinned-icon rebuild.
 */
interface AppShortcutController {
    /** Disable (and hide) any shortcut targeting [packageName] — it is gone or no longer launchable. */
    fun disableAppShortcut(packageName: String)

    /**
     * Re-render an already-pinned shortcut for [packageName] so its icon matches the app's current
     * frozen/active state. No-op when nothing is pinned. Call after any freeze or unfreeze.
     */
    fun refreshAppShortcut(packageName: String)

    /** Whether the current launcher accepts pin requests at all — gates every pin affordance. */
    fun isPinSupported(): Boolean

    /**
     * Ask the launcher to pin a per-app shortcut, off the caller's thread. Fire-and-forget: Android
     * reports only the accept, never a cancel.
     */
    fun pinAppShortcut(packageName: String, label: String)

    /**
     * Suspending pin, so a bulk caller can pin one at a time instead of spawning N concurrent icon
     * decodes and binder pin requests.
     */
    suspend fun pinAppShortcutSuspend(packageName: String, label: String)

    /** Ask the launcher to pin a Freeze-all / Unfreeze-all action shortcut for [action]. */
    fun pinBulkShortcut(action: String)

    /** Publish or remove the dynamic Freezer shortcuts to match the user's setting. */
    fun syncDynamicShortcuts(enabled: Boolean)
}
