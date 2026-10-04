# Proposal: preserve the terminal animation without Lottie

Status: **analysis only**. No replacement has been implemented, no replacement APK saving has been measured, and visual equivalence has not been demonstrated.

Lottie's sole production call site is the ACTIVE branch of [`TermLoggerContent`](../../../app/src/main/java/com/valhalla/thor/presentation/widgets/TermLogger.kt), which calls [`AnimateLottieRaw`](../../../app/src/main/java/com/valhalla/thor/presentation/widgets/AnimateLottieRaw.kt) with `R.raw.rearrange`, infinite repetition, a **50 dp** square, and `ContentScale.Crop`. The proposal preserves that animation rather than substituting another loading indicator.

## What the existing files contain

The [light](../../../app/src/main/res/raw/rearrange.json) and [dark](../../../app/src/main/res/raw-night/rearrange.json) compositions have identical geometry and timing:

- A **1080 × 1080**, **60 fps** composition, containing an inert null layer and one precomposition of **seven identical rounded squares**.
- Each square is a centered 1080 × 1080 rectangle with corner radius **150**, centered stroke width **74**, and local scale **18.493%**. Only its position animates.
- The parent precomposition scales everything to **42%** and rotates it **225.1°** around `(540, 540)`; its anchor and position are both `(540, 540)`.
- There are no bitmap images, text, gradients, masks, blur, or 3D effects to reproduce.
- The only day/night differences are the seven fill/stroke colors: light uses black; dark uses white stroke and a `0.9961` RGB fill, parsed by Lottie as `#FEFEFE`.

The current wrapper selects these colors using **Thor's in-app theme**, via `LocalDarkTheme` and an overridden resource configuration. A replacement must preserve that behavior when the app and device themes differ.

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

Most moves use cubic timing control points `(0.714, 0, 0.218, 1)`. The second moves of squares 3, 2, and 1 respectively use `(0.546, 0, 0.218, 1)`, `(0.756, 0, 0.189, 1)`, and `(0.742, 0, 0.186, 1)`. Preserve the spatial tangents in the JSON as well: Lottie evaluates distance along those paths, rather than applying the easing value directly as a cubic path parameter.

### The displayed clip is time-remapped

The outer composition plays frames **13–110**, restarting rather than reversing. Lottie 6.7.1 parses the end frame as **109.99** and exposes a nominal duration of **1616 ms**. The parent maps outer frames to child time as follows; these remapping segments are linear:

| Outer frame | Child time in seconds |
| --- | ---: |
| 0 | 0 |
| 13 | 0.217 |
| 109 | 1.15 |
| 301 | 5.017 |

After Lottie's start-frame and end-frame adjustments, the displayed interval covers approximately **child frames 13.02–70.19**, not the full sequence above. The final fraction of an outer frame also enters the remapping segment after frame 109. Replaying child frames 0–98, rounding the clip to convenient keyframes, or changing the restart seam would change the current motion.

## Proposed implementation

Use one small **Compose Canvas** renderer with an immutable geometry/keyframe table and a single progress clock. Compose and the Android drawing APIs are already present, so this requires **no new dependency**.

1. Convert outer progress through the existing time map, then evaluate each square's position and easing.
2. Apply the original local and parent transforms, fill/stroke geometry, opacity, layer order, viewport scaling, and alignment.
3. Use Android `Path`/`PathMeasure` through Canvas where matching Lottie's path interpolation and rasterization requires it. Avoid replacing its curves with an approximate generic easing or a visually similar rounded box.
4. Resolve the two colors from `LocalDarkTheme`; preserve the existing loop, animation-scale behavior, and cancellation when the terminal leaves composition or changes status.
5. Expose deterministic progress to validation code. Keep the production renderer separate from its clock so the old and new renderings can be compared at precisely the same instant.

The compact table would describe only this animation, not introduce a general JSON animation interpreter. Keep the original files as validation/reference inputs outside packaged production resources until the comparison is complete.

## Alternatives

| Approach | Tradeoff |
| --- | --- |
| Compose Canvas | Best fit for the existing UI; scalable geometry, one controllable timeline, and straightforward in-app theme handling. Exact output still requires validation. |
| AnimatedVectorDrawable | Possible with platform APIs, but flattening the clipped, time-remapped timeline and partial easing segments into synchronized XML animators is more cumbersome. Looping and Compose integration also need care. |
| Animated WebP | Android's framework decoder is available at Thor's API 28 minimum. This trades vector geometry for raster assets, chosen render resolutions, and quantized frame durations. Scaling, theme variants, and final APK size would need measurement. |

## Acceptance and removal plan

Before deleting the runtime dependency, render Lottie 6.7.1 and the proposed renderer at identical fixed progress values. Include displayed keyframe boundaries, intermediate positions, the time-map boundary, and both sides of the restart seam. Compare transparent-background images and pixel-difference maps at the actual **50 dp** size across relevant densities, in both themes. Any rasterization tolerance must be explained; matching a few screenshots is not proof of the whole loop.

Then check live playback on an emulator and physical device, including 60/120 Hz where available, disabled/scaled system animations, app theme opposite to system theme, leaving/reopening the terminal, and ACTIVE → SUCCESS. Preserve existing behavior even where the source animation has a non-obvious offset or loop boundary.

Only after those checks should the production Lottie dependency, wrapper, and two JSON resources be removed together. Build comparable optimized release APKs to measure the actual saving. Library archive sizes or attributed raw DEX bytes are not an APK-saving measurement.
