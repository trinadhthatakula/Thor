# Terminal Canvas replacement validation

Date: 2026-10-05. Branch: `chore/dependency-r8-cleanup`, based on merged `dev`
`b679e91e` (PR #557). The separately validated dependency-declaration cleanup is local
commit `50805c05`; its [device checks](dependency-cleanup.md) include fingerprint
authentication on minified FOSS and Store builds.

The terminal's seven-box animation now uses Compose Canvas and Android drawing APIs.
The original geometry, time remapping, 1616 ms nominal duration and in-app theme colors are preserved.
Lottie and both animation JSON files are removed from the production application.
Lottie 6.7.1 and unchanged JSON references remain **androidTest-only**, as the independent
pixel oracle. No new runtime dependency, keep-rule change or version-code change is introduced.

Implementation and the original animation's unusual timing are described in
[the implementation notes](../analysis/dependency-r8-2026-10-05/lottie-replacement.md).

## Devices and frame equivalence

| Target | Android | ABI | Page size | Result |
| --- | --- | --- | --- | --- |
| Odin Magisk emulator (`emulator-5582`) | API 36 | arm64 | 16 KiB | 8/8 focused checks pass |
| POCO F7 / ReSuKiSU (`1da5425f`) | API 36 | arm64 | 4 KiB | 8/8 focused checks pass |

Each device compared **237 progress samples × 6 pixel sizes × 2 themes = 2,844 frames**
against the original native Lottie renderer. All **5,688 comparisons were pixel-identical**:
zero measured channel, alpha-area, centroid and bounds differences. The samples cover
half-frame intervals, authored keyframe/time-map boundaries, and both loop endpoints at
50, 75, 100, 138, 150 and 200 pixels. This establishes equivalence at those sampled times,
sizes and devices, rather than claiming proof for every possible Android renderer. The final
eight-test device suite repeated these comparisons after the KTX lint fixes described below;
all 5,688 comparisons remained exact.

The original phone run was interrupted by a launcher start of HomeActivity inside the
isolated test process (which intentionally has no Koin application runtime). A second
attempt was interrupted the same way. Complete reruns passed, including the final eight-test
suite after runtime Lottie removal and KTX lint fixes. Logs preserve both interruptions; they
were test-isolation failures, not Canvas rendering failures.

## Composable and terminal checks

Four wrapper tests use a controlled Compose clock and a sibling fixed-progress renderer:

- Original duration and loop restart.
- Motion scale 2 slows playback; scale 0 shows the final frame and suspends frame work;
  returning to scale 1 resumes without charging the stopped time.
- Removal cancels frame work; reentry starts at the beginning.
- Initial scale 0 and changing the in-app theme correctly update the still frame's colors.

A fifth test hosts the actual `TermLoggerContent` with harmless local state. ACTIVE and
restarted ACTIVE each produce 18 distinct sampled renders; SUCCESS produces one stable
render. Log lines remain intact and the Close callback fires exactly once when clicked.
Screenshots and a 40-frame preview are retained for both devices. No queue or privileged
operation is executed by these UI fixtures.

A sixth UI test uses the **real window Choreographer clock and PixelCopy**, rather than the
controlled Compose test clock. It passed on both devices with their existing settings unchanged:

| Target | Recorded display refresh rate | System animator duration scale | Dark ACTIVE / SUCCESS / light ACTIVE distinct renders |
| --- | ---: | ---: | --- |
| Odin emulator | 60.000004 Hz | 1.0 | 20 / 1 / 20 |
| POCO F7 | 120.00001 Hz | 0.5 | 20 / 1 / 20 |

Both ACTIVE phases kept moving across their observation windows; the assertions also check
movement in the final eight samples. SUCCESS stayed still. These checks exercise the real
`TermLoggerContent` with harmless local state, including the transition to SUCCESS and a
light-theme restart. They do not execute package operations or change either device's animation
scale. Together with two native-renderer tests, the six UI tests make the **8/8** result per device.

## Host and optimized-release status

The first required host-gate run failed with four `UseKtx` lint errors: one Canvas matrix scope
and three bitmap construction calls. These now use `withMatrix` and `createBitmap` from the
already-present core KTX dependency. The final device suite above passed after those changes.

The final required host gates and both release builds passed in **8 minutes 53 seconds**:

- **7,168 tests**, with zero failures, errors, or skipped tests.
- `lintFossDebug`: zero errors/warnings; 10 informational hints.
- `lintStoreRelease`: zero errors/warnings; 9 informational hints.
- No `MissingTranslation` or `SyntheticAccessor` findings.
- `assembleFossRelease` and `assembleStoreRelease` both succeeded.

### Measured release APK reduction

These are comparable **unsigned release APKs**, before and after the Canvas replacement.
The baseline is the separately validated dependency cleanup at `50805c05`, which already
includes merged native-library compression. These savings belong to the animation replacement;
they are not library-download sizes or raw DEX attribution estimates.

| Flavor | Before, bytes | After, bytes | Saved, bytes | Reduction |
| --- | ---: | ---: | ---: | ---: |
| FOSS | 4,706,015 | 4,628,633 | 77,382 | 1.6443% |
| Store | 4,921,200 | 4,841,054 | 80,146 | 1.6286% |

APK SHA-256 values:

```text
FOSS before 07d630fa3b169ab587c906f8f2c35cd53f3961aa5333bdc5b84498779ac1697e
FOSS after  04727092102be9f01cd9fd26b2dde2de69931ba8f5d3699c3b2a9e499d74215d
Store before 74c5c7a27bfb2203d9dac51fa2e545838f80f98c5075538d6eafcfbd4b9fbde7
Store after  eaad0b627f20398254518f4f2570c8317b4c4ae92356bac2479c1e659dd75096
```

The comparison script also verified that neither release mapping retains Lottie or the test
classes, neither release APK contains the reference animation JSON payloads, and all eight
native-library entries are byte-identical to the baseline. Full hashes and payload breakdowns
are retained in `release-comparison.json` at the artifact root.

### Minified FOSS preview

The separate minified preview passed on both devices. It renders the production
`TermLoggerContent` with R8 and resource shrinking enabled, using an artifact-only entry
activity, separate application ID, debug signing and isolated application startup. It is
not byte-identical to the production APK and does not test privileged operations.

| Target | Dark ACTIVE distinct frames | Stable SUCCESS frames | Light ACTIVE distinct frames |
| --- | ---: | ---: | ---: |
| Odin emulator | 8/8 | 1 distinct of 4 | 8/8 |
| POCO F7 | 7/8 | 1 distinct of 4 | 8/8 |

The last four samples also showed sustained movement in each ACTIVE phase. Captured
process logs contained no fatal, missing-class or native-linkage errors in the corrected run.

The first preview attempt crashed before rendering: its manifest registered
`CanvasPreviewActivity`, but the artifact init script had registered Kotlin input through
the Java source set. The phone's open crash report and ADB logs both showed that missing
test activity. The script was corrected to use the Kotlin source set; both preview classes
were verified in the actual shrunk DEX before reinstalling. Startup services were also
isolated because the preview needs no root access. This fixture correction did not change
production source or APK measurements.

The preview was uninstalled from both devices after validation. Generated device captures
were removed after archival. Normal unsigned release outputs were rebuilt, and both SHA-256
values exactly matched the measured production APKs above. No physical-device `adb root`
or changes to system animation settings were used.

## Reproduction and artifacts

Build with JDK 21 and run the host gates:

```sh
./gradlew :app:assembleFossRelease :app:assembleStoreRelease \
  test lintFossDebug lintStoreRelease --max-workers=1
./gradlew :app:assembleFossDebug :app:assembleFossDebugAndroidTest --max-workers=1
```

Install the debug and test APKs, then use `ThorTestRunner` with explicit class selectors:
`RearrangingBoxesParityTest`, `RearrangingBoxesAnimationTest`, `TermLoggerPlaybackTest`
and `TermLoggerRealClockTest`, all in `com.valhalla.thor.presentation.widgets`.
These selectors isolate the application runtime. Do not launch HomeActivity concurrently.
No `clearPackageData` option or physical-device `adb root` is needed.

Artifacts are under `~/.codex/artifacts/thor-terminal-canvas-2026-10-05/`:
`baseline/`, `parity/`, `wrapper/`, `final-device/`, `release/`, `minified-fixture/`, and the build logs. The final suite's
real-clock metadata, results, and frame hashes are under
`final-device/<serial>/rearranging-boxes-parity/real-clock/`. Device CSVs, metadata, contact
sheets and terminal screenshots are pulled from private `files/rearranging-boxes-parity/`
using `run-as com.valhalla.thor.debug`.
