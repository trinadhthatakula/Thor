# Odin per-job cancellation API

Status: implemented in Odin 1.1.0; initial Thor adoption merged in PR #531, 2026-09-30.

Track remaining Thor work in the approved
[implementation plan and progress checklist](odin-1.1.0-implementation-plan.md). The API design
requirements below are historical; complete outcome handling and broader workload adoption remain
pending in Thor.

Odin PR [#15](https://github.com/trinadhthatakula/Odin/pull/15) adds opt-in `prepareIsolatedJob` /
`submitIsolated` and JobHandle cancellation/termination acknowledgement. Settings Editor adopts it;
legacy persistent jobs keep wait-only coroutine cancellation. Same-process-group descendants are
covered; deliberately detached descendants remain outside the guarantee. Nested toybox watchdogs
must use `--foreground`. Thor retains the process deadline as defense in depth.

Maven Local evidence: full unit/lint gates, 10 lifecycle checks on physical API36 root and dedicated
Magisk SDK36.1 emulator, Settings Editor deadline tests on both, Magisk deny/grant refresh and
Settings Editor disposable-key round trip. Published artifact verification is recorded in the
adoption PR. Journal recovery remains a separate Thor follow-up.
Owner: the Thor maintainer also owns Odin. Follow the linked plan for remaining consumer work.

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
