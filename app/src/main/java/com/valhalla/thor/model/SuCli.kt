package com.valhalla.thor.model

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.system.Os
import android.util.Log
import android.util.Log.e
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.valhalla.superuser.CallbackList
import com.valhalla.superuser.Shell
import com.valhalla.superuser.ShellUtils
import com.valhalla.superuser.ShellUtils.fastCmd
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.model.shizuku.ElevatableState
import com.valhalla.thor.model.shizuku.Shizuku
import com.valhalla.thor.model.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.Path
import kotlin.io.path.name

//pid=$(adb shell ps | grep <package name> | cut -c11-15) ; adb logcat | grep $pid
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
        override fun onAddElement(e: String?) {
            onStdout(e ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(e: String?) {
            onStderr(e ?: "")
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
        fastCmd(shell, "/system/bin/input keyevent 26")
    }
    fastCmd(shell, "/system/bin/svc power reboot $reason || /system/bin/reboot $reason")
}

fun rootAvailable(): Boolean {
    try {
        val shell = getRootShell()
        return shell.isRoot
    } catch (_: Exception) {
        return false
    }
}

fun isAbDevice(): Boolean {
    val shell = getRootShell()
    return fastCmd(shell, "getprop ro.build.ab_update").trim().toBoolean()
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


fun shareSplitApks(
    appInfo: AppInfo,
    context: Context,
    observer: (String) -> Unit,
    exit: () -> Unit
) {
    appInfo.packageName.let { packageName ->
        observer("Sharing ${appInfo.appName}")
        observer("Split APKs found")
        observer("generating .Apks file")
        val tempFolder = File(context.filesDir, "shareApp")
        val appFolder = File(tempFolder, appInfo.formattedAppName())
        val apksFile = File(tempFolder, "${appInfo.formattedAppName()}.apks")
        val infoFile = File(appFolder, "info.json")
        val iconFile = File(appFolder, "icon.png")
        if (appFolder.exists() || appFolder.mkdirs()) {
            apksMetadataGenerator.generateJson(appInfo, infoFile)
            observer("App Metadata generated")
            if (getAppIcon(appInfo.packageName, context)?.toFile(iconFile) == true)
                observer("App Icon generated")
            else observer("Failed to generate App Icon")
            var copyCounter = 0
            val splitPaths =
                fastCmd(getRootShell(),"pm path \"${packageName}\" | sed 's/package://' | tr '\\n' ' '")
                    .trim()
                    .split(" ")
            splitPaths.forEach { path ->
                if (fastCmd(getRootShell(),"cp $path ${appFolder.absolutePath}/${Path(path).name}").isEmpty())
                    copyCounter++
                else
                    observer("Failed to copy ${Path(path).name}")
            }
            if (copyCounter == splitPaths.size) {
                observer("Split apks copied")
                observer("Compressing splits into single .Apks file")
                ZipOutputStream(BufferedOutputStream(apksFile.outputStream())).use { out ->
                    val data = ByteArray(1024)
                    appFolder.listFiles()?.forEach { file ->
                        FileInputStream(file).use { fi ->
                            BufferedInputStream(fi).use { origin ->
                                val entry = ZipEntry(file.name).apply {
                                    method = ZipEntry.DEFLATED
                                    size = file.length()
                                    crc = calculateCrc32(file)
                                    time = System.currentTimeMillis()
                                }
                                out.putNextEntry(entry)
                                while (true) {
                                    val readBytes = origin.read(data)
                                    if (readBytes == -1) {
                                        break
                                    }
                                    out.write(data, 0, readBytes)
                                }
                            }
                        }
                    }
                }
                observer("Apks file generated")
                observer("Deleting temp files")
                if (!appFolder.deleteRecursively())
                    observer("Failed to delete temp files")
                observer("Calling Share Intent")
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = "application/vnd.android.package-archive"
                intent.putExtra(
                    Intent.EXTRA_STREAM,
                    FileProvider.getUriForFile(
                        context,
                        BuildConfig.APPLICATION_ID + ".provider",
                        apksFile
                    )
                )
                context.startActivity(
                    Intent.createChooser(
                        intent,
                        "Share App using"
                    )
                )
                observer("exiting runner")
                exit()
            } else {
                observer("Failed to copy all splits")
                observer("exiting runner")
                exit()
            }
        }


    }
}

fun shareApp(appInfo: AppInfo, context: Context) {
    appInfo.packageName.let { packageName ->
        fastCmd(getRootShell(),"pm path \"${packageName}\" | sed 's/package://' | tr '\\n' ' '")
            .trim()
            .split(" ")
            .firstOrNull { it.contains("base.apk") || it.contains(appInfo.appName ?: "", true) }
            ?.let { baseApkPath ->
                try {
                    Log.i(TAG, "shareApp: $baseApkPath")
                    val tempFolder = File(context.filesDir, "shareApp")
                    val baseFile =
                        File(tempFolder, "${appInfo.formattedAppName()}_${appInfo.versionName}.apk")
                    if (fastCmd(getRootShell(),"cp $baseApkPath ${baseFile.absolutePath}").isEmpty()) {
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
                        val intent = Intent(Intent.ACTION_SEND)
                        intent.type = "application/vnd.android.package-archive"
                        intent.putExtra(
                            Intent.EXTRA_STREAM,
                            FileProvider.getUriForFile(
                                context,
                                BuildConfig.APPLICATION_ID + ".provider",
                                File(baseApkPath)
                            )
                        )
                        context.startActivity(
                            Intent.createChooser(
                                intent,
                                "Share App using"
                            )
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Failed to share app", Toast.LENGTH_SHORT).show()
                }
            }
    }

}

fun copyFile(source: File, destination: File): Boolean {
    try {
        if (source.exists() || source.createNewFile()) {
            destination.parentFile?.let { parentFile ->
                return if (parentFile.mkdirs()) {
                    if (destination.exists() || destination.createNewFile())
                        source.copyTo(destination)
                    else false
                } else false
            }
        }
        return false
    } catch (e: Exception) {
        e.printStackTrace()
        return false
    }
}

fun getSplits(packageName: String) {
    fastCmd("pm path \"$packageName\" | sed 's/package://' | tr '\\n' ' '")
        .trim()
        .split(" ")
}

fun reInstallWithGoogle(appInfo: AppInfo, observer: (String) -> Unit, exit: () -> Unit) {
    appInfo.packageName.let { packageName ->
        try {
            observer("Reinstalling with Google...")
            observer("Package: $packageName")
            val shell = getRootShell()
            val currentUser = fastCmd(shell, "am get-current-user").trim()
            observer("Found User $currentUser")
            observer("Searching for any splits")
            val combinedPath =
                fastCmd("pm path \"$packageName\" | sed 's/package://' | tr '\\n' ' '")
                    .trim()
            val apkFilePaths = combinedPath.split(" ")
            if (apkFilePaths.size > 1)
                observer("Found ${apkFilePaths.size} splits")
            else if (apkFilePaths.size == 1)
                observer("Found apk file at ${apkFilePaths.first()}")
            shell.newJob()
                .add("pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 \"$combinedPath\" > /dev/null")
                .exec().let {
                    if (!it.isSuccess) {
                        observer("Failed to reinstall ${appInfo.appName}")
                    } else {
                        observer("Reinstalled ${appInfo.appName}")
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            observer("\n")
            observer("Done Reinstalling ")
            exit()
        }
    }
}

fun reInstallAppsWithGoogle(appInfos: List<AppInfo>, observer: (String) -> Unit, exit: () -> Unit) {
    observer("Reinstalling with Google...")
    val shell = getRootShell()
    observer("Root access found")
    val currentUser = fastCmd(shell, "am get-current-user").trim()
    observer("Found User $currentUser")
    try {
        appInfos.forEach { appInfo ->
            appInfo.packageName.let { packageName ->
                val combinedPath =
                    fastCmd(getRootShell(),"pm path \"$packageName\" | sed 's/package://' | tr '\\n' ' '")
                        .trim()
                shell.newJob()
                    .add("pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 \"$combinedPath\" > /dev/null")
                    .exec().let {
                        if (!it.isSuccess) {
                            observer("Failed to reinstall ${appInfo.appName}")
                        } else {
                            observer("Reinstalled ${appInfo.appName}")
                        }
                    }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        observer("\n")
        exit()
    }

}

fun getPackageNameFromApk(context: Context, apkPath: String): String? {
    val packageManager = context.packageManager
    // The flag GET_META_DATA is sufficient for what we need.
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageArchiveInfo(apkPath, 0)
    }
    return packageInfo?.packageName
}

fun installWithRoot(apkPath: String, observer: (String) -> Unit, onCompleted: (Result<Boolean>) -> Unit) {
    val command = "pm install -r -g '$apkPath'"
    val result = fastCmd(getRootShell(),command)
    if (result.isEmpty()) {
        observer("Installed $apkPath")
        onCompleted(Result.success(true))
    } else {
        observer("Failed to install $apkPath")
        onCompleted(Result.failure(Exception("Failed to install $apkPath")))
    }
}

fun getTotalCacheSize(elevatableState: ElevatableState):Long {
    return when (elevatableState) {
        ElevatableState.SU -> {
            getTotalCacheSizeWithRoot()
        }
        ElevatableState.SHIZUKU_RUNNING -> {
            Shizuku.getTotalCacheSizeWithShizuku()
        }
        else -> 0L
    }
}

fun getTotalCacheSizeWithRoot(): Long {
    val command = "du -sc /data/data/*/cache | grep total"
    val result = fastCmd(getRootShell(), command)
    return try {
        // The size is the first value on the line, in kilobytes.
        val kilobytes =  result.trim().split("\\s+".toRegex()).firstOrNull()?.toLong() ?: 0L
        kilobytes * 1024 // Convert kilobytes to bytes
    } catch (e: Exception) {
        e.printStackTrace()
        0L
    }
}

@SuppressLint("SdCardPath")
suspend fun clearCache(
    vararg appInfos: AppInfo,
    observer: (String) -> Unit = {},
    exit: () -> Unit = {},
    elevatableState: ElevatableState = ElevatableState.NONE
) {
    Log.d(
        TAG,
        "clearCache: apps to clear = ${appInfos.size} with elevated state = $elevatableState"
    )
    withContext(Dispatchers.IO) {
        try {
            appInfos.forEach {
                if (elevatableState == ElevatableState.SU) {
                    Log.d(
                        TAG,
                        "clearCache: found private data dir for ${it.appName}, clearing it"
                    )
                    val res = fastCmd(
                        getRootShell(),
                        "rm -rf /data/data/${it.packageName}/cache"
                    )
                    if (res.isEmpty())
                        observer("Cleared cache for ${it.appName}")
                    else Log.d(
                        TAG,
                        "clearCache: failed to clear cache for ${it.appName} reason: $res"
                    )
                } else if (elevatableState == ElevatableState.SHIZUKU_RUNNING) {
                    if (Shizuku.clearCache(it.packageName)) {
                        observer("Cleared cache for ${it.appName}")
                    } else {
                        observer("Failed to clear cache for ${it.appName}")
                    }
                }
            }
        } catch (e: Exception) {
            observer(e.message ?: "something went wrong")
        } finally {
            exit()
        }
    }
}

suspend fun Context.disableApps(
    vararg appInfos: AppInfo,
    observer: (String) -> Unit = {},
    exit: () -> Unit = {},
    elevatableState: ElevatableState = ElevatableState.NONE
) {
    try {
        observer("Freezing apps...")
        appInfos.forEach { appInfo ->
            if (elevatableState == ElevatableState.SU)
                observer(fastCmd(getRootShell(), "pm disable ${appInfo.packageName}"))
            else if (elevatableState == ElevatableState.SHIZUKU_RUNNING) {
                if (Shizuku.setAppDisabled(
                        this,
                        appInfo.packageName,
                        true
                    ).not()
                ) {
                    observer("Failed to disable ${appInfo.appName}")
                } else {
                    observer("Disabled ${appInfo.appName}")
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        observer(e.message.toString())
    } finally {
        exit()
    }
}

suspend fun Context.enableApps(
    vararg appInfos: AppInfo,
    elevatableState: ElevatableState = ElevatableState.NONE,
    observer: (String) -> Unit = {},
    exit: () -> Unit = {}
) {
    try {
        observer("UnFreezing apps...")
        appInfos.forEach { appInfo ->
            if (elevatableState == ElevatableState.SU)
                observer(fastCmd(getRootShell(), "pm enable ${appInfo.packageName}"))
            else if (elevatableState == ElevatableState.SHIZUKU_RUNNING) {
                if (Shizuku.setAppDisabled(
                        this,
                        appInfo.packageName,
                        false
                    ).not()
                ) {
                    observer("Failed to enable ${appInfo.appName}")
                } else {
                    observer("Enabled ${appInfo.appName}")
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        observer(e.message.toString())
    } finally {
        exit()
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

fun commandExists(command: String): String {
    return fastCmd(
        "command -v $command || echo 'command not found'"
    )
}

fun copyFilesWithShell(sourcePath: String, destinationPath: String) = fastCmd(
    "cp $sourcePath $destinationPath"
)


fun convertAbxToXml(abxPath: String, xmlPath: String): String {
    return fastCmd(
        "abx2xml $abxPath  $xmlPath"
    )
}

fun convertXmlToAbx(xmlPath: String, abxPath: String): String {
    return fastCmd(
        "xml2abx $xmlPath $abxPath"
    )
}

fun modifyInstallersInPackagesXml(
    xmlPath: String,
    avoidOEMs: Boolean = true
): String {
    return fastCmd(
        "sed -i -E '/installer=/ {\n" +
                "      /installer=\"[^\"]*(miui|xiaomi|samsung)[^\"]*\"/! {\n" +
                "          s/(installer=\")[^\"]*(\")/\\1com.android.vending\\2/g\n" +
                "      }\n" +
                "  }' \"$xmlPath\""
    )
}

fun modifyInstallInitiatorInPackagesXml(
    xmlPath: String,
    avoidOEMs: Boolean = true
): String {
    return fastCmd(
        "sed -i -E '/installInitiator=/ {\n" +
                "      /installInitiator=\"[^\"]*(miui|xiaomi|samsung)[^\"]*\"/! {\n" +
                "          s/(installInitiator=\")[^\"]*(\")/\\1com.android.vending\\2/g\n" +
                "      }\n" +
                "  }' \"$xmlPath\""
    )
}

fun removeInstallOriginatorInPackagesXml(
    xmlPath: String
): String {
    return fastCmd(
        "sed -i -E 's/ installOriginator=\"[^\"]*\"//g' \"$xmlPath\""
    )
}

fun removeFile(path: String): String {
    return fastCmd("rm $path")
}

fun processPackagesXml(
    xmlPath: String,
    name: String = "Packages.xml",
    observer: (String) -> Unit
): Boolean {
    val tempFile = "${xmlPath}_temp.xml"
    val result = convertAbxToXml(xmlPath, tempFile)
    observer(if (result.isEmpty()) "Converted ABX to XML" else "failed to convert ABX to XML")
    if (result.isEmpty()) {
        val modResult = modifyInstallersInPackagesXml(tempFile) +
                modifyInstallInitiatorInPackagesXml(tempFile) +
                removeInstallOriginatorInPackagesXml(tempFile)
        if (modResult.isNotEmpty()) {
            observer("failed to edit $name reason: $modResult")
        } else {
            observer("Modified Successfully")
            val reverseResult = convertXmlToAbx(tempFile, xmlPath)
            if (reverseResult.isEmpty()) {
                observer("Converted XML to ABX")
                observer("Edited $name")
                observer("removing temp files ${removeFile(tempFile)}")
                return true
            } else {
                observer("failed to convert XML to ABX")
                removeFile(tempFile)
            }
        }
    }
    return false
}

fun restorePermissions(xmlPath: String): String {
    return fastCmd("system:system $xmlPath") +
            fastCmd("chmod 640 $xmlPath") +
            if (commandExists("restorecon") != "command not found") {
                fastCmd("restorecon -v $xmlPath")
            } else ""
}

fun editPackagesABXML(
    observer: (String) -> Unit,
    exit: () -> Unit
) {
    try {
        observer("started working on packages.xml")
        val packagesXmlPath = "/data/system/packages.xml"
        val warningsXmlPath = "/data/system/packages-warnings.xml"
        val packagesXmlBackUp = "$packagesXmlPath.bak"
        val warningsXmlBackUp = "$warningsXmlPath.bak"
        if (commandExists("abx2xml") != "command not found" && commandExists("xml2abx") != "command not found") {
            observer("abx2xml and xml2abx found")
            observer("creating backups")
            if (File(packagesXmlPath).exists()) observer(
                copyFilesWithShell(
                    packagesXmlPath,
                    packagesXmlBackUp
                )
            )
            if (File(warningsXmlPath).exists()) observer(
                copyFilesWithShell(
                    warningsXmlPath,
                    warningsXmlBackUp
                )
            )
            observer("Modifying installer, installInitiator string values (except those containing xiaomi or samsung) and removing installOriginator attribute...")
            val editedPackagesXml = File(packagesXmlPath).exists() && processPackagesXml(
                packagesXmlPath,
                name = "packages.xml",
                observer = observer
            )
            val editedWarningsXml = File(warningsXmlPath).exists() && processPackagesXml(
                warningsXmlPath,
                name = "packages-warnings.xml",
                observer = observer
            )
            if (editedWarningsXml || editedPackagesXml) {
                observer("Restoring permissions and SELinux context")
                observer(restorePermissions(packagesXmlPath))
                observer(restorePermissions(warningsXmlPath))
                observer("original packages.xml backed up to $packagesXmlBackUp")
                observer("original packages-warnings.xml.xml backed up to $warningsXmlBackUp")
                observer("Please reboot and check your play integrity json Unknown installed should now be resolved")
            }
        } else {
            observer("abx2xml and xml2abx not found")
            observer("can't proceed any further")
            observer("exiting runner")
        }

    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        observer("\n\nSpecial thanks")
        observer("Tesla @T3SL4")
        observer("RiRi's RRR Chat @RiRiRRC")
        exit
    }

}

fun getPermissions(permission: Array<String>) {
    permission.forEach {
        val result = fastCmd("su pm grant ${BuildConfig.APPLICATION_ID} $it")
        if (result.isNotEmpty()) {
            Log.d(TAG, "failed to get $it, reason: $result")
        } else {
            Log.d(TAG, "got $it")
        }
    }
}

fun killApp(appInfo: AppInfo): String {
    return fastCmd(
        getRootShell(),
        "am force-stop ${appInfo.packageName}"
    )
}


fun killApps(vararg appInfos: AppInfo, observer: (String) -> Unit, exit: () -> Unit) {
    try {
        observer("Requesting War Machine to initialise Kill Apps")
        observer("War Machine identifies ${appInfos.size} targets")
        appInfos.forEach {
            observer(
                "Killing ${it.appName} ${
                    fastCmd(
                        getRootShell(),
                        "am force-stop ${it.packageName}"
                    )
                }"
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
        observer(e.message.toString())
    } finally {
        observer("\n\nPutting War Machine to rest")
        observer("Done")
        exit()
    }
}

fun openAppInfoScreen(context: Context, appInfo: AppInfo) {
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            data = "package:${appInfo.packageName}".toUri()
        })
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
    }
}

fun uninstallSystemApp(appInfo: AppInfo): String {
    val shell = getRootShell()
    val currentUser = fastCmd(shell, "am get-current-user").trim()
    return fastCmd(shell, "pm uninstall -k --user $currentUser ${appInfo.packageName}")
}

fun readTargets(context: Context): List<String> {
    if (rootAvailable()) {
        val filesDir = context.filesDir
        val trickyFolder = File("/data/adb/tricky_store")
        val trickyBackUp = File(filesDir, "tricky_store")
        fastCmd(getRootShell(), "cp -r ${trickyFolder.absolutePath} ${filesDir.absolutePath}")
        if ((trickyBackUp).exists()) {
            val trickyTarget = File(trickyBackUp, "target.txt")
            if (trickyTarget.exists()) {
                Log.d(TAG, "backup Created successfully")
                return trickyTarget.readLines()
            }
        } else {
            Log.d(TAG, "using original targets file without copying")
            return fastCmd(
                getRootShell(),
                "cat /data/adb/tricky_store/target.txt"
            ).split("\n")
        }
    }
    return emptyList()
}

var stopLogger: (() -> Unit)? = null

fun AppInfo.showLogs(observer: (String) -> Unit, exit: () -> Unit) {

    observer("Log Cat search $packageName")
    observer("try getting pId")
    val pId = fastCmd(
        getRootShell(),
        "logcat -c", "pidof $packageName"
    ).trim()
    val logCommand = if (pId.isNotEmpty()) {
        observer("pId found")
        observer("")
        observer("")
        "logcat | grep $pId"
    } else {
        observer("pId not found")
        observer("")
        observer("fallback to use packageName instead")
        "logcat | grep $packageName"
    }
    Runtime.getRuntime().exec("logcat -c")/// clear logcat
    Runtime.getRuntime().exec(logCommand).let { process ->
        stopLogger = {
            if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    process.isAlive
                } else {
                    true
                }
            ) {
                observer("stopping logcat")
                exit()
                process.destroy()
            }
        }
        process.inputStream.use {
            try {
                it.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        observer(line)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

fun getRootStatusText(isRoot: Boolean, shizukuState: ShizukuState): String {
    return if (isRoot) "Root access granted" else when (shizukuState) {
        ShizukuState.NotInstalled -> "Shizuku is not installed"
        ShizukuState.NotRunning -> "Shizuku is not running"
        ShizukuState.PermissionNeeded -> "Shizuku permission is needed"
        ShizukuState.Ready -> "Shizuku is running"
    }
}



