// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.valhalla.thor.data.gateway.userIdOf
import com.valhalla.thor.domain.model.FreezerUser

private val intType = Int::class.javaPrimitiveType
private val booleanType = Boolean::class.javaPrimitiveType
private const val MANAGED_PROFILE = "android.os.usertype.profile.MANAGED"
private const val SECONDARY_USER = "android.os.usertype.full.SECONDARY"

/** Both callers provide an already-authorized platform binder. Keep adapter records small. */
@SuppressLint("PrivateApi")
internal fun freezerPlatformUsers(manager: Any, currentUser: Int): List<Bundle> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    val api = Class.forName("android.os.IUserManager")
    val allUsers = try {
        api.getMethod("getUsers", booleanType, booleanType, booleanType).invoke(manager, true, true, true)
    } catch (_: NoSuchMethodException) {
        api.getMethod("getUsers", booleanType).invoke(manager, true)
    }
    val users = (allUsers as? List<*>)?.filterNotNull().orEmpty()
    val current = users.firstOrNull { it.javaClass.getField("id").getInt(it) == currentUser } ?: return emptyList()
    if (current.javaClass.getMethod("isAdmin").invoke(current) != true) return emptyList()
    return users.mapNotNull { user ->
        val type = user.javaClass
        val id = type.getField("id").getInt(user)
        if (id == currentUser || type.getMethod("isEnabled").invoke(user) != true ||
            type.getField("partial").getBoolean(user) || type.getField("preCreated").getBoolean(user)
        ) return@mapNotNull null
        val userType = type.getField("userType").get(user)
        if (userType != SECONDARY_USER && userType != MANAGED_PROFILE) return@mapNotNull null
        val work = userType == MANAGED_PROFILE
        if (work && (type.getField("profileGroupId").getInt(user) != currentUser ||
                api.getMethod("isQuietModeEnabled", intType).invoke(manager, id) == true)
        ) return@mapNotNull null
        if (api.getMethod("hasUserRestriction", String::class.java, intType)
                .invoke(manager, "no_control_apps", id) == true
        ) return@mapNotNull null
        Bundle().apply {
            putInt("id", id)
            putInt("serial", type.getField("serialNumber").getInt(user))
            putString("name", (type.getField("name").get(user) as? String)?.filterNot(Char::isISOControl)?.take(100))
            putBoolean("work_profile", work)
        }
    }
}

internal fun Bundle.toFreezerUser(): FreezerUser? {
    val id = getInt("id", -1)
    val serial = getInt("serial", -1)
    if (id < 0 || serial < 0) return null
    return FreezerUser(id, serial, getString("name").orEmpty(), getBoolean("work_profile"))
}

@SuppressLint("PrivateApi")
internal fun freezerApplicationInfo(manager: Any, packageName: String, userId: Int): ApplicationInfo? {
    val api = Class.forName("android.content.pm.IPackageManager")
    val flags = PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE
    val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        api.getMethod("getApplicationInfo", String::class.java, Long::class.javaPrimitiveType, intType)
            .invoke(manager, packageName, flags.toLong(), userId)
    } else {
        api.getMethod("getApplicationInfo", String::class.java, intType, intType)
            .invoke(manager, packageName, flags, userId)
    } as? ApplicationInfo
    return result?.takeIf {
        it.packageName == packageName && userIdOf(it.uid) == userId &&
            (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0
    }
}


@SuppressLint("PrivateApi")
internal fun freezerEnabledSetting(manager: Any, packageName: String, userId: Int): Int =
    Class.forName("android.content.pm.IPackageManager")
        .getMethod("getApplicationEnabledSetting", String::class.java, intType)
        .invoke(manager, packageName, userId) as Int
