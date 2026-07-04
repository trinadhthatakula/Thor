package com.valhalla.thor.presentation.launcher

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
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
class FreezerLaunchActivity : Activity() {

    private val systemRepository: SystemRepository by inject()
    private val manageAppUseCase: ManageAppUseCase by inject()
    private val freezerShortcutManager: FreezerShortcutManager by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (FreezerShortcutContract.parseAction(intent?.getStringExtra(FreezerShortcutContract.EXTRA_ACTION))) {
            FreezerShortcutContract.ACTION_FREEZE_ALL -> guardThenBulk(disable = true)
            FreezerShortcutContract.ACTION_UNFREEZE_ALL -> guardThenBulk(disable = false)
            FreezerShortcutContract.ACTION_LAUNCH -> {
                val pkg = intent?.getStringExtra(FreezerShortcutContract.EXTRA_PACKAGE)
                if (pkg.isNullOrEmpty()) finish() else launchApp(pkg)
            }
            else -> finish()
        }
    }

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
            if (launchIntent == null) {
                if (!hasPrivilege()) {
                    toast(getString(R.string.tile_grant_privilege_toast))
                    finish(); return@launch
                }
                withContext(Dispatchers.IO) { manageAppUseCase.setAppDisabled(pkg, false) }
                // Enabled state / launcher intent may not be visible instantly — retry briefly
                // (~10×150ms budget), stopping as soon as the intent resolves.
                for (attempt in 0 until 10) {
                    launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) break
                    delay(150)
                }
            }
            val toStart = launchIntent
            if (toStart != null) startActivity(toStart)
            else toast(getString(R.string.freezer_launch_failed))
            finish()
        }
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
