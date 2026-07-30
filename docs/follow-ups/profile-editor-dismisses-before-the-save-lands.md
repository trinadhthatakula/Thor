# Follow-up: the freeze-profile editor closes before its save is known to have worked

**Status:** Deferred — filed against `feat/freeze-profiles` (#295) before merge.
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

## Test to write with it

`FreezerViewModel` has no test that makes the profile repository *fail*. Whichever option is taken,
pin it: a save that fails must leave the editor state — name and membership — where the user can
still see it.
