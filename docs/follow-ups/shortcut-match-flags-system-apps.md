# Pinned-shortcut PackageManager lookups drop frozen system apps

**Status:** filed, not fixed. Latent — unreachable while per-app pins stay user-apps-only.
**Found:** 2026-07-29, during the root-cause sweep for the tile icon-refresh bug (PR #286).

## The gap

Two lookups in `FreezerShortcutManager` resolve a package without the match flags a frozen
**system** app needs:

| Site | Call | Missing |
|---|---|---|
| `appLabel` (`FreezerShortcutManager.kt:281`) | `getApplicationInfo(pkg, MATCH_DISABLED_COMPONENTS)` | `MATCH_UNINSTALLED_PACKAGES` |
| `appIcon` (`FreezerShortcutManager.kt:293`) | `getApplicationIcon(pkg)` | everything — the `String` overload resolves via `getApplicationInfo(pkg, sDefaultFlags)`, and `sDefaultFlags` is `GET_SHARED_LIBRARY_FILES` |

Thor freezes system apps with `pm uninstall --user N`, not `pm disable`, so a frozen system app
is **not installed for the current user**. `PackageInfoUtils.generateApplicationInfo` →
`checkUseInstalledOrHidden` → `PackageUserStateUtils.isAvailable` then returns false without
`MATCH_UNINSTALLED_PACKAGES` / `MATCH_KNOWN_PACKAGES`, and `getApplicationInfo` throws
`NameNotFoundException`.

`AppFreezeStateReader.MATCH_FLAGS` is the correct reference pattern; these two sites predate it.

User apps are unaffected. `getApplicationInfo` does **not** filter on the enabled setting —
`ComputerEngine.getApplicationInfoInternalBody` carries the literal comment *"Note: isEnabledLP()
does not apply here - always return info"* — so a `pm disable`d package still resolves with flags
`0`. That is why `AutoFreezeManager.kt:122` works.

## Why it is unreachable today

Per-app pinned shortcuts are `!isSystem`-gated at every entry point:

- `FreezerViewModel.kt:348` — `if (app.isSystem) return // v1: user apps only`
- `FreezerViewModel.kt:361` — `.filter { !it.isSystem }` (pin-all)
- `AppInfoDetailsScreen.kt:798`, `AppInfoDialog.kt:623` — `&& !appInfo.isSystem &&`

and `rebuildPinnedIcons` pre-filters the watchlist by `in pinnedIds` before calling `appLabel`.
So every package that reaches these lookups is a user app with a pinned shortcut.

## Fix them together, or not at all

**`appLabel` currently masks `appIcon`.** `rebuildPinnedIcons` and `updateShortcutIcon` both
short-circuit on `appLabel(pkg) ?: return`, so `appIcon` never runs for a package whose lookup
would fail. Patching `appLabel` alone un-masks it: the `getApplicationIcon(pkg)` call inside
`appIcon` throws, `appIcon`'s own `catch` swallows it and returns
`IconCompat.createWithResource(context, R.drawable.frozen)`, and that vector is
`android:tint="?attr/colorControlNormal"` — a theme attr that cannot resolve in the launcher's
context, so it renders as the white/invisible blob `bulkIcon`'s own KDoc warns about. That turns
"icon does not update" into "icon becomes a white square", which is worse.

Suggested shape: resolve `ApplicationInfo` **once** with `AppFreezeStateReader.MATCH_FLAGS` and
feed it to both `getApplicationLabel(ApplicationInfo)` and `getApplicationIcon(ApplicationInfo)`.
One binder call instead of two, and no way for the two to disagree.

## Trigger

Lifting the "v1: user apps only" pin gate — which is exactly what the "should the tile be allowed
to freeze system apps?" discussion would lead to. Do this first if that lands.

## Same gap, elsewhere (scoped out)

- `ExtensionOpsProvider.kt:113` — misreports a frozen **system** app as not-frozen to extensions.
  Real, user-visible to extension authors, unrelated to shortcuts.
- `FreezerLaunchActivity.kt:168` (`isSuspended`) — benign; the `||` at `:137` short-circuits so it
  is only evaluated once `getLaunchIntentForPackage` has already resolved.
- `AutoFreezeManager.kt:122` (flags `0`) — benign; a frozen system app throws, is logged
  "not found, skipping", and skipping an already-frozen app is the desired outcome anyway.
