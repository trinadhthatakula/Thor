# Follow-up: a restored "already prompted" flag suppresses the watchlist recovery prompt

**Status:** OPEN, unfixed. Surfaced while device-verifying follow-up #20 (backup rules).
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

1. **Exclude the flag from backup.** Cannot be done with a ruleset — the allowlist works on whole
   files and this key shares `thor_preferences.preferences_pb` with the 22 preferences that *should*
   restore. Would need its own DataStore. Listed so it is not re-proposed.
2. **Clear it on restore.** Implement `BackupAgent.onRestoreFinished()` and reset the key there. This
   is the framework's own hook for "this value doesn't survive the trip", and it is the narrowest
   fix. Costs a custom `BackupAgent` where Thor currently has none, which is a real increment.
3. **Derive the condition instead of storing it.** Drop the flag from the gate and prompt whenever
   `disabledAppsNotInFreezer` is non-empty *and* the watchlist is empty — i.e. ask the question the
   flag is a stale proxy for. Needs a per-session suppression so a user who declines is not re-asked
   on every visit (`hasCheckedAutoPrompt` already does that job within a composition).
4. **Do nothing, and say so.** Defensible: the prompt is a convenience, the manual add path works,
   and the damage is one missed offer. If this is the answer, delete this doc rather than leaving it
   open.

Option 3 is the cheapest and removes the whole class — a flag that outlives the thing it describes
cannot go stale if it does not exist. Option 2 is more faithful to intent but buys a `BackupAgent`.

## Acceptance

- Freeze some apps → `bmgr backupnow` → `adb uninstall` → `adb install -t -r` → open the Freezer tab.
  The import prompt appears, listing the still-frozen apps.
- Declining the prompt still suppresses it for the rest of the session (whatever mechanism is chosen).
- **`pm clear` + relaunch is not a valid test** — Android Auto Backup restores only at package
  *install* time, so a clear-and-relaunch never restores the flag and the bug will not reproduce.
