# bypass-stubs

Compile-only Java stubs that allow `:bypass` to reference hidden Android runtime classes at compile time.

## Purpose

Two classes used by `:bypass` are not present on the standard Android SDK classpath:

| Stub class | Real runtime class | Used for |
|---|---|---|
| `dalvik.system.VMRuntime` | `dalvik.system.VMRuntime` (on-device) | Calling `setHiddenApiExemptions()` |

Without these stubs the `:bypass` module would fail to compile. At runtime the stubs are **not present** — the real classes from the device's ART runtime are used instead.

## Important: never add this as a runtime dependency

This module is declared `compileOnly` inside `:bypass`:

```kotlin
// bypass/build.gradle.kts
compileOnly(project(":bypass-stubs"))
```

Do **not** add `:bypass-stubs` as `implementation` or `api` anywhere. Shipping the stub classes in the APK would shadow the real `dalvik.system.VMRuntime` and break the bypass entirely.

## Why stubs instead of implementation

The real classes like `dalvik.system.VMRuntime` live on the device. Placing the stub in a separate module avoids conflicts during compilation. The module is used at compile time only.

## Stub surface

**`dalvik.system.VMRuntime`**
```java
public static VMRuntime getRuntime()
public void setHiddenApiExemptions(String... signatures)
```

Only the methods actually called by `:bypass` are stubbed. There is no need to mirror the full `VMRuntime` API.
