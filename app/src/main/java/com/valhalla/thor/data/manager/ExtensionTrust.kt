package com.valhalla.thor.data.manager

import java.security.MessageDigest

/**
 * Pinned certificate SHA-256 allowlist for Thor extensions.
 *
 * An extension is only loaded (and only shown as "verified") when the SHA-256 of its
 * signer certificate is present here. This is a hard trust boundary: nothing signed by
 * an unknown key can be loaded into Thor's process via [ExtensionManager.loadExtensions].
 *
 * Values are the signer-cert SHA-256 as reported by `keytool -list -v` / apksigner —
 * uppercase hex, no separators. Case-insensitive comparison is used at match time.
 */
object TrustedExtensionSigners {

    /**
     * Dedicated "Thor Extensions" signing key — RSA-4096, generated 2026-07-08.
     *
     * Key rotation: ADD the new pin to this set BEFORE any extension switches to the new
     * key, so extensions signed with either the old or the new key keep loading through
     * the transition window. Never remove a pin until every extension has migrated off it.
     */
    val PINS: Set<String> = setOf(
        "762DC455D6F5CE05E7D1848057FDF04362D137B7AB987879AFDF370B10F9498C",
    )
}

/**
 * SHA-256 of these bytes as uppercase hex with no separators.
 *
 * For a signer certificate's DER bytes this equals the digest printed by
 * `keytool -list -v` and by apksigner. Bytes are masked to 0..255 so negative [Byte]
 * values (e.g. 0xFF) are not sign-extended in the output.
 */
fun ByteArray.toCertSha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xFF) }

/**
 * True iff [sha256Hex] is present in [pins], compared case-insensitively. Defaults to the
 * production allowlist [TrustedExtensionSigners.PINS].
 */
fun isPinnedSigner(
    sha256Hex: String,
    pins: Set<String> = TrustedExtensionSigners.PINS,
): Boolean = pins.any { pin -> pin.equals(sha256Hex, ignoreCase = true) }
