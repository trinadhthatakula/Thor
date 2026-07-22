// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.koin.core.annotation.Single
import android.app.LocaleManager as AndroidLocaleManager

/**
 * Modern Locale Manager that uses the system LocaleManager on Android 13+ (API 33)
 * and falls back to AppCompatDelegate for older versions.
 */
@Single
class LocaleManager(private val context: Context) {

    /**
     * Applies the given language code to the application.
     * `@param` languageCode The language tag (e.g., "en", "zh-CN"), or null for system default.
     */
    fun applyLocale(languageCode: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager =
                context.getSystemService(Context.LOCALE_SERVICE) as AndroidLocaleManager
            localeManager.applicationLocales = if (languageCode == null) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(languageCode)
            }
        } else {
            val appLocale: LocaleListCompat = if (languageCode == null) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(languageCode)
            }
            AppCompatDelegate.setApplicationLocales(appLocale)
        }
    }

    /**
     * Returns the currently applied application locales.
     */
    fun getAppliedLocales(): LocaleListCompat {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager =
                context.getSystemService(Context.LOCALE_SERVICE) as AndroidLocaleManager
            LocaleListCompat.wrap(localeManager.applicationLocales)
        } else {
            AppCompatDelegate.getApplicationLocales()
        }
    }
}
