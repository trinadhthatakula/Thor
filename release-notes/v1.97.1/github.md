# Thor v1.97.1 Release Notes

Version **1.97.1 (1971)** is the stable candidate covering **everything added after v1.96.0
(1960)** through `f3a863fa`. It carries forward the **1.97.0 dev candidate (1970)**, which
reached alpha/internal but was not promoted to beta or production, and includes PRs #554–#561.
It also includes **v1.96.1**, **v1.96.2 development builds**, **v1.96.3**, and the changes
already documented for 1.97.0. Features present in v1.96.0 are outside this summary.

The cumulative comparison is
[`v1.96.0...f3a863fa`](https://github.com/trinadhthatakula/Thor/compare/v1.96.0...f3a863fa31da381553abea02236a685fa49df8f5).
The latest stable release is **v1.96.3**. The 1970 dev prerelease is
[`v1.97.0-dev-127`](https://github.com/trinadhthatakula/Thor/releases/tag/v1.97.0-dev-127);
the sections marked *new since 1.97.0 dev* identify changes testers have not yet received.
Earlier post-1960 notes remain available for
[v1.96.1](https://github.com/trinadhthatakula/Thor/blob/51d118b649fb03171c8de929b5608374fe1b6d29/release-notes/v1.96.1/github.md),
[v1.96.2](https://github.com/trinadhthatakula/Thor/blob/51d118b649fb03171c8de929b5608374fe1b6d29/release-notes/v1.96.2/github.md), and
[v1.96.3](https://github.com/trinadhthatakula/Thor/blob/51d118b649fb03171c8de929b5608374fe1b6d29/release-notes/v1.96.3/github.md).
Descriptions here reflect the final implementation, including later reliability fixes.

## ✨ Highlights

- 🛡️ **Privilege Check becomes a bottom sheet** — replaces the previous dialog, adds header
  Refresh/Close icons and grant ticks, and keeps identified managers ready to Open. Discover
  ReSukiSU/SukiSU Ultra or choose a shortcut for another hidden root manager.
- 🔍 **System-app UAD recommendations** — filter system apps by Recommended, Advanced, Expert,
  Unsafe or Unknown advice; User and System tabs remember their own filter choices.
- 🛠️ **Sett Edit in Extensions** — inspect Android settings, preview individual changes, verify
  saved values and retain edit history with Root or Shizuku.
- ⏸️ **Suspended apps and Unpause** — find suspended user/system apps together and use Android's
  supported native Unpause action.
- 🛡️ **More accurate root status and safer operations** — coordinate fresh authorization with
  accepted work, retain uncertain outcomes, and improve selected export/game-file copies,
  app-data clearing and installation ownership.
- ❄️ **Dhizuku and Shizuku improvements** — device-owner hiding and supported actions, plus a
  configurable recovery fallback for affected Shizuku system-app freezes.
- 🗂️ **Freezer profiles and custom multi-app actions** — assign selections to profiles, choose how
  to recover frozen apps, and arrange separate App list/Freezer toolbars.
- 🔍 **App Ops and draggable scrollbars** — inspect and adjust supported operation modes and
  move through long app lists or grids.
- 🇯🇵 **Japanese, accessible confirmation and optional support** — Japanese localization,
  scrollable Extensions consent controls, and support shortcuts after successful actions.
- 📦 **Smaller standalone APKs** — one variable Outfit font and compressed native libraries
  reduce direct-download APK size; the terminal animation now renders with Compose Canvas.

## What's Changed

### 🔍 System-app UAD filters — new since 1.97.0 dev

[PR #555](https://github.com/trinadhthatakula/Thor/pull/555) (`bee48ba7`):

- System apps gain a **UAD recommendation** filter with All, Recommended, Advanced, Expert,
  Unsafe and Unknown choices. The category is absent from User apps. Recommendations help
  navigate the list; they are not a safety guarantee for disabling or removing an app.
- User and System tabs remember category and chip selections independently. Existing shared
  filter settings are inherited until each tab's profile is changed, preserving upgrade behavior.
- Metadata loading and failures stay distinct from Unknown advice. The app list remains usable
  with All while advice is unavailable, and cached apps receive later metadata updates.
- The suspended-only view does not overwrite either tab's saved filter profile.

### 🎨 Outfit font and terminal animation — new since 1.97.0 dev

[PR #556](https://github.com/trinadhthatakula/Thor/pull/556)
(`5aa4cc11`) and [PR #558](https://github.com/trinadhthatakula/Thor/pull/558)
(`4ce8d91f`):

- Nine static Outfit files become one variable font, preserving the nine weight choices,
  typography metrics and font picker behavior. A platform font loader keeps the weights
  distinct on the tested HyperOS phone as well as the emulators.
- The terminal's seven-box animation uses Compose Canvas with the original timing and theme
  colors. Runtime Lottie and its JSON payloads are removed; reference renderers remain in tests.
  Sampled before/after frames were pixel-identical on the tested emulator and phone.
- The font and animation changes reduce both standalone release APKs. Native-library
  compression adds a separate reduction for directly downloaded APKs; Play bundle device
  splits retain their normal native-library loading behavior.

### 🛠️ Built-in Sett Edit and edit-history reconciliation — new since v1.96.3

[PR #530](https://github.com/trinadhthatakula/Thor/pull/530),
[PR #539](https://github.com/trinadhthatakula/Thor/pull/539),
[PR #544](https://github.com/trinadhthatakula/Thor/pull/544), and
[PR #545](https://github.com/trinadhthatakula/Thor/pull/545)
(`0b309463`, `f64d4800`, `ad1fd5ee`, `98793e74`, `3d175607`):

- **Extensions → Sett Edit** is bundled with Thor and needs no separate download. It supports
  Root or Shizuku and stays unavailable with Dhizuku selected or without a supported provider.
- Six searchable views cover **System, Secure, Global, Android properties, Java properties and
  Environment**. Only System/Secure/Global are editable; these are Android's shared settings,
  not an individual app's preferences. System/Secure use the current Android user; Global is
  shared device state. Java properties and Environment describe Thor's own process.
- Separate device-local first-use consent precedes editing. Add, edit and delete show the exact
  old/new values and scope. Reads preserve absent, SQL-null, empty and literal-null distinctions.
  Fresh conflict checks precede writes; readback confirms the stored value rather than promising
  that Android applied the intended behavior.
- History records original/requested values and verified, rejected, conflicting or uncertain
  outcomes. Undo is available only for verified edits whose current value still matches Thor's
  recorded result; it does not promise to reverse every effect of a setting.
- **Check current value** records a later read-only observation for uncertain history. Matching
  readback does not retroactively verify the original write, release an unresolved writer, or
  enable undo. No uncertain command is automatically replayed.
- Root helpers have bounded execution and explicit termination/output-drain acknowledgement.
  Unconfirmed writes retain a durable barrier for their exact setting, including against a later
  Shizuku write. New shells, provider switching and app restart alone cannot prove a writer stopped.

Android settings can affect debugging, input, accessibility and device startup. Review only changes
you understand. Android/OEM normalization can reject a requested value, and the pre-write conflict
check is not an atomic compare-and-set. Presets, bulk import, app-preference editing and Android
property mutation remain separate work.

### ⏸️ Suspended-app list and Android's native Unpause — new since v1.96.3

[PR #529](https://github.com/trinadhthatakula/Thor/pull/529)
(`a92aaacc`, `d24b97e6`), addressing [issue #522](https://github.com/trinadhthatakula/Thor/issues/522):

- Home's **Suspended** counter opens a combined list of suspended user and system apps. It hides
  when empty. Search, sorting, grid/list view, actions and queue navigation preserve the ordinary
  Apps screen's filters and keep the dedicated suspension view consistent.
- Root/Shizuku suspensions request Android's native **Unpause app** action on Android 11/API 30+.
  Where supported, Android removes the selected suspension and continues the original launch.
- Dhizuku retains Android's device-policy notice and Thor's in-app Unsuspend. Older Android
  versions and OEM fallback paths can lack the native button. Existing suspensions keep their
  original dialog until recreated, and another suspension owner's policy may still apply.
- Supported suspension overloads use ordinary reflection, avoiding a native crash encountered
  when an absent overload was attempted through the unsafe reflection fallback.

### 🛡️ Privilege Check bottom sheet and manager shortcuts — new since v1.96.3

[PR #553](https://github.com/trinadhthatakula/Thor/pull/553)
(`e081e91f`, `be66be4d`, `02b40176`, `d2465d9e`, `edb79102`, `fa86a8ab`):

- **The previous Privilege Check dialog is replaced by a scrollable bottom sheet.** Refresh and
  Close are header icon actions. The searchable manager picker is also a bottom sheet with a
  header Close action and room for results above the keyboard.
- Manager rows show compact app icons; root managers also show their actual package names.
  **Green ticks mean granted access**, not simply that a manager was found or selected. Root's
  tick requires an idle, confirmed root observation; checking/unknown states remain visible.
- **Open remains available for identified and selected managers**, including while access is
  granted or root is being checked. Shizuku and Dhizuku retain their supported Grant actions
  when permission is missing.
- Adds **ReSukiSU** and **SukiSU Ultra** discovery alongside existing managers. Renamed builds
  are recognized when they retain an exact known launcher activity; names and labels alone
  do not identify a root manager or prove that Thor has root access.
- **Choose** saves a device-local shortcut to a launchable app for managers with other hidden
  or randomized identities. **Change** reopens the picker; **Clear** removes only that saved
  shortcut and restores automatic discovery. The selection is excluded from backups.
- If no root manager can be identified or selected, a generic **Root** row offers **Choose**
  while keeping root availability independent of discovery. Unavailable saved shortcuts are
  identified so they can be changed or cleared; duplicate automatic/selected rows are removed.
- Choose/Change and Clear have balanced widths and matching heights when labels wrap.
  Manager lookup is refreshed when needed and reused across unrelated preference changes.

The [source-pinned UI validation record](https://github.com/trinadhthatakula/Thor/blob/585acf62b69d0e3e02d52e5af0a0f6434a73a1cd/docs/validation/root-manager-discovery.md)
includes ReSukiSU phone and Magisk emulator smoke checks, screenshots with Shizuku/Dhizuku,
and earlier large-text coverage. The unknown-manager fallback's device simulation remains unrun;
its behavior was reviewed in code. Manager discovery and choosing a shortcut do not grant root.

### 🛡️ Fresh root status, admission and service ownership — new since v1.96.3

[PR #531](https://github.com/trinadhthatakula/Thor/pull/531),
[PR #533](https://github.com/trinadhthatakula/Thor/pull/533),
[PR #534](https://github.com/trinadhthatakula/Thor/pull/534),
[PR #535](https://github.com/trinadhthatakula/Thor/pull/535),
[PR #536](https://github.com/trinadhthatakula/Thor/pull/536),
[PR #537](https://github.com/trinadhthatakula/Thor/pull/537), and
[PR #538](https://github.com/trinadhthatakula/Thor/pull/538)
(`21f0d559`, `a1a23f7f`, `c5921e49`, `f13450dd`, `8c6e5774`, `7a8c9ac1`, `eff6de65`):

- Settings and App Info consume one shared privilege observation. Root refresh distinguishes
  confirmed root/non-root from checking, busy, timeout and failed acquisition. Busy/failed checks
  preserve the last confirmed identity while stopping new root work from treating it as fresh.
- Accepted work keeps its ownership through refresh; busy refresh retries at an idle boundary.
  Confirmed changes invalidate capability caches and retire dedicated shells when idle.
- KernelSU/ReSuKiSU manual app authorization is respected. A restricted ADB root shell is not
  treated as evidence that Thor itself lacks authorization.
- Timed-out or cancelled RootService bind attempts release their own late connections without
  clearing a newer connection. Rebinding and service ownership remain separate from shell refresh.
- Root services retain Android-user isolation. Invalidation or replacement for one user cannot
  silently take over another user's service.
- Root app-data clearing uses the compatible ActivityManager interface. A failure after possible
  dispatch does not trigger an unsafe repeated clear through a fallback path.

### 📤 Acknowledged export, game-file and input staging — new since v1.96.3

[PR #539](https://github.com/trinadhthatakula/Thor/pull/539),
[PR #540](https://github.com/trinadhthatakula/Thor/pull/540),
[PR #541](https://github.com/trinadhthatakula/Thor/pull/541),
[PR #542](https://github.com/trinadhthatakula/Thor/pull/542), and
[PR #543](https://github.com/trinadhthatakula/Thor/pull/543)
(`ad1fd5ee`, `53d7a3a6`, `ecb8bec3`, `59299562`, `99134fef`, `3d484ede`,
`9e696a88`, `5ffb42e6`, `67a980e6`):

- Explicit persistent/isolated execution policies preserve whether work started, termination was
  confirmed, output drained and the shell remains reusable. Cancelling a caller alone is not
  reported as termination of privileged work.
- Selected root export copies use private, receipt-backed staging. Successful payloads are
  published only after acknowledgement and byte validation. Unsupported atomic moves fall back
  to a checked copy; incomplete destinations cannot silently become successful exports.
- OBB placement retains package ownership across installation and placement. Each file is staged,
  permissioned and size-checked before same-directory publication, preserving an existing target
  until then. Symlinks are replaced without following them; actual directory targets are refused.
- Unresolved earlier OBB placement blocks a conflicting restore before its first destructive data
  replacement. A cleanup failure does not overwrite a successful placement result.
- Privileged preview/import and archive-icon reads retain one selected provider, bounded output
  and per-attempt staging. Shared copies are not world-writable. Icon read failures that may
  recover are kept distinct from persistent cache misses.
- Missing acknowledgement retains owned staging and uncertainty rather than deleting or reusing
  files a producer may still write. Known terminal or different-boot evidence governs recovery;
  ordinary app restart, stable file size and elapsed time are insufficient.

This adoption is limited to the named consumers. Tar creation, extraction, destructive restore
commit phases and broad cache deletion still need workload-specific ownership/recovery contracts.
Deliberately detached descendants and accepted Binder/system-server work are outside shell-only
termination guarantees.

### 📦 Tracked data clearing, suspension readback and installer ownership — new since v1.96.3

[PR #546](https://github.com/trinadhthatakula/Thor/pull/546) and
[PR #547](https://github.com/trinadhthatakula/Thor/pull/547)
(`f9e684e8`, `bc8647f5`, `ab9bf1e3`, `e7ddadf6`, `0ddae5c2`):

- Root suspension readback uses bounded typed results with owner/state validation, reducing
  reliance on large unstructured package dumps and preserving independent suspension owners.
- Clear-data requests are durably identified before dispatch. A matching callback or validated
  terminal query can settle them; missing/uncertain completion retains a package barrier across
  supported providers. A replacement service's missing record is not treated as success.
- Repeated clear gestures first reconcile an unresolved operation rather than blindly wiping
  again. Corrupt or nonempty malformed journals fail closed; interrupted empty initialization
  can resume safely.
- Internal installer sessions retain package/global ownership from attempted commit until their
  matching terminal callback. Caller deadlines, cancellation and pending user action do not imply
  Android stopped installing. Cleanup after a submitted session cannot replace its result.
- External installer ownership ends at chooser handoff. Durable installer-session recovery across
  app death, full worker-cancellation acceptance and broader IPC mutation support remain open.

The remaining sections collect the changes already delivered in **v1.96.1–v1.96.3**, all of which
are after the requested 1960 baseline.

### ❄️ Dhizuku freeze, unfreeze and hidden-app recovery

[PR #483](https://github.com/trinadhthatakula/Thor/pull/483)
(`e04e8c77`, `aab7c93f`):

- Dhizuku freezes apps with device-owner hiding and unfreezes them by unhiding, preserving the
  installed APK and app data. Thor no longer treats device-owner access as shell-level authority.
- Hidden apps appear as frozen in app metadata and Freezer state, so they remain available for
  recovery rather than disappearing from Thor's view.
- Root and Shizuku recovery also account for apps hidden by another mode, while keeping legacy
  recovery for system apps removed for the current user by older Thor versions.
- Freeze failures use localized, actionable messages.

### 📦 Supported device-owner operations and safer install completion

[PR #484](https://github.com/trinadhthatakula/Thor/pull/484)
(`92b6f127`, `d5b272d3`):

- Dhizuku uses device-owner operations for clearing app data, suspension, runtime permission
  changes, app removal and APK installation, and reports their actual results.
- Ordinary APK replacement remains supported. Fix Store and Auto Reinstall's Play attribution
  require Root or Shizuku; force-stop and cache clearing are unavailable through Dhizuku.
- Installation controls reflect mode capabilities. Install-time permission grants and the
  low-target-SDK override remain available only on supported Root/Shizuku paths.
- An install already submitted to Android is no longer reported as a submission failure merely
  because closing its session fails afterward.

### 🗂️ Profile assignment, live membership and recovery choices

[PR #487](https://github.com/trinadhthatakula/Thor/pull/487)
(`aac7bd60`, `d747b558`, `cecf0592`):

- **Add to profiles** assigns selected apps from App list or Freezer to existing profiles, with
  an option to also add them to the Freezer list. Assignment itself does not freeze the apps.
- App Info shows live profile memberships. Membership is managed through the profile editor.
- Removing a frozen app's last profile membership, when it is not in the Freezer list, offers an
  explicit recovery choice: leave it frozen or unfreeze it. Changes from elsewhere remain
  reflected while the sheet is open.
- The combined unfreeze action covers the Freezer list and profiles; recovery failures are
  reported without duplicate error messages.

### 🎛️ Multi-app action customization

[PR #488](https://github.com/trinadhthatakula/Thor/pull/488) (`8e76ce3c`):

- Settings → Customization now has separate layouts for App list and Freezer multi-app actions.
- Reorder actions, hide those you do not use, or restore defaults. Each toolbar keeps its own
  saved preferences, and Close remains available.
- Action availability still depends on the selected apps and privilege mode. Saved layouts
  preserve chosen order and incorporate newly introduced actions.

### 💛 Optional support after successful operations

[PR #485](https://github.com/trinadhthatakula/Thor/pull/485)
(`c74b0eff`, `c4b08213`, `d3b25b36`, `cd15b850`):

- Successful operations can offer a **Support Thor** shortcut through completion feedback.
- Invitations respect supporter eligibility, and the automatic introduction is shown only once.
  FOSS users, and Store users whose billing service is unavailable, can choose **I already support
  Thor** to hide invitations on that device.
- Confirmed Freezer additions get clearer success feedback, and completion sheets use consistent
  support buttons. Support remains optional in both distributions.

### 🧊 Shizuku system-app freeze and restore

[PR #521](https://github.com/trinadhthatakula/Thor/pull/521), addressing
[issue #519](https://github.com/trinadhthatakula/Thor/issues/519)
(`04d51a5e`, `d17ac998`, `63f31e43`; merged as `7994cf40`):

- Thor first tries the existing Binder disable and `pm disable-user` paths. Only a
  policy-refused **Shizuku system-app** disable can use `pm uninstall -k --user N`, and only
  while the removal fallback setting is enabled. Ordinary command/transport failures,
  user-installed apps, Root and Dhizuku do not use this fallback.
- A healthy local preference store with no saved choice defaults to enabled, including on
  upgrades. Explicit off choices persist and are checked when queued work executes. The setting
  is device-local and excluded from backups, so restoring other settings cannot override it.
  Unreadable preferences disable the fallback; corruption recovery persists an off choice.
- Unfreeze reinstalls packages removed for the current user. Restoration of legacy removed
  packages remains available even when the fallback is off.
- Queue history preserves distinct disable-refused, disable-failed and restore-failed reasons.
  Diagnostic logs retain both shell output streams. Unrelated root-availability probes no longer
  mark Shizuku tasks as degraded; task warnings follow commands actually executed for that target.
- Shizuku launches `sh` for either server identity, allowing the child process to inherit the
  server UID without an unnecessary nested `su` dependency.

### 📜 Extensions confirmation on small screens

[PR #523](https://github.com/trinadhthatakula/Thor/pull/523), addressing
[issue #518](https://github.com/trinadhthatakula/Thor/issues/518) (`a1d522bf`):

- The Extensions acknowledgement sheet now scrolls vertically, so its warning, answer field,
  Accept button and Cancel control remain reachable in short windows and landscape.
- The arithmetic confirmation and saved consent behavior are unchanged.

### 🛡️ App Ops in Permission Manager

[PR #511](https://github.com/trinadhthatakula/Thor/pull/511)
(`f06b540d`, `06373e87`, `26f57ac0`, `76f0cba4`, `699fe66a`, `1ef6de96`):

- A separate App Ops tab discovers operation names, controlling switches, linked permissions,
  defaults and reset support from the running Android build. Search and the **Relevant**,
  **Changed** and **All** filters make long catalogs easier to inspect.
- Root or Shizuku can read and change supported package- or UID-scoped modes for the current
  Android user. Thor verifies the requested scope after writes, reports uncertain results and
  offers a separate reset-to-platform-default action. Shared-UID edits warn about other affected apps.
- Runtime-permission-controlled operations stay read-only in App Ops, with a path to Permissions.
  Unknown policy blocks potentially conflicting edits. Parsing handles supported mixed Xiaomi
  operation output, and the editor sheet dismisses when swiped down from its title.

### ↕️ Draggable scrollbars in Apps and Freezer

[PR #510](https://github.com/trinadhthatakula/Thor/pull/510)
(`7df4c174`, `664808ec`, `ebd93039`, `5ae8ab7d`):

- Drag scrollbar thumbs through long lists and grids, with a bubble showing the visible app
  range and accessibility progress controls for navigation.
- The expanded drag target and responsive spacing preserve grid columns and nearby row/card
  taps. Dragging remains stable when the item count changes. Sort-aware snapping is a follow-up.

### 🇯🇵 Japanese localization

[PR #513](https://github.com/trinadhthatakula/Thor/pull/513) (`bfc594cf`):

- Adds Japanese strings across the app, including App Ops, backup, settings and Store features,
  and exposes Japanese in the supported language picker and Android locale configuration.
- Updates locale tests and contribution guidance. The hotfix also supplies its new messages in
  all nine app locales and aligns Japanese wording with the existing Unfreeze terminology.

## 🔧 Project: diagnostics, release tooling and maintenance

- **Settings Editor helper migration** — [#554](https://github.com/trinadhthatakula/Thor/pull/554)
  (`801d3482`, `b89f47fc`, `c3702064`) converts the process bridge and live-writer probes
  from Java to Kotlin, retaining their cancellation and uncertainty contracts. The module-access
  correction keeps the probe entry points callable without synthetic accessors; device and
  minified-bridge checks are recorded in the validation document.
- **Standalone APK packaging** — [#557](https://github.com/trinadhthatakula/Thor/pull/557)
  (`82e1471e`) compresses native libraries in standalone APKs while keeping Store-bundle
  device splits uncompressed. Tests covered native consumers, bundletool splits and a
  16 KiB-page emulator. Extracted-library installed-storage cost was not measured.
- **Dependency and animation audit** — [#558](https://github.com/trinadhthatakula/Thor/pull/558)
  (`50805c05`, `4ce8d91f`) removes redundant declarations and runtime Lottie after a
  scoped R8/dependency audit. Material extended icons and WorkManager remain by product
  decision; removing unused declarations alone is not presented as a performance gain.
- **Tooling and legacy cleanup** — [#559](https://github.com/trinadhthatakula/Thor/pull/559)
  (`585fd9c8`) enables host-test resources needed by Android Studio Canary Compose Preview;
  that Canary also requires a local resilient-model-fetch registry workaround. [#560](https://github.com/trinadhthatakula/Thor/pull/560)
  (`f66ffff1`) updates base Biometric. [#561](https://github.com/trinadhthatakula/Thor/pull/561)
  (`bfdb3544`) removes unused legacy WorkManager helpers while retaining active legacy-job
  compatibility and its drain gate. `63afc7ae` records the Room 3 and APK-size decisions.
- **Development lifecycle diagnostics** — [#551](https://github.com/trinadhthatakula/Thor/pull/551)
  (`46d9eeb5`, `bc529939`, `a36d0a40`, `04276805`) adds a closed, versioned event schema for
  refresh/admission, shell generations, lane recovery, isolated outcomes and service binding.
  Records contain no commands, package/work identifiers, settings or output text. Diagnostic
  failures cannot interrupt operations; normal release builds keep the sink disabled. Local
  close/unbind events do not claim producer or accepted-IPC termination.
- **Private logging and test reliability** — #530 redacts private Shizuku timeout commands
  (`a1c6e4ef`) and acknowledges helper submission before cancellation (`b005cb35`). #531 corrects
  readiness publication and opt-in assumptions (`cdf8afcc`). #547 uses a real-clock cancellation
  watchdog (`8952fab2`); #551 waits for an already-scheduled idle refresh in smoke-test setup.
- **Manager discovery and preference caching** — [#553](https://github.com/trinadhthatakula/Thor/pull/553)
  adds 21 tests for canonical/renamed manager discovery, exact package matching, duplicate and
  unavailable shortcuts, device-local preference mapping and collection-local caching. Selection
  or explicit refresh invalidates discovery; unrelated preference changes keep their current
  values without rescanning installed managers (`e081e91f`, `edb79102`).
- **Build and dependency updates** — [#548](https://github.com/trinadhthatakula/Thor/pull/548)
  (`3a49c3a0`) adopts Gradle **9.8.0**, reviews Gradle properties, updates dependencies and fixes
  Robolectric **4.17** compatibility on Java 21. Current root integration uses Odin **1.1.0**;
  AGP is **9.5.0-alpha08**. Earlier updates include #478/#479, #499/#500/#501, #507/#514,
  #516/#517 and final #549/#550 (`61dc6fb3`, `2f8e27df`), covering Android, web, Fastlane/Ruby
  and CI actions. Beta-branch dependency reconciliation retains the devalue fix (#498,
  `6ed949d2`) and compatible Rubyzip history (#515, `37f97ae6`).
- **Release correctness** — 1.96.1 release work preserves complete multiline Play notes in every
  locale, rejects empty/truncated notes, preserves contributed translations and validates Shizu
  JSON round trips (`445e7bd3`). The one-time 1961 already-published Play exception lets that
  release's GitHub promotion proceed without a second Play upload (`b2f4865b`); later versions
  use normal routing. The full commit log below records release-preparation corrections.
- **Store metadata and history** — #486 (`17953b04`), #494 (`c68810c2`) and #526 (`7aa20338`)
  synchronize Shizu after production releases; `a6de91a6` clarifies Shizuku fallback eligibility.
  #512 (`ca46642f`, `29a3138d`) adds 1.96.2 notes and en-GB Fastlane metadata. History and
  beta/production back-merges are reconciled before subsequent development.
- **Documentation and acceptance** — #506/#508 reconcile README and follow-ups; #532
  (`3e8fc7a7`) records the Odin roadmap, with implementation, exact source/APK evidence and
  remaining acceptance updated through #551. Settings live-writer and reboot/cancellation
  fixtures (#544/#545) distinguish observed values from original write/termination proof.
- **This release preparation** changes only versionCode to **1971**, deriving **1.97.1**, carries
  forward and extends the cumulative 1970 notes, and supplies complete Play/F-Droid text to
  en-US, en-GB and hi-IN. It retires v1.92.2's curated directory to retain the newest 20;
  Fastlane history remains. Shizu continues to describe published production **1.96.3** until
  1971 reaches production and its separate post-promotion sync reaches the audited branch.

## 🧪 Validation scope and remaining acceptance

The 1970 dev candidate's host build, lint and release-script results remain in
[its notes](../v1.97.0/github.md); they apply to that earlier source and APK.
Changes since then have source-pinned validation records for
[UAD filtering](../../docs/validation/system-app-uad-filter.md),
[Outfit](../../docs/validation/outfit-variable-font.md),
[native packaging](../../docs/validation/native-library-packaging.md),
[the terminal animation](../../docs/validation/terminal-canvas.md),
[Settings Editor Kotlin](../../docs/validation/settings-editor-kotlin.md),
[dependency cleanup](../../docs/validation/dependency-cleanup.md), and
[legacy WorkManager cleanup](../../docs/validation/legacy-workmanager-cleanup.md).
These include selected emulator/phone tests; they are not a full device regression of the
combined 1971 candidate.

On the 1971 release topic branch based on dev `f3a863fa`, the required
`./gradlew test lintFossDebug lintStoreRelease` gate passed with **3,576 JVM tests per
debug flavor** (7,152 total), zero failures/errors/skips. FOSS Debug lint reports
**0 errors, 0 warnings, 9 hints**; Store Release reports **0 errors, 0 warnings,
8 hints**. Neither report contains MissingTranslation or SyntheticAccessor findings.
The 13 release-script suites passed after pruning the old notes directory, as did
the production-pinned Shizu manifest check. The 1971 Play notes use **481/500 characters**;
the assembled Telegram caption uses **1,014/1,024 UTF-16 units** at the conservative
164-unit ladder wrapper. The three Fastlane locale files match the complete Play source.
`assembleFossRelease assembleStoreRelease bundleStoreRelease` also passed. Both locally
built, unsigned APKs report **1971 / 1.97.1** in their output metadata; the Store AAB
was built from the same variant. Local SHA-256 values are FOSS APK
`a638b2ef3bf66c6d96878383fa4451e1b50874ce03a3c3853f805f8dcd834367`, Store APK
`4d1da0a1212a89a1f58062bf245d81ac56ead0656df1a4c3519a28d7a4b23952`, and Store AAB
`90ccc2630875e14d6606479bdf79f7a99db72fde4af8f6664f1b235262f1c433`. The release
workflow will rebuild and sign from its own merge commit, so these are local verification
artifacts. No combined 1971 build was installed on a device during this preparation.

Historical device results retain their original tested revisions in the
[Odin implementation tracker](https://github.com/trinadhthatakula/Thor/blob/51d118b649fb03171c8de929b5608374fe1b6d29/docs/follow-ups/odin-1.1.0-implementation-plan.md),
[Settings Editor record](https://github.com/trinadhthatakula/Thor/blob/51d118b649fb03171c8de929b5608374fe1b6d29/docs/follow-ups/settings-editor-recovery.md),
[Unpause record](https://github.com/trinadhthatakula/Thor/blob/51d118b649fb03171c8de929b5608374fe1b6d29/docs/issues/522-suspended-app-unpause.md),
the [manager UI record](https://github.com/trinadhthatakula/Thor/blob/585acf62b69d0e3e02d52e5af0a0f6434a73a1cd/docs/validation/root-manager-discovery.md),
and the earlier release notes linked above. They include Magisk/ReSuKiSU root checks, selected
live Shizuku cases, native Unpause and an emulator device-owner suspension check; they do not
establish every Android/OEM/provider combination.

Open acceptance includes physical live-writer death/reboot and unacknowledged termination,
workload-specific destructive archive/cache adoption, real installer callback/durable recovery
cases, broader minified/unauthorized-caller/multi-user IPC cases, and selected App Ops/scrollbar/OEM
checks. Existing Shizuku removal fallback can preserve data files while losing app-managed accounts.
Release diagnostic retention/export remains undecided and disabled in normal release builds.

## 🛠 Commits Log

Complete non-merge development history from `v1.96.0..f3a863fa`: **153 commits** in
chronological order. This includes the 1970 release preparation and all 13 post-1970
non-merge changes; this 1971 preparation is separate. The newly developed subset since
the 1970 dev prerelease is `v1.97.0-dev-127..f3a863fa`.

- `950c7d11` chore(deps-dev): bump @types/node in /web in the web group
- `98f5c0fa` chore(deps): bump com.google.devtools.ksp in the maven group
- `e04e8c77` fix: use device-owner hiding for Dhizuku freeze
- `aab7c93f` fix: localize Dhizuku freeze failures
- `92b6f127` fix: use device-owner APIs for Dhizuku actions
- `c74b0eff` feat: add optional support actions after successful operations
- `c4b08213` docs: show support invitation entry points
- `d5b272d3` fix(installer): preserve submission after session cleanup failure
- `d3b25b36` fix(support): announce confirmed freezer additions and polish labels
- `cd15b850` style(support): outline completion sheet support buttons
- `17953b04` fix(shizu): sync changelog to production 1.96.0
- `aac7bd60` feat: assign selected apps to existing freezer profiles
- `d747b558` feat: clarify profile membership and add recovery choices
- `8e76ce3c` feat: customize App list and Freezer multi-app actions
- `cecf0592` fix: keep profile membership live and report recovery errors once
- `445e7bd3` chore(release): prepare v1.96.1 and preserve complete changelogs
- `b2f4865b` fix(release): publish 1961 without repeating Play promotion
- `c68810c2` fix(shizu): sync complete changelog to production 1.96.1
- `6ed949d2` chore(deps): bump devalue
- `265d3b0e` chore(deps): bump fastlane from 2.239.0 to 2.240.1 in the fastlane group
- `45f3eb83` chore(deps-dev): bump the web group in /web with 2 updates
- `69131bcd` chore(deps): bump ruby/setup-ruby in the actions group
- `0b5360c9` chore: refresh Thor documentation and AGP version
- `a2502252` docs: distinguish partial Samsung fix from shipped work
- `3e58535b` docs: qualify extension catalog hash verification
- `c358263f` chore(deps): update Coil to 3.6.3
- `ab3e98d6` docs: mark shipped installer and bulk follow-ups
- `fb214ae2` docs: reconcile follow-up index and reports with shipped work
- `7df4c174` feat: add draggable scrollbars to Apps and Freezer
- `664808ec` fix: tighten grid scrollbar spacing without losing columns
- `ebd93039` feat: use responsive scrollbar spacing in lists and grids
- `5ae8ab7d` fix: preserve scrollbar drag when item count changes
- `f06b540d` feat(permissions): add verified App Ops manager
- `06373e87` fix(app-ops): support mixed Xiaomi operation responses
- `26f57ac0` fix(app-ops): explain runtime restrictions and restore editor input
- `76f0cba4` fix(app-ops): filter relevance by manifest permissions
- `699fe66a` fix(app-ops): guard unknown policy and reveal linked permissions
- `1ef6de96` fix(app-ops): make editor title swipe dismiss sheet
- `ca46642f` chore(release): prepare v1.96.2
- `29a3138d` chore(release): add en-GB Fastlane metadata
- `bfc594cf` i18n: add Japanese app localization
- `6888e96a` chore: refresh dependencies, fix warnings, and save scrollbar prototypes
- `37f97ae6` chore(deps): bump rubyzip in the bundler group across 1 directory
- `fe319596` chore(deps): bump the web group in /web with 5 updates
- `f770cd59` chore(deps): bump the actions group with 2 updates
- `04d51a5e` fix(shizuku): restore consented system-app freeze fallback
- `d17ac998` fix(shizuku): enable removal fallback by default and preserve opt-outs
- `63f31e43` fix(shizuku): preserve removal opt-out on preference read failures
- `dd2c4d98` chore(release): prepare v1.96.3 Shizuku hotfix
- `a1d522bf` fix(extensions): make consent sheet scrollable on small screens
- `1a447b52` chore(release): include Extensions scrolling fix in v1.96.3 notes
- `7aa20338` chore(release): sync Shizu changelog after v1.96.3
- `a6de91a6` docs(release): clarify Shizuku fallback eligibility
- `a92aaacc` feat: add suspended apps list and native unpause action (#522)
- `d24b97e6` fix: hide empty suspended counter on home
- `0b309463` feat: add built-in Sett Edit extension (#504)
- `a1c6e4ef` fix: redact private Shizuku command timeout logs
- `5d1da1f6` docs: record Sett Edit recovery and execution limits
- `f64d4800` fix: bound Sett Edit helper jobs before Root lease release
- `b005cb35` test: acknowledge Sett Edit job submission before cancellation
- `21f0d559` feat: adopt Odin isolated cancellation and root cache refresh
- `cdf8afcc` test: fix Odin readiness ordering and policy opt-in skip
- `3e8fc7a7` docs: record Odin reliability roadmap and progress checklist [skip ci]
- `a1a23f7f` fix(root): avoid replaying uncertain data clears
- `64d296ef` docs(odin): record data-clear fix validation
- `c5921e49` fix(privilege): reuse shared state across app screens
- `d868f74d` docs(odin): track starting-pair PRs and validation
- `65b5c5b1` docs(odin): synchronize shared-state progress
- `f13450dd` feat(root): coordinate fresh root state and operation admission
- `7f5d766b` docs: record root refresh validation and firmware compatibility follow-up
- `63da45f5` docs: schedule AIDL compatibility fix after root refresh merge
- `8c6e5774` fix(root): use standard ActivityManager API for data clearing
- `9c6dccfa` docs: record clear-data compatibility validation and next steps
- `7a8c9ac1` fix(root): own delayed RootService connections
- `a3ce8f4c` docs: track binding ownership validation and pending hardware checks
- `9bb93427` docs: record successful ReSuKiSU binding validation
- `eff6de65` fix: preserve root services across Android users
- `1d61768c` docs: record root service isolation validation
- `1caf87f0` docs: link profile isolation PR in roadmap
- `ad1fd5ee` feat: preserve isolated root execution outcomes
- `1f5dd126` docs: record M2-01 validation and next milestone
- `53d7a3a6` feat: isolate root export staging with recoverable cleanup
- `bb8da97a` docs: record M2-02 device validation and roadmap progress
- `ecb8bec3` fix: copy export payload when atomic promotion is unsupported
- `59299562` feat(obb): add acknowledged isolated placement and recovery
- `e325174a` docs(odin): record M2-03 completion and device validation
- `99134fef` fix(obb): preserve placement results and preflight restores
- `1d6e1c68` docs(odin): record review-fix validation
- `3d484ede` fix(obb): coordinate installs and publish checked expansions
- `65011fcf` docs(odin): record installer and publication review checks
- `9e696a88` feat: isolate privileged preview and archive input reads
- `8e93e710` docs: link input staging validation to PR 542
- `5ffb42e6` fix: remove shared write access from read staging
- `67a980e6` feat: adopt acknowledged archive icon staging
- `328c54d0` docs: link archive icon progress to PR 543
- `e7bf7eaf` docs: record ReSuKiSU archive icon validation
- `98793e74` feat(settings): reconcile uncertain edits with read-only observations
- `988ac936` docs: link Settings Editor reconciliation PR and tested revision
- `c1d87205` test(settings): validate physical readiness and process-death recovery
- `d0b0d1aa` docs: record physical Root Shizuku and process-death validation
- `44dc3191` docs: clarify private journal validation artifacts
- `3d175607` test: validate live settings writer recovery
- `534e9472` docs: pin live-writer evidence to tested harness
- `f9e684e8` feat(root): add bounded typed suspension readback
- `59c51ada` docs: record typed suspension readback checks and remaining work
- `bc8647f5` feat(root): track clear-data completion and retain package ownership
- `3aab97d5` docs: record typed clear-data validation and remaining acceptance
- `3a49c3a0` chore(build): upgrade Gradle and fix Robolectric 4.17 on Java 21
- `ab9bf1e3` fix(root): resume interrupted empty clear-data journal initialization
- `9badb63c` docs: record clear-data journal initialization regression checks
- `8952fab2` test(root): use real time for admission cancellation watchdog
- `5df1c0e1` docs: record PR 547 cancellation watchdog validation
- `38f9a200` docs: record PR 547 validation after dev merge
- `e7ddadf6` fix(installer): retain operation leases until session completion
- `7a47f4ba` docs: record installer session lease validation
- `61dc6fb3` chore(deps): bump the web group in /web with 3 updates
- `2f8e27df` chore(deps): bump the actions group with 2 updates
- `0ddae5c2` fix(installer): retain session leases after caller detachment
- `09aa5e6f` docs(odin): record retained installer ownership validation
- `46d9eeb5` feat(root): add structured development lifecycle diagnostics
- `bc529939` fix(root): avoid synthetic accessors in binding diagnostics
- `a36d0a40` test(root): await deferred refresh settlement during setup
- `04276805` docs(odin): record diagnostics validation and remaining acceptance
- `3c213496` chore(release): prepare cumulative v1.97.0 after v1.96.0
- `e081e91f` fix: discover KernelSU forks and hidden root managers
- `d13d3b7d` ui: mark the selected root manager with a green tick
- `000dd3a1` ui: group root manager choose and clear actions
- `276dd803` fix: preserve action semantics for root manager split buttons
- `be66be4d` ui: use privilege bottom sheets with header icon actions
- `b14902fa` chore: remove the unused privilege dialog icon
- `02b40176` ui: balance manager actions and show manager app icons
- `d2465d9e` ui: align joined actions when translated labels wrap
- `34b43c2d` ui: show detected root manager details and selection change action
- `4da92cb3` docs: record root manager discovery and sheet validation
- `edb79102` fix: cache manager discovery across unrelated preference changes
- `fa86a8ab` ui: show live grant ticks and retain manager open actions
- `88388008` docs: record grant indicator and manager cache validation
- `a6530658` docs: refresh emulator screenshots with all privilege managers
- `ea66bee4` docs(release): include new privilege sheets in 1.97.0 notes
- `c6c2779b` docs(release): record merged 1.97.0 build validation
- `801d3482` chore: rename settings editor helpers for Kotlin migration
- `b89f47fc` refactor: convert settings editor process helpers to Kotlin
- `c3702064` fix: preserve module access for settings editor probe helpers
- `8db54ed9` docs: record settings editor Kotlin validation
- `bee48ba7` feat: add system app UAD filters and per-tab preferences
- `5aa4cc11` perf: replace static Outfit fonts with a variable font
- `82e1471e` perf: compress native libraries in standalone APKs
- `50805c05` chore: remove redundant dependency declarations
- `4ce8d91f` perf: replace terminal Lottie animation with Canvas
- `585fd9c8` fix: enable Compose Preview resources for Canary sync
- `f66ffff1` chore(deps): bump androidx.biometric:biometric
- `63afc7ae` docs: record Room 3 assessment and APK optimization status
- `bfdb3544` chore: remove unused legacy WorkManager helpers
