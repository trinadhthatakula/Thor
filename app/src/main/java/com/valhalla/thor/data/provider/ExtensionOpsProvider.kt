package com.valhalla.thor.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.manager.ExtensionManager
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.isAuthorizedExtensionCaller
import com.valhalla.thor.domain.model.opTargets
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Privileged package operations for trusted extensions. An extension (its own process) hands Thor a
 * list of packages and an op; Thor — which holds root/Shizuku/Dhizuku — performs it. This REPLACES the
 * old broadcast→onTrigger path: no extension code runs in Thor's process (so the minified-kotlin
 * classloader coupling that broke `onTrigger` can't happen), and a provider access reliably cold-starts
 * Thor (so it works even when Thor was killed — unlike the broadcast on MIUI).
 *
 * Security (mirrors [FreezerBridgeProvider]): the caller is UID-attested via getCallingPackage() and must
 * be a pinned-signer extension; the privileged work runs under Thor's OWN identity
 * (Binder.clearCallingIdentity()); Thor's own package and the caller are never operated on.
 */
class ExtensionOpsProvider : ContentProvider(), KoinComponent {
    private val manageAppUseCase: ManageAppUseCase by inject()
    private val preferenceRepository: PreferenceRepository by inject()
    private val extensionManager: ExtensionManager by inject()

    private enum class Op { FREEZE, UNFREEZE, TOGGLE }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle().apply { putBoolean("ok", false); putInt("count", 0) }
        val ctx = context ?: return result
        val op = when (method) {
            "freeze" -> Op.FREEZE
            "unfreeze" -> Op.UNFREEZE
            "toggle" -> Op.TOGGLE
            else -> return result
        }

        val caller = callingPackage
        val pinned = caller != null && runCatching { extensionManager.isSignatureVerified(caller) }.getOrDefault(false)
        if (!isAuthorizedExtensionCaller(caller, ctx.packageName, isPinnedSigner = pinned, isDebug = BuildConfig.DEBUG)) {
            Logger.d("ExtensionOps", "op '$method' refused (unauthorized caller): $caller / uid ${Binder.getCallingUid()}")
            return result
        }

        val guarded = setOfNotNull(ctx.packageName, "com.valhalla.thor", "com.valhalla.thor.debug", caller)
        val targets = opTargets(extras?.getStringArray("packages")?.toList().orEmpty(), guarded)
        if (targets.isEmpty()) { result.putBoolean("ok", true); return result }

        val token = Binder.clearCallingIdentity()
        val count = try {
            runCatching {
                runBlocking {
                    val suspendMode = preferenceRepository.userPreferences.first().freezerMode == FreezerMode.SUSPEND
                    val effective = if (op == Op.TOGGLE) {
                        if (anyFrozen(ctx.packageManager, targets)) Op.UNFREEZE else Op.FREEZE
                    } else op
                    targets.count { pkg ->
                        when (effective) {
                            Op.FREEZE ->
                                if (suspendMode) manageAppUseCase.setAppSuspended(pkg, true)
                                else manageAppUseCase.setAppDisabled(pkg, true)
                            Op.UNFREEZE -> manageAppUseCase.forceUnfreeze(pkg)
                            Op.TOGGLE -> Result.failure(IllegalStateException()) // resolved above
                        }.isSuccess
                    }
                }
            }.getOrElse { Logger.e("ExtensionOps", "op '$method' failed", it); 0 }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        result.putBoolean("ok", count == targets.size)
        result.putInt("count", count)
        return result
    }

    /** True if any of [pkgs] is currently frozen (disabled OR suspended). MATCH_DISABLED so we can read a disabled app. */
    private fun anyFrozen(pm: PackageManager, pkgs: List<String>): Boolean = pkgs.any { pkg ->
        runCatching {
            val info = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
            !info.enabled || (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
        }.getOrDefault(false)
    }

    // Unused CRUD surface.
    override fun query(u: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, sa: Array<out String>?): Int = 0
}
