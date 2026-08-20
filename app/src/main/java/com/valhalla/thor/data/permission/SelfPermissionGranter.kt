// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.permission

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.valhalla.thor.domain.model.DeclaredPermission
import com.valhalla.thor.domain.model.SelfGrantPlan
import com.valhalla.thor.domain.model.SelfPermission
import com.valhalla.thor.domain.model.SelfPermissionDeclaration
import com.valhalla.thor.domain.model.planSelfGrant
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.AppScanRevision
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * Grants Thor **its own** declared runtime permissions through whichever privilege gateway is live,
 * so that a user who has already handed Thor root or Shizuku is not asked again for permissions that
 * privilege can simply take.
 *
 * The rule — which permissions, and why not the others — is [planSelfGrant]; this class only asks the
 * device the questions that rule needs and issues the commands it returns. That split is what makes
 * the decision testable on the JVM: `PackageManager` is abstract, `:app` carries no mocking library
 * by policy, and a rule written inline here would be a rule no test can reach.
 *
 * ### Scope
 *
 * **Thor's own package, and nothing else.** [SystemRepository.grantPermission] takes a package name
 * and every call below passes `context.packageName`. The general-purpose per-app grant UI is
 * `PermissionManagerScreen`, which is user-driven and untouched by this.
 *
 * ⚠️ **This is a deliberate reversal of Thor's previous policy**, which was that Thor never
 * self-grants — stated on the manifest's `POST_NOTIFICATIONS` declaration and beside the
 * `GET_INSTALLED_APPS` launcher in `AppListScreen`, both now updated to say what actually happens.
 * The owner's call, and the reasoning is that an ungranted permission cannot be told apart from a
 * refused one, so a privileged user was being asked for something they had already answered in a
 * stronger form. It follows that this must never run without a live gateway: see [startObserving].
 *
 * ### What it deliberately does not do
 *
 * Nothing here knows a permission by name, including `POST_NOTIFICATIONS`. That matters because a
 * name-specific rung is where this class would rot — Thor's manifest is the input, so a permission
 * added to it later is covered with no edit here.
 *
 * ⚠️ It follows that **granting `POST_NOTIFICATIONS` is not the same as notifications working.**
 * `NotificationManagerCompat.areNotificationsEnabled()` is also false when the user has muted Thor
 * app-wide, and below API 33 the permission does not exist at all while the mute still does — no
 * `pm` verb and no app-op reaches that state, only Settings. The screen-level request path
 * (`rememberNotificationPermissionRequest`) re-reads the real answer and deep-links when it has to,
 * which is why this class does not try to special-case it.
 *
 * Modelled on [com.valhalla.thor.data.manager.UsageAccessManager]: latch only after a run that
 * actually finished, always re-verify rather than trust the command's exit code, and never throw at
 * the caller.
 */
@Single
class SelfPermissionGranter(
    private val context: Context,
    private val packageManager: PackageManager,
    private val systemRepository: SystemRepository,
    private val privilegeStateProvider: PrivilegeStateProvider,
    // Backs a process-lifetime scope rather than one call, so this chooses where every self-grant
    // sweep lives for the life of the process — the same reason AutoFreezeManager injects its two.
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var observationJob: Job? = null

    /**
     * Latched only after a run that both attempted everything and had nothing left to attempt.
     *
     * `@Volatile` for publication, matching `UsageAccessManager.autoGrantAttempted`; the only caller
     * is the single collector below, so there is no mutual exclusion to arrange.
     */
    @Volatile
    private var completed = false

    /**
     * Start watching for a privilege gateway to become available, and self-grant when one does.
     *
     * Driven off [PrivilegeStateProvider.state] rather than a one-shot call at startup because the
     * event this reacts to is usually *later* than process start: a first-run user authorises Shizuku
     * or answers the `su` prompt while Thor is already open, and on a cold start the first probe has
     * not landed yet either. The collector stays subscribed, so a privilege gained minutes in is
     * still seen, and [maybeAutoGrant]'s latch makes every subsequent emission free.
     *
     * `hasAnyPrivilege` is the gate and it is not decoration: with no gateway, every `pm grant` below
     * fails, so running unprivileged would spend a package-manager sweep per privilege state change
     * to issue commands that cannot work.
     *
     * Called from `ThorApplication.onCreate`, beside `AutoFreezeManager.startObserving()`, for the
     * same reason that one is: a Koin `@Single` nobody resolves is never constructed, so an observer
     * that is only wired in its own initialiser never runs. Idempotent, so a second call is a no-op
     * rather than a second collector.
     */
    @Synchronized
    fun startObserving() {
        if (observationJob != null) return
        observationJob = scope.launch {
            privilegeStateProvider.state.collect { state ->
                if (state.isReady && state.hasAnyPrivilege) maybeAutoGrant()
            }
        }
    }

    /** Stop watching. Present for symmetry with [startObserving]; nothing in the app calls it yet. */
    @Synchronized
    fun stopObserving() {
        observationJob?.cancel()
        observationJob = null
    }

    /**
     * One best-effort sweep per process, latched only once there is genuinely nothing left to do.
     *
     * Three things have to be true before the latch closes: the plan had no unanswered probe, every
     * command was issued, and every issued command verified. A run that fails any of them leaves the
     * latch open so the next privilege state change tries again — a gateway that is up but not yet
     * usable (Shizuku bound, permission not yet returned) is the ordinary case here, not an
     * exceptional one.
     */
    suspend fun maybeAutoGrant() {
        if (completed) return
        val outcome = tryGrantViaPrivilege()
        if (outcome.isComplete) completed = true
    }

    /**
     * Ask the device, issue the grants [planSelfGrant] returns, and verify each one.
     *
     * Verification is a re-read and not the command's exit code, because the two disagree in both
     * directions: `pm grant` exits 0 on some ROMs while the permission stays ungranted, and a
     * gateway can report failure for a grant that landed. `checkSelfPermission` asks the same
     * authority the rest of the app asks.
     *
     * Never throws. A failure here means a permission the user can still grant by hand, which is a
     * strictly better outcome than taking down whatever called this.
     */
    suspend fun tryGrantViaPrivilege(): SelfGrantOutcome {
        val plan = runCatching { plan() }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            Logger.e("SelfPermissions", "Could not read Thor's own permissions", throwable)
            // Not a verdict about anything — the sweep never happened, so it must be repeatable.
            return SelfGrantOutcome(granted = emptyList(), refused = emptyList(), isComplete = false)
        }

        if (plan.toGrant.isEmpty()) {
            return SelfGrantOutcome(
                granted = emptyList(),
                refused = emptyList(),
                isComplete = !plan.hasUnanswered,
            )
        }

        val granted = mutableListOf<String>()
        val refused = mutableListOf<String>()
        for (permission in plan.toGrant) {
            val result = systemRepository.grantPermission(context.packageName, permission)
            if (isHeld(permission)) {
                granted += permission
            } else {
                refused += permission
                Logger.w(
                    "SelfPermissions",
                    "Self-grant of $permission did not take" +
                            (result.exceptionOrNull()?.let { ": ${it.message}" } ?: "")
                )
            }
        }

        if (granted.isNotEmpty()) {
            Logger.d("SelfPermissions", "Self-granted ${granted.size}: ${granted.joinToString()}")
            AppScanRevision.bump()
        }

        return SelfGrantOutcome(
            granted = granted,
            refused = refused,
            isComplete = refused.isEmpty() && !plan.hasUnanswered,
        )
    }

    /**
     * Thor's own declared permissions, each folded into what [planSelfGrant] needs to classify it.
     *
     * `requestedPermissions` is the manifest's list **as this build of Android parsed it**, which is
     * doing more work than it looks: a `uses-permission` carrying a `maxSdkVersion` the running OS
     * has passed is dropped at parse time and never appears here, so Thor's API-28-only
     * `WRITE_EXTERNAL_STORAGE` needs no special case — on API 29+ there is nothing to skip.
     */
    @Suppress("DEPRECATION")
    private fun plan(): SelfGrantPlan {
        val flags = PackageManager.GET_PERMISSIONS
        val packageInfo: PackageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                packageManager.getPackageInfo(context.packageName, flags)
            }

        val declared = packageInfo.requestedPermissions ?: emptyArray()
        return planSelfGrant(
            declared.map { name ->
                SelfPermission(
                    name = name,
                    declaration = declarationOf(name),
                    isGranted = isHeld(name),
                )
            }
        )
    }

    /**
     * What the running OS says about [name].
     *
     * Deliberately uncached, unlike `InstalledAppsPermissionChecker`, which caches the same lookup.
     * That class answers on the app-list scan path and is asked constantly; this one runs a handful
     * of times per process at most, so a cache would buy nothing and would have to reproduce that
     * class's careful rule about which answers may be remembered.
     */
    @Suppress("DEPRECATION")
    private fun declarationOf(name: String): SelfPermissionDeclaration {
        val info = try {
            packageManager.getPermissionInfo(name, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return SelfPermissionDeclaration.Undefined
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            // A failed question rather than an answer about the device; see the KDoc on
            // SelfPermissionDeclaration.Unknown for what that costs and why it is worth it.
            Logger.w("SelfPermissions", "Could not classify $name: ${throwable.message}")
            return SelfPermissionDeclaration.Unknown
        }
        return SelfPermissionDeclaration.Declared(
            DeclaredPermission(
                isDangerous = (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
                        PermissionInfo.PROTECTION_DANGEROUS,
                group = info.group,
            )
        )
    }

    private fun isHeld(name: String): Boolean =
        context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
}

/**
 * What one self-grant sweep achieved.
 *
 * [isComplete] is **not** `refused.isEmpty()`. A sweep that granted nothing because a probe failed
 * has an empty [refused] too, and treating that as done is what would latch the feature off for the
 * life of the process; the flag is computed where both facts are in hand.
 */
data class SelfGrantOutcome(
    val granted: List<String>,
    val refused: List<String>,
    val isComplete: Boolean,
)
