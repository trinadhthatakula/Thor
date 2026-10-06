// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Filter categories, separate from the policy that decides whether an action is allowed. */
enum class UadRecommendation(val persistedValue: String) {
    RECOMMENDED("Recommended"),
    ADVANCED("Advanced"),
    EXPERT("Expert"),
    UNSAFE("Unsafe"),
    UNKNOWN("Unknown");

    companion object {
        /** Stable preference tokens; display labels can be translated independently. */
        fun fromPersistedValue(value: String): UadRecommendation? =
            entries.firstOrNull { it.persistedValue == value }

        /**
         * Match the existing badge and action policy's case handling. Whitespace and arbitrary
         * extension text remain Unknown rather than claiming advice the policy does not recognise.
         */
        fun fromRecommendation(value: String?): UadRecommendation = when (value?.lowercase()) {
            "recommended" -> RECOMMENDED
            "advanced" -> ADVANCED
            "expert" -> EXPERT
            "unsafe" -> UNSAFE
            else -> UNKNOWN
        }
    }
}

/** Null means that classification is unavailable, rather than a successful lookup with no advice. */
val AppInfo.uadRecommendation: UadRecommendation?
    get() = if (!isSystem || !isUadLoaded || isUadLoadFailed) {
        null
    } else {
        UadRecommendation.fromRecommendation(bloatRecommendation)
    }
