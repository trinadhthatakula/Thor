// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec

/** The public half is captured before the prompt. Only the matching private key can answer. */
internal class AppLockChallenge(private val publicKey: PublicKey) {
    private val nonce = ByteArray(32).also(SecureRandom()::nextBytes)

    fun verifyWith(signer: Signature): Boolean {
        signer.update(nonce)
        val answer = signer.sign()
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(nonce)
            verify(answer)
        }
    }
}

internal class AppLockKey(private val privateKey: PrivateKey, publicKey: PublicKey) {
    val challenge = AppLockChallenge(publicKey)
    fun newSigner(): Signature = Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }

    fun verifyCredential(): Boolean = try {
        challenge.verifyWith(newSigner())
    } catch (e: KeyPermanentlyInvalidatedException) {
        // The next credential confirmation will capture the replacement public key.
        discard(CREDENTIAL_ALIAS)
        throw e
    }

    companion object {
        private const val STORE = "AndroidKeyStore"
        private const val CREDENTIAL_ALIAS = "thor.app-lock.q-credential"
        private val biometricAlias: String
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "thor.app-lock.combined" else "thor.app-lock.biometric"

        @Synchronized
        internal fun discard(alias: String) {
            KeyStore.getInstance(STORE).apply {
                load(null)
                deleteEntry(alias)
            }
        }

        fun openCredentialKey(): AppLockKey = open(CREDENTIAL_ALIAS)

        // Android 10 requires separate key policies for biometrics and the device credential.
        @Synchronized
        private fun open(alias: String): AppLockKey {
            val store = KeyStore.getInstance(STORE).apply { load(null) }
            fun read(): AppLockKey {
                if (!store.containsAlias(alias)) {
                    val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        spec.setUserAuthenticationParameters(
                            0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                        )
                    } else if (alias == CREDENTIAL_ALIAS) {
                        // Q only authorizes a PIN/pattern/password key for a time window.
                        // Each use also requires a new successful system credential activity result.
                        @Suppress("DEPRECATION")
                        spec.setUserAuthenticationValidityDurationSeconds(5)
                    }
                    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply {
                        initialize(spec.build())
                        generateKeyPair()
                    }
                }
                return AppLockKey(store.getKey(alias, null) as PrivateKey, store.getCertificate(alias).publicKey)
            }
            // Enrollment or resetting the device lock can invalidate a previous key. Recover
            // before presenting a new challenge, never replace a key to accept a pending result.
            try {
                return read()
            } catch (_: UnrecoverableKeyException) {
                store.deleteEntry(alias)
                return read()
            }
        }

        @Synchronized
        fun biometricOperation(): Pair<AppLockChallenge, Signature> {
            fun begin() = open(biometricAlias).let { it.challenge to it.newSigner() }
            try {
                return begin()
            } catch (_: KeyPermanentlyInvalidatedException) {
                discard(biometricAlias)
                return begin()
            }
        }
    }
}
