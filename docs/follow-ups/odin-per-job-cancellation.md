# Odin per-job cancellation API

Status: requested by the maintainer for Odin's next update on 2026-09-30.
Owner: the Thor maintainer also owns Odin. Implement in Odin, then adopt its published API in Thor.

## Motivation

Odin 1.0.0 exposes no supported API for terminating one submitted `Shell.Job`. Coroutine
cancellation stops awaiting or collecting while the command continues on the shared serial shell.
Thor currently drains submitted MainShell callbacks before releasing an interactive lease. A
stalled job therefore needs a process watchdog, as used for Sett Edit, to let the queue progress.

## API requirements to design in Odin

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

## Acceptance

Test cancellation before submission, while queued, during execution, after completion and repeated
cancellation. Cover command trees, ignored TERM, inherited pipes, shell death, callback races and
subsequent-job output isolation. Validate on rooted emulators and physical devices before adopting
the API in Thor. Keep the Sett Edit watchdog until the new API's termination guarantees are proven.
