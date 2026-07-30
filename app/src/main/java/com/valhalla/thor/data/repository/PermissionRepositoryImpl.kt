// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.valhalla.thor.domain.model.AppPermission
import com.valhalla.thor.domain.model.PermissionIndex
import com.valhalla.thor.domain.model.PlatformPermissionGroups
import com.valhalla.thor.domain.repository.PermissionRepository
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

@Single(binds = [PermissionRepository::class])
class PermissionRepositoryImpl(
    context: Context,
    private val systemRepository: SystemRepository
) : PermissionRepository {

    private val pm = context.packageManager

    @Suppress("DEPRECATION")
    override suspend fun getAppPermissions(packageName: String): Result<List<AppPermission>> =
        withContext(Dispatchers.IO) {
            try {
                val flags = PackageManager.GET_PERMISSIONS
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(flags.toLong())
                    )
                } else {
                    pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                }

                val requestedPermissions = packageInfo.requestedPermissions ?: emptyArray()
                val requestedFlags = packageInfo.requestedPermissionsFlags ?: intArrayOf()

                val permissions = requestedPermissions.mapIndexed { index, permName ->
                    val isGranted = if (index < requestedFlags.size) {
                        (requestedFlags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    } else {
                        false
                    }
                    val permInfo = try {
                        pm.getPermissionInfo(permName, 0)
                    } catch (_: Exception) {
                        null
                    }

                    val label =
                        permInfo?.loadLabel(pm)?.toString() ?: permName.substringAfterLast('.')
                    val description = permInfo?.loadDescription(pm)?.toString() ?: ""
                    val protectionLevel = permInfo?.protectionLevel ?: 0
                    val isRuntime =
                        (protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS

                    AppPermission(
                        name = permName,
                        label = label,
                        description = description,
                        group = permInfo?.group,
                        isGranted = isGranted,
                        isRuntime = isRuntime,
                        protectionLevel = protectionLevel
                    )
                }
                Result.success(permissions)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * One pass over every installed package, bucketing them by runtime-permission group.
     *
     * The match flags mirror `AppRepositoryImpl`'s sweep, and that is not optional. Thor freezes
     * *system* apps with `pm uninstall --user N`, so a frozen system app is not installed for this
     * user and a default `getInstalledPackages` drops it. The app list keeps those rows — showing
     * them is a headline Thor capability — and `filterApps` intersects the list with this index, so
     * two different package universes would make every frozen app silently fall out of every chip.
     *
     * Two caches make this affordable. `groupOf` memoises the group per *permission name* — a device
     * with 400 apps declares maybe 200 distinct permissions, and without it the same
     * `android.permission.CAMERA` lookup runs once per declaring app. The group labels are only
     * resolved for groups that actually matched something.
     *
     * Failures are swallowed per permission, not per sweep: one uninstalled-mid-scan package or one
     * permission belonging to an app that vanished should cost that entry, not the whole filter.
     */
    @Suppress("DEPRECATION")
    override suspend fun buildPermissionIndex(): Result<PermissionIndex> =
        withContext(Dispatchers.IO) {
            try {
                val flags = PackageManager.GET_PERMISSIONS or
                        PackageManager.MATCH_UNINSTALLED_PACKAGES or
                        PackageManager.MATCH_DISABLED_COMPONENTS
                val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    pm.getInstalledPackages(flags)
                }

                // permission name -> its group, or null for "not dangerous / no usable group".
                // Null is a real cached answer here, so getOrPut would re-resolve it every time.
                val groupOf = HashMap<String, String?>()
                val byGroup = HashMap<String, MutableSet<String>>()

                for (packageInfo in packages) {
                    // The loop is plain blocking work with no suspension point of its own, so
                    // cancelling the collector cannot interrupt it — the same hazard, and the same
                    // answer, as AppRepositoryImpl's scan. Without this a torn-down sweep runs to
                    // completion on its IO thread and overlaps the one that replaced it, and each
                    // overlap pays its own full set of binder calls because `groupOf` is per-sweep.
                    currentCoroutineContext().ensureActive()
                    val requested = packageInfo.requestedPermissions ?: continue
                    for (permName in requested) {
                        val group = if (groupOf.containsKey(permName)) {
                            groupOf[permName]
                        } else {
                            resolveRuntimeGroup(permName).also { groupOf[permName] = it }
                        } ?: continue
                        byGroup.getOrPut(group) { HashSet() }.add(packageInfo.packageName)
                    }
                }

                val labels = byGroup.keys.associateWith { group ->
                    try {
                        pm.getPermissionGroupInfo(group, 0).loadLabel(pm).toString()
                    } catch (_: Exception) {
                        // A group the platform will not describe still filters correctly; only its
                        // chip loses the nice name. Better a raw-ish label than a dropped group.
                        group.substringAfterLast('.').replace('_', ' ').lowercase()
                            .replaceFirstChar { it.uppercase() }
                    }
                }

                Result.success(PermissionIndex(packagesByGroup = byGroup, groupLabels = labels))
            } catch (e: CancellationException) {
                // Ahead of the broad catch, or the ensureActive() above is defeated: a cancelled
                // sweep would come back as Result.failure and put "Couldn't read permissions" in
                // front of a user who simply switched filters.
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * The permission's group if — and only if — it is a runtime permission the user can be asked
     * about. Everything else (normal, signature, and dangerous permissions nothing will group)
     * returns null and is left out of the index.
     *
     * **The platform table is asked first, and it is not an optimisation.** Since API 29 the
     * framework declares every dangerous platform permission with
     * `android:permissionGroup="android.permission-group.UNDEFINED"` — grouping moved into
     * PermissionController — so reading `PermissionInfo.group` for `android.permission.CAMERA`
     * returns UNDEFINED on every modern device. A filter built on that field alone offers no Camera
     * chip, no Microphone chip and no Location chip; it offers whatever custom groups third-party
     * apps still declare. See [PlatformPermissionGroups].
     *
     * `PermissionInfo` is still the right answer for *custom* permissions, where `group` is honest
     * and the protection level is the only way to know the permission is dangerous at all. An
     * unmapped permission is dropped rather than guessed.
     */
    @Suppress("DEPRECATION")
    private fun resolveRuntimeGroup(permName: String): String? {
        PlatformPermissionGroups.groupOf(permName)?.let { return it }

        val info = try {
            pm.getPermissionInfo(permName, 0)
        } catch (_: Exception) {
            return null
        }
        val isRuntime =
            (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
        if (!isRuntime) return null
        val group = info.group
        // UNDEFINED is the platform's own "no group", handed back as a real string.
        return group?.takeUnless { it.isBlank() || it == PlatformPermissionGroups.UNDEFINED }
    }

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> {
        return systemRepository.grantPermission(packageName, permissionName)
    }

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> {
        return systemRepository.revokePermission(packageName, permissionName)
    }

    override suspend fun isPrivilegeActive(): Boolean {
        return try {
            systemRepository.isRootAvailable() ||
                    systemRepository.isShizukuAvailable() ||
                    systemRepository.isDhizukuAvailable()
        } catch (_: Exception) {
            false
        }
    }
}
