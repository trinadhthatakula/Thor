# Follow-up: the freeze-profile editor closes before its save is known to have worked

**Status:** ✅ RESOLVED 2026-08-08 (`feat/band-b-freezer`, backlog row 16) — **option 1, as
recommended**. Kept under retention exception 3: what it now records is which option was taken and
why the other two were not. Filed against `feat/freeze-profiles` (#295) before merge.
**Severity:** Minor, but it is a *draft-loss* bug: the failure path costs the user everything they
typed.
**Effort:** small to medium — the sheet is already state-driven, so this is one result plumbed back,
not a rewrite.
**Raised by:** the external review of #295 (2026-07-30), non-blocking suggestion 2.

Files: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt`,
`app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt`,
`app/src/main/java/com/valhalla/thor/presentation/freezer/FreezeProfilesSheet.kt`

## Problem

Creating or renaming a profile dispatches an asynchronous repository call and closes the editor in
the same frame. The write can still fail afterwards — a `UNIQUE` constraint on the profile name is
the ordinary case, a Room I/O error the rare one — and the failure surfaces as a toast *after* the
draft has already been discarded.

So the worst-affected user is the one who did the most work: someone who named a profile, searched,
and ticked forty apps gets a toast and an empty list, with no way to recover the selection except to
do it again. Nothing is corrupted and nothing is lost that was ever persisted; what is lost is the
part that never got that far.

## Why it was not fixed in #295

It is a UX contract, not a correctness one, and the branch's own risk was elsewhere — the bulk-run
coalescing that a profile freeze goes through. The reviewer marked it non-blocking for the same
reason. Fixing it properly means deciding what the editor does *while* the save is in flight, and
that is a design question with more than one defensible answer:

1. **Dismiss on success only.** Keep the sheet up, disable the save button while the write is in
   flight, close when the repository confirms. Honest, and it makes the failure recoverable — the
   draft is still on screen with the toast over it. Costs a visible "saving…" state on a write that
   is normally instant, which on a fast device reads as a flicker.
2. **Optimistic, with restore on failure.** Close immediately as today, and re-open the editor with
   the draft intact if the write fails. Keeps the fast path fast; the re-appearing sheet is a
   surprising thing to do to someone who has already moved on.
3. **Validate the name before dispatching.** Catches the constraint violation — the common failure —
   without any of the above, but leaves the I/O failure exactly as it is now.

(1) is the recommendation; (3) is worth doing regardless, since a name collision is a thing the
editor can know about before it asks Room.

## What shipped

**Option 1, unchanged.** The write decides: `runProfileWrite` returns whether it landed,
`saveProfile` emits `ProfileSaveSucceeded` only then, and `FreezerScreen` closes on that event and on
nothing else. A refused save leaves the sheet up with the draft intact and the Save button as the
retry — which is why the in-flight flag is cleared in `finally` rather than on success. Note that
both refusals were *already named* to the user; what was missing was anything left on screen to act
on the answer.

Two decisions the sketch did not anticipate:

- **The write stays on `viewModelScope`.** Awaiting it inside the sheet would be the obvious way to
  drive a "saving…" state, and it is wrong: the editor's `rememberCoroutineScope` dies with the
  composition, so a rotation mid-save would cancel `updateProfile`'s `@Transaction` part-way. The
  view model owns the write; the screen only observes the outcome.
- **The editor's open/closed state stays in the screen**, alongside its draft. The sheet's
  local-draft contract is what makes Cancel mean anything, and hoisting one half of it into the view
  model to hoist the dismissal would have split that.

**Option 3 was not taken and should not be revisited as stated.** Validating the name before
dispatching cannot replace this — Room still owns the unique index, and a check-then-write is a race
— so it would add a second source of truth for a failure this now handles correctly.

## Test written with it

`FreezerViewModel` had no test that made the profile repository *fail*; it does now. What is asserted
is the view model's half, because that is the half a JVM test can see: a refused save reports itself,
**never** emits `ProfileSaveSucceeded`, and clears the in-flight flag anyway. Those three together are
what keep the draft on screen — the editor's name and membership live in the screen's own state, and
the success event is the only thing that dismisses it.
