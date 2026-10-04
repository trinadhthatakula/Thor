# Preserving the terminal animation without runtime Lottie

Status: **implemented and validated locally**. Both devices passed 8/8 focused checks and the separate minified FOSS preview. Required host gates and both release builds passed. Measured unsigned APK reductions are **77,382 bytes for FOSS** and **80,146 bytes for Store**.

Lottie's former sole production call site was the ACTIVE branch of [`TermLoggerContent`](../../../app/src/main/java/com/valhalla/thor/presentation/widgets/TermLogger.kt). It now calls [`RearrangingBoxesAnimation`](../../../app/src/main/java/com/valhalla/thor/presentation/widgets/RearrangingBoxes.kt), preserving the **50 dp** square, centered crop scaling, and repeating seven-box animation. The old wrapper, production JSON resources, and runtime Lottie dependency have been removed. Base Lottie 6.7.1 remains **androidTest-only** as the independent rendering reference; it does not ship in release APKs.

## Original animation retained as test fixtures

The original [light](../../../app/src/androidTest/assets/animations/rearranging-boxes-light-reference.json) and [dark](../../../app/src/androidTest/assets/animations/rearranging-boxes-dark-reference.json) compositions are retained unchanged in instrumentation-test assets. They have identical geometry and timing:

- A **1080 × 1080**, **60 fps** composition, containing an inert null layer and one precomposition of **seven identical rounded squares**.
- Each square is a centered 1080 × 1080 rectangle with corner radius **150**, centered stroke width **74**, and local scale **18.493%**. Only its position animates.
- The parent precomposition scales everything to **42%** and rotates it **225.1°** around `(540, 540)`; its anchor and position are both `(540, 540)`.
- There are no bitmap images, text, gradients, masks, blur, or 3D effects to reproduce.
- The only day/night differences are the seven fill/stroke colors: light uses black; dark uses white stroke and a `0.9961` RGB fill, parsed by Lottie as `#FEFEFE`.

The former wrapper selected these colors using **Thor's in-app theme**, via `LocalDarkTheme` and an overridden resource configuration. The new composable reads `LocalDarkTheme` directly, preserving that selection when the app and device themes differ.

### Position keyframes

Coordinates and frame numbers below belong to the **child composition**, before the parent transform and time remapping. Positions hold before the first and after the last applicable keyframe.

| Square | Position sequence, with child frame in parentheses |
| --- | --- |
| 7 | `(239.25, 840.25)` (41.236) → `(239.25, 540)` (75.599609375) |
| 6 | `(239.25, 540)` (34.363) → `(239.25, 239.5)` (68.7265625) |
| 5 | `(239.25, 239.5)` (27.49) → `(540, 239.5)` (61.853515625) |
| 4 | `(540, 239.5)` (20.617) → `(839.5, 239.5)` (54.98046875) |
| 3 | `(839.5, 239.5)` (13.746) → `(839.5, 540)` (48.109); hold until 64.908; → `(540, 540)` (98) |
| 2 | `(839.5, 540)` (6.873) → `(540, 540)` (41.236); hold until 56; → `(539.5, 840.25)` (89.091796875) |
| 1 | `(540, 540)` (0) → `(539.5, 840.25)` (34.363); hold until 48.109; → `(239.25, 840.25)` (80.181640625) |

Most moves use cubic timing control points `(0.714, 0, 0.218, 1)`. The second moves of squares 3, 2, and 1 respectively use `(0.546, 0, 0.218, 1)`, `(0.756, 0, 0.189, 1)`, and `(0.742, 0, 0.186, 1)`. The renderer also preserves the JSON's spatial tangents: Lottie evaluates distance along those paths, rather than applying the easing value directly as a cubic path parameter.

### The displayed clip is time-remapped

The outer composition plays frames **13–110**, restarting rather than reversing. Lottie 6.7.1 parses the end frame as **109.99** and exposes a nominal duration of **1616 ms**. The parent maps outer frames to child time as follows; these remapping segments are linear:

| Outer frame | Child time in seconds |
| --- | ---: |
| 0 | 0 |
| 13 | 0.217 |
| 109 | 1.15 |
| 301 | 5.017 |

After Lottie's start-frame and end-frame adjustments, the displayed interval covers approximately **child frames 13.02–70.19**, not the full sequence above. The final fraction of an outer frame also enters the remapping segment after frame 109. Replaying child frames 0–98, rounding the clip to convenient keyframes, or changing the restart seam would change the current motion.

## Implementation and rendering contract

One **Compose Canvas** renderer uses a fixed geometry/keyframe table and a single progress clock. Compose and the Android drawing APIs were already present, so the replacement adds **no new production dependency**.

```kotlin
internal class RearrangingBoxesRenderer {
    fun draw(canvas: android.graphics.Canvas, width: Int, height: Int, progress: Float, darkTheme: Boolean)
}
```

`progress` is normalized across the displayed outer clip, from 0 to 1. The renderer converts it through the original time map, evaluates each square, and applies the original transforms, geometry, colors, layer order, and viewport placement. Android `PathInterpolator`, `Path`, and `PathMeasure` preserve interpolation and rasterization. Density scaling and float/double operation order follow the original renderer; the fill transforms its path while the stroke transforms the canvas. The renderer reuses its drawing objects and is intended for one drawing thread.

The public composable keeps the renderer separate from its clock, reads progress during drawing, and uses `LaunchedEffect`/`withInfiniteAnimationFrameNanos` for cancellable playback. Its clock retains Lottie 6.7.1's whole-millisecond frame deltas and 1616 ms nominal duration, including immediate wrap when the next progress reaches exactly 1. Replacing those calculations with a generic tween would alter timing.

The clock now observes Compose's live `MotionDurationScale`. At zero scale it shows the final frame and suspends frame polling; it resumes when the scale becomes positive. This retains the former disabled-animation appearance while improving responsiveness to setting changes: the old Lottie wrapper read the global setting only when recomposed. Separate clock, scale, disposal, and theme integration checks passed as described below.

The fixed table describes only this animation; there is no production JSON animation interpreter. The original JSON files and Lottie reference renderer belong only to the test APK.

## Alternatives considered

| Approach | Tradeoff |
| --- | --- |
| Compose Canvas | Selected: scalable geometry, one controllable timeline, and straightforward in-app theme handling. All sampled native-Canvas frames matched the reference exactly on both tested devices. |
| AnimatedVectorDrawable | Possible with platform APIs, but flattening the clipped, time-remapped timeline and partial easing segments into synchronized XML animators is more cumbersome. Looping and Compose integration also need care. |
| Animated WebP | Android's framework decoder is available at Thor's API 28 minimum. This trades vector geometry for raster assets, chosen render resolutions, and quantized frame durations. Scaling, theme variants, and final APK size would need measurement. |

## Completed frame validation

[`RearrangingBoxesParityTest`](../../../app/src/androidTest/java/com/valhalla/thor/presentation/widgets/RearrangingBoxesParityTest.kt) renders the original JSON through Lottie 6.7.1 and the new renderer onto native Android canvases at identical fixed progress. Its sample set includes half-frame intervals across the clip, source keyframe boundaries, the time-map boundary, and loop endpoints. Expected pixels come from the independent Lottie renderer, not from the replacement's calculations.

Both the **Odin Magisk emulator** and **POCO/ReSuKiSU physical device** passed **2/2 renderer tests**. Each device compared **237 progress samples × 6 sizes × 2 themes = 2,844 frames**: **5,688 frame comparisons** in total. Target sizes were 50, 75, 100, 138, 150, and 200 pixels, covering representative 50 dp render sizes. The recorded mean and maximum pixel errors were **zero** on both devices. The final eight-test device suite repeated these comparisons after the KTX lint fixes, with all 5,688 comparisons still exact.

This establishes exact agreement for those sampled native-Canvas outputs. It does not by itself establish every possible progress value, GPU rendering configuration, or composable clock/lifecycle behavior.

## Completed composable and terminal validation

All four [`RearrangingBoxesAnimationTest`](../../../app/src/androidTest/java/com/valhalla/thor/presentation/widgets/RearrangingBoxesAnimationTest.kt) checks passed on both devices. They cover duration/restart, slowed and disabled motion, resuming without charging stopped time, disposal/reentry, and in-app theme colors. A controlled-clock test of the actual `TermLoggerContent` also passed: ACTIVE and restarted ACTIVE moved, SUCCESS remained stable, logs stayed intact, and Close fired once.

The real-window-clock test passed separately, using Choreographer and PixelCopy. The emulator ran at its recorded **60.000004 Hz** and animator scale **1.0**; the phone ran at **120.00001 Hz** and scale **0.5**. Neither setting was changed. On each device, dark ACTIVE produced **20 distinct renders**, SUCCESS **one**, and light ACTIVE **20**. Assertions also confirmed movement in the final eight samples of each ACTIVE observation window, guarding against an animation that starts and then stalls.

Together, two renderer tests, four wrapper tests, and the two terminal playback tests passed **8/8 on each device**. These fixtures use harmless local terminal state and do not execute privileged operations. See [terminal validation](../../validation/terminal-canvas.md) for artifact locations and reproduction details.

## Completed host gates and release measurements

The initial host-gate run reported four `UseKtx` errors. The Canvas scope and three bitmap calls now use existing core KTX APIs; the final device suite and the required host-gate rerun both passed after those fixes. The final run completed in **8 minutes 53 seconds**, with **7,168 tests and zero failures/errors/skips**. FOSS Debug and Store Release lint reported zero errors or warnings, with 10 and 9 informational hints respectively; there were no `MissingTranslation` or `SyntheticAccessor` findings. Both optimized release APK builds passed.

Compared with the validated dependency-cleanup baseline `50805c05`, using unsigned release APKs on both sides:

| Flavor | Before, bytes | After, bytes | Saved, bytes | Reduction |
| --- | ---: | ---: | ---: | ---: |
| FOSS | 4,706,015 | 4,628,633 | 77,382 | 1.6443% |
| Store | 4,921,200 | 4,841,054 | 80,146 | 1.6286% |

Release inspection confirmed no Lottie or test classes in either mapping, no reference JSON payloads in either APK, and byte-identical native libraries. These are measured APK reductions for the replacement, not sums of dependency archive sizes. [Terminal validation](../../validation/terminal-canvas.md) records the SHA-256 values and the `release-comparison.json` artifact containing full payload breakdowns.

The **minified FOSS preview passed on both devices**, with sustained motion in light/dark
ACTIVE states, a stable SUCCESS state, and no fatal/linkage errors in the corrected run.
The artifact-only preview used an isolated entry activity with R8 enabled; it is not the
byte-identical production APK. Its initial missing-activity packaging error, correction,
DEX preflight, device observations and cleanup are recorded in the validation report.
The preview was removed, and restored production APK hashes match the measured outputs.
