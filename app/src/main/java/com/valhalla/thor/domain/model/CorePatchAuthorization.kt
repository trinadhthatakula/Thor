package com.valhalla.thor.domain.model

/**
 * A single-shot authorization for a CorePatch (Xposed signature-bypass) install.
 *
 * Produced by the per-op confirmation UI (Task 11 passes `CorePatchConfirmState.pkg`) and consumed
 * ONLY by the synchronous root install bracket ([com.valhalla.thor.data.repository.InstallerRepositoryImpl]).
 *
 * [pkg] is carried explicitly so the armed package is byte-identical to what the user confirmed — it
 * is NEVER re-derived from the APK on the install side. The Strombringer hook cross-checks the armed
 * (pkg, signerSha256, capability) triple before letting the signature mismatch through.
 */
data class CorePatchAuthorization(
    val pkg: String,
    val capability: String,
    val expectedNewSignerSha256: String,
    val disablePlayProtect: Boolean,
    val downgrade: Boolean,
)
