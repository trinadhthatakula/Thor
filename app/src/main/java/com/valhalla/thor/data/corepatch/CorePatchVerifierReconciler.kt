package com.valhalla.thor.data.corepatch

import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Single

/**
 * Play-Protect self-heal, run once at app startup.
 *
 * The synchronous root install bracket ([com.valhalla.thor.data.repository.InstallerRepositoryImpl])
 * transiently turns the global package verifier OFF for a bypass install, writing the durable
 * [PreferenceRepository.setVerifierIntentionallyDisabled] marker BEFORE the flip and clearing it in
 * the `finally`. If the process is killed mid-install (crash / OOM / user swipe) the `finally` never
 * runs and the verifier is left off — a fail-open we must never ship.
 *
 * On the next launch a fresh process has nothing in flight, so if the marker survived we know the
 * restore was skipped: force the verifier back ON and clear the marker. When re-enabling fails
 * (e.g. root is momentarily unavailable) the marker is intentionally left set so we retry next
 * launch rather than silently forgetting a still-disabled verifier.
 */
@Single
class CorePatchVerifierReconciler(
    private val rootGateway: RootSystemGateway,
    private val preferenceRepository: PreferenceRepository,
) {
    suspend fun reconcile() {
        val disabled = try {
            preferenceRepository.userPreferences.first().verifierIntentionallyDisabled
        } catch (e: Exception) {
            Logger.e("CorePatchReconciler", "Failed to read verifier marker", e)
            return
        }
        if (!disabled) return // common case: nothing to heal, never touches root.

        Logger.w("CorePatchReconciler", "Verifier marker survived a crash — forcing package verifier back on")
        val restored = rootGateway.setPackageVerifierEnabled(true)
        if (restored.isSuccess) {
            preferenceRepository.setVerifierIntentionallyDisabled(false)
        } else {
            // Keep the marker so we retry on the next launch instead of leaving the verifier off.
            Logger.e("CorePatchReconciler", "Could not re-enable package verifier; will retry next launch", restored.exceptionOrNull())
        }
    }
}
