package com.valhalla.thor.presentation.corepatch

/**
 * The immutable, UI-facing snapshot the per-op CorePatch confirm dialog renders (Task 11).
 *
 * Pure and Android-free so the signer-diff / capability logic is trivially unit-testable. The VM
 * builds this once, shows it, and — on confirm — feeds [pkg]/[capability]/[newSignerSha256] straight
 * into [com.valhalla.thor.domain.model.CorePatchAuthorization] so the armed package is byte-identical
 * to what the user saw (never re-derived on the install side).
 *
 * @param installedSignerSha256 the signer of the currently-installed package, or null on a fresh
 *   install (no prior package). Null means there is nothing to mismatch against → [capability] is
 *   always `"digest"`.
 */
data class CorePatchConfirmState(
    val pkg: String,
    val installedSignerSha256: String?,
    val newSignerSha256: String,
    val capability: String,
    val isDowngrade: Boolean,
)

/**
 * The CorePatch capability the hook must grant for this install:
 *  - `"sig"`    — a real signer mismatch (installed signer present AND differs from the new signer):
 *                 the strongest bypass, letting a differently-signed APK overwrite a trusted one.
 *  - `"digest"` — same signer (or a fresh install with no prior signer): only the weaker
 *                 digest/downgrade path is needed.
 *
 * Kotlin `String` has NO `equalsIgnoreCase`; the case-insensitive compare uses `equals(x, ignoreCase = true)`.
 * Pure.
 */
fun capabilityFor(installedSigner: String?, newSigner: String): String =
    if (installedSigner != null && !installedSigner.equals(newSigner, ignoreCase = true)) "sig"
    else "digest"

/**
 * Assemble a [CorePatchConfirmState]. [capability] is passed in (the caller derives it via
 * [capabilityFor]) so the value shown, armed, and audited is one and the same. Pure.
 */
fun buildCorePatchConfirmState(
    pkg: String,
    installed: String?,
    new: String,
    capability: String,
    isDowngrade: Boolean,
): CorePatchConfirmState = CorePatchConfirmState(
    pkg = pkg,
    installedSignerSha256 = installed,
    newSignerSha256 = new,
    capability = capability,
    isDowngrade = isDowngrade,
)
