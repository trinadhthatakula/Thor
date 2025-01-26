package com.valhalla.thor.model

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import android.util.Log.e
import android.widget.Toast
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import com.valhalla.thor.BuildConfig
import java.io.File

private const val TAG = "SuCli"

object SuCli {
    val SHELL: Shell = createRootShell()
    val GLOBAL_MNT_SHELL: Shell = createRootShell(true)
}

fun getRootShell(globalMnt: Boolean = false): Shell {
    return if (globalMnt) SuCli.GLOBAL_MNT_SHELL else {
        SuCli.SHELL
    }
}

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun Uri.getFileName(context: Context): String? {
    var fileName: String? = null
    val contentResolver: ContentResolver = context.contentResolver
    val cursor: Cursor? = contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            fileName = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return fileName
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    return try {
        if (globalMnt) {
            builder.build("su")
        } else {
            builder.build("su", "-mm")
        }
    } catch (e: Throwable) {
        e(TAG, "su failed: ", e)
        builder.build("sh")
    }
}


private fun flashWithIO(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    return withNewRootShell {
        newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    }
}


fun reboot(reason: String = "") {
    val shell = getRootShell()
    if (reason == "recovery") {
        // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
        ShellUtils.fastCmd(shell, "/system/bin/input keyevent 26")
    }
    ShellUtils.fastCmd(shell, "/system/bin/svc power reboot $reason || /system/bin/reboot $reason")
}

fun rootAvailable(): Boolean {
    val shell = getRootShell()
    return shell.isRoot
}

fun isAbDevice(): Boolean {
    val shell = getRootShell()
    return ShellUtils.fastCmd(shell, "getprop ro.build.ab_update").trim().toBoolean()
}

fun isInitBoot(): Boolean {
    return !Os.uname().release.contains("android12-")
}

fun overlayFsAvailable(): Boolean {
    val shell = getRootShell()
    // check /proc/filesystems
    return ShellUtils.fastCmdResult(shell, "cat /proc/filesystems | grep overlay")
}

fun hasMagisk(): Boolean {
    val shell = getRootShell(true)
    val result = shell.newJob().add("which magisk").exec()
    Log.i(TAG, "has magisk: ${result.isSuccess}")
    return result.isSuccess
}

fun forceStopApp(packageName: String) {
    val shell = getRootShell()
    val result = shell.newJob().add("am force-stop $packageName").exec()
    Log.i(TAG, "force stop $packageName result: $result")
}

fun shareApp(appInfo: AppInfo, context: Context) {
    appInfo.packageName?.let { packageName ->
        ShellUtils.fastCmd("pm path \"${packageName}\" | sed 's/package://' | tr '\\n' ' '")
            .trim()
            .split(" ")
            .firstOrNull { it.contains("base.apk") }
            ?.let { baseApkPath ->
                val file = File(baseApkPath)
                val tempFolder = File(context.filesDir, "shareApp")
                val baseFile = File(tempFolder, appInfo.appName + ".apk")
                if (
                    (!tempFolder.exists() || tempFolder.delete())
                    && tempFolder.mkdirs()
                    && (!baseFile.exists() || baseFile.delete())
                    && baseFile.createNewFile()
                    && file.copyTo(baseFile)
                ) {
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.type = "application/vnd.android.package-archive"
                    intent.putExtra(
                        Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(
                            context,
                            BuildConfig.APPLICATION_ID + ".provider",
                            baseFile
                        )
                    )
                    context.startActivity(
                        Intent.createChooser(
                            intent,
                            "Share App using"
                        )
                    )
                } else {
                    Toast.makeText(context, "Failed to share app", Toast.LENGTH_SHORT).show()
                }
            }
    }

}

fun getSplits(packageName: String) {
    val apkFilePaths = ShellUtils
        .fastCmd("pm path \"$packageName\" | sed 's/package://' | tr '\\n' ' '")
        .trim()
        .split(" ")
    apkFilePaths.forEach { path ->
        Log.d(TAG, path)
    }
}

fun reInstallWithGoogle(appInfo: AppInfo, observer: (String) -> Unit, exit: () -> Unit) {
    appInfo.packageName?.let { packageName ->
        var failCounter = 0
        var successCounter = 0
        try {
            observer("Reinstalling with Google...")
            observer("Package: $packageName")
            val shell = getRootShell()
            observer("Root access found")
            val currentUser = ShellUtils.fastCmd(shell, "am get-current-user").trim()
            observer("Found User $currentUser")
            observer("Searching for any splits")
            val combinedPath =
                ShellUtils.fastCmd("pm path \"$packageName\" | sed 's/package://' | tr '\\n' ' '")
                    .trim()
            val apkFilePaths = combinedPath.split(" ")
            if (apkFilePaths.size > 1)
                observer("Found ${apkFilePaths.size} splits")
            else if (apkFilePaths.size == 1)
                observer("Found apk file at ${apkFilePaths.first()}")
            shell.newJob()
                .add("su -c pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 \"$combinedPath\" > /dev/null")
                .exec().let {
                    if (!it.isSuccess) {
                        failCounter++
                        observer("Failed to reinstall ${appInfo.appName}")
                    } else {
                        successCounter++
                        observer("Reinstalled ${appInfo.appName}")
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            observer("\n")
            observer("Special Thanks")
            observer("CIT, citra_standalone")
            observer("TSA")
            exit()
        }
    }
}

fun launchApp(packageName: String): Shell.Result {
    val shell = getRootShell()
    val result =
        shell.newJob()
            .add("cmd package resolve-activity --brief $packageName | tail -n 1 | xargs cmd activity start-activity -n")
            .exec()
    Log.i(TAG, "launch $packageName result: $result")
    return result
}

fun restartApp(packageName: String) {
    forceStopApp(packageName)
    launchApp(packageName)
}
