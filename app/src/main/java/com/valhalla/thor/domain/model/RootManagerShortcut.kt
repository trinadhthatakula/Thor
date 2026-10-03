// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.Locale

/** An app explicitly selected as a shortcut; its identity does not establish root access. */
data class RootManagerShortcut(
    val packageName: String,
    val label: String,
)

object RootManagerShortcuts {
    /** Revalidates a saved shortcut without inferring a root manager from its name or label. */
    fun resolve(pm: PackageManager, packageName: String?): RootManagerShortcut? {
        if (packageName.isNullOrBlank()) return null
        return try {
            val app = pm.getApplicationInfo(packageName, 0)
            if (!app.enabled || app.flags and ApplicationInfo.FLAG_INSTALLED == 0) return null
            val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return null
            val launcher = pm.resolveActivity(launchIntent, 0)?.activityInfo ?: return null
            if (!launcher.enabled || !launcher.exported) return null
            RootManagerShortcut(
                packageName = packageName,
                label = pm.getApplicationLabel(app).toString().ifBlank { packageName },
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: RuntimeException) {
            // PackageManager can become unavailable while the app is being replaced.
            null
        }
    }

    /** Lists launchable apps for explicit selection, without classifying them as root-capable. */
    fun candidates(pm: PackageManager): List<RootManagerShortcut> =
        launcherActivities(pm)
            .filter { it.enabled && it.exported }
            .mapNotNull { it.packageName }
            .distinct()
            .mapNotNull { resolve(pm, it) }
            .sortedWith(
                compareBy<RootManagerShortcut> { it.label.lowercase(Locale.ROOT) }
                    .thenBy { it.packageName },
            )

    internal fun launcherActivities(pm: PackageManager): List<ActivityInfo> =
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).mapNotNull { it.activityInfo }
}
