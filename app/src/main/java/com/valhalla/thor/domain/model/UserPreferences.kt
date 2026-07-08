package com.valhalla.thor.domain.model

data class UserPreferences(
    // App List Sorting & Filtering
    val appSortBy: SortBy = SortBy.NAME,
    val appSortOrder: SortOrder = SortOrder.ASCENDING,
    val appFilterType: FilterType = FilterType.Source,
    val appSelectedFilter: String = "All",

    // Home Screen Config
    val showReinstallAllCard: Boolean = true,

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

    // Freezer Prompts
    val hasShownDisabledAppsPrompt: Boolean = false,

    // Support Developer Prompt
    val hasShownSupportDeveloperPrompt: Boolean = false,

    // Detailed View Mode
    val useDetailedView: Boolean = true,

    // Animation Intensity
    val animationIntensity: AnimationIntensity = AnimationIntensity.MEDIUM,

    // Grid/List View modes
    val appListIsGrid: Boolean = true,
    val freezerIsGrid: Boolean = true,

    // Extensions (the Settings entry is shown only with an active privilege; the manager itself is
    // gated behind a one-time liability-consent sheet on first open).
    val extensionsUnlocked: Boolean = false,
    val extensionConsentAccepted: Boolean = false,

    // Export destination (persisted SAF tree URI; null = default Downloads/Thor)
    val exportDirUri: String? = null
)


