# bypass-stubs

Compile-only Java stubs that allow `:bypass` to reference hidden Android runtime classes at compile time.

## Purpose

Two classes used by `:bypass` are not present on the standard Android SDK classpath:

| Stub class | Real runtime class | Used for |
|---|---|---|
| `dalvik.system.VMRuntime` | `dalvik.system.VMRuntime` (on-device) | Calling `setHiddenApiExemptions()` |
| `stub.sun.misc.Unsafe` | `sun.misc.Unsafe` (on-device) | Low-level field offset reads |

Without these stubs the `:bypass` module would fail to compile. At runtime the stubs are **not present** — the real classes from the device's ART runtime are used instead.

## Important: never add this as a runtime dependency

This module is declared `compileOnly` inside `:bypass`:

```kotlin
// bypass/build.gradle.kts
compileOnly(project(":bypass-stubs"))
```

Do **not** add `:bypass-stubs` as `implementation` or `api` anywhere. Shipping the stub classes in the APK would shadow the real `dalvik.system.VMRuntime` and break the bypass entirely.

## Why `stub.sun.misc` instead of `sun.misc`

The real `sun.misc.Unsafe` lives in `sun.misc` on the device. Placing the stub in the same package would risk a package conflict on some toolchain versions. The `stub.sun.misc` package is used at compile time only; `:bypass` obtains the real `Unsafe` instance at runtime via:

```kotlin
val field = Unsafe::class.java.getDeclaredField("theUnsafe")
field.isAccessible = true
field.get(null) as Unsafe   // resolves to sun.misc.Unsafe at runtime
```

## Stub surface

**`dalvik.system.VMRuntime`**
```java
public static VMRuntime getRuntime()
public void setHiddenApiExemptions(String... signatures)
```

**`stub.sun.misc.Unsafe`**
```java
long   objectFieldOffset(Field field)
long   getLong(Object obj, long offset)
void   putLong(Object obj, long offset, long newValue)
int    getInt(Object obj, long offset)
void   putInt(Object obj, long offset, int newValue)
int    arrayBaseOffset(Class<?> arrayClass)
int    arrayIndexScale(Class<?> arrayClass)
```

Only the methods actually called by `:bypass` are stubbed. There is no need to mirror the full `Unsafe` or `VMRuntime` API.
