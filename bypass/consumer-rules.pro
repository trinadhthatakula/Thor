# ProGuard keep rules for com.valhalla.bypass
# This prevents R8/ProGuard from stripping or obfuscating reflection targets.

-keep class com.valhalla.bypass.Helper {
    *;
}

-keep class com.valhalla.bypass.Helper$* {
    *;
}
