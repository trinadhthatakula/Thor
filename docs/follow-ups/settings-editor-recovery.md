# Sett Edit recovery and execution bounds

Status: explicit read-only history checks merged in
[#544](https://github.com/trinadhthatakula/Thor/pull/544) (`5efa1399`).
Host gates, Root suites on both devices, Shizuku on the phone, and actual app death after an
acknowledged write passed. The live-writer follow-up now passes app-death, same-boot blocking
and actual reboot recovery on the emulator, plus acknowledged hostile-writer cancellation on
both devices with ROOT/SHIZUKU refusal on the phone.
The approved [Odin 1.1.0 plan](odin-1.1.0-implementation-plan.md), especially M2-01 and M3-01,
tracks acceptance and remaining work.

## Current behavior

- `SettingsEditorController` saves the original state as PENDING before dispatch. Normal screen
  dismissal does not cancel dispatch/readback/finalization because those run in NonCancellable.
- Process death or a failed final journal save can leave PENDING after a setting changed.
  Ordinary transport failure records UNKNOWN when final persistence succeeds. Unconfirmed root
  termination/output drain records UNCONFIRMED and retains an independent durable write barrier.
- Uncertain records remain ineligible for verified-only undo. They preserve the original and
  requested values, Android user, table, key, provider, and time.
- **Check current value** reads the saved table/user/key using one currently allowed Root or
  Shizuku session, then durably records the exact observed value, provider, and time. It requires
  consent, preserves original history/result fields, and never writes the setting or enters the
  write-admission gate. Old history files without observation metadata remain compatible.
- Matching readback does not verify the original write or show who changed the value. It cannot
  clear the root barrier or enable undo. The history UI displays that distinction. Repeating a
  check updates only the latest observation; failed reads/saves retain the last durable one.
- Users may still submit a separate reviewed edit from the values view. It checks fresh state,
  consent, and the unresolved-write barrier before dispatch and journals its own before-image.
  No uncertain command is replayed automatically.

## Execution and recovery boundaries

Sett Edit uses the Root INTERACTIVE lane with explicit Odin isolated execution. The executor
awaits termination and output-drain acknowledgement before releasing ordinary admission. The
bridge also runs under `/system/bin/toybox timeout --foreground -s KILL 30`; getprop has a
separate five-second watchdog. The complete outcome and durable uncertainty handling landed in
[#539](https://github.com/trinadhthatakula/Thor/pull/539).

Before a root write submits, `SettingsRootExecutionGate` saves a record for its exact user/table/key.
Only acknowledged cleanup or a later known kernel boot retires the record. Matching values,
provider switching, a new root shell, or app-process restart cannot establish that an old writer
stopped. The barrier also blocks Shizuku writes to that resource. Read-only reconciliation leaves
it unchanged. Odin 1.1.0 does not expose a recoverable process identity for finer automatic recovery.

Shizuku retains its process-wait timeout and bridge watchdog. Coroutine/process cancellation is
not proof that an accepted Binder transaction or `system_server` work stopped. Missing/incompatible
Toybox fails the command instead of running it unbounded. Kernel tasks that cannot respond to
SIGKILL and broken transport still prevent a strict wall-clock completion guarantee.

## Acceptance checklist

- [x] Host tests: lost final-journal save/reopening; exact absent/null/empty values; old JSON;
  read/save/cancellation failure; wrong users/providers/consent; concurrent journal updates.
- [x] Confirm current-state observations preserve uncertain outcomes and root barriers, and a
  separate stale restoration conflicts without writing or changing the prior record.
- [x] Magisk emulator: real Root reads using disposable keys; preserve preference, consent,
  history and synthetic barrier ownership. Exact build and provider evidence is in the Odin plan.
- [x] ReSuKiSU physical Root (8/8) and live Shizuku (4/4) using the rebuilt fixtures, with state
  restoration verified. The Odin plan retains the initial startup-refusal failure and final evidence.
- [x] UI checks: explicit action, stored-user eligibility, observed provenance, unverified result,
  and disabled undo after matching readback.
- [x] Actual app force-stop after an acknowledged production write but before final journal save,
  followed by public reconciliation of durable PENDING history: 1/1 recovery on each device.
- [x] Emulator: real root writer continues after app SIGKILL; public reconciliation preserves
  PENDING and the genuine receipt after same-boot completion; actual reboot permits safe recovery.
- [x] Emulator: ignored-TERM writer and same-group child cancellation acknowledges termination
  and drain while the lane remains owned, followed by a verified edit and original-state cleanup.
- [x] ReSuKiSU: acknowledged cancellation, real ROOT/SHIZUKU refusal, four retired process
  identities and state restoration; 9/9 checks passed after reconnection and a clean baseline check.
- [ ] Physical-device live-writer app-death/reboot recovery; only the emulator was rebooted.
- [ ] Cancellation-control denial or unconfirmed termination with a surviving observer. A lost
  observer after app death and successful acknowledged cancellation do not prove this case.
- [x] Full repository gates and validation evidence in the implementation plan before review.
