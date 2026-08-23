// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * No `label()` here. It used to return "Light"/"Dark"/"System" as Kotlin string literals — a domain
 * enum, which has no Android dependencies by design and therefore no access to resources, answering
 * a question only the UI asks, in English, on all five locales. The labels now live beside the other
 * settings enums' labels in `SettingsCatalog.kt` as `ThemeMode.labelRes`.
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}
