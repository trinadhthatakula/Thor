package com.valhalla.thor.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.room.AppDao
import com.valhalla.thor.data.source.local.room.AppEntity
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.PermissionDetail
import com.valhalla.thor.domain.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.io.File

@Single(binds = [AppRepository::class])
class AppRepositoryImpl(
    private val context: Context,
    private val appDao: AppDao
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
            // Initial load from cache and baseline for comparison
            val cachedMap = try {
                val entities = appDao.getAllApps()
                if (entities.isNotEmpty()) {
                    producer.send(entities.map { it.toDomain() })
                }
                entities.associateBy { it.packageName }.toMutableMap()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) e.printStackTrace()
                mutableMapOf<String, AppEntity>()
            }

            var lastLocale = context.resources.configuration.locales[0].toString()

            // Signal the worker to refresh
            triggerChannel.send(Unit)

            for (signal in triggerChannel) {
                // Drain any extra signals that arrived while we were waiting
                while (triggerChannel.tryReceive().isSuccess) {
                    // Do nothing, just consume them so we don't loop immediately again
                }

                // Now Perform the Heavy Fetch ONE time
                try {
                    val currentLocale = context.resources.configuration.locales[0].toString()
                    val forceRefresh = currentLocale != lastLocale
                    if (forceRefresh) {
                        lastLocale = currentLocale
                    }

                    val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
                    val installedPackages =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
                        } else {
                            pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                        }

                    val currentList = ArrayList<AppInfo>(installedPackages.size)
                    val toUpdate = mutableListOf<AppEntity>()

                    for (packInfo in installedPackages) {
                        val appInfo = packInfo.applicationInfo ?: continue
                        val packageName = packInfo.packageName

                        val cachedEntry = cachedMap[packageName]
                        val isSuspended =
                            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0

                        val isInstalled = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
                        val isEnabled = appInfo.enabled && isInstalled

                        if (!forceRefresh &&
                            cachedEntry != null &&
                            cachedEntry.lastUpdateTime == packInfo.lastUpdateTime &&
                            cachedEntry.enabled == isEnabled &&
                            cachedEntry.isSuspended == isSuspended
                        ) {
                            currentList.add(cachedEntry.toDomain())
                        } else {
                            val mapped =
                                AppInfo.mapToAppInfo(packInfo, appInfo, pm, isLightweight = true)
                            currentList.add(mapped)
                            val entity = AppEntity.fromDomain(mapped)
                            toUpdate.add(entity)
                            cachedMap[packageName] = entity
                        }
                    }

                    // Handle uninstalled apps: Cleanup cache
                    val currentPackageNames = installedPackages.map { it.packageName }.toSet()
                    val toDelete = cachedMap.keys.filter { it !in currentPackageNames }

                    if (toUpdate.isNotEmpty() || toDelete.isNotEmpty()) {
                        appDao.syncCache(toUpdate, toDelete)
                        toDelete.forEach { pkgName ->
                            cachedMap.remove(pkgName)
                            try {
                                File(context.filesDir, "app_icons/$pkgName.png").delete()
                            } catch (_: Exception) {}
                        }
                    }

                    // Emit a single complete snapshot of all installed apps
                    producer.send(currentList.toList())

                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) e.printStackTrace()
                }
            }
        }

        // Receiver for Package-specific changes (requires "package" data scheme)
        val packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                triggerChannel.trySend(Unit)
            }
        }

        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }

        // Receiver for General Package changes (No data scheme)
        val generalReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                triggerChannel.trySend(Unit)
            }
        }

        val generalFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGES_SUSPENDED)
            addAction(Intent.ACTION_PACKAGES_UNSUSPENDED)
        }

        context.registerReceiver(packageReceiver, packageFilter)
        context.registerReceiver(generalReceiver, generalFilter)

        awaitClose {
            context.unregisterReceiver(packageReceiver)
            context.unregisterReceiver(generalReceiver)
            worker.cancel()
        }
    }.flowOn(Dispatchers.IO)

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

                AppInfo.mapToAppInfo(packInfo, appInfo, pm, isLightweight = false)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG)
                    e.printStackTrace()
                null
            }
        }

    @Suppress("DEPRECATION")
    override suspend fun getDetailedAppInfo(packageName: String): DetailedAppInfo? =
        withContext(Dispatchers.IO) {
            try {
                val appInfo = getAppDetails(packageName) ?: return@withContext null

                val flags = (PackageManager.GET_ACTIVITIES or
                        PackageManager.GET_SERVICES or
                        PackageManager.GET_RECEIVERS or
                        PackageManager.GET_PROVIDERS or
                        PackageManager.GET_PERMISSIONS or
                        PackageManager.GET_CONFIGURATIONS or
                        PackageManager.MATCH_UNINSTALLED_PACKAGES or
                        PackageManager.MATCH_DISABLED_COMPONENTS or
                        PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or
                        PackageManager.GET_SIGNING_CERTIFICATES).toLong()

                val packInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
                } else {
                    pm.getPackageInfo(packageName, flags.toInt())
                }

                val activities = packInfo.activities?.map { it.name } ?: emptyList()
                val services = packInfo.services?.map { it.name } ?: emptyList()
                val receivers = packInfo.receivers?.map { it.name } ?: emptyList()
                val providers = packInfo.providers?.map { it.name } ?: emptyList()
                val reqFeatures = packInfo.reqFeatures?.map {
                    if (it.name != null) {
                        it.name
                    } else {
                        val glEs = it.glEsVersion
                        if (!glEs.isNullOrEmpty()) {
                            "GlEsVersion: $glEs"
                        } else {
                            val major = it.reqGlEsVersion shr 16
                            val minor = it.reqGlEsVersion and 0xFFFF
                            "GlEsVersion: $major.$minor"
                        }
                    }
                } ?: emptyList()

                val requestedPermissions = packInfo.requestedPermissions ?: emptyArray()
                val permissions = requestedPermissions.map { permName ->
                    val isGranted = pm.checkPermission(
                        permName,
                        packageName
                    ) == PackageManager.PERMISSION_GRANTED
                    var label: String? = null
                    var description: String? = null
                    val protection = try {
                        val permInfo = pm.getPermissionInfo(permName, 0)
                        label = permInfo.loadLabel(pm).toString()
                        description = permInfo.loadDescription(pm)?.toString()
                        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            permInfo.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE
                        } else {
                            permInfo.protection and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE
                        }
                        when (base) {
                            android.content.pm.PermissionInfo.PROTECTION_NORMAL -> "Normal"
                            android.content.pm.PermissionInfo.PROTECTION_DANGEROUS -> "Dangerous"
                            android.content.pm.PermissionInfo.PROTECTION_SIGNATURE -> "Signature"
                            android.content.pm.PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM -> "Signature/System"
                            else -> "Unknown ($base)"
                        }
                    } catch (_: Exception) {
                        "Unknown"
                    }
                    PermissionDetail(
                        name = permName,
                        isGranted = isGranted,
                        protectionLevel = protection,
                        label = label,
                        description = description
                    )
                }

                val hasWakelockPermission =
                    requestedPermissions.contains(android.Manifest.permission.WAKE_LOCK)

                val nativeLibDir = packInfo.applicationInfo?.nativeLibraryDir
                val nativeLibs = if (nativeLibDir != null) {
                    val dir = File(nativeLibDir)
                    if (dir.exists() && dir.isDirectory) {
                        dir.listFiles()?.map { it.name } ?: emptyList()
                    } else emptyList()
                } else emptyList()

                val signatureSha256 = try {
                    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packInfo.signingInfo?.signingCertificateHistory
                    } else {
                        @Suppress("DEPRECATION")
                        packInfo.signatures
                    }
                    if (signatures != null && signatures.isNotEmpty()) {
                        val cert = signatures[0].toByteArray()
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        val digest = md.digest(cert)
                        digest.joinToString(":") { "%02X".format(it) }
                    } else null
                } catch (_: Exception) {
                    null
                }

                DetailedAppInfo(
                    appInfo = appInfo,
                    activities = activities,
                    services = services,
                    receivers = receivers,
                    providers = providers,
                    permissions = permissions,
                    nativeLibs = nativeLibs,
                    reqFeatures = reqFeatures,
                    hasWakelockPermission = hasWakelockPermission,
                    signatureSha256 = signatureSha256
                )
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) e.printStackTrace()
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

        AppInfo.mapToAppInfo(packInfo, appInfo, pm, isLightweight = false).copy(
            appName = pm.getApplicationLabel(appInfo).toString()
        )
    }
}
