package com.valhalla.thor.data.provider

import android.content.ContentProvider
import android.content.ContentValues
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
 * unsuspend an app on launch. Bounded to Freezer packages (see mayRestore) since the caller is the
 * launcher process and can't be cryptographically verified as our extension.
 */
class FreezerBridgeProvider : ContentProvider(), KoinComponent {
    private val freezerRepository: FreezerRepository by inject()
    private val manageAppUseCase: ManageAppUseCase by inject()

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        if (method != "restore") { result.putBoolean("ok", false); return result }
        val pkg = extras?.getString("pkg") ?: arg ?: run {
            result.putBoolean("ok", false); return result
        }
        val ok = runBlocking {
            val inFreezer = freezerRepository.getAllPackageNames().toSet()
            if (!mayRestore(pkg, inFreezer)) {
                Logger.d("FreezerBridge", "restore refused (not in freezer): $pkg from uid ${Binder.getCallingUid()}")
                false
            } else {
                manageAppUseCase.forceUnfreeze(pkg).isSuccess
            }
        }
        result.putBoolean("ok", ok)
        return result
    }

    // Unused CRUD surface.
    override fun query(u: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, sa: Array<out String>?): Int = 0
}
