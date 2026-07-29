# Follow-up: one on-device trace pass, after everything else lands

**Status:** OPEN — approved, and deliberately **last**. The owner's sequencing is explicit: run this
*after* all other in-flight changes have merged, so one capture measures a settled tree instead of a
moving one.
**Severity:** Not a defect. A measurement gap — several performance-shaped decisions have been
reasoned about carefully and none of them has been confirmed against a real device.
**Effort:** small to capture, unbounded to act on. Budget the capture and the triage separately, and
treat "the trace showed nothing" as a successful outcome.
**Raised by:** the deferred-items consolidation (2026-07-29), collecting measurement asks that were
each deferred individually and never scheduled together.

Files: none — this produces a trace and a triage, not a patch. What it measures:
`app/src/main/java/com/valhalla/thor/ThorApplication.kt` (cold start),
`app/src/main/java/com/valhalla/thor/data/freezer/BulkFreezeRunner.kt`,
`app/src/main/java/com/valhalla/thor/data/launcher/FreezerShortcutManager.kt`,
`app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt` (the settle-delay and
refresh-indicator constants).

## Problem

Thor's recent performance work has all been *argued* rather than *measured*:

- The smoothness sweep's findings were fixed and merged, but the on-device confirmation was left as
  an optional fast-follow and never run. (Its report is not in the repo — `docs/audit/` is
  gitignored — so this file is the only in-repo record that the fast-follow exists.)
- [`freezer-bulk-run-deferred-review-findings.md`](freezer-bulk-run-deferred-review-findings.md) §2
  is a measurement ask *by construction*: `PrivilegeManager` was pulled into the startup graph and
  the write-up says, in as many words, that filing it means "measure this", not "fix this", and that
  restructuring DI on the strength of a call graph alone is how cold starts get slower.
- `AppListViewModel`'s settle delays (0/400/800 ms) and `REFRESH_INDICATOR_MIN_VISIBLE` (600 ms) are
  pinned by tests that constrain the *mapping*, not the felt result. Nobody has looked at a frame
  timeline to see whether 400 ms is the right number or merely a plausible one.

Doing these as one pass is the point. Each is a ten-minute capture on its own and none of them is
worth a device session alone; together they are one sitting, and they share a baseline.

## Sketch

Not a decision, just the shape:

1. **Capture on the slowest device available**, not the fastest. A flagship hides exactly the
   regressions this pass exists to find.
2. **Cold start:** `adb shell am start -W` for a coarse number, then a Perfetto capture for where
   the time actually goes. This is the direct answer to the `PrivilegeManager` question — if there
   is no delta, close that item as measured-and-fine rather than leaving it open forever.
3. **Scroll / refresh:** capture the app list under a full device's worth of apps, with icons cold.
   Frame timeline over the pull-to-refresh and the screen-entry paths.
4. **Bulk freeze:** capture one Freeze-all run. `BulkFreezeRunner` bounds concurrency at
   `MAX_CONCURRENT`; the trace says whether that bound is the right one on a device where each
   `pm` call contends on the PackageManager lock.
5. **LeakCanary** on a debug build for the same session — it costs nothing to have running while the
   traces are being taken, and the ViewModel/scope lifetimes touched by recent work are exactly what
   it is good at.

## Acceptance

- One trace per scenario above, kept somewhere durable enough to diff the next pass against.
- Every finding is either fixed or written down with its number. A measurement that produces no
  record is a measurement that will be re-run from scratch.
- The `PrivilegeManager` cold-start item is closed one way or the other — measured-and-fine, or
  `Lazy<BulkFreezeRunner>` with the delta that justified it.
