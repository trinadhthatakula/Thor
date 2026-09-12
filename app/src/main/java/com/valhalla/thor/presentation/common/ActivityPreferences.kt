// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.common

import com.valhalla.thor.domain.model.DefaultTab
import com.valhalla.thor.domain.model.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

internal data class ActivityPreferences(
    val preferences: UserPreferences,
    val initialDefaultTab: DefaultTab,
)

/**
 * A single activity-scoped subscription, started before composition. Null means no preference
 * snapshot has arrived, so neither entry point renders text in a guessed font. Appearance remains
 * live after that first snapshot, while changing the default tab never moves an open screen.
 */
internal fun Flow<UserPreferences>.asActivityPreferences(
    scope: CoroutineScope,
): StateFlow<ActivityPreferences?> =
    runningFold<UserPreferences, ActivityPreferences?>(null) { previous, preferences ->
        ActivityPreferences(
            preferences = preferences,
            initialDefaultTab = previous?.initialDefaultTab ?: preferences.defaultTab,
        )
    }.stateIn(scope, SharingStarted.Eagerly, null)
