// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.installer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.valhalla.thor.R
import com.valhalla.thor.domain.repository.LegacyFixStoreCancelled
import com.valhalla.thor.domain.repository.LegacyFixStoreInstaller
import com.valhalla.thor.util.AppScanRevision
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.UiTextException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

internal data class LegacyInstallIdentity(
    val versionCode: Long,
    val signers: Set<String>,
    val installed: Boolean,
    val hasSplits: Boolean,
    val installer: String?,
) {
    fun verifies(previous: LegacyInstallIdentity): Boolean = installed && !hasSplits &&
        installer == "com.android.vending" && versionCode == previous.versionCode &&
        signers.isNotEmpty() && signers == previous.signers
}

@Single(binds = [LegacyFixStoreInstaller::class])
class LegacyFixStoreInstallerImpl(
    private val context: Context,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : LegacyFixStoreInstaller {
    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    override suspend fun reinstall(
        packageName: String,
        requestInstall: suspend (String) -> Result<Boolean>,
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            if (sdkInt !in 28..32) fail(R.string.fix_store_unsupported_dhizuku)
            if (packageName == context.packageName) fail(R.string.error_self_skipped)
            val before = packageInfo(packageName)
            // The system's MODE_FULL_INSTALL drops optional splits while reporting success.
            // Check fresh metadata, never just the potentially stale app-list snapshot.
            if (!before.splitNames.isNullOrEmpty() || !before.applicationInfo?.splitSourceDirs.isNullOrEmpty()) {
                fail(R.string.legacy_fix_store_split_unsupported)
            }
            val expected = identity(before)
            val app = before.applicationInfo ?: fail(R.string.legacy_fix_store_app_unsupported)
            if (!expected.installed || expected.signers.isEmpty() ||
                app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_TEST_ONLY) != 0
            ) fail(R.string.legacy_fix_store_app_unsupported)
            if (expected.installer == "com.android.vending") return@withContext Result.success(Unit)

            val base = File(app.sourceDir)
            if (!base.isFile || !base.canRead()) fail(R.string.legacy_fix_store_app_unsupported)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", base)
            if (!requestInstall(uri.toString()).getOrThrow()) {
                return@withContext Result.failure(LegacyFixStoreCancelled())
            }
            AppScanRevision.bump()
            if (!identity(packageInfo(packageName)).verifies(expected)) {
                fail(R.string.legacy_fix_store_not_verified)
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String): PackageInfo =
        context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): LegacyInstallIdentity = LegacyInstallIdentity(
        versionCode = info.longVersionCode,
        signers = info.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet(),
        installed = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_INSTALLED != 0,
        hasSplits = !info.splitNames.isNullOrEmpty() || !info.applicationInfo?.splitSourceDirs.isNullOrEmpty(),
        installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(info.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(info.packageName)
        },
    )

    private fun fail(@StringRes resource: Int): Nothing = throw UiTextException(UiText.StringResource(resource))
}
