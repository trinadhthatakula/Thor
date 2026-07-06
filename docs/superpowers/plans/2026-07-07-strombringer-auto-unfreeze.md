# Strombringer — Auto-Unfreeze Slice — Implementation Plan (Plan 1 of Spec A)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the whole Strombringer architecture end-to-end with its lowest-risk feature: tapping a **suspended** app's real launcher icon auto-unsuspends and opens it — via a headless LSPosed module that calls a new base-Thor `restore` provider.

**Architecture:** A new `com.valhalla.thor.ext.strombringer` extension APK is **dual-role** — Thor loads its `AutomationExtension` config class in-process (existing framework), and LSPosed loads its `IXposedHookLoadPackage` launcher hook into the launcher. The launcher hook, on a suspended-target launch, calls Thor's new **`restore` `ContentProvider`** (Thor holds root/Shizuku/Dhizuku privilege) which unsuspends via the #239 gateway. **Debug-tested** — `ExtensionManager.verifySignature()` already bypasses the cert check in `BuildConfig.DEBUG`, so no cert-allowlist/dedicated-key work is needed here (that's Spec B).

**Tech Stack:** Kotlin, Android (minSdk 28/29), LSPosed/Xposed API (`de.robv.android.xposed:api:82`, `compileOnly`), Jetpack Compose (host-provided), Koin, `thor-extension-api` (`compileOnly`).

## Global Constraints
- **Extension applicationId MUST start with `com.valhalla.thor.ext.`** — `ExtensionManager` (`ExtensionManager.kt:14`) discovers extensions by that prefix. Use `com.valhalla.thor.ext.strombringer`.
- **`compileOnly`** for `thor-extension-api`, `asgard`, AND the Xposed API — the host (Thor) provides api+asgard+compose at runtime; the Xposed framework provides `de.robv.*`. The extension APK must bundle none of them.
- **Headless:** no `<activity MAIN/LAUNCHER>` in Strombringer — it cannot be opened standalone.
- **Base-Thor changes stay store-safe:** the `restore` provider contains **no Xposed code**; it's a plain provider over the existing freezer gateway.
- **Two repos:** base-Thor changes land in `StudioProjects/Thor` (branch `feat/strombringer`); the module is a new project `StudioProjects/Strombringer` (later mirrored into `Thor-Extensions/verified/strombringer/` — Spec B).
- Reuse `restoreApp`/`forceUnfreeze` from `ManageAppUseCase` (#239) — never re-implement unsuspend.

---

## File Structure

**Base Thor (`app/`):**
| File | Responsibility | Change |
|---|---|---|
| `domain/model/RestoreRequest.kt` | pure: decide if a package may be restored (in-freezer gate) | Create |
| `test/.../domain/model/RestoreGateTest.kt` | unit tests for the gate | Create |
| `data/provider/FreezerBridgeProvider.kt` | `ContentProvider.call("restore", pkg)` → gateway, freezer-scoped | Create |
| `AndroidManifest.xml` | register the provider | Modify |

**Strombringer (new project `StudioProjects/Strombringer`, mirrors automation-extension):**
| File | Responsibility |
|---|---|
| `app/build.gradle.kts` | app module; `compileOnly` api/asgard/xposed |
| `settings.gradle.kts` | + `maven("https://api.xposed.info/")` |
| `app/src/main/assets/xposed_init` | `com.valhalla.thor.ext.strombringer.XposedEntry` |
| `app/src/main/AndroidManifest.xml` | `thor.extension.class` + xposed meta-data (no launcher activity) |
| `.../strombringer/StrombringerExtension.kt` | `AutomationExtension` — minimal ConfigurationScreen (auto-unfreeze toggle) |
| `.../strombringer/XposedEntry.kt` | `IXposedHookLoadPackage` — dispatch to the launcher hook + self-active flag |
| `.../strombringer/LaunchAppHook.kt` | hook launcher `startActivity*`; suspended target → call Thor provider |
| `.../strombringer/Config.kt` | read/write config via its own prefs (hook side = `XSharedPreferences`) |

---

## Task 1: Base-Thor — restore gate (pure, TDD)

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/RestoreRequest.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/RestoreGateTest.kt`

**Interfaces:**
- Produces: `fun mayRestore(packageName: String, freezerPackages: Set<String>): Boolean`

- [ ] **Step 1: Write the failing test**
```kotlin
package com.valhalla.thor.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreGateTest {
    @Test fun `allows a package that is in the freezer`() {
        assertTrue(mayRestore("com.foo", setOf("com.foo", "com.bar")))
    }
    @Test fun `blocks a package not in the freezer`() {
        assertFalse(mayRestore("com.evil", setOf("com.foo")))
    }
    @Test fun `blocks a blank package`() {
        assertFalse(mayRestore("", setOf("com.foo")))
    }
}
```
- [ ] **Step 2: Run — expect FAIL** `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.RestoreGateTest"` → unresolved `mayRestore`.
- [ ] **Step 3: Implement** — `RestoreRequest.kt`:
```kotlin
package com.valhalla.thor.domain.model

/**
 * The Strombringer launcher hook runs in the launcher's process, so the ContentProvider cannot
 * cryptographically prove the caller is our extension. We bound the blast radius instead: only
 * packages the user already put in the Freezer may be restored. GH#239 / Strombringer.
 */
fun mayRestore(packageName: String, freezerPackages: Set<String>): Boolean =
    packageName.isNotBlank() && packageName in freezerPackages
```
- [ ] **Step 4: Run — expect PASS** (3 tests).
- [ ] **Step 5: Commit** `git add app/src/main/java/com/valhalla/thor/domain/model/RestoreRequest.kt app/src/test/java/com/valhalla/thor/domain/model/RestoreGateTest.kt && git commit -m "feat(strombringer): freezer-scoped restore gate (pure)"`

## Task 2: Base-Thor — `FreezerBridgeProvider`

> **Hardened post-review (PR #242):** the shipped provider additionally (a) verifies the caller is the device's **current default launcher** (`resolveActivity(HOME, MATCH_DEFAULT_ONLY)` — not just any HOME-declaring app, which is spoofable) before any privileged work; (b) **clears the calling identity** (`Binder.clearCallingIdentity()`) so the restore runs under Thor's own identity, not the launcher's; and (c) wraps the restore in `runCatching` so a transient failure returns `ok=false` instead of throwing across Binder. A signature permission can't be used because the caller is the launcher process, not the extension, and extensions use a different signing key. See the actual `FreezerBridgeProvider.kt`; the snippet below is the pre-hardening baseline.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/provider/FreezerBridgeProvider.kt`
- Modify: `app/src/main/AndroidManifest.xml` (register provider, before `</application>`)

**Interfaces:**
- Consumes: `mayRestore` (Task 1); `FreezerRepository.getAllPackageNames()`, `ManageAppUseCase.forceUnfreeze(pkg)` (#239); Koin `KoinComponent`/`inject` (pattern: `ExtensionTriggerReceiver.kt:18-22`).
- Produces: provider authority `"${applicationId}.freezerbridge"`, method `"restore"`, arg key `"pkg"`, result bundle `{ "ok": Boolean }`.

- [ ] **Step 1: Implement the provider** — `FreezerBridgeProvider.kt`:
```kotlin
package com.valhalla.thor.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.valhalla.thor.domain.model.mayRestore
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Lets the Strombringer launcher hook ask Thor (which holds root/Shizuku/Dhizuku privilege) to
 * unsuspend an app on launch. Bounded to Freezer packages (see mayRestore) since the caller is the
 * launcher process and can't be cryptographically verified as our extension.
 */
class FreezerBridgeProvider : ContentProvider(), KoinComponent {
    private val freezerRepository: FreezerRepository by inject()
    private val manageAppUseCase: ManageAppUseCase by inject()

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        if (method != "restore") { result.putBoolean("ok", false); return result }
        val pkg = extras?.getString("pkg") ?: arg ?: run {
            result.putBoolean("ok", false); return result
        }
        val ok = runBlocking {
            val inFreezer = freezerRepository.getAllPackageNames().toSet()
            if (!mayRestore(pkg, inFreezer)) {
                Logger.d("FreezerBridge", "restore refused (not in freezer): $pkg from uid ${Binder.getCallingUid()}")
                false
            } else {
                manageAppUseCase.forceUnfreeze(pkg).isSuccess
            }
        }
        result.putBoolean("ok", ok)
        return result
    }

    // Unused CRUD surface.
    override fun query(u: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, sa: Array<out String>?): Int = 0
}
```
- [ ] **Step 2: Register in `AndroidManifest.xml`** — add before the closing `</application>` (near the other `<provider>` blocks, ~line 287):
```xml
        <!-- Strombringer bridge: lets the (LSPosed) launcher hook ask Thor to unsuspend a frozen
             app on launch. exported so the hook's process can reach it; access is bounded to
             Freezer packages inside the provider (the caller runs in the launcher, so it can't be
             signature-verified). -->
        <provider
            android:name=".data.provider.FreezerBridgeProvider"
            android:authorities="${applicationId}.freezerbridge"
            android:exported="true" />
```
- [ ] **Step 3: Verify compile** `./gradlew :app:compileFossDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** `git add app/src/main/java/com/valhalla/thor/data/provider/FreezerBridgeProvider.kt app/src/main/AndroidManifest.xml && git commit -m "feat(strombringer): FreezerBridge restore ContentProvider (freezer-scoped)"`

## Task 3: Scaffold the Strombringer project

**Files (new project `StudioProjects/Strombringer/`):** copy the automation-extension project structure (`verified/thor-automation-extension/`) as the base — same Gradle wrapper, `settings.gradle.kts`, `gradle/libs.versions.toml`, root `build.gradle.kts`. Then the deltas below.

- [ ] **Step 1: Copy the template**
```bash
cp -R /Users/trinadhthatakula/StudioProjects/Thor-Extensions/verified/thor-automation-extension \
      /Users/trinadhthatakula/StudioProjects/Strombringer
cd /Users/trinadhthatakula/StudioProjects/Strombringer
rm -rf .git .gradle build app/build app/src/main/java/com/valhalla/thor/ext/automation
git init -q
```
- [ ] **Step 2: `settings.gradle.kts` — add the Xposed maven repo** inside `dependencyResolutionManagement { repositories { … } }`:
```kotlin
        maven("https://api.xposed.info/")
```
- [ ] **Step 3: `gradle/libs.versions.toml` — add the Xposed API**
```toml
# [versions]
xposed = "82"
# [libraries]
xposed = { module = "de.robv.android.xposed:api", version.ref = "xposed" }
```
- [ ] **Step 4: `app/build.gradle.kts` — namespace + xposed dep.** Set `namespace`/`applicationId` to `com.valhalla.thor.ext.strombringer`, `versionCode = 1000`, `versionName = "1.00.0"`, and add to `dependencies`:
```kotlin
    // Xposed API — provided by the LSPosed framework at runtime; never bundled.
    compileOnly(libs.xposed)
```
- [ ] **Step 5: `app/src/main/assets/xposed_init`** (new file, one line, no trailing content):
```
com.valhalla.thor.ext.strombringer.XposedEntry
```
- [ ] **Step 6: `app/src/main/AndroidManifest.xml`** (headless, dual-role):
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:hasCode="true"
        android:label="Strombringer"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">

        <!-- Thor extension entry -->
        <meta-data android:name="thor.extension.class"
            android:value="com.valhalla.thor.ext.strombringer.StrombringerExtension" />
        <meta-data android:name="thor.extension.api.version" android:value="1" />

        <!-- LSPosed module descriptor -->
        <meta-data android:name="xposedmodule" android:value="true" />
        <meta-data android:name="xposeddescription"
            android:value="Auto-unfreeze suspended apps on launch (controlled from Thor)." />
        <meta-data android:name="xposedminversion" android:value="82" />
        <meta-data android:name="xposedscope" android:resource="@array/xposed_scope" />
    </application>
</manifest>
```
- [ ] **Step 7: `app/src/main/res/values/arrays.xml`** — the default launcher scope:
```xml
<resources>
    <string-array name="xposed_scope">
        <item>com.android.launcher</item>
        <item>com.android.launcher3</item>
        <item>com.miui.home</item>
    </string-array>
</resources>
```
- [ ] **Step 8: Commit** `git add -A && git commit -m "chore(strombringer): scaffold dual-role extension (Thor + LSPosed), no UI"`

## Task 4: Strombringer config bridge

**Files:** Create `.../strombringer/Config.kt`.

**Interfaces:**
- Produces: `Config.PREFS = "strombringer_prefs"`, `Config.KEY_AUTO_UNFREEZE = "auto_unfreeze"`; `Config.hostAuthority(ctx)` = Thor's bridge authority; a `ContentProvider` (`StrombringerConfigProvider`) the Thor-side UI writes.

- [ ] **Step 1: Implement `Config.kt`** — prefs constants + host-authority helper + a config provider the Thor UI calls, persisting to world-readable prefs the hook reads via `XSharedPreferences`:
```kotlin
package com.valhalla.thor.ext.strombringer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

object Config {
    const val PREFS = "strombringer_prefs"                       // XSharedPreferences file name
    const val KEY_AUTO_UNFREEZE = "auto_unfreeze"
    const val AUTHORITY = "com.valhalla.thor.ext.strombringer.config"
    // Thor's package differs by build (…​.debug); the hook resolves the installed Thor at call time.
    val THOR_PACKAGES = listOf("com.valhalla.thor", "com.valhalla.thor.debug")
}

/** Thor's ConfigurationScreen writes here (cross-process); the hooks read via XSharedPreferences. */
class StrombringerConfigProvider : ContentProvider() {
    override fun onCreate() = true
    @Suppress("DEPRECATION")
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context!!
        // MODE_WORLD_READABLE so an LSPosed XSharedPreferences in system/launcher can read it.
        val prefs = ctx.getSharedPreferences(Config.PREFS, Context.MODE_WORLD_READABLE)
        val out = Bundle()
        when (method) {
            "set" -> prefs.edit().putBoolean(Config.KEY_AUTO_UNFREEZE, extras?.getBoolean("value") == true).apply()
            "get" -> out.putBoolean("value", prefs.getBoolean(Config.KEY_AUTO_UNFREEZE, false))
        }
        return out
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
```
Register the provider in the manifest (`android:name=".StrombringerConfigProvider"`, `authorities="com.valhalla.thor.ext.strombringer.config"`, `exported="true"`).
> NOTE for the implementer: `MODE_WORLD_READABLE` throws on modern targetSdk unless you `StrictMode.VmPolicy` allow it or lower this module's `targetSdk`; the standard LSPosed pattern is to set this module's `targetSdk` ≤ 26 OR use LSPosed's remote-prefs. Confirm the approach on-device in Task 6 and pick whichever the device accepts; this is the one genuinely LSPosed-version-sensitive spot.
- [ ] **Step 2: Commit** `git add -A && git commit -m "feat(strombringer): world-readable config bridge for hooks"`

## Task 5: The launcher hook + Xposed entry

**Files:** Create `.../strombringer/XposedEntry.kt`, `.../strombringer/LaunchAppHook.kt`. Reference: Hail `XposedInterface.kt` + `LaunchAppHook.kt` (`StudioProjects/references/Hail-master/.../xposed/`).

**Interfaces:**
- Consumes: `Config` (Task 4); Thor's `FreezerBridgeProvider` authority `"${thorPkg}.freezerbridge"`, method `"restore"`, `pkg` extra (Task 2).

- [ ] **Step 1: `XposedEntry.kt`** — self-active flag + dispatch:
```kotlin
package com.valhalla.thor.ext.strombringer

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class XposedEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpp: LoadPackageParam) {
        // Self-hook: when Strombringer's own process is hooked, mark active (Thor reads this).
        if (lpp.packageName == "com.valhalla.thor.ext.strombringer") { Active.value = true; return }
        LaunchAppHook(lpp.classLoader).start()
    }
    object Active { @Volatile @JvmStatic var value = false }
}
```
- [ ] **Step 2: `LaunchAppHook.kt`** — hook launcher `startActivity*`; on a suspended target, ask Thor to restore (Hail-proven public-API hook). Read `auto_unfreeze` from `XSharedPreferences`.
```kotlin
package com.valhalla.thor.ext.strombringer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedHelpers

class LaunchAppHook(private val cl: ClassLoader) {
    private val prefs = XSharedPreferences("com.valhalla.thor.ext.strombringer", Config.PREFS)

    fun start() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                prefs.reload()
                if (!prefs.getBoolean(Config.KEY_AUTO_UNFREEZE, false)) return
                val intent = p.args.getOrNull(0) as? Intent ?: return
                val target = intent.`package` ?: intent.component?.packageName ?: return
                val ctx = p.thisObject as? Context ?: return
                if (target == ctx.packageName) return
                if (isSuspended(ctx, target)) requestRestore(ctx, target)
            }
        }
        XposedHelpers.findAndHookMethod(Activity::class.java.name, cl, "startActivityForResult",
            Intent::class.java, Int::class.javaPrimitiveType, Bundle::class.java, hook)
        val ctxWrapper = XposedHelpers.findClass(ContextWrapper::class.java.name, cl)
        XposedHelpers.findAndHookMethod(ctxWrapper, "startActivity", Intent::class.java, hook)
        XposedHelpers.findAndHookMethod(ctxWrapper, "startActivity", Intent::class.java, Bundle::class.java, hook)
    }

    private fun isSuspended(ctx: Context, pkg: String): Boolean = runCatching {
        val m = ctx.packageManager.javaClass.getMethod("isPackageSuspended", String::class.java)
        m.invoke(ctx.packageManager, pkg) as Boolean
    }.getOrDefault(false)

    private fun requestRestore(ctx: Context, pkg: String) {
        for (thor in Config.THOR_PACKAGES) {
            val ok = runCatching {
                val extras = Bundle().apply { putString("pkg", pkg) }
                val res = ctx.contentResolver.call("$thor.freezerbridge".toUri(), "restore", null, extras)
                res?.getBoolean("ok") == true
            }.getOrDefault(false)
            if (ok) {
                // Wait briefly for the unsuspend to land so the original launch proceeds. This runs
                // on the launcher's main thread by necessity — the launch must not proceed until the
                // target is active — but is bounded (~360ms worst case) and typically returns on the
                // first poll, since the privileged unfreeze is fast. If a slower gateway ever makes
                // this janky, move to cancelling + re-issuing the launch after an async restore.
                repeat(6) { if (!isSuspended(ctx, pkg)) return; Thread.sleep(60) }
                return
            }
        }
    }
    private fun String.toUri() = android.net.Uri.parse("content://$this")
}
```
- [ ] **Step 3: Build the module** `cd /Users/trinadhthatakula/StudioProjects/Strombringer && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. Confirm the APK bundles **no** `de.robv` / api / asgard classes (`unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -ciE 'xposed|extension/api|asgard'` → 0).
- [ ] **Step 4: Commit** `git add -A && git commit -m "feat(strombringer): launcher hook auto-unfreezes suspended apps via Thor bridge"`

## Task 6: Thor-side config UI (minimal)

**Files:** Create `.../strombringer/StrombringerExtension.kt` — `AutomationExtension` whose `ConfigurationScreen` renders in Thor and toggles `auto_unfreeze` through the config provider (Task 4). Reference the automation-extension `AutomationCluster.kt` for the Compose+Asgard style.

- [ ] **Step 1: Implement** a minimal `StrombringerExtension : AutomationExtension` — `name/description/version/author`, a no-op `onTrigger`, and a `ConfigurationScreen` with one `AsgardSettingToggleRow`("Auto-unfreeze on launch") whose state reads/writes via `LocalContext.current.contentResolver.call("content://com.valhalla.thor.ext.strombringer.config", "get"/"set", …)`. (Full code follows the automation-extension composable pattern; the load-bearing part is the two `contentResolver.call` sites.)
- [ ] **Step 2: Build** `./gradlew :app:assembleDebug` → SUCCESSFUL.
- [ ] **Step 3: Commit** `git add -A && git commit -m "feat(strombringer): Thor-rendered config screen (auto-unfreeze toggle)"`

## Task 7: End-to-end device verification (debug, LSPosed)

**Files:** none.

- [ ] **Step 1: Install both** — Thor debug (`feat/strombringer`, with Tasks 1-2) + Strombringer debug (`adb install -r`). Because both are debug-signed, `ExtensionManager.verifySignature` (DEBUG) loads Strombringer without any cert work.
- [ ] **Step 2: Activate** Strombringer in **LSPosed Manager**, scope = your launcher; reboot or restart the launcher.
- [ ] **Step 3: Configure** — open Thor → Extensions → Strombringer → toggle **Auto-unfreeze on launch** ON (verify `adb shell run-as com.valhalla.thor.ext.strombringer cat …/shared_prefs/strombringer_prefs.xml` shows `auto_unfreeze=true`, or the provider `get` returns true).
- [ ] **Step 4: Prove it** — with Thor's Freezer in **Suspend** mode, suspend a benign app; from the **home screen** tap its icon → it should **auto-unsuspend and open** (no "paused" dialog). Confirm `adb shell dumpsys package <pkg> | grep suspended` flips to `false`, and `logcat` shows the `FreezerBridge` "restore" call succeeding.
- [ ] **Step 5: Negative check** — an app **not** in the Freezer, if suspended by something else, is **not** restored (provider refuses; logcat "restore refused"). Confirm the bridge is freezer-scoped.

## Self-Review
- **Spec A coverage:** §3 dual-role → Tasks 3/5/6; §4.3 restore provider → Tasks 1/2; §5.1 manifest → Task 3; §5.2 config store + active flag → Tasks 4/5; §5.4 auto-unfreeze hook → Task 5; §6 config bridge → Tasks 4/6; §8 build-order "auto-unfreeze slice first" → this whole plan. Deferred to later plans (correctly): §4.1 cert allowlist + §4.2 foss INTERNET (Spec B), installer-spoof (Plan 3), CorePatch (Plan 4).
- **Placeholder scan:** the one soft spot is Task 6 Step 1 (Compose UI described, not fully coded) — the load-bearing `contentResolver.call` sites are shown; the surrounding composable is boilerplate mirrored from `AutomationCluster.kt`. Task 4 flags the single genuinely device-sensitive decision (`MODE_WORLD_READABLE` vs LSPosed remote-prefs) to resolve on-device rather than guess.
- **Type consistency:** `mayRestore`, `forceUnfreeze`, authority `${applicationId}.freezerbridge`, method `"restore"`, `pkg` extra, `Config.KEY_AUTO_UNFREEZE` — consistent across base-Thor and module tasks.

## Known risk
The `MODE_WORLD_READABLE`/`XSharedPreferences` handshake (Task 4) is the one LSPosed-version-sensitive piece; Task 7 validates it on the real device before we build on it. Everything else uses public APIs (Hail-proven launcher hook, a plain ContentProvider, the #239 gateway).
