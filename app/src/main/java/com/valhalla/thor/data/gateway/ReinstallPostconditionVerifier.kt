// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.valhalla.thor.data.repository.installerPackageNameOf
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.ReinstallPostconditionFailed
import java.util.concurrent.CancellationException
import org.koin.core.annotation.Single

internal data class ReinstallFinalState(
    val installedForThorUser: Boolean,
    val installerPackageName: String?,
)

internal fun interface ReinstallStateReader {
    suspend fun read(packageName: String, userId: Int): ReinstallFinalState
}

@Single(binds = [ReinstallStateReader::class])
internal class AndroidReinstallStateReader(
    private val context: Context,
) : ReinstallStateReader {
    override suspend fun read(packageName: String, userId: Int): ReinstallFinalState {
        if (userId != thorUserId) {
            throw ReinstallPostconditionFailed(packageName)
        }

        val packageManager = context.packageManager
        val applicationInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(
                        PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES,
                )
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        val installed = applicationInfo != null &&
            applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED != 0
        return ReinstallFinalState(
            installedForThorUser = installed,
            installerPackageName = if (installed) {
                packageManager.installerPackageNameOf(packageName)
            } else {
                null
            },
        )
    }
}

@Single
internal class ReinstallPostconditionVerifier(
    private val stateReader: ReinstallStateReader,
) {
    suspend fun verify(packageName: String, userId: Int): Result<Unit> {
        val state = try {
            stateReader.read(packageName, userId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.failure(ReinstallPostconditionFailed(packageName))
        }

        return if (
            state.installedForThorUser &&
            state.installerPackageName == GOOGLE_PLAY_PACKAGE
        ) {
            Result.success(Unit)
        } else {
            Result.failure(ReinstallPostconditionFailed(packageName))
        }
    }

    private companion object {
        const val GOOGLE_PLAY_PACKAGE = "com.android.vending"
    }
}
