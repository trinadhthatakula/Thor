# Screenshot capture checklist

The site's screenshots are the strongest argument it makes, and four of the six are only correct in
one specific app state. Those constraints have to survive a UI change and a re-capture six months
from now by somebody who did not read the design spec, so they live here rather than in a commit
message.

Screenshots are a **release-checklist item, not a CI check**. UI changes; screenshots don't, and
nothing automated can tell that a frame is showing the wrong dialog. What *is* automated is the
weaker question — `check:screenshots` counts unfilled `DeviceFrame` placeholders and fails the build
when `VERCEL_ENV=production`, so a page can never deploy with an empty slot. It cannot tell a right
capture from a wrong one. That is this document's job.

---

## Where they go, and in what shape

| | |
|---|---|
| Location | `web/src/assets/screenshots/` |
| Format | PNG, straight off the device |
| Theme | Dark, **AMOLED off** |
| Framing | None — the site frames them |
| Status bar | **Left real** — clock, battery, icons and all |

**`src/`, not `public/`.** Astro's `<Image>` only optimises what lives under `src/`; anything in
`public/` ships byte-for-byte as committed. A 2 MB raw capture in `public/` is 2 MB on the wire. The
six committed here are 188–406 kB as PNG and 53–85 kB as the webp Astro emits.

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

**Leave the status bar alone.** An earlier draft of this file prescribed SysUI demo mode to freeze
the clock at 10:00 and the battery at 100%. That was overruled by the project owner: *"having system
UI like battery and time make these look more natural"*. A real clock and a real battery level read
as a photograph of a phone somebody uses; a demo-mode bar reads as a press kit. Notifications are the
one thing worth clearing first, and swiping them away by hand does that without a broadcast.

### Filenames, and the page that imports each

The page sources import these by path, and Astro resolves an `<Image>` import at build time, so a
missing or renamed file is a **hard build error** rather than a broken image in production. Use these
names, or change them in the importing page in the same commit:

| File | Imported by |
|---|---|
| `01-home-bento.png` | `index.astro` (hero), `features.mdx` |
| `02-freezer-active-and-frozen.png` | `index.astro`, `features.mdx` |
| `03-refusal-unsafe-system-app.png` | `index.astro`, `features.mdx` |
| `04-app-list-permission-chips.png` | `index.astro` |
| `05-settings-work-mode.png` | `index.astro`, `features.mdx` |
| `06-extension-manager.png` | `privacy.mdx` |

**There are six, not five.** Slot 6 is easy to miss because it is the only one on `privacy.mdx` and
the only one used by exactly one page. `grep -rn "assets/screenshots" src/pages/` is the check that
does not depend on this table staying current.

### Aspect ratio

Capture at the device's native resolution and do not resize. The `DeviceFrame` is built for a modern
tall phone; the six committed captures are 1280 × 2772 (phone, ≈ 9 : 19.5) except slot 4, which is
1080 × 2400 (emulator, 9 : 20). For further reference, the ten files under
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
| **Device B** | **At least two** privilege modes available — e.g. rooted *and* Shizuku installed | 1, 2, 3, 5, 6 |

Capture 4 sells what Thor does when you grant it nothing, and its alt text says so out loud — *"on a
device with no privilege mode enabled"* — so nothing in the frame may hint that a privilege mode
exists on the device. No Work Mode chip, no "Shizuku is available" prompt, no grant banner, and **no
snowflake badges on the list rows**, which is the one that catches people: Device B's app list badges
every frozen app, and a frozen app is proof of privilege. Turning a mode *off* in Settings on Device
B is not the same thing and will show through.

Capture 5 is the Work Mode selector, which **only renders when at least two modes are available**. On
Device A it does not exist to photograph.

Everything except slot 4 belongs on the real phone. The owner's standing call: *"we should use
physical device for all screenshots, i don't mind getting my app list out, there's nothing to hide
anyway"*, and *"in cases where we need more than one device we can use emulator"*. An emulator frame
is visibly an emulator — uniform icon set, stock wallpaper, no carrier — so restrict it to the one
slot that genuinely requires a device with nothing granted.

**Uninstalling Shizuku to make Device A**: pull the APK first, so the device can be put back.

```sh
adb -s <serial> shell pm path moe.shizuku.privileged.api   # → package:/data/app/.../base.apk
adb -s <serial> pull <that path> /tmp/shizuku.apk
adb -s <serial> uninstall moe.shizuku.privileged.api
# … capture …
adb -s <serial> install /tmp/shizuku.apk
```

---

## The six captures

### 1 — Home screen, bento grid → hero

The hero image, and the first thing anyone sees.

- **The alt text promises three counts, so three counts must render.** `SummaryStatRow` drops the
  Frozen tile when `frozenCount == 0` and the Suspended tile when `suspendedCount == 0`. A device
  with nothing suspended renders two tiles under an alt that says "active, frozen and suspended" —
  which is the kind of quiet lie this whole document exists to prevent. Suspending two or three
  disposable apps is enough (`adb shell pm suspend <pkg>` works from a plain shell, no root needed);
  unsuspend them afterwards and verify with
  `adb shell dumpsys package | grep -c 'suspended=true'` → `0`. Frozen means `!enabled`
  (`pm disable`); suspended means `isSuspended && enabled` (`pm suspend`). Different states, both
  tiles conditional.
- Capture on a device with real data. A fresh install with empty counters makes the grid look like a
  placeholder.
- The hero crops on narrow viewports, so keep the most distinctive tile in the upper two-thirds of
  the frame.
- **This screen renders no app names and no app icons.** The only place a package can leak into the
  frame is the App Distribution legend, whose fallback label for an unrecognised installer is
  `pkg.substringAfterLast(".").uppercase()`. Read the legend before shipping the frame.
- `Clear All Cache` appearing on the bento grid is root-only (`HomeAction.CLEAR_CACHE.takeIf {
  isRoot }`) and is the only thing in a UI dump that proves the active mode is Root — the privilege
  status icon's content-description is the constant `"Privilege Check"` in every mode.

### 2 — Freezer list, active and frozen side by side

One frame containing **both** an active app and a frozen one, so the difference is visible without a
caption.

- The frozen rows must be unmistakably distinct from the active ones — that visual difference is the
  entire point of the shot.
- **The Freezer list is only the watchlist** (`allApps.filter { it.packageName in pkgSet }`), and
  **you cannot add an app to the watchlist without freezing it** — `toggleManaged(add = true)`
  freezes first, then adds. So a watchlist can easily contain nothing but frozen apps, and the way to
  create the contrast is to *unfreeze* something already on the list, shoot, then re-freeze it.
  Verify the restore: `adb shell dumpsys package <pkg> | grep enabled=` reads `enabled=2` when
  frozen.
- The System tab is likely to be empty — a watchlist of user apps has no system entries, and the tab
  reads "No matching apps". Shoot the User tab. Its `appListType` toggle is **not persisted** and
  resets to USER on every cold start anyway.
- The copy for this band **ends on the greyscale pinned shortcut**: a pinned launcher shortcut to a
  frozen app does not vanish, it goes greyscale and waits. Frame the shot so that detail is
  supported rather than contradicted. (That detail lives on the *launcher*, not in Thor's Freezer
  list — if the page ends up wanting it shown, it needs its own capture and a seventh slot, which is
  a decision for whoever writes the page, not a licence to substitute a launcher screenshot here.)

### 3 — The refusal dialog on an **Unsafe** system app

**No "proceed anyway" button in frame.** The refusal *is* the argument; a visible override button
destroys it. A success state would not make the same point.

- It must be an **Unsafe**-rated package, which Thor blocks outright: `AppRiskDialog`'s BLOCKED
  branch emits no confirm button at all, so the dialog carries only "Close". An **Expert**-rated
  package warns first and offers a live "Freeze Anyway" — that dialog is the wrong dialog, and it is
  the easy mistake to make here because the two look similar. `com.android.settings.intelligence` is
  Expert-rated and is the exact destructive look-alike; do not use it.
- Nothing partially scrolled: the whole dialog, and enough of the list behind it to show it is a real
  app being refused.

### 4 — App list, permission filter chips

**Device A only** (see above).

- The chip row is a `Modifier.horizontalScroll` Row — **not** a FlowRow, and not expandable. "Chips
  expanded" is not a state that exists; the row is simply visible above the list. Chip labels come
  from `PackageManager.getPermissionGroupInfo(...).loadLabel(pm)`, which is what lets the copy claim
  Android's own localised labels.
- **Select a chip.** An unfiltered list on a bare emulator sorts alphabetically into overlay
  packages — `2 Button Navigation Bar`, `android.auto_generated_rro_vendor__` — which is a useless
  frame. Filtering by *Camera* yields recognisable apps (Camera, Chrome, Drive, Gboard, Gmail, Maps,
  Messages, Phone, Settings) and demonstrates the feature instead of merely showing it. **The alt
  text names the chip that was selected**, so changing the chip means changing the alt.
- Grid view mode reads better than List here.
- Enough of the list visible behind the chips that the shot reads as a filter over real apps.

### 5 — Settings → Work Mode selector

**Device B only**, with at least two modes available — the selector does not render otherwise.

- **It is an inline `ConnectedButtonGroup`, not a dialog.** "Show the selector open" means only
  "scroll it into frame". There is nothing to open.
- Show more than one mode listed. One mode in the list is the state this capture exists to disprove.
- **Tapping a mode button persists immediately** via `setPrivilegeMode` — there is no confirm step.
  If you tap one to compose the shot, tap the device's real mode back afterwards.

### 6 — Extension Manager → `privacy.mdx`

The one screen in Thor whose own code reaches the network, and the page says exactly that. This
capture is doing evidentiary work: the claim is that this screen, and only this screen, makes a
request.

- It must be the **Extension Manager**, not the app list, not Settings. The surrounding prose is
  "only when you open the Extension Manager", and a frame showing anything else quietly widens the
  claim.
- No installed-extension state is required, and none is forbidden. Either reads correctly.

---

## Capture procedure

Nothing here is exotic; it is written down so two captures taken six months apart match.

1. **Set the app up.** Dark theme, AMOLED off, the device profile from the table above, and the
   per-slot state from the section above.
2. **Clear notifications** by hand if the shade has anything personal in it. Do **not** enter SysUI
   demo mode — the real clock and battery are wanted (see above).
3. **Capture** with `exec-out`, never `shell`:

   ```sh
   adb -s <serial> exec-out screencap -p > web/src/assets/screenshots/01-home-bento.png
   ```

   `adb shell screencap -p > file.png` translates LF to CRLF on the way out and produces a corrupt
   PNG on some hosts. `exec-out` is a binary-safe pipe. Always pass `-s <serial>` when both a phone
   and an emulator are attached.

4. **Put the device back.** Anything changed to compose a shot — suspended apps, an unfrozen app, an
   uninstalled Shizuku, a switched work mode, a changed list filter — gets restored and *verified*,
   not merely re-tapped.
5. **Check the file**: `sips -g pixelWidth -g pixelHeight <file>` on macOS, `file <file>` anywhere.

---

## Before committing

- [ ] All six files present, named exactly as above, PNG, native resolution.
- [ ] Dark theme, AMOLED off, in every one.
- [ ] No device frame, shadow, crop, scale, annotation or JPEG re-encode.
- [ ] Capture 1 shows **all three** count tiles, matching the alt text.
- [ ] Capture 2 contains an active app and a frozen app in the same frame.
- [ ] Capture 3 contains **no** "proceed anyway" button, and the dialog is the **Unsafe** block, not
      the Expert warning.
- [ ] Capture 4 came off a device with **no** privilege mode available and **no** frozen apps, and
      nothing in frame implies otherwise.
- [ ] Capture 5 shows **two or more** modes.
- [ ] Capture 6 is the Extension Manager.
- [ ] **Every alt and caption re-read against its frame.** These are claims the images make, they are
      not checked by `check:claims`, and they are where a re-capture goes wrong silently. Slot 1's alt
      names three counts; slot 4's names the selected chip.
- [ ] Every device restored, and the restore verified.
- [ ] `npm run build` passes in `web/` — a missing or misnamed file fails the build, which is how you
      find out you renamed one — and `check-screenshots` reports `0 placeholders`.

## After any UI change

Re-run this entire list. The four state-dependent constraints — the three home-screen counts, the
refusal dialog, the zero-privilege app list, the Work Mode selector — are exactly the ones a
re-capture gets wrong, because a re-capture is usually done on whichever device is at hand.
