# Pinned-shortcut PackageManager lookups drop frozen system apps

**Status:** **FIXED**, both sites together and by the shape suggested below. Still latent when it was
fixed — that is the point of fixing it now rather than after the pin gate moves. See
[Resolution](#resolution).
**Found:** 2026-07-29, during the root-cause sweep for the tile icon-refresh bug (PR #286).

## The gap

Two lookups in `FreezerShortcutManager` resolve a package without the match flags a frozen
**system** app needs:

| Site | Call | Missing |
|---|---|---|
| `appLabel` (`FreezerShortcutManager.kt:281`) | `getApplicationInfo(pkg, MATCH_DISABLED_COMPONENTS)` | `MATCH_UNINSTALLED_PACKAGES` |
| `appIcon` (`FreezerShortcutManager.kt:293`) | `getApplicationIcon(pkg)` | everything — the `String` overload resolves via `getApplicationInfo(pkg, sDefaultFlags)`, and `sDefaultFlags` is `GET_SHARED_LIBRARY_FILES` |

A system app is frozen by disabling it where the platform allows that, and by removing it for the
current user where it does not (`domain/model/FreezePolicy.kt`). Under the second mechanic — the
only one Thor used when this was written, and still what Shizuku does on devices that refuse to
disable system packages and what Dhizuku does everywhere, plus
what every system app frozen before the split is already sitting in — the package is **not installed
for the current user**. `PackageInfoUtils.generateApplicationInfo` → `checkUseInstalledOrHidden` →
`PackageUserStateUtils.isAvailable` then returns false without `MATCH_UNINSTALLED_PACKAGES` /
`MATCH_KNOWN_PACKAGES`, and `getApplicationInfo` throws `NameNotFoundException`.

`AppFreezeStateReader.MATCH_FLAGS` is the correct reference pattern; these two sites predate it.

A *disabled* package is unaffected, system or user. `getApplicationInfo` does **not** filter on the
enabled setting — `ComputerEngine.getApplicationInfoInternalBody` carries the literal comment *"Note:
isEnabledLP() does not apply here - always return info"* — so it still resolves with flags `0`, and
comes back with `enabled == false`. That is why `AutoFreezeManager.kt:129` works.

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

## Resolution

Done as suggested: one `applicationInfo(packageName)` read with the freeze-aware flags, handed to
both `getApplicationLabel(ApplicationInfo)` and `getApplicationIcon(ApplicationInfo)`. `appLabel`
became a pure `ApplicationInfo -> String`, `appIcon` takes the info instead of the package name, and
a new `liveAppShortcut` is the one place that resolves a package and builds a shortcut from what it
found — so "fix them together" is now the only thing the code lets you do.

Two things beyond the straight port:

**The flags have one definition now.** They had three — `AppFreezeStateReader`,
`ExtensionOpsProvider`, and none in the shortcut manager, which is the whole bug in miniature.
`AppFreezeStateReader.MATCH_FLAGS` is public and the other two use it, so a fourth site cannot
half-have them. `FreezeMatchFlagsTest` pins the pair; that is all a JVM test can reach, since a
`PackageManager` cannot be faked, but the flags are the half that kept drifting.

**The fallback icon is no longer the white square.** This section warned that un-masking `appIcon`
turns a stale icon into a blank one, because `IconCompat.createWithResource(context,
R.drawable.frozen)` hands the launcher a `?attr/colorControlNormal`-tinted vector it cannot resolve.
The catch now goes through `bulkIcon`, the composed-bitmap path that already existed for exactly
this, so the degraded case is a frost tile rather than nothing. The masking is gone *and* what it
was masking is fixed, rather than the first alone.

Unchanged: the `!isSystem` pin gate, at all four entry points. This removes the landmine under it;
it does not lift it.

### Acceptance

Not device-verifiable today — every path into these lookups is still user-apps-only, so there is no
frozen system app to point at. It becomes testable the moment the gate lifts, which is when this
would otherwise have been found. `FreezeMatchFlagsTest` plus the compiler (the label can no longer
be resolved without the info the icon also uses) is what stands in for it until then.

## Same gap, elsewhere (scoped out)

- ~~`ExtensionOpsProvider.kt:113` — misreports a frozen **system** app as not-frozen to extensions.
  Real, user-visible to extension authors, unrelated to shortcuts.~~ **Fixed separately** (#14 in
  the follow-ups README), and now reads the shared constant rather than its own copy.
- `FreezerLaunchActivity.kt:168` (`isSuspended`) — benign; the `||` at `:137` short-circuits so it
  is only evaluated once `getLaunchIntentForPackage` has already resolved.
- `AutoFreezeManager.kt:129` (flags `0`) — still benign, but the reason changed with the mechanic.
  A system app *removed for this user* throws and is logged "not found, skipping", as before. A
  system app *disabled in place* resolves instead — `getApplicationInfo` ignores the enabled
  setting — and comes back with `enabled == false`, so the `if (appInfo.enabled && !alreadySuspended)`
  guard four lines down skips it. Both routes end at "already frozen, do nothing"; the exception is
  simply no longer the thing doing the skipping. Note that widening these flags to
  `AppFreezeStateReader.MATCH_FLAGS` would *break* this: under `MATCH_UNINSTALLED_PACKAGES` a
  package uninstalled for this user resolves with `enabled == true`, so the guard would pass and
  auto-freeze would act on an app it can already see is frozen. Flags `0` is load-bearing here.
