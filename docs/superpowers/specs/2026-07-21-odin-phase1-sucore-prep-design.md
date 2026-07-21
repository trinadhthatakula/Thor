# Odin Phase 1 — Prepare `:suCore` in Thor (design)

**Date:** 2026-07-21
**Branch:** `feat/odin-phase1-sucore-prep`
**Status:** Design — approved roadmap; this spec details Phase 1 only.

## Context

Roadmap (approved): publish the in-house libsu fork **Odin** (`:suCore`, namespace `com.valhalla.superuser`) to Maven Central as **`com.trinadhthatakula:odin`**, mirroring how Asgard + thor-extension-api are published (vanniktech maven-publish, standalone repo, CI on `production`, Thor consumes as a published dep). Three phases:

1. **Phase 1 (this spec):** prep `:suCore` *inside Thor* so it's clean + correct — done as Thor PR(s), device-verified.
2. **Phase 2:** extract to the standalone `Odin` repo + publish `1.0.0` (vanniktech + POM + CI + Apache-2.0 + the build-config self-containment).
3. **Phase 3:** migrate Thor to the published dep (version catalog + local `includeBuild` override; drop the local `:suCore`).

## Goal (Phase 1)

Two code changes + minimal safe prep, all validated against the live Thor app:

### Part 1 — MainShell shell-init hard-failure fix
**Problem** (`docs/follow-ups/mainshell-shell-init-hard-failure.md`): `getShellAwait()` → `ShellRepository.isRootGranted()` can suspend forever on a hard shell-init failure because `Shell.GetShellCallback.onShell(Shell)` only accepts a non-null `Shell` — no failure channel.

**Fix — failure channel + timeout (defense-in-depth):**
- **`Shell.GetShellCallback`** (`Shell.kt:436`): add a **default no-op** method `fun onShellDied(error: Throwable?) {}`. Default keeps it source- & binary-compatible; the 2 internal implementers need no change unless they opt in.
- **`internal/MainShell.get(...)`** (`MainShell.kt:51-76`): in the `catch`, dispatch the failure to the callback (via the same `executor` path `returnShell` uses) — `callback.onShellDied(e)` — instead of only `Utils.ex(e)`.
- **`ktx/getShellAwait()`** (`ShellExtensions.kt:60-67`): override `onShellDied` to resume exceptionally — `if (cont.isActive) cont.resumeWithException(e ?: NoShellException("shell init failed"))`. Additionally guard the whole suspend in a bounded `withTimeoutOrNull(...)` (or the caller does) so a *never-returns* worker (no throw, no callback) also resolves — the failure channel handles thrown failures, the timeout handles hangs.
- **`ktx/ShellRepository.isRootGranted()`** (`ShellRepository.kt:21-24`): resolve to **`false`** on failure/timeout instead of propagating/handing — wrap the `getShellAwait().isRoot` in `runCatching { ... }.getOrDefault(false)` and/or the timeout above so the privilege probe that gates the UI can never wedge. (`isRoot`-returning path stays: success → real value; failure/timeout → false.)
- The other `GetShellCallback` implementer (`internal/PendingJob.kt:54`) keeps the no-op default (its path is job execution, not the await/probe path) — no behavior change.

**Acceptance:** a simulated hard shell-init failure resolves `isRootGranted()` to `false` within a bounded time (no indefinite suspension); normal rooted / non-rooted probing is unchanged; the existing `onShell(Shell)` success path and worker-thread survival are preserved.

### Part 2 — Move `ThorRootService` into `:app` (make Odin generic)
**Boundary (confirmed):**
- **Stays in `:suCore` (generic framework):** `ipc/RootService.kt` (base), `aidl/.../ipc/IIPC.aidl` (`IBinder getService(String)`), `aidl/.../internal/IRootServiceManager.aidl`, and the internal machinery (`RootServiceManager`, `RootServiceServer`, `RootServerMain`, etc.).
- **Moves to `:app` (Thor-domain):** `ipc/ThorRootService.kt` (concrete suspend / data-clear service) + `aidl/.../ipc/IThorRootService.aidl`.

**Steps:**
1. Move `IThorRootService.aidl` into `app/src/main/aidl/…` under a Thor package (e.g. `com.valhalla.thor.rootservice`).
2. Move `ThorRootService.kt` into `:app` under the matching Thor package; it `extends` Odin's generic `RootService` (imported from `com.valhalla.superuser.ipc`).
3. Drop the hardcoded `"com.valhalla.thor"` / `".debug"` caller-package fallback strings — now that the service lives in `:app`, derive the package from `BuildConfig.APPLICATION_ID` / context.
4. Update `RootSystemGateway.kt` imports: `ThorRootService` + `IThorRootService` now resolve from the new `:app` package; `RootService` (generic bind/unbind) still imports from `com.valhalla.superuser.ipc`. Bind intent + `IThorRootService.Stub.asInterface(...)` unchanged in behavior.
5. Move the `<service>` registration for `ThorRootService` from the `:suCore` manifest into the `:app` manifest (if declared there).
6. Verify the generic framework (`RootServiceServer`/`Manager`/`RootServerMain`) has **no** compile reference to `ThorRootService`/`IThorRootService`; decouple any found.

**Acceptance:** Thor builds all variants; on-device root suspend / data-clear / uninstall (which go through `ThorRootService`) still work; `grep com.valhalla.thor suCore/` returns nothing.

### Part 3 — Minimal safe self-containment prep
- **Add the coroutines dependency** to `suCore/build.gradle.kts` — the `ktx/` package uses `kotlinx.coroutines.*` but declares no dep (currently resolves only via the ambient monorepo classpath). Add the catalog `kotlinx-coroutines-android` explicitly. (Additive; safe in Thor.)
- **Add an Apache-2.0 `LICENSE`** to `suCore/` and update `suCore/README.md`'s license note (Odin → Apache-2.0 going forward; retains libsu attribution).

**Deferred to Phase 2 (extraction) — with rationale:** applying the Kotlin plugin explicitly in-module (risks conflicting with Thor's AGP-9 built-in Kotlin plugin management), removing the empty `foss_release` build type (load-bearing for app-variant matching in the monorepo; the standalone repo won't have Thor's flavors), and the final `minSdk` decision (keep 24 for now — a library supporting API 24+ is fine inside a minSdk-28 app). None of these are needed for correctness inside Thor and are safer to do in the standalone repo where the module owns its own build.

## Out of scope
New Odin repo, vanniktech/POM/signing, CI `publish.yml`, publishing, and Thor's migration to the published coordinate — all Phase 2/3.

## Testing / verification
- `./gradlew assembleFossDebug assembleStoreDebug` — both green (Part 2 touches variant-relevant code).
- Unit test for the failure wiring where feasible (a `GetShellCallback` whose `onShellDied` fires → `getShellAwait()` resumes exceptionally / `isRootGranted()` returns false; a timeout test for the never-returns case). Where the static `Shell.getShell` path resists unit testing, rely on the timeout guarantee + device verification.
- On-device (maintainer): privilege probe resolves on rooted **and** non-rooted devices (never hangs); `ThorRootService`-backed root ops (suspend, clear data, uninstall) still work post-move; extensions/other root paths unaffected.

## Suggested PR split (for the plan)
- **1a:** MainShell fix (small, contained, `:suCore` only).
- **1b:** `ThorRootService` → `:app` move (the boundary refactor).
- **1c:** coroutines dep + Apache-2.0 LICENSE (trivial).
