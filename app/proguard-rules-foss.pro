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

# Obfuscation is ENABLED for the foss flavor to shrink the dex (the dominant cost in
# the direct-download APK). SourceFile + LineNumberTable are kept so crash stack traces
# still resolve to real line numbers; SourceFile is intentionally NOT renamed, keeping
# original file names readable for FOSS transparency.
-keepattributes SourceFile,LineNumberTable

#-keep class com.valhalla.thor.** { *; }