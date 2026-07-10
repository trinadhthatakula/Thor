package com.valhalla.thor.domain.model

/** Package-name prefix every Thor extension shares. */
const val EXTENSION_OPS_PREFIX = "com.valhalla.thor.ext."

/**
 * True iff [caller] may invoke the extension-ops provider. A null caller (same-process) or Thor's own
 * package ([ownPackage]) is always allowed. A cross-process caller must be an extension
 * ([EXTENSION_OPS_PREFIX]) that is either a pinned signer ([isPinnedSigner]) or — in [isDebug] builds —
 * any ext-prefixed package (so self-built extensions work locally). Everything else is refused.
 */
fun isAuthorizedExtensionCaller(
    caller: String?,
    ownPackage: String,
    isPinnedSigner: Boolean,
    isDebug: Boolean,
    isSameProcess: Boolean,
): Boolean {
    if (isSameProcess) return true
    if (caller == null) return false
    if (caller == ownPackage) return true
    if (!caller.startsWith(EXTENSION_OPS_PREFIX)) return false
    return isPinnedSigner || isDebug
}

/**
 * The packages an op should actually touch: [requested] minus [guarded] and blanks, de-duplicated,
 * original order preserved.
 */
fun opTargets(requested: List<String?>, guarded: Set<String>): List<String> =
    requested.filterNotNull().filter { it.isNotBlank() && it !in guarded }.distinct()
