# APK-size optimization status

Reconciled 2026-10-05 against `dev` at `6967de7d5ce636d9ab66553a6d0e6df1e75c2231`
(after PR #560), the merged implementation, retained validation reports, and the
maintainer's decisions in the APK optimization discussion.

**The accepted optimization work is merged. No immediately actionable, worthwhile
dependency or R8 removal remains identified.** Legacy WorkManager retirement is the one
larger remaining candidate, subject to an upgrade-compatibility design and a measured
benefit. Room 3 is a separate maintenance assessment.

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

## Remaining candidate: retire legacy WorkManager support

**Status: identified, not implemented or scheduled; not an unused dependency today.**
New export/backup/restore and sweep work uses Room-backed services, but existing persisted
WorkManager jobs still have compatibility, observation, cancellation, and recovery paths.

- [LegacyDataWorkDrainGate](../../app/src/main/java/com/valhalla/thor/data/backup/job/LegacyDataWorkDrainGate.kt)
  prevents new data work from racing nonterminal legacy `THOR_JOB_CHAIN` work.
- [ThorJobLauncher](../../app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobLauncher.kt)
  still observes and cancels legacy jobs.
- [PrivilegeSweepWorkManagerCutover](../../app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorkManagerCutover.kt)
  closes legacy admission, requests cancellation, waits for quiescence, and preserves
  ambiguous outcomes instead of replaying privileged mutations.
- [ThorApplication](../../app/src/main/java/com/valhalla/thor/ThorApplication.kt) and the
  [app dependencies](../../app/build.gradle.kts) still initialize WorkManager and its Koin
  worker factory so persisted workers can be reconstructed.

Before removing this family:

- [ ] Define supported direct-upgrade behavior, including users who skip releases with
      old persisted jobs, or implement a safe migration that settles those jobs and retains
      interrupted-operation recovery information. Elapsed time since release is insufficient.
- [ ] Remove old workers, DI registration, WorkInfo watchers/cancel adapters,
      initializer/manifest services, WorkManager and its Koin bridge together after the
      compatibility contract is satisfied.
- [ ] Validate upgrades containing queued/running/interrupted legacy work, task ownership,
      recovery and cancellation on the emulator and physical device with minified builds.
- [ ] Run required test/lint gates and measure comparable FOSS/Store APKs before deciding
      whether the result justifies shipping the retirement.

The audit attributed **135,809 B FOSS / 131,233 B Store of raw DEX** to WorkManager.
That is not a predicted compressed APK reduction: necessary shared dependencies remain,
and compatibility replacement code may offset removals. See the
[exact retirement constraints](../analysis/dependency-r8-2026-10-05/core-dependencies.md#workmanager-retirement-exact-constraints).

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

No new APK build, profiler session, or device test was run for this documentation review.
