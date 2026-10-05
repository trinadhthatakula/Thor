# Dependency and R8 audit

**Status update, 2026-10-05:** the five dependency cleanups and Canvas replacement merged
in [PR #558](https://github.com/trinadhthatakula/Thor/pull/558). References below to local
implementation or Lottie as a candidate describe earlier stages. See the reconciled
[APK optimization status](../../follow-ups/apk-size-optimization-status.md) for remaining
work and the disposition of subsequent validation.

Date: 2026-10-05. Topic branch: `chore/dependency-r8-cleanup`, based on `dev`
`b679e91e` (PR #557 merge) in `~/StudioProject/Thor-worktrees/dependency-r8-cleanup`.
The original audit baseline was `18162fe050b1360c79de0071eed4c59ddcb49622`.

The audit found no large, clearly unused runtime dependency that can simply be deleted.
It found redundant declarations and two larger
replacement/migration candidates: Lottie and legacy WorkManager support. The Lottie
replacement is now implemented locally and validated as described below. Root/IPC and
external extension contracts account for the broadest remaining keep rules.

The maintainer discarded UAD minification/projection, license consolidation, downloadable
fonts and launcher cleanup on 2026-10-05. They are outside this work's recommendations.
The maintainer also chose to retain `material-icons-extended`: unused icon definitions
are already removed from release APKs. The five dependency cleanups below are retained
for simpler declarations and module dependencies. No runtime-performance improvement has
been demonstrated; no faster startup, lower memory use or smoother rendering is claimed.

## Coverage and evidence

Three parallel audits covered UI dependencies, core dependencies/support modules, and the
latest release APKs. A separate fresh R8 configuration-analyzer run covered both flavors.

- [Declaration inventory](inventory.md): all **56 baseline catalog library declarations**,
  their scopes and selected release versions. Implicit Kotlin runtime and build plugins
  are covered by the core report.
- [UI dependencies](ui-dependencies.md): direct use, transitive use, resources, navigation,
  images, typography/UI libraries and compiler/test scope.
- [Core dependencies and modules](core-dependencies.md): persistence, jobs, DI, privilege
  providers, extension ABI, Billing, `:bypass` and compile-only `:vm-runtime`.
- [Release APK inspection](release-apks.md): retained DEX, resources, native libraries,
  test/debug leakage and Store isolation, anchored by matching APK/R8 mapping identities.
- [R8 keep rules](r8-keep-rules.md): fresh analyzer scores, rule impact and protected roots.
- [Lottie replacement implementation](lottie-replacement.md): how to reproduce the current
  seven-square animation with Compose, including its time remapping and parity checks.

The baseline resolves **158 FOSS / 172 Store runtime artifacts**, including forwarding
and compatibility artifacts. This is not a count of independently retained APK libraries.
`:bypass` initially resolves 22 runtime artifacts; `:vm-runtime` has zero external
dependencies and is consumed as `compileOnly`.

Both fresh baseline APKs exactly reproduce the retained pre-native-packaging artifacts:
FOSS **4,808,071 bytes**, Store **5,031,260 bytes**. The APK audit separately inspected the
latest compressed-native candidates from PR #557: FOSS **4,706,367**, Store **4,921,552**.
Do not attribute that packaging difference to dependency cleanup. Initial experiments
used pre-#557 packaging. The final scope is rebased onto #557 and uses compressed native
libraries; historical measurements below are labelled separately.

## Cleanup implemented locally

| Change | Why it is valid | Expected kind of benefit |
| --- | --- | --- |
| Replace `biometric-ktx` with base `biometric` | Thor uses `BiometricManager` capability APIs; its prompt uses the Android framework. No biometric KTX API is used. | Removes unused extension artifact while preserving older-API capability behavior. |
| Remove direct Accompanist DrawablePainter declaration | Thor does not call it directly; Coil requires and brings it transitively. | Simplifies dependency ownership; no exclusion of required image code. |
| Remove direct adaptive-navigation declaration | Thor uses adaptive-navigation3, which brings this adapter transitively. | Simplifies ownership while preserving the runtime dependency. |
| Remove `room-ktx` declaration | Room 2.8.5 KTX is an empty compatibility AAR; runtime owns the Kotlin APIs. | Removes redundant compatibility declaration. |
| Remove `:bypass` core-ktx declaration | The module has no AndroidX references. `:app` retains its directly used core dependency. | Reduces the support module's compile/runtime dependency graph. |

The declaration cleanup retains all other used dependencies and runtime keeps; the subsequent
Canvas replacement removes runtime Lottie separately. In particular,
Billing KTX's suspend `acknowledgePurchase` API is used; Coil's DrawablePainter is used
transitively; Asgard's JetBrains Compose wrappers do not contain a second Compose engine.
Compiler/test dependencies and the compile-only VM stubs are not shipped runtime weight.

## Final verification on merged dev

The final five-change scope, with extended icons retained and PR #557 included, passed
both release builds and `test lintFossDebug lintStoreRelease` in **8m 46s**. All **7,168
tests** passed, with no failures/errors/skips. Lint reported no errors or warnings and no
MissingTranslation/SyntheticAccessor findings; its existing 10 FOSS / 9 Store hints remain.
Subsequent [device validation](../../validation/dependency-cleanup.md) covers the emulator
and physical phone, including real fingerprint authentication. No runtime-performance
benchmark has been performed.

| Unsigned release APK with compressed native libraries | Merged-dev baseline bytes | Final cleanup bytes | Saved bytes |
| --- | ---: | ---: | ---: |
| FOSS | 4,706,367 | 4,706,015 | **352** |
| Store | 4,921,552 | 4,921,200 | **352** |

DEX, resource tables, resources, assets and native-library contents are **byte-identical**.
Only biometric-KTX and Room-KTX version metadata are removed. Store's existing biometric
permission declarations change order; decoded permissions, attributes and all other
manifest content are equivalent. FOSS's manifest is byte-identical. Extended icons remain
declared and their version metadata is present; unused icon definitions are still shrunk.

The final runtime graphs contain **156 FOSS / 170 Store artifacts**, **2 in `:bypass`**,
and zero external dependencies in `:vm-runtime`. The change is dependency maintenance;
the evidence does not establish a runtime-performance gain. Final APKs, maps, resolved
graphs, source hashes, test/lint counts and manifest comparisons are archived under
`merged-dev-cleanup/` in the artifact directory below.

## Subsequent Canvas replacement

The terminal's seven-box animation now uses Compose Canvas with the original geometry,
time remapping, loop duration and in-app theme colors. Lottie and the two production
JSON resources are removed; base Lottie and unchanged JSON references are test-only.
There is no new runtime dependency or change to R8 keep rules.

| Unsigned release APK | Validated dependency cleanup bytes | Canvas bytes | Saved bytes |
| --- | ---: | ---: | ---: |
| FOSS | 4,706,015 | 4,628,633 | **77,382 (1.64%)** |
| Store | 4,921,200 | 4,841,054 | **80,146 (1.63%)** |

Both release builds and the required test/lint gates pass: **7,168 tests**, zero failures,
errors or skips, and no lint errors/warnings. Native-library payloads are unchanged;
Lottie, test classes and reference animation payloads are absent from both release APKs.
Each device passes **8/8 focused checks**, including **5,688 pixel-identical comparisons**
across the emulator and phone, controlled timing/lifecycle/theme checks, and sustained
live playback at the observed 60 Hz / 120 Hz display rates. These are correctness checks,
not a performance benchmark. See [validation and limitations](../../validation/terminal-canvas.md)
for exact scope, artifacts and the separate minified-preview result.

## Historical measurement: initial six-change candidate

This initial candidate also replaced extended icons with core. That icon change has since
been reverted at the maintainer's request. These numbers describe the archived experiment,
not the final five-change scope on merged `dev`.

The dependency candidate build and required `test lintFossDebug lintStoreRelease` gates
passed in **10m 3s**. There were **7,168 tests**, zero failures/errors/skips, no lint errors
or warnings, and no MissingTranslation/SyntheticAccessor findings. Lint reported 10 FOSS
and 9 Store hints, including one dependency-update hint. No device installation was
performed for this audit.

| Unsigned release APK | Baseline bytes | Cleanup bytes | Saved bytes |
| --- | ---: | ---: | ---: |
| FOSS | 4,808,071 | 4,807,503 | **568** |
| Store | 5,031,260 | 5,030,688 | **572** |

Both variants have **byte-identical DEX, resource tables, resources, assets and native
libraries**. Three version metadata entries disappeared: biometric KTX, extended icons
and Room KTX. Store's existing biometric/fingerprint permission declarations moved in
the manifest; decoded declarations and attributes are identical, and only ordering and
compression changed. These results demonstrate build/declaration cleanup rather than a
meaningful APK reduction.

The resolved artifact counts changed from **158 → 155 FOSS**, **172 → 169 Store**, and
**22 → 2 in `:bypass`**. The latter retains Kotlin stdlib and JetBrains annotations;
`:app` still resolves its required AndroidX/core graph. Accompanist and adaptive-navigation
remain transitively required. No new app runtime artifact was introduced.

## Larger candidates and their constraints

These numbers are **raw DEX attribution**, not expected compressed APK savings. R8 moves
code across package boundaries, so they cannot be added to obtain a removal estimate.

| Candidate | FOSS retained attribution | Required work |
| --- | ---: | --- |
| Lottie | 123,623 bytes / 637 methods | One active-terminal rearranging-box animation uses it. A Compose port must preserve timing, viewport, looping and Thor's in-app light/dark theme; measure and visually validate the replacement. |
| WorkManager | 135,809 bytes / 603 methods | New work uses Room/services, but existing persisted jobs and sweep migration still require WorkManager. A safe direct-upgrade migration or supported-upgrade policy is required before retiring workers, watcher/cancel adapters, initializer and Koin bridge. |
| Odin's broad keep | 76,408 bytes / 855 methods for the whole package | Replace the published consumer rules deliberately; app-side permissive rules cannot override a stronger consumer keep. Preserve bootstrap, reflected constructors, Binder and callback behavior; validate signed/minified root operations before adoption. |

### Odin experiment: not adopted

An artifact-only experiment filtered Odin 1.1.0's published consumer rules and substituted
rules that retain its classes, members and names, with conservative bootstrap/IPC keeps,
while allowing other method optimization. Both merged R8 configurations confirm that the
original blanket keep was absent and the replacement was active. Both builds passed.

| Compared with the validated dependency cleanup | FOSS | Store |
| --- | ---: | ---: |
| Raw DEX saved | 992 bytes | 856 bytes |
| Compressed DEX saved | 384 bytes | 1,235 bytes |
| Total compressed entry payload saved, including baseline profiles | **346 bytes** | **1,245 bytes** |
| Whole unsigned APK saved | 0 bytes | 16,384 bytes |

The whole-file difference is affected by native-library ZIP alignment: FOSS padding absorbs
the payload reduction, while Store crosses a 16 KiB boundary. The Store result is not
16 KiB of removed code and must not be projected onto #557's compressed-native packaging.
The modest payload saving does not justify changing root/IPC optimization constraints.
No experimental APK was installed, no privileged runtime behavior was validated, and no
experimental rule was adopted. The production keep rules remain unchanged. Inputs,
candidate APKs/mappings and `comparison.json` are archived in `odin-optimization/` under
the artifact directory below. Normal release builds were then restored: both APK hashes
exactly match the validated dependency-cleanup outputs, their original Odin keeps are
present, and the three tested build/catalog source files have unchanged hashes.

## Findings that do not justify risky changes

- Both APKs contain no JUnit/Robolectric/Espresso/Turbine/coroutines-test runtime, Compose
  UI tooling/test runtime, Kotlin reflection runtime or `:vm-runtime` stub implementation.
  FOSS contains no Billing or Google Play services classes.
- DataStore's multiprocess counter loader/classes are entirely stripped, but its four
  native entries remain: **10,923 compressed payload bytes** after #557. This is a small
  dependency-derived packaging opportunity, not a large R8 win. No exclusion is applied;
  future multiprocess use would need protection and persistence validation.
- Coil's kept View-target classes account for only about **1.1 KiB raw DEX** and protect
  an upstream R8 bug. Its workaround remains.
- R8 optimization eligibility is approximately **97.84–97.85%**, shrinking **98.06%** and
  obfuscation **98.08%**. These percentages describe item constraints, not removable code.
  Full mode, optimized defaults and resource shrinking were already enabled.
- Broad app extension and root-service keeps protect external loading/Binder contracts.
  Deleting subsumed rules while broad keeps remain would provide no extra shrinking.

## Reproduction

Use JDK 21 and the Android SDK. From this topic worktree:

```sh
./gradlew :app:assembleFossRelease :app:assembleStoreRelease \
  test lintFossDebug lintStoreRelease --max-workers=1
```

Machine-readable resolution graphs, the baseline declaration catalog, APKs, mapping files,
fresh R8 `analysis.txt` reports, source hashes, build logs and experiment inputs are retained
under `~/.codex/artifacts/thor-dependency-r8-audit-2026-10-05/`. The reports above preserve
the findings without requiring those local artifacts.
