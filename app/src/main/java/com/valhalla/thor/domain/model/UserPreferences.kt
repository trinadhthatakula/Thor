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

    // Freezer Prompts
    val hasShownDisabledAppsPrompt: Boolean = false,

    // Support Developer Prompt
    val hasShownSupportDeveloperPrompt: Boolean = false,

    // Detailed View Mode
    val useDetailedView: Boolean = true,

    // Animation Intensity
    val animationIntensity: AnimationIntensity = AnimationIntensity.MEDIUM
)


