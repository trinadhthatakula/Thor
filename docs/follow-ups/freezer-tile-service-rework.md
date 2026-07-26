# Follow-up: FreezerTileService needs a rework (process-scoped work + unbounded prefix)

**Status:** Deferred — the tile service needs a rework rather than a point fix.
**Severity:** Minor–Major (bounded leak window, but it can hold a destroyed `TileService` and its
`Context` for the duration of a wedged shell call). **Effort:** medium (redesign, not an edit).
**Raised by:** an external model's code-review report (2026-07-25), finding 1.1, which flagged the
static `appScope` as an "unbounded coroutine scope" leaking the service. The leak is real but the
report's diagnosis and its proposed fix are both incomplete — see below.

File: `app/src/main/java/com/valhalla/thor/presentation/tile/FreezerTileService.kt`

## Problem

`onClick()` launches its bulk freeze on a `companion object` `appScope`
(`CoroutineScope(SupervisorJob() + Dispatchers.Main)`) that is static and **never cancelled**. That
is deliberate: collapsing the QS shade destroys the tile service, so pinning a bulk freeze to a
service-lifetime scope would leave a **partial** freeze and skip the result toast + tile refresh.
The comment in the file says as much.

The cost of that decision is that the `onClick` lambda captures `this@FreezerTileService` — through
`getString(...)`, `resources`, `applicationContext` (an instance method), the `scope != null` read,
and the `refreshTile()` call — so the destroyed service instance stays reachable from a static root
until the coroutine finishes.

That is acceptable *if* the coroutine always finishes promptly. It doesn't necessarily:

| step | bounded? |
|------|----------|
| `systemRepository.isRootAvailable()` / `isShizukuAvailable()` / `isDhizukuAvailable()` | **no** |
| `freezerRepository.getAllPackageNames()` | **no** |
| `preferenceRepository.userPreferences.first()` | **no** |
| the freeze/suspend batch (`pkgs.map { async { … } }.awaitAll()`) | yes — `withTimeoutOrNull(30_000L)` |

Only the batch is bounded. The privilege probe in particular routes into the shell, and Thor has a
**known residual hang class** there: see [`mainshell-shell-init-hard-failure.md`](mainshell-shell-init-hard-failure.md)
— a coroutine parked in a blocking `MainShell.get(...)` cannot be unblocked cooperatively. So a
wedged shell holds the destroyed service (and its `Context`) indefinitely, before the timeout that
was added for exactly this reason ever comes into scope.

`refreshTile()` has the same unbounded probe, but it runs on the service-lifetime `scope` which
`onStopListening()` cancels, so it is not part of this finding.

## Why the reported fix is not enough

The report proposes hoisting `applicationContext` out of the coroutine (capture the app context, not
the service). That removes *one* capture edge and leaves the others: `getString()`, `resources`,
`scope`, and `refreshTile()` are all instance members, so the service is still retained. It also
does nothing about the unbounded prefix, which is the part that makes the retention window
open-ended rather than "as long as a freeze takes".

## Sketch of the rework

Not a decision, just the shape the rework should take:

1. **Move the work off the service entirely.** The bulk freeze is application work, not UI work — it
   belongs behind an injected app-scoped runner (or a `Worker`/foreground service for a long batch)
   that takes only `packageNames` + `suspendMode` and holds no `TileService` reference. The tile then
   only *starts* it and returns.
2. **Resolve every string and the `Context` before launching** so the coroutine body captures no
   service members: pass the app `Context` and pre-read the plural/format strings, or emit the result
   as data and let the toast happen on the app context.
3. **Bound the whole operation, not just the batch** — wrap the privilege probe, the package read and
   the prefs read too, or (better) fix the underlying hang at the shell layer per
   [`mainshell-shell-init-hard-failure.md`](mainshell-shell-init-hard-failure.md).
4. **Keep the tile refresh guarded.** The current `if (scope != null) refreshTile()` guard is correct
   and should survive: touching `qsTile.updateTile()` after the shade collapses can throw inside the
   framework because the binder is gone. In the reworked shape the runner should publish a result
   the tile observes in `onStartListening()` instead of calling back into a possibly-dead service.

## Acceptance

- No static/process-scoped reference to a `FreezerTileService` instance survives QS shade collapse
  (verify with LeakCanary or a heap dump after tapping the tile and collapsing immediately).
- A bulk freeze started from the tile still completes and still reports its result when the shade is
  collapsed mid-run (the behavior `appScope` exists to protect).
- A wedged/unavailable shell resolves the tile tap to a bounded failure toast instead of an
  open-ended suspension.
