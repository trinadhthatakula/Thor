package com.valhalla.thor.data.launcher

/** Pure contract shared between the shortcut publisher and the launch trampoline. */
object FreezerShortcutContract {
    const val EXTRA_ACTION = "com.valhalla.thor.extra.FREEZER_SHORTCUT_ACTION"
    const val EXTRA_PACKAGE = "com.valhalla.thor.extra.FREEZER_SHORTCUT_PACKAGE"

    const val ACTION_LAUNCH = "launch"
    const val ACTION_FREEZE_ALL = "freeze_all"
    const val ACTION_UNFREEZE_ALL = "unfreeze_all"

    const val SHORTCUT_FREEZE_ALL = "freezer_freeze_all"
    const val SHORTCUT_UNFREEZE_ALL = "freezer_unfreeze_all"
    private const val APP_SHORTCUT_PREFIX = "freezer_app_"

    /** Stable, package-scoped id for a per-app frozen-app shortcut. */
    fun appShortcutId(packageName: String): String = "$APP_SHORTCUT_PREFIX$packageName"

    /** Normalize a raw intent extra to a known action, or null if unrecognized. */
    fun parseAction(raw: String?): String? = when (raw) {
        ACTION_LAUNCH, ACTION_FREEZE_ALL, ACTION_UNFREEZE_ALL -> raw
        else -> null
    }
}
