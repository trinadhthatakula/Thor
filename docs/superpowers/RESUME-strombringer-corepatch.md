# RESUME — Strombringer CorePatch (global model, Thor Play-clean)

**Last updated:** 2026-07-08. **Status:** feature code COMPLETE + reviewed clean on both sides; installed on device; **device-verification of the hooks pending.**

## One-line state
Thor is now 100% CorePatch-free (Play-clean); the entire CorePatch capability lives in the Strombringer extension as a **global** master toggle with **auto-off** (default 20 min, user-controllable, reboot-reset). Both branches build; neither pushed.

## Branches & HEADs (neither pushed)
- **Thor:** repo `/Users/trinadhthatakula/StudioProjects/Thor`, branch `feat/strombringer-corepatch`, HEAD **`a6b6751`** (off `dev`). `assembleFossDebug` ✅, `compileStoreReleaseKotlin` ✅, versionCode 1908 → 1.90.8 (versionName now auto-calculated).
- **Strombringer:** repo `/Users/trinadhthatakula/StudioProjects/Strombringer`, branch `feat/corepatch-hooks`, HEAD **`92606dc`** (off `main`). `assembleDebug` ✅.

## What's DONE
- **Thor stripped of ALL CorePatch** (`a6b6751`): grep-clean of corepatch/xposed/package_verifier/armstate; 0 Xposed refs; generic installer + downgrade kept. Reviewer: zero over-removal.
- **Strombringer = self-contained GLOBAL CorePatch:**
  - Config (`4435985`): `getCorePatchState` bundle `{enabled, auto_off_enabled(def true), auto_off_minutes(def 20), enabled_at=SystemClock.elapsedRealtime}`; provider stamps `enabled_at` on enable; auto-off toggle + 1–120 min slider in the config screen. Backward-compat with the auto-unfreeze path preserved.
  - Hooks (`92606dc`): read the extension's OWN provider (`Config.AUTHORITY`), cached; pure `effectiveEnabled(enabled, autoOff, minutes, enabledAt, nowElapsed)` (elapsedRealtime ⇒ auto-off + reboot-reset for free; auto-off OFF ⇒ persists like raw CorePatch). Fine hooks (`CorePatchForQ..V`) gate on ONE global `isEffectivelyEnabled()` (both sig + digest clusters; caps 4/16 carve-out kept; fail-safe try/catch). `CorePatchEntryHook` gutted to a coarse per-install refresh. `XposedBridge.log` per bypass ("Strombringer log"). Arm-state/thread-token/authorizes/per-op-confirm/Thor-IPC fully retired.
- **CorePatch v4.9 source lifted** earlier (GPLv2 relicense `80207f7`): `CorePatchForQ..V` + XposedHelper + ReturnConstant, stripped to sig+digest only.
- **Size fix** (`6945eab`): minified release 63 MB → 271 KB (dropped unused material-icons-extended + R8).
- Both installed on device **`1da5425f`** (onyx, API 36): Thor `1.90.8-foss`, Strombringer `1.00.0`. Thor confirmed **Xposed-free on the compiled APK**.

## Device test setup (ready on 1da5425f)
- Test app `com.valhalla.corepatchtest`: keyA **installed**; keyB + keyC in `/sdcard/Download/`. Signer SHAs: keyA `9F55…`, keyB `6FED…`, keyC `02C9…`. Source: `/tmp/corepatch-testapp` (rebuild via aapt2/apksigner if /tmp cleared).
- CorePatch v4.9 reference: `/tmp/corepatch-ref` (tag 4.9) — may not survive reboot; re-clone `github.com/LSPosed/CorePatch` tag `4.9` if gone.

## PENDING — device verification (needs the user)
1. **Bootloop gate:** enable Strombringer in LSPosed (scope: System Framework `android` + launcher), reboot → must boot.
2. **Functional:** extension config → enable CorePatch (type "I understand the risk") → install `Download/testapp-keyB.apk` over keyA in Thor → should succeed (data marker preserved).
3. **THE hook-site unknown:** if step 2's bypass doesn't fire, the coarse hook site (`InstallPackageHelper#installPackagesTraced`, with `installStage`/`VerifyingSession` fallbacks) is wrong for A16 — pin it via LSPosed logs (`Strombringer[corepatch] bypassed …`).
4. **Auto-off** (20 min / reboot) disables; scope check; **R8 smoke test** (needs a signed release → Spec B).

## THEN (next planned work)
- **Spec B distribution** (`docs/superpowers/specs/2026-07-07-thor-extensions-distribution-design.md`): (1) dedicated "Thor Extensions" JKS + pin its cert SHA in Thor's allowlist; (2) `release` signingConfig on each verified extension + `release.yml` CI + `build-changed.sh`; (3) `catalog/extensions.json` + README/CONTRIBUTING; (4) in-app "Extensions → Browse" store (download + SHA-256 verify + install; foss needs INTERNET). Repo cleanup: `Thor-Extensions` structure (verified/ unverified/), fix stale `rootProject.name`.
- Final whole-branch review + merge of both branches (after device verification).

## Open Minors (tracked, non-blocking)
- `Strombringer/.../corepatch/CorePatchHook.java:77` stale comment ("token armed per-install" → "cached global state refreshed").
- Global blast radius while enabled (digest hooks affect all system_server digest calls; bounded by auto-off) — inherent to the master-flag model; device-sanity-check.
- Play-Protect-disable was dropped from this migration (can be re-added later as a hook, never as Thor code).

## How to resume
Read this file + the auto-memory `project-strombringer` + the SDD ledger `.superpowers/sdd/progress.md`. Everything is committed; trust `git log` on both branches over recollection.
