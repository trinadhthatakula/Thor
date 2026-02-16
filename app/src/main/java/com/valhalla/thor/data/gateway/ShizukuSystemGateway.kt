package com.valhalla.thor.data.gateway

import android.content.pm.PackageManager
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.shizuku.ShizukuReflector
import com.valhalla.thor.domain.gateway.SystemGateway
import rikka.shizuku.Shizuku

class ShizukuSystemGateway(
    private val reflector: ShizukuReflector
) : SystemGateway {

    override suspend fun isRootAvailable() = false

    override fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED && Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun forceStopApp(packageName: String): Result<Unit> {
        return runReflectiveAction { reflector.forceStop(packageName) }
    }

    override suspend fun clearCache(packageName: String): Result<Unit> {
        return runReflectiveAction { reflector.clearCache(packageName) }
    }

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> {
        return runReflectiveAction { reflector.setAppEnabled(packageName, !isDisabled) }
    }

    override suspend fun rebootDevice(reason: String): Result<Unit> {
        return Result.failure(Exception("Reboot requires Root. Shizuku cannot perform this action."))
    }

    override suspend fun uninstallApp(packageName: String): Result<Unit> {
        return if (reflector.uninstallApp(packageName)) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Uninstall failed"))
        }
    }

    override suspend fun installApp(apkPath: String): Result<Unit> {
        return if (reflector.installPackage(apkPath)) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Shizuku install failed. Ensure the file path is readable by Shell/ADB."))
        }
    }

    override suspend fun getAppCacheSize(packageName: String): Long {
        return 0L // Requires specialized logic
    }

    /**
     * Standardizes error handling for reflection calls.
     */
    private inline fun runReflectiveAction(action: () -> Unit): Result<Unit> {
        if (!isShizukuAvailable()) {
            return Result.failure(Exception("Shizuku is not available or permission denied."))
        }
        return try {
            action()
            Result.success(Unit)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                e.printStackTrace()
            Result.failure(e)
        }
    }
}
