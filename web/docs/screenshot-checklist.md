# Screenshot capture checklist

The site's screenshots are the strongest argument it makes, and three of the five are only correct in
one specific app state. Those constraints have to survive a UI change and a re-capture six months
from now by somebody who did not read the design spec, so they live here rather than in a commit
message.

Screenshots are a **release-checklist item, not a CI check**. UI changes; screenshots don't, and
nothing automated can tell that a frame is showing the wrong dialog.

---

## Where they go, and in what shape

| | |
|---|---|
| Location | `web/src/assets/screenshots/` |
| Format | PNG, straight off the device |
| Theme | Dark, **AMOLED off** |
| Framing | None — the site frames them |

**`src/`, not `public/`.** Astro's `<Image>` only optimises what lives under `src/`; anything in
`public/` ships byte-for-byte as committed. A 2 MB raw capture in `public/` is 2 MB on the wire.

**Commit raw captures only.** Framing is done by the site's CSS `DeviceFrame` component, wrapping an
unmodified capture. A screenshot that already has a device frame baked into the pixels comes out
double-framed, and it makes a frame restyle an image-editing job instead of a CSS one.

So: **no** device frames, drop shadows, rounded-corner masks, annotations, arrows, captions or
gradients in the image, and **no** cropping, scaling or re-encoding to JPEG.

**Dark theme with AMOLED off.** AMOLED is off by default in the app — `ThorTheme` defaults
`amoledMode = true`, but both call sites pass `prefs.useAmoled`, which defaults `false` — so the real
default dark background is `#0E0E0E`, not `#000000`. That is also the site's default dark page
colour. A pure-black AMOLED capture sitting inside a `#0E0E0E` frame reads as a hole rather than as a
screen.

### Filenames

The page sources import these by path, and Astro resolves an `<Image>` import at build time, so a
missing or renamed file is a **hard build error** rather than a broken image in production. Use these
names, or change them in the importing page in the same commit:

```
01-home-bento.png
02-freezer-active-and-frozen.png
03-refusal-unsafe-system-app.png
04-app-list-permission-chips.png
05-settings-work-mode.png
```

### Aspect ratio

Capture at the device's native resolution and do not resize. The `DeviceFrame` is built for a modern
tall phone; for reference, the ten files under
`fastlane/metadata/android/en-US/images/phoneScreenshots/` are:

| File | Pixels | Ratio |
|---|---|---|
| `1.jpg` – `9.jpg` | 1280 × 2772 | ≈ 9 : 19.5 |
| `0.png` | 1220 × 2712 | ≈ 9 : 20 |

Those ten are **store crops in the wrong app states**. They are an aspect-ratio reference and nothing
else — do not reuse one for a slot below, and do not assume the state they show is still current.
Anything between 9:19.5 and 9:20 frames cleanly; a 16:9 capture from an old device will letterbox.

---

## Two devices, and why one will not do

Captures 4 and 5 need **mutually exclusive device states**. This is a two-device job; there is no
setting that makes one device satisfy both.

| Profile | State | Covers |
|---|---|---|
| **Device A** | **No** privilege mode available at all: not rooted, Shizuku not installed, Dhizuku not installed | 4 |
| **Device B** | **At least two** privilege modes available — e.g. rooted *and* Shizuku installed | 1, 2, 3, 5 |

Capture 4 sells what Thor does when you grant it nothing, so nothing in the frame may hint that a
privilege mode exists on the device — no Work Mode chip, no "Shizuku is available" prompt, no grant
banner. Turning a mode *off* in Settings on Device B is not the same thing and will show through.

Capture 5 is the Work Mode selector, which **only renders when at least two modes are available**. On
Device A it does not exist to photograph.

---

## The five captures

### 1 — Home screen, bento grid → hero

The hero image, and the first thing anyone sees.

- Capture on a device with real data. A fresh install with empty counters makes the grid look like a
  placeholder.
- The hero crops on narrow viewports, so keep the most distinctive tile in the upper two-thirds of
  the frame.

### 2 — Freezer list, active and frozen side by side

One frame containing **both** an active app and a frozen one, so the difference is visible without a
caption.

- The frozen rows must be unmistakably distinct from the active ones — that visual difference is the
  entire point of the shot.
- The copy for this band **ends on the greyscale pinned shortcut**: a pinned launcher shortcut to a
  frozen app does not vanish, it goes greyscale and waits. Frame the shot so that detail is
  supported rather than contradicted. (That detail lives on the *launcher*, not in Thor's Freezer
  list — if the page ends up wanting it shown, it needs its own capture and a sixth slot, which is a
  decision for whoever writes the page, not a licence to substitute a launcher screenshot here.)

### 3 — The refusal dialog on an **Unsafe** system app

**No "proceed anyway" button in frame.** The refusal *is* the argument; a visible override button
destroys it. A success state would not make the same point.

- It must be an **Unsafe**-rated package, which Thor blocks outright. An **Expert**-rated package
  warns first and offers a way through — that dialog is the wrong dialog, and it is the easy mistake
  to make here because the two look similar.
- Nothing partially scrolled: the whole dialog, and enough of the list behind it to show it is a real
  app being refused.

### 4 — App list, permission filter chips expanded

**Device A only** (see above).

- Chips expanded, showing Android's own localised permission-group labels.
- Enough of the list visible behind the chips that the shot reads as a filter over real apps.

### 5 — Settings → Work Mode selector

**Device B only**, with at least two modes available — the selector does not render otherwise.

- Show the selector open, with more than one mode listed. One mode in the list is the state this
  capture exists to disprove.

---

## Capture procedure

Nothing here is exotic; it is written down so two captures taken six months apart match.

1. **Set the app up.** Dark theme, AMOLED off, the device profile from the table above.
2. **Clean the status bar** with SysUI demo mode, so a battery percentage or a notification blob does
   not date the shot:

   ```sh
   adb shell settings put global sysui_demo_allowed 1
   adb shell am broadcast -a com.android.systemui.demo -e command enter
   adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1000
   adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
   adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
   adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
   ```

3. **Capture** with `exec-out`, never `shell`:

   ```sh
   adb exec-out screencap -p > web/src/assets/screenshots/01-home-bento.png
   ```

   `adb shell screencap -p > file.png` translates LF to CRLF on the way out and produces a corrupt
   PNG on some hosts. `exec-out` is a binary-safe pipe.

4. **Leave demo mode** so the device is not stuck with a fake clock:

   ```sh
   adb shell am broadcast -a com.android.systemui.demo -e command exit
   ```

5. **Check the file**: `sips -g pixelWidth -g pixelHeight <file>` on macOS, `file <file>` anywhere.

---

## Before committing

- [ ] All five files present, named exactly as above, PNG, native resolution.
- [ ] Dark theme, AMOLED off, in every one.
- [ ] No device frame, shadow, crop, scale, annotation or JPEG re-encode.
- [ ] Capture 3 contains **no** "proceed anyway" button, and the dialog is the **Unsafe** block, not
      the Expert warning.
- [ ] Capture 4 came off a device with **no** privilege mode available, and nothing in frame implies
      otherwise.
- [ ] Capture 5 shows **two or more** modes.
- [ ] Capture 2 contains an active app and a frozen app in the same frame.
- [ ] No personal data in frame: account names, real notification text, or an app list that
      identifies the owner.
- [ ] `npm run build` passes in `web/` — a missing or misnamed file fails the build, which is how you
      find out you renamed one.

## After any UI change

Re-run this entire list. The three state-dependent constraints — the refusal dialog, the
zero-privilege app list, the Work Mode selector — are exactly the ones a re-capture gets wrong,
because a re-capture is usually done on whichever device is at hand.
