# APK-size optimization status

Reconciled 2026-10-05 against `dev` at `6967de7d5ce636d9ab66553a6d0e6df1e75c2231`
(after PR #560), the merged implementation, retained validation reports, and the
maintainer's decisions in the APK optimization discussion.

**This APK optimization pass is complete. The accepted work is merged, and the maintainer
has decided to retain WorkManager for legacy-job compatibility.** No immediately actionable,
worthwhile dependency or R8 removal remains identified. Removing unused helpers is routine
maintenance with no measured APK-size or performance benefit. Room 3 remains a separate,
deferred maintenance assessment.

This status supersedes the pending-work interpretation of the original
`apk-size-optimization-handover.md`, retained on local branch
`docs/apk-size-optimization-handover` at `0498b204`. That historical handover is not in
the reviewed `dev` tree; its unchecked candidate list predates the work below. Likewise,
older analysis sections saying "implemented locally" or listing Lottie as a candidate
must be read alongside the merged status here.

## Completed and merged

All sizes below are **measured unsigned APK savings for each documented stage**, FOSS
then Store. They are not fresh measurements of PR #560 or final signed distribution sizes.

| Change | Merge | FOSS saved | Store saved | Evidence |
| --- | --- | ---: | ---: | --- |
| Nine static Outfit files replaced with one variable font, explicit weight loading | [#556](https://github.com/trinadhthatakula/Thor/pull/556) | 158,090 B | 141,702 B | [Font validation](../validation/outfit-variable-font.md) |
| Compress/extract standalone native libraries; retain direct native loading in Store-generated splits | [#557](https://github.com/trinadhthatakula/Thor/pull/557) | 101,704 B | 109,708 B | [Packaging validation](../validation/native-library-packaging.md) |
| Five dependency declaration cleanups | [#558](https://github.com/trinadhthatakula/Thor/pull/558) | 352 B | 352 B | [Dependency analysis](../analysis/dependency-r8-2026-10-05/README.md), [device checks](../validation/dependency-cleanup.md) |
| Replace production Lottie with the matching Compose Canvas animation | [#558](https://github.com/trinadhthatakula/Thor/pull/558) | 77,382 B | 80,146 B | [Canvas validation](../validation/terminal-canvas.md) |

The five declaration changes replace biometric KTX with base biometric, remove direct
Room KTX, Accompanist DrawablePainter and adaptive-navigation declarations, and remove
core KTX from `:bypass`. Required Accompanist/adaptive code remains transitively available.
Extended icons remain declared; unused icon definitions are shrunk from releases.
Lottie and reference animation JSON are now test-only.

The last recorded Canvas-stage unsigned artifacts were **4,628,633 B FOSS** and
**4,841,054 B Store**. PRs #559 and #560 followed that measurement. Rebuild current `dev`
with matching toolchain/signing before using a current-size number in a new comparison.

## Decision: retain legacy WorkManager support

**Status: retained by the maintainer on 2026-10-05; retirement is closed for this pass.**
New export/backup/restore and sweep work uses Room-backed services, but existing persisted
WorkManager jobs still have compatibility, observation, cancellation, and recovery paths.
The possible small APK saving does not justify changing that upgrade behavior. Only
leftovers confirmed to have no callers are being removed; the library and live legacy
compatibility paths stay.

- [LegacyDataWorkDrainGate](../../app/src/main/java/com/valhalla/thor/data/backup/job/LegacyDataWorkDrainGate.kt)
  prevents new data work from racing nonterminal legacy `THOR_JOB_CHAIN` work.
- [ThorJobLauncher](../../app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobLauncher.kt)
  still observes legacy jobs, while notification actions retain their WorkManager
  cancellation route.
- [PrivilegeSweepWorkManagerCutover](../../app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorkManagerCutover.kt)
  awaits the legacy-chain cancellation operation and reconciles ambiguous outcomes before
  new service claims begin, serialized by the existing process gate and mutex. It does not
  replay privileged mutations. The removed in-process execution fence had no production
  registration callers; it was not an active worker-quiescence barrier.
- [ThorApplication](../../app/src/main/java/com/valhalla/thor/ThorApplication.kt) and the
  [app dependencies](../../app/build.gradle.kts) still initialize WorkManager and its Koin
  worker factory so persisted workers can be reconstructed.

The audit attributed **135,809 B FOSS / 131,233 B Store of raw DEX** to WorkManager.
That is not a measured or predicted compressed APK reduction; an exact saving below
100 KB has not been established. Necessary shared dependencies remain, and compatibility
replacement code could offset removals. No runtime-performance problem caused by retaining
WorkManager was established in this assessment.

The [historical retirement analysis](../analysis/dependency-r8-2026-10-05/core-dependencies.md#workmanager-retirement-exact-constraints)
is retained as evidence for this decision, not an active deletion checklist. Any future
reconsideration would require a new maintenance decision covering direct upgrades that skip
intermediate releases, persisted-job recovery, and meaningful measured benefit. Elapsed time
since the service migration alone is not evidence that legacy jobs no longer exist.

## Closed, superseded, or deliberately excluded

- **Unused static Outfit weights:** superseded by the complete variable-font replacement.
- **UAD minification/projection, license consolidation, downloadable fonts, launcher cleanup:**
  dropped by the maintainer; do not reopen the small savings as pending implementation.
- **Extended-icon replacement:** reverted by request; retain the declaration.
- **Odin keep-rule experiment:** not adopted. It saved only **346 B FOSS / 1,245 B Store**
  of compressed entry payload. Preserve root, Binder, reflection and extension contracts;
  deleting redundant rules under a broader keep does not add shrinking.
- **DataStore native counter:** approximately **10.7 KiB** compressed payload was identified
  as a possible exclusion, but it is outside the chosen worthwhile-savings scope. It is not
  a remaining recommended step.
- **Global shrinker switches or accidental compiler/test libraries:** no new useful switch
  or large accidentally bundled dependency was found. R8 full mode and optimized resource
  shrinking are already active. Eligibility percentages are not removable-byte estimates.
- **Room 3:** record and revisit as [database maintenance](room-3-migration.md), with no
  established size/performance win. WorkManager currently keeps Room 2 in the dependency graph.

The [dependency/R8 audit](../analysis/dependency-r8-2026-10-05/README.md) retains the
measurements and reasons for these decisions.

## Validation disposition and measurement limits

The accepted changes are not waiting for their original emulator/phone checks:

- Outfit: baseline and candidate passed five checks on each API 30/API 36 emulator and
  the POCO/ReSuKiSU phone, including distinct weight rendering and typography comparisons.
- Native packaging: seven checks per target on the same three devices, including 4 KiB
  and 16 KiB page sizes; minified FOSS → Store splits → FOSS installation/update checks passed.
- Dependency cleanup: emulator/phone checks include maintainer-confirmed fingerprint
  authentication in minified FOSS and Store builds.
- Canvas: eight focused checks on each of the Odin emulator and phone, 5,688 pixel-identical
  frame comparisons, and corrected minified-preview checks passed.

The later root-granted emulator profiling comparison was completed in the discussion and
accepted by the maintainer as showing no major loss/gain. Earlier reports saying no benchmark
had been run predate that comparison; this status does not reopen profiling or claim a speedup.

Native **net installed-storage impact remains unmeasured**. Play split policy was checked
locally with bundletool, not through actual Play-server delivery. Runtime targets were arm64;
API 28/31, other ABIs and further OEM coverage remain bounded coverage gaps in the validation
reports. These are measurement/coverage limits, not additional APK-saving implementations
or newly imposed blockers for the merged work.

The status reconciliation reuses the retained size measurements above. Separate build and
device checks for the unused-helper cleanup are recorded in
[WorkManager cleanup validation](../validation/legacy-workmanager-cleanup.md); they do not
establish a new APK-size or performance improvement.
