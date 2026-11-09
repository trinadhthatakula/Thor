// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.NavigationBarPreferences
import kotlinx.serialization.json.Json

/** Unknown future fields are tolerated; malformed or incompatible snapshots use safe defaults. */
internal object NavigationPreferencesCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(value: NavigationBarPreferences): String = json.encodeToString(value.normalized())
    /** Restores and clamps a saved style, using defaults for absent or invalid snapshots. */
    fun decode(value: String?): NavigationBarPreferences = value?.let {
        runCatching { json.decodeFromString<NavigationBarPreferences>(it).normalized() }.getOrNull()
    } ?: NavigationBarPreferences()
}
