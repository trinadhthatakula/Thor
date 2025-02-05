package com.valhalla.thor.model

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.valhalla.thor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.security.MessageDigest

//var integrityTokenProvider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

fun Context.initIntegrityManager(
    onIntegrityTokenProvider: (Result<String>) -> Unit = {}
) {
    IntegrityManagerFactory.create(this@initIntegrityManager).requestIntegrityToken(
        IntegrityTokenRequest.builder()
            .setNonce(generateNonce())
            .setCloudProjectNumber(187862217680)
            .build()
    ).addOnSuccessListener {
        onIntegrityTokenProvider(Result.success(it.token()))
    }.addOnFailureListener {
        it.printStackTrace()
        onIntegrityTokenProvider(Result.failure(it))
    }
}

fun Context.initStandardIntegrityProvider(
    onIntegrityTokenProvider: (Result<StandardIntegrityManager.StandardIntegrityTokenProvider>) -> Unit = {}
) {
    /*if (integrityTokenProvider != null)
        onIntegrityTokenProvider(Result.success(integrityTokenProvider!!))
    else*/
        IntegrityManagerFactory.createStandard(this).prepareIntegrityToken(
            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(187862217680)
                .build()
        ).addOnSuccessListener {
            //integrityTokenProvider = it
            onIntegrityTokenProvider(Result.success(it))
        }.addOnFailureListener {
            it.printStackTrace()
            onIntegrityTokenProvider(Result.failure(it))
        }
}

fun randomHash(): String {
    val bytes = String(ByteArray(256)).toString().toByteArray()
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.fold("") { str, it -> str + "%02x".format(it) }
}

fun String.hash(): String {
    val bytes = this.toString().toByteArray()
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.fold("") { str, it -> str + "%02x".format(it) }
}

fun getVerdict(
    integrityTokenProvider: StandardIntegrityManager.StandardIntegrityTokenProvider,
    onVerdictResult: (Result<String>) -> Unit
) {
    integrityTokenProvider.request(
        StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
            .setRequestHash(randomHash())
            .build()
    ).addOnSuccessListener {
        onVerdictResult(Result.success(it.token()))
    }.addOnFailureListener {
        it.printStackTrace()
        onVerdictResult(Result.failure(it))
    }
}

suspend fun getTokenResponse(token: String, onVerdictResult: (Result<String>) -> Unit) {
    withContext(Dispatchers.IO) {
        try {
            val url = URL(BuildConfig.API_URL+"api/check?token=$token")
            Log.d("IntegrityUtils", "getTokenResponse: url = $url")
            onVerdictResult(Result.success(url.readText()))
        } catch (e: Exception) {
            e.printStackTrace()
            onVerdictResult(Result.failure(e))
        }
    }
}

fun generateNonce(): String {
    return (1..50).map { (('A'..'Z') + ('a'..'z') + ('0'..'9')).random() }.joinToString("")
}