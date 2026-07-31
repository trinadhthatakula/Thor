# Follow-up: measure `PrivilegeManager`'s cold-start cost

**Status:** **Configurations 1, 2, 4, 5, 6 and 8 are measured and all PASS, on release-shaped builds,
as of 2026-07-31.** Config 1 (root granted, physical hardware) —
see [the re-measurement](#2026-07-31--config-1-re-measured-on-v1931-the-defect-is-gone): root probe
57 ms median, bimodality gone, **spinner window zero — privilege state is published ~107 ms *before*
the first frame, in 12 of 12 cold starts.** The projected "~579 ms of visible spinner on ~60% of
rooted cold starts" was measured directly and **does not exist**. Config 2 (root denied, same
physical device) — see
[the denial series](#2026-07-31-later-still--configuration-2-root-denied-su-exits-fast-and-the-fallback-chain-works):
a denied `su` costs 51.5 ms, i.e. **no more than a granted one** — it exits fast, nowhere near Odin's
10 s timeout — and the chain fails over to `active=SHIZUKU` in 12 of 12 runs. Configs 4, 5, 6 and 8
(Pixel 10 Pro Fold, Android 17) — see
[the floor series](#2026-07-31-later--configs-4-5-and-8-on-a-pixel-10-pro-fold--android-17-the-floor-and-a-drift-trap):
the whole probe is the root check even when there is no `su` (30–39 ms), Shizuku and Dhizuku cost
0 ms median, and the spinner window is again zero — **but in ~25% of runs it is zero with no headroom
left.** **Configuration 7 (Dhizuku device owner) is the only one left unmeasured**, and needs an
artifact plus `dpm set-device-owner` on an account-free device.
**Severity:** ~~Suspected moderate on rooted devices~~ → **none observed on any configuration
measured so far.** No lever from "What to do if it is slow" needs pulling. The severity claim below
was based on the v1.93.0 numbers and is kept only as the "before" record.
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

```text
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
# Poll for the probe to land — do NOT sleep a fixed interval. The root probe is bounded by
# Odin's 10 s SHELL_INIT_TIMEOUT_MS, so any fixed wait shorter than that force-stops the
# process mid-probe and silently drops the slowest runs: precisely the samples this
# measurement exists to catch. 15 s ceiling = the 10 s guard plus margin.
for _ in $(seq 1 30); do
  adb logcat -d ThorPrivPerf:D '*:S' | grep -q 'ready sinceProcessStart' && break
  sleep 0.5
done
adb logcat -d ThorPrivPerf:D '*:S'    # shorthand: adb logcat -d -s ThorPrivPerf
```

If the loop falls through without a `ready` line, that is **data, not a failed run** — it means the
probe exceeded 10 s and the hang class in §1 is still reachable. Record it; do not retry it away.

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
  # Wait for *this* run's ready line by counting, not by sleeping. logcat is deliberately not
  # cleared between iterations, so a bare `grep -q` would match the previous run's line and
  # return instantly. Same reasoning as the single run: never wait less than Odin's 10 s guard.
  for _ in $(seq 1 30); do
    [ "$(adb logcat -d ThorPrivPerf:D '*:S' | grep -c 'ready sinceProcessStart')" -ge "$i" ] && break
    sleep 0.5
  done
done
adb logcat -d ThorPrivPerf:D '*:S'
```

Check `n` in the summary below against `N` before believing any percentile. A short count means
runs timed out past 10 s and were dropped — which biases the result *fast*, in the direction that
makes the problem look solved.

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
| 2 | Root denied | Magisk / KernelSU Next per-app policy → Deny. Deny **both** app ids (`com.valhalla.thor` *and* `.debug`) or the release-shaped build is still granted | does `su` exit fast, or do we wait? → **measured 2026-07-31: it exits fast, 51.5 ms, and the chain fails over to Shizuku** |
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

⚠️ **The "grant it before measuring" warning in row 1 applies to Shizuku too, and it bites harder
because it does not look like a prompt.** The first launch of an app id that has never been granted
`moe.shizuku.manager.permission.API_V23` raises Shizuku's approval dialog, and until someone taps it
the probe records `shizuku=0ms/false` — a *successful, fast* check that happens to return false. On a
configuration where root is unavailable that publishes **`active=NONE`**, which reads exactly like a
privilege-detection bug and is not one. Observed once on 2026-07-31 and initially mis-diagnosed here
as a binder-delivery race; it did not reproduce in 12 steady-state runs, 5 first-launch-after-install
cycles, or 3 cross-build reinstall cycles, because by then the grant existed. **Before any series:
launch once, clear every dialog, confirm `granted=true` in
`dumpsys package <pkg> | sed -n '/runtime permissions:/,/^ *$/p'`, and only then start measuring.**
Note that `dumpsys` read *after* someone taps approve looks identical to one that was never blocked —
the grant state cannot tell you a dialog happened, so the warm-up runs are what protect you.

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

### 7. ~~Known confound: `HomeActivity` probes root independently~~ — REMOVED 2026-07-30

`HomeActivity.onResume()` used to call `systemRepository.isRootAvailable()` directly, racing
`PrivilegeManager`'s own root probe. Both landed on Odin's cached, synchronized shell: **whichever
got there first paid for shell init and the other returned almost instantly** — which is precisely
the 62-85 ms / 627-789 ms split with nothing in between that configuration 1 measured.

It now reads `privilegeManager.state.first { it.isReady }` instead, so there is exactly one root
probe per cold start and it is the traced one. (The auto-freeze path also probes all three, but only
from its screen-off receiver, so it was never on the start path.)

⚠️ ~~**Expect this to make the numbers look *worse*, and do not treat that as a regression.** The
duplicate was not adding work — it was *stealing* it. With one caller left, the distribution should
collapse to **unimodal, near the old slow mode**.~~

**REFUTED by measurement, 2026-07-31.** That prediction was wrong, and this section said to say so:
*"If the fast mode survives the change, the race was not the cause and this section is wrong."* The
fast mode did not merely survive — **it became universal.** 12 of 12 cold starts report
`root=` 60–79 ms on debug and 51–73 ms on release-shaped, with nothing above 79 ms in 24 measured
runs. The slow mode (627–789 ms) did not appear once.

So the duplicate probe was not *stealing* work that someone else silently paid — it was **causing**
work that now nobody pays. `probes=1` per cold start is confirmed in the trace, so there is exactly
one caller, and that one caller is fast.

**The mechanism is not established, and this section should not guess at a replacement.** A second
caller merely losing a race on `@Synchronized MainShell.get()` would wait for the winner's ~70 ms of
init, not ~700 ms, so simple monitor contention does not explain the old slow mode either. What the
data supports is only the outcome: with two callers the root probe was bimodal with a 627–789 ms
mode; with one it is uniformly ~57–70 ms. Anyone re-opening this should treat the mechanism as an
open question, not as settled by the fix working.

⚠️ **Two variables changed between the two measurements, not one.** The v1.93.0 numbers were taken
before this change *and* on the previous release; the v1.93.1 numbers after both. Non-probe startup
also improved over the same span (debug first frame 1152 → 881 ms), which no change to the probe
chain can explain. So the ~600 ms improvement in `root=` is **correlated with** removing the
duplicate probe, not proven to be caused by it in isolation. Re-running v1.93.0 with the trace would
settle it; nothing currently depends on the answer.

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

## Measurements taken so far

### 2026-07-31 (later still) — configuration 2, root denied: `su` exits fast, and the fallback chain works

The one configuration that could only be answered on rooted hardware. Owner denied root to both
`com.valhalla.thor` and `com.valhalla.thor.debug` in KernelSU Next, leaving **everything else
identical to configuration 1**: same device `1da5425f` (25053PC47G, Android 16), same APKs (`dev` @
`ec49853e`, `versionCode` 1931), Shizuku 13.7.0 still running (as root) and still granted. One
variable changed. 30 cold starts, all `LaunchState: COLD`, 3 warm-ups discarded, N = 12 per series,
exactly one `probe` line per run.

| Series | first frame | `probe total` | `root=` | `shizuku=` | `dhizuku=` | `ready` | `active=` |
|---|---:|---:|---:|---:|---:|---:|---|
| `storeBenchmark` | 304 ms | 51.5 ms | 51.5 ms | 1 ms | 0 ms | 91.5 ms | `SHIZUKU` 12/12 |
| `storeRelease` (control) | 303.5 ms | — | — | — | — | — | — |

**Verdict: within budget on every signal.** `probe total` p90 70 ms (Good ≤150), `ready` p90 108 ms
(Good ≤400), p90 ÷ median 1.34 on `root=` — unimodal, range 46–70 ms, no slow mode anywhere in 15
runs. **Spinner window negative in 12 of 12, median −103 ms.** `storeBenchmark` 304 ms vs
`storeRelease` 303.5 ms is the third independent confirmation of the benchmark-as-release proxy, this
time to within 0.5 ms.

**The question §4 asks for this row — "does `su` exit fast, or do we wait?" — is answered: it exits
fast.** A denied `su` costs 51.5 ms median. Nothing approaches Odin's 10 s `SHELL_INIT_TIMEOUT_MS`,
and the hang class is not reached on the denied path.

**The fallback chain does what it claims.** `active=SHIZUKU` in 12 of 12 runs: root denied, Shizuku
selected, and the failover costs 1 ms. This is the first direct evidence in this document that
Root → Shizuku → Dhizuku actually fails over under a real denial rather than an absence.

**Denial still leaves the resident `sh`.** Same as the no-`su` case on the Fold: with `su` present
but denied, libsu falls back and the shell survives as a child of the Thor process
(`u0_a647 6393 29694 sh`). The cost of the root probe is paid, and the shell it produces cannot be
used, on both the denied and the absent path.

⚠️ **Not a controlled comparison against configuration 1.** Config 1 was measured earlier the same
day on this device; config 2 is a later series. By this document's own new acceptance rule, ranking
two configurations measured at different times on one machine requires re-measuring the first one
last, which needs the owner to re-grant root. Taken at face value the numbers are
`root=` 51.5 ms denied vs 57 ms granted and `ready` 91.5 ms vs 96 ms — i.e. **denial is not more
expensive than a grant**, which is the useful direction, but the gap is small enough that only the
control would settle it. Both series are tight and unimodal, unlike the Fold, so the drift risk here
is lower — that is a reason to believe it, not a substitute for the control.

### 2026-07-31 (later) — configs 4, 5 and 8 on a Pixel 10 Pro Fold / Android 17: the floor, and a drift trap

**Configuration 8 — the floor — is measured.** Also 4 and 5, and configuration 6 comes free (on a
device with no `su` and no Dhizuku, "Shizuku not installed" *is* "none of the three", so 6 ≡ 8 and
one series answers both).

Device: `Pixel_10_Pro_Fold` AVD, `sdk_gphone16k_arm64`, **Android 17 / API 37** — the first
measurement on Thor's actual `targetSdk`. 4 cores, 4 GB, 16 KB pages, unfolded (`OPENED`). Verified
absent before measuring: no `su` on `PATH`, no `/system/bin/su`, `/system/xbin/su` or `/sbin/su`, no
Magisk/KernelSU manager, `Device Owner Type: -1`. Same APKs as the config-1 series above — `dev` @
`ec49853e`, `versionCode` 1931, so the build is byte-identical across both devices.

60 cold starts. **Every measured run reported `LaunchState: COLD`**, 3 warm-ups discarded per series,
N = 12 measured per series, exactly one `probe` line per run.

| # | Configuration | `active=` | first frame | `probe total` | `root=` | `shizuku=` | `ready` |
|---|---|---|---:|---:|---:|---:|---:|
| 8 | nothing available | `NONE` | 221.5 ms | 35 ms | 34.5 ms | 0 ms | 59.5 ms |
| 5 | Shizuku installed, not running | `NONE` | 208.5 ms | 30 ms | 30 ms | 0 ms | 52.5 ms |
| 4 | Shizuku running + granted | `SHIZUKU` | 224 ms | 38.5 ms | 38.5 ms | 0 ms | 64 ms |
| 8 | `storeRelease` control (no trace) | — | 209 ms | — | — | — | — |

All medians. `dhizuku=` is 0 ms median in every configuration (max 2 ms). `storeRelease` 209 ms vs
`storeBenchmark` 221.5 ms re-confirms the benchmark build as a release proxy on a second device and a
different OS version.

#### ⚠️ The between-configuration comparison is void, and the control is what proves it

The series were run in the order 8 → 5 → 4, and `root=` came out **bimodal** in all of them: a fast
mode at 18–86 ms and a slow mode at 123–168 ms, with a clean ~43 ms gap and nothing in between. The
slow-mode share rose monotonically with run order — 8%, 17%, 33%. Read naively that says
"configuration 4 is the worst".

It says nothing of the sort. **Configuration 8 was then re-measured a fourth time, unchanged, about
ten minutes after the first series:**

| series | run order | slow-mode share | `root=` median (all runs) | `root=` median (fast mode only) |
|---|---|---:|---:|---:|
| config 8 | 1st | **8%** | 34.5 ms | 34.0 ms |
| config 5 | 2nd | 17% | 30 ms | 28.5 ms |
| config 4 | 3rd | 33% | 38.5 ms | 36.0 ms |
| **config 8 (repeat)** | **4th** | **42%** | **52 ms** | **39.0 ms** |

Identical configuration, identical APK, identical device: **8% → 42%.** The bimodality tracks
wall-clock, not configuration. It is host/emulator scheduling drift, and it is larger than every
between-configuration difference in the table above. **Do not rank configurations 4, 5 and 8 against
each other from this session.** Restricted to the fast mode, all four series land in 28–39 ms —
i.e. indistinguishable, which is the honest result.

This also kills a tempting reading of the config-1 bimodality. The same 4.5× fast/slow split appears
here on a device with **no `su` at all**, where no superuser prompt, no grant lookup and no
`HomeActivity` duplicate probe can possibly be involved. Whatever produces it is not specific to
root — and on the physical device the same build showed **no** bimodality across 24 runs (51–79 ms).
The most economical explanation for both is the measurement host, not Thor. §7 stays unexplained;
this narrows what it is *not*.

#### The spinner window is zero — but the slow mode consumes the entire margin

Pooled across all four traced series, n = 48: `ready` precedes the first frame in **41 of 48 runs**,
median **−118 ms**. The worst run in the entire session is **+1 ms**. There is no visible spinner in
any configuration.

The split is perfectly clean, and it is the interesting part:

| `root=` mode | n | spinner window |
|---|---:|---|
| fast (18–86 ms) | 36 | median **−123 ms** — ~123 ms of headroom |
| slow (123–168 ms) | 12 | median **0 ms**, worst **+1 ms** — headroom **fully consumed** |

Every slow-mode run lands `ready` within 2 ms of the first frame, in both directions, with no
exceptions. That is not a coincidence — `ready sinceProcessStart` plus the pre-fork slice simply
equals `TotalTime` at that point. The margin protecting the user from a visible spinner is ~123 ms,
and a slow probe spends all of it. **Config 8 passes, with zero headroom in ~25% of runs.** A device
faster to first frame, or a probe slightly slower, and the spinner is back — so this is a pass to
re-check on a real low-end phone, not a pass to close the question on.

#### What the floor actually costs: `root=` is 88–99% of the probe on a device with no root

In every configuration measured here, `shizuku=` is 0 ms median (max 4 ms, and that is with the
server running and the permission granted) and `dhizuku=` is 0 ms median (max 2 ms). **The entire
probe is the root check**, and the root check is discovering that root does not exist.

That cost is not abstract. Launching Thor on config 8 and inspecting the process tree:

```
USER           PID  PPID NAME
u0_a229      13580   435 com.valhalla.thor
u0_a229      13612 13580 sh          <-- Odin's fallback shell, no su on this device
```

Odin's `RealShellRepository.isRootGranted()` builds the shell to ask whether it is root; libsu finds
no `su`, falls back to `sh`, and that `sh` **stays resident for the lifetime of the process**.
Confirmed by grep: `ShellRepository` is injected into exactly one class, `RootSystemGateway`
(`Modules.kt:73` → `RootSystemGateway.kt:46`), and `RootSystemGateway` is only ever selected when
`isRootAvailable()` returned true. On the majority configuration Thor pays ~30–40 ms and one resident
process for a shell it will never use.

⚠️ **The obvious fix is a trap.** Guarding the probe on a `su`-file existence check would break root
detection on exactly the devices that matter: **KernelSU Next hides `su`** from processes it has not
granted, which is why the config-1 device shows no `su` to `adb shell` while being fully rooted. A
cheap pre-check would report "not rooted" on hidden-`su` setups. There is currently no such
pre-check anywhere in `:app` — that is correct, not an oversight. Any lever here has to be a
lazy/deferred shell, not a cheaper test.

#### What this series does not establish

- **Nothing about relative ordering.** §4 asks whether #8 is clearly the fastest. The drift is bigger
  than the differences, so this session cannot answer it. It can only say the three configurations
  are within ~10 ms of each other in the fast mode, and that `root=` — paid identically in all three,
  since none of them has `su` — is the whole probe.
- **Nothing comparable to config 1.** That was physical hardware; this is an emulator on a fast host,
  and it reaches first frame *sooner* (209–224 ms vs 316 ms). Cross-device run-for-run comparison is
  invalid in both directions.
- **Configuration 2 (root denied) is unreachable here** — it needs a rooted device with a per-app
  deny policy, and KernelSU Next has no request mode.
- **Configuration 7 (Dhizuku device owner) was not run** — it needs the Dhizuku APK and
  `dpm set-device-owner` on a fresh, account-free device. The emulator qualifies; the artifact was
  not on hand.
- **Emulator ≠ low-end phone.** The headroom finding above matters most on slow hardware, which is
  exactly what was not measured.

### 2026-07-31 — config 1 re-measured on v1.93.1: the defect is gone

Device `1da5425f` (`25053PC47G`, Android 16) — the same device as every run below. Root granted in
KernelSU Next, Shizuku installed/running/granted, Dhizuku absent. `versionCode` 1931, built from
`dev` @ `ec49853e`. **Three builds, three series, N = 12 each after 3 discarded warm-ups, every
launch via `am start -W -n`, and `LaunchState: COLD` confirmed on all 45 runs.** This series meets
§1, §2 and §3 in full, which no earlier series did.

| Build | first frame (`TotalTime`) | `probe total` | `root=` | `ready sinceProcessStart` |
|---|---:|---:|---:|---:|
| `fossDebug` | 881 ms | 72 ms | 70 ms | 536 ms |
| `storeRelease` | **318 ms** | — *(trace compiled out)* | — | — |
| `storeBenchmark` (release-shaped + traced) | **316 ms** | **58 ms** | **57 ms** | **96 ms** |

Medians. Full percentiles for the release-shaped build, which is the one that matters:

| Series | n | median | p90 | max | min | p90 ÷ median | Verdict |
|---|---:|---:|---:|---:|---:|---:|---|
| `probe total` | 12 | 58 ms | 67 ms | 73 ms | 52 ms | 1.16 | **good** (≤ 150) |
| `root=` | 12 | 57 ms | 66 ms | 73 ms | 51 ms | 1.16 | **good** (≤ 150) |
| `shizuku=` | 12 | 1 ms | 7 ms | 8 ms | 0 ms | — | good |
| `dhizuku=` | 12 | 0 ms | 0 ms | 5 ms | 0 ms | — | good |
| `ready sinceProcessStart` | 12 | 96 ms | 111 ms | 118 ms | 92 ms | 1.16 | **good** (≤ 400) |
| **spinner window** | 12 | **0 ms** | **0 ms** | — | — | — | **good** (≤ 200) |

**`storeBenchmark` is a faithful proxy for `storeRelease`** — first frame 316.5 ms vs 318.0 ms
(medians), p90 333 vs 330. That control did not exist when the benchmark build type was introduced;
it does now, so its timings can be quoted as release timings.

#### The spinner window is zero, measured rather than projected

Taken by the exact method in §5.1 — both events pulled from one `-v epoch` logcat and **paired per
run**, not compared as aggregates:

```text
ready_epoch − Displayed_epoch:  n=12  median = −107 ms  p90 = −100 ms  min = −120 ms
```

Every value is **negative**: `PrivilegeManager` publishes the privilege state ~107 ms *before* the
first frame is drawn, in **12 of 12** cold starts. The loaders on the Apps and Freezer tabs are
therefore already released when the first frame appears — there is no interval in which a user can
see a spinner or disabled freeze controls.

**This directly refutes [the projection below](#why-this-is-bad-news)**, which inferred ~579 ms of
visible spinner on ~60% of rooted cold starts and called it "a user-visible defect that making the
app faster exposes". That inference rested on the root probe still costing ~720 ms on release. It
costs 57 ms. The projection's *arithmetic* was right and its *input* was stale — which is exactly the
risk the section flagged about itself ("this is an inference, not a measurement").

Note the shape of the win: on release-shaped builds `ready` (96 ms) is now **5.6× faster** than on
debug (536 ms) while `root=` barely moves (57 vs 70 ms). That is the doc's own claim confirmed — the
probe's cost is process spawn and shell handshake, which AOT cannot speed up, so nearly all of the
`ready` improvement is the *non-probe* startup work being AOT-compiled. The probe is now a small
fraction of a fast startup instead of the dominant term in a slow one.

#### What this series still does not establish

- Configurations **2 and 4–8 remain unmeasured**, including **#8, the floor the whole comparison
  rests on**. Config 1 passing does not tell you the no-privilege path is fast — it is a *different*
  code path, and §4's "if #8 is not clearly the fastest, that is a bad result on its own" is still an
  open question.
- No `drop_caches` and no `cmd package compile -m speed -f` normalization (§2's optional third
  bullet). Consistent across all three builds in this session, so the comparisons hold, but absolute
  figures include whatever ART state the device had.
- Shizuku was running and granted throughout, so `shizuku=` here is the happy path only; configs 5
  and 6 (dead binder, missing service) are the ones that could block, and neither was exercised.
- One device, one OEM, one Android version. The root probe's cost is a `su` handshake, which is
  root-solution-specific — KernelSU Next only. Magisk is unmeasured.

### Superseded — 2026-07-30 config 1 (kept as the "before" record)

⚠️ **The config-1 table immediately below is superseded by 2026-07-31 above.** It was taken on
v1.93.0, with the duplicate `HomeActivity` root probe still present, without `LaunchState: COLD`
confirmation and without warm-up discards. Keep it for the before/after contrast; do not cite it as
current, and do not compare it run-for-run against anything newer.

**2026-07-30 — 2 of the 8 configurations measured. This does not meet the acceptance bar below**
(no `LaunchState: COLD` line, no warm-up discard, six configurations untouched), but the first
configuration already returns a **bad** result on three of the five signals, so it is recorded rather
than held.

### Configuration 1 — root granted (KernelSU Next) + Shizuku installed, running, granted

Device `25053PC47G`, Android 16, debug build `com.valhalla.thor.debug`. N = 10.

| Series | n | median | p90 | max | min | p90 ÷ median | Verdict |
|---|---:|---:|---:|---:|---:|---:|---|
| `probe total` | 10 | 662 ms | 757 ms | 793 ms | 66 ms | 1.14 | **bad** (> 500) |
| `root=` | 10 | 656 ms | 751 ms | 789 ms | 62 ms | 1.15 | **bad** (> 500) |
| `shizuku=` | 10 | 4.5 ms | 9 ms | 16 ms | 0 ms | 2.0 | good |
| `dhizuku=` | 10 | 2 ms | 5 ms | 16 ms | 1 ms | 2.5 | good |
| `ready sinceProcessStart` | 10 | 1201 ms | 1332 ms | 1436 ms | 508 ms | 1.11 | **bad** (> 800) |

**Verdict: bad — and the p90 ÷ median column is lying, read the distribution instead.** Every ratio
sits under 2, which the table above would call "steady work". It is not steady; it is **bimodal**,
and a 60/40 split puts the median inside the slow mode where a ratio cannot see it:

| mode | runs | `root=` | `ready` |
|---|---|---|---|
| fast | 4 of 10 | 62–85 ms | 508–540 ms |
| slow | 6 of 10 | 627–789 ms | 1195–1436 ms |

Raw `root=` series, in run order: `85, 80, 733, 627, 789, 684, 62, 70, 751, 750`. Nothing between
85 ms and 627 ms in ten runs.

The two modes differ by **667 ms of root-probe time and 778 ms of time-to-`ready`** — i.e. the swing
in the root probe accounts for essentially the whole swing in the number the loaders wait on. Neither
Shizuku nor Dhizuku moves at all between modes. The root probe is ~99% of `probe total` in both modes.

**This is exactly the signature confound 7 predicts.** `HomeActivity.onResume()` and
`PrivilegeManager` race for Odin's `@Synchronized` shell; the loser pays for shell init. What the
data cannot yet settle is which side pays in the *fast* mode — a fast `root=` with a fast `ready`
(508 ms) is consistent both with "shell init was genuinely cheap that run" and with "`onResume`
paid ~700 ms in parallel, off-trace, without delaying `ready`". Distinguishing them needs
`HomeActivity.kt:108` instrumented too, or removed.

**Removed, 2026-07-30** (see §7). These ten runs are therefore the *last* measurement taken with two
competing probes, and the table above should not be compared run-for-run against anything measured
after that change — only against itself.

**Odin's 10 s `SHELL_INIT_TIMEOUT_MS` was never reached** — max tier 789 ms. The hang class described
above did not appear in this run.

### Superseded — no `su` (configuration 3), Shizuku installed, emulator baseline

> ⚠️ **Retired 2026-07-31 by the Pixel 10 Pro Fold series above**, which covers the same ground at
> N = 12 per configuration on a release-shaped build, with the Shizuku server state explicitly
> controlled and verified in all three of its states. Kept only as the first data point. Its own
> caveats below are the reason it needed replacing.

Emulator, API 37, no `su` binary. Shizuku is installed; whether the server was running was not
verified, so this is config 3 and *not* a clean read of 4/5/6. N = 5, below the doc's own N ≥ 10 bar.

| Series | n | median | p90 | max | min | Verdict |
|---|---:|---:|---:|---:|---:|---|
| `probe total` | 5 | 67 ms | 74 ms | 74 ms | 56 ms | good |
| `root=` (returns `false`) | 5 | 66 ms | 72 ms | 72 ms | 54 ms | good |
| `ready sinceProcessStart` | 5 | 599 ms | 725 ms | 725 ms | 581 ms | investigate (400–800) |

**Verdict: investigate — and the two halves of this row disagree on purpose.** The *probe* is
unambiguously fine (67 ms median, no slow tail); `ready sinceProcessStart` is not, at p90 = 725 ms,
which lands inside the 400–800 ms investigate band. Since the probe accounts for ~70 ms of it, the
other ~650 ms is process init, Koin start and first ViewModel resolution — i.e. **the cost is not in
the thing this document instruments.** That is worth knowing, but it is not a pass.

It stays "investigate" until an N ≥ 10 run confirms it; N = 5 is below this document's own bar and
p90 on five samples is just the maximum wearing a percentile's name. The "config #8 must be fastest"
expectation holds in the right direction so far — the configuration with nothing to find is ~10×
cheaper on `probe total` than the one with root, which is the correct sign but says nothing yet
about #8 itself.

### ~~The spinner window is not yet measured, and the debug build is hiding it~~ — MEASURED 2026-07-31

> ✅ **Resolved.** This section correctly refused to call the 2026-07-30 spinner window a pass, and
> correctly identified the method needed to settle it (§5.1, epoch timestamps, two events paired per
> run). That method was applied on 2026-07-31 against the release-shaped `benchmark` build, which is
> the exact build shape this section said the debug numbers could not stand in for. Result:
> `ready_epoch − Displayed_epoch` is **negative in 12 of 12 cold starts**, median **−107 ms** — the
> privilege state is published before the first frame is drawn, so there is no spinner window at all.
> The concern in this section was legitimate and is now closed by measurement, not by assumption.
> The paragraph below is the original text, kept for the record.

Four unpaired `Displayed` samples on the rooted device: `+1s181ms, +1s243ms, +1s244ms, +1s413ms`.
Against those, `ready` at 1195–1436 ms lands *at or just after* first frame in the slow mode and
several hundred ms *before* it in the fast mode. So the lower bound from §5.2 comes out at roughly
zero — **the spinner window looks fine, but only because the debug build's own first frame takes
~1.2 s and covers the probe.** A release build draws its first frame far sooner, and a 750 ms root
probe would then be exposed as visible spinner on the Apps and Freezer tabs. Do not read "spinner
window ≈ 0" here as a pass; it is an artifact of §6. Redo it with the exact epoch-timestamp method in
§5.1, with the two events paired per run.

### Release build, same device, same day — and it makes the result worse, not better

> ⚠️ **Half-superseded 2026-07-31.** The *timing* half of this section still holds and was reproduced:
> release really does reach first frame far sooner than debug (2026-07-31: 318 ms vs 881.5 ms,
> **2.77×**; the 4.04× below was measured against a slower debug baseline on v1.93.0). The
> *conclusion* — that release speed exposes a slow root probe as visible spinner — **is refuted.** On
> v1.93.1 the root probe is 57 ms on the same release-shaped build, so there is nothing left for the
> fast first frame to expose. Read the mechanism here; do not read the verdict.

Run because the owner's reasonable hypothesis was "release builds are faster". They are, by a lot,
and **that is precisely the problem.**

`com.valhalla.thor` 1.93.0-foss (`versionCode` 1930), R8-minified + resource-shrunk + signed,
installed on the same device `1da5425f` with root granted in KernelSU Next. N = 12 measured after 3
discarded warm-ups, `am start -W -n`, **every run reported `LaunchState: COLD`** — so this series
satisfies §1 and §2 in a way the debug series above does not.

| `TotalTime` (→ first frame) | n | median | p90 | max | min | p90 ÷ median |
|---|---:|---:|---:|---:|---:|---:|
| **release** | 12 | **285.5 ms** | 313 ms | 315 ms | 274 ms | 1.10 |
| **debug** | 12 | **1152 ms** | 1185 ms | 1193 ms | 1125 ms | 1.03 |

**Release reaches first frame 4.04× faster — 866 ms sooner.** Three compounding causes, all verified
in the artifacts rather than assumed:

1. **R8 merged 21 dex files into 1.** `unzip -l` on each APK: `app-foss-release.apk` has one
   `classes.dex`, `app-foss-debug.apk` has 21.
2. **The release APK embeds an ART profile and the debug APK does not** — `assets/dexopt/baseline.prof`
   (9974 B) + `baseline.profm`, from library dependencies shipping their own `baseline-prof.txt`.
   Its 5409 merged lines cover 2346 classes — 47.7% of the APK's 4914 `class_defs` — and 8513
   startup-flagged methods, but they contain **zero `com/valhalla/` entries**: every rule comes from
   androidx/Compose/appcompat/fragment/Lottie/Coil/coroutines AARs. So the framework and UI layers
   are AOT-compiled on first launch and *Thor's own code is not*. That matters for the inference
   below — the privilege probe's own bytecode gets no profile-guided head start in either build.
   This is *not* the excluded `app/baselineprofile` module; it is dependency-supplied and
   deterministic, so it does not threaten FOSS reproducibility.
3. **Consequently the two builds run at different compilation tiers.** `dumpsys package dexopt`:
   release is `[status=speed-profile] [reason=baseline]` **at install time, before first launch**;
   debug is `[status=run-from-apk]` — interpreter plus JIT, never AOT.

**This is not a controlled comparison, and must not be quoted as one.** It measures minification,
resource shrinking, non-debuggable *and* AOT-from-profile all at once — plus one more asymmetry: the
release package is a **fresh install** with empty DataStore, empty Room and no Shizuku grant, while
the debug package has accumulated state. First frame precedes both the data load and the probe, so
that asymmetry is weak here, but the 4× belongs to "shipped build vs debug build", not to any single
cause.

⚠️ **`TotalTime` is unimodal in both builds** (p90 ÷ median 1.03 and 1.10) — and **that is not
evidence that the probe's bimodality is gone.** It cannot be. `SystemRepositoryImpl.isRootAvailable()`
is `withContext(Dispatchers.IO)` and `HomeViewModel` (unlike the Apps/Freezer tabs) renders
optimistically off the preferred mode, so the probe never blocked first frame in *either* build.
A clean release `TotalTime` is exactly what you would see whether the probe took 70 ms or 790 ms.
Reading it as reassurance is the specific way this measurement turns into a lie. What it does show
is that the bimodality is not in the launch path — consistent with it living in the probe, which the
debug trace measures directly and this does not.

#### Why this is bad news

> ⚠️ **REFUTED 2026-07-31 — this whole subsection is wrong, and is kept only to show why.** It
> projected ~579 ms of visible spinner on ~60% of rooted cold starts. Measured directly on the
> release-shaped `benchmark` build, the spinner window is **zero in 12 of 12 cold starts**. The
> arithmetic below is sound; its input — a ~720 ms root probe — is stale. The probe now costs 57 ms,
> so the term this projection was built around has effectively vanished. **Do not act on any
> conclusion in this subsection.**

The privilege probe's cost is **process spawn and shell handshake** — `su` under KernelSU Next, via
Odin — not bytecode execution. R8 and AOT do not make `fork`/`exec` faster. So the ~450–570 ms of
*non-probe* startup work shrinks by ~4×, and the ~720 ms root probe does not move.

Decomposing the debug series (`ready` − `root=`, per mode) and scaling only the non-probe part:

| mode | non-probe work | root probe | projected release `ready` | projected spinner window |
|---|---:|---:|---:|---:|
| fast (4/10 runs) | 448 → ~111 ms | 74 ms | ~185 ms | **none** — ready lands before first frame |
| slow (6/10 runs) | 572 → ~142 ms | 722 ms | ~864 ms | **~579 ms** |

Against this doc's own threshold — spinner ≤ 200 ms good, 200–500 investigate, **> 500 bad** — the
slow mode on release lands in **bad**, in ~60% of cold starts. On debug the same window measured
approximately **zero**, because a 1152 ms first frame comfortably covered a 1201 ms `ready`.

**The debug build was hiding a user-visible defect, and making the app faster is what exposes it.**
Users on the shipped build see disabled freeze controls for roughly half a second, on the majority of
cold starts, on rooted devices — the exact failure the `isReady` gate was introduced to prevent.

**This is an inference, not a measurement.** It rests on one assumption — that the root probe's cost
does not scale with compilation tier — which is well-founded but untested. See below for what it
would take to measure it directly.

#### The trace does not exist in release, and that is by design

Verified rather than assumed, with the debug APK as a positive control:

```text
app-foss-release.apk: searched  1 dex | ThorPrivPerf=0 PrivilegeProbeTrace=0  logmsgs=0
app-foss-debug.apk  : searched 21 dex | ThorPrivPerf=1 PrivilegeProbeTrace=14 logmsgs=2
```

`PrivilegeProbeTrace.start()`'s `BuildConfig.DEBUG` fold works exactly as its KDoc claims: the tag,
the class and both message strings are entirely absent from the shipped dex. **So `probe total`, the
per-tier numbers and `ready sinceProcessStart` cannot be obtained from a stock release build at all.**
`am start -W` is the only cold-start signal release exposes, and it ends at first frame — before the
privilege state exists (§5).

#### The `benchmark` build type — added 2026-07-30, use this instead

So the projection above can be **measured rather than inferred**, there is now a third build type:

```bash
./gradlew assembleStoreBenchmark
# app/build/outputs/apk/store/benchmark/app-store-benchmark.apk
```

`benchmark` does `initWith(release)` — same `isMinifyEnabled`, `isShrinkResources`, ProGuard files
and release signing, so it runs at the same compilation tier — and turns the trace back on. Two
switches, because one is not enough:

| Switch | Where | Why it is needed |
|---|---|---|
| `BuildConfig.PRIVILEGE_TRACE` | `defaultConfig` false, `debug` + `benchmark` true | decides whether the timings are **taken**. `PrivilegeProbeTrace.start()` now folds on this, not on `BuildConfig.DEBUG`, which is false in anything derived from release |
| `Logger.isDebug` | `ThorApplication.kt`, `DEBUG \|\| PRIVILEGE_TRACE` | decides whether they are **printed**. `Logger` gates every level including `e`, so ungating only the trace yields a build that measures everything and logs nothing — indistinguishable from a probe that never ran |

**It is confined to the store flavour.** Build types and flavours are a cross product, so declaring
`benchmark` would also have created `fossBenchmark`; an `androidComponents.beforeVariants` block
disables that variant, leaving `storeBenchmark` as the only one. The foss flavour keeps exactly the
two variants it always had — verified: 97 `storeBenchmark` tasks exist and **zero** `fossBenchmark`
tasks do, with `assembleFoss{,Debug,Release,DebugAndroidTest,DebugUnitTest}` unchanged. Nothing in
the benchmark configuration is reachable from `fossRelease`, so IzzyOnDroid reproducibility cannot be
affected by it.

**No `applicationIdSuffix`, on purpose.** It installs over `com.valhalla.thor` with the same package
name and release signature, so the Magisk/KernelSU/Shizuku grants that package already holds carry
over. KernelSU Next has no request mode, so a separate application id would mean granting root by
hand before every session. `versionNameSuffix = "-benchmark"` is what tells them apart on-device.
The trade is that it replaces an installed release build — reinstall the real one afterwards.

Verified in the emitted artifacts rather than assumed:

```text
app-store-benchmark.apk : dex=1  ThorPrivPerf=1  logmsgs=2   <- traced, and release-shaped
app-foss-release.apk    : dex=1  ThorPrivPerf=0  logmsgs=0   <- still folds out completely
BuildConfig.PRIVILEGE_TRACE: foss/debug=true  foss/release=false  store/benchmark=true
```

CI is unaffected: `pr-ci.yml` runs `assembleFossDebug`, `testFossDebugUnitTest` and
`lintStoreRelease`, none of which touch the new variant.

### What this run does not establish

- **Coldness rests on `am force-stop` plus the one-`ready`-line-per-run self-check** (which held for
  all 10 runs). Launches went through `monkey`, not `am start -W -n`, so there is no
  `LaunchState: COLD` line on the record. The self-check is the stronger of the two signals, but the
  protocol above asks for both.
- No warm-up runs were discarded and no `cmd package compile -m speed -f` normalization was done, so
  JIT warm-up is inside these numbers.
- Configurations 2, 4, 5, 6, 7 and 8 are unmeasured. **#8 — the floor, and the one the "bad result"
  table hangs on — has not been run at all.**

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
  `n`, median, p90 and max — not a single number per cell. → **7 of 8 done properly**: config 1 at
  N=12 on both debug and release-shaped builds (physical hardware), config 2 at N=12 on a
  release-shaped build (same physical device, root denied per-app), configs 4, 5 and 8 at N=12 on a
  release-shaped build (Pixel 10 Pro Fold / Android 17), config 6 ≡ config 8 on that device, and
  config 3 satisfied by every Fold series (none of them has `su`) — which retires the old N=5
  emulator baseline. **Only config 7 remains.**
- Every run in every series is confirmed cold (`LaunchState: COLD`, and one `ready` line per run).
  → ✅ **done**: all 45 runs of the config-1 session, all 60 runs of the Fold session and all 30 runs
  of the config-2 session reported `LaunchState: COLD` via `am start -W -n`, with exactly one `probe`
  line per cold start.
- A one-line verdict per configuration: within budget / investigate / bad, and if any is "bad", which
  lever above it points at. → ✅ **all seven measured configurations are within budget on every signal**,
  on release-shaped builds. **No lever needs pulling.** Levers 1 (drop the `isReady` gate) and 3
  (warm the probe in `ThorApplication.onCreate`) are now priced and both come out **not worth doing**
  — the gate costs nothing when the state is ready ~107–123 ms before first frame, and warming a
  30–57 ms probe earlier buys at most that much of a 210–320 ms startup while adding a cross-cutting
  init dependency. **One caveat carried forward, not a failure:** in ~25% of Fold runs the probe
  consumed the *entire* pre-first-frame margin (window 0 ms, worst +1 ms). That is a pass with no
  headroom, and it is the reason to re-check on genuinely slow hardware before closing this out.
- ⚠️ **A new acceptance rule, learned the hard way:** when configurations are measured in sequence on
  one machine, **re-measure the first configuration last**. On the Fold the slow-mode share of an
  unchanged configuration went 8% → 42% over ten minutes, which is larger than every
  between-configuration difference. Without that control the run order would have been written up as
  a result. Any future series that ranks configurations must include the repeat, or must not rank.
- The instrumentation stays in — it is free in release and this measurement will need repeating
  after any change to the probe chain or to Odin's shell init.

### Next session, in order

Items 1–5 are done as of 2026-07-31. Seven of the eight configurations are measured; what remains is
configuration 7, one control re-run, and two open questions that device time alone will not settle.

1. ~~Instrument or delete `HomeActivity.kt:108`'s independent `isRootAvailable()` call~~ — **done**,
   §7. ~~Re-run config 1 and check the shape~~ — **done**. The prediction (unimodal near the old
   *slow* mode, ~700 ms) was **wrong**: the fast mode became universal at ~57 ms and the bimodality
   vanished. Per this list's own instruction — *"If the fast mode survives, the race was not the
   cause and §7 needs re-opening"* — §7 is re-opened and marked REFUTED. The mechanism is unexplained.
2. ~~Re-run with `am start -W -n` and warm-up discards so the numbers satisfy §1 and §2~~ — **done**,
   45 runs, all `LaunchState: COLD`, 3 warm-ups discarded per series, N=12 measured.
3. ~~Build a release-shaped traced build~~ — **done**, `assembleStoreBenchmark`. ~~Read the spinner
   window directly instead of projecting it~~ — **done**, and the projection (~579 ms, over budget)
   was the weakest link exactly as suspected: measured directly it is **zero in 12/12 runs**
   (`ready_epoch − Displayed_epoch` median −107 ms). **#22 is not a user-visible defect on config 1.**
4. ~~Run config 8 (non-rooted, no Shizuku, no Dhizuku) — the floor~~ — **done** on a Pixel 10 Pro
   Fold / Android 17, together with 4, 5 and 6, and it retires config 3's below-bar N=5 series. The
   floor passes; the *timeout* worry did not materialise, because with no `su` present libsu falls
   back to `sh` in ~35 ms rather than waiting anywhere near Odin's 10 s guard.
5. ~~Config 2 (root denied)~~ — **done** on the physical device, N=12, root denied per-app to both
   app ids in KernelSU Next. `su` exits fast (51.5 ms, no more than a grant) and `active=SHIZUKU` in
   12/12 confirms the fallback chain. **Config 7 (Dhizuku device owner) is all that is left**, and it
   needs the Dhizuku APK plus `dpm set-device-owner` on an account-free device; the Fold AVD
   qualifies, so this is an artifact problem, not a device problem.
   - ⚠️ **One control still owed:** config 1 and config 2 were measured hours apart on the same
     device, so by the acceptance rule above they cannot be ranked against each other until config 1
     is re-run last. Re-granting root and repeating the config-1 series is ~10 minutes and would close
     the only comparison this session left unsupported.
6. **Re-check the zero-headroom case on genuinely slow hardware.** Every device measured so far
   reaches first frame in 210–320 ms and the probe hides behind it. In the slow-probe mode the margin
   is *exactly* consumed (window 0 ms, worst +1 ms). A low-end phone is the configuration where that
   flips positive, and it is the only remaining way this turns out to be a real defect.
7. Explain the ~600 ms drop, or stop claiming it is explained. Two variables changed at once
   (duplicate probe removed *and* v1.93.0 → v1.93.1); a bisect on v1.93.0-with-the-fix is the only
   way to attribute it. Not urgent — the number is good either way — but the doc should not carry an
   unearned causal story. The Fold session narrows it: the same bimodal signature appears with **no
   `su` at all**, so whatever it is, it is not root-grant handling.
8. Optional, cheap: decide whether the fallback `sh` shell should be built lazily. It costs ~35 ms
   and one resident process on every non-rooted launch, and only `RootSystemGateway` can ever use it.
   **Not a `su`-existence pre-check** — KernelSU hides `su`, so that would break real rooted devices.
