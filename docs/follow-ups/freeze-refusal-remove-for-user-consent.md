# "Remove it for this user anyway" — the consent path band A #1 left behind

**Filed:** 2026-08-07 (UTC) · **Opened by:** the band A #1 fix, deliberately
**Status:** open, and it is a product decision before it is a build.

## What changed under this

Band A #1 removed the freeze → uninstall escalation. `uninstallFreezeFallbackAllowed` now answers
`false` under **every** privilege mode, refused or not, and `ShizukuSystemGateway.freezeSystemApp` /
`DhizukuSystemGateway.freezeSystemApp` end at that gate the way `RootSystemGateway` already did:
build an `IOException`, log it, `return Result.failure`, leave the package installed. On an OEM build
that refuses `pm disable-user`, freezing a system app now **fails visibly** and says so — the refusal
branch renders `R.string.freeze_system_app_disable_refused`.

That is the right default. It is not the whole answer, because it also removes a capability: on those
devices, "remove this system app for my user, keeping its data" was a thing Thor could do, and some
users want it — they just did not want it to happen silently under a button labelled *Freeze*.

The escalation code is **still in the tree**, unreachable at runtime but statically referenced from
all three gateways, precisely so this path has something to call. Nothing was deleted.

## What it needs

**A new gateway method, not the existing one.** `SystemGateway.uninstallApp` is a plain
`pm uninstall` — **no `-k`** — so it destroys the app's data and falls into the `DELETE_ALL_USERS`
trap documented at `RootSystemGateway.kt:1097`. Wiring consent to that call would turn "remove it for
me" into "delete my data on every user", which is worse than the behaviour that was just removed. The
signature this wants is closer to `removeForUserKeepingData(packageName: String): Result<Unit>`,
routed to the `pm uninstall -k --user N` rung that already exists in each gateway.

**A screen-level dialog, on four hosts.** The failure arrives *after* `AppInfoSheet` has already
dismissed itself, so the confirmation cannot live inside the sheet — the host has to own it. The four
freeze entry points that can show UI are the app list, the app-info detail screen, the Freezer screen
and the multi-select toolbox.

## The four questions that have to be answered first

None of these is an engineering unknown; all four are product calls, and getting them wrong produces
a state the user cannot reason about.

1. **What does the Freezer watchlist show for a removed-but-not-frozen app?** It is on the watchlist
   and it is not disabled. `AppFreezeStateReader` reads `FLAG_INSTALLED`, so it will read as frozen —
   but the user chose *remove*, not *freeze*, and the row's own affordance says Unfreeze.
2. **Does unfreeze restore it?** `pm install-existing` is the inverse and it works, but "unfreeze"
   reinstalling a package the user asked to have removed is a second surprise in the opposite
   direction.
3. **What do the headless surfaces do?** `AutoFreezeManager`, `BulkFreezeRunner`,
   `FreezerTileService`, `ExtensionOpsProvider` and the launcher shortcut all freeze with **no UI**.
   They cannot ask. Today they will simply count a failure on a refusing device. A per-app "yes, you
   may remove this one" flag persisted at consent time is the obvious answer, and it is a schema
   decision.
4. **Is a device reproduction required before shipping?** The account-loss mechanism this whole thread
   started from is **inferred, not measured** — see
   [`reddit-howtomen-feedback.md`](reddit-howtomen-feedback.md) finding 1. Neither the old escalation
   under Dhizuku nor the new refusal message has been observed on hardware that actually refuses
   `pm disable-user` (reported on Xiaomi HyperOS, Android 14).

## Two things not to re-derive

- **Do not delete the escalation code to "clean up".** It is what this path calls. If this item is
  ever declined outright, the removal has to take
  `freeze_system_app_requires_root` / `freeze_system_app_removal_failed` with it — both are rendered
  only from that code, and `UnusedResources` is fatal in `:app`.
- **The gate function survives answering `false` everywhere on purpose.** Its `when` stays exhaustive
  over `PrivilegeMode`, so a new mode is a compile error at the one site that owns the decision. A
  bare `false` at the two call sites would push the decision back into the gateways, which is the
  state the function was written to get out of.
