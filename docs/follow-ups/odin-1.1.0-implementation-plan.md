# Odin 1.1.0 implementation plan and progress tracker

**Status:** Implementation underway; the work-package rows and evidence below track progress.
**Priority:** Reliability and accurate root status first; broader cancellation adoption follows.
**Approved:** 2026-09-30.
**Audit baseline:** Thor `e16285b102213a4780b7e98f0775852da97fdd88` (`dev`, after PR #531).
**Dependency:** `com.trinadhthatakula:odin:1.1.0`.

This is the current implementation roadmap for the Odin audit findings. It records the agreed
sequence, intended app behavior, and acceptance checklist. The older
[cancellation](odin-per-job-cancellation.md) and
[root-availability](odin-root-availability-cache.md) documents retain historical context; their
upstream-only proposals are not the remaining Thor implementation plan.

## How to keep this tracker current

- Update the work-package row and its checklist in the PR that changes the behavior. Add the owner,
  PR link, tested commit, and evidence while the work is in progress.
- Use `Not started`, `In progress`, `In review`, `Merged — validation pending`, or `Done` for status.
  Only use `Done` when implementation and applicable acceptance checks have recorded evidence.
- A checked implementation box does not imply its device checks passed. Keep unrun checks open;
  record a justified non-applicable result explicitly instead of silently checking it off.
- Keep this document as the single progress tracker. Link related follow-ups here instead of
  maintaining competing copies of the same checklist.
- Reverify the cited code when starting each package; the audit baseline is a snapshot, not a
  guarantee that a finding remains valid after subsequent merges.

## Already available at the baseline

These are existing capabilities, not completed work packages from the new plan:

- Odin 1.1.0 is resolved as an external dependency. It provides isolated job handles, detailed
  completion outcomes, bounded fresh root acquisition, and shared startup/refresh attempts.
- Settings Editor already uses isolated jobs and forwards cancellation. Its watchdog uses
  `toybox timeout --foreground -s KILL`; retain this protection during further adoption.
- `PrivilegeManager.refresh()` already invalidates Odin's cached observation. Fresh acquisition
  can observe a changed grant; typed propagation through Thor is the remaining gap.
- ARCHIVE and SWEEP have dedicated mount-master shells. Their current cancellation closes the
  shell generation, which does not itself acknowledge descendant termination.
- The audit ran 93 focused FOSS debug unit tests with no failures, errors, or skips. Those tested
  existing routing behavior; they do not validate the future changes below. No device tests ran
  as part of that audit.

## Agreed design and app behavior

### One shared privilege state

Keep `PrivilegeManager` as the owner of observations and refresh requests, exposed through
`PrivilegeStateProvider`. Add an injected Odin adapter using the existing Koin architecture.
Screens consume state rather than creating shells or independently probing root.

Root state must keep these facts separate:

| Field | Values or purpose |
| --- | --- |
| Last confirmed availability | Unknown, root available, non-root |
| Refresh status | Idle, checking, busy, timed out, failed |
| Observation revision | Changes on invalidation and completion, including ROOT to ROOT |
| Confirmed revision | Changes only after ROOT or NON_ROOT; governs idle shell retirement |

Root/non-root describes the latest completed acquisition. Busy, timeout, or failure does not prove
denial. Preserve initial-loading semantics separately from availability; completing the first
probe does not mean root is available.

The intended behavior is:

- Keep loaded screen content visible during refresh. Show an appropriate checking, waiting, or
  retry state without misreporting root as revoked.
- Preserve the user's preferred provider through temporary unavailability.
- Let already accepted operations finish under their existing ownership.
- Temporarily defer new root mutations while root freshness is unresolved. Apply this admission
  rule to shell and Binder-backed root operations, without blocking the refresh mechanism itself.
- Keep independently available Shizuku/Dhizuku operations usable. In automatic mode, retain the
  selected route during a busy root check; use existing fallback after confirmed non-root.
- Retry a busy refresh once when the relevant work becomes idle, coalescing concurrent requests.
  Offer Retry after timeout/failure instead of continuously probing.
- Key measured capabilities by observation revision and provider. Key gateway selection by
  revision and preferred mode. Do not cache a transient failed measurement as proven unsupported.
- Make revision validation and dispatch ordering safe against concurrent refresh, using short
  coordination around admission rather than holding a refresh mutex for the command lifetime.

The refresh and admission PRs must be integrated before this whole behavior is considered shipped.
A transition that merely keeps an old ROOT Boolean must not authorize new work as if it were a
fresh observation.

### Explicit execution contracts

Keep scheduling lane and execution policy independent:

- **Lane:** INTERACTIVE, ARCHIVE, or SWEEP determines scheduling and shell ownership.
- **Policy:** persistent or isolated determines state persistence and cancellation behavior.
- **Command class:** identifies the operation for diagnostics; its name must not select policy.

Preserve legacy persistent behavior unless a trusted operation explicitly opts into isolation.
Carry Odin's terminal kind, `started`, nullable exit code, output, `terminationConfirmed`,
`outputDrained`, `shellReusable`, and failure detail in a Thor-owned result. Preserve ordinary
nonzero exits as command results rather than treating all failures as dead transport.

On cancellation of an isolated job, request termination, await acknowledgement under
`NonCancellable`, interpret the outcome, and then propagate structured cancellation. Do not flatten
cleanup failure into an ordinary cancellation. Persistent jobs retain their documented callback
drain or owned-session cleanup behavior; this does not imply per-job termination. Keep Settings
Editor's existing mutation/readback/journal finalization on screen dismissal.

### Uncertain execution and recovery

Odin completion can report that termination is unconfirmed. The user-facing progression should
distinguish Running, Stopping, Cancelled, and Could not confirm completion. Cancellation never
implies that earlier changes were rolled back.

Record uncertainty before normal ownership release, reusing Settings Editor history and restore
breadcrumbs where appropriate. Release ordinary coroutine locks after the bounded cleanup path,
but prevent conflicting operations on the affected resource through explicit recovery state.
Include Android user and resource identity where needed; unrelated work may proceed only when
its scope is known to be independent. Persist recovery state where work can outlive Thor's process.

Readback proves current state, not that an earlier producer stopped. A matching setting value,
stable file size, new shell, root refresh, or Thor process restart must not clear an unconfirmed
termination barrier by itself. Define independent termination evidence and safe cleanup before
enabling each workload. Odin's current public outcome does not expose a recoverable process
identity, so automatic recovery must not be promised without a supported contract.

Never automatically replay an uncertain mutation through another shell or provider. Shell
termination also does not cancel an accepted Binder transaction or work handed to `system_server`.

## Work-package overview

PR labels below are planned packages, not existing GitHub PR numbers. Each package uses a topic
branch from `dev`, targets `dev`, and leaves `versionCode` unchanged.

| ID | Package | Dependency | Owner | PR | Status |
| --- | --- | --- | --- | --- | --- |
| M1-01 | Shared privilege state in screens | None | Codex | [#534](https://github.com/trinadhthatakula/Thor/pull/534) | Merged — validation pending |
| M1-02 | Typed refresh and cache coordination | Coordinate with M1-01 | Codex | [#535](https://github.com/trinadhthatakula/Thor/pull/535) | Done |
| M1-03 | Root admission and lane recovery | M1-02 | Codex | [#535](https://github.com/trinadhthatakula/Thor/pull/535) | Done |
| M1-04 | Data-clear fallback correction | None | Codex | [#533](https://github.com/trinadhthatakula/Thor/pull/533), [#536](https://github.com/trinadhthatakula/Thor/pull/536) | Merged — validation pending |
| M1-05 | RootService connection ownership | None | Codex | [#537](https://github.com/trinadhthatakula/Thor/pull/537) | Done |
| M1-06 | RootService profile isolation | M1-05 test fixture recommended | Codex | [#538](https://github.com/trinadhthatakula/Thor/pull/538) | Done |
| M2-01 | Execution policy and complete outcomes | Milestone 1 | Codex | [#539](https://github.com/trinadhthatakula/Thor/pull/539) | Done |
| M2-02 | Cancellable export staging copy | M2-01 | Codex | [#540](https://github.com/trinadhthatakula/Thor/pull/540) | Done |
| M2-03 | OBB context and cancellable placement | M2-02 | Codex | [#541](https://github.com/trinadhthatakula/Thor/pull/541) | Done |
| M2-04 | Selected archive/cache/import adoption | M2-02; workload-specific recovery | Codex | [#542](https://github.com/trinadhthatakula/Thor/pull/542), [#543](https://github.com/trinadhthatakula/Thor/pull/543) | Selected input reads and archive icons merged; broader adoption pending |
| M3-01 | Settings Editor reconciliation | M2-01 | Codex | [#544](https://github.com/trinadhthatakula/Thor/pull/544), [#545](https://github.com/trinadhthatakula/Thor/pull/545) | Merged through #545 (`95e1bdb5`); live-writer emulator recovery and acknowledged cancellation on both devices validated below |
| M3-02 | Typed Binder results and compact readback | Milestone 1; protocol design | Codex | [#546](https://github.com/trinadhthatakula/Thor/pull/546), [#547](https://github.com/trinadhthatakula/Thor/pull/547) | Compact readback and tracked clear-data implemented and validated; wider mutation/IPC acceptance remains open |
| M3-03 | Diagnostics and documentation reconciliation | Follow the affected packages | Unassigned | — | Not started |

**Starting pair:** M1-01 and M1-04 deliver independent reliability fixes. M1-05 can proceed in
parallel. Design M1-02/M1-03 together so state, routing, and admission use the same revision.

## Milestone 1 — reliability and accurate root status

### M1-01: Shared privilege state in screens

At the baseline, App Details and Settings called root probes that could throw `ShellLaneBusy`
during ordinary contention, aborting loading. PR #534 moves those screens, the installer, and
the launcher to shared privilege observation. Typed refresh and admission remain M1-02/M1-03.

- [x] Replace direct display probes with shared `PrivilegeStateProvider` observation.
- [x] Keep content/loading/error transitions correct during busy or failed refresh.
- [x] Review remaining installer/launcher probes; one provider's failure must not skip discovery
  of independently available providers. Preserve structured cancellation.
- [x] Add regression tests for busy and transport-failed root observation in both screens.
- [ ] On an emulator/device, hold an acknowledged MainShell job, open both screens, release the
  job, and verify no crash, stuck loader, or stale status.
- [x] Exercise the production App Details and Settings view models under acknowledged MainShell
  contention on the emulator and connected device, including release/recovery.

**Validation, 2026-09-30:** tested code `c5921e498be224be95afd410d1c578fd2f98653a`,
external Odin `1.1.0`. JDK 21 `test lintFossDebug lintStoreRelease` and FOSS debug/app-test APK
assembly passed (`--no-parallel --max-workers=2`). Each FOSS/Store debug variant ran 3,122 tests
with zero failures, errors, or skips; lint had no errors, MissingTranslation warnings, or
SyntheticAccessor findings. Coverage includes content preservation and shared provider updates,
installer readiness timeout/recovery, provider selection and confirmation, accepted installs,
launcher timeout versus confirmed absence, and cancellation.

`SharedPrivilegeContentionIntegrationTest` passed on both the KernelSU API 36 device and Magisk
API 36.1 emulator. A real routed helper publishes readiness, holds MainShell while both production
Koin view models load, and releases before recovery is checked. Startup can compete for admission;
only `ShellLaneBusy`, raised before helper dispatch, is retried. Execution/transport failures are
never replayed. The test constructs/observes view models rather than rendering screens, so the
full navigation/visual acceptance box remains open. The FOSS debug app was also installed and
successfully launched on the connected device. Tests use Thor's root grant, without `adb root`.

Local evidence: `~/.codex/artifacts/thor-odin-m1-2026-09-30/` —
`shared-ui-validated-gates.log`, `shared-ui-ksu-final.log`, `shared-ui-magisk-final.log`, and
`shared-ui-ksu-launch.log`. Reproduce the contention check by selecting
`SharedPrivilegeContentionIntegrationTest` with `odinRoot=true`.

**Combined validation, 2026-09-30–2026-10-01:** #533 and #534 are merged into `dev` at
`b8a8df68b8faebd8e4c47301977ad9a3c7898098`; its tree exactly matches the combined tested
commit `7d6b41739638d050d5f1b87df94a2e103069d625`. Required host gates passed with 3,126
app tests per FOSS/Store debug variant and no lint errors or warnings. The FOSS debug build
passed 13 instrumentation tests on `Odin_Magisk_API36_1` and 12 on the physical KernelSU
Android 16 device: shared-view-model contention, Odin lifecycle, and normal disposable-app
clearing on both, plus ordinary-refusal/real-AIDL fallback on the emulator. Home, Settings,
expanded App Details, and installer previews rendered on both devices. UI navigation ran
without held contention, so the stronger visual acceptance box above remains open; actual
post-dispatch data-clear fault injection also remains unrun. Evidence is recorded in
[#533](https://github.com/trinadhthatakula/Thor/pull/533#issuecomment-5917410851) and
[#534](https://github.com/trinadhthatakula/Thor/pull/534#issuecomment-5917411218), with local
logs/screenshots/APKs in `~/.codex/artifacts/thor-odin-combined-device-validation-2026-09-30/`.

### M1-02: Typed refresh and cache coordination

Start in `PrivilegeManager`, `PrivilegeState`, `ActiveGatewayResolver`,
`DataArchiveCapabilityCache`, and capability probes in `SystemRepositoryImpl`.

- [x] Add the injected Odin refresh adapter and the confirmed-availability/refresh-status model.
- [x] Preserve ROOT/NON_ROOT/BUSY/TIMED_OUT/FAILED and initial-loading semantics through Thor.
- [x] Publish observation revisions, including successful equal-value refreshes; complete
  `refreshAndAwait()` for those results and prevent older in-flight observations from replacing
  newer state.
- [x] Invalidate gateway selection by revision and preference; invalidate measured capability
  caches by revision/provider, including the component-capability cache where applicable.
- [x] Keep failed capability measurements unknown; cache only measured supported/unsupported.
- [x] Implement one coalesced idle retry after busy and explicit Retry after timeout/failure.
- [x] Test all observation transitions, concurrent refresh, one cancelled waiter, same-value
  cache recovery, and immediate routing after preference changes.
- [x] Validate host-controlled revoke/regrant through `PrivilegeManager.refreshAndAwait()` and
  actual UI/routing, rather than only calling Odin directly.

**Implementation:** root coordination lives in an independent `RootAvailabilityCoordinator`,
with `PrivilegeManager` publishing the combined state. This avoids a dependency cycle through
repository/gateway selection. Automatic routing follows the last confirmed availability; a
confirmed NON_ROOT observation keeps the existing Shizuku/Dhizuku fallback throughout an
unresolved refresh. Root-only calls still reach a typed admission refusal.

### M1-03: Root admission and lane recovery

Start in root gateway admission, `RootCommandRouter`, `RootFallbackCoordinator`,
`OwnedRootShellExecutor`, and `DefaultRootLaneStatusSource`.

- [x] Coordinate revision checks with new shell and Binder mutation admission, including direct
  root-only gateway paths that do not pass through ordinary provider selection.
- [x] Preserve accepted work and existing leases during refresh; return a recoverable state for
  new root mutations while freshness remains unresolved.
- [x] Recover degraded ARCHIVE/SWEEP capacity after a confirmed successful refresh at an idle
  boundary, without replaying a previously dispatched command.
- [x] Retire stale dedicated sessions only when idle. Treat existing RootService lifetime
  separately from MainShell refresh; do not kill accepted IPC work.
- [x] Test refresh/dispatch races, idle/active recovery, failed reopen, exact-generation cleanup,
  and independently available alternative providers.
- [x] Validate that accepted work survives refresh and subsequent work follows the new state.

**M1-02/M1-03 validation, 2026-10-01:** implementation commit
`f13450dd1ec12bb06b26d7785a4665f97938e223`, external Odin `1.1.0`, in
[#535](https://github.com/trinadhthatakula/Thor/pull/535), merged as `ce95c787`.
Later tracker commits change docs only. The results below record that PR's validation;
the subsequent physical clear-data compatibility fix is recorded under M1-04.
JDK 21 `test lintFossDebug lintStoreRelease` and FOSS debug/app-test APK assembly passed
(`--no-parallel --max-workers=2`). Each FOSS/Store debug variant ran 3,174 tests with zero
failures, errors, or skips. Lint has zero errors/warnings and no MissingTranslation or
SyntheticAccessor findings; existing hint-only reports remain.

| Selected instrumentation | Odin Magisk API 36.1 emulator | POCO F7 / ReSuKiSU API 36 device |
| --- | --- | --- |
| Refresh/admission and dedicated-shell replacement | 2 passed | 2 passed |
| Shared-view-model contention | 1 passed | 1 passed |
| Odin lifecycle | 10 passed | 10 passed |
| Normal disposable-app clearing | 1 passed | 1 passed |
| Ordinary shell refusal followed by real AIDL clear | 1 passed | **1 failed**; see M1-04 below |
| Host-controlled manager revoke/regrant | 1 passed | 1 passed |
| Total | **16 passed** | **15 passed, 1 failed** |

`RootRefreshAdmissionIntegrationTest` holds an acknowledged production gateway helper, observes
BUSY through `PrivilegeManager.refreshAndAwait()`, verifies new work cannot dispatch, then checks
normal completion, one idle refresh, and subsequent admission. A second test records separate
ARCHIVE/SWEEP shell PIDs and proves both are replaced after a confirmed refresh.
`RootRefreshPolicyIntegrationTest` uses private-cache handshakes while the host manually changes
only Thor debug's grant in Magisk/ReSuKiSU. It verifies NON_ROOT and ROOT through
`refreshAndAwait()`, refused mutation admission during denial, and successful UID 0 work after
grant restoration. Both tests require `odinRoot=true`; policy coordination additionally requires
`odinPolicyToggle=true` and must never run unattended without its host handshake.

Emulator Home, Settings, and Privilege Check reflected revocation and regrant in the same app
process, retained loaded content, and kept the dialog open after manual Refresh. The physical
ReSuKiSU v4.2.0-rc2 (35159/4) device passed the new API-level acceptance checks; final Home and
Settings rendered with both Root and Shizuku available and the original Shizuku preference
preserved. This does not establish live Shizuku/Dhizuku operation coverage; independent-provider
routing is covered by JVM tests. The stronger held-contention visual check in M1-01 remains open.
Thor debug's root grants were restored, both current debug APKs were left installed, and the
only destructive target, `com.valhalla.thor.audit.cleardata`, was removed. No `adb root` or shell-root
grant was used.

Local evidence: `~/.codex/artifacts/thor-odin-refresh-admission-2026-10-01/` —
`build-gates-with-policy.log`, `host-validation.json`, `emulator-admission-contention-clear.log`,
`emulator-odin-lifecycle.log`, `emulator-manager-policy.log`, `resukisu-refresh-admission.log`,
`resukisu-contention.log`, `resukisu-lifecycle.log`, `resukisu-clear-data.log`,
`resukisu-policy.log`, and manager/UI screenshots. `tested-code-files.sha256` records the exact
source inputs; its manifest digest is
`1ac43c0433a22b577916b531d181c22e434782a76a0885b418e895ff92306e8c`.

**Current task, updated 2026-10-02:** archive-icon staging merged in
[#543](https://github.com/trinadhthatakula/Thor/pull/543) as `1c0424f9`, following selected
preview/import reads in [#542](https://github.com/trinadhthatakula/Thor/pull/542). M3-01 now adds
explicit read-only checks for uncertain Settings Editor history. This depends on completed M2-01;
it does not require enabling cancellation for the broader M2-04 workloads. Tar creation,
extraction, final restore mutations, and cache deletion remain deferred until their source/output
ownership and durable uncertainty contracts are ready. Their checkboxes and the broader device
acceptance matrix remain open.

### M1-04: Data-clear fallback correction

The baseline `RootSystemGateway.clearAppData` fell from every failed shell result into AIDL,
including a transport failure after the destructive command may have run. PR #533 preserves
structured execution failures before considering ordinary-failure fallback.

- [x] Stop fallback on structured uncertain execution failures, following existing gateway
  handling of `PrivilegeExecutionException`.
- [x] Preserve explicitly supported ordinary-failure fallback, Android user identity, real
  observer confirmation, and structured cancellation.
- [x] Test post-dispatch transport loss and timeout-class failure with zero AIDL replays.
- [x] Validate normal data clear and permitted fallback using a disposable fixture app; record
  what was actually exercised and leave untested failure injection open.
- [ ] Inject actual post-dispatch transport loss/deadline failure on a device; current no-replay
  coverage supplies the typed failures at the gateway executor boundary.
- [x] Fix the confirmed physical-firmware AIDL clear-data compatibility gap found on 2026-10-01.
  Use the verified ActivityManager API while preserving the Android user, observer confirmation,
  and no-replay rule; validate both clear-data paths on ReSuKiSU and Magisk.

**Compatibility fix and validation, 2026-10-01:**
[#536](https://github.com/trinadhthatakula/Thor/pull/536), implementation commit
`8c6e57749497e8c9c5f3fc577e29794909a86756`, based on merged #535 (`ce95c787`), external Odin
`1.1.0`, merged as `711a018c`. The daemon now uses
`IActivityManager.clearApplicationUserData(String, boolean, IPackageDataObserver, int)` with
`keepState=false`, following Android's `pm clear`. The explicit user ID is argument four.
The Boolean result means request acceptance; only the existing observer confirms successful
completion. Refusal, invocation failure, or missing confirmation returns failure, with no
second API attempt or replay. Thor's AIDL wire contract is unchanged.

The matching ActivityManager signature was verified in the physical framework and AOSP Android
9–16: [Android 9 declaration](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/core/java/android/app/IActivityManager.aidl#L220),
[Android 16 declaration](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/app/IActivityManager.aidl#L335),
and [Android 16 pm clear](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java#L2356).
The firmware-specific PackageManager boolean is neither used nor inferred.

JDK 21 `test lintFossDebug lintStoreRelease` and FOSS debug/app-test APK assembly passed
(`--no-parallel --max-workers=2`): 3,174 tests per FOSS/Store debug variant, zero failures,
errors, or skips. Both lint gates have zero errors/warnings, including no MissingTranslation
or SyntheticAccessor findings; existing hints remain.

| `RootClearAppDataIntegrationTest` | Normal root clear | Ordinary shell refusal → real daemon |
| --- | --- | --- |
| POCO F7 / Android 16 / ReSuKiSU v4.2.0-rc2 (35159/4) | Passed | Passed; observer confirmed |
| Odin Magisk API 36.1 / Magisk 30.7 emulator | Passed | Passed; observer confirmed |

Both devices ran the unchanged two-test regression suite against the exact built APKs.
The tests check fixture-marker removal, one shell attempt, and the expected daemon reset count;
both daemon logs report observer-confirmed `CLEARED`. The first physical fixture installation
was blocked by Android; it succeeded when retried at the user's request, before any physical
clear-data test ran. No clear-data mutation was retried. The only destructive target was
`com.valhalla.thor.audit.cleardata`, removed afterward on both devices. Installed app/test APK
hashes match the validated build. Thor's existing app grants were used without `adb root`,
ADB shell-root grants, or manager changes.

These device runs cover the primary Android user. AOSP signature compatibility is not runtime
validation of every supported Android version. Actual post-dispatch transport/deadline fault
injection and broader profile/lifecycle acceptance remain open; the earlier full-suite failure
below is retained as historical evidence.

Local evidence: `~/.codex/artifacts/thor-root-clear-api-compat-2026-10-01/` — `build-gates.log`,
`host-validation.json`, `API-CONTRACT.md`, `emulator-clear-data.log`, `resukisu-clear-data.log`,
`emulator-root-service-logcat.log`, `resukisu-clear-data-odin-logcat.log`,
`emulator-install-verification.log`, and `resukisu-retry-install-verification.log`.
`tested-code-files.sha256` records the exact source inputs; its manifest digest is
`eed5f85c1f6da10b1e8ffd490f28542259b8d709554a48a0dd854519fe209173`.

**Original compatibility failure, 2026-10-01:** the forced ordinary-shell-refusal test passed on the
Magisk emulator but failed on the POCO F7 / Android 16 / ReSuKiSU device. The root daemon returned
false because looking up `IPackageManager.clearApplicationUserData(String, IPackageDataObserver,
int)` throws `NoSuchMethodException`, before that AIDL clear can be dispatched. Offline inspection
of the device's actual `framework.jar` confirms its interface instead declares
`clearApplicationUserData(String, IPackageDataObserver, int, boolean)`; the three-argument overload
is absent. Do not infer the additional boolean's semantics from its type.

The same test reproduced the identical lookup failure on the paired baseline APKs whose tree
matches merged `dev` at `b8a8df68`; `ThorRootService` and its AIDL are unchanged in #535. This is an
existing compatibility defect exposed by broader validation, not a missing ReSuKiSU grant or a
new admission refusal. The historical full physical suite remains recorded as 15 passed, 1 failed.
Evidence: `resukisu-baseline-clear-data-aidl.log`, `resukisu-root-service-logcat.log`,
`resukisu-clear-api-signatures.txt`, and `resukisu-framework.jar` in the original
`~/.codex/artifacts/thor-odin-refresh-admission-2026-10-01/` directory.
Both current APKs were restored after the baseline comparison.

**Validation, 2026-09-30:** tested code `a1a23f7ffa2f6bf6377445c6620e679f3c3bedc1`,
external Odin `1.1.0`. JDK 21 `test lintFossDebug lintStoreRelease` and FOSS debug/app-test APK
assembly passed (`--no-parallel --max-workers=2`). Each FOSS/Store debug variant ran 3,111 tests
with zero failures, errors, or skips; lint had no errors, MissingTranslation warnings, or
SyntheticAccessor findings.

`RootClearAppDataIntegrationTest` passed normal clearing on the connected KernelSU device and
both normal clearing and the real AIDL fallback on the Magisk API 36.1 emulator. The fallback
test substitutes one ordinary shell refusal before using the production root daemon. Both paths
verify removal of a unique marker in the debuggable disposable package
`com.valhalla.thor.audit.cleardata`; neither touches another app's data. The physical-device
AIDL fallback was not exercised. Startup lane contention was observed on the first physical run;
the final test waits for idle before its single wipe and never retries that mutation. Root was
verified through Thor's app grant, without `adb root`.

Local evidence: `~/.codex/artifacts/thor-odin-m1-2026-09-30/` —
`data-clear-final-gates.log`, `data-clear-ksu-final.log`, `data-clear-magisk-final.log`, and
`odin-dependency.log`. Reproduce instrumentation with `odinRoot=true` and
`odinClearDataTestPackage=com.valhalla.thor.audit.cleardata` after installing that disposable
fixture; select `RootClearAppDataIntegrationTest` on either root manager.

### M1-05: RootService connection ownership

Start in `RootSystemGateway.getRootService`. Odin may finish a pending bind after Thor's earlier
unbind found no established connection.

- [x] Track ownership of each binding attempt and immediately unbind the exact late connection
  on main when its waiter has timed out or been cancelled.
- [x] Make cancellation/late-callback cleanup idempotent.
- [x] Make old disconnect/null-binding callbacks leave a newer connection and binder intact.
- [x] Add a small binding facade and deterministic tests for late connection, duplicate cleanup,
  timeout/cancellation, replacement, and old callbacks after replacement.
- [x] Exercise delayed startup and bind/unbind/rebind with Odin on the rooted Magisk emulator.
- [x] Repeat binding lifecycle and clear-data regression checks on the physical ReSuKiSU device;
  all three checks passed after the phone reconnected.

**Implementation:** `RootServiceConnectionOwner` owns the cached Binder and each unique
`ServiceConnection` on main. The gateway delegates binding through `RootServiceBinding`;
`OdinRootServiceBinding` posts callbacks after Odin finishes its connection bookkeeping.
Cancellation marks only its attempt abandoned and queues independent cleanup. A successful
unbind before registration can be a no-op, so a late connected callback performs the second
exact-connection release. Duplicate callbacks and stale disconnect/null/death notifications
cannot retire the replacement, even when Odin shares the same Binder between connections.
Cache reads follow the same ownership boundary. A stalled main queue cannot pin the waiter or
allow its cancelled queued bind to start later. The existing broad daemon reset is unchanged
and remains the separate M1-06 task.

**Odin 1.1.0 limits:** its public `unbind` does not remove pending startup tasks. Thor bounds its
wait and releases eventual registrations; it cannot remove a library task whose startup never
finishes. Odin can also retain a server-side reference after a null binding without creating a
client connection record for `unbind` to release. These need separate Odin lifecycle changes;
the Thor fix does not substitute a service stop or process-pattern kill for per-attempt cleanup.

**M1-05 validation, 2026-10-01:** implementation commit
`7a8c9ac18fe1d51fdd0394deb91de48da1c8b525`, based on merged #536 (`711a018c`), in
[#537](https://github.com/trinadhthatakula/Thor/pull/537). External Odin `1.1.0` was confirmed
by dependency insight. Subsequent tracker commits change documentation only.

JDK 21 `test lintFossDebug lintStoreRelease` and FOSS debug/app-test APK assembly passed
(`--no-parallel --max-workers=2`). Each FOSS/Store debug variant ran 3,188 tests, including
14 new ownership tests, with zero failures, errors, or skips. Both lint gates have zero
errors/warnings, no MissingTranslation or SyntheticAccessor findings, and existing hints only.

| Scenario | Odin Magisk API 36.1 / Magisk 30.7 | POCO F7 / API 36 / ReSuKiSU v4.2.0-rc2 (35159) |
| --- | --- | --- |
| Real delayed startup, cancellation, timeout, late cleanup, cache, and unbind/rebind | 1 passed | 1 passed |
| Normal clear and ordinary-shell-refusal → real daemon | 2 passed | 2 passed |

`OdinRootServiceBindingIntegrationTest` holds the startup task returned by Odin's public
`bindOrTask`. One acknowledged pending request is cancelled and another reaches the owner's
timeout before a replacement starts. Releasing startup once produces real late registrations;
the test verifies both exact connections are released and the replacement survives. It also
injects stale client null/death notifications and verifies cached reuse and unbind/rebind with
harmless `dumpPackage` reads. Select this class alone with `odinRoot=true` so the isolated test
runtime starts with a fresh Odin client manager. The clear-data suite runs in a separate
instrumentation invocation with its disposable fixture opt-in.

Installed app/test APK hashes match the built artifacts on both devices, and the sole
clear-data target, `com.valhalla.thor.audit.cleardata`, was removed afterward on both. The first
physical attempt stopped before installation because the phone disconnected; after it
reconnected, binding and clearing passed with no skips. During explicit physical unbind/rebind,
Odin caught a `DeadObjectException` from the retiring server and recovered; replacement read/cache
assertions passed. Both devices logged observer-confirmed fixture clearing. No `adb root`,
shell-root grant, or root-manager toggle was used. These checks cover the primary Android user;
the separate multi-user/minified-runtime matrix items and actual post-dispatch fault injection
remain open.

Local evidence: `~/.codex/artifacts/thor-root-service-binding-2026-10-01/` — `build-gates.log`,
`host-validation.json`, `odin-dependency.log`, `emulator-binding.log`, `emulator-clear-data.log`,
`emulator-odin-logcat.log`, `emulator-install-verification.log`, `resukisu-binding.log`,
`resukisu-clear-data.log`, `resukisu-odin-logcat.log`, `resukisu-validation.json`,
`resukisu-install-cleanup-verification.log`, and both `*-environment.txt` records.
`tested-code-files.sha256` has manifest digest
`0293d1212005efb4ac0f31e75791e86e83676a172585db80196325625eaa4764`.

### M1-06: RootService profile isolation

The former `pkill -f <applicationId>:root` in `RootSystemGateway` matched Odin servers belonging
to other Android users. The gateway now delegates directly to its connection owner.

- [x] Test removal of the blanket reset against Odin's existing APK-update/client-death lifecycle.
- [x] Verify ordinary startup, APK replacement, app-process death, and rebind load the expected
  code without process-pattern killing.
- [x] Bind harmless fixture operations in two Android users; initialize/rebind one and verify
  the other's Binder and acknowledged work survive, in both directions.
- [x] Check for stale code before adding an owned-retirement or version-handshake workaround.
  No stale-code case occurred in the tested normal lifecycle; a new AIDL handshake was unnecessary.

**Implementation:** `eff6de65` in [#538](https://github.com/trinadhthatakula/Thor/pull/538), merged
as `316c5892`, based on #537's merge (`447ce79e`). The reset command, mutex,
flag, and command class are removed. Existing clear-data tests no longer bypass or allow a
reset, and require exactly one shell wipe attempt before an ordinary-refusal Binder fallback.
The new debug-only fixture verifies compiled code identity, root process/instance continuity,
and the caller's full UID and user-specific package context. Instrumentation initializes the
actual gateway, performs harmless package reads, and asserts zero attempted shell commands.

**Odin contract checked:** published Odin `1.1.0`, source commit
`1741da74bd0154d984eb780b2cbd1a02b0d3e1ac`. Thor uses ordinary service mode. Odin names ordinary
servers `<package>:root:<userId>`, launches with the full app UID and installed APK path, binds
clients by full UID, and exits on APK replacement or loss of its last ordinary client/service.
Ordinary startup does not rediscover a daemon from an earlier app process. See
[RootServiceManager](https://github.com/trinadhthatakula/Odin/blob/1741da74bd0154d984eb780b2cbd1a02b0d3e1ac/odin/src/main/java/com/valhalla/superuser/internal/RootServiceManager.kt),
[RootServiceServer](https://github.com/trinadhthatakula/Odin/blob/1741da74bd0154d984eb780b2cbd1a02b0d3e1ac/odin/src/main/java/com/valhalla/superuser/internal/RootServiceServer.kt),
and [RootServerMain](https://github.com/trinadhthatakula/Odin/blob/1741da74bd0154d984eb780b2cbd1a02b0d3e1ac/odin/src/main/java/com/valhalla/superuser/internal/RootServerMain.kt).

**M1-06 validation, 2026-10-01:** JDK 21 `test lintFossDebug lintStoreRelease`, FOSS debug/app-test
APK assembly, and dependency insight passed with published Odin `1.1.0` and no local substitution.
Each FOSS/Store debug variant ran 3,188 JVM tests with zero failures, errors, or skips. Both lint
gates have zero errors/warnings, no MissingTranslation or SyntheticAccessor findings, and existing
hints only. Source manifest SHA-256:
`4a5185aba601f37233ce1098d7fa75e2ab73727e3fe34a619466df4fb6a1faa4`.

| Scenario | Magisk 30.7 / Odin API 36.1 emulator | POCO F7 / API 36 / ReSuKiSU v4.2.0-rc2 (35159) |
| --- | --- | --- |
| A cold bind/rebind, B bind/rebind after APK replacement, B bind/rebind after client death | 3 passed | 3 passed |
| Acknowledged hold interrupted by replacement; acknowledged hold interrupted by app force-stop | 2 expected interruptions; old PIDs retired | 2 expected interruptions; old PIDs retired |
| Hold in user 10 while user 0 binds/rebinds and restarts, then reverse users | 6 passed; 2 expected other-client interruptions | Not run; physical user 0 only |
| Existing delayed-binding ownership lifecycle | 1 passed | 1 passed |
| Normal fixture clear and ordinary shell refusal → real daemon clear | 2 passed | 2 passed |
| Final debug cold launch and installed app/test APK hash verification | Passed | Passed |

Totals: **12 emulator and 6 physical instrumentation passes, zero skips**. Six deliberately
interrupted invocations are recorded separately, not counted as passing JUnit tests. Both
cross-user survivors completed only after explicit release and retained their original PID and
instance UUID. User 10 reported UID `1010225` and `/data/user/10/com.valhalla.thor.debug`; owner
reported UID `10225` and its user-0 directory. The phone reported app UID `10392`. No user-context
fallback warning was observed. Existing binding regression runs logged a caught `DeadObjectException`
while retiring an old connection; replacement reads and all assertions passed.

Build A used `-PversionName=1.96.3-m106-old` as an update stimulus. Build B uses normal `1.96.3`
and the final test assertions; `versionCode` remained `1963`. The compiled fixture constant
changed from A to B, and old root PIDs exited before fresh B identities were accepted.

| APK | SHA-256 |
| --- | --- |
| A app | `5a130d9d97a5960e08931dcb2d7a775ccecea240b7eec03548ba874eefbf66ea` |
| A test | `a711d4b99bd8bfa87f889581dd9bb9c7ca0259d8ebc704a19180a7fd4cfd3c12` |
| B app | `bff9dc8040aa32d08785176518e95a2847e1c5b206db8d1bb0a8ec80c7912386` |
| B test | `218cf1bbaeebd2ef6b6a0b92945354557ecdc9da71d8a9d149ed800264520cce` |

The disposable emulator user was removed; Magisk's temporary **Device owner managed** setting
was restored to **Device owner only**. Thor's grant remains enabled and Shell's disabled. The
phone's root policy was unchanged. Both clear-data fixtures and all per-run marker directories
were removed; both devices retain build B. Initial phone installs were rejected with
`INSTALL_FAILED_USER_RESTRICTED`; installation succeeded after waking the screen and using
`--no-incremental` for the disposable fixture. These were installer failures before their tests,
not root-validation failures. No `adb root` or shell-root grant was used.

Local evidence: `~/.codex/artifacts/thor-root-service-profile-isolation-2026-10-01/` contains
`build-gates-final.log`, `host-validation.json`, `tested-code-files.sha256`, `apk-sha256.json`,
`*-results.json`, per-invocation logs, installed-hash verification, and Magisk before/after UI
captures. `lifecycle-check.py` and `regression-check.py` retain the bounded host orchestration.
The final physical logs use `resukisu-owner-retry` and `resukisu-retry`; earlier failed installation
logs remain as evidence. The following procedure describes the committed test interfaces.

**Reproduction.** The fixture lives only in the debug APK; it has no exported manifest component
or production AIDL additions. It reports the inlined `BuildConfig.VERSION_NAME`, root PID,
instance UUID, caller UID, attached package UID/data directory, and loaded APK path.

Build and retain both app/test APKs for A, then rebuild B from the same source and versionCode:

```bash
./gradlew :app:assembleFossDebug :app:assembleFossDebugAndroidTest -PversionName=1.96.3-m106-old
./gradlew :app:assembleFossDebug :app:assembleFossDebugAndroidTest
```

Copy A's outputs before building B. The outputs are `app/build/outputs/apk/foss/debug/`
and `app/build/outputs/apk/androidTest/foss/debug/`. B uses the normal version name from its
`output-metadata.json` (`1.96.3` for this snapshot). Preserve APK hashes and installed hashes;
the A override distinguishes loaded bytecode without changing versionCode or production AIDL.

Run one method per `am instrument --user N -w -r` invocation using
`com.valhalla.thor.debug.test/com.valhalla.thor.ThorTestRunner`. Class:
`com.valhalla.thor.data.gateway.root.OdinRootServiceLifecycleIntegrationTest`.

- `#probeGatewayRebindAndReportIdentity`: bind the fixture, initialize the real gateway,
  read Thor's own package, unbind its exact connection, rebind, and verify fixture continuity.
- `#holdAcknowledgedWorkUntilHostRelease`: keep one bounded Binder operation active for host coordination.
- Required arguments: `odinRoot=true`, fresh canonical UUID `odinRunId`,
  `odinExpectedBuild=<A or B version name>`, and `odinExpectedUserId=N`.
  Optional `odinHoldTimeoutMs` is bounded to 120000 ms; that is also the default.

Each invocation precreates app-owned files under `cache/odin-root-lifecycle/<UUID>/`.
Use `run-as com.valhalla.thor.debug --user N` to read them without shell root.
Wait for valid, nonempty `entered.json` before acting: the root process writes this acknowledgment.
Release the hold by writing exactly the UUID to `release`. Require successful instrumentation,
`completed.json` with `released=true`, and `result.json` with `status=passed`.
The result marker is written after connection cleanup succeeds. Compare PID and instance UUID,
as well as build/context identity; a dispatch receipt alone does not establish survival.

For same-user lifecycle checks, probe A, then replace A with B while an acknowledged user-0 hold
is active. Verify the old root PID exits and a fresh B probe reports the expected compiled build.
Separately force-stop only user 0's app during a hold, verify its root PID exits, and probe again.
The interrupted held instrumentation is expected in these two cases; do not count it as a pass.

For profile isolation, use one APK build and a disposable emulator user, installed with
`pm install-existing --user N`; start the user in the background without switching the foreground.
Hold acknowledged work in B while A initializes/rebinds, then force-stop/restart only A's app.
Release B and require unchanged Binder lifetime/PID/instance plus successful completion.
Repeat with the users reversed. APK installation/replacement changes shared package code across
users, so run that stage separately; it is valid for replacement to retire both users' old servers.

Release outstanding holds, finish or explicitly account for interrupted invocations, capture
markers/logs, and remove only their UUID directories. Stop/remove only the disposable test user,
verify removal, restore any temporary emulator configuration, and verify the final installed APKs.
Use app-owned root grants; these checks require neither `adb root` nor shell-root grants.
Physical-device scope is user 0; extra-user checks belong to the disposable emulator.
This procedure covers ordinary debug RootService lifetime, not daemon mode, minified loading,
every OEM/root manager, or an in-place APK hot-swap. Watch for Odin's package-context fallback
warning and retain any unavailable case as unverified.

## Milestone 2 — acknowledged cancellation for selected workloads

### M2-01: Execution policy and complete outcomes

- [x] Replace the `settings_editor.*` policy convention with an explicit persistent/isolated
  contract independent of lane and command class.
- [x] Preserve all relevant Odin outcome fields in Thor, including cancellation cleanup outcomes.
- [x] Define uncertainty recording/admission and staging-cleanup behavior before releasing
  ownership. Preserve original coroutine cancellation after recording the outcome.
- [x] Apply the contract to existing Settings Editor isolation first; preserve its watchdog and
  mutation/readback/journal finalization on screen dismissal.
- [x] Test the terminal-outcome matrix, pre-dispatch cancellation, drain/termination flags,
  unusable transport, unconfirmed termination, next admission, and no automatic replay.
- [x] Test real acknowledgement ordering with readiness markers and a TERM-resistant child.

**Implementation:** `ad1fd5ee853144031ef885d7a93508f1a4d628a5` in
[#539](https://github.com/trinadhthatakula/Thor/pull/539), merged as `1014a467`, based on #538's merge (`316c5892`).
Scheduling lane and diagnostic command class no longer select execution
policy. `PrivilegeExecutionContext.rootExecutionPolicy` defaults to `PERSISTENT`; Settings Editor
explicitly selects `ISOLATED`. MainShell and owned ARCHIVE/SWEEP sessions share the same isolated
adapter. Thor retains every Odin terminal field, including stdout/stderr separately, nullable exit
code, failure, started, termination/drain confirmation, and shell reuse. Ordinary nonzero exits
remain command results. Cancellation awaits acknowledgement and records it under `NonCancellable`
before releasing the lane, then rethrows the received cancellation with outcome metadata attached.
Local timeout exceptions also retain the acknowledgement. Only an unusable owned transport retires
its exact generation; isolated failure or uncertainty never replays through fallback.

`RootJobOutcome.cleanupConfirmed` requires both termination and output drain and is always false
for `TERMINATION_UNCONFIRMED`, even if its flags disagree. Workload observers persist ownership
before submission and consume completion before lease release. This defines the staging rule for
M2-02: delete or reuse a destination only after cleanup is confirmed; otherwise retain its recovery
record and resources. No archive workload has opted into isolation in M2-01.

Settings Editor writes persist a UUID and exact table/effective-user/key identity in an AtomicFile
under `noBackupFilesDir` before root submission. The sidecar stores terminal metadata, without
commands, setting values, output, or failure text. An unresolved record blocks that same setting's
Root and Shizuku writes, including undo, while reads and independent settings remain usable.
Only the owning execution's confirmed acknowledgement or a different known kernel boot UUID
clears it. Matching readback, a new shell, provider switch, root refresh, and app process restart
provide no clearance. Kernel identity comes from `/proc/sys/kernel/random/boot_id`; the editable
`Settings.Global.BOOT_COUNT` is not termination evidence. Missing or malformed boot identity and
unreadable/corrupt persistence fail closed; an unavailable boot identity cannot promise automatic
recovery even after reboot. The gate coordinates this Thor installation, not separate installations
or Android profiles. Settings Editor records unconfirmed execution distinctly in history and
shows a localized blocking message; its watchdog and protected mutation/readback/journal sequence
remain intact. Read-only history reconciliation is still M3-01.

Isolated process-group acknowledgement is not rollback and does not prove completion of detached
children or accepted Binder/system-server work. Device coverage below must not be extrapolated
to every OEM, policy-denied control shell, minified build, or archive workload.

**M2-01 validation, 2026-10-01:** JDK 21 `test lintFossDebug lintStoreRelease`, FOSS debug/app-test
APK assembly, and dependency insight passed with published external Odin `1.1.0` and no local
substitution. Each FOSS/Store debug variant ran **3,223 JVM tests**, with zero failures, errors,
or skips. Both lint gates have zero errors/warnings, no MissingTranslation or SyntheticAccessor
findings, and existing hints only. The tests cover the terminal flag matrix, cancellation before
submission and during outcome recording, exact-generation retirement/reuse, ownership-safe
persistence, invalid boot identities, and cancellation cause/suppressed metadata after coroutine
stack-trace recovery. Source manifest SHA-256:
`4b8bcdd2bed9febafbfb53e2a681faaa9c8f503a221b9708942ad99faaab1f49`.

| Scenario | Magisk 30.7 / Odin API 36.1 emulator | POCO F7 / API 36 / ReSuKiSU v4.2.0-rc2 (35159) |
| --- | --- | --- |
| Explicit persistent/isolated policy independent of command name on all three lanes | 1 passed | 1 passed |
| TERM-resistant child cancellation on INTERACTIVE, ARCHIVE, and SWEEP | 3 passed | 3 passed |
| App-domain canonical boot UUID matches authenticated root observation | 1 passed | 1 passed |
| Settings Editor watchdog, acknowledged helper start, and reap before next admission | 1 passed | 1 passed |
| Disposable SYSTEM/SECURE/GLOBAL edit, delete, undo, conflict, and diagnostic reads | 1 passed | 1 passed |
| Installed app/test APK hash verification and final debug Home launch | Passed | Passed |

Totals: **7 emulator and 7 physical instrumentation passes, zero skips**. Cancellation callbacks
held the lane while recording acknowledgement; the child was no longer live before subsequent
work, stdout/stderr remained separate, the reusable shell PID was retained, and submission/next
command markers proved no replay or stale output. Both devices ran user 0 only. Settings Editor
used unique disposable keys, verified their removal, restored the original preference/consent and
history, and left its recovery sidecar empty. The original history hash matched after each run.
Marker directories were removed only after confirmed cleanup. The final debug app/test APKs remain
installed. Root-manager policies were unchanged; no `adb root` or shell-root grant was used.

The three test classes ran separately through
`com.valhalla.thor.debug.test/com.valhalla.thor.ThorTestRunner` using
`adb -s <serial> shell am instrument -w -r -e class <class> <arguments> <runner>`:

- `com.valhalla.thor.data.gateway.root.OdinExecutionPolicyIntegrationTest`: `-e odinRoot true`.
- `com.valhalla.thor.data.settingseditor.SettingsEditorDeadlineTest`: `-e settingsEditorMode ROOT`.
- `com.valhalla.thor.data.settingseditor.SettingsEditorIntegrationTest`: `-e settingsEditorMode ROOT`.

The app-domain boot-ID test confirms access on these two devices. JVM tests cover uncertain/failure
outcome handling, persistence reopening and corruption, and simulated boot changes. Actual
unconfirmed termination/control-shell denial, forced process-death recovery, and reboot recovery
were not fault-injected on the devices. Live Shizuku/Dhizuku operations, cross-profile coordination,
detached producers, and archive staging remain outside this validation. The broader acceptance
matrix below remains open.

Both devices matched these built APK SHA-256 values:

- FOSS debug app: `b50f7b9e875ef09e74e16ce0609cf8083d3af52be4b079a8b02a41bca52190e6`.
- FOSS debug test: `fc7d902e3ac38d0c73e8dfa8f47af9ea8ea5d3e563681ac85e17d393abaf267d`.

Local evidence: `~/.codex/artifacts/thor-odin-execution-policy-2026-10-01/` —
`build-gates-verified.log`, `dependency-insight.log`, `host-validation.json`,
`tested-code-files.sha256`, `emulator-validation.json`, `resukisu-validation.json`,
each device's `OdinExecutionPolicyIntegrationTest`, `SettingsEditorDeadlineTest`, and
`SettingsEditorIntegrationTest` logs, history hashes, cleanup checks, and final Home screenshots.
Earlier failed/unfinished runs are retained separately; they are not the acceptance evidence.

### M2-02: Cancellable export staging copy

- [x] Opt one bounded-output `file.copy` staging path into isolated execution on ARCHIVE.
- [x] Preserve package/work identity, mount-master namespace, unique destination, and deadlines.
- [x] Require confirmed termination/drain before normal cleanup; retain uncertainty when proof
  is absent, rather than deleting or reusing an actively written destination.
- [x] Verify cancellation responsiveness, cleanup order, subsequent safe shell reuse, and an
  unrelated lane's ability to work. Measure aggregate isolated-copy overhead (including control
  shell setup) and temporary storage; record the limits of timing attribution.

**Initial implementation:** `53d7a3a6df4bf5d8f41efc0ba59b82140b4577a0` in
[#540](https://github.com/trinadhthatakula/Thor/pull/540), based on #539's merge (`1014a467`).
**Merged:** `1fdda7450ed5332a5625939f17f59cc26f3d438b` on 2026-10-02 (local date).
The direct and durable export use cases select `buildExportWithProgress`.
Only that entry point's APK root-copy fallback adopts isolated execution on ARCHIVE; app-readable
copies retain their existing byte-copy path. Share, backup/archive builders, OBB probes, OBB copies,
and other root commands retain their previous policies. The selected copy preserves package/work
identity, the existing nine-minute maximum or a shorter caller deadline, and fallback provenance.
Owned ARCHIVE shells retain their mount-master configuration; degraded routing is unchanged.

Root copies write into a new UUID directory under `noBackupFilesDir/root_export_staging`, outside
the bundle/batch cache trees and launch sweeper. An app-owned payload file preserves readability
when root overwrites it. Before submission, an AtomicFile receipt records UUID, package/work
identity, kernel boot UUID, and recovery metadata. Terminal metadata is persisted before the
external observer returns and before lane release. Receipts omit source paths, commands, output,
and raw failure text; non-regular receipt files and malformed records fail closed.

The payload is promoted into the bundle cache only after a recorded `EXITED`, exit zero,
started, termination-confirmed and output-drained outcome. Promotion first attempts an atomic
move. If Android reports `AtomicMoveNotSupportedException` (including cache project-quota
boundaries), a cancellable app-side copy replaces the destination and verifies its size against
the confirmed payload. A failed, incomplete, or cancelled fallback removes its destination and
preserves the original failure. Other move errors propagate without fallback. The existing verified
operation boundary and byte checks follow promotion, before publication. Cancellation preserves
its original exception and acknowledgement; confirmed cleanup removes the private workspace.
Missing or uncertain completion and terminal-persistence failure retain it without promotion,
deletion, or reuse. Existing export/batch cleanup can safely delete its ordinary cache tree because
the uncertain producer never writes there.

Before a later export, the recovery sweep reclaims only recorded safe completion or a different
known canonical kernel boot UUID. An in-process registry excludes active workspaces, including
while an outcome observer or promotion is pending. Same-boot process restart, root refresh,
new shells, stable file length, and elapsed time do not authorize reclamation. Unresolved work
remains retained when boot/receipt evidence is missing, corrupt, or unavailable; recorded safe
completion permits cleanup even without boot identity. Each newly requested attempt gets a new
workspace; neither the component nor gateway retries its copy through another shell/provider.
This concerns private staging, not a change to durable-task publication/reconciliation policy.

This milestone does not opt OBB/restore writes into isolation, claim termination of detached
producers or Binder work, or add a UI for manually reclaiming unresolved private workspaces.

**Validation, 2026-10-01:** All checks below used the implementation SHA above and the published
Maven dependency `com.trinadhthatakula:odin:1.1.0`, with no local Odin substitution.

- `./gradlew test lintFossDebug lintStoreRelease :app:assembleFossDebug
  :app:assembleFossDebugAndroidTest --no-parallel --max-workers=2` passed with Zulu JDK 21.0.12.
  FOSS and Store debug each report **3,244 JVM tests**, zero failures/errors/skips. Both lint
  reports have zero errors/warnings, including no `MissingTranslation` or `SyntheticAccessor`
  findings; existing hints remain 14 FOSS / 13 Store.
- New deterministic coverage includes 13 staging lifecycle/recovery tests, 7 real-builder adoption
  tests, and a direct-export contract test alongside existing durable-publication tests. It checks
  acknowledgement ordering, original cancellation/provenance, pending and terminal persistence
  failures, absent outcomes, corrupt metadata, boot-aware recovery, active-work exclusion, atomic
  promotion failure, and non-export/OBB compatibility. Unconfirmed partial copies survive the
  outer export cleanup without publication.
- Restarted **`Odin_Magisk_API36_1`**, API 36.1 ARM64/16 KiB, Magisk **30.7 (30700)**:
  **7/7 instrumentation tests passed**, no skips.
- Connected **POCO F7 (`25053PC47G`)**, API 36, ReSuKiSU **v4.2.0-rc2 (35159)**:
  **7/7 instrumentation tests passed**, no skips, using Thor debug's existing manual grant.
- Each device ran `RootExportStagingIntegrationTest` (2 tests) and
  `OdinExecutionPolicyIntegrationTest` (5 tests), separately through
  `com.valhalla.thor.debug.test/com.valhalla.thor.ThorTestRunner` with `-e odinRoot true`.
  The new tests verify a root-only 256 KiB source through the real export builder, exact bytes and
  verified progress, owned mount-master namespace equality, and an actual `cp` blocked on a FIFO
  after copying 1,024 known bytes. Cancellation waits for acknowledgement/observer completion,
  retains files until then, and releases the lane afterward. INTERACTIVE work succeeds during the
  blocked copy; the next ARCHIVE command reuses the same shell PID with exact output and no replay.
- Fixture/recovery directories were empty after the final runs. The same debug APK was installed,
  hash-checked, launched, and visually inspected on Home on both devices. No `adb root`, shell-root
  grant, or root-manager policy changes were used.

Observed timings in milliseconds (three paired regular-copy samples; descriptive measurements,
not performance thresholds):

| Measurement | Magisk emulator | ReSuKiSU phone |
|---|---:|---:|
| Persistent copy median (range) | 17.1 (17.1–31.2) | 25.3 (17.4–25.4) |
| Isolated copy median (range) | 297.3 (290.0–321.2) | 389.6 (349.9–421.9) |
| Paired aggregate isolation delta median (range) | 280.2 (272.9–290.1) | 364.2 (332.5–396.6) |
| Full export staging, including receipts/promotion | 310.7 | 399.1 |
| INTERACTIVE command while FIFO copy is blocked | 17.8 | 20.3 |
| Cancellation request to terminal acknowledgement | 219.2 | 262.9 |

The paired delta includes isolated-process setup, control-shell work, termination and output
drain; it does not isolate control-shell acquisition time. Both devices observed a 262,144-byte
payload plus a 365-byte terminal receipt for success, and a 1,024-byte partial payload plus a
370-byte receipt for cancellation. These are logical file sizes observed at acknowledgement,
not filesystem allocation peaks or the size of transient AtomicFile replacement files. Atomic
promotion adds no second whole-payload copy; the compatibility fallback can temporarily retain
both the confirmed payload and a complete destination. These small fixtures do not establish large-export
throughput or a bound on storage retained by unresolved attempts.

Forced process death, reboot/power loss, and control-shell denial were not injected on hardware;
their receipt/recovery decisions have deterministic JVM coverage. No cross-profile, detached
producer, Binder, or OBB-isolation guarantee is inferred from these runs. Earlier device attempts
failed on fixture SELinux categories and an unsupported empty-stderr assertion after cancellation.
The fixture now creates its FIFO as the application UID, and cancellation retains valid shell
diagnostics while verifying drain and exact subsequent-command output. Those earlier attempts are
preserved separately and excluded from acceptance counts. Exact stale FIFO fixtures were removed
through Thor's app UID and existing root grant before the final runs.

Local evidence: `~/.codex/artifacts/thor-odin-export-staging-2026-10-01/` —
`build-gates-acceptance.log`, `dependency-insight.log`, `host-validation.json`, both device
`*-validation.json` and instrumentation logs, cleanup/launch logs, and `*-debug-home.png`.
The tested app APK SHA-256 is
`82341e8a70cdff8629cddad35fb5fdc3628bcc319404085a32169b07fa4c389c`; the test APK is
`6e311ddd6309b8d9a12a72520fb55090fda133f9759c9322a91114878d2ecea1`.
`tested-code-files.sha256` covers 1,009 source/build inputs and matches the committed implementation;
its manifest digest is `252c85a878d5d03dcfde4e0809552d54a8ef9055fed9c8f253190cf9fe581c5b`.

**Promotion compatibility follow-up, 2026-10-02:** The
[review finding](https://github.com/trinadhthatakula/Thor/pull/540#discussion_r4158891243)
identified that same-volume placement alone does not guarantee atomic rename. ext4/F2FS can
return `EXDEV` across inherited project-quota boundaries; Android maps this to
`AtomicMoveNotSupportedException`. The fallback above handles that exception after confirmed root
completion, without rerunning root work. Seven new `RootExportPromotionTest` cases force the
exception and cover successful replacement, empty payloads, missing/short destinations, partial
I/O failure, cancellation after the first actual copy chunk, and unrelated move failures.

The full local test/lint/build command above passed again: **3,251 JVM tests per FOSS/Store debug
variant**, zero failures/errors/skips, zero lint errors/warnings (14 FOSS / 13 Store hints).
The rebuilt debug APK passed the same **7/7 instrumentation tests on each device**, with no skips;
fixture/recovery cleanup and Home launch also passed. This revalidates the root-export lifecycle.
The unsupported-move exception is injected in JVM tests; neither device reproduced an actual
project-quota boundary failure. Current stock AOSP disables the internal project-ID feature, so
this is a compatibility case rather than a claim about every Android device.

Follow-up evidence: `~/.codex/artifacts/thor-root-export-promotion-2026-10-02/` —
`review-rationale.md`, `build-gates-final.log`, `host-validation.json`, and both device validation
JSON/instrumentation/cleanup/launch logs. The installed app APK SHA-256 is
`340d412ad1365f89169bff8331be4e63cfad92875fa4513109a5d64f4ebcf9af`; the unchanged test APK is
`6e311ddd6309b8d9a12a72520fb55090fda133f9759c9322a91114878d2ecea1`.
The follow-up source manifest covers 1,010 inputs with digest
`482d5023b25e150eb205bde1c7a2d44f24f88b862ced7059eb97a5cd92371865`.

### M2-03: OBB context and cancellable placement

- [x] Carry `PrivilegeExecutionContext` through `AppArchiveInstaller.placeBundleObb`, its
  implementation, and both `ObbInstaller` placement paths, including restore/install callers.
- [x] Route owned OBB work to ARCHIVE with `obb.mkdir`/`obb.copy` and the shorter of the caller's
  deadline or nine minutes; preserve package/work/sweep identity, provenance, and observers.
- [x] Opt root placement commands into isolated execution; preserve destination guards, size
  verification, one-file-at-a-time streaming extraction, and other providers' semantics.
- [x] Add unique source ownership, durable receipts, acknowledgement-gated cleanup, same-package
  admission, and automatic rollback refusal for active/unresolved OBB work.
- [x] Preserve unresolved OBB state when the archive installer's outer timeout consumes a
  cancellation, so restore does not clear its interruption breadcrumb as an ordinary failure.
- [x] Test archive routing, interactive responsiveness, partial-copy cancellation before source
  deletion, nonzero copy failure, recovery, and non-root placement compatibility without root callbacks.
- [x] Hold standalone XAPK package admission before OBB preflight through install and placement;
  explicitly reuse the restore caller's matching lease.
- [x] Publish checked OBB copies from unique temporary siblings, preserving previous files on
  pre-publication failure and cleaning temporary files on acknowledged catchable cancellation.
- [x] Pass required host gates and root device acceptance on the Magisk emulator and ReSuKiSU phone.
- [ ] Validate live Shizuku OBB placement separately; JVM compatibility coverage does not establish
  its process lifecycle or firmware behavior.

**Implementation:** `592995622039e4517fd93ee0802fe81b717cdb9a` in
[#541](https://github.com/trinadhthatakula/Thor/pull/541), based on #540's merge (`1fdda745`).
Subsequent review fixes and their validation are recorded below.

**Merged, 2026-10-02:** #541 is integrated into `dev` at
`e449623ca908d2a4bb3a314ef105541440ab2e54`. The recorded host and root-device acceptance below
covers this package; its explicitly unrun scenarios remain open.

`ObbPlacementStaging` owns unique sources at `externalFilesDir/obb_placement/<UUID>/` outside
cache cleanup, with private receipts at `noBackupFilesDir/obb_placement/<package>/receipt.json`.
The external source location preserves access for the existing Shizuku path. Metadata includes
operation/package identity, work/sweep IDs, canonical boot identity when available, and root
lifecycle flags; it omits commands, source paths, output, and raw failure details.

Each command persists and verifies fresh pending state before root dispatch. Terminal metadata
is persisted before forwarding the caller's outcome observer, and placement waits for that
observer before continuing or deleting a source. A process-local registry excludes another
same-package placement throughout the active session, including a paused terminal observer.
Confirmed termination/drain permits source cleanup after success, failure, or cancellation;
it does not undo writes already made to the final OBB file. Missing/uncertain completion or
terminal-persistence failure retains sources and receipts without replay. A subsequent placement
raises `ObbPlacementUnresolved` before dispatch. OBB-bearing install preflight and automatic
archive rollback also refuse active/unresolved ownership.

Recovery reclaims valid prepared records, acknowledged completion, or a record from a different
known canonical kernel boot. Process restart, root refresh, new shells, stable size, and readback
alone cannot clear submitted unresolved work. Missing/corrupt metadata or unknown boot identity
can leave it retained indefinitely. Cleanup touches only owned sources and receipts, avoids
following symlinks, and preserves the receipt when source deletion fails. Legacy `obb_in` cache
leftovers remain outside this receipt-aware cleanup.

The ten-minute archive install budget can expire during an OBB command after earlier install
work consumes part of it. When that outer `withTimeoutOrNull` returns null with unresolved OBB
ownership, the adapter raises a typed failure before converting to ordinary `Unconfirmed`.
Virtual-time regressions exercise the real isolated-command adapter for unconfirmed completion,
acknowledged cancellation, and a timeout with no OBB ownership; the latter two retain their
existing ordinary timeout result.

**Validation, 2026-10-02:** The implementation SHA above resolves published
`com.trinadhthatakula:odin:1.1.0` as an external AAR, with no local substitution.

- Zulu JDK 21.0.12: `./gradlew test lintFossDebug lintStoreRelease :app:assembleFossDebug
  :app:assembleFossDebugAndroidTest --no-parallel --max-workers=2` passed. FOSS/Store debug each
  report **3,281 JVM tests**, zero failures/errors/skips. Both lint reports have zero
  errors/warnings, including no `MissingTranslation` or `SyntheticAccessor` findings; existing
  hints remain 14 FOSS / 13 Store.
- New deterministic coverage: 17 ownership/recovery tests, 12 placement/adapter tests, and one
  restore-context regression. This covers observer sequencing, durable-write failures, corrupt or
  missing receipts, reboot/restart admission, deadline/context preservation, uncertain completion,
  concurrent refusal, ordinary copy failure, streaming bounds, rollback refusal, and no-hook
  non-root compatibility.
- **Magisk emulator:** `Odin_Magisk_API36_1`, API 36.1, ARM64/16 KiB, Magisk 30.7 (30700):
  **9/9 instrumentation tests passed**, zero skips.
- **Physical ReSuKiSU phone:** POCO F7 (`25053PC47G`), API 36, ARM64/4 KiB,
  ReSuKiSU v4.2.0-rc2 (35159): **9/9 instrumentation tests passed**, zero skips.
- Each ran `ObbPlacementIntegrationTest` (2), `RootExportStagingIntegrationTest` (2), and
  `OdinExecutionPolicyIntegrationTest` (5), through
  `com.valhalla.thor.debug.test/com.valhalla.thor.ThorTestRunner` with `-e odinRoot true`.
  Both devices kept their existing Thor app grant; no `adb root`, shell-root grants, manager
  policy changes, or user/profile creation were used.
- The same final app/test APKs were installed and hash-verified on both devices. Home launch and
  visual inspection passed. Private fixture/recovery snapshots were empty afterward.
  Instrumentation verifies external source and exact-owned target cleanup. Host `run-as` cannot
  inspect the external source tree on either device; that host observation remains unavailable.

The regular OBB scenario places two files (256 KiB and 128 KiB) through each entry point, checks
exact bytes via root readback, and preserves an unrelated sentinel. The streaming cancellation
scenario uses actual `cp` with only its source operand replaced by an app-created private FIFO
(external FUSE does not support FIFO creation). After 1,024 known bytes reach the target, it
checks INTERACTIVE work, cancellation acknowledgement, source retention while the outcome
observer is paused, subsequent ARCHIVE shell PID reuse, and no replay or second-file copy.
Fixtures use fresh UUID leaves under the installed instrumentation package; cleanup never
removes the package's OBB directory.

Single-run descriptive measurements, in milliseconds; these are not performance thresholds:

| OBB measurement | Magisk emulator | ReSuKiSU phone |
|---|---:|---:|
| Eager placement, two files | 775.5 | 1,244.1 |
| Streaming placement, two files | 777.9 | 1,219.1 |
| INTERACTIVE command during blocked copy | 19.8 | 18.5 |
| Cancellation request to terminal acknowledgement | 230.4 | 289.5 |

Both devices observed 393,216 logical source bytes for eager placement and a 262,144-byte
streaming peak. Cancellation retained a 4,096-byte extracted source and 382-byte receipt through
acknowledgement, with a 1,024-byte partial destination. These are fixture observations, not
filesystem-allocation peaks, large-archive throughput, or bounds on unresolved retained storage.

**Limits:** Admission covers these placement paths, OBB-bearing install preflight, and automatic
archive rollback. It does not block every unrelated APK update, uninstall, or data-clear operation.
Final OBB content is not automatically deleted, restored, or reconciled; cancelled/failed writes
may leave partial files. Non-root Shizuku lifecycle behavior is unchanged. Live Shizuku placement,
hardware process death/reboot/power loss, ENOSPC, control denial, and cross-profile fault injection
remain unrun. No detached-producer or Binder cancellation guarantee is inferred from these tests.

Local evidence: `~/.codex/artifacts/thor-odin-obb-placement-2026-10-02/` —
`build-gates-acceptance.log`, `dependency-insight.log`, `host-validation.json`, `review-rationale.md`,
`tested-code-files.sha256`, both `*-validation.json`, `*-root-acceptance.log`, environment,
fixture/launch logs, and `*-debug-home.png` files. Earlier host-only fixture/lint failures remain
in separate logs and are excluded from acceptance counts.
The app APK SHA-256 is
`225376cbe5b140a331a9c9b657203009c1c5b9d9fd23387c0a9aa1f849ba5c84`; the test APK is
`256ccd68689183277502396ba378192a480cd60389bc71ced14d6e6ab6196270`.
The source manifest covers 1,014 inputs and matches the committed implementation; its digest is
`b81b99670528ab88d195a7a99fec4ae931c244b9f899ae62a10899671be6f618`.

**Review fixes, 2026-10-02:** `99134fefba978c57b428f73364398abc64d5dfca` addresses both
reported issues. Cleanup-only exceptions no longer replace a completed OBB placement result;
cleanup errors remain suppressed on a primary failure, and source-cleanup failure keeps its
receipt for later recovery. Existing-app restores with OBB enabled now consult the read-only
`AppArchiveInstaller.hasUnresolvedObbPlacement` preflight before the first force-stop or data
replacement. Prior unresolved work returns an ordinary refusal without writing or clearing a
breadcrumb. Uncertainty from the current placement still throws `ObbPlacementUnresolved` and
retains the interruption breadcrumb. Disabled OBB and install-first paths retain their behavior.

The full JDK 21 test/lint/build command above passed again on this fix commit: **3,286 JVM tests
per FOSS/Store debug variant**, zero failures/errors/skips; zero lint errors/warnings and the same
14 FOSS / 13 Store hints. Five new restore regressions cover early refusal (including archives
without a bundle), unchanged breadcrumbs, normal admission, disabled/install-first behavior,
and current-attempt uncertainty; existing adapter coverage now checks the preflight delegation.
Both the Magisk emulator and physical ReSuKiSU phone passed the same **9/9 instrumentation
tests**, zero skips, with matching installed APK hashes, empty private fixture/recovery snapshots,
and successful Home launch/visual inspection. External cleanup is asserted in instrumentation;
host `run-as` observation remains unavailable. No manager-policy changes or `adb root` were used.
The new restore preflight is covered on the JVM; cleanup I/O failure was not injected on hardware.

Review-fix evidence: `~/.codex/artifacts/thor-odin-obb-review-fixes-2026-10-02/` —
`build-gates-acceptance.log`, `host-validation.json`, `tested-code-files.sha256`, both device
validation JSON/instrumentation/environment/fixture/launch logs, and Home screenshots.
The app APK SHA-256 is
`7823a0143c71ef1d1d9eb5706a477f6555d69b70e571f7584480b8e8b53eb31f`; the test APK remains
`256ccd68689183277502396ba378192a480cd60389bc71ced14d6e6ab6196270`.
The 1,014-input source manifest digest is
`d03cbe351d49bf0c11ea3bc8a9c5a2c050f9f5960ae7cfedd321787a54c825d5`.

**Installer admission and publication review fixes, 2026-10-02:**
`3d484edead88dd1fa5b03990f0c8d3c365fdd60d` fixes both additional findings in #541.
Standalone installs with a resolved XAPK package now acquire `REINSTALL` package admission before
`refusalReason` and retain it through installation, the existing confirmation wait, OBB placement,
outcome observation and source cleanup. Archive restore explicitly supplies its already-held
package name; a resolved OBB-target mismatch refuses before invocation or preflight. Execution
metadata alone never bypasses admission. Seven new JVM regressions cover busy admission,
callback ordering, ownership through paused install/outcome observation, independent packages,
matching restore reuse, mismatches, cancellation/failure release and external handoff.

Each copy uses an exclusively created UUID temporary sibling directory beneath the destination,
with the final OBB basename. `chmod 644` and the existing optional size check run on that staged
file, then `mv -f <temporary>/<leaf> <destination-directory>/` publishes it on the same filesystem.
The matching-basename form replaces file and directory symlinks themselves without `mv -T`,
which older supported Android toybox versions lack; actual directory destinations are refused.
The prior final file is preserved until publication. Normal exit and catchable signals call the
same cleanup function directly: device tests exposed Android mksh skipping an EXIT trap when a
subshell exits from a signal handler. Repeated catchable signals are ignored during cleanup.

The full JDK 21 test/lint/build gates passed on this commit: **3,294 JVM tests per FOSS/Store debug
variant**, zero failures/errors/skips; both lint reports have zero errors/warnings, with unchanged
14 FOSS / 13 Store hints. Published `com.trinadhthatakula:odin:1.1.0` remains resolved without local
substitution. Both the Magisk 30.7 API 36.1 emulator and physical ReSuKiSU v4.2.0-rc2 POCO F7
passed **11/11 instrumentation tests**, zero skips: OBB placement (4), root export (2), execution
policy (5). New device checks cover copy/size failure preservation, replacement, directory refusal,
symlink-target preservation and removal of the temporary sibling after FIFO cancellation. The
previous OBB is intact while 1,024 bytes exist only in the temporary file and after cancellation;
source/receipt ownership remains held through outcome observation. Installed APK hashes match
the host, private recovery/fixture snapshots are empty, and Home launch/visual inspection passed.
External source and target cleanup is asserted in instrumentation; host `run-as` external access
remains unavailable. Existing Thor app grants were used without root-policy changes.

**Remaining limits:** Borrowing a lease is a documented caller precondition. Coordination covers
resolved XAPK targets, not every package mutation or external installer. Accepted PackageInstaller/
Binder work can outlive cancellation or the existing confirmation timeout. A cancellation racing
publication may leave either complete version, and previously published files are not rolled back.
SIGKILL, power loss or cleanup I/O failure can retain a unique temporary directory; shell path-swap
races and the optional stat-unavailable behavior remain. Live Shizuku placement, old-API device
execution and forced-KILL/crash/reboot/ENOSPC/control-denial/cross-profile fault injection remain
unrun. Initial failed cancellation runs were diagnostic and are excluded from the final counts.

Evidence: `~/.codex/artifacts/thor-odin-obb-publication-review-2026-10-02/` —
`build-gates-acceptance.log`, `host-validation.json`, `dependency-insight.log`, `review-rationale.md`,
`tested-code-files.sha256`, both `*-final-validation.json`, instrumentation/fixture/launch logs,
environment records and Home screenshots. The 1,015-input source manifest digest is
`947bdea4d062309c7c889a37765fb573b9702374fe3195aa9074b050a1306f60`.
App APK SHA-256: `4d63cf1bf92db687f4debfd837598dd0a0acdf2595b68bf07695ccb3a07d95d8`.
Test APK SHA-256: `04ec82ac1d7ec9e250735c696bafb4be06c8981ddb93b81cee9ee622a10e9485`.

### M2-04: Selected archive/cache/import adoption

- [x] Inventory each candidate's state needs, affected resources, output bounds, deadlines, and
  recovery behavior before opting it in.
- [ ] Expand to suitable tar/extract/chown/restorecon/cache jobs only after copy validation;
  preserve package leases and mount visibility.
- [x] Add explicit policies to generated preview/import staging in `AppAnalyzerImpl` and
  `UriArchiveSourceFactory`; retain unique temporary paths and content-resolver handling.
- [x] Pin one selected provider throughout each privileged input copy and its cleanup; never
  replay a failed or uncertain copy through another provider.
- [x] Reuse private receipt-backed root staging, validate stopped payloads before promotion,
  retain uncertain work outside ordinary cache cleanup, and preserve preview byte limits.
- [x] Guard archive-source ownership when no descriptor is available; propagate cancellation
  and delete private copies that cannot be returned to their caller.
- [x] Record required host gates, resolved Odin dependency, and exact tested source/artifacts.
- [x] Validate both selected root entry points on the Magisk emulator and ReSuKiSU phone;
  record acknowledged cancellation, cleanup ordering, independent INTERACTIVE work, and safe
  subsequent work. Independent concurrent archive copies are covered by host tests.
- [ ] Validate each workload's child termination, cleanup, uncertainty, and concurrent operation
  behavior. Do not infer all workloads passed from one copy test.
- [x] Adopt archive-icon staging with cancellation cleanup, the existing byte budget,
  independent concurrent fetch ownership, and transient failures separated from persistent misses.
- [x] Record archive-icon host gates and Magisk emulator acceptance with exact source/APK hashes.
- [x] Run the archive-icon checks and regression set on the ReSuKiSU phone with the same
  verified APKs; record current evidence for this consumer independently of #542.
- [ ] Design and validate accepted-work/recovery contracts before enabling cancellation of
  destructive restore commit phases or PackageInstaller work.

**Selected implementation, 2026-10-02:** `feat/odin-staging-adoption`, based on #541's merge
(`e449623c`). Implementation `9e696a88` merged through
[#542](https://github.com/trinadhthatakula/Thor/pull/542) as `369520f7`; validation evidence is recorded below. This increment covers the generated
fallback copies in `AppAnalyzerImpl` and `UriArchiveSourceFactory`. It does not complete the
broader archive/cache workload adoption.

| Candidate | Existing state and resources | Bounds and lifetime | Decision and remaining contract |
| --- | --- | --- | --- |
| Installer preview | Provider stream first; one unique private bundle survives until install or discard. Metadata and installation must use those same bytes. | Preserve the 4 GiB input budget. Generated reads use ARCHIVE / `input.preview`, with a root command deadline of at most nine minutes. | Selected. Bound privileged staging to budget plus one detection byte; enforce the final app-copy budget too. Promote root bytes only after acknowledged completion. |
| Archive import/open | Prefer retained FD access; provider stream and unique cache copy are fallbacks. The returned source owns its FD/ZIP/cache lifetime. | No new archive-size cap. Generated reads use ARCHIVE / `input.archive`, with a root command deadline of at most nine minutes. | Selected. Retain invalid-ZIP classification, independent per-open files, and cleanup before cancellation can discard ownership. |
| Tar creation and staged-file ownership | Backup already holds a package lease. Tar writes a package/class-named staging file, followed by ownership adjustment and encryption; both gateway and caller clean that file. | Current tar commands have no explicit deadline. Preserve sizing/free-space checks, mount visibility, and nonempty exit-1 warning semantics. | Deferred. Hold the source snapshot lease and output through acknowledgement, move uncertain output beyond ordinary cleanup, and prevent the uncompressed retry from replaying uncertain execution. The export helper's exit-0-only promotion contract cannot be applied unchanged. |
| Restore extraction | Restore holds a package lease, but extraction resets shared `<class-root>/.thorbak-staging` and reads a decrypted tar owned by its caller. | Current extraction has no explicit command deadline; preserve member validation and same-volume staging. | Deferred. Retain the tar until termination is acknowledged and block reuse/deletion of shared staging after uncertain execution, including across restart. |
| Final swap, chown, restorecon, and cache deletion | These mutate live app data, ownership/labels, or cache trees. Their affected user/package scope can exceed an individual temporary file. | Existing command/provider behavior remains. New deadlines need workload-specific partial-effect handling and output limits. | Deferred. Design durable uncertainty barriers and accepted-work recovery before opting in; cancellation cannot imply rollback. PackageInstaller/Binder work requires its own contract. |
| Archive-icon fallback (`ArchiveIconLoader`) | Unique temporary archive copy; installed-icon/cache shortcuts. Coil cancellation owns only that fetch's scratch. | Preserve the 256 MiB staging limit, direct-readable-file path, and nonlocal `.thorbak` refusal. Use ARCHIVE / `input.archive-icon` with a 30-second root command deadline. | Selected in this increment. Reuse acknowledged staging; cache only confirmed misses as `.none-v2`. Publish complete PNGs by same-directory rename under a short cache-write lock; independent reads retain separate ownership. |

`SystemRepository.copyFileForRead` resolves the gateway once, preserving preferred independent
providers even when root is available. Its root branch uses `RootExportStaging` under
`noBackupFilesDir/root_read_staging/<UUID>/`, with precreated app-owned payloads, durable pending
receipts, and isolated execution. Validation and promotion require recorded `EXITED`, exit 0,
started work, and confirmed termination/output drain. A missing or uncertain outcome retains
the private workspace; another read gets a new identity without replay. Reclamation requires
acknowledged cleanup or a different known boot, and excludes active workspaces. Receipts contain
identity/lifecycle metadata, not input paths, commands, output, or raw errors.

Non-root providers retain their unique `/data/local/tmp` route and existing synchronous process
behavior, with cleanup bound to the same selected gateway. Their temporary cleanup remains best
effort, and the existing shared-location exposure remains. Root acknowledgement, deadlines, and
child-termination guarantees do not apply to this branch. App-copy cancellation checkpoints and
byte checks also do not cancel a ContentResolver read or accepted Binder work.

The direct provider/FD paths remain available without privileged admission. Preview still stages
the URI only once, and archive-source ownership now covers the descriptor-null fallback as well
as the FD path. The new 24 host tests cover provider/read-once behavior, byte equality,
invalid ZIPs, per-open ownership, overlapping archive copies, bounds, deadlines, observer order,
uncertain retention/no replay, and cancellation including the descriptor-null handoff.

**Selected-input acceptance, 2026-10-02:** JDK 21.0.12; the full command
`./gradlew test lintFossDebug lintStoreRelease :app:assembleFossDebug :app:assembleFossDebugAndroidTest --no-parallel --max-workers=2`
passed. Each FOSS/Store unit-test variant passed 3,318 tests with zero failures/errors/skips.
Lint reported zero errors/warnings and no `MissingTranslation` or `SyntheticAccessor` findings
(14 FOSS and 13 Store hints). Dependency insight resolved the external published
`com.trinadhthatakula:odin:1.1.0` AAR without a project substitution; no local Odin override was used.

| Environment | Selected input checks | Regression checks | Result |
| --- | --- | --- | --- |
| `Odin_Magisk_API36_1`, Magisk 30.7, API 36.1, ARM64, 16 KiB pages, user 0 | Protected APK preview and archive open; real FD close; separate preview/archive partial-read cancellations | Two root-export checks and five explicit-policy/admission checks | 12/12 passed, zero skips; installed APK hashes verified; Home launch passed |
| POCO F7 `25053PC47G`, ReSuKiSU v4.2.0-rc2 (35159), API 36, ARM64, 4 KiB pages, user 0 | The same five production entry-point checks | The same seven regression checks | Final unchanged-APK rerun 12/12 passed, zero skips; installed APK hashes verified; Home launch passed |

The preview fixture used the installed 2,059,851-byte test APK and checked real PackageManager
metadata plus SHA-256 equality. Archive checks preserved exact ZIP bytes and entries and released
their actual FD/cache owners. Each cancellation waited for a real 1,024-byte FIFO prefix before
cancelling. A held outcome observer proved that the durable CANCELLED receipt, private payload,
and input inode/open writer survived acknowledgement handling; a concurrent sweep retained active
work. After release, no output was published, the receipt/payload disappeared, cancellation
propagated, and the same ARCHIVE shell accepted exactly one subsequent command without replay.
INTERACTIVE remained usable during each blocked read. In the final runs, preview/archive
cancellation-to-acknowledgement observations were 93/56 ms on the emulator and 303/306 ms on the
phone; these are observations, not timing assertions or performance guarantees.

Both final runs began and ended with no private staging/test fixtures. Thor debug and test APKs
remain installed. Only the phone's Thor provider preference was temporarily changed from Shizuku
to Root and then restored to Shizuku, verified in the saved preference; no `adb root`, shell-root
grant, or root-manager policy change was used.

**Validation history and limits:** the initial phone run stopped all five new tests before fixture
creation because Shizuku was selected; the root regressions still passed. With Root selected, a
preview fixture's unnecessary root `chmod` hit `ShellLaneBusy` before the read. Setup now uses
`Os.chmod` on its app-owned inode, retaining the direct-read denial assertion and live root copy.
After rebuilding, all five new tests passed on both devices. One existing
`explicitPolicyIsIndependentOfCommandClassAndLane` run on the phone reported
`RootAdmissionUnavailable` during its cleanup; a full rerun with identical APK hashes passed all
12 checks. Both logs are retained. This does not establish why freshness changed, nor prove the
existing regression test is free of intermittent startup/refresh contention.

- [ ] Investigate that existing policy test's intermittent admission/cleanup failure under
  concurrent startup/refresh; preserve a primary test failure if cleanup also fails.
- [ ] Validate live Shizuku copies, storage exhaustion, process-death/uncertain receipt recovery,
  and multiple Android users for these entry points. Host outcome injection is not device proof.
- [ ] Validate the deferred workloads independently before adopting them; the passing input-read
  tests do not establish cancellation safety for tar, extraction, final restore mutation, or caches.

Evidence: `~/.codex/artifacts/thor-odin-input-staging-2026-10-02/` — `build-gates-final.log`,
`host-validation.json`, `dependency-insight.log`, `tested-code-files.sha256`,
`emulator-final-validation.json`, `physical-acceptance-validation.json`, corresponding
instrumentation/fixture/launch logs, the earlier phone failure logs, environment records,
`physical-provider-restored.json`, and screenshots. The 1,019-input source manifest SHA-256 is
`35709bbad5bd0e039d3395d8e7fd1da3b191d60bbd01e3a37cd45ee3aef95a07`.
App APK SHA-256: `3e3431a532c14427d2a043c0fae338be3d45e76b272f1841a463daa164276d81`.
Test APK SHA-256: `4e6e7ce778472cd11ee16cbb493aec769d4097f1e4b2a2db72f5d1d82ba06d7c`.

### M2-04 archive-icon increment

**Implementation, 2026-10-02:** `67a980e6` on `feat/odin-archive-icon-staging` in
[#543](https://github.com/trinadhthatakula/Thor/pull/543), merged as `1c0424f9` after #542 (`369520f7`).
`ArchiveIconFetcher` now delegates generated fallback reads to
`SystemRepository.copyFileForRead`, preserving provider selection and the acknowledged private
root-staging contract above. The root command uses ARCHIVE / `input.archive-icon` and a
30-second deadline. Staging remains capped at 256 MiB even when provider length is unknown;
direct-readable files, provider streams, installed icons, existing PNG cache keys, and the
nonlocal `.thorbak` policy remain available.

The fetch owns cleanup around staging and parsing, propagates cancellation, and checks coroutine
activity before publishing cache results. An unacknowledged root writer remains owned by its
private receipt workspace, separate from the fetch's temporary input. Concurrent fetches use
unique input/APK/PNG temporary files. Only cache publication and pruning share a mutex; reads
and extraction run independently. A complete encoded PNG replaces its cache entry through a
same-directory rename, so readers cannot observe a partially encoded image and a cancelled peer
cannot delete a successful fetch's PNG.

Only a successfully inspected bundle with no top-level icon or APK candidate creates a `.none-v2`
miss marker. Old `.none` files are ignored because earlier code also used them for transient
failures. Admission/read/ZIP/nested-APK failures, undecodable or oversized icon entries,
cancellation, policy skips, failed standalone APK parsing, and a local `.thorbak` header
identifying an app not currently installed do not
create permanent misses. A later caller may retry the same key; no failed privileged attempt is
automatically replayed through another provider.

**Remaining bounds:** the 256 MiB limit applies to staged input, not directly readable files or
total expanded ZIP data. Nested APK extraction retains `BundleZip`'s existing 4 GiB expanded cap.
The root deadline does not cancel blocking ContentResolver/Binder reads or bound every local
parsing phase. The existing 32-bit URI-hash/size/mtime cache identity still omits display name
and package identity; strengthening it is separate work.

**Acceptance, 2026-10-02:** JDK 21.0.12; the full command
`./gradlew test lintFossDebug lintStoreRelease :app:assembleFossDebug :app:assembleFossDebugAndroidTest --no-parallel --max-workers=2`
passed. Both FOSS/Store unit-test variants passed 3,333 tests with zero failures/errors/skips,
including 15 new archive-icon tests. Lint reported zero errors/warnings and no
`MissingTranslation` or `SyntheticAccessor` findings (14 FOSS and 13 Store hints). Dependency
insight resolved the external published `com.trinadhthatakula:odin:1.1.0` AAR; no local override
or project substitution was used. The initial gate run caught four `UseKtx` issues in the new
tests; these were corrected before the complete passing run.

| Environment | Archive-icon checks | Regression checks | Result |
| --- | --- | --- | --- |
| `Odin_Magisk_API36_1`, Magisk 30.7, API 36.1, ARM64, 16 KiB pages, user 0 | Protected XAPK decode/pixels and cache reuse; confirmed miss; partial-root cancellation and same-key retry; overlapping fetch ownership | Five selected-input, two export-staging, five explicit-policy/admission checks | 16/16 passed, zero skips; installed APK hashes verified; Home launch passed |
| POCO F7 `25053PC47G`, ReSuKiSU v4.2.0-rc2 (35159), API 36, ARM64, 4 KiB pages, user 0 | Same four archive-icon checks | Same twelve regression checks | 16/16 passed on first run, zero skips; installed APK hashes verified; Home launch passed |

The cancellation test waited for a real 1,024-byte FIFO prefix. While its outcome observer held
acknowledgement handling, the durable receipt/private payload and input inode/open writer
remained owned, an active-workspace sweep retained them, and no PNG or miss was published.
After acknowledgement, the fetch propagated cancellation and removed its scratch; a fresh
same-key request succeeded. An independent INTERACTIVE command completed during the blocked
read. Cancellation-to-outcome acknowledgement was observed at 68 ms on the emulator and
327 ms on the phone; these are not timing assertions or guarantees. The overlapping test paused
two separately acknowledged copies, published one icon, and cancelled the peer without deleting
the completed PNG.

Both devices began and ended with no private staging/test fixtures; their debug/test APKs remain
installed. The phone's preferred provider was temporarily changed through Thor's Settings from
Shizuku to Root, then restored to Shizuku and verified in the saved preference. No root-manager
policy was changed. Host `run-as` observation of external app storage was denied on both devices;
these icon fixtures and staging assertions use private app storage. No `adb root` or shell-root
grant was used. Live Shizuku copies, storage exhaustion, process-death/uncertain recovery,
multi-user validation, and rapid list UI scrolling remain unverified for this consumer.

Evidence: `~/.codex/artifacts/thor-odin-archive-icons-2026-10-02/` — `build-gates.log`,
`build-gates-initial-lint.log`, `host-validation.json`, `dependency-insight.log`,
`tested-code-files.sha256`, `emulator-validation.json`, `physical-validation.json`, corresponding
instrumentation/fixture/hash/launch logs, both environment records, `emulator-home.png`,
`physical-root-home.png`, and `physical-provider-restored.json`/`.xml`/`.png`. The earlier
`physical-validation-pending.json` is retained as historical evidence; the phone reconnected and
passed the unchanged-APK suite on 2026-10-02. The 1,021-input source manifest SHA-256 is
`a4d862016e206ef774d6ea85a32f9f53575f56ba7a52e025d25aaace5d3bfc5f`.
App APK SHA-256: `6e7d3f904b0fbcb51d3145d4ca6aa87e809cbed279c9150b55181c1731495322`.
Test APK SHA-256: `b915a0db8a3d02c71c3557c5e3be9c62dc4d9feec32bd24291928dabd8e4c82b`.

## Milestone 3 — recovery and IPC improvements

### M3-01: Settings Editor reconciliation

- [x] Implement user-driven read-only checks for PENDING/UNKNOWN/UNCONFIRMED history, preserving
  the saved user/table/key and original/requested values, provider, timestamp, and outcome.
- [x] Persist the latest observed value, observing provider, and time as separate optional metadata;
  older history without this field remains readable.
- [x] Preserve the difference between current-state readback and proof of termination. Matching
  readback neither enables verified-only undo nor retires the root execution barrier.
- [x] Keep restoration as a separately reviewed mutation with fresh conflict checks; no automatic
  replay or falsely verified undo. Existing manual edits continue to provide this path.
- [x] Validate lost final journal save/reopening, failed reads/saves, cancellation, concurrent edits,
  provider/user/consent restrictions, stale restoration, and unresolved producer barriers on the host.
- [x] Record required host gates and live emulator Root/UI checks with exact source/APK evidence.
- [x] Run the same checks on the ReSuKiSU physical device and validate live Shizuku reconciliation.
- [x] Exercise actual app death after an acknowledged real write but before final history persistence,
  then reconcile the retained PENDING record after restart on the physical device and emulator.
- [x] Emulator: kill Thor during a real privileged writer, prove post-death writes, retain the
  exact receipt after same-boot completion, and recover through public admission after a real reboot.
- [x] Emulator: cancel an ignored-TERM writer and same-group child; require genuine acknowledgement
  before lane release, receipt retirement and later safe edits.
- [x] ReSuKiSU: acknowledged hostile-writer cancellation and exact-resource refusal through both
  ROOT and live SHIZUKU, followed by a safe edit and complete original-state cleanup.
- [ ] Physical-device live-death/reboot recovery; this increment reboots only the emulator.
- [ ] Validate control-shell denial/unacknowledged termination with a surviving observer; the
  lost-observer app-death scenario does not establish this behavior.

**Implementation, 2026-10-02:** `98793e74` on `feat/settings-editor-reconciliation` in
[#544](https://github.com/trinadhthatakula/Thor/pull/544), merged as `5efa1399`, based on #543's merge (`1c0424f9`).
History offers **Check current value** for uncertain records. The controller reads
the saved table/user through one currently allowed Root or Shizuku session and atomically adds a
`SettingsEditObservation`; it does not issue a settings write or invoke write admission. Both
before access and before publication it checks consent, and cancellation/read/save failures leave
the prior durable observation intact. Observations preserve absent, SQL null, empty, and literal
values separately. A repeated explicit check replaces only the latest observation.

The history card keeps the original result and provenance visible alongside the observation,
its provider/time, and a note that the earlier change is still unverified. Wrong-user records and
busy/unavailable/unconsented actions cannot request a check. The existing verified-only undo path
is unchanged. Even a matching observation of an UNCONFIRMED entry leaves the independent
`SettingsRootExecutionGate` intact, including across provider changes.

This increment updates [the recovery follow-up](settings-editor-recovery.md). It does not add an
automatic retry, a restore shortcut, or a mechanism to prove that an old producer terminated.

**Validation, 2026-10-02:**

- Required `./gradlew test lintFossDebug lintStoreRelease` gates and both FOSS debug APK builds
  passed with JDK 21.0.12 (`--no-parallel --max-workers=2`). All **3,350 tests per FOSS/Store
  variant** passed with zero failures or skips, including 14 new reconciliation cases and three
  new persistence cases. Both app lint reports have no errors or warnings and no
  `MissingTranslation`/`SyntheticAccessor` findings. Dependency insight resolves external
  `com.trinadhthatakula:odin:1.1.0`; neither a composite Odin build nor Maven Local is selected.
- Magisk emulator: **8/8 tests passed**, zero skips. `Odin_Magisk_API36_1`, Android 16/API 36.1,
  ARM64, 16 KiB pages, Magisk 30.7, Android user 0; Thor used its existing app root authorization.
  The run included `SettingsEditorReconciliationIntegrationTest` (3),
  `SettingsEditorIntegrationTest` (1), `SettingsEditorDeadlineTest` (1), and
  `SettingsEditorHistoryTest` (3), with `settingsEditorMode=ROOT`.
- Metrics confirmed six persisted read-only observations, one refused stale restoration, and
  retention of the synthetic unresolved barrier plus a consent refusal. Matching readback kept
  uncertain outcomes and disabled undo. UI checks covered stored-user/provider/consent/busy
  eligibility, observed provenance, shared GLOBAL scope, and absent/null/empty/literal rendering.
- Instrumentation restored the selected provider and consent, history, disposable keys, and its
  own synthetic receipt. Host comparison confirmed identical semantic hashes/counts for history
  and root-execution journals and no new disposable keys in SYSTEM/SECURE/GLOBAL. No raw history
  or settings-table values were saved in host artifacts. Installed APK hashes matched the built artifacts, and
  the debug Home activity launched successfully afterward.
- The ReSuKiSU phone disconnected before this increment's validation; final ADB inventory showed
  only the emulator. The physical follow-up below completes Root/Shizuku and post-acknowledgement
  app-death checks. Seeded uncertain records and synthetic receipts still do not establish hostile
  or unacknowledged producer termination.

Evidence: `~/.codex/artifacts/thor-settings-reconciliation-2026-10-02/` contains `build-gates.log`,
`host-validation.json`, `odin-dependency.log`, `emulator-environment.json`,
`emulator-root-settings-acceptance.log`, `emulator-root-validation.json`, and
`emulator-debug-home.png`. `tested-code-files.sha256` covers 1,024 source/build/resource inputs;
its SHA-256 is `026a818f94af19a6ff07f36921fb3edd86bb8620f7292945050f6fcc0bbeef9a`.

App APK SHA-256: `c820bba65cef34e344b961193f1f61fca2e87fd91a85c4c1d434428e1011c179`.
Test APK SHA-256: `3fe1470e5adf797fec02a1bc90ac942fabcfdd159d544d75a4cdd7992c89f5f7`.

### M3-01 physical-device and process-death follow-up

**Validation, 2026-10-02:** test harness `c1d87205` in
[#544](https://github.com/trinadhthatakula/Thor/pull/544); production code and the app APK are
unchanged from `98793e74`.

| Environment | Suite | Result |
| --- | --- | --- |
| POCO F7 / 25053PC47G, Android 16/API 36, ARM64, 4 KiB pages; ReSuKiSU v4.2.0-rc2 (35159), user 0 | Reconciliation (3), production round trip (1), deadline/cancellation (1), history UI (3); ROOT | **8/8 passed**, zero skips |
| Same physical device; Shizuku 13.7.0-thedjchi (1361) | Reconciliation (3), production round trip (1); SHIZUKU | **4/4 passed**, zero skips |
| Same physical device; ROOT | Acknowledged write, force-stop, restart, durable reconciliation | **1/1 recovery passed** plus one expected interrupted arm phase |
| Magisk 30.7 emulator above; ROOT | Updated eight-check suite and the same process-death scenario | **8/8 + 1/1 recovery passed**, plus one expected interrupted arm phase |

The first physical ROOT attempt passed five checks but the new three stopped before mutation:
one `ShellLaneBusy` and two `RootAdmissionUnavailable` refusals during startup/provider refresh.
Its before/after journals and fixture keys matched. Test setup now awaits idle lanes and current
root admission and uses a read-only readiness probe; only typed pre-dispatch refusals may repeat.
Edits and reconciliation outcomes are never retried by that helper. The failed run remains in the
original evidence directory; the final results above use the rebuilt test APK.

`SettingsEditorProcessDeathIntegrationTest` obtains the exact production session factory through
a test-only reflection seam and runs the real controller/store. It intercepts only the fixture's
final VERIFIED history save, after the real write/readback and root execution cleanup returned.
It confirms that the original PENDING row is durable and no receipt remains for the owned key,
then publishes value-free readiness. Private originals were persisted before any changes. The
host verifies the fixture UUID and exact PID/start ticks, force-stops Thor, confirms that process
is gone, then starts recovery without reinstalling or rebooting. Recovery checks the same kernel
boot and APK update identity, uses public `repository.reconcile`, and verifies durable observed
state with PENDING/original metadata retained and undo disabled. The interrupted arm is expected
instrumentation failure and is **not** counted as a successful test.

For reproduction, run only `armAcknowledgedWriteBeforeFinalJournal` with
`settingsEditorMode=ROOT`, `settingsProcessDeathPhase=arm`, and
`settingsProcessDeathId=<canonical UUID>`. Wait for and validate
`no_backup/settings_reconcile_death/<UUID>/ready.json`, then force-stop the debug package.
Run only `recoverAfterAcknowledgedWriteProcessDeath` with the same UUID and phase `recover`.
Keep Settings Editor closed and do not run concurrent history edits. `fixture.json` contains
private recovery originals and stays on-device; recovery owns cleanup. The artifact runner below
performs the identity, expected-interruption, recovery-result and cleanup checks.

Both devices restored their history, root-execution journals, provider preference, consent,
disposable keys and owned recovery directory. Host semantic journal hashes/counts and key sets
matched before/after; both recovery directories were removed. Debug Home launched afterward.
No root-manager policy or ADB-root setting was changed. Death during live/hostile producer
execution remained open at that checkpoint; the next section records its follow-up evidence.

Required host gates and both debug APK builds passed again: **3,350 tests per variant**, no
failures/skips, and no lint errors/warnings or MissingTranslation/SyntheticAccessor findings.
Independent review and whitespace checks passed.

Evidence: `~/.codex/artifacts/thor-settings-reconciliation-physical-2026-10-02/` contains
`host-validation.json`, `physical-environment.json`, the `physical-root`, `physical-shizuku`,
`emulator-root`, `physical-death-root`, and `emulator-death-root` logs/validation JSON, and
`validate-process-death.py`. The 1,025-input source manifest SHA-256 is
`4318a8f992ef0024f53b63c227846fa5524807836abd7946721b108ea1223e86`.

App APK SHA-256: `c820bba65cef34e344b961193f1f61fca2e87fd91a85c4c1d434428e1011c179`.
Test APK SHA-256: `311e19b67160f588516e96e2412b167d24a0bcff479acfe1ed3c99fafcc5f1ba`.

### M3-01 live privileged writer and reboot recovery

**Scope, 2026-10-02:** tested harness `3d175607c4903de3306b4f58e692ef7562f572c8` in
[#545](https://github.com/trinadhthatakula/Thor/pull/545), based on #544's merge (`5efa1399`).
This increment adds instrumentation fixtures; production code and the app APK are unchanged.

`SettingsEditorLiveWriterProbe` runs from the test APK as root and repeatedly PUTs/GETs one
validated UUID SYSTEM setting for user 0. It publishes readiness only after a verified write and
a same-process-group child has started. Both ignore TERM. Separate foreground Toybox watchdogs
and helper deadlines bound their lifetime; only the producer writes the setting. Marker files
contain identities/counters in an app-owned private directory. Recovery originals stay in its
sibling `fixture.json`, which the root helper never reads.

`SettingsEditorLiveProducerIntegrationTest` uses the production controller, journal, gateway and
root observer with this controlled test payload. The host verifies durable PENDING history,
the genuine pending receipt, and the exact app PID/start ticks before sending that PID SIGKILL.
Two heartbeat samples after confirmed app death must show more verified writes. The restarted
app samples again, reconciles through the public repository, checks exact-resource ROOT refusal
and unrelated-key usability, then requests the helper's final verified write. It checks all four
producer/child/watchdog identities through the ROOT gateway. The missing app acknowledgement
still leaves the exact receipt on that same boot, despite matching readback and process exit.
Only a real later emulator boot permits a public repository write to retire it normally.

`SettingsEditorLiveCancellationIntegrationTest` instead keeps the app alive and cancels the
actual isolated gateway job. It forwards Odin's genuine outcome to the settings gate and checks
that acknowledgement/persistence finish while the lane remains owned, before allowing later
work. It checks all four retired process identities, a subsequent verified edit, and restoration
of history, provider preference, nullable consent and fixture keys. When Shizuku is actually
available, it also checks that switching to it cannot bypass the real live receipt. Calling the
gateway directly is intentional: the production controller makes accepted writes NonCancellable.

For reproduction, install the same FOSS debug app/test APKs and retain their hashes. Keep
Settings Editor closed and require an initially empty root receipt journal. On an emulator only,
run `SettingsEditorLiveProducerIntegrationTest#armLiveWriter` with `settingsEditorMode=ROOT`,
`settingsLivePhase=arm` and `settingsLiveId=<canonical UUID>`. Validate
`no_backup/settings_live_writer/<UUID>/ready.json` and its `control/ready.json`, then kill only
the exact acknowledged app PID. Run `observeAfterAppDeath` with phase `observe`, and require its
successful result plus `reboot-ready.json` before rebooting the emulator. After a changed kernel
boot ID, run `recoverAfterReboot` with phase `recover`. An interrupted arm is expected failure,
not a passing JUnit test. A failed sequence retains its private fixture; after an actual reboot,
`cleanupAfterFailedRunReboot` with phase `cleanup` can restore it through normal admission.
That cleanup is never counted as a passed acceptance sequence. Do not reinstall between phases.

For either device, run only
`SettingsEditorLiveCancellationIntegrationTest#hostileWriterCancellationAcknowledgesBeforeCleanupAndReadmission`
with `settingsEditorMode=ROOT` and `settingsLiveCancellation=true`. No reboot is involved.
The value-free artifact runner performs APK/identity checks and retains semantic journal hashes;
it never exports recovery originals or changes root-manager policy.

**Validation, 2026-10-02:**

- `./gradlew test lintFossDebug lintStoreRelease :app:assembleFossDebug
  :app:assembleFossDebugAndroidTest --no-parallel --max-workers=2` passed with Zulu 21.0.12:
  **3,350 JVM tests per FOSS/Store variant**, zero failures/skips; no lint errors/warnings or
  MissingTranslation/SyntheticAccessor findings. External Odin 1.1.0 resolved without substitution.
- Magisk 30.7 / `Odin_Magisk_API36_1`, Android 16/API 36.1, ARM64, 16 KiB, user 0:
  **9/9 passed**, zero skips (the previous eight checks plus hostile-writer cancellation).
  The real cancellation receipt retired after acknowledged cleanup while the lane stayed held;
  all four captured producer/child/watchdog identities were retired before the next verified edit.
- The same emulator passed **1/1 observe + 1/1 recovery**, with one separately recorded expected
  interrupted arm. After confirmed app death, heartbeat writes increased from 1 to 2; the new
  app sampled further progression itself. Same-boot readback and helper completion preserved
  PENDING and the exact receipt. Kernel boot changed from `2d45cf9f-58c5-411d-ba2d-62f974ca78bf`
  to `b69f7bcd-19d1-4bc8-8d24-4ab2a3af2e9f`; only normal public write admission then retired it.
  Original semantic history/receipt hashes matched and the owned recovery directory was removed.
- POCO F7 / 25053PC47G, Android 16/API 36, ARM64, 4 KiB, ReSuKiSU v4.2.0-rc2 (35159),
  user 0: **9/9 passed**, zero skips. The cancellation fixture verified exact-resource refusal
  through both ROOT and the already available SHIZUKU provider, real acknowledgement before
  lane release, all four retired identities, and a subsequent verified edit. Host journal hashes
  and disposable key sets matched before/after, and debug Home launched successfully.
- The first physical attempt disconnected after three recorded reconciliation completions during
  the existing production round-trip test. It has no terminal result and is retained as an
  interrupted attempt. After reconnection, read-only checks found no Thor process/instrumentation,
  no live-writer fixture directories, and the original journal hashes and key sets restored.
  The successful nine-check rerun above used a fresh evidence label and the same verified APKs.
  No phone reboot or root-manager policy change was performed. Emulator debug Home also launched
  after reboot recovery.

Evidence: `~/.codex/artifacts/thor-settings-live-producer-2026-10-02/` contains
`host-validation.json`, `build-gates-3.log`, `odin-dependency.log`, `fixture-review.json`,
`emulator-root-validation.json`, `emulator-live-validation.json`, sanitized phase JSON,
`physical-root-validation.json`, `physical-reconnect-snapshot.json`,
`physical-root-reconnected-validation.json`, device environment metadata and both host runners.
The 1,028-input source manifest SHA-256 is
`5f578c3b657eec7af2efc92cd6b3654fd38543feb3a0094a40f55933d9c9818a`.

App APK SHA-256: `c820bba65cef34e344b961193f1f61fca2e87fd91a85c4c1d434428e1011c179`.
Test APK SHA-256: `17efa71f759527237bfac8140d63bc9ce2bdacbc4a3776a4262a5242a4b48570`.

Broader M2-04 tar/extraction/destructive restore/cache adoption remains deferred until each
workload has its own ownership, partial-mutation and recovery contracts. A cancelled settings
helper cannot establish those contracts. Cancellation-control denial, unconfirmed
termination with a surviving observer, deliberately detached descendants, and physical-device
live-death/reboot recovery remain separate acceptance cases. This fixture does not kill the
ordinary production `SettingsEditorBridge` while it is inside a Settings provider call.

### M3-02: Typed Binder results and compact suspension readback

- [x] Append a compact read-only AIDL result for explicit suspended/not-suspended/not-installed,
  refused, and unknown outcomes; retain all six existing transaction slots and owner Android users.
- [x] Return compact typed suspension-owner information for the requested Android user, with
  bounded read/output and explicit unknown state, instead of arbitrary full package dumps.
- [x] Bound the framework observation and dump producer to a five-second caller wait and one MiB
  of output; retain admission until a timed-out worker actually exits.
- [x] Validate package/user/version/owner bounds, require independent typed framework-state
  agreement, and stop further writes after uncertain readback or Binder dispatch.
- [x] Test truncation, oversized/malformed output, ambiguous dialog text, invalid requests,
  user identity, synthetic transport failure, and no replay; run the real app path on both devices.
- [x] Append tracked clear-data request/query results, preserving all seven earlier transaction slots.
- [x] Persist package/user ownership before dispatch and reconcile late completion without resubmission.
- [x] Retain live-process install leases after a successful session commit until its correlated
  terminal callback, including caller cancellation; keep external chooser handoff separate.
- [ ] Add durable installer-session recovery across app death and resolve indeterminate commit transport failures.
- [ ] Extend typed AIDL **mutation** results beyond clear-data for service-confirmed/refused/unknown outcomes. Map
  client-observed Binder death or `RemoteException` to explicit transport/uncertain execution
  states; a dead service cannot return its own terminal reply. Add operation identity only where
  accepted work requires tracking.
- [x] Bound clear-data observation across preparation, dispatch and callback while retaining unfinished work.
- [ ] Extend cooperative **mutation** IPC deadline boundaries beyond clear-data; do not present coroutine timeout or shell
  cancellation as cancellation of a Binder transaction.
- [x] Validate real delayed clear-data acknowledgement and read-only reconciliation without another wipe on both devices.
- [ ] Test real late mutation success, missing observer callback, and Binder death after dispatch.
- [ ] Extend live acceptance to an unauthorized Binder caller, secondary-user readback, older/OEM
  formats, and minified Binder loading. The current minified build/keep check is a host check.

#### M3-02 compact readback evidence (2026-10-03)

Tested implementation and harness:
[`f9e684e8`](https://github.com/trinadhthatakula/Thor/commit/f9e684e8d5ee965c083aa536d34002dcd5740ffe)
in [#546](https://github.com/trinadhthatakula/Thor/pull/546), based on merged #545
(`95e1bdb5f0772c92cc678a05a17ac5e36a71b3c6`). Subsequent documentation changes do not change
the tested app or instrumentation sources.

The new `getSuspensionStateForUser` transaction returns bounded metadata rather than a full dump.
The root daemon compares strict supported-format parsing with a typed `ApplicationInfo` observation
for the exact package/user. Missing sections, contradictory flags, timeout, overflow, old/null replies,
and malformed transport remain unknown. Owner identities retain both package and suspending user;
the existing mutation method cannot name a different owner user, so the gateway refuses that case
before writing. A failed removal stops further owner calls and shell fallback; only readback can
confirm the outcome. Legacy service verification uses the same strict state checks.

The five-second wait covers framework observation, process startup, full output drain, and exit.
Interrupting the waiting worker does not cancel a framework Binder call. A stalled worker retains
the service's single read admission until it actually finishes, so later requests return busy/unknown
instead of accumulating workers. The child uses argv execution and output is capped at one MiB;
the wire result permits at most 64 bounded owner identities.

Owner names still depend on supported dump framing, and the framework flags and dump are separate
observations rather than an atomic snapshot. Resource/null dialogs and Thor's fixed string dialogs
from its own or shell identity are supported; other custom/OEM string dialogs remain unknown.
The canonical framework check prevents unescaped manifest metadata from manufacturing a negative
suspension state. Typed mutation acknowledgements and recovery remain separate work.

- Required `test lintFossDebug lintStoreRelease` gates passed with **3,396 JVM tests per FOSS/Store
  variant**, zero failures/errors/skips. Lint had zero errors/warnings and no `MissingTranslation`
  or `SyntheticAccessor` findings; 14 FOSS and 13 Store hint-level suggestions remain.
- FOSS debug app/test APKs and the unsigned minified FOSS release built successfully. R8 mappings
  retain the root service, AIDL interface and new parcelables. Published Odin **1.1.0** resolved
  without a local Odin substitution. Device execution used the debug build.
- Magisk 30.7 API 36.1 emulator (`emulator-5554`, ARM64, 16 KiB): **7/7 passed** — four new
  readback/real multi-owner suspension checks, two clear-data regressions, one binding lifecycle check.
- ReSuKiSU API 36 phone (`1da5425f`, POCO F7, ARM64, 4 KiB): the same **7/7 passed**. The debug
  app also cold-launched successfully. No `adb root`, root-policy change, provider-preference change,
  or phone reboot was needed. Both disposable fixtures were confirmed unsuspended and removed.
- Generated Binder proxies preserved slots 1–6 and made only the appended read against a synthetic
  old service. This is protocol compatibility evidence, not a real mutation-death experiment.
- The initial host run failed only a new exception-identity assertion; coroutine stack-trace recovery
  copies cancellation exceptions. The test now checks propagated cancellation and no subsequent
  call, and the full gates passed on rerun. The phone initially refused fixture installation;
  after the user unlocked it, installation and all checks passed. Both initial attempts are retained.

App APK SHA-256: `ca20be5118eaf7111132b39cd692d59aa7ea85bca0fcc9f018f8e2782fc4fdbd`.
Test APK SHA-256: `47e3a6880a387dc9ec8eb0913c76d26013089339b3fe44aab0df56d5e9342503`.
Installed hashes matched both built artifacts on both devices. Evidence lives in
`~/.codex/artifacts/thor-typed-suspension-readback-2026-10-03/`, including final/initial gate logs,
test summaries, device logs, APKs, installed hashes, dependency and R8 checks, and source revision.

#### M3-02 tracked root clear-data contract

Root clear-data now invokes the typed daemon transaction once, replacing the shell-first/legacy
boolean chain. Provider preference is unchanged. The daemon still invokes the same four-argument
`IActivityManager.clearApplicationUserData` API with `keepState=false`; no alternative mutation is
tried after failure, cancellation, an old/null reply, or connection loss. This requires a daemon
supporting the appended protocol; an older daemon cannot be used as a destructive fallback.

| Observation | Meaning and ownership |
| --- | --- |
| Cleared | Dispatch returned accepted and the exact observer confirmed success; release ownership. |
| Failed | Dispatch returned accepted and the observer reported failure; release ownership, without claiming rollback or unchanged data. |
| Refused | A validated pre-dispatch refusal or returned dispatch rejection; no accepted clear remains tracked. |
| Unknown | Preparation/dispatch is unfinished, an observer is missing, dispatch threw, the reply is malformed, or transport was lost; retain ownership. |

The service bounds initial observation to 15 seconds, including reflection preparation, invocation,
and callback delivery. It admits one active clear, retaining admission until the dispatch worker has
returned and a valid callback arrived, or nonacceptance is established. Callback-before-return and
callback-then-throw are handled independently. Callback package and root/system sender UID must
match the strict protocol. Requests bind full caller UID, UUID, package and Android user; duplicate
retained identities return their record. History retains at most 256 records without eviction per
daemon lifetime; capacity refuses before dispatch. It never resets the daemon or replays a request
in order to free capacity. A replacement daemon's missing record remains unknown.

An app-private AtomicFile journal in `noBackupFilesDir` records the exact identity before dispatch.
Every admitted package lease checks it, including queued waiters and operations using a different
privilege provider. Unknown-target installs and global cache clearing hold a shared admission lock
through Thor's invocation, excluding a new clear's record creation. The session-completion follow-up
below extends live-process leases through the terminal result of a successfully submitted session.
Known external-installer targets also check their package lease before chooser launch; ownership
ends at that handoff. Durable installer-session recovery remains open. Independent operations
outside this Thor installation are outside this journal.

A cancelled admission may leave a PREPARED record. Exact-phase retirement prevents its old owner from
subsequently entering Binder. A direct terminal reply or local proof that submission never started
also permits retirement. After attempted dispatch without terminal evidence, recovery requires a
validated read-only terminal query or known, different recorded/current kernel boot identities.
Reboot establishes actor retirement, not successful clearing. Unknown boot identity prevents
boot-based retirement; terminal query recovery remains available. Corrupt metadata, an initialization
marker with missing state, lost daemon state, and remote exceptions remain blocked. Thor refuses
clearing, uninstalling, or restoring its own control-plane package through coordinated flows.
External erasure of Thor's private storage is outside this recovery
contract. While a prior record remains retained, a repeated clear gesture first recovers that operation
and cannot become another wipe;
a subsequent explicit gesture may create a new request after settlement. Global operations do not
query every pending package: targeted reconciliation must first retire PREPARED or late terminal records.

- [x] Run the new real typed clear/query and held-observer checks on the Magisk emulator.
- [x] Run the same checks on the ReSuKiSU physical device.
- [ ] Exercise actual app/service death during a clear and same-boot/different-boot recovery on devices.
- [ ] Extend beyond delayed observer delivery to missing observer and hostile dispatch/death cases.
- [ ] Add dedicated recovery visibility and resolve bounded history capacity UX.

#### M3-02 tracked clear-data evidence (2026-10-03)

Tested implementation and harness:
[`bc8647f5ced6c2be8f4f140fc23b8e24e0e9d728`](https://github.com/trinadhthatakula/Thor/commit/bc8647f5ced6c2be8f4f140fc23b8e24e0e9d728)
in [#547](https://github.com/trinadhthatakula/Thor/pull/547), based on merged #546
(`fdbfbbda2e3a0e5072829828474c8ae5592ab316`). The evidence update changes documentation only.
The 1,053-input source manifest has SHA-256
`4a552489cd80c4a486d858a745356e16dc7234a034c81c34d7d5b42112b6d205`.

- Required `test lintFossDebug lintStoreRelease` gates passed with **3,463 JVM tests per FOSS/Store
  Debug variant**, zero failures/errors/skips. Lint had zero errors/warnings and no
  `MissingTranslation` or `SyntheticAccessor` findings; 14 FOSS and 13 Store hints remain.
  The final build used JDK 21, one Gradle worker, and disabled parallel compilation after an
  earlier Kotlin compiler heap exhaustion; repository build settings were unchanged.
- FOSS debug app/test APKs and the unsigned minified FOSS release built successfully. R8 mappings
  retain `ThorRootService`, `IThorRootService`, `RootDataClearResult`, and `RootDataClearLedger`.
  Published Odin **1.1.0** resolved without a local Odin project substitution. Minified execution
  on a device remains pending.
- Magisk **30.7** emulator (`emulator-5554`, `Odin_Magisk_API36_1`, Android 16/API 36,
  ARM64, 16 KiB): **8/8 passed**, comprising three typed-clear checks, four suspension regressions,
  and one binding regression, with no skips.
- ReSuKiSU **v4.2.0-rc2 / 35159** phone (`1da5425f`, POCO F7 / `25053PC47G`, Android 16/API 36,
  ARM64, 4 KiB): the same **8/8 passed**, with no skips. The phone initially refused fixture
  installation while locked; after the user unlocked it, installation and the complete suite passed.
- Both debug apps cold-launched successfully, installed APK hashes matched the retained artifacts,
  and the disposable `com.valhalla.thor.audit.cleardata` fixture was removed from both devices.
  No `adb root`, root-policy change, provider-preference change, or phone reboot was needed.

The typed-clear checks invoke the real gateway/API, verify that a clear removes fixture data,
and confirm that read-only lookup and a duplicate retained request preserve newly written data.
The held-observer fixture waits for Android's real callback, then delays delivery to the ledger.
It verifies retained package ownership and late reconciliation without another wipe. This establishes
late acknowledgement after the real clear, not death or continued filesystem mutation during the hold.

The first emulator held-observer attempt used the wrong Binder interface token. After fixing the
fixture descriptor, a verified emulator reboot retired the uncertain attempt before rerunning;
unresolved journal metadata was not deleted or edited. Earlier host compile/fixture lookup issues
and eager Android-storage initialization failures were corrected before the successful final gates.
Initial attempts and the corrected final logs are retained separately.

App APK SHA-256: `4224b8fcbe904ff9b7e59ba4cd0b9e4c1b4591c76a49572a58093bad9e85df2b`.
Test APK SHA-256: `a185736bd73611562fbebc6d26bf43d0b642b6dcb63dc30f93d61da8c071ef6e`.
Evidence lives in `~/.codex/artifacts/thor-typed-root-data-clear-2026-10-03/`, including
`committed-source-gates.log`, `validation-summary.json`, `source-revision.json`,
`source-inputs.json`, `emulator-final-results.json`, `physical-final-results.json`, their raw logs,
device environment records, the reboot record, lint reports, and APKs. The `*-final-*` results
identify the committed artifacts; earlier unsuffixed attempts are not the final acceptance evidence.

Actual app/service death during an in-flight clear, missing-observer/hostile dispatch cases,
minified device execution, recovery/history-capacity UX, and asynchronous installer completion
ownership remain open. This slice does not close the broader M3-02 acceptance checklist.

#### M3-02 interrupted journal initialization follow-up (2026-10-03)

Tested fix and regression tests:
[`ab9bf1e35901a8691d5ef822d4cb524bbe731ce5`](https://github.com/trinadhthatakula/Thor/commit/ab9bf1e35901a8691d5ef822d4cb524bbe731ce5)
in [#547](https://github.com/trinadhthatakula/Thor/pull/547). With no initialization marker,
an existing directory can finish initialization only when committed state is absent or is a valid
version-1 journal with no records. An absent state is written before the marker. Nonempty state is
rejected before boot filtering, including an authoritative AtomicFile backup; invalid state or
markers and a surviving marker with missing state still fail closed.

All **21 focused journal tests passed**, including seven new regressions for interrupted initialization
and refusal boundaries. Required `test lintFossDebug lintStoreRelease` gates passed on JDK 21 with
one worker: **3,470 tests per FOSS/Store Debug variant**, zero failures/errors/skips, and zero lint
errors/warnings (14 FOSS / 13 Store hints). These are host checks; devices were not rerun for this
follow-up, and the earlier device/APK evidence remains tied to `bc8647f5` above. Evidence:
`~/.codex/artifacts/thor-pr547-journal-init-2026-10-03/`, including before/after focused results,
`final-gates.log`, `source-revision.json`, and `validation-summary.json`.

#### M3-02 CI cancellation watchdog follow-up (2026-10-03)

Tested revision (test-only fix):
[`8952fab2f324f28b59fffe26925278d5eaeb8200`](https://github.com/trinadhthatakula/Thor/commit/8952fab2f324f28b59fffe26925278d5eaeb8200)
in [#547](https://github.com/trinadhthatakula/Thor/pull/547).
[CI run 37070795009](https://github.com/trinadhthatakula/Thor/actions/runs/37070795009)
at `9badb63c` failed one of 3,470 tests: `RootDataClearGlobalAdmissionCancellationTest`.
Its virtual timeout could expire while the cancelled journal operation returned from real IO
threads. The join now uses a real-clock 10-second hang guard; cancellation must still finish
before global admission is released, without a journal record or Binder/shell dispatch.

The focused test passed. A temporary `NonCancellable` wrapper around journal admission made the
test fail at the guard while global admission remained held, confirming the cancellation-order check
still detects uncancellable admission. The production source was restored byte-for-byte before
the final checks. Required `test lintFossDebug lintStoreRelease` gates, debug assembly, and
AndroidTest Kotlin compilation passed on JDK 21 with one worker: **3,470 tests per FOSS/Store
Debug variant**, zero failures/errors/skips, and zero lint errors/warnings (14 FOSS / 13 Store
hints). No device rerun was needed for this test-only change; earlier device evidence retains
its original revision. Evidence: `~/.codex/artifacts/thor-pr547-ci-test-failure-2026-10-03/`,
including the failed CI log, focused/mutation results, `final-gates.log`,
`source-revision.json`, and `validation-summary.json`.

After [#548](https://github.com/trinadhthatakula/Thor/pull/548) merged, `dev` at
`a13e761ab5d7e2c2d49ca9de57dc999412ab0809` was merged into #547 as
[`f0ca834a830fccf3fd29019b20cd8ba4876aadfe`](https://github.com/trinadhthatakula/Thor/commit/f0ca834a830fccf3fd29019b20cd8ba4876aadfe).
The same complete host gates passed again with Gradle **9.8.0**, AGP **9.5.0-alpha08**, and
Robolectric **4.17**: **3,470 tests per variant**, zero failures/errors/skips, zero lint
errors/warnings (9 FOSS / 8 Store hints), and successful debug assembly and AndroidTest compilation.
The cancellation test and production code were unchanged by this merge. Results and source
provenance are retained in the evidence directory's `merged-dev/` subdirectory; devices were not rerun.

#### M3-02 installer session completion follow-up (2026-10-03)

Implementation and regression tests:
[`e7ddadf64c0388345dd8db779c1fc005f5bb4122`](https://github.com/trinadhthatakula/Thor/commit/e7ddadf64c0388345dd8db779c1fc005f5bb4122)
in [#547](https://github.com/trinadhthatakula/Thor/pull/547).
Normal, Shizuku-session, and Dhizuku-session installation now retain the enclosing global,
package, or borrowed restore lease after `commit()` returns until `InstallReceiver` supplies the
matching session ID and attempt token's terminal result. Registration precedes submission, and
the token also distinguishes the PendingIntent identity. Confirmation, streaming, unknown statuses,
and unrelated events cannot settle ownership. Terminal failure remains a submitted install and
cannot authorize fallback or OBB placement; even a changed package timestamp cannot override it.

Cancellation drains that session's completion before unwinding the lease. Correlated success also
reaches the restore caller's success observer before cancellation propagates; ordinary observer
failure is diagnostic and cannot trigger a second install. External mode still releases after
chooser handoff because Thor does not own the external installer's lifetime.

This closes the successfully submitted session's live-process ownership gap noted in the original
clear-data evidence. Missing or uncorrelated terminal callbacks retain the lease beyond cancellation
and archive timeouts. Durable recovery after app death, indeterminate `commit()` transport failures,
and real-device acceptance of this follow-up remain open; earlier device results keep their original
APK/source attribution.

Required `test lintFossDebug lintStoreRelease` gates passed on the revision above using JDK 21,
Gradle 9.8.0, AGP 9.5.0-alpha08, Robolectric 4.17, and one worker: **3,491 tests per FOSS/Store
Debug variant**, including 61 installer/session/archive tests, with zero failures/errors/skips.
Lint reported zero errors/warnings (9 FOSS / 8 Store hints). Debug assembly, AndroidTest Kotlin
compilation, and both minified release builds passed, with no nonempty R8 missing-rules files.
Evidence: `~/.codex/artifacts/thor-pr547-install-completion-2026-10-03/`, including
`source-revision.json`, `validation-summary.json`, `final-gates.log`, test/lint reports and APKs.
The initial KTX lint failure and its preceding successful tests are retained separately in
`before-ktx-fix/`. CI results are not included in this evidence.

### M3-03: Diagnostics and documentation

- [ ] Add structured lifecycle/refresh/bind diagnostics without raw commands, setting values,
  package inventories, or arbitrary dump output; retain disabled global Odin verbose logging.
- [ ] Resolve release diagnostic retention/export as a separate product decision before enabling
  it in shipped builds.
- [ ] Reconcile historical follow-ups and shell startup/cancellation comments as behavior ships.
- [ ] Record final workload coverage, supported device evidence, limitations, and remaining work.

## Validation and evidence

For each implementation PR, run the repository gates from [AGENTS.md](../../AGENTS.md):

```bash
./gradlew test lintFossDebug lintStoreRelease
```

Record no unhandled lint errors, `MissingTranslation` warnings, or `SyntheticAccessor` errors in
the gated modules. Use JDK 21 and verify the published Odin version without unintended composite
or Maven Local substitution when recording consumer acceptance.

Use a separate evidence row per package/environment as needed. Do not replace the historical
baseline row with results from a later commit.

| Package / scope | Commit / PR | Environment | Command / scenario | Result / evidence |
| --- | --- | --- | --- | --- |
| Audit baseline only | `e16285b1` | Host, FOSS debug | Six focused root-routing/privilege test classes; dependency insight | 93 passed; external Odin 1.1.0 resolved; no device run |
| M1-04 compatibility | `8c6e5774` / #536 | Host; ReSuKiSU API 36; Magisk API 36.1 | Required gates; normal clear and real-daemon fallback | 3,174 JVM tests per variant; lint passed; 2/2 device tests each; details above |
| M1-05 ownership | `7a8c9ac1` / #537 | Host; Magisk API 36.1; ReSuKiSU API 36 | Required gates; binding lifecycle; clear-data regression | 3,188 JVM tests per variant; lint passed; 3/3 tests on each device |
| M1-06 isolation | `eff6de65` / #538 | Host; Magisk API 36.1 users 0/10; ReSuKiSU API 36 user 0 | Required gates; replacement/death/rebind; cross-user held work; binding/clear regressions | 3,188 JVM tests per variant; lint passed; 12 emulator and 6 physical passes; expected interruptions separate |
| M2-01 execution policy | `ad1fd5ee` / #539 | Host; Magisk API 36.1 user 0; ReSuKiSU API 36 user 0 | Required gates; explicit policy; cancellation/lease ordering on all lanes; boot identity; Settings watchdog and edit round trips | 3,223 JVM tests per variant; lint passed; 7 emulator and 7 physical passes; full evidence above |
| M3-01 read-only reconciliation | `98793e74` / #544 | Host; Magisk API 36.1 user 0 | Required gates; persisted observations; stale restoration; retained barrier; cancellation and history UI | 3,350 JVM tests per variant; lint passed; 8/8 initial emulator passes; follow-up below completes physical and post-acknowledgement death checks |
| M3-01 physical and process-death follow-up | `c1d87205` / #544 | Host; ReSuKiSU API 36; Magisk API 36.1 | Required gates; ROOT/SHIZUKU; actual app death after acknowledged write | 3,350 JVM tests per variant; lint passed; physical 8 ROOT + 4 SHIZUKU + 1 recovery; emulator 8 ROOT + 1 recovery; live-producer death pending |
| M3-01 live-writer follow-up | [`3d175607`](https://github.com/trinadhthatakula/Thor/commit/3d175607c4903de3306b4f58e692ef7562f572c8) / [#545](https://github.com/trinadhthatakula/Thor/pull/545); base `5efa1399` (#544) | Host; Magisk API 36.1; ReSuKiSU API 36 | Required gates; real live writer, same-boot barrier, actual reboot recovery, hostile cancellation | 3,350 JVM tests per variant; lint passed; emulator 9 checks + 2 phases; physical 9 checks with ROOT/SHIZUKU refusal; interrupted attempts separate |
| M3-02 compact readback | [`f9e684e8`](https://github.com/trinadhthatakula/Thor/commit/f9e684e8d5ee965c083aa536d34002dcd5740ffe) / [#546](https://github.com/trinadhthatakula/Thor/pull/546); base `95e1bdb5` (#545) | Host; Magisk API 36.1; ReSuKiSU API 36 | Required gates; compact protocol; state/owner validation; real multi-owner suspension; clear/bind regressions | 3,396 JVM tests per variant; lint and minified build passed; 7/7 emulator and 7/7 physical checks; typed mutation acceptance remains open |
| M3-02 tracked clear-data | [`bc8647f5ced6c2be8f4f140fc23b8e24e0e9d728`](https://github.com/trinadhthatakula/Thor/commit/bc8647f5ced6c2be8f4f140fc23b8e24e0e9d728) / [#547](https://github.com/trinadhthatakula/Thor/pull/547); base `fdbfbbda2e3a0e5072829828474c8ae5592ab316` (#546) | Host; Magisk 30.7 API 36; ReSuKiSU v4.2.0-rc2 API 36 | Required gates; typed clear/query; real held observer and package barrier; no replay; suspension/binding regressions | 3,463 JVM tests per FOSS/Store Debug variant; lint and minified build passed; 8/8 emulator and 8/8 physical checks; live mutation-death and wider IPC acceptance remain open |
| M3-02 journal initialization | [`ab9bf1e3`](https://github.com/trinadhthatakula/Thor/commit/ab9bf1e35901a8691d5ef822d4cb524bbe731ce5) / [#547](https://github.com/trinadhthatakula/Thor/pull/547) | Host, JDK 21 | Interrupted empty initialization; atomic backups; nonempty/invalid state refusal; required gates | 21 focused journal tests and 3,470 JVM tests per FOSS/Store Debug variant passed; lint passed; no device rerun |
| M3-02 CI cancellation watchdog | [`8952fab2`](https://github.com/trinadhthatakula/Thor/commit/8952fab2f324f28b59fffe26925278d5eaeb8200) / [#547](https://github.com/trinadhthatakula/Thor/pull/547) | Host, JDK 21 | Real-clock cancellation guard; negative admission mutation; required gates; debug assembly and AndroidTest compile | Focused test and 3,470 JVM tests per FOSS/Store Debug variant passed; unsafe mutation rejected; lint passed; no device rerun |
| M3-02 dev build integration | [`f0ca834a`](https://github.com/trinadhthatakula/Thor/commit/f0ca834a830fccf3fd29019b20cd8ba4876aadfe) / [#547](https://github.com/trinadhthatakula/Thor/pull/547); merged dev `a13e761a` (#548) | Host, JDK 21; Gradle 9.8.0; AGP 9.5.0-alpha08; Robolectric 4.17 | Required gates; debug assembly and AndroidTest compile after merging dev | 3,470 JVM tests per FOSS/Store Debug variant passed; lint passed; no device rerun |
| M3-02 installer session completion | [`e7ddadf6`](https://github.com/trinadhthatakula/Thor/commit/e7ddadf64c0388345dd8db779c1fc005f5bb4122) / [#547](https://github.com/trinadhthatakula/Thor/pull/547) | Host, JDK 21; Gradle 9.8.0; Robolectric 4.17 | Correlated terminal callbacks; global/package/borrowed leases; cancellation; OBB refusal; external handoff; required gates and release builds | 3,491 JVM tests per variant, including 61 relevant regressions; lint, debug/AndroidTest compile and both minified builds passed; device and durable-recovery acceptance remain open |
| Milestone 1 | — | — | — | Broader acceptance matrix pending |
| Milestone 2 | — | — | — | Pending |
| Milestone 3 | — | — | — | Pending |

Baseline classes: `RootCommandRouterTest`, `OwnedRootShellExecutorTest`,
`OdinRootShellSessionTest`, `RootSystemGatewayRoutingTest`,
`PrivilegeExecutionProductionPathTest`, and `PrivilegeResolverTest`.

### Device acceptance matrix

Record device/API, root manager/version, app authorization, tested SHA/build variant, exact
scenario, and result. These boxes concern the new implementation, not earlier Odin API tests.

- [ ] Magisk emulator: cold startup, delayed grant/denial, concurrent/cancelled refresh, busy to
  idle recovery, revoke/regrant through Thor, and correct screen/provider state.
- [ ] KernelSU connected test device: app-authorized root refresh, new-operation admission,
  accepted-work preservation, and relevant shell/RootService lifecycle checks. Restricted
  `adb root` is not evidence that Thor lacks root authorization.
- [ ] Owned shells: ARCHIVE/SWEEP mount visibility, degraded recovery, exact-generation ownership,
  and idle retirement after a changed confirmed observation.
- [ ] Isolated jobs: queued/running cancellation, ignored TERM, inherited pipes, control-shell
  denial, unconfirmed termination, cleanup/lease order, and subsequent safe work.
- [ ] RootService: delayed bind, replacement/death/rebind, APK update, minified loading,
  unrelated-UID rejection, and isolation between two Android users.
- [ ] Non-root provider compatibility: Shizuku/Dhizuku selection and affected operation behavior;
  persistent extension semantics and installation postconditions remain intact.
- [ ] Settings/archives: uncertainty survives relevant process death; reconciliation and cleanup
  never imply rollback or termination proof without evidence.

## Implementation boundaries

- Use existing gateways, Koin bindings, lanes, leases, and workflow journals. Introduce only the
  shared abstractions needed by a concrete package, rather than a new general scheduler.
- Arbitrary extensions may depend on persistent cwd, variables/functions, or background work.
  Isolation requires an explicit future opt-in contract for them.
- Odin isolated output is buffered and returned at completion. Preserve chunked binary file,
  ZIP, and encryption pipelines; `asFlow()` cancellation alone does not stop shell execution.
- Do not replace privileged suspension attribution with generic `pm suspend`, re-enter `su`
  inside an already-root service, broaden keep rules, or change existing AIDL transaction order.
- Root identity, permission for fresh root acquisition, Binder lifetime, and Android permission
  grants remain separate facts.

## Source entry points

Paths are relative to this document. Verify current behavior against the audit baseline when
starting work; filenames are entry points rather than a fixed exhaustive edit list.

- [Privilege manager](../../app/src/main/java/com/valhalla/thor/data/manager/PrivilegeManager.kt),
  [state](../../app/src/main/java/com/valhalla/thor/domain/model/PrivilegeState.kt),
  [gateway resolver](../../app/src/main/java/com/valhalla/thor/data/repository/ActiveGatewayResolver.kt),
  [capability cache](../../app/src/main/java/com/valhalla/thor/data/backup/DataArchiveCapabilityCache.kt).
- [App Details](../../app/src/main/java/com/valhalla/thor/presentation/appList/AppInfoDetailsViewModel.kt),
  [Settings](../../app/src/main/java/com/valhalla/thor/presentation/settings/SettingsViewModel.kt).
- [Root gateway](../../app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt),
  [main executor](../../app/src/main/java/com/valhalla/thor/data/gateway/root/MainShellCommandExecutor.kt),
  [owned executor](../../app/src/main/java/com/valhalla/thor/data/gateway/root/OwnedRootShellExecutor.kt),
  [Odin session](../../app/src/main/java/com/valhalla/thor/data/gateway/root/OdinRootShellSession.kt),
  [execution context](../../app/src/main/java/com/valhalla/thor/domain/model/PrivilegeExecution.kt).
- [Package coordinator](../../app/src/main/java/com/valhalla/thor/data/privilege/DefaultPackageOperationCoordinator.kt),
  [archive gateway](../../app/src/main/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImpl.kt),
  [OBB installer](../../app/src/main/java/com/valhalla/thor/data/repository/ObbInstaller.kt),
  [restore use case](../../app/src/main/java/com/valhalla/thor/domain/usecase/RestoreAppArchiveUseCase.kt).
- [Settings controller](../../app/src/main/java/com/valhalla/thor/data/settingseditor/SettingsEditorController.kt),
  [root service](../../app/src/main/java/com/valhalla/thor/rootservice/ThorRootService.kt),
  [AIDL](../../app/src/main/aidl/com/valhalla/thor/rootservice/IThorRootService.aidl).

Published adoption: [Thor PR #531](https://github.com/trinadhthatakula/Thor/pull/531),
[Odin PR #15](https://github.com/trinadhthatakula/Odin/pull/15). Odin lifecycle source inspected
for the audit: `1741da74bd0154d984eb780b2cbd1a02b0d3e1ac`.
