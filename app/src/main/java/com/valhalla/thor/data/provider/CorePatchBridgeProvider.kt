package com.valhalla.thor.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.valhalla.thor.data.corepatch.CorePatchArmStateHolder
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * True only for system_server (SYSTEM_UID). The Strombringer hook that reads arm-state runs inside
 * system_server, so this is the single legitimate caller. Any other UID is treated as "disarmed".
 */
internal fun isSystemCaller(callingUid: Int): Boolean = callingUid == Process.SYSTEM_UID

/**
 * Serves the in-memory CorePatch arm-state to the Strombringer signature-bypass hook (which runs in
 * system_server). Exported so system_server can reach it, but hard-guarded to SYSTEM_UID: any other
 * caller — or any method other than "getArmState" — sees only `armed=false` (fail-safe disarmed).
 */
class CorePatchBridgeProvider : ContentProvider(), KoinComponent {
    private val armState: CorePatchArmStateHolder by inject()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        // Only system_server may read arm-state. Any other caller sees "disarmed".
        if (!isSystemCaller(Binder.getCallingUid())) {
            result.putBoolean("armed", false)
            return result
        }
        if (method != "getArmState") {
            result.putBoolean("armed", false)
            return result
        }
        val map = armState.toBundleMap()
        result.putBoolean("armed", map["armed"] as Boolean)
        (map["pkg"] as? String)?.let { result.putString("pkg", it) }
        (map["signerSha256"] as? String)?.let { result.putString("signerSha256", it) }
        (map["capability"] as? String)?.let { result.putString("capability", it) }
        (map["deadlineMillis"] as? Long)?.let { result.putLong("deadlineMillis", it) }
        return result
    }

    // Unused CRUD surface.
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
