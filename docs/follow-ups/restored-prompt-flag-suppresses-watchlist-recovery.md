# Follow-up: a restored "already prompted" flag suppresses the watchlist recovery prompt

**Status:** **FIXED.** The flag moved to a second DataStore that the backup allowlist does not name,
so it can no longer arrive on an install that did not produce it. Option 1 — which this document had
written off as needing more than a ruleset, and which turned out to need no ruleset change at all.
See [Resolution](#resolution).
**Severity:** Minor. Nothing is lost that was not already lost, and the user can still add apps to the
watchlist by hand — but the one affordance built to recover from exactly this situation is silently
switched off, and the user is never told.
**Effort:** small.
**Raised by:** the #20 backup-rules device pass (2026-07-30).

Files: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt:116-117`,
`app/src/main/java/com/valhalla/thor/data/repository/PreferenceRepositoryImpl.kt:66`,
`app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`

## Problem

The backup ruleset deliberately splits Thor's state in two:

| State | Backed up? | Why |
|---|---|---|
| `datastore/thor_preferences.preferences_pb` | **yes** | the settings the user chose — #20's whole brief |
| `databases/thor_database*` (incl. the freezer watchlist) | **no** | reconstructible cache + the 25 MB cloud quota, which fails the *entire* backup silently when overrun |

`has_shown_disabled_apps_prompt` (`PreferenceRepositoryImpl.kt:66`) is on the wrong side of that
split. It is not a setting the user chose; it is a **fact about the watchlist** — "we have already
offered to import the disabled apps we found". It restores. The watchlist it describes does not.

`FreezerScreen` gates the recovery prompt on it:

```kotlin
LaunchedEffect(state.isLoading, state.hasShownDisabledAppsPrompt, disabledAppsNotInFreezer) {
    if (!state.isLoading && !state.hasShownDisabledAppsPrompt && !hasCheckedAutoPrompt && disabledAppsNotInFreezer.isNotEmpty()) {
```

So after a restore the flag says "already asked", the watchlist is empty, and the prompt — *the*
mechanism for rebuilding a watchlist from what is actually frozen on the device — never appears.

### The scenario where it bites

Not the new-device transfer: on a fresh device nothing is frozen, so `disabledAppsNotInFreezer` is
empty and the prompt would have had nothing to offer anyway.

It bites on **reinstalling Thor onto the same device**. A `pm disable` survives Thor's uninstall — it
is system state, not Thor state — so the frozen apps are still frozen when Thor comes back. That is
precisely the moment the import prompt exists for, and it is precisely the moment the restored flag
suppresses it. The user sees a Freezer tab that looks empty and correct while N apps sit frozen and
untracked, and the only thing that would have said so has been turned off by a backup.

## Options

1. **Exclude the flag from backup.** ~~Cannot be done with a ruleset — the allowlist works on whole
   files and this key shares `thor_preferences.preferences_pb` with the 22 preferences that *should*
   restore. Would need its own DataStore. Listed so it is not re-proposed.~~ **This is what shipped.**
   "Would need its own DataStore" was correct and was priced as if it were the objection; it is not.
   See [Resolution](#resolution).
2. **Clear it on restore.** Implement `BackupAgent.onRestoreFinished()` and reset the key there. This
   is the framework's own hook for "this value doesn't survive the trip", and it is the narrowest
   fix. Costs a custom `BackupAgent` where Thor currently has none, which is a real increment.
3. **Derive the condition instead of storing it.** Drop the flag from the gate and prompt whenever
   `disabledAppsNotInFreezer` is non-empty *and* the watchlist is empty — i.e. ask the question the
   flag is a stale proxy for. Needs a suppression so a user who declines is not re-asked on every
   visit, and **`hasCheckedAutoPrompt` is not that mechanism as it stands.** Two reasons, both worth
   checking before assuming it can be reused:
   - It is `rememberSaveable` owned by `FreezerScreen` (`FreezerScreen.kt:101`), so its lifetime is
     the screen's saved state, not the session. It survives a rotation and system-initiated process
     death, and does **not** survive a cold launch from the launcher.
   - It is set when the prompt is *shown* (`FreezerScreen.kt:117-119`), not when it is dismissed, so
     it records "we already offered this" rather than "the user said no".
   Decide the scope explicitly and say so in the acceptance below: screen-scoped is what exists,
   session-scoped wants `FreezerViewModel` state, and "never ask me again" is a preference — which is
   the flag this whole document is about, so choosing it means fixing its restore behaviour rather
   than escaping it.
4. **Do nothing, and say so.** Defensible: the prompt is a convenience, the manual add path works,
   and the damage is one missed offer. If this is the answer, delete this doc rather than leaving it
   open.

Option 3 is the cheapest and removes the whole class — a flag that outlives the thing it describes
cannot go stale if it does not exist. Option 2 is more faithful to intent but buys a `BackupAgent`.

## Resolution

**Option 1, at a price nobody had checked.**

The objection above was that the ruleset allowlists whole files, so the key cannot be excluded while
it shares a file with 22 preferences that should restore. True — and the conclusion drawn from it,
"would need its own DataStore", was treated as the reason not to. It isn't a cost. Both rulesets are
allowlists containing exactly **one** `<include>`, naming `datastore/thor_preferences.preferences_pb`
by filename rather than the `datastore/` directory, so *any* other store is already excluded. A
second one is a single `by preferencesDataStore(...)` line and no ruleset change whatsoever.

`thor_local_state` now holds `has_shown_disabled_apps_prompt`. `userPreferences` combines the two
snapshots and `toUserPreferences` takes the second as a parameter, so which file a value came out of
is a signature, not a convention. What earns a place in the new store is stated on it: a fact *about*
state that is itself excluded from backup — the Room database, principally. A user setting does not
qualify, however local it feels; settings are what the backup is for.

Options 2 and 3 are moot. Both were ways to *cope* with a value that outlives the thing it describes;
this one stops it travelling.

### The two decisions inside it

**The old value is deliberately not migrated forward.** An install that already had
`has_shown_disabled_apps_prompt=true` starts the new store empty. This is the whole fix: Thor cannot
tell a restore from an in-place upgrade without the `BackupAgent` option 2 wanted, so carrying the
value over would reproduce the bug for exactly the users it exists for. The cost is that an upgrading
user may be asked once more — but only if `disabledAppsNotInFreezer` is non-empty, i.e. only if they
genuinely have frozen apps that nothing is tracking. That is not a regression; that is the prompt
working.

**The old key is deleted rather than left.** `setHasShownDisabledAppsPrompt` removes it from the
settings store on the way past. Nothing reads it, so this is tidiness — but a live key in the
backed-up file is a loaded gun for whoever next adds a read of it, and the constant is left behind
under a `LEGACY_` name with that written on it.

### Scope of the suppression, as the options section asked

Unchanged, and deliberately: still the persisted flag, still screen-scoped `hasCheckedAutoPrompt`
underneath it. The scope question in option 3 only arose because option 3 removes the flag. Nothing
about the nag behaviour changes here — one prompt per install that has something to offer, exactly as
before. The only difference is that "per install" is now true.

Both `backup_rules.xml` and `data_extraction_rules.xml` record the new store in their "left out on
purpose" lists, because the failure mode here is someone widening the path to `datastore/` and
silently undoing it.

## Acceptance

- **DONE, in a unit test.** A `true` in the settings snapshot does not reach
  `UserPreferences.hasShownDisabledAppsPrompt` — from either file, in either direction.
  `ToUserPreferencesTest` pins that, plus the defaults and the fact that
  `has_shown_support_developer_prompt` is untouched (it describes the *user*, so restoring it is
  correct and it stays in the backed-up store).
- **OUTSTANDING (needs a device).** Freeze some apps → `bmgr backupnow` → `adb uninstall` →
  `adb install -t -r` → open the Freezer tab. The import prompt appears, listing the still-frozen
  apps. The unit test proves the value cannot be read from the wrong file; only the device proves
  the file itself is not in the backup.
- Declining the prompt still suppresses it per install, as before — see
  [Scope of the suppression](#scope-of-the-suppression-as-the-options-section-asked). Re-entering the
  Freezer tab must not re-ask.
- **`pm clear` + relaunch is not a valid test** — Android Auto Backup restores only at package
  *install* time, so a clear-and-relaunch never restores the flag and the bug will not reproduce.
