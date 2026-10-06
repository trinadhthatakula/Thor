# Odin per-job cancellation API

Status: implemented in Odin 1.1.0; initial Thor adoption merged in PR #531, 2026-09-30.

Track remaining Thor work in the approved
[implementation plan and progress checklist](odin-1.1.0-implementation-plan.md). The API design
requirements below are historical. Complete outcome handling shipped in
[#539](https://github.com/trinadhthatakula/Thor/pull/539); selected workload adoption and Settings
reconciliation followed. Broader adoption and workload-specific acceptance remain open.

Odin PR [#15](https://github.com/trinadhthatakula/Odin/pull/15) adds opt-in `prepareIsolatedJob` /
`submitIsolated` and JobHandle cancellation/termination acknowledgement. Odin's legacy persistent
job coroutine APIs stop observation rather than terminating the producer. Same-process-group
descendants are covered; deliberately detached descendants remain outside the guarantee. Nested toybox watchdogs
must use `--foreground`. Thor retains the process deadline as defense in depth.

Initial Maven Local evidence: full unit/lint gates, 10 lifecycle checks on physical API36 root and
dedicated Magisk SDK36.1 emulator, Settings Editor deadline tests on both, Magisk deny/grant refresh and
Settings Editor disposable-key round trip. Published artifact verification is recorded in the
adoption PR. These results belong to that initial adoption, not every later consumer.
Owner: the Thor maintainer also owns Odin. Follow the linked plan for remaining consumer work.

## Current Thor adoption

Thor preserves the isolated outcome's started, termination-confirmed, output-drained, and
shell-reusable facts and keeps owned cleanup/leases until acknowledgement. Adopted consumers
include Settings Editor, export staging (#540), OBB placement (#541), selected privileged input
reads (#542), and archive-icon staging (#543). Settings history and read-only reconciliation
shipped through #544/#545; the tracker distinguishes acknowledged completion, live-writer
evidence, and remaining hostile termination/process-death acceptance.

Tar creation, extraction, destructive restore phases, and cache deletion remain deferred for
their own source/output ownership, partial-mutation, and durable uncertainty contracts. Odin's
shell acknowledgement does not establish Binder or PackageInstaller completion; M3-02 tracks
those operations separately. The linked plan is the source for tested revisions, device evidence,
and outstanding checks.

## Historical motivation (Odin 1.0.0)

Odin 1.0.0 exposes no supported API for terminating one submitted `Shell.Job`. Coroutine
cancellation stops awaiting or collecting while the command continues on the shared serial shell.
Thor currently drains submitted MainShell callbacks before releasing an interactive lease. A
stalled job therefore needs a process watchdog, as used for Sett Edit, to let the queue progress.

## Historical API requirements

- Provide an explicit cancellation handle for a submitted job, with completion that distinguishes
  queued cancellation, terminated execution, ordinary exit and transport failure.
- Make cancellation idempotent and define submission/completion race behavior. A cancelled queued
  job must never start later; cancellation after dispatch must not imply that side effects reverted.
- Terminate only the selected job and define how descendants are handled. Do not kill unrelated
  jobs or corrupt the shared shell's stdout/stderr framing.
- Keep the shell queue occupied until termination and output drain are complete, then make the
  next job usable. Expose a termination acknowledgement callers can await before releasing leases.
- Define bounded termination, escalation and transport recovery for children that ignore TERM,
  retain pipes or cannot be killed immediately. Do not report success before cleanup is confirmed.
- Preserve existing `submit`, `await`, `asFlow` and Java interoperability contracts; make the
  relationship between explicit job cancellation and coroutine cancellation clear.

## Original API acceptance scope

Test cancellation before submission, while queued, during execution, after completion and repeated
cancellation. Cover command trees, ignored TERM, inherited pipes, shell death, callback races and
subsequent-job output isolation. Validate on rooted emulators and physical devices before adopting
the API in Thor. Keep the Sett Edit watchdog until the new API's termination guarantees are proven.
