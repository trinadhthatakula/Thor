// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

/**
 * Domain port for the launcher-shortcut side effects an app action has to trigger. Narrow on
 * purpose: the concrete `FreezerShortcutManager` also pins, syncs and re-renders shortcuts, all
 * of which need `ShortcutManager` and a `Context`. Callers that only have to retire a shortcut
 * when its app disappears take this instead.
 */
interface AppShortcutController {
    /** Disable (and hide) any shortcut targeting [packageName] — it is gone or no longer launchable. */
    fun disableAppShortcut(packageName: String)
}
