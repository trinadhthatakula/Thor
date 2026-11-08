// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

enum class AppListType {
    USER, SYSTEM, ALL;

    val includesUser: Boolean get() = this != SYSTEM
    val includesSystem: Boolean get() = this != USER

    fun includes(app: AppInfo): Boolean = if (app.isSystem) includesSystem else includesUser

    /** Opting out restores User while preserving an existing single-source selection. */
    fun forCombinedLists(enabled: Boolean): AppListType =
        if (!enabled && this == ALL) USER else this

    /** Toggle one source while keeping at least one source selected. */
    fun toggle(type: AppListType): AppListType = when (type) {
        USER -> when (this) {
            ALL -> SYSTEM
            SYSTEM -> ALL
            USER -> USER
        }
        SYSTEM -> when (this) {
            ALL -> USER
            USER -> ALL
            SYSTEM -> SYSTEM
        }
        ALL -> ALL
    }

    companion object {
        /** Individual sources, also used by single-source charts and app pickers. */
        val individualTypes: List<AppListType> = listOf(USER, SYSTEM)
    }
}
