# Follow-up: the bulk-freeze findings deferred out of PR #284

**Status:** §1 ✅ RESOLVED 2026-08-08 (`feat/band-b-freezer`, backlog row 22); **§2 still open and
still unmeasured.** Two independent items, neither blocking.
**Severity:** Minor — one cosmetic staleness, one unmeasured cold-start cost. Everything else the
review raised was fixed on the branch.
**Effort:** ~~medium (1, wants the runner's concurrency tests first)~~ — it did not; see the
resolution block. Medium (2, needs measurement before any change).
**Raised by:** the adversarial code review of PR #284 (`fix/freezer-tile-rework`, 2026-07-28) —
18 subagents over two workflows, 10 confirmed findings — plus a second, independent review. See the
PR comments for the full list, for the four findings that were refuted, and for what was fixed.

Files: `app/src/main/java/com/valhalla/thor/data/freezer/BulkFreezeRunner.kt`,
`app/src/main/java/com/valhalla/thor/data/launcher/FreezerShortcutManager.kt`,
`app/src/main/java/com/valhalla/thor/ThorApplication.kt`

---

## 1. `lastResult` has no expiry, and no invalidation from non-runner unfreezes

`BulkFreezeRunner` does own the field — `_lastResult` is private, published read-only, and the only
external mutator is `consumeResult`. What it lacks is a *lifecycle policy*: nothing ages the value
out, and nothing invalidates it when the state it describes stops being true. Two edges, and why the
last fix could not close them:

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
2. Route *every* bulk unfreeze through `BulkFreezeRunner`, so the field's owner also observes every
   event that ought to invalidate it. That is the architectural direction the PR already started
   (`BulkFreezeRunner` as "the single owner of bulk freeze/unfreeze"). It is a bigger change than it
   sounds: the ViewModels unfreeze single apps and small selections, not watchlists.

Prefer 2, but only once the runner has the tests that
[`bulk-freeze-runner-concurrency-tests.md`](bulk-freeze-runner-concurrency-tests.md) describes —
widening what mutates shared state in an untested concurrent class is how the two defects that
follow-up documents got in.

### ✅ Resolved 2026-08-08 — both fixes, and the sequencing advice was wrong

**Both** were done, because they close different edges: 1 handles a result that is simply *old*, 2
handles one the runner can know has been *invalidated*. Doing only 2 leaves an untouched 09:00 freeze
in the subtitle at 17:00, which is the first bullet above.

The sketch's two objections to 1 both dissolved:

- **"Needs a clock injected to stay testable."** It does not. The expiry is a pure function —
  `freshParkedResult(parked, nowMs, ttlMs)` in `BulkFreeze.kt` — and the runner passes it
  `SystemClock.elapsedRealtime()` at the one call site. Nothing about the *rule* needs a seam, and
  a `Clock` port would have been a collaborator on a class that already has four untestable ones.
  Monotonic, not wall clock, on purpose: wall clock can be set backwards by the user or by NTP,
  which would make an hours-old report look fresh again. A negative age reads as **stale**, so the
  fail-closed direction is pinned even for a caller that passes a clock which can go backwards.
- **"Picks an arbitrary number."** `RESULT_TTL_MS = 5 * 60_000L`, and the constant's own comment says
  the number is arbitrary and that nothing depends on the value. That is the honest version of the
  objection, not a reason to skip the fix.

Two things worth keeping:

- **The expiry is read-side, and needs no timer.** Nothing sweeps the parked value on a schedule.
  `FreezerTileService.paint()` already runs synchronously at the top of every `onStartListening`, so
  every moment the staleness can be *observed* is a moment the check already executes. A timer would
  add a process-lifetime coroutine to publish a change no subscriber exists to see.
- **`consumeResult` takes the parked value, not the bare `BulkResult`.** Two runs over the same
  watchlist routinely produce an equal result ("Froze 12 apps" twice); without the stamp in the
  comparison, a surface that displayed the first would silently compare-and-set away the second —
  clearing a report nobody had seen.

For part 2, the honest scope is **one call site**: `SettingsViewModel.unfreezeAll` was the only
non-runner path that unfreezes the *entire* watchlist. Routing it there is close to a deletion —
`bulkResultMessage` already produced the same two strings the view model hand-built — and it gained
the `Semaphore(5)` bound (replacing an unbounded `awaitAll`), the 30 s deadline, the
completions-driven pinned-icon rebuild it had been requesting by hand, and the `_lastResult` clear
this finding is about. It also stopped **over-reporting**: it unfroze and counted every watchlist
row, including apps that were already running, where `targetsFor` counts only the genuinely frozen
ones. The single-app and small-selection unfreezes in `FreezerViewModel`, `AppListViewModel` and
`MainViewModel` are left alone, exactly as the sketch warned — the runner's API is list-shaped and
watchlist-or-profile scoped, and they are neither. Those paths can still leave a *briefly* stale
subtitle; the TTL is what bounds it.

**The concurrency-test prerequisite did not apply.** The advice was sound for change 2 as originally
imagined — rewriting every unfreeze path through the runner — but neither change here widens what
mutates shared state concurrently: 1 is a pure function plus a stamp on an existing single-writer
field, and 2 is a caller moving onto an existing entry point.
[`bulk-freeze-runner-concurrency-tests.md`](bulk-freeze-runner-concurrency-tests.md) is still worth
doing on its own merits.

**No `origin` field was added to `BulkRequest`**, and none should be. Its equality is the runner's
coalescing key, so widening it changes concurrency behaviour — which is the untested part. Where a
run came from is not part of its identity.

---

## 2. `PrivilegeManager` is now pulled into the eager cold-start graph

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

---

## Fixed after this file was first written, and why the reasoning changed

- **Op-agnostic `freezableCount` / `isRunning`** — fixed on the branch. `refreshCandidates(op)`
  became `refreshFreezableCount()`, which always sweeps for `FREEZE`, and `isRunning: Boolean`
  became `runningOp: BulkOp?`. The original deferral called this "display-only", which was true, but
  the fix turned out to be smaller than the write-up assumed: the field was already *named*
  `freezableCount`, so making it mean that was a correction, not new coupling.
- **No unfreeze reporting surface** — fixed on the branch. `BulkFreezeRunner.launch` now returns a
  `Deferred<BulkResult?>`, and `FreezerLaunchActivity` awaits it (bounded, 2 s) and toasts the
  outcome. Legal there and only there: it is a resumed Activity, so `checkCanEnqueueToast` does not
  drop it.
- **Notification row gated on API 33** — fixed on the branch. The deferral rationale here was simply
  wrong: it claimed the fix "needs new strings and therefore ar/es/fr/zh translations". It did not.
  `notification_access`, `notification_access_granted_subtitle`,
  `notification_access_needed_subtitle` and `appNotificationSettingsIntent()` all already existed
  and are all regime-neutral, so the change was a removed version gate and one branch in
  `onCheckedChange`, at zero i18n cost. Worth remembering as a deferral-reasoning failure: the cost
  was asserted from the shape of the change rather than checked against the strings file.
