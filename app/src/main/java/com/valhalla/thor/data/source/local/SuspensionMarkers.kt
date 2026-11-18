// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import android.annotation.SuppressLint
import android.content.Context
import android.os.PersistableBundle
import java.util.UUID

/** A per-package/user nonce detects another shell tool overwriting Thor's shared owner entry. */
class SuspensionMarkers(private val context: Context) {
    private val preferences = context.getSharedPreferences("suspension_ownership", Context.MODE_PRIVATE)

    fun token(packageName: String, userId: Int): String? = preferences.getString("$userId/$packageName", null)

    // KTX edit discards commit success; the marker must be durable before the platform write.
    @SuppressLint("UseKtx")
    @Synchronized
    fun extras(packageName: String, userId: Int): PersistableBundle {
        val token = token(packageName, userId) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("$userId/$packageName", it).commit())
        }
        return PersistableBundle().apply { putString(KEY, token) }
    }

    fun readToken(packageName: String, userId: Int): String? {
        require(userId == thorUserId)
        val launcher = context.getSystemService(android.content.pm.LauncherApps::class.java)
        return launcher.getSuspendedPackageLauncherExtras(packageName, android.os.Process.myUserHandle())?.getString(KEY)
    }

    companion object {
        const val KEY = "com.valhalla.thor.suspension_token"
    }
}
