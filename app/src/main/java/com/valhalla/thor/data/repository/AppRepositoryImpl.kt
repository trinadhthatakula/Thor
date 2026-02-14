package com.valhalla.thor.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppRepositoryImpl(
    private val context: Context
) : AppRepository {

    private val pm = context.packageManager

    /**
     * RUTHLESS OPTIMIZATION V2:
     * We debounce the TRIGGER to prevent heavy package scanning during batch operations.
     */
    override fun getAllApps(): Flow<List<AppInfo>> = callbackFlow {
        val producer = this

        // A conflated channel acts as a signal buffer.
        // If 50 broadcasts come in, we only keep the latest "refresh needed" flag.
        val triggerChannel = Channel<Unit>(Channel.CONFLATED)

        // The Worker: Consumes triggers, waits for quiet, then fetches ONCE.
        val worker = launch(Dispatchers.IO) {
            // Initial load
            triggerChannel.send(Unit)

            for (signal in triggerChannel) {
                // Wait for the broadcast storm to settle (e.g. 500ms after last signal)
                //delay(500L)

                // Drain any extra signals that arrived while we were waiting
                while (triggerChannel.tryReceive().isSuccess) {
                    // Do nothing, just consume them so we don't loop immediately again
                }

                // Now Perform the Heavy Fetch ONE time
                try {
                    val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
                    val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
                    } else {
                        pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    }

                    val currentList = ArrayList<AppInfo>(installedPackages.size)

                    for (packInfo in installedPackages) {
                        val appInfo = packInfo.applicationInfo ?: continue
                        val mapped = mapToAppInfo(packInfo, appInfo, isLightweight = true)
                        currentList.add(mapped)
                    }

                    // Emit a single complete snapshot of all installed apps
                    producer.send(currentList.toList())

                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) e.printStackTrace()
                }
            }
        }

        // Register Receiver
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Signal the worker to refresh. Non-blocking.
                triggerChannel.trySend(Unit)
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }

        context.registerReceiver(receiver, filter)

        awaitClose {
            context.unregisterReceiver(receiver)
            worker.cancel()
        }
    }.flowOn(Dispatchers.IO)

    // ... (rest of the file remains unchanged) ...
    override suspend fun getAppDetails(packageName: String): AppInfo? =
        withContext(Dispatchers.IO) {
            try {
                val flags = (PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong()
                val packInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
                } else {
                    pm.getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                }
                val appInfo = packInfo.applicationInfo ?: return@withContext null

                mapToAppInfo(packInfo, appInfo, isLightweight = false)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG)
                    e.printStackTrace()
                null
            }
        }

    override suspend fun getApkDetails(apkPath: String): AppInfo? = withContext(Dispatchers.IO) {
        val flags = PackageManager.GET_PERMISSIONS
        val packInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkPath, flags)
        } ?: return@withContext null

        val appInfo = packInfo.applicationInfo?.apply {
            sourceDir = apkPath
            publicSourceDir = apkPath
        } ?: return@withContext null

        mapToAppInfo(packInfo, appInfo, isLightweight = false).apply {
            this.appName = pm.getApplicationLabel(appInfo).toString()
        }
    }

    private fun mapToAppInfo(
        packInfo: android.content.pm.PackageInfo,
        appInfo: ApplicationInfo,
        isLightweight: Boolean
    ): AppInfo {
        val isDebuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        @Suppress("DEPRECATION") val mapped = AppInfo(
            appName = appInfo.loadLabel(pm).toString(),
            packageName = packInfo.packageName,
            versionName = packInfo.versionName,
            versionCode = packInfo.longVersionCode.toInt(),
            minSdk = appInfo.minSdkVersion,
            targetSdk = appInfo.targetSdkVersion,
            isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            installerPackageName = getInstallerPackageName(packInfo.packageName),
            publicSourceDir = appInfo.publicSourceDir,
            splitPublicSourceDirs = appInfo.splitPublicSourceDirs?.toList() ?: emptyList(),
            enabled = appInfo.enabled,
            dataDir = appInfo.dataDir,
            nativeLibraryDir = appInfo.nativeLibraryDir,
            deviceProtectedDataDir = appInfo.deviceProtectedDataDir,
            sourceDir = appInfo.sourceDir,
            lastUpdateTime = packInfo.lastUpdateTime,
            firstInstallTime = packInfo.firstInstallTime,
            isDebuggable = isDebuggable
        )

        if (!isLightweight) {
            mapped.sharedLibraryFiles = appInfo.sharedLibraryFiles?.toList() ?: emptyList()
            val obbFile = File(
                Environment.getExternalStorageDirectory(),
                "Android/obb/${appInfo.packageName}"
            )
            if (obbFile.exists()) {
                mapped.obbFilePath = obbFile.absolutePath
            }
            val dataFile = File(
                Environment.getExternalStorageDirectory(),
                "Android/data/${appInfo.packageName}"
            )
            mapped.sharedDataDir = dataFile.absolutePath
        }

        return mapped
    }

    private fun getInstallerPackageName(packageName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(packageName)
            }
        } catch (_: Exception) {
            null
        }
    }
}