// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.pm.ShortcutManagerCompat
import com.valhalla.thor.R
import com.valhalla.thor.data.launcher.FreezerShortcutContract
import com.valhalla.thor.data.launcher.FreezerShortcutManager
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Invisible (translucent) trampoline for Freezer launcher shortcuts. Translucent — not
 * Theme.NoDisplay — because it does async work (enable-then-launch) and NoDisplay requires
 * finish() before onResume completes.
 */
// Not a splash screen: this is a translucent trampoline activity that does async
// enable-then-launch work; it shows no UI and has no branded splash.
@SuppressLint("CustomSplashScreen")
class FreezerLaunchActivity : Activity() {

    private val systemRepository: SystemRepository by inject()
    private val manageAppUseCase: ManageAppUseCase by inject()
    private val freezerShortcutManager: FreezerShortcutManager by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    // Bulk: privilege-guard, hand off to the app-scoped manager, finish immediately.
    private fun guardThenBulk(disable: Boolean) {
        scope.launch {
            if (!hasPrivilege()) {
                toast(getString(R.string.tile_grant_privilege_toast))
            } else {
                freezerShortcutManager.runBulk(disable)
            }
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
    } catch (e: Exception) {
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
}
