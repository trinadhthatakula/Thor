# Sett Edit recovery and execution bounds

Status: explicit recovery remains a follow-up. A bridge process deadline is now implemented in PR #530.

Implementation and acceptance progress are tracked in the approved
[Odin 1.1.0 plan](odin-1.1.0-implementation-plan.md), especially M2-01 and M3-01.

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
  The bridge launch runs under `/system/bin/toybox timeout --foreground -s KILL 30`, alongside
  Odin isolated execution adopted in PR #531. The getprop child has a separate five-second
  watchdog. Thor still needs complete handling of unconfirmed termination and output-drain
  outcomes; a terminal callback alone is not proof of safe cleanup.

## Proposed recovery flow

Offer explicit, user-driven reconciliation for PENDING/UNKNOWN records. Read the same table,
user and key through an allowed provider and durably record the observed state and time without
claiming the original write succeeded. Matching the requested value does not establish who wrote
it or whether all side effects completed. A restore must be a separately confirmed mutation with
fresh conflict checks and the existing before-image journal. Never replay the uncertain command.

## Remaining execution limits

The process watchdog supplies the current bound without destroying the shared shell or changing
provider routing. Missing/incompatible Toybox fails the command instead of running it unbounded.
A nonzero exit leaves dispatched mutation outcomes UNKNOWN. Kernel tasks that cannot respond to
SIGKILL or a broken shell transport still cannot be promised a strict wall-clock bound.
[Per-job cancellation in Odin](odin-per-job-cancellation.md) shipped in 1.1.0. The implementation
plan tracks remaining outcome propagation, recovery, and consumer validation.

## Acceptance for future implementation

- Inject process death and final-journal-save failure after a mutation; preserve the before-image
  and uncertainty across restart, without automatic replay or falsely verified undo.
- Verify reconciliation does not write settings, honors user/provider/consent restrictions and
  fails safely on read or journal failure. Verify conflicts before any confirmed restoration.
- Use a disposable hanging helper on emulator Root/Shizuku paths. Verify bounded completion,
  cleanup and subsequent lane availability; do not alter personal settings or unrelated jobs.
- Run repository gates, then authorized physical-device checks before claiming device behavior.
