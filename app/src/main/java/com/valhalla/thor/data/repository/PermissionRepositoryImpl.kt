// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.valhalla.thor.domain.model.AppPermission
import com.valhalla.thor.domain.model.DeclaredPermission
import com.valhalla.thor.domain.model.PermissionIndex
import com.valhalla.thor.domain.model.runtimeGroupFor
import com.valhalla.thor.domain.repository.PermissionRepository
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * The flags every permission read in this file uses, single-package and whole-device alike.
 *
 * The match half is not optional, and it is not optional *per call site* either — which is how the
 * two here came apart. A **system** app frozen by removal for the current user (what
 * `FreezePolicy.uninstallFreezeFallbackAllowed` still permits, and the state every system app frozen
 * before Thor preferred disabling is already in) is not installed for this user, so a
 * `getPackageInfo` without MATCH_UNINSTALLED_PACKAGES **throws** for it. The index kept the flag and
 * the per-app read did not, so Thor listed the app, offered its permission sheet, and then failed to
 * open it — for exactly the apps Thor itself had frozen.
 *
 * A top-level constant rather than two literals: the sweep and the single read have to describe the
 * same universe of packages, and the only way to keep them from drifting again is to give them one
 * name to share.
 */
internal const val PERMISSION_QUERY_FLAGS =
    PackageManager.GET_PERMISSIONS or
            PackageManager.MATCH_UNINSTALLED_PACKAGES or
            PackageManager.MATCH_DISABLED_COMPONENTS

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
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PERMISSION_QUERY_FLAGS.toLong())
                    )
                } else {
                    pm.getPackageInfo(packageName, PERMISSION_QUERY_FLAGS)
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
     * [PERMISSION_QUERY_FLAGS] mirrors `AppRepositoryImpl`'s sweep, and that is not optional. A
     * *system* app frozen by removal for the current user — what
     * `FreezePolicy.uninstallFreezeFallbackAllowed` still permits, and what every system app frozen
     * before Thor preferred disabling is already in — is not installed for this user, and a default
     * `getInstalledPackages` drops it. The disabled mechanic needs no flag of its own here, which is
     * exactly why the pair must stay whole: the half that is doing the work is the invisible one.
     * The app list keeps those rows — showing them is a headline Thor capability — and `filterApps`
     * intersects the list with this index, so two different package universes would make every
     * frozen app silently fall out of every chip.
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
                val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(
                        PackageManager.PackageInfoFlags.of(PERMISSION_QUERY_FLAGS.toLong())
                    )
                } else {
                    pm.getInstalledPackages(PERMISSION_QUERY_FLAGS)
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
     * The permission's group if — and only if — this device defines it *and* it is a runtime
     * permission the user can be asked about. Everything else returns null and is left out of the
     * index.
     *
     * The binder call is the whole of this method's job; the decision it feeds lives in
     * [runtimeGroupFor], which is where the ordering rule (device first, table only for the group)
     * is stated and where it is unit-tested. Splitting them is what makes "an APK declaring
     * POST_NOTIFICATIONS on API 28 must not produce a Notifications chip" assertable at all —
     * `PackageManager` is abstract and `:app` has no mocking library.
     */
    private fun resolveRuntimeGroup(permName: String): String? =
        runtimeGroupFor(permName, declaredPermission(permName), Build.VERSION.SDK_INT)

    /** What this device says about [permName], or null if it does not define it. */
    @Suppress("DEPRECATION")
    private fun declaredPermission(permName: String): DeclaredPermission? {
        val info = try {
            // Throws NameNotFoundException for a permission this Android version has never heard
            // of, which is the authoritative answer and not an error worth logging: manifests
            // routinely declare permissions for OS versions newer than the one running them.
            pm.getPermissionInfo(permName, 0)
        } catch (_: Exception) {
            return null
        }
        return DeclaredPermission(
            isDangerous = (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
                    PermissionInfo.PROTECTION_DANGEROUS,
            group = info.group
        )
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
