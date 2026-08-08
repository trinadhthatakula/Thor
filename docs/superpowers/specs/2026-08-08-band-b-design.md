# Band B — design

**Date:** 2026-08-08
**Status:** approved, not yet implemented
**Scope:** rows 13–22 of `docs/follow-ups/README.md`, plus the unnumbered docs row at the head of
band B. Four pull requests: one against `master`, three against `dev`.

---

## 1. Problem

Band A (PR #366, merged `3c893861`) closed rows 1–12 and left band B as the next rung. The eleven
items were ranked against what users actually asked for, not against how interesting they are, but
they were ranked before anyone read the code they touch. This document is the result of reading it.

Three things changed as a result, and they are the reason this is not simply "do rows 13–22 in
order":

1. **Row 13 is a precondition, not a peer.** Rows 18 and 20 each add a new DataStore write. Until
   the write path is guarded, adding a write is adding an instance of the bug.
2. **Two rows got cheaper** because the platform moved under them (row 19) or because the work was
   already sitting in the tree (row 21).
3. **One claim in the backlog is false** and one row's headline fix is unreachable — both recorded
   in §9 so they are not re-derived later.

Band A also left two debts due on the `master` merge, and the website copy splits across both. The
part that is false *today* — the Dhizuku claim, which predates band A and is unrelated to its change
— does not need the merge at all and is pulled forward into PR0. The part that describes band A's own
change is timing-bound and stays on `dev`, in PR1, so the copy and the behaviour become true in the
same commit. §Part A sets out why.

## 2. Current state (measured 2026-08-08)

Everything below was read out of the tree at the commit this document was written against. Line
numbers are load-bearing; re-check them if this sits unimplemented for long.

**Preference writes.** `PreferenceRepositoryImpl` is 532 lines holding **29 `override suspend fun`**
— 28 writers and one reader (`getInstallerArg`). Those 28 writers make **29 `.edit { }` calls**
across two stores: `context.dataStore` (28) and `context.localState` (1, in
`setHasShownDisabledAppsPrompt`, which writes both). The count of `try` blocks in the file is
**zero**. The identifier `guardedRead` appears **3 times**. The read path is guarded and the write
path is not, in the same file, with no comment explaining the asymmetry. `presentation/` holds
**33** call sites of `set*`/`update*`/`toggle*`.

`viewModelScope` is a `SupervisorJob` on `Dispatchers.Main.immediate` with no
`CoroutineExceptionHandler`, so an `IOException` from any of those 29 edits terminates the process
from a settings tap.

**Compose.** `composeBom = "2026.06.01"`, `material3 = "1.5.0-alpha25"`.
`androidx.compose.material3.ScrollbarKt` exports a public
`Modifier.nonInteractiveScrollbar(ScrollIndicatorState, Orientation, …)`, with
`NonInteractiveScrollbarDefaults` alongside it. Verified by `javap` against the resolved
`material3.aar`, not by documentation. `:app` contains **zero** occurrences of `scrollbar` (case
insensitive) and zero drag-gesture APIs; positive control `LazyVerticalGrid` matches 4 files.

**Bulk freeze.** `BulkOp` is `{ FREEZE, UNFREEZE }` (`BulkFreeze.kt:7`). `BulkFreezeRunner.kt:489`
resolves the verb as `bulkActionFor(op, preferenceRepository.userPreferences.first().freezerMode)`
— from the **global** preference, with no per-request override. `_lastResult` is published only for
`FREEZE` at watchlist scope (`:358-359`), cleared on `UNFREEZE` (`:358`) and on cancel (`:396`), and
consumed with `compareAndSet` (`:466`). It has no expiry. The companion holds `MAX_CONCURRENT = 5`,
`DEADLINE_MS = 30_000L`, `CANCEL_GRACE_MS = 2_000L`, `SWEEP_GRACE_MS = 10_000L`.

`SettingsViewModel.unfreezeAll` does not use the runner. It fans out `forceUnfreeze` with
`async`/`awaitAll` (unbounded), calls `freezerShortcutManager.refreshPinnedShortcutIcons()` by hand
under a comment naming itself "the one bulk path that does not go through BulkFreezeRunner", and
hand-builds `unfrozen_count_success` / `tile_unfreeze_partial_failure` — the same two strings
`BulkResultText.kt:40-48` already produces.

**Risk dialog.** `AppRiskDialog` (`presentation/widgets/AppRiskDialog.kt`) is shared by every freeze
and uninstall surface. Its doc comment at `:48-53` records that a `BLOCKED` app gets no confirm
button and *"on most of these paths that missing button is the whole enforcement"* — only
`FreezerViewModel.toggleManaged` re-checks the tier; `AppListViewModel.freezeApp`,
`FreezerViewModel.freezeSingleApp` and `AppInfoDetailsViewModel.toggleFreezerState` take a package
name and freeze it. `FreezeAppUseCase` refuses `BLOCKED` as a backstop; it does **not** refuse
`EXPERT`. `:55-59` records that `AppRiskAction.Freeze` is only ever raised for system apps.

**Fix Store.** `MainViewModel`'s reinstall-all target set is

```kotlin
val targets = userApps.filter {
    it.installerPackageName != "com.android.vending" &&
            it.installerPackageName != "com.google.android.packageinstaller"
}
```

It excludes Play and Google's installer. It does **not** exclude AOSP's
`com.android.packageinstaller`, and it does **not** exclude Thor, which all three gateways refuse by
name anyway. The computed set is passed straight to `onMultiAppAction(MultiAppAction.ReInstall(…))`
with no picker and no cancel.

**Scan verdicts.** `InstalledAppsVisibility.kt:114-120` defines
`ScanVerdict = Accept | Retain(reason)`; `scanVerdict()` at `:157` decides which. This is the gate
row 15's prune hangs on.

## 3. Delivery shape

Four pull requests. PR0 targets a different branch from the rest and therefore cannot sit in the
sequence.

| | Branch → base | Contents | Precondition |
|---|---|---|---|
| **PR0** | `docs/site-copy-corrections` → **`master`** | The Dhizuku correction only (A1) | none |
| **PR1** | `fix/guarded-preference-writes` → `dev` | Row 13 + the fallback copy (A2) + the repo half of the docs row | none |
| **PR2** | `feat/band-b-freezer` → `dev` | Rows 15, 16, 17, 18, 22 | PR1 |
| **PR3** | `feat/band-b-applist` → `dev` | Rows 14, 19, 20, 21 | PR1 |

PR2 and PR3 are file-disjoint and may run concurrently once PR1 lands. Their only shared dependency
is `guardedWrite`, which PR1 introduces; rows 18 and 20 each add a preference and must not add one
before the guard exists.

**Why PR0 targets `master`.** `master` is what publishes the site. `docs/branching-and-releases.md`
says never to open a PR against it, and that rule exists to stop code and version drift — neither of
which a `web/`-only change can cause. `2-master-promote.yml` path-ignores `web/**`, `docs/**` and
`*.md`, so the merge fires no release rung and promotes no artifact. The rule's purpose is not
engaged; its letter is. Taken deliberately, once, for a live falsehood, and noted here so it is not
read as precedent.

---

## Part A — website copy that is false

Two independent corrections, both in `web/`. **They ship in different PRs, to different branches,
because they become true at different times.** The split below supersedes an earlier draft of this
section that put both in PR0.

### A1 — PR0, to `master`: the Dhizuku claim is false *now*

Six passages state that freezing a system app under Dhizuku removes it for the user
unconditionally and without `-k`, so the data is lost; three of them tell the reader to avoid
Dhizuku for preinstalled apps. The backlog named two of them; the other four were found by sweeping
`web/src/` rather than trusting the list:

| File | Shape |
|---|---|
| `features.mdx` | prose ("under Shizuku" only), a `<Callout>` — "the exception on both counts", and the recovery table — "has not been converted yet" |
| `faq.mdx` | a whole `<Callout tone="warning" title="A note on Dhizuku">` |
| `privacy.mdx` | "On the Shizuku path only", plus a `<Callout tone="warning" title="Dhizuku is the exception">` |
| `download.mdx` | "Dhizuku is the exception: … does not keep its data" |
| `index.astro` | "Shizuku mode removes it"; "the one to avoid for preinstalled apps" |
| `claims.mjs` | the **C1 rule's own rationale** asserts it — the gate meant to catch this class of error had the error written into its justification |

PR #332 made this false. On `master`, `DhizukuSystemGateway.freezeSystemApp` tries
`setAppEnabledDetailed` first, consults the same `uninstallFreezeFallbackAllowed` gate Shizuku does,
and reaches the uninstall rung only where the platform actually refused — where it calls
`freezeSystemAppForUser`, the `-k` path. Verified by reading the gateway on `origin/master`, not
inferred from the PR description.

`claims.mjs`'s `source` list for C1 also cites `DhizukuSystemGateway.setAppDisabled`, which is the
wrong symbol: the gate is consulted in `freezeSystemApp`. Corrected in the same PR.

This is live on the production site and steers users away from a privilege mode that behaves
correctly. It is true on `master` and on `dev`, so it needs no follow-up at the release merge.

**As built — a third correction, found in review of PR #368.** The replacement copy was itself wrong,
in a direction this spec did not anticipate. It said the API 37 refusal applies to *the shell user*,
which describes a shell-specific rule that does not exist, and it named only Shizuku.

`PackageManagerShellCommand.java` on android17-release requires `Binder.getCallingUid() ==
Process.ROOT_UID` before honouring `--user` on a `FLAG_SYSTEM` package — **uid 0, not "not root"**.
`DhizukuAPI.newProcess` spawns `pm` inside the device-owner app, which is neither shell nor root, so
Dhizuku hits the identical guard. Measured on an API 37 device against `com.android.egg`: `Failure
[only root can delete system app for a particular user]`, the package left `installed=true
enabled=0`, `ceDataInode` unchanged. The shipped copy now says both non-root modes get the same
refusal and names them both.

**One measured nuance was deliberately not published.** On that same device, rung 1
(`pm disable-user --user 0`) exited 0 at shell uid while being refused for the device-owner uid — so
Dhizuku reaches the fallback more readily than Shizuku does. That is one package on one build. It
would need its own measurement before it is worth a sentence, and stating it unmeasured would trade
one over-claim for another.

### A2 — PR1, to `dev`: the fallback copy describes behaviour band A removed

Six passages describe an automatic freeze → `pm uninstall -k --user N` escalation for Shizuku and
Dhizuku: `index.astro`, `faq.mdx`, `features.mdx` (twice), `download.mdx`, `privacy.mdx`. Band A's
row 1 made `FreezePolicy.uninstallFreezeFallbackAllowed` answer `false` for every privilege mode.

**A2 is timing-sensitive and A1 is not, which is why they are split.** The fallback still exists in
v1.94.0 — the version users are running — and is gone only on `dev`. An earlier draft of this spec
had PR0 rewrite these passages in the **past tense** so the wording would hold on both branches.
That is wrong: past tense would make the site claim the fallback is gone while `master` still
publishes an app that has it. A user on v1.94.0 reading "older builds used to fall back" would be
told a falsehood about their own device.

So A2 stays in the present tense and moves to **PR1, on `dev`**, where it sits unpublished until the
release merge carries `dev` to `master` — which is the same moment the app behaviour changes. The
copy and the code become true together, by construction, with no window in which either is wrong and
no follow-up edit to remember.

The replacement wording matches the string already shipped at `strings.xml:428`: on OEM builds that
refuse `pm disable-user`, Shizuku and Dhizuku cannot freeze preinstalled apps, and freezing them
needs Root.

**This also discharges the first of Band A's two debts** — the release-notes line retracting
`release-notes/v1.94.0/github.md:84` — for the `web/` half. The release-notes half remains due at
the `master` merge.

**Say nothing about a future consent path.** `docs/follow-ups/freeze-refusal-remove-for-user-consent.md`
is parked in band D with four unanswered product questions. Putting it on the website converts a
deferred decision into a commitment.

**A3 — the `#161` FAQ answer**, also PR1 for the same timing reason: the switch it describes shipped
in Band A, so it exists on `dev` and not on `master`. `faq.mdx` gains a mention of the new "show Thor
when opening any file" switch as a **last resort**, after "try a different file manager", explicitly
flagged as unconfirmed. Not as the recommended route:
`docs/follow-ups/161-apks-not-openable-from-file-managers.md:119-122` records that the shipped filter
is a reasoned guess, both acceptance criteria are unmet, and the switch makes Thor a candidate for
every untyped `file://` VIEW intent. Recommending it by default trades one bug for a worse one.

**In neither PR:** the four `docs/site-content/*.md` drafts. They are untracked local files that back
pages which have shipped. They are the owner's to delete; PR1 strikes the citations to them from
`docs/follow-ups/README.md` so no future row is sent to fix files that cannot be committed.

**As built — two additions this spec did not call for.**

*A new claims rule, C16.* A2 rewrites six passages, but nothing stops the seventh: the claim was true
for two years, it is still true in `release-notes/v1.94.0/github.md`, and anyone rewriting a freezing
page from an older draft reintroduces it. C16 forbids the withdrawn mechanic stated as current, with
the `unless` window — the match plus the 60 characters before it — sized so "earlier builds
substituted `pm uninstall -k`" passes and "Thor runs `pm uninstall -k`" fails. Two existing rules
were written against the old behaviour and moved with it: C1's rationale and C15's `source`. C15's
own **fixtures** asserted the withdrawn mechanic in prose, so both sides were rephrased — a `pass`
fixture must produce no violation from *any* rule, so C16 turned C15's `pass` red the moment it
landed. One C16 pattern was dropped before it shipped: `... for your user instead of disabling ...`
ends in "instead of", which is itself in the `unless` list, so the exemption window always contained
it and the pattern could never have fired. The fixture meta-test catches a rule with no fixtures, not
a pattern that cannot match.

*A share-target claim on the features page.* A3's sweep found `features.mdx` opening with "you can
share or open a package file", which `faq.mdx` contradicts outright — Thor declares no `ACTION_SEND`
target at all. Left alone it would have sat two clicks from the corrected #161 answer, telling the
reader to try the one route that cannot work.

**Review corrections to the above.** Four, all from checking the new copy against the manifest rather
than against the previous copy:

- *C16 pattern 2 forbade a true sentence.* It anchored on the subject (`Thor|Shizuku|…`) rather than
  on freezing, so "Thor removes it for your Android user" tripped it — which is exactly what the
  Uninstall and Debloat paths do, truthfully, via `pm uninstall --user N`. A gate that forbids a true
  claim gets suppressed rather than obeyed. Both that pattern and the switch-off-or-remove one are
  now anchored to a freezing word.
- *`features.mdx` said the installer matches on the filename extension.* Only one of its three VIEW
  filters does. The other two match on MIME alone, with no host and no path — so a file manager that
  declares a type Thor claims is matched with no filename anywhere in the URI.
- *The #161 FAQ gave the wrong reason the filter cannot be narrowed.* It claimed Android consults
  path patterns only for URIs naming a host, and that these URIs have none. Content URIs do name an
  authority; the constraint is on the **filter** — `IntentFilter.matchData` reads the path list only
  inside its `mDataAuthorities != null` branch. The honest reason is simpler and does not depend on
  the platform detail: what the switch exists to catch is a handover with no name and no type, so
  there is nothing left in it to match on.
- *`features.mdx` still attributed the removed-for-your-user state to "a freeze"* 180 lines after the
  callout saying that substitution was withdrawn. The chip behaviour is real and stays — `AppInfo`
  folds `FLAG_INSTALLED` into `enabled` — and so is the state: **Uninstall and Debloat still put a
  system app there**, through `pm uninstall --user N`, which is what those two are for. The first
  rewrite of this sentence said "nothing Thor does now puts an app in that state", which swapped one
  wrong sentence for another. Only the *freeze* rung stopped reaching it; the corrected copy names
  the two paths that still do, and keeps the older-build device as a second reason the chip stays.

---

## Part B — PR1: guard the preference write path

### B1. `guardedWrite`

A module-internal helper beside the existing `guardedRead`, wrapping every one of the 29 `.edit { }`
calls across both stores. Returns whether the write landed. `internal`, not `private`, for the same
reason `guardedRead` is — both are file-scoped extension functions, on `DataStore<Preferences>` and
`Flow<Preferences>` respectively, and the test that drives the real helper against a throwing store
lives in the same module.

**Not a `CoroutineExceptionHandler`.** One line of diff against 29, but it silences every unrelated
exception in `viewModelScope` for the life of the app, and it cannot tell an individual caller
whether *its* write landed — which is the whole requirement for the two callers below. The
file-scoped `settingsFileReplaced` latch is the same shape and has the same defect: a write failure
is per-call and per-key.

Catch `IOException` and `CorruptionException`. Let `CancellationException` propagate — structured
concurrency must keep working, and the repository already sets that precedent elsewhere in the
codebase.

**As built:** the catch is `IOException` alone.
`androidx.datastore.core.CorruptionException extends IOException` (`javap`-verified), so naming both
would have been redundant, not safer. The narrowness is the point in the other direction too:
`CancellationException` is *not* an `IOException`, so it propagates without the explicit rethrow a
`catch (Throwable)` would have needed — and a rethrow that someone later deletes is a failure mode
this shape does not have.

### B2. Which setters report their outcome

26 of the 28 keep returning `Unit` and swallow. Two return `Boolean`, because two call sites take a
second action whose correctness depends on the answer:

**`setBiometricLock`.** `SecurityViewModel.init` collects a `combine` of the preference and the
device's capability, and on the locked-out branch writes `setBiometricLock(false)` **from inside
`collect { }`**, then sends `biometric_lock_disabled_no_biometric`. Two distinct failures:

- *Unguarded (today):* the throw cancels the collector for the process lifetime. The user is
  stranded outside their own app lock with no self-heal.
- *Guarded but silent:* the write fails, the flow does not flip — the code's own comment,
  *"Writing `false` flips that flow, so this settles after one pass instead of looping"*, is what
  makes this load-bearing — and the user is told "App lock turned off" while the lock is still armed
  on disk. Thor lies about the user's security state.

So on failure `SecurityViewModel` suppresses that message and emits a different one. Being told when
Thor changes your lock state without asking is an entitlement the code already honours
(`HomeActivity.kt:174-180`); being told when it *failed* is the same entitlement.

This paragraph reasons entirely about the *auto-disarm* caller and, as written, missed the other
one — the user standing in Settings flipping the switch themselves. Both callers of a `Boolean`
setter have to consume it or the return value is decoration; see B3's review correction.

**`setLanguage`.** `SettingsViewModel.kt:224-227` runs `preferenceRepository.setLanguage(language)`
and then `localeManager.applyLocale(language)`, sequentially in one coroutine. **Today a failed
write throws and `applyLocale` never runs — the two stay consistent by accident.** Add a guard that
swallows and `applyLocale` now runs on a failed write; the platform persists the locale
independently of DataStore, so the app is visibly in the new language while DataStore holds the old
one, and the next cold start silently reverts it. `SettingsViewModel` therefore skips `applyLocale`
when the write did not land, preserving today's behaviour exactly.

`ThorApplication.kt:249` is a third caller of `setLanguage`, outside `viewModelScope`. It reconciles
at startup and has no user to tell; it logs.

### B3. What the user sees

One notice per process, through the `Channel<UiText>` that already carries
`settings_lost_using_defaults`. One new string × 5 locales.

Not a toast per failed write: on a genuinely full disk every toggle toasts, which the follow-up doc
itself calls "its own kind of broken". Not silent: the app-lock case is the one that matters and it
cannot be silent. Not a persistent banner: a new surface for a rare state.

**As built: five strings × 5 locales, not one.** The generic latch needs its own notice, and so does
each of B2's two reporting setters — the whole reason those two return `Boolean` is that they have
something specific to say, so reusing the generic string there would have discarded the distinction
the return value exists to carry. Hence `settings_not_saved`, `biometric_lock_disable_not_saved` and
`language_not_saved`.

That in turn forced the `announce` parameter on `guardedWrite`. Without it a failed write from a
reporting setter both latches the generic notice *and* returns `false` for the caller's specific one,
and the user gets two messages about one failure. The reporting setters pass `announce = false`.

**Review correction: `announce = false` plus a discarded return value is worse than the crash.**
The first cut wired `setBiometricLock`'s `Boolean` to `SecurityViewModel`'s auto-disarm and left
`SettingsViewModel.setBiometricLock` calling it for effect. So a user turning the app lock on or off
from Settings on a full disk got *nothing* — no crash, no toast, and no store-wide notice either,
because this setter suppresses it. On `dev` that same tap crashed the process, which is at least
information. Converting a loud failure into a silent one is a regression however good the guard is,
and it landed on the single preference whose interface KDoc says its failure must be heard in its own
words.

Fixed with two more strings rather than one, because the direction is the whole point: a dropped
`true` leaves the lock **off** after Thor appeared to arm it, a dropped `false` leaves it **on**.
`biometric_lock_not_saved_still_off` and `biometric_lock_not_saved_still_on` name the state the lock
is actually in. `biometric_lock_disable_not_saved` stays what it was — the auto-disarm's own message,
which has a different thing to say ("it will ask again next launch").

**Review correction: the latch has to be acknowledged.** It lives on the repository singleton; the
only collector is `SecurityViewModel`, which does not. Exit finishes the activity without ending the
process (`MainScreen` → `finish()`), so the next launch built a fresh ViewModel, collected a `true`
it had already reported, and opened on a notice about nothing. `acknowledgeSettingsWriteFailure()`
lowers it immediately after the send. It does not claim the disk recovered — the next dropped write
raises it again — which is the distinction that makes clearing safe: the latch tracks an *unreported*
failure, not a *broken store*. Config-change recreation was never affected; the ViewModel survives
that.

### B4. UI state does not roll back

A failed write leaves the switch showing the value the user chose until the preference flow re-emits
the old one, which it will on the next read. Rolling back optimistically means writing a second
piece of state that can itself disagree with the store. The notice is the correction.

### B5. Docs

`docs/follow-ups/README.md`: mark row 13 done, strike the `docs/site-content/*.md` citations, record
that PR0 (`master`) discharged the Dhizuku half of the unnumbered row and this PR discharges the
fallback half, and file the two rows this design spins out (§9).

**Review correction: the write guard needed its own test file.** The first cut asserted the guard
only through `FakePreferenceRepository`, whose `write()` is a hand re-implementation of
`guardedWrite` — so the tests proved the fake behaves like the fake. `PreferenceWriteGuardTest` now
drives the real helper against a `DataStore<Preferences>` whose `updateData` throws, mirroring
`PreferenceReadGuardTest`: failure returns `false` rather than throwing, the latch is raised,
`CorruptionException` is covered by the same catch, `announce = false` stays quiet, a non-IO failure
is rethrown untouched, `CancellationException` propagates, and the write is attempted exactly once.
That last one is the assertion that pins the no-retry decision to something other than a comment.

**As built: one gap is left open on purpose.** `SettingsViewModel` has no JVM test and this PR does
not give it one. `LocaleManager` is a concrete class taking a `Context`, and `applyLocale` calls
`context.getSystemService(Context.LOCALE_SERVICE)`, so constructing the ViewModel under
`testFossDebugUnitTest` is not possible without extracting an interface. No existing test constructs
it either — this is a real seam gap, not one this change introduced. Extracting the interface is a
refactor with its own blast radius across DI and the Settings screen, and folding it into a
crash-fix PR would make the fix hard to review and hard to revert. The `setLanguage` branch is
therefore covered by reading. Filed rather than done.

---

## Part C — PR2: the Freezer cluster

### C1. Row 15 — prune rows with no referent

Delete watchlist rows whose package is absent from a scan **that `scanVerdict` returned `Accept`
for**. Silent, one `Logger` line.

**The `Accept` gate is the entire safety property.** `Retain(reason)` means Thor did not believe the
scan — the `GET_INSTALLED_APPS` collapse on Chinese ROMs is exactly this — and pruning on a
disbelieved scan deletes the user's whole watchlist in a single pass. This must be asserted in a
test that fails if the gate is removed.

No dialog and no count: the row has no referent, no UI surface renders it, and naming packages the
user cannot act on is noise.

**Not shipping:** "Remove anyway". It re-creates on purpose the state GH#310 exists to prevent and
would make the Freezer the only one of four watchlist-removal surfaces that does not restore. Also
not shipping: classify-the-failure-and-offer-the-fix, which is the best answer but is not small and
which only one privilege mode can currently feed. Both stay filed.

Add a comment to `FreezerViewModel.removeFromFreezer` stating the restore-first invariant as
intended, so the next reader does not "fix" it.

### C2. Row 16 — the editor stops dismissing before its save lands

Save is disabled while a write is in flight; the sheet dismisses only when the repository confirms.
`FreezerViewModel` gains a `profileSaveInFlight` field on its UiState and a one-off
`ProfileSaveSucceeded` event; `FreezerScreen` closes the editor on that event. The editor's
open/closed state stays in the screen, preserving the sheet's documented local-draft contract
(`FreezeProfileEditorSheet.kt:75-77`).

**Not a `suspend onSave` awaited inside the sheet.** `rememberCoroutineScope()`
(`FreezeProfileEditorSheet.kt:129`) is cancelled when the composition is disposed on rotation, which
would cancel `FreezeProfileDao.updateProfile`'s `@Transaction` mid-write. `viewModelScope` owns the
write.

**Not moving the three `rememberSaveable`s into UiState.** It buys JVM test coverage of the
dismiss-vs-save ordering, but they would then need `SavedStateHandle` to keep surviving process
death — a larger change than the bug.

On failure the sheet stays up with the draft intact and the existing toast floats over it. No Retry
action: there is no `SnackbarHost` anywhere in `:app` (a repo-wide search hits only a doc comment in
`ObserveAsEvents.kt:28`), every one-off is a `Toast`, and a Toast cannot carry an action. Introducing
Snackbar as an app-wide pattern for one rare path is not justified — and once the sheet stays up,
"retry" is the Save button the user is already looking at.

### C3. Row 17 — per-group kill and suspend, per-tap

No schema change, no persisted verb. The profile entity is verb-agnostic
(`FreezeProfile.kt:15-21`) and nothing else wants to know a profile's verb — the launcher restore
gate, `FreezerBridgeProvider` and the runner's `targetsFor` all key on membership alone. Persisting
one buys a single saved tap and costs a Room 6→7 migration the debug build cannot exercise, a
5-locale string set for the picker, and a merge conflict with C2 in the same sheet.

**Suspend** needs no new `BulkOp`. It is already `bulkActionFor`'s second branch; the change is
letting a request carry an optional `FreezerMode` override, falling back to the global preference
(`BulkFreezeRunner.kt:489`) when absent.

**Kill** goes through `MultiAppAction.Kill`, which `FreezerScreen` already forwards to
`MainViewModel` via `pendingMultiAction`, and which already has a confirm dialog and a progress
logger. Putting it in `BulkOp` forces an answer to "does a kill cancel an in-flight freeze?" for a
verb where the honest answer is that they are orthogonal.

**Target set:** installed **and non-frozen** members. `AppFreezeStateReader` already separates
`FROZEN`/`ACTIVE`/`ABSENT` from one binder read, so the filter is free, and it makes the reported
count defensible — a frozen app is not running by construction.

### C4. Row 18 — optional per-freeze confirmation

A Settings → Freezer switch. Two strings. Suppresses only the confirmation shown when freezing a
**system app at `NORMAL` tier**.

**The suppression decides whether the dialog is raised. It never lives inside the dialog.**
`AppRiskDialog.kt:48-53` records that for `BLOCKED` the missing confirm button *is* the enforcement
on three of the four single-app freeze paths. Auto-confirming from inside the composable turns a
renderer into an actor and puts every future predicate bug directly onto that path.

One shared, JVM-unit-tested helper beside the dialog:

```
suppressible = isSystem && !isUninstall && tier == NORMAL
```

called by every call site. Four copies of a gate is precisely the drift `AppRiskDialog` was created
to end (`:42-46`).

**`EXPERT` is never suppressible.** It carries a real per-app UAD verdict — "freezing it breaks
important functionality" — and unlike `BLOCKED` it has **no backstop** below the composable;
`FreezeAppUseCase` refuses `BLOCKED` only. Suppressing `EXPERT` is the one option here that can
silently break a device.

**Out of scope, filed separately:** the post-freeze "Frozen — Add to Freezer?" snackbar keeps
appearing. It is an offer, not a warning, it auto-dismisses in 4 s, and it is the only route by
which a one-off freeze becomes a tracked one. Folding an offer under a warning-suppression flag
makes the setting's meaning unstateable in one sentence.

**Also filed, not fixed here:** the same bulk-freeze gesture confirms on the Freezer tab
(`MainScreen.kt:488`) and does not on the Apps tab (`AppListScreen.kt:311`). Real, and exactly the
kind of surface-dependent answer this codebase has repeatedly paid for — but adding a prompt inside
a row about removing one makes the change impossible to describe in a release note.

### C5. Row 22 — `lastResult` expiry, and one routing change

`RESULT_TTL_MS = 5 * 60_000L` in the runner's companion beside the other four constants. The value
is arbitrary and the doc says so; 5 minutes is long enough that a user who taps the tile, collapses
the shade and reopens it still gets their report, and short enough that no plausible session shows
an hour-old message.

**No origin field on `BulkRequest`.** Its equality is the coalescing key (`BulkFreeze.kt:28`), so
widening it changes concurrency behaviour — the thing that is untested.

Then route `SettingsViewModel.unfreezeAll` through the runner. It is the only non-runner path that
unfreezes the *entire* watchlist, i.e. the only one that fully falsifies a parked "Froze N apps".
This is close to a deletion: `BulkResultText.kt:40-48` already produces the same two strings the
ViewModel hand-builds at `:269-278`, and routing gains the `Semaphore(5)` bound (replacing an
unbounded `awaitAll`), the 30 s deadline, the completions-driven icon rebuild it currently requests
by hand, and the `_lastResult` clear — the runner already nulls it on `UNFREEZE` at `:358`.

Single-app and small-selection unfreezes are left alone: the runner's API is list-shaped and they
are not.

### C6. The tension between C3 and C5

C5 refuses to add a field to `BulkRequest` because its equality is the coalescing key. C3 adds one.

That is deliberate, on a principle: **the verb is part of the operation's identity — a suspend-run
and a freeze-run of the same profile genuinely should not coalesce — whereas where a request came
from is not.** Including the mode is a correctness improvement to the key; including origin would be
a corruption of it.

It remains a concurrency change in code with no JVM test, because `BulkFreezeRunner` cannot be
constructed in one (four final collaborators over `Context`/`PackageManager`, see
`BulkFreezeWorkerTest`). Both the TTL and the coalescing rule are therefore tested as **pure
functions**, the way `bulkActionFor` and the reuse rule already are.

---

## Part D — PR3: the Apps-tab cluster

### D1. Row 14 — Fix Store: selection, cancel, plainer copy

**The reinstall stays.** Switching to `pm set-installer` — the command Thor's own Auto Reinstall
already uses at `AutoReinstallReceiver.kt:59` — would make the operation near-instant, remove the
debuggable-app failure mode and drop the signature risk the confirm dialog warns about. It is also a
behaviour change to a shipped feature whose semantics the website documents verbatim
(`features.mdx:183-186`), and it cannot be evaluated without a device. Filed as its own row with the
probe commands (§9).

Ships:

- **A picker**, everything pre-checked, showing each app's current installer, with the count in the
  confirm button label. The accident being fixed is "I did not know what it would touch", not "I did
  not mean to tap Confirm" — showing the list is the fix; forcing 40 taps punishes the correct use
  case.
- **Cancel** during the run.
- **Plainer copy** on the confirm dialog.
- **Two target-set bugs.** Also exclude AOSP's `com.android.packageinstaller` — not a judgement
  call, since `AppListViewModel.kt:820-822` already classifies it as Sideloaded alongside the Google
  one and the predicate simply forgot it — and exclude Thor itself, which all three gateways refuse
  by name.

**No undo.** Nothing in Thor or in Android retains the previous installer after the rewrite: there
is no event table (`AppDatabase.kt:13-31`) and `AppEntity`'s column is REPLACEd on the next scan. An
undo means new persistent state, i.e. a Room migration or a DataStore blob. Prevention is what the
reporter needed.

**Dhizuku is unresolved.** There is evidence its shell cannot reach `PackageManagerService` with
`INSTALL_PACKAGES`, and `MultiSelectToolBox.kt:78` currently enables Reinstall for Dhizuku
explicitly. If confirmed dead, the action should be hidden in that mode and
`web/src/pages/features.mdx:111` corrected — a picker that lets a Dhizuku user tick 40 apps and then
fails 40 times is worse than an absent button. Filed with a probe (§9), not guessed at.

### D2. Row 19 — non-interactive scrollbar

`Modifier.nonInteractiveScrollbar` on the Apps tab (`AppList.kt`) and the Freezer main list
(`FreezerScreen.kt:349/:374`). Roughly two lines each: no gesture code, no new strings, no
accessibility surface.

**Not the two bottom sheets** (`ManageFreezerSheet.kt:158`, `FreezeProfileEditorSheet.kt:236`): they
are search-first, sized to a sheet, and a scrubber inside a `ModalBottomSheet` fights the sheet's own
drag-to-dismiss.

**The interactive scrubber is deferred, not cancelled.** The indicator is most of the perceived win
and makes the scrubber a purely additive layer afterwards. When it is built, the sort-aware bubble
should show **real buckets** for name, the two date modes and the two SDK modes, and a **raw field
readout** ("12.4.1", "128 MB") for size and the two version modes — `VERSION_NAME` sorts as a raw
String, so `"10.0" < "9.0"` and any bucketing of it is nonsense.

**A-Z buckets are folded out of the list's actual order**, not out of a human alphabet. Switching
`sortApps`'s NAME comparator to a locale `Collator` would silently change app-list ordering for
every existing user in every locale; that deserves its own row and its own release note, and must
not ride in on a scrollbar.

⚠️ `material3 1.5.0-alpha25` is an alpha. Confirm the opt-in annotation at implementation time and
expect the signature to move between alphas.

### D3. Row 20 — export the app list

- **What:** exactly what is on screen — `displayedApps` verbatim, honouring tab, search, filter and
  sort. WYSIWYG is the least surprising rule and it is free (`AppListViewModel.kt:83` already holds
  the answer). `is_system` is still emitted as a column so a user who wants everything runs it twice.
- **Where from:** the app-list control bar / `AppFilterSheet` (`AppList.kt:279`). "Export the list I
  am looking at" is a view-level action, and it keeps a read-only export out of the destructive
  multi-select toolbox.
- **Where to:** the existing export target — remembered SAF tree, else `Downloads/Thor` — via
  `openSession` + `writeStaged`, plus a Share button on the success path (`shareUri`,
  `AppBundleFileStoreImpl.kt:87`).
- **No install-size column.** It is the difference between a millisecond operation and one needing
  progress and cancellation, and it is the single decision that determines whether this row needs a
  runner at all.
- **CSV fields are neutered against formula injection**: prefix `'` when a field starts with `=`,
  `+`, `-`, `@`, TAB or CR, on top of RFC 4180 quoting. App labels are third-party-controlled
  (`AppInfoMapper.kt:60`) and a debloater's users are precisely the group installing unknown APKs.
  The corruption this introduces is rare and *visible*; the failure it prevents is invisible.

### D4. Row 21 — #130, friendly installer label **and** the chart drill-down

Both halves. The drill-down is far cheaper than the roadmap implies: `FilterType.Source`, the chip
row, the persisted selection and the Home→Apps switch all exist, so part 2 is a click handler on the
legend row calling `updateAppFilter(FilterType.Source, pkg)` and setting `activeDestination = APPS`.
Doing the label alone means a second trip into the same 40 lines of `AppDistributionChart` and leaves
a public promise made twice — February and 2026-07-31 — outstanding.

**Name resolution order: curated → `PackageManager` label → raw package id.** The curated tier is not
redundancy: the platform label for `com.google.android.packageinstaller` is "Package installer",
which is accurate and useless, and Thor already ships "Sideloaded" as the editorial answer in five
languages. Keep the curated tier at exactly the entries that already exist — do **not** grow it into
a registry of stores — and let `PackageManager` answer everything else, which is what makes
InstallWithOptions, Aurora, Obtainium and the next one work with no code change.

**The chart stays at top-3 + OTHERS.** A 6-entry legend at 2 per row is three rows of half-width
uppercase ellipsised text; the source-filtered app list reached through the drill-down is the better
full view.

**On the Installer InfoCard the friendly name replaces the id**, with `copyValue` kept as the raw
id. The card already announces "Copy Installer source" to TalkBack, so the id is one tap from the
clipboard. Concatenating both re-triggers the monospace heuristic, doubles the width problem, and is
what gets copied.

---

## 4. Testing

JVM unit tests, run with `--rerun-tasks`, counts read from `app/build/test-results/**/*.xml` and
never from Gradle's log line.

**PR1.** `guardedWrite` returns false and does not throw when the store throws `IOException`; a
`CancellationException` still propagates. `SecurityViewModel` does not emit
`biometric_lock_disabled_no_biometric` when the write fails, and its collector survives. The
one-notice-per-process channel emits once across many failures. `SettingsViewModel` does not call
`applyLocale` when `setLanguage` returns false. The existing `ViewModelTestDoubles` fake needs its
two signatures widened and a failure mode.

**PR2.** The prune deletes nothing when `scanVerdict` returns `Retain` — the test that must fail if
the gate is removed. The editor does not dismiss until the repository confirms, and Save is disabled
in flight. `suppressible` is true only for system + freeze + `NORMAL`, and false for `EXPERT` and
`BLOCKED` — a table test over every tier × action × isSystem combination. The TTL and the coalescing
rule are tested as pure functions; `BulkFreezeRunner` itself cannot be constructed on the JVM.
`unfreezeAll` routed through the runner produces the same two strings it produced by hand.

**PR3.** The Fix Store target set excludes `com.android.vending`, both package installers and Thor.
CSV round-trips RFC 4180 quoting and prefixes the six dangerous leading characters. Installer name
resolution falls through curated → PM → raw id, including the case where the PM lookup throws
`NameNotFoundException`.

**Every new test is mutation-checked** before it is trusted: break the thing it asserts and confirm
it goes red. A test that silently finds nothing is a green no-op, which is how
`AnyFileOpenerAliasTest` nearly shipped useless.

**Not covered by any of this:** anything needing a device. Rows filed in §9 exist because guessing
was the alternative.

## 5. Error handling

The rule this whole band turns on: **an empty failure list means *unknown*, never *fine*.** It is
already written down for cross-privilege unsuspend and it is the same mistake `guardedWrite` would
make if it returned `Unit` everywhere.

- A failed preference write is reported once per process and the setting does not stick. It is never
  silently treated as applied.
- A refused freeze ends in `Result.failure` with the package left installed and the refusal named.
  Band A established this; nothing here weakens it.
- The prune acts only on positive evidence (`Accept`), never on absence of evidence.
- Suppressing a confirmation never suppresses an enforcement.

## 6. Strings and locales

514 strings currently sit at exact parity across `values`, `-ar`, `-es`, `-fr`, `-zh-rCN`, and
`MissingTranslation` is fatal. New strings: one for PR1, two for row 18, a small set for rows 14, 20
and 21.

**Within each PR the string additions are a single blocking first commit owned by one worker.** Band
A established this the hard way: concurrent appends to five locale files are both a clobber and a
hard build failure.

## 7. Version and release

`versionCode` stays **1940** throughout. It changes only in a `chore(release)` commit, and none of
these four PRs is one. `versionName` is derived and never edited.

The release-notes retraction for `release-notes/v1.94.0/github.md:84` — which promises the freeze
fallback band A removed — remains due at the next release, in that release's own `github.md`. Not an
edit to v1.94.0's published body: the line was **true** when v1.94.0 shipped, so it is superseded,
not erroneous, and `gh release view` confirms the published body is a snapshot taken at run time, so
editing the repo file corrects nothing a reader sees while creating the appearance that it did.

## 8. Order of work

1. PR0 → `master`. Independent, publishes on merge, unblocks nothing.
2. PR1 → `dev`. Blocks 3 and 4.
3. PR2 and PR3 → `dev`, concurrently.

Before merging any PR, merge its base in first and re-run CI — a stacked PR is otherwise validated
by the CI its own branch predated, which is how band A nearly merged against a weaker gate. Scope
green-check polling to the current head SHA: workflow-level `concurrency` with `cancel-in-progress`
means every push cancels the prior run, so a poll against a superseded SHA reports `cancelled`
rather than `failure`.

## 9. Deliberately not in this band

Each of these is filed rather than dropped.

| Item | Why it is out |
|---|---|
| Fix Store via `pm set-installer` | Needs measurement under root and Shizuku on a real device |
| Is Fix Store dead under Dhizuku? | Needs a Dhizuku-only device; `MultiSelectToolBox.kt:78` enables it today |
| "Remove anyway" escape hatch (row 15 option 2) | Re-creates the state GH#310 prevents; owner declined |
| Classify a failed restore and offer the fix (row 15 option 4) | Best answer, not small, only one privilege mode can feed it |
| Interactive drag scrubber (row 19) | Deferred behind the free indicator |
| Locale `Collator` for the NAME sort | Changes ordering for every existing user; needs its own release note |
| Apps-tab bulk freeze has no confirmation | Real asymmetry with `MainScreen.kt:488`; belongs in a row that *adds* prompts |
| Suppressing the post-freeze watchlist snackbar | An offer, not a warning; wait for a second complaint |
| Per-app installer history (Room v7) | Needed for a Fix Store undo; shares scope with row 25 |
| The `#161` `pm query-activities` diagnostic | Needs a Samsung device; until it runs, the opt-in switch stays the only route |
| `docs/site-content/*.md` drafts | Untracked owner-local files; PR1 only strikes the citations |
| A `LocaleManager` interface so `SettingsViewModel` is JVM-testable | Concrete class taking a `Context`; extracting it touches DI and the Settings screen, and no existing test constructs the ViewModel either. A refactor inside a crash-fix PR is hard to review and hard to revert — see B5 |

### Two backlog claims corrected

**Row 16's "fold mismatch" is not a bug.** `FreezeProfileEditorSheet.kt:115` filters `existingNames`
by name rather than by profile id. The unique index is NOCASE, so two profiles cannot share a name,
which makes name-filtering and id-filtering provably equivalent — and the comment at `:111-113` says
so. There is no reachable failure. Row 16 is the optimistic dismiss and nothing else.

**Row 13 was under-counted.** The follow-up doc says 25 bare `edit { }` blocks and 28 setters. The
measured figures are **28 write functions making 29 `.edit { }` calls across two DataStores**, with
**zero** `try` blocks in the file and 33 call sites in `presentation/`.
