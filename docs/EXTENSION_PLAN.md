# Thor App Manager - Extension System Design & Plan

This document details the architecture and implementation plan for the Thor App Manager Extension System. The extension system allows modular, community-driven features (debloat lists, automation, backup providers, and installer filters) to be installed as separate APKs and loaded dynamically at runtime.

---

## 1. App-Side Architecture (Thor Core)

The core application provides the execution environment, security sandbox, and privilege gateways (Root, Shizuku, Dhizuku).

```mermaid
graph TD
    subgraph Core App Process (:app)
        Manager["ExtensionManager"]
        Registry["UadHelper / Backup / Installer"]
        CoreClassLoader["context.classLoader"]
    end

    subgraph Dynamic Loader
        PathCL["PathClassLoader"]
    end

    subgraph System Package Manager
        ExtAPK["Extension APK (com.valhalla.thor.ext.*)"]
    end

    Manager -- Queries PM --x ExtAPK
    Manager -- Verifies Signature --x ExtAPK
    Manager -- Instantiates --x PathCL
    PathCL -- Maps APK Path --x ExtAPK
    PathCL -- Inherits --x CoreClassLoader
    Registry -- Registers --x Manager
```

### Module Split
We introduce a `:extension-api` library module. Both the `:app` module and all extensions depend on this library.

1. **`:extension-api`**: Pure library containing interfaces (`ThorExtension`, `DebloatExtension`, etc.) and basic domain models.
2. **`:app`**: The main application. Implements `ExtensionManager` to find, verify, and load extensions using `PathClassLoader`.

### App-Side Key Components

#### `ExtensionManager.kt`
- **Discovery**: Queries the system package manager using `PackageManager.getInstalledApplications(PackageManager.GET_META_DATA)` to locate packages starting with `com.valhalla.thor.ext.`.
- **Security Check**: Compares the signing certificate of the extension APK with the core app. Only packages signed with the identical key are loaded (can be relaxed for debug/testing builds).
- **Class Loading**: Reads the `thor.extension.class` meta-data value from the extension's manifest, loads the class dynamically via `PathClassLoader`, and instantiates it.

---

## 2. Extension Builder Side (The Plugin Template)

To enable developers to easily write extensions, we publish a standalone Android project template called `thor-extension-template`.

### Project Structure
```
thor-extension-template/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/
│   └── libs.versions.toml
└── app/
    ├── build.gradle.kts
    └── src/
        └── main/
            ├── AndroidManifest.xml
            └── java/
                └── com/
                    └── valhalla/
                        └── thor/
                            └── ext/
                                └── myextension/
                                    └── MyExtension.kt
```

### Manifest Declaration (`AndroidManifest.xml`)
The extension APK requires no activities or launcher UI. It only declares the meta-data indicating the implementation class:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.valhalla.thor.ext.sample">
    
    <application android:hasCode="true">
        <!-- Tells Thor Core which class implements the contract -->
        <meta-data
            android:name="thor.extension.class"
            android:value="com.valhalla.thor.ext.sample.SampleDebloatExtension" />
        <meta-data
            android:name="thor.extension.api.version"
            android:value="1" />
    </application>
</manifest>
```

---

## 3. Supported Extension Types

We plan to support four core extension types, each defined by a specific interface contract in `:extension-api`.

### Type A: Debloat List Extension (`DebloatExtension`)
Allows community-maintained manufacturer-specific debloat packages (UAD recommendations) to be added dynamically.

```kotlin
package com.valhalla.thor.extension.api

interface DebloatExtension : ThorExtension {
    val targetManufacturer: String
    
    /**
     * Returns a list of packages and recommendations (Recommended, Advanced, Expert, Unsafe)
     * accompanied by descriptions.
     */
    fun getDebloatItems(): List<ExtensionDebloatItem>
}

data class ExtensionDebloatItem(
    val packageName: String,
    val recommendation: String, // "recommended", "advanced", "expert", "unsafe"
    val description: String
)
```

---

### Type B: Automation & Trigger Extension (`AutomationExtension`)
Allows custom background tasks or event handlers to execute when system states change.

```kotlin
package com.valhalla.thor.extension.api

import android.content.Context

interface AutomationExtension : ThorExtension {
    /**
     * Executes when a specific trigger event (e.g. Screen Off, Boot Complete, Time Interval) occurs.
     * The core app passes a ShellExecutor to allow running privileged operations (Root/Shizuku).
     */
    fun onTrigger(context: Context, eventType: String, shellExecutor: ShellExecutor)
}

interface ShellExecutor {
    fun execute(command: String): Pair<Int, String?>
}
```

---

### Type C: Backup & Sync Extension (`BackupExtension`)
Supports modular backup and restore targets without swelling the core app with networking or specific archive formats.

```kotlin
package com.valhalla.thor.extension.api

import java.io.File

interface BackupExtension : ThorExtension {
    /**
     * Backs up an app's files or APKs to a target location (e.g. Encrypted file, WebDAV, local storage).
     */
    fun performBackup(packageName: String, files: List<File>, targetDestination: String): Result<Unit>
    
    /**
     * Restores an app's data from a backup source.
     */
    fun performRestore(packageName: String, sourcePath: String, targetDirectory: File): Result<Unit>
}
```

---

### Type D: Installer Filter Extension (`InstallerExtension`)
Allows intercepting and modifying app installation flows, such as patching APK files (MIME or target version) or injecting custom install flags.

```kotlin
package com.valhalla.thor.extension.api

import java.io.File

interface InstallerExtension : ThorExtension {
    /**
     * Triggered immediately before sending the package to the installation engine.
     * Can alter installation parameters (e.g., adding downgrade flags) or modify/re-sign the APK.
     */
    fun onPreInstall(
        apkFile: File, 
        installFlags: MutableList<String>
    ): File // Returns either the original or a modified APK file
}
```
