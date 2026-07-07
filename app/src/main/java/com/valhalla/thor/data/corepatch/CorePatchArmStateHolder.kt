// CorePatchArmStateHolder.kt
package com.valhalla.thor.data.corepatch

import com.valhalla.thor.domain.model.ArmState
import org.koin.core.annotation.Single
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory ONLY. Never persisted — process death / reboot => disarmed => fail-safe.
 * Lazy expiry against the injected clock guarantees correctness even if a watchdog is missed.
 */
@Single
class CorePatchArmStateHolder(private val clock: () -> Long = System::currentTimeMillis) {
    private val ref = AtomicReference(ArmState.DISARMED)

    fun arm(pkg: String, signerSha256: String, capability: String, ttlMillis: Long): ArmState {
        val s = ArmState(true, pkg, signerSha256, capability, clock() + ttlMillis)
        ref.set(s)
        return s
    }

    fun disarm() = ref.set(ArmState.DISARMED)

    fun current(): ArmState {
        val s = ref.get()
        return if (s.armed && clock() <= s.deadlineMillis) s else ArmState.DISARMED
    }

    fun toBundleMap(): Map<String, Any> {
        val s = current()
        if (!s.armed) return mapOf("armed" to false)
        return mapOf(
            "armed" to true,
            "pkg" to s.pkg!!,
            "signerSha256" to s.signerSha256!!,
            "capability" to s.capability!!,
            "deadlineMillis" to s.deadlineMillis,
        )
    }
}
