# Follow-up: cross-privilege suspend ownership (root ⇄ Shizuku can't cross-unsuspend)

**Status:** OPEN — to handle in a future release (post-1.92.3).
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
| Root | `ThorRootService` reflection only (no shell fallback for suspend — GH#239) | `com.valhalla.thor` (Thor's own package, so the OS shows "Managed by Thor") |
| Shizuku | primary `pm suspend --user <u>` shell, then reflection fallback | `com.android.shell` (shell-owned) — see `Shizuku.kt` `caller = if (isRoot) APPLICATION_ID else "com.android.shell"` |

Because the recorded owner differs (`com.valhalla.thor` vs `com.android.shell`),
an unsuspend issued by the *other* backend targets the wrong owner and is a no-op
/ failure. A root-shell `pm unsuspend` also can't clear a `com.valhalla.thor`-owned
suspension, which is exactly why the root **unsuspend** path already clears BOTH
owners (reflection as `com.valhalla.thor` **and** shell `pm unsuspend`).

## Relevant code (as of v1.92.3)

- `app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt`
  — suspend caller = `this@ThorRootService.packageName`; unsuspend already dual-clears.
- `app/src/main/java/com/valhalla/thor/rootservice/ThorRootService.kt`
  — `callSetSuspended` tries callers `[thor, com.android.shell, android]` for suspend.
- `app/src/main/java/com/valhalla/thor/data/source/local/shizuku/Shizuku.kt:156`
  — `setAppSuspended`: shell-first, reflection caller `com.android.shell` (non-root).

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
