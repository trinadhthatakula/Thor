package com.valhalla.thor.data.source.local.shizuku

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.valhalla.bypass.Bypass
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.text.NumberFormat
import java.util.Locale

object Shizuku {

    val isRoot get() = Shizuku.getUid() == 0

    private fun asInterface(className: String, original: IBinder): Any {
        val clazz = Class.forName("$className\$Stub")
        return Bypass.invoke(
            clazz,
            null,
            "asInterface",
            arrayOf(IBinder::class.java),
            ShizukuBinderWrapper(original)
        )
    }

    private fun asInterface(className: String, serviceName: String): Any =
        asInterface(className, SystemServiceHelper.getSystemService(serviceName))

    val lockScreen
        get() = runCatching {
            execute("input keyevent 26").first == 0
        }.getOrElse {
            it.printStackTrace()
            false
        }

    fun forceStopApp(context: Context, packageName: String): Boolean = runCatching {
        execute("am force-stop --user ${Packages(context).myUserId} $packageName").first == 0
    }.getOrElse {
        it.printStackTrace()
        false
    }

    fun setAppDisabled(context: Context, packageName: String, disabled: Boolean): Boolean {
        Packages(context).getApplicationInfoOrNull(packageName) ?: return false
        val userId = Packages(context).myUserId
        val command = if (disabled) {
            "pm disable-user --user $userId $packageName"
        } else {
            "pm enable --user $userId $packageName"
        }
        val result = execute(command)

        if (result.first != 0) {
            // Fallback to Bypass reflection
            runCatching {
                val pm = asInterface("android.content.pm.IPackageManager", "package")
                val newState = when {
                    !disabled -> android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    isRoot -> android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else -> android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                }
                Bypass.invoke<Any?>(
                    pm.javaClass,
                    pm,
                    "setApplicationEnabledSetting",
                    arrayOf(String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java),
                    packageName,
                    newState,
                    0,
                    userId,
                    com.valhalla.thor.BuildConfig.APPLICATION_ID
                )
            }.onFailure { it.printStackTrace() }
        }

        return Packages(context).isAppDisabled(packageName) == disabled
    }

    fun setAppHidden(context: Context, packageName: String, hidden: Boolean): Boolean {
        Packages(context).getApplicationInfoOrNull(packageName) ?: return false
        val userId = Packages(context).myUserId
        val command = if (hidden) {
            "pm hide --user $userId $packageName"
        } else {
            "pm unhide --user $userId $packageName"
        }
        return execute(command).first == 0
    }

    fun setAppSuspended(context: Context, packageName: String, suspended: Boolean): Boolean {
        Packages(context).getApplicationInfoOrNull(packageName) ?: return false
        val userId = Packages(context).myUserId
        val command = if (suspended) {
            "pm suspend --user $userId $packageName"
        } else {
            "pm unsuspend --user $userId $packageName"
        }
        return execute(command).first == 0
    }

    fun clearCache(packageName: String): Boolean =
        execute("pm clear $packageName").first == 0

    fun getTotalCacheSizeWithShizuku(): Long {
        var totalCacheBytes = 0L
        val result = execute("dumpsys diskstats")

        result.second?.lines()?.forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("Cache Size:")) {
                try {
                    val sizeString = trimmedLine.substringAfter(":").trim()
                    val bytes =
                        NumberFormat.getNumberInstance(Locale.US).parse(sizeString)?.toLong() ?: 0L
                    totalCacheBytes += bytes
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return totalCacheBytes
    }

    fun setAppRestricted(packageName: String, restricted: Boolean): Boolean =
        execute("appops set $packageName RUN_ANY_IN_BACKGROUND ${if (restricted) "ignore" else "allow"}").first == 0

    fun uninstallApp(context: Context, packageName: String): Boolean =
        execute("pm ${if (Packages(context).canUninstallNormally(packageName)) "uninstall" else "uninstall --user current"} $packageName").first == 0

    fun reinstallApp(packageName: String): Boolean =
        execute("pm install-existing --user current $packageName").first == 0

    fun execute(command: String, root: Boolean = isRoot): Pair<Int, String?> = runCatching {
        IShizukuService.Stub.asInterface(Shizuku.getBinder())
            .newProcess(arrayOf(if (root) "su" else "sh"), null, null)
            .run {
                ParcelFileDescriptor.AutoCloseOutputStream(outputStream).use {
                    it.write(command.toByteArray())
                }
                waitFor() to inputStream.text.ifBlank { errorStream.text }.also { destroy() }
            }
    }.getOrElse { -1 to it.stackTraceToString() }

    private val ParcelFileDescriptor.text
        get() = ParcelFileDescriptor.AutoCloseInputStream(this)
            .use { it.bufferedReader().readText() }
}
