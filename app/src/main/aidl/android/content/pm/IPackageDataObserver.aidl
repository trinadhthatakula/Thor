// SPDX-FileCopyrightText: 2007 The Android Open Source Project
// SPDX-License-Identifier: Apache-2.0
//
// This file is AOSP's interface definition, not Thor's code, so it deliberately does NOT carry
// Thor's usual `2025-2026 Trinadh Thatakula` / `GPL-3.0-or-later` pair. Apache-2.0 combined into a
// GPL-3.0-or-later work is a compatible one-way combination; relabelling it would be a licence
// claim over someone else's text. There is no CI licence-header gate, so a "fix the SPDX header"
// reflex is the only thing that can get this wrong -- do not let it.
//
// Copied from frameworks/base/core/java/android/content/pm/IPackageDataObserver.aidl. The only
// deviation from AOSP is the removal of `@UnsupportedAppUsage` on the method: that annotation lives
// in `android.compat.annotation`, which is not on an application module's aidl import path, and it
// is metadata for the platform's own greylist rather than part of the interface's shape.
//
// ---------------------------------------------------------------------------------------------
// WHY A COPY EXISTS, AND WHY IT IS NEVER LOADED
// ---------------------------------------------------------------------------------------------
// `IPackageManager.clearApplicationUserData(String, IPackageDataObserver, int)` returns void and
// `deleteApplicationCacheFiles(String, IPackageDataObserver)` returns a boolean that means nothing;
// in both cases the real verdict arrives asynchronously on `onRemoveCompleted`. Receiving it needs
// a genuine `IPackageDataObserver.Stub` subclass, because a `java.lang.reflect.Proxy` cannot be
// marshalled over binder -- there is no way to hand the framework a callback object without one.
// `Stub` is not in the public SDK, so the Kotlin compiler has nothing to subclass unless this file
// generates one.
//
// At runtime the generated copy is dead weight and that is the point. `PathClassLoader` does not
// override `ClassLoader.loadClass`, so resolution is parent-first: `BootClassLoader` supplies the
// framework's real `android.content.pm.IPackageDataObserver$Stub` and the class generated from this
// file is never loaded. Thor's observer therefore extends the FRAMEWORK Stub, and dispatch is done
// by the framework's own `onTransact` using the framework's own transaction codes. That is the
// safety property worth stating plainly: because we subclass rather than reimplement `onTransact`,
// a change to the platform's transaction numbering cannot mis-dispatch us. It is the opposite
// situation to `com/valhalla/thor/rootservice/IThorRootService.aidl`, whose append-only rule exists
// precisely because those codes ARE ours to keep stable.
//
// Every way this can fail degrades to "could not confirm", never to a false success -- if the
// framework class ever disappeared, the generated copy would load instead, the framework would
// never call our object, `awaitDataObserver` would time out, and the clear would report failure.
// See `data/source/local/PackageDataObservers.kt` for the receiving half and
// `app/proguard-rules.pro` for why R8 must not rename the override.
//
// `oneway` and the parameter names/order are kept exactly as AOSP has them. `oneway` is irrelevant
// to the receive path (the framework Stub does the dispatch), but a faithful copy is far easier to
// re-verify against AOSP later than a tidied one.

package android.content.pm;

/**
 * API for package data change related callbacks from the Package Manager.
 * Some usage scenarios include deletion of cache directory, generate
 * statistics related to code, data, cache usage(TODO)
 * {@hide}
 */
oneway interface IPackageDataObserver {
    void onRemoveCompleted(in String packageName, boolean succeeded);
}
