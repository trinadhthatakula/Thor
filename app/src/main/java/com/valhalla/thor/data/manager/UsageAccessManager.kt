package com.valhalla.thor.data.manager

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.valhalla.thor.domain.repository.SystemRepository
import org.koin.core.annotation.Single

/**
 * Manages the GET_USAGE_STATS (Usage Access) app-op needed by
 * StorageStatsManager. Tries a silent grant through the active privilege
 * gateway; always re-verifies; exposes the Settings deep-link for the fallback.
 */
@Single
class UsageAccessManager(
    private val context: Context,
    private val systemRepository: SystemRepository
) {
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val pkg = context.packageName

    @Volatile
    private var autoGrantAttempted = false

    fun isGranted(): Boolean {
        val ops = appOps ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), pkg)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), pkg)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Best-effort silent grant via a privileged gateway; returns the verified result. */
    suspend fun tryGrantViaPrivilege(): Boolean {
        if (isGranted()) return true
        // Harmless if no privilege is active (command just fails); may also be
        // blocked on newer Android — hence we re-verify rather than assume success.
        systemRepository.executeShellCommand("appops set $pkg GET_USAGE_STATS allow")
        return isGranted()
    }

    /** One-shot per-process auto-grant, meant to run once a privilege is available. */
    suspend fun maybeAutoGrant() {
        if (autoGrantAttempted || isGranted()) return
        autoGrantAttempted = true
        tryGrantViaPrivilege()
    }

    /** Settings deep-link (best-effort per-app; OEMs may land on the list). */
    fun usageAccessIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.fromParts("package", pkg, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
