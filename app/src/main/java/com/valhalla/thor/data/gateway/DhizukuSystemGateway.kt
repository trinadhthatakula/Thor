package com.valhalla.thor.data.gateway

import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.data.source.local.dhizuku.DhizukuReflector
import com.valhalla.thor.data.source.local.dhizuku.DhizukuHelper

class DhizukuSystemGateway(
    private val reflector: DhizukuReflector
) : SystemGateway {

    override suspend fun isRootAvailable() = false

    override fun isShizukuAvailable(): Boolean = false

    override fun isDhizukuAvailable(): Boolean {
        return DhizukuHelper.isDhizukuAvailable()
    }

    override suspend fun forceStopApp(packageName: String): Result<Unit> {
        return try {
            reflector.forceStop(packageName)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearCache(packageName: String): Result<Unit> {
        return try {
            reflector.clearCache(packageName)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> {
        return try {
            reflector.setAppEnabled(packageName, !isDisabled)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun rebootDevice(reason: String): Result<Unit> {
        return Result.failure(Exception("Reboot requires Root. Dhizuku cannot perform this action easily."))
    }

    override suspend fun uninstallApp(packageName: String): Result<Unit> {
        return if (reflector.uninstallApp(packageName)) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Uninstall failed"))
        }
    }

    override suspend fun installApp(apkPath: String, canDowngrade: Boolean): Result<Unit> {
        val result = DhizukuHelper.execute("pm install -r -g${if (canDowngrade) " -d" else ""} ${com.valhalla.superuser.ShellUtils.escapedString(apkPath)}")
        return if (result.first == 0) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Dhizuku install failed: ${result.second}"))
        }
    }

    override suspend fun getAppCacheSize(packageName: String): Long {
        return 0L
    }
}