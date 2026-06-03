package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.valhalla.thor.domain.model.AppPermission
import org.koin.core.annotation.Single
import com.valhalla.thor.domain.repository.PermissionRepository
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Single(binds = [PermissionRepository::class])
class PermissionRepositoryImpl(
    context: Context,
    private val systemRepository: SystemRepository
) : PermissionRepository {

    private val pm = context.packageManager

    override suspend fun getAppPermissions(packageName: String): Result<List<AppPermission>> =
        withContext(Dispatchers.IO) {
            try {
                val flags = PackageManager.GET_PERMISSIONS
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
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

                    val label = permInfo?.loadLabel(pm)?.toString() ?: permName.substringAfterLast('.')
                    val description = permInfo?.loadDescription(pm)?.toString() ?: ""
                    val protectionLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        permInfo?.protectionLevel ?: 0
                    } else {
                        @Suppress("DEPRECATION")
                        permInfo?.protection ?: 0
                    }
                    @Suppress("DEPRECATION")
                    val isRuntime = (protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS

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

    override suspend fun grantPermission(packageName: String, permissionName: String): Result<Unit> {
        return systemRepository.grantPermission(packageName, permissionName)
    }

    override suspend fun revokePermission(packageName: String, permissionName: String): Result<Unit> {
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
