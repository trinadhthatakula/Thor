# Follow-up: root availability is cached for the process lifetime

**Status:** Odin 1.1.0 invalidation and Thor's typed refresh, cache coordination, root admission,
and idle lane recovery are implemented through [#535](https://github.com/trinadhthatakula/Thor/pull/535).
The approved [implementation plan and progress checklist](odin-1.1.0-implementation-plan.md)
records their focused validation and the remaining broader device acceptance.
**Historical severity:** Minor (stale privilege state after a revocation, until restart).
**Historical effort:** small in Thor (add a re-probe), medium in Odin (invalidate the cache).
**Raised by:** assessment during the FreezerTileService rework (2026-07-28).

The original problem and proposal below describe pre-1.1.0 behavior. They are retained as context,
not as current implementation instructions. See the dated adoption section and the linked plan.

## Historical problem

Odin's `MainShell.cached` (`MainShell.kt:75-79`) returns the same `ShellImpl` until its
`status < 0`, and `status` is computed **once at construction** (`ShellImpl.kt:88`/`:98`) via
an `id` → `uid=0` probe (`ShellImpl.kt:130-149`). `isRoot` is `status >= ROOT_SHELL`. So
`isRootAvailable()` answers from a snapshot taken when the shell was first built.

Consequence: if the user **revokes** root after granting it, Thor keeps reporting root as
available for the rest of the process lifetime. `PrivilegeManager.refresh()` re-runs the
probe but the probe itself is cached, so it cannot see the change.

The reverse direction — denying root at first ask — works correctly: the build falls back to
`sh`, `isRoot` is false, and privileged UI disables itself. That was verified on device
during the tile assessment, which is why the tile rework does not treat this as a blocker.

## Historical proposal

Not a decision, just the shape:

1. In Odin, invalidate the cached shell when a privileged command fails with a
   permission-denied exit, or expose an explicit `Shell.invalidate()`.
2. In Thor, call it from `PrivilegeManager.refresh()` so the existing refresh path becomes
   genuinely re-probing.

Deferring is reasonable: revoking root mid-session is rare, and the failure mode is a
privileged action that fails with a clear error rather than silent corruption.


## 2026-09-30 Odin 1.1 adoption

Odin PR [#15](https://github.com/trinadhthatakula/Odin/pull/15) provides explicit invalidation and
bounded fresh acquisition after graceful retirement. Thor's user-triggered privilege refresh now
invalidates cached Odin observations before its existing provider probes. A live Magisk test
verified that an existing shell can retain UID0 after policy denial while a fresh acquisition
returns NON_ROOT; re-grant restores ROOT. BUSY/TIMED_OUT/FAILED remain distinct Odin observations;
the legacy Boolean gateway stays a convenience view and preserves provider-selection rules.

## Current Thor coordination

`RootAvailabilityCoordinator` owns coalesced refresh attempts and admission; `PrivilegeManager`
publishes that observation with the independent Shizuku/Dhizuku observations. BUSY, TIMED_OUT,
and FAILED preserve the last confirmed ROOT/NON_ROOT acquisition result while keeping new root
admission closed. Accepted work retains its leases; a busy refresh retries once at idle. Observation
revisions invalidate capability caches; confirmed revisions retire owned ARCHIVE/SWEEP shells only
at an idle boundary.
RootService lifetime remains separate from MainShell refresh.

M1-02/M1-03 in the linked plan records the Magisk and ReSuKiSU refresh/admission evidence.
That evidence does not close the outstanding held-contention visual check or establish all
Shizuku/Dhizuku operation and lifecycle scenarios in the broader acceptance matrix.
