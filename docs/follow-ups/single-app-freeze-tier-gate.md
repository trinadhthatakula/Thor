# Follow-up: the single-app freeze paths have no tier gate — only a hidden button

**Status:** OPEN — not user-reachable today, so not urgent; worth closing before another surface
learns to freeze.
**Severity:** Minor now, Major the moment a new caller appears.
**Effort:** small.
**Raised by:** the adversarial review of the `AppRiskDialog` consolidation (2026-07-29). Two of the
four review lenses independently flagged that the new dialog's KDoc claimed a view-model backstop
that mostly does not exist. The claim was wrong; the code was right. This is the gap the claim
described.

Files:
`app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt:298 (freezeApp)`,
`app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt:260 (freezeSingleApp)`,
`app/src/main/java/com/valhalla/thor/presentation/appList/AppInfoDetailsViewModel.kt:130 (toggleFreezerState)`,
`app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt:190 (toggleManaged — the one that does it right)`,
`app/src/main/java/com/valhalla/thor/domain/model/FreezePolicy.kt`

## Problem

`FreezeTier.BLOCKED` means "never freeze, whatever the surface". Exactly one view model enforces
that. `FreezerViewModel.toggleManaged:207` resolves the app and refuses on `BLOCKED` — and fails
closed when the app cannot be resolved at all, which is the shape PR #287's CodeRabbit Major
established as correct.

The three single-app freeze entry points do not check anything. Each takes a `packageName` and goes
straight to `manageAppUseCase.setAppDisabled(packageName, true)`:

- `AppListViewModel.freezeApp` — from the app-info sheet on the Apps tab
- `FreezerViewModel.freezeSingleApp` — from the app-info sheet on the Freezer tab
- `AppInfoDetailsViewModel.toggleFreezerState` — from the details screen / details pane

What stops a blocked system app being frozen through them is that `AppRiskDialog` renders **no
confirm button** for `BLOCKED`, so the callback that would dispatch the freeze is never wired to
anything the user can tap. That is a real gate and it currently holds on every path — the review
traced all of them — but it lives in the presentation layer, one composable away from the code that
performs the freeze.

This is the same class of gap PR #287 closed for the QS tile: the tile froze what the in-app dialog
refused, because the rule was re-typed per surface instead of enforced where the action happens. The
consolidation into `FreezePolicy.kt` fixed the *predicate* duplication. It did not move
*enforcement* down to the view models on these three paths.

## Why it is not a live bug

Every caller is gated. Traced 2026-07-29:

- `AppInfoSheet.kt:238` — `onFreezeToggle` only sets `showFreezeConfirmation` when
  `shouldFreeze && appInfo.isSystem`; the sheet is the only remaining constructor of
  `AppClickAction.Freeze`, and `AppRiskDialog` has no confirm button when blocked. (This was
  `AppInfoDialog.kt` when the follow-up was filed; the unified-sheet branch deleted that file and
  moved the gate here unchanged.)
- `AppInfoDetailsScreen.kt` — the same gate, same dialog.
- `ManageFreezerSheet.kt` — `if (!inFreezer && tier != FreezeTier.NORMAL) pendingApp = app`, and
  `tier != NORMAL` implies `isSystem` by construction (`freezeTierOf` opens with
  `!isSystem -> NORMAL`).

So: no reachable path today. The exposure is that a fourth surface — a new shortcut, an extension
trigger, an automation intent, a widget — reaches for `freezeApp(packageName, …)` because that is
the obvious entry point, and inherits nothing.

### Not this: the *membership* adds, which were a live bug and are now fixed

Adding an app to the freezer watchlist is a different action from freezing it, and it had the same
gap for real. `FreezerViewModel.toggleManaged` checked the tier; `AppListViewModel`
`.toggleFreezerMembership` and `AppInfoDetailsViewModel.addOrRemoveFromFreezer` did not, so the
snowflake on the Apps tab and on the details screen would happily put a BLOCKED system app on the
watchlist that the Freezer tab's own manage sheet refuses. Every bulk run then silently skipped it —
the app just sat there looking managed.

That was latent behind the `useDetailedView` setting until the unified-sheet branch made the
snowflake part of the default tap path, so it was fixed there: all three now resolve the `AppInfo`,
refuse on `BLOCKED`, fail closed when it cannot be resolved, and emit `R.string.error_unsafe_skipped`.
Removal is deliberately never gated, so anything that got on the list before the gate can still get
off it.

That is option 1 applied to the membership paths — three copies of the same block, which is exactly
the argument for doing the freeze paths as option 2 rather than making it six.

**The other four `freezerRepository.add` sites are deliberately ungated, and should stay that way.**
CodeRabbit raised them on #288 as the same bug; they are not. The line is *who is asking for what*:

- **"Put this app I am looking at on the watchlist"** — gated. The app is not frozen, so an entry
  for a `BLOCKED` app buys nothing and every bulk run skips it. That is the snowflake, the manage
  sheet, `toggleManaged`.
- **"This app is already frozen — track it?"** — not gated. `AppListViewModel.addToFreezer`,
  `FreezerViewModel.addToFreezer` and `AppInfoDetailsViewModel.addToFreezer` are only ever reached
  from a prompt raised inside `result.onSuccess { … if (freeze && !inFreezer) }`, and
  `FreezerViewModel.addAppsToFreezer` runs over apps that are already `!enabled`. Membership is what
  makes those apps *recoverable*: `freezableCandidates` drops `blockedFromFreeze` from FREEZE runs
  but filters UNFREEZE runs on `state == FROZEN` alone, so an entry can never cause a re-freeze and
  is the only way Unfreeze-all reaches the app. Failing closed there would refuse to track an app
  that is already frozen — harm, not caution.
- `addAppsToFreezer` could not see a blocked app regardless: `disabledAppsNotInFreezer` filters
  `!isSystem` and `freezeTierOf` opens with `!isSystem -> NORMAL`.

`MainViewModel:319` and `:444` add after a *successful system uninstall*, which is Thor's freeze
mechanism for system apps — same category, same reasoning, and `:444` is already gated on the
uninstall itself.

## Fix

Push the check to where the freeze happens, not where it is offered. Either:

1. Gate inside each of the three view models the way `toggleManaged` already does — resolve the
   `AppInfo`, refuse on `BLOCKED`, **fail closed when it cannot be resolved**, emit
   `R.string.error_unsafe_skipped`. Straightforward, but it is a fourth copy of the same block.
2. Better: move it under the view models entirely — have `ManageAppUseCase.setAppDisabled` (or a
   `FreezeAppUseCase`) resolve the tier and return `Result.failure(UiTextException(...))` for
   `BLOCKED`. One home, every present and future caller covered, and the view models keep only the
   message. Needs care so unfreeze is never blocked — unfreezing is the way out of a bad state — and
   so the batch paths, which already filter, do not double-report.

Option 2 is the one that matches where `FreezePolicy.kt` was heading.

## Test

`FreezeStateTest.kt` covers `freezeTierOf`. The gap is behavioural, so it wants the same
`kotlinx-coroutines-test` dependency as
[`viewmodel-behavior-tests.md`](viewmodel-behavior-tests.md) and
[`bulk-freeze-runner-concurrency-tests.md`](bulk-freeze-runner-concurrency-tests.md): freeze a
BLOCKED package through each entry point against a fake `ManageAppUseCase` and assert it was never
called. Land it with those two.
