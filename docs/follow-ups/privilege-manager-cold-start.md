# Follow-up: measure `PrivilegeManager`'s cold-start cost

**Status:** Instrumentation landed (debug-only); the measurement itself is on the owner — it needs a
real device in each privilege configuration, which CI cannot provide.
**Severity:** Unknown, which is the point. Suspected minor-to-moderate on non-rooted and
root-denied devices.
**Effort:** ~30 min per configuration to run; the analysis is a one-liner.
**Raised by:** follow-up #22, approved "but it needs proper checks".
**Related:** [`perfetto-trace-pass.md`](perfetto-trace-pass.md) is the umbrella device session that
lists this as one of its items. This file is the protocol for *that* item; run them in one sitting if
you are doing both. Perfetto's `android.log` data source records logcat into the trace, so the
`ThorPrivPerf` lines land on the same timeline as the frame data — no separate capture needed.

Files: `app/src/main/java/com/valhalla/thor/util/PrivilegeProbeTrace.kt`,
`app/src/main/java/com/valhalla/thor/data/manager/PrivilegeManager.kt:59 (init)`,
`app/src/main/java/com/valhalla/thor/data/manager/PrivilegeManager.kt:103 (availabilityFlow)`

## Why this is on the critical path

`AppListViewModel` and `FreezerViewModel` hold their loader on `isLoading = !priv.isReady`
(`AppListViewModel.kt:227`, `FreezerViewModel.kt:123`) so freeze/unfreeze controls never flash
disabled and then enable themselves. That is the right behaviour, and it has a price: **the probe
run is in front of the first usable frame of two of the four tabs**. Nobody has ever measured it.

The root probe is the one with teeth. It goes `SystemRepositoryImpl.isRootAvailable()` →
`RootSystemGateway.isRootAvailable()` → Odin `RealShellRepository.isRootGranted()`, which is
`withTimeoutOrNull(10_000) { getShellAwait().isRoot }` — i.e. shell init, bounded only by a 10 s
hang guard, not by any UI budget. And that timeout is cooperative: it cannot unblock a worker
already parked in a blocking `shellCheck().get()` / `@Synchronized MainShell.get()`. The
user-visible hang was fixed; the hang *class* was not removed at the thread level. If it is still
reachable, this instrumentation is what will show it.

## What is instrumented

Two lines, one tag, monotonic clock (`SystemClock.elapsedRealtime()`; a wall-clock read straddling
an NTP step would yield a negative duration that still looks like data).

```
D ThorPrivPerf: probe total=412ms root=408ms/true shizuku=3ms/false dhizuku=1ms/false
D ThorPrivPerf: ready sinceProcessStart=1180ms active=ROOT
```

- **`probe`** — one line per probe run (cold start, every `refresh()`, every Shizuku binder or
  permission event). `total` is the whole run including the `async` dispatch; each tier is that
  probe's own duration and its result. **Tier times overlap** — the three probes run concurrently
  in `availabilityFlow` — so they do not sum to `total`. The tier closest to `total` is the one
  holding the run up. A `total` well above every tier means dispatch or thread starvation, not any
  one probe.
- **`ready`** — emitted **once per process**, the first time `PrivilegeState.isReady` is published.
  This is the number the loaders actually wait on. It is *not* `probe total`: `isReady` needs the
  `combine` of the probe run **and** the DataStore preference read, and it is preceded by process
  init, `Bypass.init`/`prepareThor`, Koin start, and the first ViewModel resolution
  (`PrivilegeManager` is a lazy `@Single`, so the probe does not begin until something injects it).
  A fast `probe total` with a slow `ready` means the cost is somewhere other than the probes.

The instrumentation is compiled out of release builds: `PrivilegeProbeTrace.start()` is `inline`
and returns `null` when `BuildConfig.DEBUG` is false (AGP emits a literal `false` there, so it is a
compile-time constant), which folds `timeProbe` down to a bare call to the probe. It adds no
suspension point, no dispatcher hop and no extra probe in any build — the timed code is the shipped
code.

## Running it

Debug package `com.valhalla.thor.debug`, launcher activity `com.valhalla.thor.HomeActivity`
(the class keeps the base package even though the application id gains `.debug`).

```bash
PKG=com.valhalla.thor.debug
CMP=$PKG/com.valhalla.thor.HomeActivity
```

### One cold run

```bash
adb shell am force-stop "$PKG"        # kills the process — this is the one that matters
adb shell am kill "$PKG"              # no-op if already dead; belt and braces
adb logcat -c
adb shell am start -W -n "$CMP"
sleep 6                               # let the probe run land before you read logcat
adb logcat -d ThorPrivPerf:D '*:S'    # shorthand: adb logcat -d -s ThorPrivPerf
```

Clear the task from recents by hand at least once per configuration (there is no reliable adb
equivalent) so no OEM ROM can restore saved state; `adb shell dumpsys activity recents | grep -i thor`
confirms none is left. `force-stop` already kills the process, so this is belt and braces — but it is
the failure mode that produces a suspiciously fast "cold" run.

### N runs (use this one)

```bash
PKG=com.valhalla.thor.debug; CMP=$PKG/com.valhalla.thor.HomeActivity; N=${N:-12}
adb logcat -c
for i in $(seq 1 "$N"); do
  adb shell am force-stop "$PKG"; adb shell am kill "$PKG"; sleep 2
  adb shell am start -W -n "$CMP" | grep -E 'LaunchState|TotalTime|WaitTime'
  sleep 6
done
adb logcat -d ThorPrivPerf:D '*:S'
```

Then, for any of the four numbers (`probe total`, each tier, `ready sinceProcessStart`):

```bash
adb logcat -d ThorPrivPerf:D '*:S' | grep -o 'total=[0-9]*' | cut -d= -f2 | sort -n | \
awk '{v[NR]=$1} END {
  if (NR==0) {print "no samples"; exit}
  m = (NR%2) ? v[(NR+1)/2] : (v[NR/2]+v[NR/2+1])/2
  p = int(0.9*NR); if (p < 0.9*NR) p++
  printf "n=%d median=%.0f p90=%d max=%d\n", NR, m, v[p], v[NR]
}'
```

Swap `total=` for `root=`, `shizuku=`, `dhizuku=` or `sinceProcessStart=` to get each series. `sort -n`
before the awk is required — the p90 is nearest-rank on sorted input.

## Proper checks

These are the parts that make the number mean something. Skipping any one of them produces a
plausible-looking number that is wrong.

### 1. The start must actually be cold

A warm start measures the Activity, not the probe — the process is already up, `PrivilegeManager`
is already constructed, `isReady` is already true, and the probe cost is exactly zero. Confirm,
per run, at least one of:

- `am start -W` prints **`LaunchState: COLD`** (Android 10+). This is the cheapest check; it is in
  the loop output above.
- logcat shows **`ActivityManager: Start proc <pid>:com.valhalla.thor.debug/...`** — the system only
  logs that when it forks a new process.
- `adb shell pidof com.valhalla.thor.debug` prints nothing *before* the launch.

Also: **a `ready` line only appears once per process.** If a run produces no `ready` line, that run
was not cold — a silent, self-checking property, so scan for `ready` count == N before analysing.

`am force-stop` is what kills the process; `am kill` alone only reaps background processes and does
nothing to a foregrounded app. Swiping the task out of recents matters separately: a lingering task
lets some OEM ROMs restore from saved state, which is a warm start wearing a cold hat.

### 2. Discard the warm-up runs, and say so

- Discard the **first launch after every install**. `Bypass.init(this)` persists its core-oj dex
  scan to disk on first use; the first launch pays for the scan, every later one reads the cache.
- The app is JIT-only until ART has profiled it. Either discard the first 2–3 runs, or normalize
  with `adb shell cmd package compile -m speed -f com.valhalla.thor.debug` before the loop — and
  then do the same for every configuration, or the comparison is meaningless.
- `echo 3 > /proc/sys/vm/drop_caches` between runs (root only) makes results pessimistic but
  reproducible. Optional; be consistent. Do not do it for half the runs.

### 3. N ≥ 10, report median and p90 — never a mean, never one run

Cold-start latency is long-tailed: a mean is dragged by the tail without describing it, and a single
run is a sample of the tail or of the mode with no way to tell which. **N = 10 minimum, 12–20
preferred.** Report `n / median / p90 / max` for every series. The awk one-liner above prints
exactly that.

Read the *shape* too. **If p90 is more than ~3× the median, the cost is a contended or blocking
dependency, not steady work** — that is the signature of the shell-init serialization, and it is a
finding in its own right even if the median looks fine.

### 4. Measure every privilege configuration, especially the ones without privilege

The denied/absent paths are where a blocking probe hurts most and they are the easy ones to forget —
a probe that has nothing to find still has to discover that.

| # | Configuration | How to set it up | What you are looking for |
|---|---|---|---|
| 1 | Root granted | Magisk/KernelSU → allow `com.valhalla.thor.debug`. **Grant it before measuring**; the superuser prompt is a human in the loop and will wreck the run | baseline; `root=` should dominate `total` |
| 2 | Root denied | Magisk per-app policy → Deny | does `su` exit fast, or do we wait? |
| 3 | No `su` at all | any non-rooted device/emulator | the majority configuration. Odin still builds a fallback `sh` shell here — that is a real cost for zero benefit |
| 4 | Shizuku running + granted | `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`, then grant in-app | `shizuku=` is two binder IPCs (`checkSelfPermission` + `pingBinder`); should be single-digit ms |
| 5 | Shizuku installed, not running | reboot without starting Shizuku (or kill `shizuku_server`) | dead-binder path must not block |
| 6 | Shizuku not installed | uninstall it | `pingBinder` on a missing service |
| 7 | Dhizuku as device owner | Dhizuku set up + Thor authorised | `dhizuku=` is blocking binder IPC via `DhizukuAPI` |
| 8 | None of the three | non-rooted, no Shizuku, no Dhizuku | the floor. Should be the **fastest** configuration |

Configuration 1 vs 3 vs 8 is the whole question. **If #8 is not clearly the fastest, that is a bad
result on its own**, regardless of absolute numbers: it means we pay most where we gain nothing, on
the devices least able to afford it.

The debug application id is a **separate app** as far as Magisk, Shizuku and Dhizuku are concerned —
its grants are not the release app's. Set each one up explicitly rather than assuming it inherited.

### 5. `am start -W` TotalTime is not time-to-`isReady`

`TotalTime` is time to the **first frame**. `HomeActivity` calls `installSplashScreen()` with no
`setKeepOnScreenCondition`, so the splash dismisses as soon as the first Compose frame is ready —
and that frame is drawn with `isLoading` still true. **`TotalTime` therefore ends before the privilege
state exists.** Report both and read the gap:

- `WaitTime` — the whole ActivityManager-side wait, including pausing whatever was in front.
- `TotalTime` — launch start → first frame, **including** process creation, so its clock starts
  slightly *before* the fork. Equivalent to logcat's `Displayed ...: +XXXms`.
- `ready sinceProcessStart` — fork → privilege state published, i.e. the loaders release. Anchored on
  `Process.getStartElapsedRealtime()`, which is later than where `TotalTime` starts.

The user-facing number is the **spinner window**: first frame → loaders released, on the Apps and
Freezer tabs. Two ways to get it, in order of preference:

1. **Exact** — both events are already in logcat on one timeline; subtract their timestamps:
   ```bash
   adb logcat -d -v epoch ActivityTaskManager:I ActivityManager:I ThorPrivPerf:D '*:S' \
     | grep -E 'Displayed com\.valhalla\.thor\.debug|ThorPrivPerf: ready'
   ```
2. **Lower bound** — `ready sinceProcessStart − TotalTime`. The two clocks do not start at the same
   instant (see above), so this *under*-reports the window by the pre-fork slice. Fine to act on: if
   the lower bound is already bad, it is bad.

`TotalTime` alone will look fine and tell you nothing — it ends before the privilege state exists.

### 6. Debug-build numbers are not release numbers

The instrumentation only exists in debug, and debug is `isMinifyEnabled = false` and `debuggable`,
which makes it slower than what ships. Use these numbers **to compare tiers against each other and
configurations against each other** — those comparisons are valid because everything shifts
together. Do **not** quote `ready sinceProcessStart` as Thor's cold-start figure. For the absolute
figure, run `am start -W` against a release build, where the trace prints nothing.

### 7. Known confound: `HomeActivity` probes root independently

`HomeActivity.onResume()` calls `systemRepository.isRootAvailable()` directly
(`HomeActivity.kt:108`), racing `PrivilegeManager`'s own root probe. Both land on Odin's cached,
synchronized shell: **whichever gets there first pays for shell init and the other returns almost
instantly.** So a `root=4ms` line does not prove the root probe is cheap — it may mean `onResume`
already paid, off-trace. Cross-check against `ready sinceProcessStart`; if `ready` is large while
every tier is small, the cost is real and is being attributed to the untraced caller. (The auto-freeze
path also probes all three, but only from its screen-off receiver, so it is not on the start path.)

## What a bad result looks like

Android's own startup guidance treats a cold start over **~500 ms** as needing attention. That 500 ms
is the whole budget and the probe is one of several claimants — process init, `Bypass` dex work,
Koin graph construction, Room open, the first `PackageManager` query. A defensible split gives the
privilege probe **at most ~30%**:

| Signal | Good | Investigate | Bad |
|---|---|---|---|
| `probe total` p90 | ≤ 150 ms | 150–500 ms | > 500 ms |
| slowest tier p90 | ≤ 150 ms | 150–500 ms | > 500 ms |
| `ready sinceProcessStart` p90 | ≤ 400 ms | 400–800 ms | > 800 ms |
| spinner window (first frame → `ready`) | ≤ 200 ms | 200–500 ms | > 500 ms |
| p90 ÷ median, any series | ≤ 2 | 2–3 | > 3 (blocking/contended, not slow) |
| config #8 (nothing available) | fastest config | — | slower than config #1 |

Rationale for the spinner window in particular: a spinner visible for under ~200 ms reads as "the
screen appeared"; past ~500 ms it reads as "the app is slow", and the user is looking at *disabled*
freeze controls while they wait, which is the failure the `isReady` gate was introduced to prevent
in the first place.

**Any tier reporting ≈10 000 ms is not a slow probe — it is Odin's `SHELL_INIT_TIMEOUT_MS` (the
`withTimeoutOrNull` in `RealShellRepository.isRootGranted`) firing, i.e. the hang class described
above, reached.** That is a P1 regardless of the median. Note that the trace *under*-reports it: the
10 s timeout is cooperative, so it unblocks the coroutine while the worker thread stays parked in
`ShellImpl`'s `check.get(builder.timeout, SECONDS)` — 20 s by default, and `ThorShellConfig` does not
override it — or on `MainShell.get()`'s monitor. Capture a thread dump (`adb shell kill -3 <pid>`,
then `adb logcat`) while it is happening so the parked stack is on the record; the log line alone
cannot show it.

## What to do if it is slow

Levers, not decisions. **This follow-up is measurement only — do not implement any of these without
numbers first.**

1. **Render optimistically and correct on arrival — cheapest, changes no probe.** `HomeViewModel`
   already does exactly this (`HomeViewModel.kt:92-98`: before `isReady`, fall back to the
   *preferred* mode and let the reactive state correct it). `AppListViewModel` and `FreezerViewModel`
   hold a loader instead. Making them match Home removes the probe from the critical path without
   touching the probe. The cost is the flash-of-disabled-controls the gate was added to prevent, so
   this is a UX trade, not a free win — decide it deliberately.
2. **Persist the last-known privilege state across launches** and start from it, correcting on
   arrival. Do **not** design this in isolation: see
   [`odin-root-availability-cache.md`](odin-root-availability-cache.md), which documents the opposite
   staleness problem (Odin already caches root availability for the whole process lifetime and cannot
   observe a revocation). A cross-launch cache compounds that — it would need the invalidation hook
   that follow-up proposes, so the two land together or not at all.
3. **Start the probe earlier, not faster.** `PrivilegeManager` is a lazy `@Single`, so the probe
   begins when the first ViewModel resolves it — after Activity creation. Warming it in
   `ThorApplication.onCreate` would overlap it with inflation instead of queueing it behind. The
   `ready sinceProcessStart` − `probe total` gap tells you whether this is even the problem before
   you spend anything on it.
4. **Give the *first* probe a UI-sized deadline.** Odin's 10 s is a hang guard, not a UI budget. A
   shorter first-pass deadline would have to resolve to **"still unknown, re-probe"** and keep
   `isReady` false — resolving it to `false` would flash the no-privilege UI, which is precisely
   what `isReady` exists to prevent.
5. **Parallelising the probes is already done** — `availabilityFlow` runs all three in `async` and
   waits `max`, not the sum. Do not re-file it. The remaining serialization is *inside* Odin
   (`@Synchronized MainShell.get()`), so two callers racing it (see confound 7) serialize regardless
   of what Thor does.

## Acceptance

- The table in "What a bad result looks like" is filled in for **each** of the 8 configurations, with
  `n`, median, p90 and max — not a single number per cell.
- Every run in every series is confirmed cold (`LaunchState: COLD`, and one `ready` line per run).
- A one-line verdict per configuration: within budget / investigate / bad, and if any is "bad", which
  lever above it points at.
- The instrumentation stays in — it is free in release and this measurement will need repeating
  after any change to the probe chain or to Odin's shell init.
