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
| M1-04 | Data-clear fallback correction | None | Codex | [#533](https://github.com/trinadhthatakula/Thor/pull/533), [#536](https://github.com/trinadhthatakula/Thor/pull/536) | In review |
| M1-05 | RootService connection ownership | None | Unassigned | — | Not started |
| M1-06 | RootService profile isolation | M1-05 test fixture recommended | Unassigned | — | Not started |
| M2-01 | Execution policy and complete outcomes | Milestone 1 | Unassigned | — | Not started |
| M2-02 | Cancellable export staging copy | M2-01 | Unassigned | — | Not started |
| M2-03 | OBB context and cancellable placement | M2-02 | Unassigned | — | Not started |
| M2-04 | Selected archive/cache/import adoption | M2-02; workload-specific recovery | Unassigned | — | Not started |
| M3-01 | Settings Editor reconciliation | M2-01 | Unassigned | — | Not started |
| M3-02 | Typed Binder results and compact readback | Milestone 1; protocol design | Unassigned | — | Not started |
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

**Next task, updated 2026-10-01:** #535 has merged. The physical-firmware AIDL clear-data
compatibility fix is in [#536](https://github.com/trinadhthatakula/Thor/pull/536), with the affected
tests passing on ReSuKiSU and Magisk as recorded under M1-04. After that fix merges, continue
M1-05 connection ownership, then M1-06 profile isolation. Actual post-dispatch transport/deadline
fault injection remains an unchecked M1-04 follow-up.

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
`1.1.0`. The daemon now uses
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

- [ ] Track ownership of each binding attempt and immediately unbind the exact late connection
  on main when its waiter has timed out or been cancelled.
- [ ] Make cancellation/late-callback cleanup idempotent.
- [ ] Make old disconnect/null-binding callbacks leave a newer connection and binder intact.
- [ ] Add a small binding facade and deterministic tests for late connection, duplicate cleanup,
  timeout/cancellation, replacement, and old callbacks after replacement.
- [ ] Exercise delayed startup and bind/unbind/rebind with Odin on a rooted emulator/device.

### M1-06: RootService profile isolation

Start with the broad `pkill -f <applicationId>:root` in `RootSystemGateway`. It matches Odin
servers belonging to other Android users.

- [ ] Test removal of the blanket reset against Odin's existing APK-update/client-death lifecycle.
- [ ] Verify ordinary startup, APK replacement, app-process death, and rebind load the expected
  code without process-pattern killing.
- [ ] Bind harmless fixture operations in two Android users; initialize/rebind one and verify
  the other's binder and acknowledged work survive.
- [ ] If a stale-code case remains, document and fix that specific case using owned retirement.
  Add an append-only AIDL version/capability handshake only if it solves the reproduced case;
  otherwise record that it was unnecessary.

## Milestone 2 — acknowledged cancellation for selected workloads

### M2-01: Execution policy and complete outcomes

- [ ] Replace the `settings_editor.*` policy convention with an explicit persistent/isolated
  contract independent of lane and command class.
- [ ] Preserve all relevant Odin outcome fields in Thor, including cancellation cleanup outcomes.
- [ ] Define uncertainty recording/admission and staging-cleanup behavior before releasing
  ownership. Preserve original coroutine cancellation after recording the outcome.
- [ ] Apply the contract to existing Settings Editor isolation first; preserve its watchdog and
  mutation/readback/journal finalization on screen dismissal.
- [ ] Test the terminal-outcome matrix, pre-dispatch cancellation, drain/termination flags,
  unusable transport, unconfirmed termination, next admission, and no automatic replay.
- [ ] Test real acknowledgement ordering with readiness markers and a TERM-resistant child.

### M2-02: Cancellable export staging copy

- [ ] Opt one bounded-output `file.copy` staging path into isolated execution on ARCHIVE.
- [ ] Preserve package/work identity, mount-master namespace, unique destination, and deadlines.
- [ ] Require confirmed termination/drain before normal cleanup; retain uncertainty when proof
  is absent, rather than deleting or reusing an actively written destination.
- [ ] Verify cancellation responsiveness, cleanup order, subsequent safe shell reuse, and an
  unrelated lane's ability to work. Measure fresh-control-shell overhead and temporary storage.

### M2-03: OBB context and cancellable placement

- [ ] Carry `PrivilegeExecutionContext` through `AppArchiveInstaller.placeBundleObb`, its
  implementation, and both `ObbInstaller` placement paths.
- [ ] Route owned OBB work to ARCHIVE with explicit command classes and an appropriate deadline.
- [ ] Opt shell copies into the validated isolated contract; retain one-file-at-a-time extraction,
  size verification, package/work identity, and other providers' semantics.
- [ ] Test archive routing, interactive responsiveness, cancellation before staging deletion,
  partial-copy failure, and unchanged Shizuku placement behavior.

### M2-04: Selected archive/cache/import adoption

- [ ] Inventory each candidate's state needs, affected resources, output bounds, deadlines, and
  recovery behavior before opting it in.
- [ ] Expand to suitable tar/extract/chown/restorecon/cache jobs only after copy validation;
  preserve package leases and mount visibility.
- [ ] Add explicit policies to generated preview/import staging in `AppAnalyzerImpl` and
  `UriArchiveSourceFactory`; retain unique temporary paths and content-resolver handling.
- [ ] Validate each workload's child termination, cleanup, uncertainty, and concurrent operation
  behavior. Do not infer all workloads passed from one copy test.
- [ ] Keep destructive restore commit phases and PackageInstaller cancellation deferred until
  their accepted-work/recovery contracts are independently designed and validated.

## Milestone 3 — recovery and IPC improvements

### M3-01: Settings Editor reconciliation

- [ ] Implement user-driven read-only reconciliation for PENDING/UNKNOWN history, preserving
  user/table/key/provider, original/requested/observed value, and observation time.
- [ ] Preserve the difference between current-state reconciliation and proof of termination.
- [ ] Keep restoration as a separately reviewed mutation with fresh conflict checks; no automatic
  replay or falsely verified undo.
- [ ] Test process death, lost final journal save, read/journal failure, conflicting restore, and
  unresolved producer termination. Keep [the recovery follow-up](settings-editor-recovery.md)
  aligned with the implemented behavior.

### M3-02: Typed Binder results and compact suspension readback

- [ ] Design append-only typed AIDL results for service-confirmed/refused/unknown outcomes. Map
  client-observed Binder death or `RemoteException` to explicit transport/uncertain execution
  states; a dead service cannot return its own terminal reply. Add operation identity only where
  accepted work requires tracking.
- [ ] Define cooperative IPC deadline boundaries; do not present coroutine timeout or shell
  cancellation as cancellation of a Binder transaction.
- [ ] Return compact typed suspension-owner information for the requested Android user, with
  bounded read/output and explicit unknown state, instead of arbitrary full package dumps.
- [ ] Test late success, missing observer callback, binder death after dispatch, no replay,
  oversized/truncated/unparseable output, caller rejection, and user identity.

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
| Milestone 1 | — | — | — | Pending |
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
