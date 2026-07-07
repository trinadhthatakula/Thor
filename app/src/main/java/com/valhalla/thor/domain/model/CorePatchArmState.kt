// CorePatchArmState.kt
package com.valhalla.thor.domain.model

data class ArmState(
    val armed: Boolean,
    val pkg: String?,
    val signerSha256: String?,
    val capability: String?,
    val deadlineMillis: Long,
) {
    companion object {
        val DISARMED = ArmState(false, null, null, null, 0L)
    }
}

fun ArmState.authorizes(
    nowMillis: Long,
    candidatePkg: String,
    candidateSignerSha256: String,
    candidateCapability: String,
): Boolean =
    armed &&
        nowMillis <= deadlineMillis &&
        pkg == candidatePkg &&
        signerSha256?.equals(candidateSignerSha256, ignoreCase = true) == true &&
        capability == candidateCapability
