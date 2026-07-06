package com.valhalla.thor.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.valhalla.thor.domain.model.mayRestore
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Lets the Strombringer launcher hook ask Thor (which holds root/Shizuku/Dhizuku privilege) to
 * unsuspend an app on launch.
 *
 * The hook runs INSIDE the launcher's process, so the caller is the launcher — it cannot be
 * signature-verified as our extension, and a `signature`-level permission can't span Thor's key and
 * the (dedicated) extension key. So we defend with two independent bounds, both applied before any
 * privileged work:
 *   1. the caller must be a HOME/launcher app (the only place the hook legitimately runs), and
 *   2. the target must already be in the user's Freezer (`mayRestore`).
 */
class FreezerBridgeProvider : ContentProvider(), KoinComponent {
    private val freezerRepository: FreezerRepository by inject()
    private val manageAppUseCase: ManageAppUseCase by inject()

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle().apply { putBoolean("ok", false) }
        if (method != "restore") return result
        val pkg = extras?.getString("pkg") ?: arg ?: return result

        if (!callerIsDefaultLauncher()) {
            Logger.d("FreezerBridge", "restore refused (caller not the default launcher): uid ${Binder.getCallingUid()}")
            return result
        }

        // Caller verified above; run the privileged unfreeze under Thor's OWN identity (not the
        // launcher's) so any downstream permission checks attribute to Thor. Restore in finally.
        val token = Binder.clearCallingIdentity()
        val ok = try {
            runCatching {
                runBlocking {
                    val inFreezer = freezerRepository.getAllPackageNames().toSet()
                    if (!mayRestore(pkg, inFreezer)) {
                        Logger.d("FreezerBridge", "restore refused (not in freezer): $pkg")
                        false
                    } else {
                        manageAppUseCase.forceUnfreeze(pkg).isSuccess
                    }
                }
            }.getOrElse {
                Logger.e("FreezerBridge", "restore failed for $pkg", it)
                false
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        result.putBoolean("ok", ok)
        return result
    }

    /**
     * True only when the calling UID owns the device's CURRENT default home launcher. We resolve the
     * *default* (`MATCH_DEFAULT_ONLY`) rather than querying every HOME-category app: any app can
     * declare a HOME activity, so an all-apps query would let a malicious app spoof launcher status.
     * The Strombringer hook only runs in the real launcher, so this is exactly the legitimate caller.
     */
    private fun callerIsDefaultLauncher(): Boolean {
        val pm = context?.packageManager ?: return false
        val callers = pm.getPackagesForUid(Binder.getCallingUid())?.toSet() ?: return false
        val defaultHome = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName ?: return false
        return defaultHome in callers
    }

    // Unused CRUD surface.
    override fun query(u: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, sa: Array<out String>?): Int = 0
}
