// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.gateway.ComponentEnabledState
import com.valhalla.thor.domain.model.ComponentDetail
import com.valhalla.thor.domain.model.ComponentOverride
import com.valhalla.thor.domain.model.ComponentType
import com.valhalla.thor.domain.repository.ComponentOverrideRepository
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory

/**
 * Which state to write when the user asks to switch a component **on**.
 *
 * Preferring [ComponentEnabledState.DEFAULT] whenever the manifest already says enabled is not
 * tidiness. An explicit `ENABLED` override is a row in `package-restrictions.xml` that outlives the
 * app update which removed the component, pins the component on if a later version ships it
 * disabled on purpose, and reads as a deviation to anything auditing component state. Writing
 * `default-state` leaves the app exactly as its developer shipped it, with no trace that Thor was
 * ever here — the correct end state for an undo.
 *
 * When the manifest default is *disabled*, `default-state` would switch the component straight back
 * off, so an explicit [ComponentEnabledState.ENABLED] is the only way to honour the request.
 */
fun enableTargetState(manifestDefaultEnabled: Boolean): ComponentEnabledState =
    if (manifestDefaultEnabled) ComponentEnabledState.DEFAULT else ComponentEnabledState.ENABLED

/**
 * The per-component verbs, with the ledger kept in step.
 *
 * The pairing is the reason this exists rather than the ViewModel calling [SystemRepository]
 * directly: a disable that is not recorded is a change the user can never find again, and a
 * recorded disable that never happened is a Restore All that undoes something Thor did not do.
 * Ordering is deliberate in both directions — **the ledger is written only after the platform call
 * succeeds, and cleared only after the restoring call succeeds** — so a failure at any point leaves
 * the ledger describing the world as it is.
 */
@Factory
class ComponentControlUseCase(
    private val systemRepository: SystemRepository,
    private val overrides: ComponentOverrideRepository,
) {

    fun observeOverrides(packageName: String): Flow<List<ComponentOverride>> =
        overrides.observe(packageName)

    suspend fun allOverrides(): List<ComponentOverride> = overrides.getAll()

    /** Switch [component] off and record it. */
    suspend fun disable(
        packageName: String,
        type: ComponentType,
        component: ComponentDetail,
    ): Result<Unit> = systemRepository
        .setComponentEnabled(packageName, component.className, ComponentEnabledState.DISABLED)
        .onSuccess {
            overrides.record(
                packageName = packageName,
                className = component.className,
                type = type,
                // The manifest default as it stands right now, not `true`: a component that ships
                // disabled must be restored to disabled or Thor has invented a state the app never
                // had.
                restoreToEnabled = component.manifestDefaultEnabled,
            )
        }

    /** Switch [component] back on and drop its ledger row. */
    suspend fun enable(packageName: String, component: ComponentDetail): Result<Unit> =
        systemRepository
            .setComponentEnabled(
                packageName,
                component.className,
                enableTargetState(component.manifestDefaultEnabled),
            )
            .onSuccess { overrides.forget(packageName, component.className) }

    /**
     * Remove the override entirely, whatever it was, and drop the ledger row.
     *
     * Distinct from [enable]: this is offered for a component whose state somebody *else* set, where
     * "on" would be Thor asserting a preference rather than stepping out of the way.
     */
    suspend fun resetToDefault(packageName: String, className: String): Result<Unit> =
        systemRepository
            .setComponentEnabled(packageName, className, ComponentEnabledState.DEFAULT)
            .onSuccess { overrides.forget(packageName, className) }

    /**
     * Put every component Thor disabled back the way it found it.
     *
     * Each row is restored to its own `restoreToEnabled`, and a row is forgotten only when its own
     * call succeeds. A partial failure therefore leaves exactly the rows that are still overridden,
     * which is what makes pressing Restore All again a safe retry rather than a second, different
     * operation.
     *
     * @return the number restored and the number attempted.
     */
    suspend fun restoreAll(): RestoreAllOutcome {
        val rows = overrides.getAll()
        var restored = 0
        rows.forEach { row ->
            val result = systemRepository.setComponentEnabled(
                row.packageName,
                row.className,
                enableTargetState(row.restoreToEnabled),
            )
            if (result.isSuccess) {
                overrides.forget(row.packageName, row.className)
                restored++
            }
        }
        return RestoreAllOutcome(restored = restored, attempted = rows.size)
    }

    /** Forget a row without touching the platform — for an override something else already undid. */
    suspend fun forget(packageName: String, className: String) =
        overrides.forget(packageName, className)

    suspend fun forceLaunch(packageName: String, className: String): Result<Unit> =
        systemRepository.forceLaunchActivity(packageName, className)

    suspend fun stopService(packageName: String, className: String): Result<Unit> =
        systemRepository.stopService(packageName, className)
}

/**
 * How Restore All went.
 *
 * Two numbers rather than a boolean because "restored 6 of 9" is the only honest report of a partial
 * run, and a boolean would have to choose between calling that a success and calling it a failure.
 */
data class RestoreAllOutcome(val restored: Int, val attempted: Int) {
    val isComplete: Boolean get() = restored == attempted
}
