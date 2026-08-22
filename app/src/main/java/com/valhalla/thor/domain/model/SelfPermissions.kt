// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * What the running OS says about one permission **Thor's own manifest declares**.
 *
 * Three states and not two, for the same reason [installedAppsPermissionState] needs
 * [DeclaredPermission] to be nullable: "this OS has never heard of it" is an authoritative answer
 * about the device, and "the package manager would not tell us" is not an answer at all. Folding the
 * second into the first is what would make [planSelfGrant] latch a permission off forever on the
 * strength of one unlucky binder call.
 */
sealed interface SelfPermissionDeclaration {

    /**
     * `getPermissionInfo` threw `NameNotFoundException` — the definitive "not on this build".
     *
     * The common case, not an edge one. Thor's manifest declares `POST_NOTIFICATIONS`, which does not
     * exist below API 33, and `com.android.permission.GET_INSTALLED_APPS`, which exists only on the
     * Chinese-market ROMs [GET_INSTALLED_APPS_PERMISSION] documents. Thor's supported range starts at
     * API 28, so both of those are ordinary devices.
     */
    data object Undefined : SelfPermissionDeclaration

    /**
     * The question failed for some reason other than "no such permission".
     *
     * A failed question, not a verdict about the device — and treated as such: it costs this run its
     * latch rather than being remembered. Same rule, and the same reasoning, as the uncached branch
     * in `InstalledAppsPermissionChecker.declaredPermission`.
     */
    data object Unknown : SelfPermissionDeclaration

    /** The OS defines it, and described it. */
    data class Declared(val permission: DeclaredPermission) : SelfPermissionDeclaration
}

/** One permission out of Thor's own `requestedPermissions`, as the running device describes it. */
data class SelfPermission(
    val name: String,
    val declaration: SelfPermissionDeclaration,
    val isGranted: Boolean,
)

/**
 * What a privileged self-grant should do this run.
 *
 * [toGrant] is what to issue `pm grant` for. [hasUnanswered] is the reason this is a data class and
 * not a `List<String>`: a run that could not classify everything has to be repeatable, and the
 * *absence* of a permission from [toGrant] does not say which of the four reasons put it there.
 */
data class SelfGrantPlan(
    /** The permissions worth a `pm grant`, in the manifest's declaration order. */
    val toGrant: List<String>,
    /**
     * True when at least one permission came back [SelfPermissionDeclaration.Unknown].
     *
     * The caller must not latch its once-per-process guard on such a run. Without this, a package
     * manager that hiccupped on the one probe that mattered would disable the whole feature for the
     * life of the process and report success while doing it.
     */
    val hasUnanswered: Boolean,
)

/**
 * Which of Thor's own declared permissions a privileged `pm grant` could actually change.
 *
 * **The device is asked, and no name is hardcoded.** Thor's manifest is the input, so a permission
 * added to it later is covered without a second edit here — the failure mode of a hand-kept list is
 * that the list and the manifest agree on the day they are written and never again. It also means
 * the answer is honest per device rather than per build: on a Pixel this returns
 * `POST_NOTIFICATIONS` alone, on HyperOS it returns that and
 * [GET_INSTALLED_APPS_PERMISSION], and on API 28 it returns neither of them.
 *
 * The three rejections all exist because `pm grant` *fails* on them, and a privileged command issued
 * once per attempt per permission is not free — it is a round trip through the root shell or the
 * Shizuku binder, on a path that runs while the user is waiting for the app list:
 *
 * 1. **Not defined on this build** ([SelfPermissionDeclaration.Undefined]).
 *    `PackageManagerShellCommand.runGrantRevokePermission` resolves the name first and answers
 *    `Unknown permission: …`. Nothing to grant, and nothing a retry could improve.
 * 2. **Defined but not `dangerous`.** `grantRuntimePermission` throws
 *    `SecurityException("… is not a changeable permission type")` for anything that is not a runtime
 *    permission. Two of Thor's declarations are exactly this and both are already handled properly
 *    elsewhere: `PACKAGE_USAGE_STATS` is `signature|privileged|appop`, whose *app-op* half
 *    `UsageAccessManager` sets through `appops` rather than `pm`, and `REQUEST_INSTALL_PACKAGES` is an
 *    app-op the user toggles under "Install unknown apps". Sending either to `pm grant` would be a
 *    guaranteed failure wearing the shape of a real attempt.
 * 3. **Already held.** Re-granting is a no-op that still costs the round trip.
 *
 * A [SelfPermissionDeclaration.Unknown] is not a rejection: it is left out of [toGrant] *and*
 * recorded in [SelfGrantPlan.hasUnanswered], so the run can be repeated.
 *
 * Pure, because everything above is a decision and none of it is a binder call. `PackageManager` is
 * abstract and `:app` has no mocking library by policy, so a rule that lives in the data layer is a
 * rule no test can reach — the same split `installedAppsPermissionState` and [runtimeGroupFor] make.
 *
 * ⚠️ **This overrides a decision the user may have made deliberately.** A permission is in
 * [toGrant] precisely because it is ungranted, and Thor cannot tell "never asked" from "denied on
 * purpose". That is the owner's explicit call for privileged users — the point of granting Thor
 * root or Shizuku is not to keep being asked — and it is why the grant runs *only* once a privilege
 * gateway is live, and why nothing here touches another package's permissions.
 */
fun planSelfGrant(permissions: List<SelfPermission>): SelfGrantPlan {
    val toGrant = mutableListOf<String>()
    var hasUnanswered = false

    for (permission in permissions) {
        when (val declaration = permission.declaration) {
            SelfPermissionDeclaration.Unknown -> hasUnanswered = true
            SelfPermissionDeclaration.Undefined -> Unit
            is SelfPermissionDeclaration.Declared -> {
                if (declaration.permission.isDangerous && !permission.isGranted) {
                    toGrant += permission.name
                }
            }
        }
    }

    return SelfGrantPlan(toGrant = toGrant, hasUnanswered = hasUnanswered)
}
