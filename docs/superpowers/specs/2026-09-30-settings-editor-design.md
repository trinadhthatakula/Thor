# Issue #504: Sett Edit

Status: implemented; emulator and physical-device acceptance passed. Required local gates passed
after rebasing onto the merged unpause changes: 3,107 tests per flavor; no lint warnings or errors.
Issue: https://github.com/trinadhthatakula/Thor/issues/504
Branch: `feat/504-settings-editor`, initially from `dev` at `25a120cb`, rebased onto
`ecf02a82` after the unpause PR #529 merged.

## Product decisions

- Bundle **Sett Edit** as a global built-in tool in Extensions, without download, installation or uninstall controls.
- App preference editing is deferred to [follow-ups](../../follow-ups/app-preference-editor.md).
  It belongs in app details surfaces and is outside #504.
- Require Root or Shizuku. The entire editor is unavailable while Dhizuku is selected/active or no
  supported provider is available. Do not resolve Dhizuku into Root silently for this feature.
- Require separate first-use consent before editing, following the Extensions warning pattern.
- Keep the six views from the assessment: System, Secure, Global, Android properties, Java
  properties and Linux environment. Only the first three allow mutations in this version.
- No versionCode change. This feature belongs in its own PR to dev.

## Entry points and navigation

The Extensions manager shows a built-in Sett Edit card independently of external catalog loading,
errors or installed packages. Disable it with an explanation in unsupported privilege modes.
A serializable SettingsEditor route opens the shared editor and returns to Extensions on Back.
Restored navigation independently enforces capability and consent. App-info actions remain unchanged.

## First-use warning and consent

Use a scrollable, expanded warning dialog/sheet following ExtensionConsentSheet, with a small
arithmetic check and an explicit **I understand and accept the risk** action. Decline, Back, swipe
or scrim dismissal leaves the editor and grants no consent. Rotation preserves the check and answer.

Proposed warning text:

> This editor changes Android's shared System, Secure and Global settings. These are not the
> selected app's preferences. Global settings can affect all users on this device.
>
> Incorrect changes can disrupt the display, keyboard, accessibility, networking or debugging,
> make apps stop working, or prevent Android from starting normally. Only change values you
> understand and keep a record of their original values.
>
> A saved value may be ignored or replaced by Android or your device manufacturer. Undo restores
> a recorded value; it cannot guarantee that every effect of a change can be reversed. Recovery
> may require ADB, recovery mode or a factory reset.

Consent is independent from extension consent and stored in thor_local_state, excluded from
backup/device transfer. Updates preserve acceptance; fresh installs/restores ask again. Missing,
unreadable or not-yet-loaded acceptance never enables edits. Persist acceptance successfully before
enabling mutation controls; a failed persistence is surfaced and leaves them locked.

The controller/repository checks consent for add, update, delete and undo, independently from UI
visibility. A restored route or direct call cannot bypass the warning. Mode changes or lost access
lock the screen and block new commands. An operation already dispatched is verified using its
pinned provider, and its result remains visible; do not cancel and blindly replay the write.

A first-use warning does not replace the existing plan for per-change previews and specific
confirmation when a key can disrupt debugging, input methods, accessibility or other access.

## Editor and data model

- Search keys and values, copy an individual value, refresh and switch views.
- System/Secure operations explicitly target Thor's current Android user; show the user scope.
  Label Global as shared device settings. Handle inherited profile settings as platform limits.
- Preserve values exactly, including empty strings, missing keys and literal "null". Parse at the
  first equals sign only. Ambiguous/multiline shell output must not authorize a write; use a
  structured provider transport if necessary rather than silently corrupting text.
- Support individual add, edit and delete with a preview of table/user/key/old/new state.
- Root/Shizuku execute through existing gateways and command-lane admission, pinned for the whole
  read/write/readback operation. Dhizuku never supplies a session.
- Verify existing value just before mutation. If it changed since opening the form, show a conflict
  and request refresh rather than overwrite it silently.
- Read back exact state after writes/deletes. Report "Value saved and verified", never assume the
  device behavior changed. Keep unknown outcome distinct from an applied/rejected operation.
- Record history on this device with namespace, user, key, original presence/value, requested
  presence/value, provider, time and verification status. Avoid dumping values into debug logs.
- Undo checks the current value equals Thor's recorded resulting value before restoring the old
  value or original absence. A conflict requires explicit review; deletion is not factory reset.
- Java properties belong to Thor's runtime. Environment belongs to Thor's process; a privileged
  shell environment must be separately labeled if exposed. Android properties visibility depends
  on provider/SELinux. All three diagnostic views remain read-only.

## Implementation slices

1. Capability policy and device-local consent persistence, with pure regression coverage.
2. Settings models, command/provider transport, strict parsing and controller with conflict checks,
   exact readback, serialized writes and undo history.
3. Editor ViewModel/UI, warning, view selection, forms, previews, result/error states and history.
4. Built-in Extensions card and global navigation with correct Back behavior.
5. Local gates and emulator acceptance, followed by authorized physical-device checks.

## Acceptance

- Dhizuku-only, selected Dhizuku with Root also available, no privilege, startup probe and provider
  loss: no usable editor session or mutation; restored route remains guarded.
- Consent absent/loading/read failure/write failure, decline, correct/incorrect arithmetic,
  rotation, recreation, repeated launch and backup/restore: controls stay locked until acceptance
  is successfully persisted on this device. Other feature consent has no effect.
- Extensions opens the bundled editor even with no external extensions. Global/per-user scope is clear.
  No app-level Sett Edit entry point or fake installed extension is created.
- Disposable emulator keys cover create/update/delete/undo in each writable table, exact quoting,
  equals/empty/null edge cases, provider refusal, concurrent change and readback failure. Always
  restore the original key state. Do not disable debugging or alter personal settings for testing.
- Android 11 and current Android emulator coverage with shell-backed Shizuku; Root coverage where
  available. Physical Root and Shizuku acceptance uses only disposable test keys, with restoration.
- Required gates before PR: ./gradlew test lintFossDebug lintStoreRelease, no MissingTranslation
  warnings or bypass SyntheticAccessor errors. Verify warning scrolling/IME/insets on small screens.

## Deferred

Curated descriptions can be added where reliable evidence exists. Presets, bulk import, cross-user
restore, automatic boot reapplication and Android property mutation are separate follow-ups.

## Platform behavior verified during implementation

- Android 11 and 16 SettingsProvider stores an exact string `null` as SQL null. This is a platform
  normalization; exact readback reports REJECTED when the requested string differs from stored null.
- The helper calls SettingsProvider through an external provider reference, with shell/root
  attribution and explicit `_user`. LIST entries remain separate Bundle strings, so multiline values
  are not split into fake keys. Ambiguous textual null is resolved with GET.
- The original value is journaled before dispatch. An interrupted or unverified mutation remains
  pending/unknown and is never automatically replayed. Conflict checking narrows the race window;
  Android offers no atomic compare-and-set, so another writer can still race after the check.

## Acceptance evidence (2026-09-30)

| Environment | Provider | Result |
| --- | --- | --- |
| Android 11 / API 30 emulator | Shizuku shell | Full repository roundtrip passed |
| Android 16 / API 36 emulator | Shizuku shell | Full repository roundtrip passed |
| POCO F7 / API 36 physical device | Shizuku shell | Full repository roundtrip passed |
| POCO F7 / API 36 physical device | Root | Full repository roundtrip passed |

Each live roundtrip used generated `thor_sett_edit_test_` keys in System, Secure and Global.
Create/update/delete/undo, empty strings, multiline/quoting/Unicode, SQL null normalization,
conflicts, all three read-only diagnostic views and selected-Dhizuku blocking passed.
Finally blocks verified the keys were absent and restored privilege preference, consent and history.
Warning-sheet tests passed on both emulators (wrong answer locked, correct answer enabled,
scrolling, failed persistence message). Manual API 30 navigation verified Extensions -> built-in
Sett Edit -> separate warning -> settings values, and Back returning to Extensions.
The editor TopAppBar uses `WindowInsets(0, 0, 0, 0)` because MainScreen already applies
system-bar padding. API 30 visual verification confirmed normal toolbar spacing and search
remaining visible above the keyboard.

Limits: API 28/29, managed-profile inheritance, additional OEMs and future Android provider
internals are not device-validated here. Platform validation can reject keys or values. Android
may ignore/overwrite saved values; verified storage never promises a behavioral effect.
App preferences editing is deferred in `docs/follow-ups/app-preference-editor.md`.
The maintainer confirmed Sett Edit is working on 2026-09-30.

## Recovery limits confirmed during review

Process death or a failed final journal save can leave a PENDING record after a mutation.
PENDING/UNKNOWN records remain unverified and cannot use guarded undo; inspect the current
table value before submitting a separate reviewed edit. Root INTERACTIVE execution does not
enforce a settings-helper deadline, so a hung helper can keep the editor and lane busy.
Explicit reconciliation and safe execution bounds are recorded in
[follow-ups](../../follow-ups/settings-editor-recovery.md).
