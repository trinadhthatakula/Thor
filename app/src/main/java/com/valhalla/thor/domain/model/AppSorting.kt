package com.valhalla.thor.domain.model

/**
 * Pure, side-effect-free app sorter (unit-tested). Unknown install sizes
 * (`null`) sort as smallest, so DESCENDING puts the biggest apps first.
 */
fun sortApps(apps: List<AppInfo>, sortBy: SortBy, order: SortOrder): List<AppInfo> {
    val comparator = when (sortBy) {
        SortBy.NAME -> compareBy<AppInfo> { it.appName?.lowercase() }
        SortBy.SIZE -> compareBy(nullsFirst<Long>()) { it.installSize }
        SortBy.INSTALL_DATE -> compareBy { it.firstInstallTime }
        SortBy.LAST_UPDATED -> compareBy { it.lastUpdateTime }
        SortBy.VERSION_CODE -> compareBy { it.versionCode }
        SortBy.VERSION_NAME -> compareBy { it.versionName }
        SortBy.TARGET_SDK_VERSION -> compareBy { it.targetSdk }
        SortBy.MIN_SDK_VERSION -> compareBy { it.minSdk }
    }
    return if (order == SortOrder.ASCENDING) apps.sortedWith(comparator)
    else apps.sortedWith(comparator).reversed()
}
