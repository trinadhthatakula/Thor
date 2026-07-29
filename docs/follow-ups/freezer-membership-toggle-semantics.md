# Follow-up: one freezer-membership button, two meanings

**Status:** OPEN — a product decision, not a defect. Both behaviours are correct for their host; the
problem is that they wear the same label.
**Severity:** Minor. Nothing is lost either way, but one of the two outcomes will surprise someone.
**Effort:** small once the semantics are chosen.
**Raised by:** the adversarial review of the unified-app-info-sheet steps 6/7 (2026-07-29). The
compose-ui lens flagged it and an independent verifier confirmed every code assertion; it survived
because the divergence is invisible to the compiler — both hosts satisfy `(() -> Unit)?`.

Files:
`app/src/main/java/com/valhalla/thor/presentation/widgets/AppActionRow.kt:142 (the shared control)`,
`app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt:446 (Freezer host)`,
`app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt:221 (toggleManaged, remove branch)`,
`app/src/main/java/com/valhalla/thor/presentation/appList/AppListScreen.kt:278 (Apps host)`,
`app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt:398 (toggleFreezerMembership)`

## Problem

`AppInfoSheet` renders one snowflake action, labelled from state: `action_add_freezer` when the app
is out, `action_in_freezer` when it is in. Both hosts pass the same icon, the same label and the same
tint. They do not pass the same operation.

- **Apps tab** → `AppListViewModel.toggleFreezerMembership` moves the watchlist row and nothing else.
  It never disables or restores anything.
- **Freezer tab** → `FreezerViewModel.toggleManaged(add = false)` removes the row, disables the
  launcher shortcut, *and* calls `manageAppUseCase.restoreApp(pkg, app.enabled, app.isSuspended)` —
  which for a frozen app resolves to `setAppDisabled(pkg, false)` / `setAppSuspended(pkg, false)`.
  Leaving the freezer thaws the app.

So the same app, reached through the same sheet, ends in two different states depending on which tab
opened it:

| Tap "In Freezer" on a frozen, watchlisted app | Watchlist | App state |
|---|---|---|
| from the Apps tab | removed | still frozen |
| from the Freezer tab | removed | restored |

Neither is a regression. The Apps path reproduces `AppInfoDetailsViewModel.addOrRemoveFromFreezer`,
the details-screen behaviour it replaced verbatim. The Freezer path matches `ManageFreezerSheet`,
the only un-manage surface that tab has ever had — and it is the newly introduced half, because
before this branch the sheet carried no membership control at all on that tab ("Not wired here, on
purpose", the old KDoc said).

The two failure modes are symmetric, which is why this is a decision rather than a bug:

- **Apps tab:** removing a *frozen* app from the freezer orphans it — disabled, and no longer on any
  list that offers to unfreeze it. Partly mitigated by the Freezer screen's `disabledAppsNotInFreezer`
  import prompt (`FreezerScreen.kt:108`), which offers to re-adopt exactly these apps.
- **Freezer tab:** one tap on a control that reads as a *state* label re-enables the app, with no
  confirmation and no undo.

## Candidate resolutions

1. **Make the label carry the operation.** Keep both behaviours, but let the host name the action —
   "Remove & Restore" on the Freezer tab, "Remove from Freezer" on the Apps tab. Smallest change,
   keeps each tab's established contract, costs two string resources.
2. **Unify on membership-only** and let the Freezer tab's remove leave the app frozen, relying on the
   import prompt to surface it again. Most consistent, but the Freezer tab is the one place where
   "get this app out of the freezer" almost certainly means "and give it back to me".
3. **Unify on remove-and-restore** so the Apps tab thaws too. Consistent and never orphans, but it
   changes shipped behaviour on a surface users already know, and makes a membership control
   privileged — it would need the same tier gate as the freeze paths (`FreezeAppUseCase.kt:35-48`;
   the follow-up doc that used to describe this shipped and was deleted in `412f655e`).
4. **Confirm on the destructive direction only** — a dialog on the Freezer tab's remove, none on the
   Apps tab. Solves the surprise without picking a semantic, at the cost of a tap.

Option 1 is the cheapest honest answer and does not foreclose any of the others.

## Notes

Until this is decided, the divergence is documented at both ends — `AppActionRow`'s parameter list
and `AppInfoSheet`'s KDoc — so the next caller to wire this control knows the contract is "the host
defines what leaving means", not "membership only".
