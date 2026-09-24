# App Ops in Permission Manager

Feature request: [#503](https://github.com/trinadhthatakula/Thor/issues/503). Work branch: `feat/app-ops-manager`, based on `dev`.

## Product scope

The per-app Permissions screen gains a separate App Ops tab. Runtime grants and App Ops modes answer different questions: an allowed operation can still be blocked by a denied permission or another platform restriction. Normal/signature permissions are status rows rather than grant/revoke switches.

The default filter is **Relevant**, as chosen by the owner. It includes permission-linked operations and operations with recorded activity or changed modes. **All** exposes the remaining operations in the device's Android catalog, with relevant entries first; **Changed** isolates overrides. Search covers operation names, aliases, and related permissions.

An operation sheet shows its package mode, UID mode, platform default, and Android user. It offers Allow, Ignore, Deny, Default, and Foreground, with descriptions that retain Android's operation-specific limits. UID changes warn that apps sharing the UID can be affected. A package edit can be masked by a UID override, which remains visible.

On devices where Android maps runtime permissions directly to App Ops, those operations are status-only. Thor detects the device policy and its controlling operation's permission mapping rather than assuming every Android 16 build behaves identically. Some ROMs keep the flag class outside the app classpath; Thor then reads the policy through its selected privileged session. The sheet links to Permissions when the app requests the permission, or explains that the app does not request it. Usage access and other independently editable operations retain their mode controls. A successful runtime grant/revoke refreshes previously visited App Ops, and a readback started before that change cannot restore old modes.

**Reset to platform default** applies the operation's device-defined baseline to the chosen scope. It is distinct from selecting the literal `MODE_DEFAULT` value, and it is disabled where the platform disallows reset. It does not clear operation history or reset unrelated operations.

## Parallel implementation plan

1. Platform worker: read the current device catalog through Thor's Bypass, group aliases by their controlling operation, build validated user-scoped commands, and parse Android 9/current shell formats.
2. Repository integration: resolve a Root or Shizuku transport, validate the package's current-user UID, serialize mutations, and verify the requested scope after every write. Dhizuku's device-owner identity is not treated as general App Ops authority.
3. UI worker: add the tab, search/filters, mode editor, shared-UID warning, loading/error states, translations, and ViewModel tests.
4. Integration/review: exercise silent refusals and concurrent state changes, run repository build gates, and smoke-test the actual device catalog and reversible changes on the rooted emulator.

## Platform and recovery rules

- The running Android build supplies operation codes, names, linked permissions, defaults, and reset eligibility. No fixed operation list or assumed OEM codes are used. Explicit unnamed retired slots are omitted without renumbering later operation codes; this was verified against Android 16's deprecated slot 96.
- Commands target Thor's Android user and the package UID obtained for that user. Unsafe shell tokens, special UID/daemon names, unavailable operation codes, and unknown modes are rejected before a write.
- One selected privileged transport is used throughout each read/write/readback sequence. Root and Shizuku are supported; an unavailable transport produces an error.
- Read-only snapshots retry a busy root-shell admission up to five complete attempts with 100 ms between attempts. Every attempt revalidates the target and rereads both scopes. Writes, resets, parser errors, timeouts, and cancellations are not automatically retried.
- Modern package dumps mix UID and package records. A separate numeric-UID query establishes the boundary, and matching UID reads before and after the package query guard against concurrent changes. Unrecognized or ambiguous responses fail the read.
- Xiaomi/HyperOS and xiaomi.eu can append `MIUIOP(...)` records outside Android's catalog, including the vendor-only `ask` mode. These records participate in scope-boundary checks but are omitted from Android controls with a visible notice. Their defaults and modes are not inferred or changed; catalog collisions and unknown Android records still fail the read.
- A successful shell exit is insufficient. The repository rereads the chosen scope and compares it with the requested mode; a missing record means the operation's platform default. UID values cannot stand in for package verification, or vice versa.
- The UI does not optimistically change modes. Loading, failed refreshes, and uncertain writes disable edits until current state is available. Switching apps cannot apply an old response to the new app.
- The editor opens fully expanded with scrollable content, avoiding the intermediate expansion that left touch input unresponsive on the physical device. Invalidating a snapshot clears the old editor selection so refresh does not reopen a failed operation.
- Changes are individual and interactive. Bulk presets, scheduled enforcement, cross-user browsing, and whole-app reset are outside this first implementation.

## Acceptance matrix

| Coverage | Status |
|---|---|
| Pure catalog, API 28/36 parser, alias, command-validation tests | Passed, including retired slots, Xiaomi vendor records, scope boundaries, and catalog-collision regressions |
| Scope readback, silent refusal, wrong user, reset/default, cancellation tests | Passed, including package replacement during readback |
| Relevant/Changed/All and ViewModel loading/failure/race tests | Passed; runtime-only permission switches also covered by Compose tests |
| `./gradlew test` | Passed 2026-09-24 after editor/runtime-policy fixes: 3,074 tests in each of FOSS and Store, with no failures or skips |
| `./gradlew lintFossDebug lintStoreRelease` | Passed 2026-09-24: no lint errors, MissingTranslation warnings, or SyntheticAccessor errors. The intentional hidden-API probe has a scoped suppression and a privileged fallback |
| Rooted `Thor_Root_API36`: real device catalog, package/UID round trips and restoration | Passed 2026-09-24 through the production Koin repository and Root gateway |
| Permissions/App Ops UI on rooted emulator | Checked 2026-09-24: navigation, Relevant/All, search, mode sheet, package/UID display, UID warning, and normal-permission status rows |
| Physical-device Root reads on xiaomi.eu Android 16 / KernelSU | Passed 2026-09-24: read-only production-repository test on Thor Debug, plus the App Ops screen for the installed release app; Android modes and the Xiaomi-controls notice are visible |
| Physical-device runtime policy and editor interaction | Passed 2026-09-24: 7 device tests; production catalog marks Handover permission-controlled and Usage Stats independently editable. Normal navigation also verified the Handover explanation and Usage Stats selection/confirmation. Confirmation was cancelled; saved modes remained Handover Ignore and Usage Stats Allow |
| Physical-device scoped writes, ordinary Shizuku and Shizuku running as root | Pending |
| Android 9 device, secondary/work-profile user, shared UID, additional OEM formats | Pending; pure fixtures do not replace device acceptance |

Issue #503 remains open until review/merge and the required acceptance decision. This document records implementation and validation separately so unrun device checks are not mistaken for completed work.

The opt-in device test is `com.valhalla.thor.data.appops.AppOpsIntegrationTest`, with runner argument `appOpsTestPackage=com.valhalla.thor.acceptance.fixture`. It changes only that disposable fixture's `READ_CLIPBOARD` operation, then restores and verifies both original scoped policies. It does not perform a whole-app reset. The latest FOSS debug APK was installed on the rooted emulator for the visual check; no release version was changed.

For a read-only check without a fixture, run `com.valhalla.thor.data.appops.AppOpsReadIntegrationTest` with `appOpsReadTest=true`. It reads Thor Debug's own operations through the production repository and does not change permissions or modes. The Xiaomi compatibility build was installed and visually verified on the physical device.
