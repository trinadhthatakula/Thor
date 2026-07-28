# Follow-up: root availability is cached for the process lifetime

**Status:** Deferred — narrow, and the fix belongs in Odin rather than Thor.
**Severity:** Minor (stale privilege state after a revocation, until restart).
**Effort:** small in Thor (add a re-probe), medium in Odin (invalidate the cache).
**Raised by:** assessment during the FreezerTileService rework (2026-07-28).

## Problem

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

## Sketch

Not a decision, just the shape:

1. In Odin, invalidate the cached shell when a privileged command fails with a
   permission-denied exit, or expose an explicit `Shell.invalidate()`.
2. In Thor, call it from `PrivilegeManager.refresh()` so the existing refresh path becomes
   genuinely re-probing.

Deferring is reasonable: revoking root mid-session is rare, and the failure mode is a
privileged action that fails with a clear error rather than silent corruption.
