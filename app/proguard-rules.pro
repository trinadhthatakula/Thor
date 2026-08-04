# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# Ignore missing service definitions that are not relevant for Android runtime
-dontwarn javax.annotation.processing.Processor
-dontwarn javax.annotation.Nullable
-dontwarn dalvik.system.VMRuntime

# --- Extension API ABI (host-provided contract) -----------------------------
# Thor bundles com.trinadhthatakula:thor-extension-api and provides these types to extensions at
# runtime. Extensions declare the api `compileOnly` and their entry class is loaded via
# PathClassLoader (parent = Thor), so they reference the ORIGINAL fully-qualified names and resolve
# to Thor's copies parent-first. R8 must NOT rename or strip these, or every extension fails to load
# in release with a ClassNotFoundException / ClassCastException on its declared interface
# (ThorExtension / AutomationExtension / DebloatExtension …). Keep names + members of the api.
#
# NOTE: this is the ONLY host-ABI keep extensions need. Extension config UI runs in the extension's
# OWN process (an Activity Thor launches by Intent — ExtensionManager.ACTION_CONFIGURE), NOT inside
# Thor, so no Compose / Asgard / kotlin-stdlib types cross the boundary and Thor can shrink them
# freely. (The old in-host @Composable model forced keeping all of those and bloated the APK ~4x.)
-keep class com.valhalla.thor.extension.api.** { *; }
-keep interface com.valhalla.thor.extension.api.** { *; }

# --- Odin RootService daemon (Binder/AIDL, reflectively instantiated in the root process) --------
# ThorRootService is loaded BY CLASS NAME in a separate root (app_process) process via Odin's
# RootService bootstrap (RootServiceServer/RootServerMain: loadClass(name.className).newInstance()),
# and IThorRootService is dispatched over Binder. R8 must NOT shrink/optimize/rename these, or the
# daemon's suspend transaction breaks in release (works in debug only because debug isn't minified):
# suspend is the one root op with no shell fallback — it MUST use this AIDL path (GH#239: a shell
# `pm suspend` records the suspender as "root" and crashes SuspendedAppActivity on tap).
# Restores the full-keep that com.valhalla.superuser.ipc.** provided before ThorRootService moved
# out of :suCore into :app.
-keep class com.valhalla.thor.rootservice.** { *; }
-keep interface com.valhalla.thor.rootservice.** { *; }

# --- IPackageDataObserver (framework AIDL callback, shadowed at runtime) --------------------------
# This file is the one the RELEASE build type wires in (`proguardFiles(..., "proguard-rules.pro")`),
# so it covers both flavours -- `proguardFile("proguard-rules-foss.pro")` on the foss flavour ADDS to
# that list rather than replacing it. The rule has to live here and not there: the foss file's only
# blanket keep, `-keep class com.valhalla.thor.** { *; }`, is commented out, and it would not have
# covered `android.content.pm.**` anyway.
#
# app/src/main/aidl/android/content/pm/IPackageDataObserver.aidl is an AOSP copy that exists only so
# the Kotlin compiler has a Stub to subclass. At runtime PathClassLoader delegates parent-first and
# the boot classpath's real framework class wins, so Thor's observer extends the FRAMEWORK Stub and
# the FRAMEWORK's onTransact dispatches to it -- by the original method name and descriptor, which
# R8 has no way to know about: nothing in the dex calls onRemoveCompleted, a binder transaction does.
# Renaming the override would leave onTransact unable to find it, so the callback would never fire,
# every clear would report "could not confirm" forever, and only in release, because debug is not
# minified. Same failure shape as the rootservice block above.
-keep class android.content.pm.IPackageDataObserver { *; }
-keep class android.content.pm.IPackageDataObserver$Stub { *; }
-keep class * extends android.content.pm.IPackageDataObserver$Stub { *; }

