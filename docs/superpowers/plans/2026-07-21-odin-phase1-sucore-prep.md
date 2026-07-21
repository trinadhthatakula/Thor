# Odin Phase 1 — Prepare :suCore in Thor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Thor's `:suCore` (Odin) module clean + correct ahead of extracting it to a standalone Maven Central library — fix the MainShell shell-init hang, move the Thor-specific `ThorRootService` into `:app`, and add the minimal self-containment prep.

**Architecture:** Three independent tasks, each a self-contained Thor change verified by building both flavors. Task 1 (shell-init failure channel + timeout) and Task 3 (coroutines dep + license) are `:suCore`-local; Task 2 moves app-domain code out of the library and adds a reusable caller-guard to Odin's `RootService` base.

**Tech Stack:** Kotlin, Android (AGP), coroutines, AIDL/Binder root-service IPC, `./gradlew` (JDK 21).

## Global Constraints

- Build tool: `./gradlew` (JDK 21). A context-mode hook intercepts `./gradlew …` run via the Bash tool — run Gradle through the `mcp__plugin_context-mode_context-mode__ctx_execute` MCP tool (`language: "shell"`); load it via `ToolSearch "select:mcp__plugin_context-mode_context-mode__ctx_execute"` if absent. Use Bash for git/file ops.
- Verify with `./gradlew assembleFossDebug assembleStoreDebug` (both must be green).
- `:suCore` minSdk stays **24**; do NOT apply the Kotlin plugin explicitly, do NOT remove the `foss_release` build type, do NOT add publishing config — those are **Phase 2** (deferred, per spec).
- Behavior must be preserved: root privilege probing and `ThorRootService`-backed ops (suspend / clear-data) work exactly as before; the only intended behavior change is that a hard shell-init failure resolves to `false` instead of hanging.
- `GetShellCallback.onShellDied` is added as a **default no-op** method (source- and binary-compatible).
- License target for Odin: **Apache-2.0**.
- On-device verification (root probing on rooted + non-rooted; `ThorRootService` ops after the move) is done by the maintainer, not in CI.

---

### Task 1: MainShell shell-init hard-failure fix (1a)

**Files:**
- Modify: `suCore/src/main/java/com/valhalla/superuser/Shell.kt` (the `GetShellCallback` interface, ~line 436)
- Modify: `suCore/src/main/java/com/valhalla/superuser/internal/MainShell.kt` (the `get(...)` catch, ~lines 51-76)
- Modify: `suCore/src/main/java/com/valhalla/superuser/ktx/ShellExtensions.kt` (`getShellAwait()`, lines 60-68)
- Modify: `suCore/src/main/java/com/valhalla/superuser/ktx/ShellRepository.kt` (`isRootGranted()`, lines 21-24)

**Interfaces:**
- Produces: `Shell.GetShellCallback.onShellDied(error: Throwable?)` (default no-op); unchanged `getShellAwait(): Shell` (now resumes exceptionally on failure); unchanged `ShellRepository.isRootGranted(): Boolean` (now bounded + returns false on failure/timeout).

- [ ] **Step 1: Add the `onShellDied` default method to `GetShellCallback`**

In `Shell.kt`, the interface currently is:
```kotlin
    interface GetShellCallback {
        fun onShell(shell: Shell)
    }
```
Replace with:
```kotlin
    interface GetShellCallback {
        fun onShell(shell: Shell)

        /**
         * Called when the shell could NOT be created (hard init failure). Default no-op keeps this
         * source- and binary-compatible; callers that await a shell (e.g. getShellAwait) should
         * override it to fail fast instead of suspending forever.
         */
        fun onShellDied(error: Throwable?) {}
    }
```

- [ ] **Step 2: Dispatch `onShellDied` from `MainShell.get(...)`'s catch**

In `MainShell.kt`, the worker `catch` currently ends with `Utils.ex(e)`. Replace the catch body:
```kotlin
                } catch (e: Exception) {
                    // Shell creation failed. Log it, keep the worker thread alive (catch-all), AND
                    // signal the terminal failure to the callback via the same executor path
                    // returnShell uses — so awaiting callers fail fast instead of suspending forever.
                    Utils.ex(e)
                    if (e is CancellationException) throw e
                    if (executor == null) callback.onShellDied(e)
                    else executor.execute { callback.onShellDied(e) }
                }
```
Add the import `import kotlinx.coroutines.CancellationException` at the top of `MainShell.kt` if not already present. (Rethrow guard: this runs inside `Shell.EXECUTOR.execute { }`, a plain Runnable, so `CancellationException` is not expected here, but the guard keeps parity with the codebase's AP-K03 discipline and is harmless.)

- [ ] **Step 3: Resume `getShellAwait()` exceptionally on failure**

In `ShellExtensions.kt`, `getShellAwait()` (lines 60-68) currently only overrides `onShell`. Replace with:
```kotlin
suspend fun getShellAwait(): Shell = suspendCancellableCoroutine { cont ->
    Shell.getShell(object : Shell.GetShellCallback {
        override fun onShell(shell: Shell) {
            if (cont.isActive) {
                cont.resume(shell)
            }
        }

        override fun onShellDied(error: Throwable?) {
            if (cont.isActive) {
                cont.resumeWithException(
                    error ?: NoShellException("Root shell initialization failed")
                )
            }
        }
    })
}
```
Add imports at the top of `ShellExtensions.kt`:
```kotlin
import com.valhalla.superuser.NoShellException
import kotlin.coroutines.resumeWithException
```

- [ ] **Step 4: Make `isRootGranted()` bounded + failure-safe**

In `ShellRepository.kt`, replace `isRootGranted()` (lines 21-24):
```kotlin
    // Lazy check for root status that doesn't block the UI thread
    override suspend fun isRootGranted(): Boolean = withContext(Dispatchers.IO) {
        // Ensure we have a shell first
        getShellAwait().isRoot
    }
```
with:
```kotlin
    // Lazy, bounded, failure-safe root check. A hard shell-init failure now resumes getShellAwait()
    // exceptionally (via onShellDied); the timeout additionally covers a worker that never returns.
    // Either way this UI-gating probe resolves to false instead of suspending indefinitely.
    override suspend fun isRootGranted(): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(SHELL_INIT_TIMEOUT_MS) {
            runCatching { getShellAwait().isRoot }.getOrDefault(false)
        } ?: false
    }
```
Add the import `import kotlinx.coroutines.withTimeoutOrNull` and a companion constant to `RealShellRepository`:
```kotlin
    companion object {
        // Upper bound for shell-init before the root probe gives up and reports "no root".
        private const val SHELL_INIT_TIMEOUT_MS = 10_000L
    }
```
(`runInternal` at lines 34-50 already wraps `getShellAwait()` in try/catch → `Result.failure`, so it inherits the failure channel automatically — no change needed there.)

- [ ] **Step 5: Verify compile (both flavors)**

Run (via ctx_execute shell): `./gradlew assembleFossDebug assembleStoreDebug`
Expected: BUILD SUCCESSFUL for both. No unresolved refs (`resumeWithException`, `NoShellException`, `withTimeoutOrNull`, `CancellationException`).

> No unit test is added: `getShellAwait()`/`isRootGranted()` bottom out in the static `Shell.getShell(...)` Android path with no injection seam, so a unit test would exercise mocks, not behavior. Correctness is guaranteed by the compile + the timeout bound + the maintainer's device check (below). Do not fabricate a mock-only test.

- [ ] **Step 6: Commit**

```bash
git add suCore/src/main/java/com/valhalla/superuser/Shell.kt \
        suCore/src/main/java/com/valhalla/superuser/internal/MainShell.kt \
        suCore/src/main/java/com/valhalla/superuser/ktx/ShellExtensions.kt \
        suCore/src/main/java/com/valhalla/superuser/ktx/ShellRepository.kt
git commit -m "fix(sucore): signal shell-init failure via onShellDied + bound isRootGranted so it can't hang"
```

**Device acceptance (maintainer):** on a rooted device the root probe still resolves true and root ops work; on a non-rooted device the probe resolves false within ~10s without wedging the UI.

---

### Task 2: Move `ThorRootService` into `:app`; add `RootService.enforceCaller()` to Odin (1b)

**Files:**
- Modify: `suCore/src/main/java/com/valhalla/superuser/ipc/RootService.kt` (add `protected fun enforceCaller()`)
- Create: `app/src/main/aidl/com/valhalla/thor/rootservice/IThorRootService.aidl`
- Create: `app/src/main/java/com/valhalla/thor/rootservice/ThorRootService.kt`
- Delete: `suCore/src/main/aidl/com/valhalla/superuser/ipc/IThorRootService.aidl`
- Delete: `suCore/src/main/java/com/valhalla/superuser/ipc/ThorRootService.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt` (imports at lines 11 + ~88)
- Modify: `app/src/main/AndroidManifest.xml` (line 63 `android:name`)

**Interfaces:**
- Consumes: Odin's `abstract class RootService : ContextWrapper` (generic base, stays in `com.valhalla.superuser.ipc`) and `IIPC.aidl`/`IRootServiceManager.aidl` (stay in suCore).
- Produces: `protected fun RootService.enforceCaller()` (throws `SecurityException` on an unauthorized caller UID) — reusable by any `RootService` subclass; and app-package `com.valhalla.thor.rootservice.{ThorRootService, IThorRootService}`.

- [ ] **Step 1: Add `enforceCaller()` to Odin's `RootService` base**

In `RootService.kt` (`abstract class RootService : ContextWrapper(null)`), add this protected method (it lives in the same module as the `internal` `RootServiceServer`, so it can read `authorizedUid`; subclasses in any module can call the `protected` method):
```kotlin
    /**
     * Enforce that the current Binder transaction comes from an authorized caller: root (0),
     * the system server (1000), or the UID that started this RootService. Call from inside your
     * AIDL stub methods. Throws [SecurityException] otherwise.
     */
    protected fun enforceCaller() {
        val callingUid = android.os.Binder.getCallingUid()
        val authorizedUid =
            com.valhalla.superuser.internal.RootServiceServer.getInstanceOrNull()?.authorizedUid ?: -1
        if (callingUid != 0 && callingUid != 1000 && callingUid != authorizedUid) {
            throw SecurityException("Access denied: UID $callingUid is not authorized.")
        }
    }
```

- [ ] **Step 2: Create the app-side AIDL**

Create `app/src/main/aidl/com/valhalla/thor/rootservice/IThorRootService.aidl`:
```aidl
package com.valhalla.thor.rootservice;

interface IThorRootService {
    void setAppSuspended(String packageName, boolean suspended);
    void clearAppData(String packageName);
}
```

- [ ] **Step 3: Create the app-side `ThorRootService`**

Create `app/src/main/java/com/valhalla/thor/rootservice/ThorRootService.kt` by moving `suCore/.../ipc/ThorRootService.kt` **verbatim** with exactly these changes:
1. Package line → `package com.valhalla.thor.rootservice`
2. Imports: keep `com.valhalla.superuser.ipc.RootService` and `com.valhalla.superuser.utils.Logger`; add `import com.valhalla.thor.BuildConfig`. (Drop the now-unnecessary explicit `IThorRootService` import — it's in the same package now.)
3. Replace the inline `enforceCaller()` local function inside the Stub (which reached into the internal `RootServiceServer`) with a call to the inherited base helper. The Stub's methods change from calling the local `enforceCaller()` to `this@ThorRootService.enforceCaller()`, and the local `private fun enforceCaller()` block is deleted. i.e.:
```kotlin
    override fun onBind(intent: Intent): IBinder {
        return object : IThorRootService.Stub() {
            override fun setAppSuspended(packageName: String, suspended: Boolean) {
                this@ThorRootService.enforceCaller()
                this@ThorRootService.setAppSuspended(packageName, suspended)
            }

            override fun clearAppData(packageName: String) {
                this@ThorRootService.enforceCaller()
                this@ThorRootService.clearAppData(packageName)
            }
        }
    }
```
4. In `setAppSuspended`, replace the hardcoded Thor package strings in the `callers` list. Current:
```kotlin
            val callers = listOf(
                this@ThorRootService.packageName,
                "com.valhalla.thor.debug",
                "com.valhalla.thor",
                "com.android.shell",
                "android"
            )
```
→
```kotlin
            val callers = listOf(
                this@ThorRootService.packageName,
                BuildConfig.APPLICATION_ID,
                "com.android.shell",
                "android"
            )
```
Everything else (`setAppSuspended`, `callSetSuspended`, `buildSuspendDialogInfo`, `clearAppData`) moves verbatim.

- [ ] **Step 4: Delete the suCore copies**

```bash
rm suCore/src/main/java/com/valhalla/superuser/ipc/ThorRootService.kt \
   suCore/src/main/aidl/com/valhalla/superuser/ipc/IThorRootService.aidl
```

- [ ] **Step 5: Update `RootSystemGateway.kt` imports**

Change the two Thor-service imports to the new app package; leave the generic `RootService` import alone. In `RootSystemGateway.kt`:
- Line 11 `import com.valhalla.superuser.ipc.IThorRootService` → `import com.valhalla.thor.rootservice.IThorRootService`
- Line 9 `import com.valhalla.superuser.ipc.RootService` — **unchanged** (generic base stays in Odin).
- Line ~88 `Intent(context, com.valhalla.superuser.ipc.ThorRootService::class.java)` → `Intent(context, com.valhalla.thor.rootservice.ThorRootService::class.java)`.

- [ ] **Step 6: Update the manifest service name**

In `app/src/main/AndroidManifest.xml`, the `<service>` at line 63:
```xml
            android:name="com.valhalla.superuser.ipc.ThorRootService"
```
→
```xml
            android:name="com.valhalla.thor.rootservice.ThorRootService"
```
(Keep `android:process=":root"`, `android:exported="false"`, `tools:ignore="Instantiatable"`.)

- [ ] **Step 7: Verify suCore is Thor-free + build both flavors**

Run (Bash): `grep -rn "com.valhalla.thor" suCore/src` → expected: **no matches**.
Run (ctx_execute shell): `./gradlew assembleFossDebug assembleStoreDebug` → BUILD SUCCESSFUL for both. If the generic framework (`RootServiceServer`/`RootServiceManager`/`RootServerMain`) fails to compile referencing `ThorRootService`, decouple that reference (it should not exist) and report.

- [ ] **Step 8: Commit**

```bash
git add suCore/src/main/java/com/valhalla/superuser/ipc/RootService.kt \
        app/src/main/aidl/com/valhalla/thor/rootservice/IThorRootService.aidl \
        app/src/main/java/com/valhalla/thor/rootservice/ThorRootService.kt \
        app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt \
        app/src/main/AndroidManifest.xml
git add -u suCore/src/main/java/com/valhalla/superuser/ipc/ThorRootService.kt \
          suCore/src/main/aidl/com/valhalla/superuser/ipc/IThorRootService.aidl
git commit -m "refactor(sucore): move Thor-specific ThorRootService into :app; add reusable RootService.enforceCaller()"
```

**Device acceptance (maintainer):** freezing/suspending and clear-data (which route through `ThorRootService`) still work with root; the OS still shows "Managed by Thor" on suspended apps.

---

### Task 3: coroutines dependency + Apache-2.0 license (1c)

**Files:**
- Modify: `gradle/libs.versions.toml` (add coroutines version + library)
- Modify: `suCore/build.gradle.kts` (add the dependency)
- Create: `suCore/LICENSE`
- Modify: `suCore/README.md` (license note)

**Interfaces:** none (build/config + docs).

- [ ] **Step 1: Add the coroutines catalog entries**

In `gradle/libs.versions.toml`, under `[versions]` add (near the `kotlin` line):
```toml
kotlinxCoroutines = "1.10.2"
```
and under `[libraries]` add:
```toml
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
```
(Verify `1.10.2` is the current stable compatible with Kotlin `2.4.10`; bump if a newer patch exists.)

- [ ] **Step 2: Declare the dependency in suCore**

In `suCore/build.gradle.kts`, the `dependencies { }` block currently has only `implementation(libs.androidx.core.ktx)`. Add the coroutines dep (the `ktx/` package uses `kotlinx.coroutines.*` but never declared it):
```kotlin
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 3: Add the Apache-2.0 LICENSE to suCore**

Create `suCore/LICENSE` containing the **standard Apache License 2.0 text** (the canonical text from https://www.apache.org/licenses/LICENSE-2.0.txt — the full "Apache License / Version 2.0, January 2004 / …" document, unmodified).

- [ ] **Step 4: Update the README license note**

In `suCore/README.md`, the license note currently states Odin (as part of Thor) is GPL-3.0. Update it to state that **Odin is licensed under Apache-2.0** (retaining the existing attribution to topjohnwu/libsu, itself Apache-2.0), e.g. replace the license paragraph with:
```markdown
## License

Odin is licensed under the **Apache License 2.0** (see `LICENSE`). It is a Kotlin-first reimagining
inspired by [topjohnwu/libsu](https://github.com/topjohnwu/libsu) (also Apache-2.0); credit to the
original authors.
```
(Match the exact heading/wording style already in the README; only the license statement changes from GPL-3.0 to Apache-2.0.)

- [ ] **Step 5: Verify build**

Run (ctx_execute shell): `./gradlew assembleFossDebug assembleStoreDebug` → BUILD SUCCESSFUL for both (coroutines now resolves via the explicit dep).

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml suCore/build.gradle.kts suCore/LICENSE suCore/README.md
git commit -m "chore(sucore): declare kotlinx-coroutines dep + relicense Odin module to Apache-2.0"
```

---

## Self-Review

- **Spec coverage:** Part 1 (MainShell fix) → Task 1; Part 2 (ThorRootService move) → Task 2 (incl. the `enforceCaller` helper needed because `RootServiceServer` is `internal`); Part 3 (coroutines dep + Apache LICENSE) → Task 3. Deferred items (explicit Kotlin plugin, `foss_release`, minSdk, publishing) are explicitly out of scope per Global Constraints. All covered.
- **Placeholder scan:** none — every code step shows exact code; the LICENSE is a named canonical artifact (Apache-2.0 text), not a placeholder; the coroutines version has a concrete value with a verify note.
- **Type consistency:** `onShellDied(error: Throwable?)` identical across Shell.kt (definition), MainShell.kt (call), ShellExtensions.kt (override). `enforceCaller()` defined in RootService (Task 2 Step 1), called in the moved ThorRootService (Step 3). `IThorRootService` / `ThorRootService` consistently repackaged to `com.valhalla.thor.rootservice` across the AIDL, class, gateway import, and manifest. `libs.kotlinx.coroutines.android` catalog alias matches the `kotlinx-coroutines-android` library key.
