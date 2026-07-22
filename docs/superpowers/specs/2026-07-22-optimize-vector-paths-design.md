# Design: Lossless VectorDrawable path optimization

**Date:** 2026-07-22
**Branch:** `chore/optimize-vector-paths` (off `dev`)
**Status:** Design — pending user review

## Problem

Android lint's `VectorPath` warning flags three drawables whose `pathData` exceeds
~800 characters. Long `pathData` strings inflate the APK and slow VectorDrawable
inflation at runtime. We want to shrink them **without changing a single rendered
pixel**.

| File | `pathData` size (chars) | Notes |
|---|---|---|
| `app/src/main/res/drawable/dhizuku.xml` | 3220 (1 path) | viewport 240×240, absolute commands, 2-decimal coords, values mostly > 1 → dense |
| `app/src/main/res/drawable/thor_drawn_foreground.xml` | 5185 (1 path in a `<group>`) | viewport 135.47, group `scale 0.501875` + `translate 33.740498`, mixed abs/rel, many `0.xx` / `0,0` |
| `app/src/main/res/drawable/thor_mono.xml` | 10591 / 5112 / 8817 (3 paths) | viewport 135.47, relative commands, dense `0.xx` / `-0.xx`, contains negative-zero tokens (`-0,0.41`) |

## Acceptance bar (decided with user)

**Lossless, pixel-identical.** Only string-encoding compaction is permitted — every
coordinate's *numeric value* is preserved exactly. No rounding, no precision
reduction. Consequence, explicitly accepted by the user:

- The `VectorPath` warning will **not** clear (all three stay well above 800 chars
  even after compaction). The deliverable is a modestly smaller APK + provably
  identical rendering, **not** a lint-count change.
- Expected reduction: `dhizuku` ~5% (already dense), `thor_drawn_foreground`
  ~15–25%, `thor_mono` ~10–15% (leading-zero + sign-separator wins on its many
  negative `0.xx` coords).

## The optimizer — strictly value-preserving

Pure-JS. Tokenizes each `pathData` into `(command-letter, numeric-operands...)` and
re-emits with the minimal textual form. Every transform below is unambiguously
supported by **both** Android's `PathParser` and the SVG path grammar:

1. **Leading-zero strip:** `0.5` → `.5`, `-0.5` → `-.5`.
2. **Trailing-zero / bare-decimal strip:** `1.230` → `1.23`, `2.0` → `2`.
3. **Negative-zero normalize:** the number `-0` (and `-0.0`, `-0.00`) → `0`
   (value-identical; avoids the leading-zero rule producing a bare `-`).
4. **Sign-as-separator:** drop the whitespace before a negative operand,
   `.14 -.1` → `.14-.1` (a leading `-` is itself a valid operand separator).
5. **Implicit repeated command:** merge consecutive operand groups that share the
   **same** command letter, `L1 2 L3 4` → `L1 2 3 4`. Never merged across a
   `moveto` (`M`/`m`), since trailing pairs after a moveto are re-interpreted as
   `lineto` — merging there would change semantics.

**Explicitly excluded** (too risky for a pixel-identical bar):

- **Implicit decimal separator** (`0.5 0.5` → `.5.5`): the one place Android's
  `PathParser` behaviour *could* differ from SVG across API levels. Left OFF; the
  few extra percent aren't worth the risk. (Configurable flag, default off.)
- **No-op segment removal, path merging, precision reduction, `arc`↔`curve`
  rewriting** — all change or risk changing geometry.

**Command case (`M` vs `m`, `L` vs `l`, `C` vs `c`) is preserved verbatim** —
absolute vs relative is geometry, never touched.

## Verification harness — why we can trust the result

The optimizer changes only the *string form* of numbers, so a correct optimizer
yields byte-identical rendering *by construction*. The harness exists to **catch
optimizer/converter bugs** (e.g. a botched separator that silently merged two
numbers into one).

Pipeline, per file, per path:

1. **VD → SVG** (pure-JS converter): `android:viewportWidth/Height` → `viewBox`;
   each `<group>` `scaleX/Y` + `translateX/Y` → SVG
   `transform="translate(tx,ty) scale(sx,sy)"` (matching AOSP `VGroup` matrix
   order: translate-pivot → scale → rotate → translate); `fillColor` → `fill`,
   `fillType="evenOdd"` → `fill-rule="evenodd"` (default nonzero otherwise);
   stroke attrs mapped through. The **same** wrapper is used for before and after
   so any wrapper quirk cancels between the two renders.
2. **Render** `before.svg` and `after.svg` with `rsvg-convert` (librsvg 2.62.3,
   cairo backend) at **96 / 512 / 2048 px**, on **transparent and solid**
   backgrounds. Large sizes make the gate sensitive — a tiny coordinate corruption
   that rounds away at 96px shows as real pixels at 2048px. Solid background
   surfaces any `fill-rule`/alpha divergence.
3. **Diff gate:** `magick compare -metric AE before.png after.png diff.png`.
   **Gate: `AE == 0`** (zero differing pixels) at every size/background. Any
   `AE > 0` rejects that file's change and triggers investigation.
4. **Review composite:** `magick montage before.png after.png diff.png` → one
   `[before | after | diff]` image per file for **manual eyeball sign-off**.

Renderer choice: `rsvg-convert` (accurate, deterministic, headless) over
ImageMagick's internal MSVG renderer (its `rsvg` delegate is now satisfied by the
installed librsvg). ImageMagick is used only for `compare` and `montage`.

**Correctness chain:** conservative Android-safe transforms (parser-level safety) +
`AE == 0` at 2048px (optimizer-bug safety) + the user's existing on-device smoke
test (runtime safety). librsvg is a proxy renderer, not Android; the on-device
check remains the final word.

## Tooling constraints

- Harness scripts are analysis/computation only → run via
  `ctx_execute` (pure JS, Node built-ins, no npm). `rsvg-convert` / `magick`
  invoked via Bash (read-only, produce files in a scratch dir, not the repo).
- The three `.xml` files are edited only via the Edit/Write tools.
- Scratch renders/diffs/montages live in a temp dir outside the repo (never
  committed).

## Flow & delivery

1. Branch `chore/optimize-vector-paths` off `dev` (done).
2. Build the VD→SVG converter + optimizer + rsvg/magick harness.
3. Per file: optimize → gate `AE == 0` (all sizes/bgs) → montage → **user visual
   sign-off**. Keep the change only if both pass.
4. Rebuild lint (`./gradlew lint`) to report the (expected-unchanged) `VectorPath`
   count and record byte savings per file.
5. Commit → PR to `dev` after user approval.

## Out of scope

- Clearing the `VectorPath` warning (impossible under the lossless bar; accepted).
- Re-drawing / re-authoring the icons at fewer nodes (that is lossy re-design, a
  separate effort).
- Any other drawable not on the three-file list.
