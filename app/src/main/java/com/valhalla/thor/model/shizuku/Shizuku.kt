package com.valhalla.thor.model.shizuku

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import com.valhalla.thor.BuildConfig
import moe.shizuku.server.IShizukuService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.text.NumberFormat
import java.util.Locale

object Shizuku {

    val isRoot get() = Shizuku.getUid() == 0
    private val callerPackage get() = if (isRoot) BuildConfig.APPLICATION_ID else "com.android.shell"

    private fun asInterface(className: String, original: IBinder): Any = Class.forName("$className\$Stub").run {
        if (Targets.P) HiddenApiBypass.invoke(this, null, "asInterface", ShizukuBinderWrapper(original))
        else getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinderWrapper(original))
    }

    private fun asInterface(className: String, serviceName: String): Any =
        asInterface(className, SystemServiceHelper.getSystemService(serviceName))

    val lockScreen
        get() = runCatching {
            val input = asInterface("android.hardware.input.IInputManager", Context.INPUT_SERVICE)
            val inject = input::class.java.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.java
            )
            val now = SystemClock.uptimeMillis()
            inject.invoke(
                input, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER, 0), 0
            )
            inject.invoke(
                input, KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_POWER, 0), 0
            )
            true
        }.getOrElse {
            it.printStackTrace()
            false
        }

    fun forceStopApp(context: Context,packageName: String): Boolean = runCatching {
        asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE).let {
            if (Targets.P) HiddenApiBypass.invoke(
                it::class.java, it, "forceStopPackage", packageName, Packages(context).myUserId
            ) else it::class.java.getMethod(
                "forceStopPackage", String::class.java, Int::class.java
            ).invoke(
                it, packageName, Packages(context).myUserId
            )
        }
        true
    }.getOrElse {
        it.printStackTrace()
        false
    }

    fun setAppDisabled(context: Context, packageName: String, disabled: Boolean): Boolean {
        Packages(context).getApplicationInfoOrNull(packageName) ?: return false
        if (disabled) forceStopApp(context,packageName)
        runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            val newState = when {
                !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                isRoot -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            pm::class.java.getMethod(
                "setApplicationEnabledSetting",
                String::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                String::class.java
            ).invoke(pm, packageName, newState, 0, Packages(context).myUserId, BuildConfig.APPLICATION_ID)
        }.onFailure {
            if(it is InvocationTargetException){
                Shizuku.addBinderReceivedListener {
                    if(Shizuku.pingBinder()){
                        setAppDisabled(context,packageName,disabled)
                    }else {
                        Log.d("Shizuku", "setAppDisabled: failed to get shizuku ")
                    }
                }
                Shizuku.addBinderDeadListener {
                    if(Shizuku.pingBinder()){
                        setAppDisabled(context,packageName,disabled)
                    }else {
                        Log.d("Shizuku", "setAppDisabled: failed to get shizuku ")
                    }
                }
                Shizuku.requestPermission(1001)
            }
            it.printStackTrace()
        }
        return Packages(context).isAppDisabled(packageName) == disabled
    }

    fun setAppHidden(context: Context,packageName: String, hidden: Boolean): Boolean {
        Packages(context).getApplicationInfoOrNull(packageName) ?: return false
        if (hidden) forceStopApp(context,packageName)
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            pm::class.java.getMethod(
                "setApplicationHiddenSettingAsUser", String::class.java, Boolean::class.java, Int::class.java
            ).invoke(pm, packageName, hidden, Packages(context).myUserId) as Boolean
        }.getOrElse {
            it.printStackTrace()
            false
        }
    }

    fun setAppSuspended(context: Context, packageName: String, suspended: Boolean): Boolean {
        Packages(context).getApplicationInfoOrNull(packageName) ?: return false
        if (Targets.P) setAppRestricted(context, packageName, suspended)
        if (suspended) forceStopApp(context,packageName)
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            (when {
                Targets.U -> runCatching {
                    HiddenApiBypass.invoke(
                        pm::class.java,
                        pm,
                        "setPackagesSuspendedAsUser",
                        arrayOf(packageName),
                        suspended,
                        null,
                        null,
                        if (suspended) suspendDialogInfo else null,
                        0,
                        callerPackage,
                        Packages(context).myUserId /*suspendingUserId*/,
                        Packages(context).myUserId /*targetUserId*/
                    )
                }.getOrElse {
                    if (it is NoSuchMethodException) setPackagesSuspendedAsUserSinceQ(context,pm, packageName, suspended)
                    else throw it
                }

                Targets.Q -> runCatching {
                    setPackagesSuspendedAsUserSinceQ(context,pm, packageName, suspended)
                }.getOrElse {
                    if (it is NoSuchMethodException) setPackagesSuspendedAsUserSinceP(context,pm, packageName, suspended)
                    else throw it
                }

                Targets.P -> setPackagesSuspendedAsUserSinceP(context,pm, packageName, suspended)

                else -> return false
            } as Array<*>).isEmpty()
        }.getOrElse {
            it.printStackTrace()
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setPackagesSuspendedAsUserSinceQ(context: Context,pm: Any, packageName: String, suspended: Boolean): Any =
        HiddenApiBypass.invoke(
            pm::class.java,
            pm,
            "setPackagesSuspendedAsUser",
            arrayOf(packageName),
            suspended,
            null,
            null,
            if (suspended) suspendDialogInfo else null,
            callerPackage,
            Packages(context).myUserId
        )

    @RequiresApi(Build.VERSION_CODES.P)
    private fun setPackagesSuspendedAsUserSinceP(context: Context,pm: Any, packageName: String, suspended: Boolean): Any =
        HiddenApiBypass.invoke(
            pm::class.java,
            pm,
            "setPackagesSuspendedAsUser",
            arrayOf(packageName),
            suspended,
            null,
            null,
            null /*dialogMessage*/,
            callerPackage,
            Packages(context).myUserId
        )

    private val suspendDialogInfo: Any
        @RequiresApi(Build.VERSION_CODES.Q) @SuppressLint("PrivateApi") get() = HiddenApiBypass.newInstance(
            Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
        ).let {
            HiddenApiBypass.invoke(it::class.java, it, "setNeutralButtonAction", 1 /*BUTTON_ACTION_UNSUSPEND*/)
            HiddenApiBypass.invoke(it::class.java, it, "build")
        }

    @SuppressLint("PrivateApi")
    fun clearCache(packageName: String): Boolean {
        return try {
            val pm = asInterface("android.content.pm.IPackageManager", "package")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.invoke(
                    pm::class.java,
                    pm,
                    "deleteApplicationCacheFiles",
                    packageName,
                    null /* IPackageDataObserver */
                )
            } else {
                val method = pm::class.java.getMethod(
                    "deleteApplicationCacheFiles",
                    String::class.java,
                    Class.forName("android.content.pm.IPackageDataObserver")
                )
                method.invoke(pm, packageName, null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getTotalCacheSizeWithShizuku(): Long {
        var totalCacheBytes = 0L
        // Assuming you have a function to execute shell commands via Shizuku
        val result = execute("dumpsys diskstats")

        result.second?.lines()?.forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("Cache Size:")) {
                try {
                    // Example line: "Cache Size: 1,234,567"
                    val sizeString = trimmedLine.substringAfter(":").trim()
                    // Use NumberFormat to handle commas in the string
                    val bytes = NumberFormat.getNumberInstance(Locale.US).parse(sizeString)?.toLong() ?: 0L
                    totalCacheBytes += bytes
                } catch (e: Exception) {
                    // Log error if parsing fails for a line
                    e.printStackTrace()
                }
            }
        }
        return totalCacheBytes
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(context: Context, packageName: String, restricted: Boolean): Boolean = runCatching {
        val appops = asInterface("com.android.internal.app.IAppOpsService", Context.APP_OPS_SERVICE)
        HiddenApiBypass.invoke(
            appops::class.java,
            appops,
            "setMode",
            HiddenApiBypass.invoke(AppOpsManager::class.java, null, "strOpToOp", "android:run_any_in_background"),
            Packages(context).packageUid(packageName),
            packageName,
            if (restricted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
        )
        true
    }.getOrElse {
        it.printStackTrace()
        false
    }

    fun uninstallApp(context: Context,packageName: String): Boolean =
        execute("pm ${if (Packages(context).canUninstallNormally(packageName)) "uninstall" else "uninstall --user current"} $packageName").first == 0

    fun reinstallApp(packageName: String): Boolean =
        execute("pm install-existing --user current $packageName").first == 0

    fun execute(command: String, root: Boolean = isRoot): Pair<Int, String?> = runCatching {
        IShizukuService.Stub.asInterface(Shizuku.getBinder()).newProcess(arrayOf(if (root) "su" else "sh"), null, null)
            .run {
                ParcelFileDescriptor.AutoCloseOutputStream(outputStream).use {
                    it.write(command.toByteArray())
                }
                waitFor() to inputStream.text.ifBlank { errorStream.text }.also { destroy() }
            }
    }.getOrElse { -1 to it.stackTraceToString() }

    private val ParcelFileDescriptor.text
        get() = ParcelFileDescriptor.AutoCloseInputStream(this).use { it.bufferedReader().readText() }
}