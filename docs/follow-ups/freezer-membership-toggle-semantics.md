# Follow-up: one freezer-membership button, two meanings

**Status:** SHIPPED — resolved via Option 1 (differentiating action labels: "Unfreeze & Remove" on the Freezer tab, "Remove from Watchlist" on the Apps tab).
**Severity:** Minor.
**Effort:** trivial.
**Raised by:** the adversarial review of the unified-app-info-sheet steps 6/7 (2026-07-29). The
compose-ui lens flagged it and an independent verifier confirmed every code assertion; it survived
because the divergence is invisible to the compiler — both hosts satisfy `(() -> Unit)?`.

Files:
`app/src/main/java/com/valhalla/thor/presentation/widgets/AppActionRow.kt:177 (the shared control)`
`app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt:582 (Freezer host passing R.string.action_unfreeze_and_remove)`
`app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt:221 (toggleManaged, remove branch)`
`app/src/main/java/com/valhalla/thor/presentation/appList/AppListScreen.kt:353 / AppInfoDetailsScreen.kt:292 (Apps host defaulting to R.string.action_remove_from_watchlist)`
`app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt:398 (toggleFreezerMembership)`

## Problem (Historical)

`AppInfoSheet` previously rendered one snowflake action with identical labels: `action_add_freezer` when
the app was out, and `action_in_freezer` when it was in. Both hosts passed the same icon, the same label,
and the same tint, but did not pass the same operation:

- **Apps tab** → `AppListViewModel.toggleFreezerMembership` moves the watchlist row and nothing else.
  It never disables or restores anything.
- **Freezer tab** → `FreezerViewModel.toggleManaged(add = false)` removes the row, disables the
  launcher shortcut, *and* calls `manageAppUseCase.restoreApp(pkg, app.enabled, app.isSuspended)` —
  which for a frozen app resolves to `setAppDisabled(pkg, false)` / `setAppSuspended(pkg, false)`.
  Leaving the freezer thaws the app.

So the same app, reached through the same sheet, ended in two different states depending on which tab
opened it:

| Tap on a frozen, watchlisted app | Watchlist | App state |
|---|---|---|
| from the Apps tab ("Remove from Watchlist") | removed | still frozen |
| from the Freezer tab ("Unfreeze & Remove") | removed | restored |

## Shipped Resolution: Option 1

1. **Make the label carry the operation (✅ Shipped).** Kept both established behaviors, but let each host
   explicitly name the action — `"Unfreeze & Remove"` (`action_unfreeze_and_remove`) on the Freezer tab,
   and `"Remove from Watchlist"` (`action_remove_from_watchlist`) on the Apps tab. Configured via
   `@StringRes freezerRemoveLabelRes` on `AppActionRow` and `AppInfoSheet`.

## Other Considered Resolutions (Archived)

2. **Unify on membership-only** and let the Freezer tab's remove leave the app frozen, relying on the
   import prompt to surface it again. Most consistent, but the Freezer tab is the one place where
   "get this app out of the freezer" almost certainly means "and give it back to me".
3. **Unify on remove-and-restore** so the Apps tab thaws too. Consistent and never orphans, but it
   changes shipped behaviour on a surface users already know, and makes a membership control
   privileged — it would need the same tier gate as the freeze paths (`FreezeAppUseCase.kt:35-48`).
4. **Confirm on the destructive direction only** — a dialog on the Freezer tab's remove, none on the
   Apps tab. Solves the surprise without picking a semantic, at the cost of a tap.

## Notes

Resolved by parameterizing `freezerRemoveLabelRes` with default `R.string.action_remove_from_watchlist`
in `AppActionRow` / `AppInfoSheet`, overridden to `R.string.action_unfreeze_and_remove` in `FreezerScreen`.
Localized across all 8 supported locales (`en`, `ar`, `es`, `fr`, `pl`, `pt`, `pt-rBR`, `zh-rCN`).
