# Odin (`suCore`)

Odin (integrated as `:suCore`) is a lightweight, high-performance, and extremely stable persistent root-level process management and Binder IPC library. 

While its architectural foundations are inspired by [`libsu`](https://github.com/topjohnwu/libsu), Odin has been completely re-engineered as a modern, standalone Kotlin-first framework, freeing it entirely from third-party namespaces, package structures, and precompiled files.

---

## 🚀 Key Architectural Features

1. **Pure Kotlin Implementation**
   - The entire codebase has been converted from Java to Kotlin for modern, reactive, typesafe patterns.
   - Removed legacy static-context attachments to eliminate any potential resource or memory leaks.

2. **Zero Precompiled Binary Dependencies**
   - Completely deleted upstream binary wrappers such as `main.jar`.
   - Odin operates with zero precompiled class file assets, ensuring total transparency, compile-time auditability, and safety.

3. **Direct APK Classpath Bootstrapping**
   - Instead of extracting a loader JAR into the local cache directory, Odin dynamically mounts the app's main APK (`base.apk`) on `app_process`'s execution `CLASSPATH`.
   - The root process launches the launcher trampoline [RootServerMain.kt](src/main/java/com/valhalla/superuser/internal/RootServerMain.kt) directly from the APK, resulting in zero asset disk-writing or caching overhead.

4. **Persistent, High-Performance Binder IPC Daemon**
   - Declares the privileged process service ([ThorRootService.kt](src/main/java/com/valhalla/superuser/ipc/ThorRootService.kt)) under the restricted `:root` process.
   - Binds persistent, lifecycle-aware connections over a type-safe, warning-free AIDL interface ([IThorRootService.aidl](src/main/aidl/com/valhalla/superuser/ipc/IThorRootService.aidl)).
   - Executing system calls (like app suspensions and data clearing) is incredibly efficient, completing transactions in under **2 milliseconds** via native Binder transactions.

---

## 🛠️ Package and Namespace Structure

All files reside under our independent namespace: `com.valhalla.superuser`.

```
com.valhalla.superuser
├── internal/
│   ├── BinderHolder.kt       — Handles death recipient notifications.
│   ├── Constants.kt          — Houses bootstrap command line options and daemon settings.
│   ├── HiddenAPIs.kt         — Reflection bindings to bypass private platform restrictions.
│   ├── RootServerMain.kt     — Direct Kotlin APK bootstrapper trampoline.
│   ├── RootServiceManager.kt — Constructs app_process commands and launches root processes.
│   ├── RootServiceServer.kt  — Manages service registrations and Client bindings.
│   └── Utils.kt              — Context resolution, root state tracking, and helper utilities.
└── ipc/
    ├── RootService.kt        — Base binder-ready wrapper for root services.
    └── ThorRootService.kt    — Implementations for package data clears and app suspensions.
```

---

## ⚙️ ProGuard Configuration

Odin is fully obfuscation-safe. Standard ProGuard rules keep the reflection boundaries intact for execution within the target root container:
```proguard
# Keep all classes under com.valhalla.superuser for reflection-based trampoline compatibility
-keep class com.valhalla.superuser.** { *; }

# Keep all IPC interfaces and service classes
-keep class com.valhalla.superuser.ipc.** { *; }
-keep interface com.valhalla.superuser.ipc.** { *; }
```

---

## 📜 Credits and Original Attribution

Odin's design principles and IPC daemon architecture are adapted from the exceptional [`libsu`](https://github.com/topjohnwu/libsu) library by [topjohnwu](https://github.com/topjohnwu/).

### License Compatibility
- The original design from `libsu` is licensed under the Apache License 2.0. All modifications and structural enhancements comply with Apache-2.0 requirements.
- Odin, as part of the Thor project, is licensed under the **GNU General Public License v3.0 (GPL-3.0)**.