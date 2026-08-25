// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

data class UserPreferences(
    // App List Sorting & Filtering
    val appSortBy: SortBy = SortBy.NAME,
    val appSortOrder: SortOrder = SortOrder.ASCENDING,
    val appFilterType: FilterType = FilterType.Source,
    val appSelectedFilter: String = "All",

    // Navigation — the tab Thor opens on at launch
    val defaultTab: DefaultTab = DefaultTab.HOME,

    // Home Screen Config
    val showReinstallAllCard: Boolean = true,

    // Which of the two always-available Home tiles the user wants there. Both default to shown —
    // hiding a tile only removes the shortcut, never the feature: Installer still handles APK
    // intents and Extensions keeps its Settings entry.
    val showInstallerTile: Boolean = true,
    val showExtensionsTile: Boolean = true,

    // Theme
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = false,
    val useAmoled: Boolean = false,

    // Security
    val biometricLockEnabled: Boolean = false,

    // Work Mode
    val preferredPrivilegeMode: PrivilegeMode? = null,

    // Localization
    val language: String? = null, // null means System Default

    // Auto Freeze
    val autoFreezeEnabled: Boolean = false,

    // Freezer action mode: FREEZE = pm disable, SUSPEND = pm suspend
    val freezerMode: FreezerMode = FreezerMode.FREEZE,

    // Add Freezer to launcher (home-screen shortcuts for frozen apps)
    val addFreezerToLauncher: Boolean = false,

    // Skip the freeze confirmation for system apps at FreezeTier.NORMAL — the dialog someone
    // debloating a fresh device answers forty times in a row. Reaches nothing else: EXPERT still
    // warns and BLOCKED is still refused. See `freezeNeedsConfirmation`.
    val skipRoutineFreezeConfirmation: Boolean = false,

    // Freezer Prompts
    val hasShownDisabledAppsPrompt: Boolean = false,

    // Support Developer Prompt
    val hasShownSupportDeveloperPrompt: Boolean = false,

    // Animation Intensity
    val animationIntensity: AnimationIntensity = AnimationIntensity.MEDIUM,

    // Grid/List View modes
    val appListIsGrid: Boolean = true,
    val freezerIsGrid: Boolean = true,

    // How tightly the grids pack. DEFAULT is today's rendering to the dp — see AppGridDensity.
    val appGridDensity: AppGridDensity = AppGridDensity.DEFAULT,

    // Extensions (the Settings entry is shown only with an active privilege; the manager itself is
    // gated behind a one-time liability-consent sheet on first open).
    val extensionsUnlocked: Boolean = false,
    val extensionConsentAccepted: Boolean = false,

    // Per-component control (App info → Components). Disabling a component is the one action in Thor
    // that can break an app without the app ever appearing changed — it stays installed, enabled and
    // launchable while some part of it silently stops working. The first disable therefore asks once,
    // and this records that it was answered. Read-only actions (Open, Force open, Stop now) never
    // consult it; nothing here gates them.
    val componentControlConsentAccepted: Boolean = false,

    // Auto Reinstall Config
    val autoReinstallEnabled: Boolean = false,

    // Export destination (persisted SAF tree URI; null = default Downloads/Thor)
    val exportDirUri: String? = null,

    // AppInfo Sheet Actions Customization (reordered action list and hidden action set)
    val appInfoActionsOrder: List<AppInfoActionId> = AppInfoActionId.DEFAULT_ORDER,
    val hiddenAppInfoActions: Set<AppInfoActionId> = emptySet(),

    /**
     * True when the values above are Thor's defaults rather than the user's, because the settings
     * store could not be read or had to be thrown away and replaced after corruption.
     *
     * Not a preference — nothing writes it and it is never persisted — but it belongs on the
     * snapshot rather than beside it, because it is the only thing that distinguishes "the user
     * turned everything off" from "we could not find out what the user chose". [biometricLockEnabled]
     * is why that distinction has to travel: `false` there is both the common case and, after a
     * failed read, a silently disarmed app lock.
     */
    val settingsLost: Boolean = false
)


