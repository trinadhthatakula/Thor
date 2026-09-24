// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.valhalla.thor.data.appops.AppOpsCatalog
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.gateway.ShizukuSystemGateway
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.model.AppOpMode
import com.valhalla.thor.domain.model.AppOpScope
import com.valhalla.thor.domain.model.AppOpsSnapshot
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.repository.AppOpsRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single(binds = [AppOpsRepository::class])
class AppOpsRepositoryImpl(
    context: Context,
    private val rootGateway: RootSystemGateway,
    private val shizukuGateway: ShizukuSystemGateway,
    preferenceRepository: PreferenceRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : AppOpsRepository {
    private val pm = context.packageManager
    // Device-owner status does not grant arbitrary cross-app App Ops access. Resolve only the
    // transports that carry root/shell identity, while respecting their saved preference.
    private val gatewayResolver = ActiveGatewayResolver(
        preferredMode = { preferenceRepository.userPreferences.first().preferredPrivilegeMode },
        rootAvailable = { rootGateway.isRootAvailable(it) },
        shizukuAvailable = shizukuGateway::isShizukuAvailable,
        dhizukuAvailable = { false },
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
    )
    private val catalog by lazy(AppOpsCatalog::load)
    private val controller = AppOpsController(
        currentUserId = { thorUserId },
        loadTarget = ::loadTarget,
        loadCatalog = { catalog },
        openSession = { packageName ->
            val execution = PrivilegeExecutionContext(
                commandClass = PrivilegeCommandClass("app_ops.manage"),
                packageName = packageName,
            )
            val gateway: SystemGateway = when (gatewayResolver.resolve(execution).getOrThrow()) {
                PrivilegeMode.ROOT -> rootGateway
                PrivilegeMode.SHIZUKU -> shizukuGateway
                else -> error("App Ops requires Root or Shizuku. Dhizuku does not provide cross-app App Ops access.")
            }
            AppOpsCommandSession { command -> gateway.executeShellCommand(command, execution).getOrThrow() }
        },
    )

    override suspend fun getAppOps(packageName: String): Result<AppOpsSnapshot> = withContext(ioDispatcher) {
        controller.getAppOps(packageName).onFailure {
            Logger.e("AppOpsRepository", "Could not read App Ops", it)
        }
    }

    override suspend fun setAppOpMode(
        packageName: String,
        code: Int,
        scope: AppOpScope,
        mode: AppOpMode,
    ): Result<Unit> = withContext(ioDispatcher) {
        controller.setMode(packageName, code, scope, mode)
    }

    override suspend fun resetAppOpMode(
        packageName: String,
        code: Int,
        scope: AppOpScope,
    ): Result<Unit> = withContext(ioDispatcher) {
        controller.setMode(packageName, code, scope, null)
    }

    @Suppress("DEPRECATION")
    private fun loadTarget(packageName: String): AppOpsTarget {
        // AppOpsService uses MATCH_UNINSTALLED_PACKAGES too: system apps frozen by per-user
        // removal still have App Ops records and must remain inspectable from this screen.
        val flags = PERMISSION_QUERY_FLAGS
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(packageName, flags)
        }
        val uid = requireNotNull(info.applicationInfo).uid
        return AppOpsTarget(
            uid = uid,
            requestedPermissions = info.requestedPermissions.orEmpty().toSet(),
            sharedUidPackages = pm.getPackagesForUid(uid).orEmpty().toList().sorted(),
        )
    }
}
