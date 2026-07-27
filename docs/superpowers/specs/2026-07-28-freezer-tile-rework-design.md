# FreezerTileService rework — design

**Date:** 2026-07-28
**Status:** Approved (brainstorming complete; implementation plan pending)
**Supersedes:** `docs/follow-ups/freezer-tile-service-rework.md`, whose problem statement and
acceptance criteria are written around a Toast that has never worked. That file is deleted by this
change.

## Problem

The Quick Settings tile has three defects, one reported and two found while assessing it.

**1. The tile never goes dim (reported).** `refreshTile()` derives its state from
`freezerRepository.getAllPackageNames()` — the curated watchlist. `FreezerEntity` has no
frozen-state column and nothing deletes rows on freeze, so the list size is **invariant** under
freeze/unfreeze. After freezing every app in the watchlist the tile still reads `STATE_ACTIVE` with
"N apps to freeze". Reproduced on device with root.

The companion symptom "the tile stays enabled when no privilege is available" did **not** reproduce:
denying root produced a correctly disabled tile. The privilege path works today. (A narrower latent
bug does exist — Odin's `MainShell` caches `isRoot` for the process lifetime, so root *revocation*
after a grant is invisible until restart — but that is a separate follow-up, not this one.)

**2. Every Toast the tile posts is dropped.** All four `Toast.makeText(...).show()` sites are dead,
for two independent reasons:

- `NotificationManagerService.checkCanEnqueueToast` drops a toast from a package that lacks
  notification permission and is not foreground. A tile bound by SystemUI is neither.
- `TYPE_TOAST` is window layer 7; `TYPE_NOTIFICATION_SHADE` is layer 17. Even if enqueued, it draws
  *under* the open shade.

This has never worked, so "the result toast" — cited in three code comments and in the follow-up doc
as the thing the static `appScope` exists to protect — is protecting nothing.

**3. The timeout bounds nothing, and lies when it fires.**
`withTimeoutOrNull(30_000L) { pkgs.map { async { … } }.awaitAll() }` is a scoping builder: on timeout
it cancels its children and then cannot return until they complete. The children are blocking
shell/binder calls that do not observe cancellation, so the timeout waits for precisely the thing it
was added to escape. When it does fire, `results?.count { it.isFailure } ?: pkgs.size` reports **all
N as failed**, even though most probably succeeded.

Secondary: `onClick` fans out unbounded `async` over the whole watchlist while the sibling
`FreezerShortcutManager.runBulk` runs strictly sequentially; rapid taps spawn overlapping batches;
and the `onClick` lambda captures `this@FreezerTileService` from a static, never-cancelled scope, so
a destroyed service is retained for as long as the coroutine runs.

## Decisions taken during brainstorming

| # | Decision |
|---|---|
| D1 | The tile is **freeze-only**. No `TOGGLEABLE_TILE`, no unfreeze from the tile. |
| D2 | `STATE_UNAVAILABLE` = no privilege; `STATE_INACTIVE` = nothing to freeze; `STATE_ACTIVE` = ≥1 freezable app. |
| D3 | "Freezable" is resolved from **real per-app state**, not watchlist size. |
| D4 | The freezable filter is **shared** with `FreezerShortcutManager.runBulk`. |
| D5 | Result reporting = tile surface **always**, notification **additionally** when permitted. |
| D6 | `POST_NOTIFICATIONS` is obtained by the **standard runtime request from Settings**. Never self-granted. |
| D7 | No foreground service, no WorkManager, no ContentProvider for batch survival. |

## 1. Tile state machine

Three states, from two independent inputs:

| State | Condition | Behavior |
|---|---|---|
| `STATE_UNAVAILABLE` | `!privilegeState.hasAnyPrivilege` | greyed, **not clickable** |
| `STATE_INACTIVE` | privileged, `freezableCount == 0` | dim, clickable (repaints, no work) |
| `STATE_ACTIVE` | privileged, `freezableCount >= 1` | lit |

**Freezable** = watchlist entries whose current `PackageManager` state satisfies the existing domain
predicate `FreezerMode.isActive(enabled, isSuspended)` (`FreezerMode.kt:13`).
`NameNotFoundException` (uninstalled) counts as not-freezable; such rows are **not** pruned from the
watchlist here.

### The unknown window

`PrivilegeState.isReady` is false until the first probe completes. Painting `UNAVAILABLE`
optimistically during that window is a trap: AOSP's `CustomTile.handleClick()` early-returns on
`STATE_UNAVAILABLE`, so an unavailable tile **never receives `onClick`** — a wrong optimistic paint
makes the tile dead until the next listen. Therefore:

- **Phase 1** (synchronous, in `onStartListening`): paint from `PrivilegeManager.state.value` plus
  the last known count. If `!isReady`, paint `INACTIVE` with a "checking" subtitle — never
  `UNAVAILABLE`.
- **Phase 2** (after the off-thread `PackageManager` sweep): correct to the real state.

Because `INACTIVE` is clickable, `onClick` keeps a no-privilege guard as a race fallback. It is not
redundant — it catches a tap landing inside the unknown window.

### Surfaces

`setSubtitle` (API 29+), `setStateDescription` (API 30+), `setContentDescription` (API 24+, always,
for TalkBack). minSdk 28 gets contentDescription only. **Never `setLabel`** — it mutates the tile's
identity in the picker.

`ACTIVE_TILE` stays off: it would allow pushing state after the shade opens, but it also makes the
framework persist state between listens, and freshness is the entire point of this fix.

## 2. `BulkFreezeRunner` — the shared seam

A Koin `@Single` owning its own `CoroutineScope(SupervisorJob() + io)`, mirroring
`FreezerShortcutManager:46`.

```kotlin
enum class BulkOp { FREEZE, UNFREEZE }

data class BulkResult(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
) { val unresolved: Int get() = total - succeeded - failed }

class BulkFreezeRunner {
    /** Candidate count for the current op, `null` until the first sweep. Drives the tile state. */
    val freezableCount: StateFlow<Int?>
    val lastResult: StateFlow<BulkResult?>
    val isRunning: StateFlow<Boolean>

    /** Re-runs the PackageManager sweep and republishes [freezableCount]. */
    suspend fun refreshCandidates(op: BulkOp)
    fun launch(op: BulkOp)
    fun consumeResult()
}
```

`freezableCount` is what phase 1 of the tile paint reads synchronously (`.value`, possibly `null` on
a cold process) and what phase 2 republishes after `refreshCandidates`. The tile holds no cached
count of its own.

`FreezerTileService.onClick()` becomes `bulkFreezeRunner.launch(BulkOp.FREEZE)` and returns. That
deletes the static `appScope` and the retention it caused: the runner holds no `TileService`, no
service `Context`, no `getString`/`resources` capture.

**The unbounded prefix largely evaporates.** The three
`isRootAvailable()/isShizukuAvailable()/isDhizukuAvailable()` calls are the part that can park on the
known Odin `MainShell.get` hang. `PrivilegeManager` already probes all three in the background and
caches the answer in a `StateFlow`, so both the tile and the runner read `state.value` — instant,
non-blocking, no shell. What remains (`getAllPackageNames()`, the prefs read, the batch) goes under
one deadline covering the whole operation.

**Three result buckets, not two.** `unresolved` means "never started, or still running past the
deadline". Counting is incremental, so a deadline **abandons and reports what it knows**. "Froze 12
of 15, 3 didn't finish" is true; "3 failed" is not.

**Concurrency unifies the two call sites**, which currently disagree (tile: unbounded fan-out;
shortcut: strictly sequential). Both move to `Semaphore(5)`, matching `AutoFreezeManager.kt:106`.

**Op-aware filtering.** `FREEZE` targets `isActive` apps; `UNFREEZE` targets `isFrozen` ones. The
tile only ever passes `FREEZE`.

**Deleted:** `FreezerShortcutManager.isFrozen` (`:241-247`), an inline re-implementation of the
domain predicate. Replaced by `AppFreezeStateReader` (`@Single`, wraps the already-injectable
`PackageManager`, returns `FROZEN | ACTIVE | ABSENT`), which is also the fake seam for unit tests.

`runBulk` keeps its pinned-shortcut icon refresh, driven off the succeeded packages rather than
refreshing packages whose freeze may have failed.

## 3. Reporting the result

**In memory, in the runner.** `lastResult` is a `StateFlow`, not persisted. The runner is a
`@Single`, so results survive service destruction (the common case, shade collapse); they die only
with the process. Replaying a stale count from a process that died days ago is worse than
re-deriving real state on the next listen. Self-healing beats stale.

**Consume-once.** The tile collects `lastResult` in `onStartListening`, paints it into the subtitle,
and clears it — otherwise the next shade-open still reads "Froze 12 apps" when the subtitle's job is
"N apps to freeze".

**The two synchronous early-returns lose their Toasts and gain no replacement.** Once the tile state
is truthful, both conditions are on screen before the tap: no privilege → `UNAVAILABLE`, and AOSP
will not deliver the click; nothing to freeze → `INACTIVE` with "No apps to freeze". The only tap
that can reach those branches lands inside the unknown window, and the correct response is to
repaint the tile — the tile visibly changing under the finger *is* the feedback.
`TileService.showDialog()` (API 24+, no permission, collapses the shade) is the escalation if device
testing says that feels dead; it is not shipped speculatively. It must never be used for the async
result, because the tile token may be dead by then.

**Notification, posted by the runner** (which has the app `Context`; the tile never touches
notifications). Gated hard:

```kotlin
val canNotify = NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
    nm.getNotificationChannel(CHANNEL_BULK_RESULT)?.importance != IMPORTANCE_NONE
```

One channel at `IMPORTANCE_DEFAULT` — `LOW` is stripped of any peek by SystemUI's
`PeekNotImportantSuppressor`, `HIGH` is dishonest for a routine result. Fixed notification id so
repeat taps replace instead of stacking; `setAutoCancel`, `setOnlyAlertOnce`,
`setTimeoutAfter(10s)`, `setShowBadge(false)`, `CATEGORY_STATUS`, content intent into Home → Freezer.

This is one code path across all API levels: `areNotificationsEnabled()` is backed by the permission
on 33+ and by the user's notification toggle on 28-32. Only the Settings request row needs
`SDK_INT >= 33`.

### Visibility, and why the notification is worth having

A notification posted while the shade is open lands as a row in the shade the user is already
looking at, plus the normal sound/haptic — `buzzBeepBlinkLocked` never reads shade state. On Android
16 scene-container/dual-shade with QS expanded it additionally pins as a floating heads-up
(`pin |= mIsQsExpanded` in `HeadsUpManagerImpl`). For any multi-second batch the shade is usually
already collapsed at post time, giving a normal heads-up.

### Why the permission is requested, not self-granted (D6)

`pm grant` hard-fails on a permission the package does not declare, so
`<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` is required either way.
Once that line exists, the ordinary `ActivityResultContracts.RequestPermission()` dialog grants
**exactly the same capability**. The self-grant buys one tap and costs:

- **Dhizuku cannot do it at all.** `DhizukuAPI.newProcess` forks in Dhizuku's own app uid, not
  shell/root → `SecurityException`. The DPM alternative `setPermissionGrantState(GRANTED)` works but
  sets `FLAG_PERMISSION_POLICY_FIXED`, removing the user's ability to ever silence Thor — and on the
  non-coexistence branch returns `true` even when it granted nothing.
- A `--user` footgun on the other two gateways (see the follow-up in §6).
- Play Device-and-Network-Abuse exposure on the `store` flavor, trivially detectable statically.
  *(Policy read: medium confidence — judgment, not a quotable per-permission rule.)*
- It cuts against Thor's own thesis: an app about giving users control over apps should not take
  control from its user.

It cannot be lazy in any case — a `TileService` cannot show a runtime-permission dialog, so the grant
lives in Settings regardless of mechanism. The request is a row in the existing
`// ── PERMISSIONS ──` section of `SettingsScreen`.

**Corollary:** the tile must be fully functional and self-reporting with the permission denied
forever. §3's tile surface is unconditional; the notification is purely additive.

### Strings

Deliberately additive. `tile_freeze_success` and `tile_freeze_partial_failure` are **shared** with
`SettingsViewModel`, `AppListViewModel` and `FreezeLoggerDialog`, so rewording them has blast radius
outside this change. Both are reused as-is for the all-succeeded and some-failed cases. New:

- `tile_freeze_incomplete` — "Froze %1$d of %2$d — %3$d didn't finish"
- `channel_bulk_result_name` — the channel label

Two new strings × ar/es/fr/zh. Nothing existing is reworded.

### The shortcut path is fixed for free

`FreezerShortcutManager.runBulk` reports **nothing** today: it discards every `Result`, and
`FreezerLaunchActivity` finishes immediately. Delegating to the runner gives it the same
notification. `FreezerLaunchActivity`'s own Toasts (`:133-134`) keep working — a resumed Activity is
not subject to `checkCanEnqueueToast` — and stay.

## 4. Lifetime, deadline, cancellation

**The deadline is restructured, not just moved:**

```kotlin
val job = scope.launch { /* batch, incrementing atomic counters */ }
val finished = withTimeoutOrNull(DEADLINE) { job.join() } != null
if (!finished) job.cancel()          // best-effort
report(counters.snapshot())          // report what we know, now
```

`job` is a child of the runner's scope, not of the timeout block, so the timeout cancels the
`join()` — which *is* cancellable — and returns immediately. Any op still blocked in the shell
finishes harmlessly in the background: freezing is idempotent and the next refresh shows the truth.

**Counters are atomic** (five concurrent workers under the semaphore) and increment as each op
resolves. That is what makes `unresolved` honest rather than retroactively guessed.

**Cancellation hygiene — the same trap as PR #278.** `ensureActive()` before taking each package off
the queue, and no `runCatching` around the ops: `CancellationException` is an `Exception` in Kotlin,
so a broad catch silently swallows it and defeats the `ensureActive()` entirely.

**Double-tap guard.** One in-flight job per op; a second `launch` while it runs is a no-op. The tile
surfaces `isRunning` as a "Freezing…" subtitle, so a tap that starts work looks different from a tap
that did nothing.

**Nothing calls back into the service.** `if (scope != null) refreshTile()` is deleted; the tile
observes `lastResult`/`isRunning` in `onStartListening`. For the record, the comment justifying that
guard ("touching `qsTile.updateTile()` … can throw inside the framework") is **factually wrong** —
`updateTile()` catches `RemoteException` internally, and `refreshTile()` already null-checks
`qsTile`. The guard was harmless; its stated reason was not, and the follow-up doc repeats the claim
as something that "is correct and should survive".

**Stale comments corrected in this change:** `FreezerTileService.kt:48-50`, `:101-103`, `:136-139`.

### Process survival

No foreground service, and not merely because it is unnecessary: on Android 12/13/14 an FGS started
from `onClick` throws `ForegroundServiceStartNotAllowedException` — SystemUI is a persistent client,
so `canBindingClientStartFgsLocked` skips it; only Android 15+ temp-allowlists the tile (15 s).
WorkManager does not help either: expedited work *is* an FGS on API 28-30, and `androidx.work` is not
in the version catalog.

A ContentProvider cannot help. A provider raises the **host's** adj only for a **foreign** client;
a self-call never reaches AMS (`ActivityThread.acquireProvider` hits the in-process map first, and
`OomAdjuster` contains a literal `if (client == app) { return false; }`). Thor's exported
`FreezerBridgeProvider` genuinely exercises that mechanism — its client is the launcher process —
which is why the intuition felt right. It is not self-applicable. A declared-but-unqueried provider
confers exactly zero.

**Mitigation is idempotence**, not process protection: `pm disable-user`/`pm suspend` on an
already-frozen app is a no-op, so a batch killed mid-run is recoverable by tapping again, and the
tile's re-derived state shows what is left. OEM killer daemons (MIUI/ColorOS/EMUI/OneUI) ignore AOSP
adj policy and are unquantifiable; this is the answer for them too.

## 5. Verification

### Unit tests — zero new dependencies

The project has only `testImplementation(libs.junit)`, so the three things that were wrong sit
behind pure functions:

| Function | Tests |
|---|---|
| `tileVisualFor(privilege, freezableCount)` → sealed `TileVisual` | `!isReady` → INACTIVE+checking, **never UNAVAILABLE**; no privilege → UNAVAILABLE; privileged + 0 → INACTIVE; privileged + N → ACTIVE |
| `freezableCandidates(watchlist, stateOf)` | all-frozen → empty (the reported bug); mixed → only active; `ABSENT` excluded; `UNFREEZE` inverts |
| `bulkResultMessage(result)` | all-succeeded / some-failed / some-unresolved; the timeout case must **not** report N failures |

`stateOf` is a lambda, so the `PackageManager` reader is faked with a `Map`. `TileVisual` is a sealed
type mapped to `Tile.STATE_*` at the service edge, keeping framework types out of tested code —
consistent with the A3 domain-purity work. This does not block on the
`viewmodel-behavior-tests.md` follow-up.

### Device verification, across root / Shizuku / Dhizuku

1. Freeze every watchlist app → reopen the shade → tile is dim, "No apps to freeze".
2. Revoke privilege → tile greyed and untappable.
3. Tap → collapse the shade immediately → result still reported.
4. Notification with permission granted, denied, and with the shade open vs. collapsed.
5. The Settings permission row on API 33+, and its absence below.
6. The Freeze-all launcher shortcut now reports a result where it previously reported nothing.

### Acceptance

- Tile state derives from real per-app state and goes `INACTIVE` once everything is frozen.
- Every completion path reports — subtitle unconditionally, notification when permitted — from both
  the tile and the shortcut.
- No `FreezerTileService` instance reachable after shade collapse (LeakCanary or heap dump).
- A wedged shell yields a bounded, honest report within the deadline, with `unresolved` counted
  separately from `failed`.
- `foss` + `store` release lint clean; unit tests green under `--rerun-tasks`.

## 6. Files

**New:**

| Path | Contents |
|---|---|
| `domain/model/BulkFreeze.kt` | `BulkOp`, `BulkResult` — pure, no Android types |
| `domain/model/FreezeState.kt` | `FROZEN \| ACTIVE \| ABSENT` + `freezableCandidates(watchlist, op, stateOf)` — pure, unit-tested |
| `presentation/tile/TileVisual.kt` | sealed `TileVisual` + `tileVisualFor(privilege, freezableCount, isRunning)` — pure, unit-tested |
| `presentation/tile/BulkResultText.kt` | `bulkResultMessage(result): UiText` — pure, unit-tested |
| `data/freezer/AppFreezeStateReader.kt` | `@Single`, wraps the injectable `PackageManager` |
| `data/freezer/BulkFreezeRunner.kt` | `@Single`, owns the scope, semaphore, deadline, counters |
| `data/freezer/BulkResultNotifier.kt` | `@Single`, channel creation + gated post |

`bulkResultMessage` returns `UiText` (`util/UiText.kt`), the established project idiom, with
`PluralsResource` for the success case. `UiText` subclasses implement `equals` without touching
`Context`, so the function is assertable in a plain JVM test.

`tileVisualFor` takes `isRunning` so the "Freezing…" subtitle is decided by the same pure function
rather than branched at the call site.

**Modified:** `presentation/tile/FreezerTileService.kt` (rewritten around the runner);
`data/launcher/FreezerShortcutManager.kt` (`runBulk` delegates; `isFrozen` deleted);
`presentation/settings/SettingsScreen.kt` + its ViewModel (permission row);
`app/src/main/AndroidManifest.xml` (`<uses-permission>`);
`res/values/strings.xml` + `values-{ar,es,fr,zh-rCN}/strings.xml` (two new strings).

**Deleted:** `docs/follow-ups/freezer-tile-service-rework.md`, superseded by this spec.

## 7. Scope boundaries

**Out of scope:** unfreeze from the tile (D1); `ACTIVE_TILE`/`TOGGLEABLE_TILE`; foreground service,
WorkManager, or ContentProvider for batch survival (D7); self-granting `POST_NOTIFICATIONS` (D6);
pruning uninstalled packages from the watchlist; `FreezerShortcutPinnedReceiver.kt:25`, whose Toast
hits the same suppression class but is a different trigger with a different fix.

**Follow-ups to file separately** (found during research, real but unrelated):

- `SystemGateway.grantPermission` emits no `--user`, while every other Shizuku command does
  (`Shizuku.kt:63/94/162`). `PackageManagerShellCommand.runGrantRevokePermission` initialises
  `userId = UserHandle.USER_SYSTEM`, so this is a latent bug in the Permission Manager screen today.
- Odin's `MainShell` computes `status` once at construction and caches it, so `isRoot` is stale for
  the process lifetime. Root *revocation* after a grant is invisible until restart.

Work lands on branch `fix/freezer-tile-rework`; PR targets `dev`.
