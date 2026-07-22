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

