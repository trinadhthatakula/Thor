// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.appops

import com.valhalla.thor.data.gateway.userIdOf
import com.valhalla.thor.domain.model.AppOpMode
import com.valhalla.thor.domain.model.AppOpScope

/** Shell commands for one catalog-validated operation in one Android user. */
internal object AppOpsCommands {
    // A numeric package token would be parsed as a UID by AppOpsService. Keeping the rest to
    // package-name characters also makes every interpolated argument safe without shell quoting.
    private val packageNamePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*$")
    private val formattedUidPattern = Regex("^u[0-9]+[as][0-9]+.*$")
    private val daemonTargets = setOf("root", "shell", "dumpstate", "media", "audioserver", "cameraserver")

    fun getPackage(packageName: String, userId: Int, code: Int? = null): String {
        validatePackage(packageName)
        validateUser(userId)
        code?.let(::validateCode)
        return "appops get --user $userId $packageName${code?.let { " $it" }.orEmpty()}"
    }

    /** A numeric UID works on API 28, where `--uid PACKAGE` does not exist. */
    fun getUid(uid: Int, userId: Int, code: Int? = null): String {
        validateUid(uid, userId)
        code?.let(::validateCode)
        return "appops get --user $userId $uid${code?.let { " $it" }.orEmpty()}"
    }

    fun setMode(
        packageName: String,
        uid: Int,
        userId: Int,
        code: Int,
        scope: AppOpScope,
        mode: AppOpMode,
    ): String {
        validatePackage(packageName)
        validateUid(uid, userId)
        validateCode(code)
        val token = requireNotNull(mode.shellToken) { "Unknown App Ops mode cannot be written" }
        val target = when (scope) {
            AppOpScope.PACKAGE -> packageName
            AppOpScope.UID -> uid.toString()
        }
        return "appops set --user $userId $target $code $token"
    }

    private fun validatePackage(packageName: String) {
        require(packageNamePattern.matches(packageName)) { "Invalid package name" }
        // AppOpsService also accepts formatted UIDs such as u0a123/u10s1000, including suffixes.
        require(packageName.contains('.') || !formattedUidPattern.matches(packageName)) {
            "Package name would be interpreted as a UID"
        }
        require(packageName !in daemonTargets) { "Package name would resolve to a system daemon" }
    }

    private fun validateUser(userId: Int) {
        require(userId >= 0) { "Invalid Android user" }
    }

    private fun validateUid(uid: Int, userId: Int) {
        validateUser(userId)
        require(uid >= 0 && userIdOf(uid) == userId) { "UID does not belong to Android user" }
    }

    private fun validateCode(code: Int) {
        require(code >= 0) { "Invalid App Ops code" }
    }
}
