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
 * The outcome of a verb that changes the platform *and* writes the ledger.
 *
 * Two fields rather than one [Result] because the two halves can disagree, and collapsing them
 * loses the only case that matters: **the privileged change went through and the record of it did
 * not.** Chaining the ledger write inside `Result.onSuccess` collapsed exactly that way — that block
 * does not catch, so a Room failure (`SQLiteFullException`, a disk-I/O error) threw out through the
 * `Result` and the caller reported a failure for a component that was, in fact, already off.
 *
 * The platform is the source of truth, so [platform] alone decides whether the *action* succeeded.
 * [ledgerError] is a second, quieter fact about Thor's bookkeeping, and only [disable] has to say it
 * out loud: an unrecorded disable is invisible to Restore All. For the restoring verbs a failed
 * ledger delete leaves a row whose component is enabled, which the UI already surfaces on its own as
 * drift — "Changed elsewhere", with Forget offered — so there is nothing extra to tell the user.
 */
data class ComponentActionOutcome(
    val platform: Result<Unit>,
    val ledgerError: Throwable? = null,
) {
    val isPlatformSuccess: Boolean get() = platform.isSuccess
}

/**
 * The per-component verbs, with the ledger kept in step.
 *
 * The pairing is the reason this exists rather than the ViewModel calling [SystemRepository]
 * directly: a disable that is not recorded is a change the user can never find again, and a
 * recorded disable that never happened is a Restore All that undoes something Thor did not do.
 * Ordering is deliberate in both directions — **the ledger is written only after the platform call
 * succeeds, and cleared only after the restoring call succeeds** — so a failure at any point leaves
 * the ledger describing the world as it is.
 *
 * Every ledger touch is wrapped. The repository hands Room calls straight through, so each one can
 * throw on a full or damaged database; unwrapped, that throw either took the process down (the
 * verbs the ViewModel launched bare) or was misreported as the platform call failing.
 */
@Factory
class ComponentControlUseCase(
    private val systemRepository: SystemRepository,
    private val overrides: ComponentOverrideRepository,
) {

    fun observeOverrides(packageName: String): Flow<List<ComponentOverride>> =
        overrides.observe(packageName)

    /** Switch [component] off and record it. */
    suspend fun disable(
        packageName: String,
        type: ComponentType,
        component: ComponentDetail,
    ): ComponentActionOutcome {
        val platform = systemRepository
            .setComponentEnabled(packageName, component.className, ComponentEnabledState.DISABLED)
        if (platform.isFailure) return ComponentActionOutcome(platform)
        val ledger = runCatching {
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
        return ComponentActionOutcome(platform, ledger.exceptionOrNull())
    }

    /** Switch [component] back on and drop its ledger row. */
    suspend fun enable(packageName: String, component: ComponentDetail): ComponentActionOutcome =
        restoring(
            systemRepository.setComponentEnabled(
                packageName,
                component.className,
                enableTargetState(component.manifestDefaultEnabled),
            ),
            packageName,
            component.className,
        )

    /**
     * Remove the override entirely, whatever it was, and drop the ledger row.
     *
     * Distinct from [enable]: this is offered for a component whose state somebody *else* set, where
     * "on" would be Thor asserting a preference rather than stepping out of the way.
     */
    suspend fun resetToDefault(packageName: String, className: String): ComponentActionOutcome =
        restoring(
            systemRepository
                .setComponentEnabled(packageName, className, ComponentEnabledState.DEFAULT),
            packageName,
            className,
        )

    /** Drop the row for a component the platform call just put back, if it did. */
    private suspend fun restoring(
        platform: Result<Unit>,
        packageName: String,
        className: String,
    ): ComponentActionOutcome {
        if (platform.isFailure) return ComponentActionOutcome(platform)
        val ledger = runCatching { overrides.forget(packageName, className) }
        return ComponentActionOutcome(platform, ledger.exceptionOrNull())
    }

    /**
     * Put every component Thor disabled back the way it found it.
     *
     * Each row is restored to its own `restoreToEnabled`, and a row is forgotten only when **both**
     * its platform call and its ledger delete succeed. A partial failure therefore leaves exactly the
     * rows that did not fully complete, which is what makes pressing Restore All again a safe retry
     * rather than a second, different operation. That set is wider than "the components still
     * disabled": one the platform put back whose row could not be deleted stays behind too, and the
     * next press restores an already-restored component, which costs nothing.
     *
     * A row counts as restored only when **both** halves land. Counting a successful platform call
     * whose ledger delete failed would report "restored 3 of 3" while a row was still sitting in the
     * table telling the screen that 1 component is still restricted — a completion message the
     * user's own screen contradicts. Under-reporting instead keeps the two consistent, and the row
     * that stays behind makes the next press retry it; `setComponentEnabled` is idempotent, so a
     * retry on an already-restored component is free. Each delete is wrapped so that one unwritable
     * row cannot abort the sweep over all the others.
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
            if (result.isSuccess &&
                runCatching { overrides.forget(row.packageName, row.className) }.isSuccess
            ) {
                restored++
            }
        }
        return RestoreAllOutcome(restored = restored, attempted = rows.size)
    }

    /**
     * Forget a row without touching the platform — for an override something else already undid.
     *
     * Returns a [Result] rather than throwing: the caller launches this from the UI, and a Room
     * failure escaping into `viewModelScope` is an uncaught exception, which is process death.
     */
    suspend fun forget(packageName: String, className: String): Result<Unit> =
        runCatching { overrides.forget(packageName, className) }

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
