# Thor emulator profiling results: 2026-09-11

## Verdict and scope

**Repeatable process-cold launches and mostly smooth non-destructive navigation in
the tested emulator workload.** No app crash or ANR was observed. No sustained
scrolling bottleneck appeared, and this run does not justify an architectural
performance rewrite.

This is not an all-features, privileged-operation, leak-freedom, physical-device
performance, or Kotlin regression sign-off. There is no matched pre-update trace.
The maintainer separately reported successful physical-device functional testing;
that is not a physical-device profiling result.

The measured code is commit `e5b611926c110df064b5194c631c60a1604a998f` in
[PR #468](https://github.com/trinadhthatakula/Thor/pull/468), including the dependency
updates, stdlib helper changes, and explicit backing field in `InstallerEventBus`.
No application source changes were made during profiling.

## Environment and method

| Item | Configuration |
|---|---|
| Device | `emulator-5554`, Android 16 / API 36, ARM64 |
| Display | 1080 x 2400, 60 Hz |
| Graphics | Android Emulator OpenGL ES Translator, Apple M4 host GPU |
| Build | `storeBenchmark`, minified and resource-shrunk, not debuggable |
| Package / version | `com.valhalla.thor`, `1.95.4-benchmark`, version code 1954 |
| SDK | minSdk 28, targetSdk 37 |
| Instrumentation | Existing `PRIVILEGE_TRACE` timing logs; Perfetto system tracing |
| Measured privilege state | `NONE` on every repeated launch; Magisk and `su` present |

`assembleStoreBenchmark` succeeded using Zulu JDK 21, serialized compilation, and
a temporary 4 GB Kotlin compiler heap override. APK SHA-256:

```text
d0566c61db9d52d7e6bc52c5833aa699cae9c4f9cf14909d3c7525834c65f174
```

- Installed alongside the existing debug app. Its data and the existing acceptance
  fixtures were not cleared, modified, or uninstalled.
- Recorded the first new-package launch separately, then fifteen process-cold
  launches using `am force-stop` and `am start -W`. The first three were planned
  warmups; the remaining twelve form the measured sample. Every run reported
  `LaunchState: COLD` and a privilege-ready log line.
- No ART speed compilation or OS cache dropping was forced. These are process-cold
  starts with filesystem/app caches warmed, not twelve fresh installs.
- Window, transition, and animator scales were temporarily changed from 0/0/0 to
  1/1/1, then restored and verified afterward.
- Captured startup and navigation separately using Perfetto: scheduling, Android
  activity/view/graphics/binder/database events, FrameTimeline, process/system
  memory counters, and logcat. Analysis used trace processor v58.2.
- All device commands selected the emulator explicitly. No physical device was
  accessed and no superuser authorization was approved by automation.

## Cold-start results

| Measurement | n | Median | p90, nearest rank | Maximum |
|---|---:|---:|---:|---:|
| `am start` TotalTime / first display | 12 | 259 ms | 283 ms | 296 ms |
| Privilege probe total | 12 | 59.5 ms | 67 ms | 69 ms |
| Privilege resolution ready since process start | 12 | 90.5 ms | 114 ms | 122 ms |

The measurements have different boundaries and must not be added together.
Privilege readiness means a decision is available, not that root is granted or
every screen has finished loading. A reportFullyDrawn-derived TTFD was unavailable
for the queried mapped starts.

### Measured samples

All durations are milliseconds. Capture runs 1-3 are excluded warmups; their
TotalTime values were 283, 243, and 256 ms. All rows below resolved to `active=NONE`.

| Capture run | Process PID | TotalTime | Probe total | Ready since process start |
|---|---:|---:|---:|---:|
| 4 | 4492 | 273 | 57 | 83 |
| 5 | 4577 | 257 | 59 | 77 |
| 6 | 4661 | 252 | 46 | 94 |
| 7 | 4745 | 239 | 38 | 59 |
| 8 | 4829 | 245 | 49 | 86 |
| 9 | 4913 | 248 | 62 | 91 |
| 10 | 4993 | 283 | 62 | 96 |
| 11 | 5080 | 261 | 60 | 90 |
| 12 | 5163 | 255 | 53 | 81 |
| 13 | 5242 | 282 | 69 | 122 |
| 14 | 5332 | 296 | 67 | 114 |
| 15 | 5417 | 280 | 60 | 100 |

### First-launch authorization delay

The first new-package launch, before the trace, displayed its first frame in
336 ms, but logged `root=10026ms`, `probe total=10030ms`, and readiness at 10062 ms.
System logcat records Magisk's `SuRequestActivity` starting at 05:39:50.421 and
being displayed at 05:39:50.630 during this interval. No authorization was approved.

This is a permission-prompt-confounded first launch, not a steady-state ten-second
render time or proof of a Kotlin regression. The delay did not recur in the fifteen
subsequent launches. Its evidence is saved logcat and `am start`, not a Perfetto
call-stack capture of the initial wait. Root profiling requires a separate run with
the benchmark package's grant state explicitly established before measurement.

## Navigation and rendering

Manually checked Home, user/system app grids, paced search, Settings landing,
empty Freezer, and empty Guardians. Cancelled the disabled-app import prompt
without importing or freezing anything.

The controlled workload repeated this journey three times:

1. Home to Apps, select the system-app grid.
2. Four upward and four downward swipes, each 500 ms, with 300 ms pauses.
3. Freezer to Guardians and back, then Settings and Home, with approximately
   one-second pauses between navigation actions.

This yields 24 scroll gestures. Search and the preliminary screen exploration are
not part of this controlled frame benchmark. The final screenshot confirms a
return to Home.

### Android gfxinfo

| Metric | Result |
|---|---:|
| Rendered frames | 2001 |
| Frame-deadline misses | 3 (0.15%) |
| Render time p50 / p90 / p95 / p99 | 5 / 7 / 17 / 19 ms |
| GPU time p90 / p95 / p99 | 2 / 3 / 3 ms |
| Legacy-janky frames | 65 (3.25%) |
| High-input-latency flags | 214 |

The input-latency flags are not a measured end-to-end latency distribution. This
run therefore does not certify input latency, even though paced interaction was
usable.

### Perfetto findings

The navigation trace contains 2009 actual-frame records: nine dropped frames,
1882 Prediction Error, three Prediction Error + App Deadline Missed,
107 Prediction Error + Buffer Stuffing, and eight SurfaceFlinger Scheduling.
These categories and the denominator differ from gfxinfo. **Do not count every
emulator prediction error as app jank.**

All three App Deadline Missed records occurred around screen entry:

| Entry | Frame ID | Frame window | Main Running | RenderThread Running | Recorded work |
|---|---:|---:|---:|---:|---|
| Apps, cycle 1 | 6973 | 17.649 ms | 16.932 ms | 2.232 ms | Draw/layout; measureAndLayout 8.547 ms |
| Freezer, cycle 1 | 175142 | 25.284 ms | 10.922 ms | 20.951 ms | Shader compile 4.893 ms; eglSwapBuffers 14.049 ms |
| Apps, cycle 3 | 470364 | 17.908 ms | 16.723 ms | 2.287 ms | Draw/layout; measureAndLayout 8.798 ms |

Frame windows were intersected with thread states using `SPAN_JOIN` partitioned
by `utid`. Thread times overlap and nested slices are not additive. Main runnable
time in these windows was 0.353 / 0.100 / 0.547 ms, so the misses were not solely
scheduler delay. The longest main-thread Choreographer slice was 15.659 ms.

| Marked scroll interval | Actual-frame records | App-deadline misses | Maximum pipeline duration |
|---|---:|---:|---:|
| Cycle 1 | 386 | 0 | 7.438 ms |
| Cycle 2 | 381 | 0 | 7.079 ms |
| Cycle 3 | 381 | 0 | 6.998 ms |
| Total | 1148 | 0 | 7.438 ms |

The capture identifies occasional screen-entry layout/drawing and shader work,
not a sustained scrolling bottleneck or a proven source-level fix. The Freezer
phase marker also encloses Guardians/back; there is no independently timed
Guardians interval. Navigation main-thread and RenderThread state aggregation
contained no uninterruptible-sleep (`D`) rows.

## Memory and stability

PSS units below are KB as reported by `dumpsys meminfo`; MiB values divide by 1024.

| Controlled snapshot | Uptime, ms | PSS | Activity / AppContext count |
|---|---:|---:|---:|
| Before navigation, after UI exploration | 1186009 | 78083 KB (76.3 MiB) | 1 / 5 |
| After three cycles | 1243268 | 80565 KB (78.7 MiB) | 1 / 5 |
| Later idle snapshot | 1367342 | 60957 KB (59.5 MiB) | 1 / 5 |

PSS grew by 2482 KB during the controlled journey, then fell below its baseline.
The last two samples are **124.074 seconds apart**, not merely the explicit
30-second sleep used before the last sample. This is encouraging but is not a
heap-retention or leak test. An earlier 34051 KB snapshot preceded UI/icon/keyboard
exploration and is not an equivalent controlled baseline.

Gfxinfo separately reported 33.26 MB of GPU cache, 31.07 MB purgeable. Emulator
Graphics PSS=0 does not establish zero graphics cost. No app crash or ANR appeared
in the scoped logs. Exit-info contained the fifteen intentional
USER REQUESTED/FORCE STOP exits used for cold-start setup.

## Comparison with Android guidance

- [Android Vitals startup guidance](https://developer.android.com/topic/performance/vitals/launch-time)
  considers a cold TTID of five seconds or more excessive. The observed first-frame
  times are well below that boundary, but it is a failure threshold, not a target
  for excellent performance.
- [Android rendering guidance](https://developer.android.com/topic/performance/vitals/render)
  recommends roughly 16 ms frames for smooth UI. At this emulator's 60 Hz refresh
  rate, the budget is approximately 16.7 ms. Median and p90 render times fit
  comfortably; p95 is near/slightly beyond the budget.
- There is no universal good-PSS threshold. Workload, device memory, retained
  objects, and longer-term growth matter more than a single memory sample.

These comparisons support a good result for this workload, not a production jank
rate, lower-end-device guarantee, or measured benefit from the Kotlin upgrade.

## Limitations and follow-ups

1. **Privileges:** the benchmark application ID did not inherit the debug app's
   root grant. Repeated launches selected NONE. Actual privileged operations were
   not profiled, and no package mutation was attempted.
2. **Nonfatal logs:** the Unsafe hidden-API-exemption path logged an
   IllegalArgumentException. Alternative bypass paths exist, but their complete
   capability coverage was not verified. BillingClient logged reconnect result 3
   and unbind warnings despite the Play Store package being present. Neither is
   proven to cause the privilege delay; investigate those workflows separately.
3. **Startup attribution:** all fifteen activity-start events are present, but only
   nine map to hosting processes. Zero CPU values for unmapped starts are missing
   attribution, not zero work. Unreconciled TTID fields were not averaged; headline
   values use all twelve saved `am start` measurements.
4. **Trace health:** no positive error/loss/overrun counters appeared. Navigation
   has one informational discarded-chunk count; complete loss-free attribution is
   not claimed.
5. **Input automation:** an IME stylus tutorial intercepted the first search
   attempt. After dismissal, paced input produced correct results. One fast adb
   injection produced altered text; no human-typing reproduction or app cause was
   established. Search was not timed in the controlled trace.
6. **Coverage:** no real install/export/backup/restore/uninstall/freeze, purchase,
   populated task log, multi-locale, foldable, stress, or long-duration leak matrix
   was exercised. Physical-device functional testing remains separate maintainer
   evidence.
7. **Baseline:** no matched pre-update profile exists. Emulator shared-host/GPU
   behavior prevents treating these timings as physical-device performance.

Priority follow-up, if needed: establish the benchmark app's privilege grant state
and capture a separately authorized first-launch/privileged workflow. A matched
pre-update run is required before attributing a performance delta to Kotlin.

## Evidence retention and cleanup

This committed report preserves the extracted samples, metrics, workload, and
limitations. The raw capture bundle `thor-emu-profile-20260911.ux1ZcI` remains in
local temporary storage, **not in Git or attached to the PR**. Its lifetime is not
guaranteed; archive it outside Git before local cleanup if future raw-trace
reanalysis is required. Do not mistake the filenames below for downloadable PR
attachments.

| Raw trace | Bytes | SHA-256 |
|---|---:|---|
| startup.perfetto-trace | 54769933 | `69f7c1f14861beb095bcbc7c01cc9fe7ef643d5d1f7db0ce8e49f7c061321b20` |
| navigation.perfetto-trace | 65665335 | `92e3de827a6dbf9d6257116dca2c53eb695d6197fcf676029dc86db7b44ceb67` |

The local bundle also contains launch/probe logs, summary JSON, frame-phase and
thread-state CSVs, memory/exit-info dumps, screenshots, capture configuration,
workload scripts, and per-trace analysis notes. Trace files open in Perfetto UI or
Android Studio. Large binary traces and potentially sensitive device logs were
deliberately excluded from the source repository.

Animation scales were restored and verified as 0/0/0. Device-side trace files were
removed after pulling them. The benchmark APK remains installed; the existing
debug app and fixtures were preserved. No application source was changed by this
measurement. An independent Oracle review checked the bounded interpretation.

The protocol was informed by
[the existing privilege cold-start record](../follow-ups/privilege-manager-cold-start.md)
and [Android startup analysis guidance](https://developer.android.com/topic/performance/appstartup/analysis-optimization).
This emulator pass does not close the broader
[deferred physical-device trace pass](../follow-ups/perfetto-trace-pass.md).
