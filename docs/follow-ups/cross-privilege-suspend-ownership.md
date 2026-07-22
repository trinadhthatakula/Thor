# Follow-up: cross-privilege suspend ownership (root ⇄ Shizuku can't cross-unsuspend)

**Status:** OPEN — to handle in a future release (post-1.93.0).
**Area:** Freezer "Suspend" mode · `SystemGateway` backends · GH#239 lineage.

## Symptom

An app **suspended under one privilege mode cannot be unsuspended under another**:

- App suspended via **Root** → cannot be unsuspended while in **Shizuku** mode.
- App suspended via **Shizuku** → cannot be unsuspended while in **Root** mode.

(Dhizuku, being the Device-Owner path, is a third distinct owner and has the same
class of problem.)

## Root cause

Android only lets the **suspending package** lift a package suspension
(`PackageManager.setPackagesSuspendedAsUser(..., callingPackage, ...)`; unsuspend
is validated against the recorded suspender). The two backends present **different
suspender identities** to the framework:

| Mode | Path | Suspender identity recorded by the OS |
|------|------|----------------------------------------|
| Root (API ≥ 29, Q+) | `ThorRootService` reflection (`setPackagesSuspendedAsUser`), trying callers `[com.valhalla.thor, com.android.shell, android]` in order and stopping at the **first that succeeds** (no shell fallback on this path — GH#239) | Normally **`com.valhalla.thor`** (tried first → OS shows "Managed by Thor"), but **`com.android.shell`** or **`android`** if the Thor-owned call is rejected |
| Root (API < 29) | shell `pm suspend` fallback (no reflection path below Q) — `RootSystemGateway.kt:312` | **`root`** — a non-existent package, which is why tapping the paused app can crash `SuspendedAppActivity` (GH#239) |
| Shizuku | primary `pm suspend --user <u>` shell, then reflection fallback | `com.android.shell` (shell-owned) — see `Shizuku.kt` `caller = if (isRoot) APPLICATION_ID else "com.android.shell"` |

Because the recorded owner varies (`com.valhalla.thor`, `com.android.shell`,
`android`, or `root` depending on the backend and API level), an unsuspend issued
by a *different* backend — or one targeting a *different* owner — is a no-op /
failure. A root-shell `pm unsuspend` also can't clear a `com.valhalla.thor`-owned
suspension, which is precisely why the root **unsuspend** path already clears every
owner it can produce: reflection (its caller loop retries `com.valhalla.thor` →
`com.android.shell` → `android`) **and** shell `pm unsuspend` (covering the
`root`/shell-owned suspensions).

## Relevant code (as of v1.93.0)

- `app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt` (`setAppSuspended`)
  — API ≥ 29: suspend via AIDL reflection only (never shell — GH#239); API < 29: `pm suspend`
    shell fallback (records owner `root`, line 312). Unsuspend always **dual-clears**: reflection
    **and** `pm unsuspend`.
- `app/src/main/java/com/valhalla/thor/rootservice/ThorRootService.kt`
  — `setAppSuspended` throws below API 29; on Q+ `callSetSuspended` tries callers
    `[thor, com.android.shell, android]` in order and stops at the first that succeeds, so the
    recorded owner is that first successful caller.
- `app/src/main/java/com/valhalla/thor/data/source/local/shizuku/Shizuku.kt:156`
  — `setAppSuspended`: shell-first (`pm suspend`), reflection fallback with caller
    `com.android.shell` (non-root).

## Candidate approaches for the fix (design in next version's brainstorm)

1. **Dual-owner unsuspend everywhere** — make the Shizuku and Dhizuku unsuspend
   paths clear *all* known suspender identities (as the root path already does),
   so unsuspend is owner-agnostic regardless of which mode suspended it.
2. **Standardize the suspender identity** across backends (e.g. always
   `com.android.shell`, or always `com.valhalla.thor` where the caller UID allows
   it) so any mode can lift any suspension. Note the OS may validate that the
   calling UID owns `callingPackage`, so a single identity may not be reachable
   from every backend.
3. **Persist the suspending mode per package** (DataStore/Room) and, on unsuspend,
   route through the same backend that suspended it (falling back to dual-clear
   if that backend is no longer available).

Recommendation to evaluate first: **(1)** — smallest, matches the pattern the root
path already established, and needs no new persisted state.

## Verification for the eventual fix

On a device: suspend an app in Root mode → switch to Shizuku mode → unsuspend →
confirm it actually unsuspends (and the reverse). Repeat for Dhizuku.

Cover each Root owner path, since the recorded suspender varies:
- **API ≥ 29, Thor-owned** (the normal case) — reflection succeeds as `com.valhalla.thor`.
- **API ≥ 29, shell/android-owned** — force the Thor caller to be rejected so the loop falls
  through to `com.android.shell` / `android`, then cross-unsuspend.
- **API < 29** — root records owner `root` via `pm suspend` (verify the GH#239
  `SuspendedAppActivity` behavior too).
