<!--
SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Getting Thor into F-Droid

Written 1 August 2026 against `dev` at versionCode 1931. Every claim about *this repo* was checked
against the working tree; every claim about *F-Droid* comes from the pages listed under
[Sources](#sources), fetched the same day. Where I could not verify something, it says so.

> **IzzyOnDroid is not F-Droid.** Thor is already in IzzyOnDroid's repo
> (`https://apt.izzysoft.de/fdroid/repo`), which is a *third-party* repository that users add to an
> F-Droid client. It confers no standing with f-droid.org, shares none of its infrastructure, and
> being in it is not a step toward being in the main repo. This document is about the main repo.

---

## 1. Verdict

Thor is a good candidate and most of the hard work is already done — but **it cannot be submitted
today.** Two things must change in this repo first, and one of them is not a quick fix.

| | Status |
|---|---|
| GPL-3.0-or-later, source public | ✅ |
| `foss` flavour has zero proprietary dependencies | ✅ |
| Fastlane metadata already present | ✅ mostly |
| **AGP is a pre-release (`9.4.0-alpha07`)** | ⛔ **blocker** |
| ~~Release build aborts without a keystore~~ | ✅ fixed in the commit that added this doc |
| `-foss` versionNameSuffix | ⚠️ declare it in the recipe |
| ~~Stale changelogs directory~~ | ✅ `1931.txt` added alongside this doc |
| Extension store downloads APKs at runtime | ⚠️ raise it proactively |

---

## 2. What is already in Thor's favour

**The licence is unambiguous.** GPL-3.0-or-later, `LICENSE` at the root, SPDX headers throughout.
The Inclusion Policy asks for a FLOSS licence recognised by DFSG/FSF/OSI; GPL-3.0 is the example it
names first.

**The `foss` flavour is genuinely clean.** This is the single most important fact and it is already
true. In `app/build.gradle.kts`:

```kotlin
"storeImplementation"(libs.play.billing)
"storeImplementation"(libs.play.billing.ktx)
```

Play Billing is the only proprietary dependency in the project and it is scoped to `store`. The
`foss` source set carries its own `BillingProcessorImpl.kt` and `SupportDeveloperHelper.kt`, so
`assembleFossRelease` never links it. Everything else — Odin, Asgard, `thor-extension-api`, Shizuku
API, Dhizuku API, Coil, Koin, Lottie, Room, Compose — resolves from Maven Central or Google Maven,
both of which the Inclusion Policy explicitly lists as trusted sources for prebuilt FLOSS binaries.

`Odin`, `Asgard` and `thor-extension-api` being your own artifacts is fine — but be ready to point
reviewers at their public source repos, because "it's on Maven Central" is *not* on its own
sufficient. The policy is explicit: *"Those binaries must still be freely licensed, simply being
included in one of those repositories is not enough."*

**`dependenciesInfo { includeInApk = false }`** is already set, which is what you want — that block
is a Google-signed blob that gets in the way of verification.

**The benchmark build type is already confined to `store`**, so nothing about it can perturb a
`foss` build. That was done for IzzyOnDroid reproducibility and it pays off again here.

**Fastlane metadata exists** at `fastlane/metadata/android/{en-US,hi-IN}/` with title, short and full
descriptions, icon, feature graphic and ten screenshots. `fdroid update` picks this up from the repo
automatically — you do not re-enter it in the metadata file.

---

## 3. Blocker 1 — the pre-release AGP

`gradle/libs.versions.toml`:

```toml
agp = "9.4.0-alpha07"
```

The Inclusion Policy says, verbatim:

> The complete application building process requires a 100% FLOSS toolchain including
> Debian-packaged tools. **The use of proprietary build tools are strictly forbidden, including
> Oracle's JDK and some pre-release toolchains.**

An alpha Android Gradle Plugin is squarely "a pre-release toolchain". I did not find a page that
enumerates *which* pre-release toolchains are refused, so I cannot tell you with certainty that a
reviewer would reject `9.4.0-alpha07` — but planning around it being accepted would be optimistic.
There is also a practical problem behind the policy one: F-Droid's buildserver image ships a fixed
set of Gradle versions, and this repo is on Gradle 9.6.1 with `compileSdk`/`targetSdk` 37. All three
need to exist in the buildserver image on the day it builds.

**What to do:** wait for AGP 9.4.0 stable (or move to the newest stable line) before submitting.
This is a scheduling constraint, not a code change, and it is the reason the answer to "can we
submit this week" is no.

**Do not** try to work around it with a `patch:` or `prebuild:` line in the recipe that swaps the
AGP version — that produces an APK built from something other than what you ship and tested, which
defeats the point.

---

## 4. Blocker 2 (now fixed) — the release build aborted without a keystore

This one *is* a quick fix and it is entirely in your hands.

`app/build.gradle.kts` declares the release signing config with a three-way branch — local
`jks.properties`, then CI env vars, then a fallback that only logs:

```kotlin
} else {
    logger.warn("⚠️ keystore.properties not found or environment variables not set. …")
}
```

…but the release build type then assigns that config unconditionally:

```kotlin
release {
    …
    signingConfig = signingConfigs.getByName("release")
}
```

On F-Droid's buildserver there is no `jks.properties` and no `KEY_ALIAS`, so the config is empty and
the assignment still stands. **I verified this empirically rather than reading it off:** I moved
`jks.properties` aside, unset the four env vars, and ran

```bash
./gradlew :app:validateSigningFossRelease
```

which failed with `Keystore file not set for signing config release`. `assembleFossRelease` depends
on that task, so the F-Droid build dies before it produces an APK. (`jks.properties` was restored
immediately.)

**The fix**, applied in the same commit that added this document:

```kotlin
release {
    …
    // Only sign when we actually have credentials. F-Droid and any clean clone build unsigned;
    // fdroid/CI sign afterwards.
    signingConfig = signingConfigs.getByName("release")
        .takeIf { keystorePropertiesFile.exists() || System.getenv("KEY_ALIAS") != null }
}
```

This was worth doing regardless of F-Droid: before it, a fresh `git clone` could not build a release
APK at all, which is a poor first experience for any contributor. Verified both directions — with
credentials present `validateSigningFossRelease` still succeeds unchanged; with `jks.properties`
moved aside and no CI env vars, `assembleFossRelease` now succeeds and emits
`app-foss-release-unsigned.apk`.

> Worth knowing: IzzyOnDroid rebuilds Thor from source to award its reproducibility badge, which
> means their recipe is almost certainly already patching this line out on their side. Fixing it
> upstream removes that dependency.

---

## 5. Smaller things to fix in the same pass

**The `-foss` versionNameSuffix.** The `foss` flavour block in `app/build.gradle.kts` sets
`versionNameSuffix = "-foss"`, so the APK's versionName is `1.93.1-foss`, not `1.93.1`. F-Droid
compares the `versionName` in the recipe against the built APK and errors on a mismatch. You do not
have to remove the suffix — just declare it (see the recipe in §7). Decide deliberately, because
changing it later churns the metadata.

**The changelog directory was stale.** `fastlane/metadata/android/en-US/changelogs/` held exactly one
file, `1600.txt`, while `versionCode` is `1931`. F-Droid reads `changelogs/<versionCode>.txt` for the
"What's New" text, so every release since 1600 shipped without one — in IzzyOnDroid too. `1931.txt`
was added in the same commit as this document, copied from `release-notes/v1.93.1/playstore.txt`.

Still open: nothing *copies* it. The release notes are written each cycle under `release-notes/vX/`
and the fastlane changelog has to be updated by hand, which is why it drifted five releases behind
without anyone noticing. Worth adding to the release script rather than the checklist — a checklist
item is what already failed here.

**`hi-IN` is incomplete** — title, short and full description, but no images and no changelogs. Not
a blocker; F-Droid falls back to `en-US`.

**`.DS_Store` files are a non-issue** — worth stating because a plain `find` makes it look like one.
There are eight of them in the working tree, three inside `fastlane/metadata/`, but `.gitignore`
already covers them at line 12 and `git ls-files` reports **zero** tracked. Nothing to do; F-Droid
clones the repo, not your disk.

---

## 6. The conversation to have proactively: the extension store

`data/repository/StoreRepositoryImpl.kt` fetches a remote catalogue and Thor can install extension
APKs at runtime. This is the part of Thor most likely to draw a question, so raise it in the merge
request yourself rather than waiting to be asked.

I read the current Inclusion Policy and **did not find a clause that forbids downloading executable
code at runtime** — the "prebuilt binaries" rules I quoted above are about the *build* toolchain,
not about what a shipped app does. So I am not going to tell you it is banned. The real risk is
softer: reviewers may see a mechanism that delivers unreviewed executable code to users of an app
distributed by F-Droid, and want to understand the guardrails.

Points in Thor's favour, all already true:

- the extensions surface only appears when Root/Shizuku/Dhizuku is available, so ordinary users are
  never offered it;
- entry is gated behind a one-time liability-consent sheet;
- nothing is installed silently — the user chooses each extension.

If a reviewer does object, the fallback is an anti-feature label rather than rejection. The closest
fits in F-Droid's list are `NonFreeAdd` ("promotes other non-libre apps or plugins") if any listed
extension is closed-source, and `NonFreeNet` if the catalogue is served from a proprietary service.
Keeping every listed extension FOSS and the catalogue on infrastructure you control is what keeps
both off.

I could not verify what licences the currently-listed extensions carry — check that before you
submit, because it is the question that decides which way this goes.

---

## 7. Which route to take

There are two, and F-Droid's own docs disagree slightly about which to use, so here is the actual
distinction.

**Route A — RFP (Request For Packaging).** Open an issue at
`https://gitlab.com/fdroid/rfp/-/issues` and someone else may eventually package it. The RFP issue
template itself says:

> This issue tracker is meant for anyone to get an automated review to start the process of getting
> an app included. Opening issues here does not guarantee that the app will be reviewed or packaged.
> **If you are looking to submit an app to F-Droid, please open a merge request instead.**

**Route B — merge request against `fdroiddata`.** You write the recipe, test it locally, and open
the MR yourself.

**Take Route B.** You are the upstream developer, you know the build, and RFP is explicitly the
lower-priority path with no guarantee anyone picks it up. Route A only makes sense if you want
someone else to do the work and don't mind waiting indefinitely.

### Do not enable reproducible builds on the first attempt

You will read about `AllowedAPKSigningKeys` + `Binaries`, which make F-Droid publish *your*
signed APK after verifying it matches a build from the recipe. It is attractive — the same APK from
f-droid.org, IzzyOnDroid and GitHub, one signature, no "F-Droid version" confusion.

**It is close to a one-way door.** Once `AllowedAPKSigningKeys` pins your signing key, F-Droid will
only ever publish APKs bearing that key. Changing your mind means an app-id migration for every
installed user, because Android will not accept an update signed by a different key. There is no
undo.

Ship the ordinary F-Droid-signed way first. Get one release out, see the build go green on their
infrastructure, and enable reproducible builds later as a deliberate second step once the recipe is
proven. F-Droid supports adding it after the fact.

---

## 8. The recipe

Create `metadata/com.valhalla.thor.yml` in a fork of `fdroiddata`. This is a starting draft, not a
finished file — the `Builds:` block in particular needs to be reduced to whatever the first
submitted version actually is.

```yaml
Categories:
  - System
  - Security
License: GPL-3.0-or-later
AuthorName: Trinadh Thatakula
SourceCode: https://github.com/trinadhthatakula/Thor
IssueTracker: https://github.com/trinadhthatakula/Thor/issues
Changelog: https://github.com/trinadhthatakula/Thor/releases

Summary: Manage, freeze and clean up your installed apps
Description: |-
  Thor is an app manager for rooted devices and for devices with Shizuku or
  Dhizuku. It can freeze, suspend, force-stop, clear and uninstall apps that
  Android normally will not let you touch.

  Features:

  * Freezer — disable apps without uninstalling them, with a watchlist and
    optional auto-freeze when the screen turns off
  * Pinned home-screen shortcuts that unfreeze and launch in one tap
  * Suspend mode as a lighter alternative to freezing
  * Force stop, clear cache, clear data, batch uninstall
  * Works through root, Shizuku or Dhizuku, whichever is available

RepoType: git
Repo: https://github.com/trinadhthatakula/Thor.git

Builds:
  - versionName: 1.93.1-foss
    versionCode: 1931
    commit: v1.93.1
    subdir: app
    gradle:
      - foss

AutoUpdateMode: Version +-foss
UpdateCheckMode: Tags ^v\d+\.\d+\.\d+$
UpdateCheckData: gradle.properties|versionCode=(\d+)||
CurrentVersion: 1.93.1-foss
CurrentVersionCode: 1931
```

Four lines there are Thor-specific and are the ones most likely to be wrong if you start from
someone else's file:

**`gradle: [foss]`** selects the flavour. Per the metadata reference, *"Flavours are case sensitive
since the path to the output APK is as well"* and the task run is `assemble<Flavour>Release` —
so this produces `assembleFossRelease`, which is exactly right. Never `yes`: that would build both
flavours and pull in Play Billing.

**`UpdateCheckData: gradle.properties|versionCode=(\d+)||`** — Thor's versionCode lives in
`gradle.properties`, not in `build.gradle.kts`. F-Droid's default scanner looks in the build file
and will not find it. The two empty trailing fields mean "take the version name from the tag".

**`UpdateCheckMode: Tags ^v\d+\.\d+\.\d+$`** — the tag list mixes stable releases (`v1.93.0`) with
pre-releases (`v1.93.1-dev-103`). Without the regex, F-Droid would happily pick up a `-dev-` tag as
a release. The anchor is what excludes them.

**`AutoUpdateMode: Version +-foss`** — the `+<suffix>` form appends to the version name, which is how
you reconcile the recipe with `versionNameSuffix = "-foss"`. The metadata reference documents this
as a way *"to differentiate F-Droid's build from the original"*; using it to mirror an existing
suffix is the same mechanism. **Verify this one with `fdroid checkupdates` before relying on it** —
it is the line I am least certain about, since the published example is truncated mid-word.

`commit:` should be the tag, not a branch — F-Droid builds from a fixed point.

---

## 9. Test it locally before opening the MR

Do not submit an untested recipe. F-Droid ships the buildserver as a container, so you can run the
exact toolchain they will:

```bash
git clone --depth=1 https://gitlab.com/fdroid/fdroiddata ~/fdroiddata
git clone --depth=1 https://gitlab.com/fdroid/fdroidserver ~/fdroidserver

sudo docker run --rm -itu vagrant --entrypoint /bin/bash \
  -v ~/fdroiddata:/build:z \
  -v ~/fdroidserver:/home/vagrant/fdroidserver:Z \
  registry.gitlab.com/fdroid/fdroidserver:buildserver
```

Then inside the container:

```bash
. /etc/profile
export PATH="$fdroidserver:$PATH" PYTHONPATH="$fdroidserver"
export JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 > /dev/null \
  | grep 'java.home' | awk -F'=' '{print $2}' | tr -d ' ')
cd /build

fdroid readmeta
fdroid rewritemeta com.valhalla.thor
fdroid checkupdates --allow-dirty com.valhalla.thor
fdroid lint com.valhalla.thor
fdroid build com.valhalla.thor
```

`fdroid build` is where blockers 1 and 2 will surface concretely. F-Droid's own docs budget roughly
2 GB of traffic and 5 GB of disk for this; Thor's dependency set will not be smaller.

Note that this container is amd64. On an Apple Silicon Mac it runs under emulation and will be
slow — budget an evening, or run it on a Linux box.

When it builds, commit the metadata file on a branch named for the application id, label the MR
**New App**, and open it against `fdroiddata`.

---

## 10. Suggested order

1. ~~Make `signingConfig` conditional so a keystore-less clone can build.~~ ✅ done here.
2. ~~Add `fastlane/.../changelogs/1931.txt`.~~ ✅ done here — but teach the release script to copy it,
   or it drifts again. *(minutes)*
3. Decide about the `-foss` suffix: keep it and declare it, or drop it. *(a decision, not work)*
4. Audit the licences of the listed store extensions. *(an afternoon)*
5. **Wait for a stable AGP.** *(the actual gate)*
6. Write the recipe, test it in the buildserver container, open the MR.
7. Much later, once a release has gone through cleanly: consider reproducible builds.

Steps 3–4 are worth doing whether or not you ever submit. Step 5 is what sets the date.

---

## 11. What I could not verify

Stated plainly so none of it reads as settled:

- **Whether AGP `9.4.0-alpha07` would actually be rejected.** The policy names "some pre-release
  toolchains" without enumerating them. My read is that it would be, but it is a read.
- **Whether the buildserver image currently has Gradle 9.6.1 and SDK 37.** Both are recent. The
  container run in §9 answers this in one command.
- **Whether reviewers will object to the extension store**, and whether any anti-feature would be
  attached. Depends on the extensions' licences, which I did not check.
- **The exact `AutoUpdateMode: Version +<suffix>` syntax** — the published example is truncated.
- **How long any of it takes.** F-Droid is volunteer-run; MR review times vary widely and I have no
  basis for an estimate.

## Sources

Fetched 1 August 2026:

- <https://f-droid.org/docs/Inclusion_Policy/>
- <https://f-droid.org/docs/Build_Metadata_Reference/>
- <https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/>
- <https://f-droid.org/docs/Reproducible_Builds/>
- <https://f-droid.org/docs/Anti-Features/>
- <https://gitlab.com/fdroid/fdroiddata> — `CONTRIBUTING.md`, `templates/`, and
  `metadata/org.fdroid.fdroid.yml` as a worked example
