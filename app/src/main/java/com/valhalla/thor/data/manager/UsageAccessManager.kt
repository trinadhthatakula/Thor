// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.manager

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.data.source.local.usageStatsGrantCommand
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.repository.UsageAccessGate
import org.koin.core.annotation.Single

/**
 * Manages the GET_USAGE_STATS (Usage Access) app-op needed by
 * StorageStatsManager. Tries a silent grant through the active privilege
 * gateway; always re-verifies; exposes the Settings deep-link for the fallback.
 */
@Single(binds = [UsageAccessGate::class])
class UsageAccessManager(
    private val context: Context,
    private val systemRepository: SystemRepository
) : UsageAccessGate {
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val pkg = context.packageName

    @Volatile
    private var autoGrantAttempted = false

    @Suppress("DEPRECATION")
    override fun isGranted(): Boolean {
        val ops = appOps ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), pkg)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), pkg)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Best-effort silent grant via a privileged gateway; returns the verified result. */
    override suspend fun tryGrantViaPrivilege(): Boolean {
        if (isGranted()) return true
        // Harmless if no privilege is active (command just fails); may also be
        // blocked on newer Android — hence we re-verify rather than assume success.
        //
        // The user id is not decoration. `isGranted()` above reads the op through
        // AppOpsManager for `Process.myUid()`, which answers for Thor's own user; the bare
        // `appops set` this replaced was resolved by `AppOpsService.Shell.parseUserPackageOp`
        // against `USER_CURRENT`, i.e. the foreground user. On a managed profile those are two
        // different users, so the write and the confirming read never met.
        systemRepository.executeShellCommand(
            usageStatsGrantCommand(pkg.escapeForShell(), thorUserId)
        )
        return isGranted()
    }

    /**
     * Best-effort per-process auto-grant. Latches only after a *successful* grant, so a
     * transient failure (e.g. the privilege gateway not fully ready yet) can still be
     * retried the next time this runs rather than being disabled for the whole process.
     */
    override suspend fun maybeAutoGrant() {
        if (autoGrantAttempted || isGranted()) return
        if (tryGrantViaPrivilege()) autoGrantAttempted = true
    }

    /** Settings deep-link (best-effort per-app; OEMs may land on the list). */
    fun usageAccessIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.fromParts("package", pkg, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
