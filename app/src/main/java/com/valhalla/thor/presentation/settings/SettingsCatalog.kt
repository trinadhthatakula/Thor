// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.AppGridDensity
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.util.AppLanguage

/**
 * The eight doors, and every row behind them.
 *
 * This file exists so that the *shape* of Settings is data rather than layout. Before it, one
 * `Column(verticalScroll)` held 29 entries under 10 hand-written section headers, and two of the
 * last three rows were added with a `koinInject` in the middle of the layout — there was no gate a
 * new setting had to pass, which is why the screen grew to 1275 lines. Now a new setting is an entry
 * in [SettingsRowId] with a category, and [SettingsCategoryScreen]'s exhaustive `when` refuses to
 * compile until it is drawn somewhere.
 *
 * The index's length is fixed at eight; the number of settings is not. That is the whole point.
 */
enum class SettingsCategory(
    /**
     * The stable id carried in [com.valhalla.thor.presentation.navigation.ThorRoute.SettingsCategory].
     *
     * A string, not the enum's `name` or `ordinal`, and deliberately not the enum itself. A back
     * stack is persisted for task restoration and survives an app update, so a restored stack can
     * name a category this build no longer has. An unknown id resolves to null and the entry pops
     * itself; an ordinal would silently resolve to *a different category* the day this enum is
     * reordered.
     */
    val id: String,
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
) {
    APPEARANCE("appearance", R.string.settings_category_appearance, R.drawable.theme_panel),
    HOME("home", R.string.settings_category_home, R.drawable.home),
    CUSTOMIZATION("customization", R.string.settings_category_customization, R.drawable.dashboard_customize),
    FREEZER("freezer", R.string.freezer, R.drawable.frozen),
    INSTALLING("installing", R.string.settings_category_installing, R.drawable.apk_install),
    SECURITY("security", R.string.settings_category_security, R.drawable.round_key),
    BACKUP("backup", R.string.backup_and_restore, R.drawable.settings_backup_restore),
    EXTENSIONS("extensions", R.string.extensions, R.drawable.round_extension),
    ABOUT("about", R.string.settings_category_about, R.drawable.thor_mono);

    companion object {
        /** @return null for an id no build understands — see [id]. */
        fun fromId(id: String): SettingsCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One entry per navigable row, in the order it is drawn inside its category.
 *
 * [title] and [keywords] are what search matches on. [keywords] is the row's *default* subtitle:
 * several rows swap their subtitle at runtime (the language row names the current language, the two
 * permission rows say granted or needed, five Freezer rows say "requires privilege" when there is
 * none), and indexing whichever sentence happens to be showing would make a row findable on one
 * device and not another. The static one is the one that describes the setting.
 */
enum class SettingsRowId(
    val category: SettingsCategory,
    @StringRes val title: Int,
    @StringRes val keywords: Int,
) {
    // ── Appearance ──────────────────────────────────────────────────────────────────────────────
    THEME(SettingsCategory.APPEARANCE, R.string.theme, R.string.theme_desc),
    GRID_DENSITY(SettingsCategory.APPEARANCE, R.string.grid_density, R.string.grid_density_desc),
    ANIMATION_INTENSITY(
        SettingsCategory.APPEARANCE,
        R.string.animation_intensity,
        R.string.animation_intensity_desc,
    ),
    AMOLED(SettingsCategory.APPEARANCE, R.string.amoled_mode, R.string.amoled_desc),
    DYNAMIC_COLORS(
        SettingsCategory.APPEARANCE,
        R.string.dynamic_colors,
        R.string.dynamic_colors_desc,
    ),
    APP_LANGUAGE(SettingsCategory.APPEARANCE, R.string.app_language, R.string.app_language_desc),

    // ── Home screen ─────────────────────────────────────────────────────────────────────────────
    DEFAULT_TAB(SettingsCategory.HOME, R.string.default_tab, R.string.default_tab_desc),
    SHOW_REINSTALL_CARD(
        SettingsCategory.HOME,
        R.string.show_reinstall_card,
        R.string.show_reinstall_card_desc,
    ),
    SHOW_INSTALLER_TILE(
        SettingsCategory.HOME,
        R.string.show_installer_tile,
        R.string.show_installer_tile_desc,
    ),
    SHOW_EXTENSIONS_TILE(
        SettingsCategory.HOME,
        R.string.show_extensions_tile,
        R.string.show_extensions_tile_desc,
    ),

    // ── Customization ───────────────────────────────────────────────────────────────────────────
    APP_INFO_ACTIONS(
        SettingsCategory.CUSTOMIZATION,
        R.string.customization_app_info_actions,
        R.string.customization_app_info_actions_desc,
    ),

    // ── Freezer ─────────────────────────────────────────────────────────────────────────────────
    AUTO_FREEZE(SettingsCategory.FREEZER, R.string.auto_freeze, R.string.auto_freeze_desc),
    SUSPEND_INSTEAD_OF_FREEZE(
        SettingsCategory.FREEZER,
        R.string.suspend_instead_of_freeze,
        R.string.suspend_instead_of_freeze_desc,
    ),
    SKIP_ROUTINE_FREEZE_CONFIRMATION(
        SettingsCategory.FREEZER,
        R.string.skip_routine_freeze_confirmation,
        R.string.skip_routine_freeze_confirmation_desc,
    ),
    ADD_FREEZER_TO_LAUNCHER(
        SettingsCategory.FREEZER,
        R.string.add_freezer_to_launcher,
        R.string.add_freezer_to_launcher_desc,
    ),
    UNFREEZE_ALL(
        SettingsCategory.FREEZER,
        R.string.unfreeze_all_apps,
        R.string.unfreeze_all_apps_desc,
    ),

    // ── Installing & sharing ────────────────────────────────────────────────────────────────────
    AUTO_REINSTALL(SettingsCategory.INSTALLING, R.string.auto_reinstall, R.string.auto_reinstall_desc),
    GRANT_ALL_PERMISSIONS(
        SettingsCategory.INSTALLING,
        R.string.grant_all_permissions,
        R.string.grant_all_permissions_desc,
    ),
    ANY_FILE_OPENER(
        SettingsCategory.INSTALLING,
        R.string.any_file_opener,
        R.string.any_file_opener_desc,
    ),

    // ── Security ────────────────────────────────────────────────────────────────────────────────
    BIOMETRIC_LOCK(SettingsCategory.SECURITY, R.string.biometric_lock, R.string.biometric_lock_desc),
    USAGE_ACCESS(
        SettingsCategory.SECURITY,
        R.string.usage_access,
        R.string.usage_access_needed_subtitle,
    ),
    NOTIFICATION_ACCESS(
        SettingsCategory.SECURITY,
        R.string.notification_access,
        R.string.notification_access_needed_subtitle,
    ),

    // ── Backup & restore ────────────────────────────────────────────────────────────────────────
    RESTORE(SettingsCategory.BACKUP, R.string.restore_title, R.string.restore_settings_desc),
    PASSPHRASE(
        SettingsCategory.BACKUP,
        R.string.passphrase_settings_title,
        R.string.passphrase_settings_desc,
    ),

    // ── Extensions ──────────────────────────────────────────────────────────────────────────────
    MANAGE_EXTENSIONS(
        SettingsCategory.EXTENSIONS,
        R.string.manage_extensions,
        R.string.manage_extensions_desc,
    ),

    // ── About & support ─────────────────────────────────────────────────────────────────────────
    SUPPORT_DEVELOPER(
        SettingsCategory.ABOUT,
        R.string.support_developer,
        R.string.support_developer_desc,
    ),
    VERSION(SettingsCategory.ABOUT, R.string.version, R.string.release_candidate),
    LINKS(SettingsCategory.ABOUT, R.string.github, R.string.source_code);

    companion object {
        /**
         * Grouped once, eagerly, rather than filtered per category on every composition.
         *
         * `entries.groupBy` preserves declaration order inside each group, which is what makes the
         * enum's own layout the source of truth for the order rows are drawn in.
         */
        private val byCategory: Map<SettingsCategory, List<SettingsRowId>> =
            entries.groupBy { it.category }

        fun rowsIn(category: SettingsCategory): List<SettingsRowId> =
            byCategory[category].orEmpty()
    }
}

// ── Enum labels ─────────────────────────────────────────────────────────────────────────────────
//
// Every closed-set setting's user-facing names, in one place, on the presentation side.
//
// [ThemeMode] used to carry its own `label()` returning "Light"/"Dark"/"System" as Kotlin string
// literals — a domain enum answering a question only the UI asks, in English, on all five locales,
// for the setting users change most. The privilege picker was worse: it rendered `mode.name`, so the
// segmented control read ROOT / SHIZUKU / DHIZUKU while `install_mode_root` and its two siblings had
// existed for the installer sheet all along.

@get:StringRes
internal val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.SYSTEM -> R.string.theme_system
    }

@get:StringRes
internal val AppGridDensity.labelRes: Int
    get() = when (this) {
        AppGridDensity.COMPACT -> R.string.grid_density_compact
        AppGridDensity.DEFAULT -> R.string.grid_density_default
        AppGridDensity.LARGE -> R.string.grid_density_large
    }

@get:StringRes
internal val AnimationIntensity.labelRes: Int
    get() = when (this) {
        AnimationIntensity.LOW -> R.string.animation_intensity_low
        AnimationIntensity.MEDIUM -> R.string.animation_intensity_medium
        AnimationIntensity.HIGH -> R.string.animation_intensity_high
    }

@get:StringRes
internal val PrivilegeMode.labelRes: Int
    get() = when (this) {
        PrivilegeMode.ROOT -> R.string.install_mode_root
        PrivilegeMode.SHIZUKU -> R.string.install_mode_shizuku
        PrivilegeMode.DHIZUKU -> R.string.install_mode_dhizuku
        // Never drawn: the engine picker only lists modes that probed available.
        PrivilegeMode.NONE -> R.string.settings_engine_none
    }

@DrawableRes
internal fun PrivilegeMode.iconRes(): Int = when (this) {
    PrivilegeMode.ROOT -> R.drawable.magisk_icon
    PrivilegeMode.SHIZUKU -> R.drawable.shizuku
    PrivilegeMode.DHIZUKU -> R.drawable.dhizuku
    PrivilegeMode.NONE -> R.drawable.shield
}

/**
 * The label for each entry, the only Android-side half of [AppLanguage].
 *
 * Split out so the tag list and the label list cannot drift apart: they used to be two hand-written
 * `when`/`listOf` blocks — one in the row, one in the picker sheet — and a language added to one but
 * not the other showed up as a row reading "System default" over a checkmark next to its own name.
 */
@get:StringRes
internal val AppLanguage.labelRes: Int
    get() = when (this) {
        AppLanguage.SystemDefault -> R.string.system_default
        AppLanguage.English -> R.string.english
        AppLanguage.Chinese -> R.string.chinese
        AppLanguage.French -> R.string.french
        AppLanguage.Spanish -> R.string.spanish
        AppLanguage.Arabic -> R.string.arabic
        AppLanguage.Portuguese -> R.string.portuguese
        AppLanguage.PortugueseBrazil -> R.string.portuguese_brazil
        AppLanguage.Polish -> R.string.polish
    }
