# Follow-up: MainShell shell-init hard failure can hang awaiting callers

**Status:** Deferred — tracked for a future version (not v1.92.2).
**Severity:** Major (but low real-world probability). **Effort:** heavy (API-level change on the Odin/`:suCore` shell).
**Raised by:** CodeRabbit on PR #263 (2026-07-21); the limitation is already documented in-code at `suCore/src/main/java/com/valhalla/superuser/internal/MainShell.kt` (`get(...)`, the `catch` block, `// See needsFollowUp`).

## Problem
`MainShell.get(executor, callback)` acquires the shell on a worker thread and, on failure, catches all exceptions to keep the worker thread alive. But `Shell.GetShellCallback.onShell(Shell)` only accepts a **non-null** `Shell`, so on a *hard* shell-init failure there is **no way to signal failure back through the callback**. Any coroutine awaiting that callback —
`getShellAwait()` (`suCore/.../ktx/ShellExtensions.kt`, via `suspendCancellableCoroutine`) → `ShellRepository.isRootGranted()` — will **suspend indefinitely** with no timeout, which can wedge the privilege probe that gates the UI.

## Why it's deferred (not a v1.92.2 blocker)
- In practice libsu falls back to a non-root `sh` shell, so the callback fires (with `isRoot = false`) in virtually all real cases; a *total* shell-init failure where even `sh` cannot start is near-impossible on Android.
- The proper fix is an **API-level change** to the vendored Odin shell (a sensitive module), not a quick edit.

## Proposed fix (for the next version)
Pick one:
1. **Failure channel** — add `onFailure(Throwable)` / `onShellDied()` to `Shell.GetShellCallback`; call it from `MainShell.get(...)`'s `catch`; have `getShellAwait()` resume its continuation exceptionally.
2. **Timeout** — wrap `getShellAwait()` (or `ShellRepository.isRootGranted()`) in a `withTimeout(...)`/`withTimeoutOrNull(...)` so a hung shell-init resolves to "no root" instead of suspending forever (mirrors the `withTimeoutOrNull` guard already used in `RootSystemGateway` for the RootService bind).

Option 2 is the lighter mitigation; Option 1 is the complete fix. Either should preserve the existing `onShell(Shell)` success path and the worker-thread-survival behavior.

## Acceptance
- A simulated hard shell-init failure resolves `isRootGranted()` to `false` (or throws) within a bounded time instead of hanging.
- Root/Shizuku/Dhizuku privilege probing still resolves correctly on rooted, non-rooted, and shell-less environments.
