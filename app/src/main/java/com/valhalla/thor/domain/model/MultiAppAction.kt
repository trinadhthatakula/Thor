// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

sealed interface MultiAppAction {
    data class ReInstall(val appList: List<AppInfo>) : MultiAppAction
    data class Uninstall(val appList: List<AppInfo>) : MultiAppAction
    data class Freeze(val appList: List<AppInfo>, val useSuspend: Boolean = false) : MultiAppAction
    data class UnFreeze(val appList: List<AppInfo>) : MultiAppAction
    data class Share(val appList: List<AppInfo>) : MultiAppAction

    /** Writes one installer bundle per app to the export destination. Sibling of [Share], but the
     *  files land in the user's export folder instead of a one-shot content-provider Uri. */
    data class Backup(val appList: List<AppInfo>) : MultiAppAction
    data class Kill(val appList: List<AppInfo>) : MultiAppAction

    data class ClearCache(val appList: List<AppInfo>) : MultiAppAction
    data class Suspend(val appList: List<AppInfo>) : MultiAppAction
    data class UnSuspend(val appList: List<AppInfo>) : MultiAppAction

    // There is deliberately no `ClearData` here. One existed, with a handler in MainViewModel, a
    // "this cannot be undone" confirmation in AffirmationDialog and translated batch copy in five
    // locales — and no button anywhere in the app ever constructed it. Nothing was reachable; the
    // three pieces only made it look reachable, which is worse than absent, because a plan drawn up
    // from this file would have sized it as an existing feature to migrate rather than as a
    // destructive one to design. Single-app clear data is a real feature and unaffected: see
    // [AppClickAction.ClearData]. If bulk clear data is wanted, it wants a deliberate design pass
    // for the irreversible-over-N-apps case, not the resurrection of this stub.
}