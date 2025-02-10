@file:Suppress("unused")

package com.valhalla.thor.model

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.security.MessageDigest

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
    @Suppress("KotlinConstantConditions")
    if(BuildConfig.API_URL!="null") {
        IntegrityManagerFactory.createStandard(this).prepareIntegrityToken(
            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(187862217680)
                .build()
        ).addOnSuccessListener {
            onIntegrityTokenProvider(Result.success(it))
        }.addOnFailureListener {
            it.printStackTrace()
            onIntegrityTokenProvider(Result.failure(it))
        }
    }else {
        onIntegrityTokenProvider(Result.failure(Exception("API_URL is not set, please set up your backend for play integrity verification.")))
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
        @Suppress("KotlinConstantConditions")
        if(BuildConfig.API_URL!="null") {
            try {
                val url = URL(BuildConfig.API_URL + "api/check?token=$token")
                Log.d("IntegrityUtils", "getTokenResponse: url = $url")
                onVerdictResult(Result.success(url.readText()))
            } catch (e: Exception) {
                e.printStackTrace()
                onVerdictResult(Result.failure(e))
            }
        }else{
            onVerdictResult(Result.failure(Exception("API_URL is not set, please set up your backend for play integrity verification.")))
        }
    }
}

fun generateNonce(): String {
    return (1..50).map { (('A'..'Z') + ('a'..'z') + ('0'..'9')).random() }.joinToString("")
}

fun parseIntegrityStatus(jsonString: String) = when {
    jsonString.isEmpty() -> "Checking Integrity"
    jsonString.contains(
        "meets_strong_integrity",
        true
    ) -> "Strong Integrity found"

    jsonString.contains(
        "meets_device_integrity",
        true
    ) -> "Device Integrity found"

    jsonString.contains(
        "meets_basic_integrity",
        true
    ) -> "Basic Integrity found"

    else -> "Messed up device found"
}

fun parseIntegrityIcon(jsonString: String) = when {
    jsonString.isEmpty() -> R.drawable.shield_countdown
    jsonString.contains(
        "meets_strong_integrity",
        true
    ) -> R.drawable.shield_with_heart

    jsonString.contains(
        "meets_device_integrity",
        true
    ) -> R.drawable.shield_verified

    jsonString.contains(
        "meets_basic_integrity",
        true
    ) -> R.drawable.shield_maybe

    else -> R.drawable.shield_bad
}