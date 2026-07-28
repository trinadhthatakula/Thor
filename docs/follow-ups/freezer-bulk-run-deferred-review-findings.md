# Follow-up: the bulk-freeze findings deferred out of PR #284

**Status:** Deferred — four independent items, none blocking.
**Severity:** Minor throughout (one wrong-paint, one cosmetic staleness, one reporting gap, one
unmeasured cold-start cost). The Important findings from the same review were fixed on the branch.
**Effort:** small (1), small (2), medium (3, drags i18n), medium (4, needs measurement first).
**Raised by:** the adversarial code review of PR #284 (`fix/freezer-tile-rework`, 2026-07-28) —
18 subagents over two workflows, 10 confirmed findings. See the PR comment for the full list and
for the four findings that were refuted.

Files: `app/src/main/java/com/valhalla/thor/data/freezer/BulkFreezeRunner.kt`,
`app/src/main/java/com/valhalla/thor/data/freezer/BulkResultNotifier.kt`,
`app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt`,
`app/src/main/java/com/valhalla/thor/data/launcher/FreezerShortcutManager.kt`,
`app/src/main/java/com/valhalla/thor/ThorApplication.kt`

---

## 1. `BulkFreezeRunner`'s published state is op-agnostic

`_freezableCount` and `_isRunning` describe "the last run", not "the last run of op X". Two
consequences:

- The `finally` sweep calls `refreshCandidates(op)` with the op that just ran
  (`BulkFreezeRunner.kt`, post-run sweep). After an **UNFREEZE** — which only the launcher shortcut
  issues — `_freezableCount` therefore holds the number of *unfreeze* candidates. The tile is
  freeze-only, so it then paints a count that answers a question nobody asked: `NOTHING_TO_FREEZE`
  when apps are freezable, or `READY` with a wrong number.
- `_isRunning` is likewise shared, so an unfreeze shortcut running in the background paints the
  freeze tile as `WORKING`.

**Impact is display-only, and that was verified rather than assumed.** `NOTHING_TO_FREEZE` maps to
`Tile.STATE_INACTIVE`, which still delivers `onClick` (only `STATE_UNAVAILABLE` is swallowed by
AOSP's `CustomTile.handleClick()`), and `run()` recomputes its own candidate list at tap time. So
the tile can look wrong but cannot *act* wrong.

This is the item recorded during the branch's own review as **B-9**; deferring it again is
consistent with that decision rather than a new one.

**Sketch.** The narrow fix is to always sweep for `BulkOp.FREEZE` in the `finally`, since that is
the only op the tile displays — one line, but it encodes "the tile is freeze-only" in the runner,
which is exactly the coupling `BulkFreezeRunner` exists to avoid. The honest fix is to key the
published state by op (`Map<BulkOp, Int?>`, or a small `RunState` per op) and have the tile observe
the FREEZE slot. Do it together with item 2 — both are "the runner publishes one global thing where
it should publish per-op things".

---

## 2. `lastResult` has no owner, no expiry, and no coverage of non-runner unfreezes

Three related edges on the same field:

- **No expiry.** `_lastResult` lives for the process lifetime. A result published by a launcher
  shortcut at 09:00 is still in the field at 17:00 and gets rendered into the tile subtitle the next
  time the shade opens, presented as if it had just happened.
- **Cleared only by two paths.** `consumeResult` (the tile, after displaying it) and a runner
  `UNFREEZE`. Every *other* unfreeze — `SettingsViewModel`, `FreezerViewModel`, `AppListViewModel`,
  `MainViewModel`, and the extension providers — leaves an old "Froze N apps" armed while having
  just unfrozen those very apps. Cosmetic, lasts exactly one shade-open window, and self-heals on
  the next sweep, but it is a message that is briefly false.
- The commit that clears on runner-unfreeze (`5f1bc052`) fixed one instance of this; it did not and
  could not fix the paths that never go through the runner.

**Sketch.** Two candidate fixes, and the second is the one worth doing:

1. Stamp the result and ignore it past some TTL. Needs a clock injected to stay testable, and picks
   an arbitrary number.
2. Route *every* bulk unfreeze through `BulkFreezeRunner`, so there is one writer. That is the
   architectural direction the PR already started (`BulkFreezeRunner` as "the single owner of bulk
   freeze/unfreeze") and it makes both this and item 1 fall out. It is a bigger change than it
   sounds: the ViewModels unfreeze single apps and small selections, not watchlists.

Prefer 2, but only once the runner has the tests that
[`bulk-freeze-runner-concurrency-tests.md`](bulk-freeze-runner-concurrency-tests.md) describes —
changing who writes shared mutable state in an untested concurrent class is how the two defects that
follow-up documents got in.

---

## 3. Bulk-result reporting has gaps the user cannot see or fix

Two halves of one problem:

- **Unfreeze has no unconditional surface.** `BulkResultNotifier.post` returns early when
  notifications are disabled, and the runner deliberately does not park UNFREEZE results in
  `lastResult` (a process-lifetime result would surface in the freeze tile hours later). So an
  Unfreeze-all shortcut run with notifications off reports **nothing, anywhere**. This is now
  documented honestly in the `BulkResultNotifier` KDoc rather than claimed away, but documenting it
  is not fixing it.
- **Below API 33 there is no in-app way to notice.** `SettingsScreen`'s notification row is gated on
  `SDK_INT >= TIRAMISU`, because `POST_NOTIFICATIONS` is a 33+ runtime permission. But
  `areNotificationsEnabled()` — the thing `BulkResultNotifier` actually checks — is meaningful and
  user-toggleable all the way down to minSdk 28, as that class's own comment states. A user on 28–32
  who muted Thor in system settings gets silent drops, with no row explaining it and no deep link to
  the setting.

**Sketch.** For the sub-33 half: drop the SDK gate and show the row whenever
`areNotificationsEnabled()` is false, with the 33+ path requesting the permission and the 28–32 path
deep-linking to `Settings.ACTION_APP_NOTIFICATION_SETTINGS`. Cheap in logic, but it needs new
strings and therefore ar/es/fr/zh translations, which is the actual cost.

For the unfreeze half: `FreezerLaunchActivity` issues the shortcut and *is* an Activity, so unlike a
`TileService` it can legitimately show a Toast or a small confirmation. That is the natural surface
and it is unconditional. (Do not reach for a Toast anywhere in the tile path — see the tile rework
notes for why one can never render from a `TileService`.)

---

## 4. `PrivilegeManager` is now pulled into the eager cold-start graph

`FreezerShortcutManager` gained a `BulkFreezeRunner` dependency, which depends on
`PrivilegeManager`. `FreezerShortcutManager` is reached eagerly at startup:

```
ThorApplication → AutoFreezeManager → FreezerShortcutManager → BulkFreezeRunner → PrivilegeManager
```

So `PrivilegeManager` is now constructed during application `onCreate` for every launch, whether or
not anything needs a privilege probe yet.

**Explicitly not a confirmed regression.** Nobody measured it. `PrivilegeManager`'s constructor may
be trivial and the probe is already asynchronous, in which case the edge costs a Koin resolution and
nothing else. Filing it as a follow-up means "measure this", not "fix this".

**Sketch.** Trace a cold start (`adb shell am start -W`, or a Perfetto capture) on a low-end device
before and after the branch. If and only if there is a real delta, inject `Lazy<BulkFreezeRunner>`
into `FreezerShortcutManager` — the runner is only needed when a shortcut is actually invoked, so
the seam is natural. Do not restructure DI on the strength of a call graph alone; an unmeasured perf
fix is how cold starts get slower.
