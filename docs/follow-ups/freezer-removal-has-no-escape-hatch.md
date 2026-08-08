# Follow-up: a watchlist row you cannot remove because the app will not thaw

**Status:** PARTIALLY RESOLVED — **option 3 shipped 2026-08-08** (`feat/band-b-freezer`, backlog row
15). Options 1, 2 and 4 are still open, and the product decision between them is still not taken.
**Severity:** Minor, and strictly better than what shipped before. Nothing is lost; a row the user
asked to delete stays.
**Effort:** small once the semantics are chosen.
**Raised by:** the GH#310 fix itself. Closing the silent-failure hole opens this one by construction.

Files:
`app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt (removeFromFreezer, toggleManaged)`,
`app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt:384 (the Remove action)`

## What GH#310 changed

Before: removing an app from the Freezer deleted the Room row **first**, then attempted the restore
and discarded the `Result`. A failed restore left the app frozen with no watchlist entry — invisible
to the Freezer screen and unreachable by Unfreeze-all. The user was told it worked.

After: the restore runs first and the row is deleted **only** if it succeeded. The invariant is now
explicit — **removing from the watchlist always restores** — and a failure is reported with the
gateway's own message.

That is the right trade. Keeping a row the user can act on again beats orphaning an app they can no
longer find. But it means a *persistently* unrestorable app is a row the user cannot get rid of.

## When that actually happens

The restore has to keep failing, not just fail once. Real causes, in rough order of likelihood:

- **Cross-privilege suspend ownership** — the app was suspended under a different privilege mode and
  the current one is not allowed to lift it. Post-PR#330 Thor detects this and names the owner
  (`suspend_owned_by_other_privilege`), so the message tells the user to switch modes. Genuinely
  recoverable, and the row *should* stay.
  See [cross-privilege-suspend-ownership.md](cross-privilege-suspend-ownership.md).
- **The privilege backend is gone** — root revoked, Shizuku not running. Recoverable; the row should
  stay.
- **The app was uninstalled out from under the watchlist.** Not recoverable, and the row is pure
  litter. This one has a cheap targeted answer (see option 3).
- **An OEM refusing the enabled-settings write** (HyperOS, `FreezePolicy.kt`). Not recoverable on
  that device, and the row is stuck for good.

Only the last two produce a genuinely undeletable row. The first two are the system working.

## Why no "remove anyway" button was added

Three reasons, and the third is the deciding one:

1. It reintroduces the exact state GH#310 exists to prevent — a frozen app with no watchlist entry —
   just with a confirmation in front of it. The orphan is the harm; a dialog does not make the app
   findable again.
2. The Freezer screen's import prompt (`disabledAppsNotInFreezer`, `FreezerScreen.kt:108`) can
   re-adopt a disabled orphan, but it keys on *disabled*, so it would not catch a **suspended** one.
   The escape hatch's safety net has a hole exactly where the most likely failure lands.
3. **It is a new destructive user-facing action.** That is the owner's call to make, not something to
   slip into a bug fix. Nothing forces the decision now: the failure is visible and the row is
   recoverable, which is a stable place to sit.

## Candidate resolutions

1. **Leave it.** The row stays, the message says why, the user fixes the cause or lives with it.
   Correct for the two recoverable causes, which are most of them. Costs nothing.
2. **"Remove anyway" behind a confirmation that names the consequence** — "This app will stay frozen
   and will no longer appear in the Freezer." Honest, and matches how the Apps tab already behaves
   (removing membership there never thaws). Needs the import prompt widened to suspended apps first,
   or it strands them.
3. **Auto-prune rows whose package is no longer installed.** ✅ **SHIPPED 2026-08-08.** Narrow, safe,
   and needed no new UI — an uninstalled package cannot be restored and the row has no referent.

   One thing it *did* need, which this sketch understated: **the prune cannot key off the app list.**
   By the time `FreezerViewModel` sees the union of scan and cache, an uninstalled package and one
   the OS declined to report are the same thing — a `GET_INSTALLED_APPS` collapse on a Chinese ROM
   looks exactly like a mass uninstall, and pruning on that would delete the user's entire watchlist.
   So the rule keys off the **`ScanVerdict`** the app cache already computes, which is the only value
   in the system that distinguishes *"the package is gone"* from *"the OS refused to answer"*, and it
   lives beside `scanVerdict` rather than in the view model.

   `prunableWatchlistRows` also carries **its own emptiness guard on top of `Accept`**:
   `scanVerdict`'s first rule accepts unconditionally when nothing is cached, so an empty scan
   against an empty cache arrives carrying `Accept` — a verdict about the *cache*, taken on a scan
   that saw nothing. `FreezerRepository.removeAll` deliberately has no restore step, unlike `remove`;
   its only caller has already proved the package absent, so an unfreeze could only fail. Both KDocs
   say so, because the asymmetry reads like an oversight.
4. **Offer the fix instead of the escape** — when the failure is a known-recoverable one, surface the
   action that resolves it (switch privilege mode) rather than a way to hide the row. Most work,
   best outcome, and it composes with option 1.

Option 3 is independent of the others and landed on its own, as expected. Between 1 and 2, 1 is the
status quo and forecloses nothing — it remains what ships today for a row that refuses to thaw.

## Notes

Whichever is chosen, the invariant must stay stated where the code enforces it. `removeFromFreezer`
and `toggleManaged(add = false)` both order restore-before-delete on purpose, and both carry a
comment saying so — a future refactor that "tidies" the ordering back reintroduces GH#310 **silently**,
because the privileged call reports a failure by *returning* `Result.failure`: nothing throws, no test
that ignores the return value fails, and the only visible difference is a toast.

Note the two failure modes are not the same shape, which is why both functions carry two guards. The
privileged call returns; the durable steps around it — the Room write and `ShortcutManagerCompat` —
report by **throwing**, and `:app` installs no `CoroutineExceptionHandler`, so an unguarded one takes
the process rather than the toast.
