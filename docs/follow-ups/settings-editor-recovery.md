# Sett Edit recovery and execution bounds

Status: follow-up proposals from PR #530 review; not implemented in this PR.

## Verified behavior

- `SettingsEditorController` saves the original state as PENDING before dispatch. Normal screen
  dismissal does not cancel dispatch/readback/finalization because those run in NonCancellable.
- Process death or a failed final journal save can leave PENDING after a setting changed.
  Transport failure records UNKNOWN when the final journal save succeeds. Both appear as
  unverified in the UI and are deliberately ineligible for verified-only undo.
- History retains the original and requested values. Users can refresh the relevant table,
  inspect its current value and submit a reviewed edit manually. No uncertain write is replayed.
- Shizuku has a five-minute process-wait timeout. Private timeout logs were redacted in
  `a1c6e4ef`. Stream cleanup and process destruction do not establish a bound for every Binder call.
- Sett Edit uses the Root INTERACTIVE lane. `RootFallbackCoordinator.executeInteractive` calls
  MainShell directly; `MainShellCommandExecutor` drains a submitted callback even on cancellation.
  No settings-helper execution deadline is enforced on this path. The shell initialization
  timeout does not bound an already running command. A hung helper can leave the editor busy and
  occupy the interactive Root lane.

## Proposed recovery flow

Offer explicit, user-driven reconciliation for PENDING/UNKNOWN records. Read the same table,
user and key through an allowed provider and durably record the observed state and time without
claiming the original write succeeded. Matching the requested value does not establish who wrote
it or whether all side effects completed. A restore must be a separately confirmed mutation with
fresh conflict checks and the existing before-image journal. Never replay the uncertain command.

## Proposed Root execution bound

Assess a dedicated, safely terminable helper/session with a command deadline. Merely wrapping the
current shared MainShell call in a coroutine timeout cannot guarantee callback drain or release.
Avoid destroying a shared shell used by unrelated commands. Preserve uncertainty after dispatch,
even when termination succeeds.

## Acceptance for future implementation

- Inject process death and final-journal-save failure after a mutation; preserve the before-image
  and uncertainty across restart, without automatic replay or falsely verified undo.
- Verify reconciliation does not write settings, honors user/provider/consent restrictions and
  fails safely on read or journal failure. Verify conflicts before any confirmed restoration.
- Use a disposable hanging helper on emulator Root/Shizuku paths. Verify bounded completion,
  cleanup and subsequent lane availability; do not alter personal settings or unrelated jobs.
- Run repository gates, then authorized physical-device checks before claiming device behavior.
