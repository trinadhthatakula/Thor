# bypass

An internal module that replaces the [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) dependency for accessing Android's restricted (hidden) APIs at runtime.

## Why this exists

Android enforces hidden API restrictions via a denylist checked in `java.lang.reflect` and in the native linker. The standard bypass technique calls `VMRuntime.setHiddenApiExemptions()` — a hidden method itself — before any restricted calls are made. Rather than pulling in an external AAR dependency for this single responsibility, `bypass` implements it directly with full control over the exemption signatures and an optional `Unsafe`-based field access path.

## Module structure

```
:bypass         — the runtime implementation (Bypass.kt)
:bypass-stubs   — compileOnly Java stubs for dalvik.system.VMRuntime and sun.misc.Unsafe
```

`:bypass-stubs` is a plain `java-library` that provides stub classes so `:bypass` can reference `VMRuntime.setHiddenApiExemptions()` and `Unsafe` at compile time without those classes being on the normal classpath. The real implementations are always present on-device.

## API reference

All functionality lives in the `com.valhalla.bypass.Bypass` singleton.

### Setup

```kotlin
// Call once, early in Application.onCreate()
Bypass.prepareThor()

// Optional: wire in your own logger
Bypass.setLogger { message, throwable ->
    Log.e("Bypass", message, throwable)
}
```

`prepareThor()` exempts the package prefixes Thor uses most:

| Exempted prefix | Covers |
|---|---|
| `Landroid/app` | ActivityManager, hidden app ops, etc. |
| `Landroid/content/pm` | PackageManager internals, IPackageManager |
| `Landroid/hardware/input` | Input manager internals |
| `Lcom/android/internal/app` | Internal app utilities |

### Exemption methods

```kotlin
// Exempt specific signatures (Dalvik descriptor prefix format)
Bypass.addExemptions("Landroid/content/pm", "Lcom/android/internal")

// Exempt every hidden API (equivalent to HiddenApiBypass.addHiddenApiExemptions("L"))
Bypass.exemptAll()
```

Exemptions are additive and permanent for the process lifetime. Prefer `addExemptions()` with tight prefixes over `exemptAll()` in production builds.

### Reflection helpers

These helpers bypass access checks even for methods/fields not covered by `setHiddenApiExemptions` by using `Unsafe` offsets directly.

```kotlin
// Call a hidden method
val result = Bypass.invoke(
    clazz    = ActivityManager::class.java,
    instance = activityManagerInstance,
    methodName = "getRunningServiceControlPanel",
    intent
)

// Get a declared Method object (already set accessible)
val method: Method? = Bypass.getDeclaredMethod(
    SomeHiddenClass::class.java,
    "hiddenMethodName",
    String::class.java, Int::class.java   // parameter types
)

// Read a field value via Unsafe (bypasses all access checks)
val value: Any? = Bypass.getField(instance, "mHiddenField")

// Instantiate a class with a hidden constructor
val obj = Bypass.newInstance(HiddenClass::class.java, arg1, arg2)
```

## Migration from AndroidHiddenApiBypass

| AndroidHiddenApiBypass | bypass equivalent |
|---|---|
| `HiddenApiBypass.addHiddenApiExemptions("L")` | `Bypass.exemptAll()` |
| `HiddenApiBypass.addHiddenApiExemptions("Landroid/app")` | `Bypass.addExemptions("Landroid/app")` |
| `HiddenApiBypass.invoke(clazz, obj, method, args)` | `Bypass.invoke(clazz, obj, method, args)` |
| `HiddenApiBypass.getDeclaredMethod(clazz, name, params)` | `Bypass.getDeclaredMethod(clazz, name, params)` |
| `HiddenApiBypass.newInstance(clazz, args)` | `Bypass.newInstance(clazz, args)` |

## Usage in the project

Add the module dependency:

```kotlin
// in your module's build.gradle.kts
implementation(project(":bypass"))
```

`:bypass-stubs` must **not** be added as a runtime dependency — it is only needed as `compileOnly` inside `:bypass` itself and is already declared there.

## How it works

1. **`VMRuntime.setHiddenApiExemptions()`** — the primary path. `VMRuntime` is itself a hidden class; `:bypass-stubs` provides a compile-time stub in the `dalvik.system` package so the call compiles. At runtime the real `dalvik.system.VMRuntime` on the device is used, and calling `setHiddenApiExemptions` with a set of Dalvik descriptor prefixes whitelists all matching members for the current process.

2. **Reflection-based access** — once exemptions are added, standard reflection (`getDeclaredMethod`, `getDeclaredField`) works even for hidden members without `Unsafe` poked reflection.
