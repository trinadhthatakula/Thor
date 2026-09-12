// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Stable preference tokens; display labels belong to the presentation layer. */
enum class FontPreset(val storageValue: String) {
    ASGARD("asgard"),
    SYSTEM("system");

    companion object {
        /** Preserve the existing appearance for new installs and unrecognized saved values. */
        fun fromStorageValue(value: String?): FontPreset =
            entries.firstOrNull { it.storageValue == value } ?: ASGARD
    }
}
