// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.rootservice

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import androidx.annotation.Keep
import com.valhalla.superuser.ipc.RootService
import com.valhalla.thor.BuildConfig
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Debug APK fixture only: no manifest component and no addition to Thor's production AIDL. */
@Keep
class RootServiceLifecycleFixture : RootService() {
    private val instance = UUID.randomUUID().toString()

    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            this@RootServiceLifecycleFixture.enforceCaller()
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(RootServiceLifecycleProtocol.DESCRIPTOR)
                return true
            }
            if (code != RootServiceLifecycleProtocol.IDENTITY && code != RootServiceLifecycleProtocol.HOLD) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(RootServiceLifecycleProtocol.DESCRIPTOR)
            val response = when (code) {
                RootServiceLifecycleProtocol.IDENTITY -> identity()
                else -> hold(requireNotNull(data.readString()), data.readLong())
            }
            requireNotNull(reply).apply {
                writeNoException()
                writeString(response.toString())
            }
            return true
        }
    }

    internal fun identity(): JSONObject = JSONObject()
        // This constant is inlined into the fixture bytecode; A/B APKs can prove which code loaded.
        .put("build", BuildConfig.VERSION_NAME)
        .put("pid", Process.myPid())
        .put("instance", instance)
        .put("rootUid", Process.myUid())
        .put("contextUid", applicationInfo.uid)
        .put("callingUid", Binder.getCallingUid())
        .put("dataDir", applicationInfo.dataDir)
        .put("apkPath", packageCodePath)

    internal fun hold(token: String, timeoutMillis: Long): JSONObject {
        require(timeoutMillis in 1..RootServiceLifecycleProtocol.MAX_HOLD_MILLIS)
        val directory = RootServiceLifecycleProtocol.directory(this, token).canonicalFile
        val expectedParent = File(cacheDir, RootServiceLifecycleProtocol.DIRECTORY).canonicalFile
        require(directory.parentFile == expectedParent && directory.isDirectory)
        fun precreated(name: String): File = File(directory, name).also {
            require(it.isFile && it.canonicalFile.parentFile == directory) {
                "The app must precreate fixture files inside its private run directory"
            }
        }
        val entered = precreated(RootServiceLifecycleProtocol.ENTERED)
        val release = precreated(RootServiceLifecycleProtocol.RELEASE)
        val completed = precreated(RootServiceLifecycleProtocol.COMPLETED)
        entered.writeText(identity().put("token", token).put("phase", "entered").toString())
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var released = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (release.readText().trim() == token) {
                released = true
                break
            }
            Thread.sleep(20)
        }
        return identity().put("token", token).put("released", released).also {
            completed.writeText(it.toString())
        }
    }
}

/** Shared only by the debug fixture and its instrumentation client. */
object RootServiceLifecycleProtocol {
    const val DESCRIPTOR = "com.valhalla.thor.debug.RootServiceLifecycleFixture.v1"
    const val IDENTITY = IBinder.FIRST_CALL_TRANSACTION
    const val HOLD = IBinder.FIRST_CALL_TRANSACTION + 1
    const val MAX_HOLD_MILLIS = 120_000L
    const val DIRECTORY = "odin-root-lifecycle"
    const val ENTERED = "entered.json"
    const val RELEASE = "release"
    const val COMPLETED = "completed.json"
    const val RESULT = "result.json"

    fun directory(context: Context, token: String): File {
        require(UUID.fromString(token).toString() == token) { "A canonical UUID run token is required" }
        return File(File(context.cacheDir, DIRECTORY), token)
    }
}
