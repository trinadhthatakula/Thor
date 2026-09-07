// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.pm.ShortcutManagerCompat
import com.valhalla.thor.R
import com.valhalla.thor.data.freezer.PrivilegeSweepSurfaceLauncher
import com.valhalla.thor.data.launcher.FreezerShortcutContract
import com.valhalla.thor.data.launcher.FreezerShortcutManager
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchRejection
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject

/**
 * Invisible (translucent) trampoline for Freezer launcher shortcuts. Translucent — not
 * Theme.NoDisplay — because it does async work (enable-then-launch) and NoDisplay requires
 * finish() before onResume completes.
 */
// Not a splash screen: this is a translucent trampoline activity that does async
// enable-then-launch work; it shows no UI and has no branded splash.
internal fun shortcutBulkMessageRes(result: PrivilegeSweepLaunchResult): Int = when (result) {
    is PrivilegeSweepLaunchResult.Accepted -> R.string.sweep_queued
    is PrivilegeSweepLaunchResult.Rejected -> when (result.reason) {
        PrivilegeSweepLaunchRejection.NotificationsRequired ->
            R.string.notification_access_needed_subtitle
        PrivilegeSweepLaunchRejection.NoPrivilege -> R.string.tile_grant_privilege_toast
        PrivilegeSweepLaunchRejection.NoTargets -> R.string.tile_no_apps_toast
        is PrivilegeSweepLaunchRejection.EnqueueFailed -> R.string.bulk_run_failed
    }
}

@SuppressLint("CustomSplashScreen")
class FreezerLaunchActivity : Activity() {

    private val systemRepository: SystemRepository by inject()
    private val manageAppUseCase: ManageAppUseCase by inject()
    private val freezerShortcutManager: FreezerShortcutManager by inject()
    private val sweepLauncher: PrivilegeSweepSurfaceLauncher by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Applies the chosen locale on API 28–32, where nothing else will.
     *
     * A third entry point, reached from a launcher shortcut without passing through
     * [com.valhalla.thor.HomeActivity]. It draws no UI, but every outcome it reports is a
     * `getString` on **this** context — `tile_grant_privilege_toast`, `tile_no_apps_toast`,
     * `bulk_run_failed`, `freezer_launch_failed`, and the queued acknowledgement —
     * so without the wrap those toasts are the one part of Thor still speaking English.
     *
     * No `recreateOnChange` counterpart: this activity is `noHistory`, `excludeFromRecents` and
     * finishes itself within a few hundred milliseconds, so there is no instance alive long enough
     * for a language change to strand.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (FreezerShortcutContract.parseAction(intent?.getStringExtra(FreezerShortcutContract.EXTRA_ACTION))) {
            FreezerShortcutContract.ACTION_FREEZE_ALL -> {
                reportShortcutUsed(FreezerShortcutContract.SHORTCUT_FREEZE_ALL)
                guardThenBulk(disable = true)
            }
            FreezerShortcutContract.ACTION_UNFREEZE_ALL -> {
                reportShortcutUsed(FreezerShortcutContract.SHORTCUT_UNFREEZE_ALL)
                guardThenBulk(disable = false)
            }
            FreezerShortcutContract.ACTION_LAUNCH -> {
                val pkg = intent?.getStringExtra(FreezerShortcutContract.EXTRA_PACKAGE)
                if (pkg.isNullOrEmpty()) {
                    finish()
                } else {
                    reportShortcutUsed(FreezerShortcutContract.appShortcutId(pkg))
                    launchApp(pkg)
                }
            }
            else -> finish()
        }
    }

    // Tell the launcher a shortcut was activated so it can rank frequently-used shortcuts
    // (and, for pinned/dynamic shortcuts, keep usage history). Lightweight single binder call.
    private fun reportShortcutUsed(shortcutId: String) =
        ShortcutManagerCompat.reportShortcutUsed(this, shortcutId)

    // Bulk: persist and enqueue inside the short report window, then get out of the launcher's way.
    private fun guardThenBulk(disable: Boolean) {
        scope.launch {
            val result = withTimeoutOrNull(REPORT_WINDOW_MS) {
                sweepLauncher.launch(
                    request = BulkRequest(if (disable) BulkOp.FREEZE else BulkOp.UNFREEZE),
                    source = PrivilegeSweepSource.LAUNCHER,
                ).await()
            }
            toast(
                getString(
                    result?.let(::shortcutBulkMessageRes)
                        ?: if (disable) R.string.log_freezing_batch else R.string.log_unfreezing_batch
                )
            )
            finish()
        }
    }

    // Launch: stay foreground through startActivity (Android 10+ background-launch rule).
    private fun launchApp(pkg: String) {
        scope.launch {
            var launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            // A frozen app may be DISABLED (no launch intent) or — in Suspend mode — SUSPENDED but
            // still enabled (the intent resolves, yet launching it pops the system "app paused"
            // dialog). Handle both: forceUnfreeze unsuspends AND enables before we launch.
            if (launchIntent == null || isSuspended(pkg)) {
                if (!hasPrivilege()) {
                    toast(getString(R.string.tile_grant_privilege_toast))
                    finish(); return@launch
                }
                val restored = withContext(Dispatchers.IO) { manageAppUseCase.forceUnfreeze(pkg) }
                if (restored.isFailure) {
                    // Restore failed (privilege/shell error) — fail fast instead of waiting out the retry loop.
                    toast(getString(R.string.freezer_launch_failed))
                    finish(); return@launch
                }
                // Unsuspended/enabled state / launcher intent may not be visible instantly — retry
                // briefly (~10×150ms budget), stopping as soon as the intent resolves.
                for (attempt in 0 until 10) {
                    launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) break
                    delay(150)
                }
            }
            val toStart = launchIntent
            if (toStart != null) startActivity(toStart)
            else toast(getString(R.string.freezer_launch_failed))
            freezerShortcutManager.refreshAppShortcut(pkg) // now active → recolour the shortcut icon
            finish()
        }
    }

    // A suspended app stays "enabled" (Suspend mode), so getLaunchIntentForPackage still resolves —
    // detect suspension via the FLAG_SUSPENDED bit (API 24+, matches the rest of the app; the
    // isPackageSuspended(pkg) overload is only API 29+).
    private fun isSuspended(pkg: String): Boolean = try {
        val info = packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
        (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
    } catch (_: Exception) {
        // Unreadable package: treat as not-suspended and let the launch path fail visibly.
        false
    }

    private suspend fun hasPrivilege(): Boolean = withContext(Dispatchers.IO) {
        systemRepository.isRootAvailable() ||
                systemRepository.isShizukuAvailable() ||
                systemRepository.isDhizukuAvailable()
    }

    private fun toast(msg: String) =
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private companion object {
        /**
         * How long the trampoline stays alive waiting for a bulk run's outcome. Short because
         * the window is invisible yet touchable; long enough for the Room-to-WorkManager enqueue
         * handoff in ordinary conditions. The process-owned launcher continues that handoff if this
         * report window expires.
         */
        const val REPORT_WINDOW_MS = 2_000L
    }
}
