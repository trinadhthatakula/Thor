package com.valhalla.thor.data.repository

import com.valhalla.thor.data.gateway.DhizukuSystemGateway
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.gateway.ShizukuSystemGateway
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Single

@Single(binds = [SystemRepository::class])
class SystemRepositoryImpl(
    private val rootGateway: RootSystemGateway,
    private val shizukuGateway: ShizukuSystemGateway,
    private val dhizukuGateway: DhizukuSystemGateway,
    private val preferenceRepository: PreferenceRepository
) : SystemRepository {

    override suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        rootGateway.isRootAvailable()
    }

    override fun isShizukuAvailable(): Boolean = shizukuGateway.isShizukuAvailable()

    override fun isDhizukuAvailable(): Boolean = dhizukuGateway.isDhizukuAvailable()

    // Dynamic Resolution Strategy: Respect user preference if available, else auto-detect.
    // Must be suspend because checking root and reading preferences are suspend operations.
    private suspend fun getActiveGateway(): Result<SystemGateway> {
        val prefs = preferenceRepository.userPreferences.first()

        // 1. Try User Preference
        prefs.preferredPrivilegeMode?.let { mode ->
            when (mode) {
                PrivilegeMode.ROOT -> if (rootGateway.isRootAvailable()) return Result.success(rootGateway)
                PrivilegeMode.SHIZUKU -> if (shizukuGateway.isShizukuAvailable()) return Result.success(shizukuGateway)
                PrivilegeMode.DHIZUKU -> if (dhizukuGateway.isDhizukuAvailable()) return Result.success(dhizukuGateway)
            }
        }

        // 2. Fallback to Auto-Detection
        return when {
            rootGateway.isRootAvailable() -> Result.success(rootGateway)
            shizukuGateway.isShizukuAvailable() -> Result.success(shizukuGateway)
            dhizukuGateway.isDhizukuAvailable() -> Result.success(dhizukuGateway)
            else -> Result.failure(IllegalStateException("No privileged gateway available (Root, Shizuku or Dhizuku required)"))
        }
    }

    override suspend fun forceStopApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        getActiveGateway().fold(
            onSuccess = { gateway ->
                try {
                    gateway.forceStopApp(packageName)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun clearCache(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        getActiveGateway().fold(
            onSuccess = { gateway ->
                try {
                    gateway.clearCache(packageName)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun clearAppData(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        getActiveGateway().fold(
            onSuccess = { gateway ->
                try {
                    gateway.clearAppData(packageName)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            getActiveGateway().fold(
                onSuccess = { gateway ->
                    try {
                        gateway.setAppDisabled(packageName, isDisabled)
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                },
                onFailure = { Result.failure(it) }
            )
        }

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            getActiveGateway().fold(
                onSuccess = { gateway ->
                    try {
                        gateway.setAppSuspended(packageName, isSuspended)
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                },
                onFailure = { Result.failure(it) }
            )
        }

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        getActiveGateway().fold(
            onSuccess = { gateway ->
                try {
                    gateway.setAppRestricted(packageName, isRestricted)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun uninstallApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        getActiveGateway().fold(
            onSuccess = { gateway ->
                try {
                    gateway.uninstallApp(packageName)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun rebootDevice(reason: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (rootGateway.isRootAvailable()) {
            rootGateway.rebootDevice(reason)
        } else {
            Result.failure(Exception("Reboot requires Root access"))
        }
    }

    override suspend fun aggressiveCleanup(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        getActiveGateway().fold(
            onSuccess = { gateway ->
                try {
                    gateway.forceStopApp(packageName)
                    gateway.clearCache(packageName)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        getActiveGateway().fold(
            onSuccess = { gateway ->
                try {
                    gateway.reinstallAppWithGoogle(packageName)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun copyFileWithRoot(
        sourcePath: String,
        destinationPath: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (rootGateway.isRootAvailable()) {
            try {
                rootGateway.copyFile(sourcePath, destinationPath)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.failure(Exception("Root required for privileged copy"))
        }
    }

    override suspend fun getAppPaths(packageName: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            if (rootGateway.isRootAvailable()) {
                val paths = rootGateway.getAppPaths(packageName)
                if (paths.isNotEmpty()) Result.success(paths)
                else Result.failure(Exception("No paths found"))
            } else {
                Result.failure(Exception("Root required to fetch split paths reliably"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        getActiveGateway().fold(
            onSuccess = { gateway ->
                try {
                    gateway.grantPermission(packageName, permissionName)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        getActiveGateway().fold(
            onSuccess = { gateway ->
                try {
                    gateway.revokePermission(packageName, permissionName)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }
}