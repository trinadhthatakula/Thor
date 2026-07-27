# Shizu CoreFetch Store Listing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** List Thor on Shizu CoreFetch by adding a validated `shizu_store.json` to the root of `master`, alongside a refresh of the store-facing copy that removes a privacy claim which is no longer true.

**Architecture:** The manifest is entirely hand-written; nothing in CI ever rewrites it. Two shell scripts back it up — `check-shizu-manifest.sh` asserts the manifest still matches the fastlane copy, the screenshot directory, the SDK versions, and the release notes, and `sync-shizu-changelog.sh` refreshes the one field that tracks releases. PR CI runs the checker without network on every relevant change; a weekly scheduled workflow runs it *with* network and files a tracking issue when something outside the repo has rotted.

**Tech Stack:** JSON Schema draft-07, `jq`, `curl`, `check-jsonschema`, GitHub Actions, fastlane metadata files.

## Global Constraints

Every task's requirements implicitly include this section.

- **Target branch is `master`.** Work happens on `feat/shizu-store-manifest` branched from `master`, merged to `master` by PR, then `master` is merged into `dev`. Shizu CoreFetch reads only the default branch, which is `master`.
- **Do not bump `versionCode`.** It stays `1930` in `gradle.properties` → version name `1.93.0`. This ships against the already-released v1.93.0.
- **`version_name` and `version_code` must NOT appear in `shizu_store.json`.** They are omitted by design so the store reads them from the GitHub release. A CI assertion enforces their absence.
- **The schema sets `"additionalProperties": false`** at the top level, inside `developer`, inside `developer.socials`, and inside each `locales.*` entry. One stray key voids the entire file, and the store's response is silence — it ignores the manifest and falls back to bare GitHub data.
- **`short_description` must be ≤ 80 characters** in every locale. The schema permits 200; Google Play permits 80; the same text feeds both, so the stricter limit governs.
- **`tags` must have ≤ 15 entries.** We ship exactly 15.
- **`download_url` must match `^https?://.*\.apk$`.**
- **All image URLs are pinned to `/master/`**, never to a tag or to `dev`.
- **No text anywhere may claim Thor is "100% offline"** or that it requires "zero internet permissions". `app/src/main/AndroidManifest.xml` declares `android.permission.INTERNET`. This is the change's second purpose and applies to English, Hindi, and the README.
- **The app's UI languages are exactly `en, ar, es, fr, zh-rCN`.** `app/build.gradle.kts:190` sets `localeFilters` to that set and there is no `app/src/main/res/values-hi/`. Any "language switcher supports…" line must name these five and no others — the current Hindi copy claims Hindi among them and is wrong. Shipping a Hindi *store listing* for an app with no Hindi UI is fine and intended; claiming a Hindi UI is not.
- **The APK size is written as "about 3 MB"** — never a two-decimal figure. `foss-release.apk` is 3,239,471 bytes, which is 3.24 MB decimal but 3.09 MiB, and GitHub's release page shows the latter.
- **The distributed artifact is `foss-release.apk`**, never `store-release.apk` (Play Billing) and never the `.aab`.
- **Valid locale codes** are exactly `ar, en, fr, es, pt, ru, hi, zh, ja`. We use `ar, es, fr, hi, zh` under `locales`; `en` is the top-level base and must not also appear under `locales`.
- **Text comparisons strip trailing newlines on both sides.** `en-US/short_description.txt` ends without a newline; `hi-IN/short_description.txt` ends with one. A byte-exact comparison would fail on one of them for a reason unrelated to drift.
- **Every commit ends with** `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- **Never run `git add -A`.** `docs/audit/` is untracked and not gitignored.

---

## File Structure

| Path | Responsibility | Action |
|---|---|---|
| `shizu_store.json` | The store manifest. Hand-written, root of `master`. | Create |
| `.github/shizu_store.schema.json` | Vendored copy of the upstream schema, so validation is deterministic. | Create |
| `.github/scripts/check-shizu-manifest.sh` | All manifest assertions. `--network` adds URL reachability and live-schema validation. Called by both workflows. | Create |
| `.github/scripts/sync-shizu-changelog.sh` | Rewrites the single `changelog` field from `release-notes/v*/playstore.txt`. Run by a human at release prep. | Create |
| `.github/workflows/shizu-store-audit.yml` | Weekly `--network` run; opens/updates/closes a tracking issue. | Create |
| `.github/workflows/pr-ci.yml` | Gains a second job running the checker without network. | Modify |
| `.github/workflows/release-manager.yml` | Unused and broken. | Delete |
| `fastlane/metadata/android/en-US/full_description.txt` | English long copy; also the source of `detailed_description`. | Rewrite |
| `fastlane/metadata/android/en-US/short_description.txt` | English short copy; also the source of `short_description`. | Rewrite |
| `fastlane/metadata/android/hi-IN/full_description.txt` | Hindi long copy; source of `locales.hi.detailed_description`. | Rewrite |
| `fastlane/metadata/android/hi-IN/short_description.txt` | Hindi short copy; source of `locales.hi.short_description`. | Rewrite |
| `fastlane/metadata/android/en-US/images/featureGraphic.png` | Store banner. | Replace from `dev` |
| `README.md` | Repo front page; carries the same false claims and two dead links. | Modify |

The two scripts are separate files because they have opposite audiences and opposite risk profiles: the checker only reads and is run by machines, while the sync script writes the manifest and is run by a person. Folding the write path into the thing CI executes is how a "checker" quietly becomes a writer.

---

### Task 1: Branch from `master` and bring over the feature graphic

The store banner comes from `featureGraphic.png` on `master`, but the current version of that image only exists on `dev`. Commit `e2d5b522` on `dev` touches that one file and nothing else, so it cherry-picks cleanly.

**Files:**
- Create: branch `feat/shizu-store-manifest`
- Modify: `fastlane/metadata/android/en-US/images/featureGraphic.png`

**Interfaces:**
- Produces: a branch based on `master` containing the current banner. Every later task commits onto it.

- [ ] **Step 1: Confirm the working tree is clean and create the branch**

```bash
git status --porcelain          # must print nothing
git fetch origin
git checkout -b feat/shizu-store-manifest origin/master
```

- [ ] **Step 2: Verify the cherry-pick target touches only the image**

```bash
git show --stat --oneline e2d5b522
```

Expected: exactly one file changed, `fastlane/metadata/android/en-US/images/featureGraphic.png`. If any other path appears, stop and do the copy manually with `git checkout e2d5b522 -- <path>` instead.

- [ ] **Step 3: Cherry-pick it**

```bash
git cherry-pick e2d5b522
```

- [ ] **Step 4: Verify the result is byte-identical to `dev`'s blob**

```bash
diff <(git show e2d5b522:fastlane/metadata/android/en-US/images/featureGraphic.png) \
     fastlane/metadata/android/en-US/images/featureGraphic.png && echo IDENTICAL
```

Expected: `IDENTICAL`. Also confirm the dimensions are still 1024×500, which is what the store expects:

```bash
sips -g pixelWidth -g pixelHeight fastlane/metadata/android/en-US/images/featureGraphic.png
```

Expected: `pixelWidth: 1024`, `pixelHeight: 500`.

- [ ] **Step 5: No commit needed**

The cherry-pick already created one. Confirm with `git log --oneline -1`.

---

### Task 2: Rewrite the English store copy

Two files. `full_description.txt` gains the v1.93.0 features and loses the false offline claim; `short_description.txt` loses it too and drops from 80 characters to 74.

Both files are also the source of truth for the manifest's `detailed_description` and `short_description`, so their exact bytes matter — Task 6's checker compares them.

**Files:**
- Modify: `fastlane/metadata/android/en-US/full_description.txt` (full rewrite)
- Modify: `fastlane/metadata/android/en-US/short_description.txt` (full rewrite)

**Interfaces:**
- Produces: the exact English text that Task 5 embeds into `shizu_store.json` as `detailed_description` and `short_description`.

- [ ] **Step 1: Rewrite `short_description.txt`**

Write exactly this, **with no trailing newline** (the current file has none, and Task 6's checker strips trailing newlines anyway, but keeping the file's existing convention avoids a spurious diff):

```
Freeze, debloat & install apps via Shizuku, Root & Dhizuku. Ad-free & FOSS
```

- [ ] **Step 2: Verify the length**

```bash
awk '{print length($0)}' fastlane/metadata/android/en-US/short_description.txt
```

Expected: `74`. Must be ≤ 80.

- [ ] **Step 3: Rewrite `full_description.txt`**

Replace the entire file with:

```
Thor App Manager is a lightweight, open-source Android app manager and installer designed for power users who want complete control over their devices. Built 100% in Kotlin using Jetpack Compose and Material 3, Thor delivers advanced package management without trackers, ads, or telemetry.

⚡ MULTIPLE PRIVILEGE MODES
Thor works seamlessly across different privilege engines with an automatic fallback system. Manually choose your active backend:
• Root (su)
• Shizuku
• Dhizuku (Device Owner)

📦 ADVANCED APP INSTALLER
Easily install, reinstall, or repair installer records.
• Redesigned app installer for root, Shizuku, or standard package manager.
• Full support for split APK formats: .apkm, .apks, and .xapk files.
• Auto Reinstall: sync and reinstall your apps with custom install-time options.
• Fix Store: Reassign the installer record to Google Play Store in any privilege mode.

🚫 UNIVERSAL SYSTEM APP DEBLOATING
Safely debloat your device with integrated Universal Android Debloater (UAD) guidelines.
• Dynamic safety recommendation chips: Recommended, Advanced, Expert, Unsafe.
• Safety Gating: Automatically blocks freezing of "Unsafe" system packages to prevent bootloops and warns on "Expert" apps.
• Clean Uninstalls: Uninstalls system apps for the current user (pm uninstall --user) and restores them (pm install-existing) with ease.
• Local icon caching and danger badges for uninstalled system packages.

🛠️ BATCH OPERATIONS & POWER TOOLS
Perform actions on multiple apps simultaneously with a real-time terminal logger dialog.
• Batch freeze, unfreeze, reinstall, uninstall, suspend, and clear cache or data.
• Force stop, restrict background activity, or clear app cache/data in any mode.
• Suspend & unsuspend apps with a custom Thor-branded system dialog.

🧩 EXTENSION MANAGER
Extend Thor with optional add-ons, browsed and installed from an in-app catalog.
• Every extension is signature-verified and SHA-256 checked before it installs.
• Entirely opt-in: install no extensions and Thor never touches the network.

📱 ADAPTIVE UI & LAYOUT PERSISTENCE
Enjoy a beautiful Material 3 interface that adapts to your device.
• Redesigned home screen: an adaptive bento grid with one-tap access to the Extension Manager.
• Multi-pane layouts and vertical navigation rail optimized for tablets and foldables.
• Persistent layout preferences: Grid and list view settings are preserved across restarts.
• True AMOLED black mode, Light/Dark themes, and Material You dynamic color support.
• Fingerprint / biometric lock to protect app access.
• Built-in language switcher supporting English, Spanish, French, Arabic, and Chinese.

🔒 PRIVACY-FIRST & LIGHTWEIGHT
• No analytics, no crash reporters, no ads, no trackers — ever.
• The only network access is the optional Extensions store, which fetches its catalog and verified extension APKs over HTTPS with a pinned signer and SHA-256 check. Every other feature works fully offline.
• Open Source: Licensed under GNU GPL v3.0-or-later (libre software).
• Ultra-lightweight: the direct-download APK is about 3 MB.

TECHNICAL HIGHLIGHTS (AI Agent Optimized)
• Target Platform: Android (Root, Shizuku, Dhizuku)
• Architecture & Stack: Kotlin, Clean Architecture, MVVM, Room DB (caching metadata), Jetpack Compose
• Hidden API bypass: Custom in-house bypass module (no external library dependency)
• Shell Execution: Odin, an in-house Kotlin root-shell engine published on Maven Central

No bloat. No trackers. No nonsense. Take control of your apps with Thor.
```

Keep the single trailing newline the file already ends with.

- [ ] **Step 4: Verify no false claim survives**

```bash
grep -niE "offline|internet permission|no internet" fastlane/metadata/android/en-US/*.txt
grep -n "language switcher" fastlane/metadata/android/en-US/full_description.txt
```

Expected from the first: exactly one hit — the line "Every other feature works fully offline." in `full_description.txt`, which is a scoped and true statement. Any hit containing "100%" or "permission" is a failure.

Expected from the second: a line naming exactly English, Spanish, French, Arabic, and Chinese. Do not add Hindi — the app has no Hindi UI, only a Hindi store listing.

- [ ] **Step 5: Verify the length limit Play enforces**

```bash
wc -c < fastlane/metadata/android/en-US/full_description.txt
```

Expected: a number under 4000 (Play's limit for the long description).

- [ ] **Step 6: Commit**

```bash
git add fastlane/metadata/android/en-US/full_description.txt \
        fastlane/metadata/android/en-US/short_description.txt
git commit -m "docs(fastlane): correct the offline claim and add v1.93.0 features

Thor declares android.permission.INTERNET, so \"100% offline\" and \"zero
internet permissions required\" are both false. The Extensions store
fetches a catalog and verified extension APKs over HTTPS; nothing else
touches the network. The copy now says that instead.

Also adds Auto Reinstall, the Extension Manager and the redesigned home
screen, corrects the size claim from ~2 MB to about 3 MB, and renames
suCore to Odin now that it ships from Maven Central.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Rewrite the Hindi store copy

The Hindi files carry the same two false claims — `• 100% ऑफलाइन: किसी इंटरनेट अनुमति की आवश्यकता नहीं है।` and `~2 MB डाउनलोड साइज` — and are missing the v1.93.0 features. They mirror the English structure section for section, so the rewrite mirrors Task 2's.

`hi-IN` is the only non-English fastlane locale that exists; `ar`, `es`, `fr`, and `zh` live only in the manifest and are handled in Task 7.

**Files:**
- Modify: `fastlane/metadata/android/hi-IN/full_description.txt` (full rewrite)
- Modify: `fastlane/metadata/android/hi-IN/short_description.txt` (full rewrite)

**Interfaces:**
- Consumes: the structure and claims settled in Task 2 — the Hindi text must make the same statements, not different ones.
- Produces: the exact Hindi text that Task 7 embeds as `locales.hi.detailed_description` and `locales.hi.short_description`.

- [ ] **Step 1: Rewrite `short_description.txt`**

Only the trailing claim changes. Write exactly this, keeping the file's existing trailing newline:

```
Shizuku, Root & Dhizuku से ऐप्स फ्रीज, डीब्लोट व इंस्टॉल करें। Ad-free & FOSS
```

- [ ] **Step 2: Verify the length**

```bash
awk '{print length($0)}' fastlane/metadata/android/hi-IN/short_description.txt
```

Expected: a number ≤ 80. This swaps `Offline` for `Ad-free`, both seven characters, so it must equal the previous file's count. If `awk` reports bytes rather than characters on this system, use `python3 -c "import sys;print(len(open(sys.argv[1],encoding='utf-8').read().rstrip('\n')))" fastlane/metadata/android/hi-IN/short_description.txt` instead — Devanagari is three bytes per character in UTF-8, so a byte count will look like a failure when it is not.

- [ ] **Step 3: Rewrite `full_description.txt`**

Replace the entire file with:

```
थॉर (Thor) ऐप मैनेजर एक लाइटवेट, ओपन-सोर्स एंड्रॉइड ऐप मैनेजर और इंस्टॉलर है जिसे उन पावर यूजर्स के लिए डिज़ाइन किया गया है जो अपने डिवाइस पर पूरा नियंत्रण चाहते हैं। जेटपैक कंपोज़ (Jetpack Compose) और मटेरियल 3 (Material 3) का उपयोग करके 100% कोटलिन (Kotlin) में निर्मित, थॉर बिना किसी ट्रैकर, विज्ञापन या टेलीमेट्री के उन्नत पैकेज प्रबंधन प्रदान करता है।

⚡ मल्टीपल प्रिविलेज मोड्स (Privilege Modes)
थॉर एक ऑटोमैटिक फ़ॉलबैक सिस्टम के साथ विभिन्न प्रिविलेज इंजनों में आसानी से काम करता है। अपना पसंदीदा मोड मैन्युअल रूप से चुनें:
• रूट (Root - su)
• शिज़ुकु (Shizuku)
• धीज़ुकु (Dhizuku - डिवाइस ओनर)

📦 एडवांस ऐप इंस्टॉलर (App Installer)
आसानी से ऐप्स इंस्टॉल, रीइंस्टॉल या इंस्टॉलर रिकॉर्ड को ठीक करें।
• रूट, Shizuku या स्टैंडर्ड पैकेज मैनेजर के लिए रीडिजाइन किया गया ऐप इंस्टॉलर।
• स्प्लिट एपीके (Split APK) प्रारूपों के लिए पूर्ण समर्थन: .apkm, .apks, और .xapk फ़ाइलें।
• ऑटो रीइंस्टॉल (Auto Reinstall): कस्टम इंस्टॉल-टाइम विकल्पों के साथ अपने ऐप्स को सिंक और रीइंस्टॉल करें।
• फिक्स स्टोर (Fix Store): किसी भी प्रिविलेज मोड में इंस्टॉलर रिकॉर्ड को Google Play Store पर रीअसाइन करें।

🚫 यूनिवर्सल सिस्टम ऐप डीब्लोटिंग (Debloating)
यूनिवर्सल एंड्रॉइड डीब्लोटर (UAD) दिशानिर्देशों के साथ अपने डिवाइस को सुरक्षित रूप से डीब्लोट करें।
• डायनेमिक सुरक्षा अनुशंसा चिप्स: Recommended (अनुशंसित), Advanced (उन्नत), Expert (विशेषज्ञ), Unsafe (असुरक्षित)।
• सेफ्टी गेटिंग (Safety Gating): बूटलूप से बचने के लिए "Unsafe" सिस्टम ऐप्स को फ्रीज करने से रोकता है और "Expert" ऐप्स पर चेतावनी देता है।
• क्लीन अनइंस्टॉल: वर्तमान यूजर के लिए सिस्टम ऐप्स अनइंस्टॉल करें (pm uninstall --user) और उन्हें आसानी से पुनर्स्थापित (pm install-existing) करें।
• अनइंस्टॉल किए गए सिस्टम पैकेजों के लिए लोकल आइकन कैशिंग और डेंजर बैज।

🛠️ बैच ऑपरेशंस और पावर टूल्स
एक रीयल-टाइम टर्मिनल लॉगर डायलॉग के साथ एक साथ कई ऐप्स पर क्रियाएं करें।
• बैच फ्रीज (Freeze), अनफ्रीज (Unfreeze), रीइंस्टॉल, अनइंस्टॉल, सस्पेंड और डेटा/कैश क्लियर करें।
• किसी भी मोड में ऐप्स को फोर्स स्टॉप करें, बैकग्राउंड एक्टिविटी को प्रतिबंधित करें या ऐप कैश/डेटा क्लियर करें।
• कस्टम थॉर-ब्रांडेड system डायलॉग के साथ ऐप्स को सस्पेंड और अनसस्पेंड करें।

🧩 एक्सटेंशन मैनेजर (Extension Manager)
इन-ऐप कैटलॉग से वैकल्पिक ऐड-ऑन ब्राउज़ और इंस्टॉल करके थॉर का विस्तार करें।
• हर एक्सटेंशन इंस्टॉल होने से पहले सिग्नेचर-सत्यापित और SHA-256 जाँचा जाता है।
• पूरी तरह वैकल्पिक: कोई एक्सटेंशन इंस्टॉल न करें, और थॉर कभी नेटवर्क से संपर्क नहीं करेगा।

📱 एडेप्टिव यूआई (Adaptive UI) और लेआउट परसिस्टेंस
एक सुंदर मटेरियल 3 इंटरफ़ेस का आनंद लें जो आपके डिवाइस के अनुकूल बनता है।
• नया होम स्क्रीन: एक एडेप्टिव बेंटो ग्रिड, जिसमें एक्सटेंशन मैनेजर सिर्फ एक टैप दूर है।
• टैबलेट और फोल्डेबल के लिए अनुकूलित मल्टी-पेन लेआउट और वर्टिकल नेविगेशन रेल।
• लगातार लेआउट प्राथमिकताएं: ग्रिड और लिस्ट व्यू सेटिंग्स रीस्टार्ट होने पर भी सुरक्षित रहती हैं।
• ट्रू एमोलेड (AMOLED) ब्लैक मोड, लाइट/डार्क थीम और मटेरियल यू (Material You) डायनेमिक कलर सपोर्ट।
• ऐप एक्सेस की सुरक्षा के लिए फिंगरप्रिंट / बायोमेट्रिक लॉक।
• इन-ऐप लैंग्वेज स्विचर: अंग्रेजी, स्पेनिश, फ्रेंच, अरबी और चीनी।

🔒 प्राइवेसी-फर्स्ट और लाइटवेट
• कोई एनालिटिक्स नहीं, कोई क्रैश रिपोर्टर नहीं, कोई विज्ञापन नहीं, कोई ट्रैकर नहीं — कभी नहीं।
• नेटवर्क का उपयोग केवल वैकल्पिक एक्सटेंशन स्टोर करता है, जो अपना कैटलॉग और सत्यापित एक्सटेंशन APK पिन किए गए सिग्नर और SHA-256 जाँच के साथ HTTPS पर लाता है। बाकी हर सुविधा पूरी तरह ऑफलाइन काम करती है।
• ओपन सोर्स: GNU GPL v3.0-or-later (लिब्रे सॉफ्टवेयर) के तहत लाइसेंस प्राप्त।
• अल्ट्रा-लाइटवेट: सीधे डाउनलोड होने वाली APK लगभग 3 MB की है।

TECHNICAL HIGHLIGHTS (AI Agent Optimized)
• Target Platform: Android (Root, Shizuku, Dhizuku)
• Architecture & Stack: Kotlin, Clean Architecture, MVVM, Room DB (caching metadata), Jetpack Compose
• Hidden API bypass: Custom in-house bypass module (no external library dependency)
• Shell Execution: Odin, an in-house Kotlin root-shell engine published on Maven Central

बिना किसी ट्रैकर, बिना विज्ञापन और बिना किसी फालतू चीज़ के। थॉर के साथ अपने ऐप्स पर पूरा नियंत्रण पाएं।
```

Two removals here are corrections, not trimming:

- The language-switcher line previously read `…अरबी और चीनी के साथ हिंदी का समर्थन करता है` — "supports Hindi along with English, Spanish, French, Arabic and Chinese". Thor's UI has no Hindi translation. `app/build.gradle.kts:190` sets `localeFilters` to exactly `en, ar, es, fr, zh-rCN`, and there is no `app/src/main/res/values-hi/`. The claim was false in the language of the reader most able to check it.
- `• Localization: Hindi translation matching English feature set and design specs` is dropped. It sits in a list of app internals but describes only this store listing, so it reads as a promise the app does not keep.

A Hindi store listing for an app with an English UI is normal and stays. Claiming a Hindi UI does not.

- [ ] **Step 4: Verify no false claim survives**

```bash
grep -nE "100% ऑफलाइन|इंटरनेट अनुमति|~2 MB|4 MB|हिंदी का समर्थन" fastlane/metadata/android/hi-IN/*.txt
```

Expected: no output.

- [ ] **Step 4b: Verify the claimed UI languages match the build**

```bash
grep -n "localeFilters" app/build.gradle.kts
ls -d app/src/main/res/values-*/ | sed 's#.*res/values-##; s#/##'
```

Expected: `localeFilters.set(setOf("en", "ar", "es", "fr", "zh-rCN"))`, and no `hi` among the resource directories. Both language-switcher lines — English and Hindi — must name exactly these five and no more.

- [ ] **Step 5: Verify the section count matches English**

Both files must have the same seven emoji-headed sections, or the manifest presents a different app in each language.

```bash
for d in en-US hi-IN; do
  printf '%s: %s sections\n' "$d" \
    "$(grep -cE '^(⚡|📦|🚫|🛠️|🧩|📱|🔒)' fastlane/metadata/android/$d/full_description.txt)"
done
```

Expected: both print `7`. Alternation is used rather than a `[…]` bracket expression because `🛠️` is two codepoints (the glyph plus a variation selector) and would corrupt a character class.

- [ ] **Step 6: Commit**

```bash
git add fastlane/metadata/android/hi-IN/full_description.txt \
        fastlane/metadata/android/hi-IN/short_description.txt
git commit -m "docs(fastlane): apply the same corrections to the Hindi copy

Mirrors the English rewrite: drops the false offline and size claims,
adds Auto Reinstall, the Extension Manager and the redesigned home
screen, and renames suCore to Odin.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Update the README

The README repeats the offline claim, states two size figures that are now wrong, and links twice to `Thor/tree/master/suCore` — a path that no longer exists on `master` or `dev`, because the module was extracted to its own repository. `settings.gradle.kts` includes only `:app`, `:bypass`, and `:vm-runtime`.

**Files:**
- Modify: `README.md:32-36` (bullet list), `README.md:40-72` (features), `README.md:87`, `README.md:99-112` (credits)

**Interfaces:**
- Consumes: the claims settled in Task 2. The README must not contradict the store copy.

- [ ] **Step 1: Fix the summary bullets**

Replace lines 32–36:

```markdown
* PlayStore Download Size (around 3.0 MB)
* Smallest APK size (less than 6 MB)
* FOSS - GPL-3.0
* Fully Offline
* No Ads/Trackers
```

with:

```markdown
* Direct-download APK around 3 MB
* FOSS - GPL-3.0
* No Ads/Trackers
* No network access except the optional Extensions store
```

- [ ] **Step 2: Add the v1.93.0 features**

In the `## Working Features` list, immediately after the `- **Redesigned App Installer** …` line, insert:

```markdown
- **Auto Reinstall** — sync and reinstall apps with custom install-time options
- **Extension Manager** — an in-app catalog of optional add-ons, each signature-verified and SHA-256 checked before install
- **Redesigned Home** — an adaptive bento grid with one-tap access to the Extension Manager
```

- [ ] **Step 3: Fix the support section's claim**

Replace on line 87:

```markdown
Thor is a labor of love, built to be **100% offline, ad-free, and tracker-free**. If this tool has
```

with:

```markdown
Thor is a labor of love, built to be **ad-free and tracker-free**. If this tool has
```

Leave line 131 (`Thor is **100% FOSS with no ads and no trackers**`) alone — it is still true.

- [ ] **Step 4: Repoint the two dead links**

Replace:

```markdown
- Built the **Odin** root-service binding framework inside the [
  `suCore`](https://github.com/trinadhthatakula/Thor/tree/master/suCore) module under package `com.valhalla.superuser`. Odin was adapted from the architectural design of [`libsu`](https://github.com/topjohnwu/libsu) by [topjohnwu](https://github.com/topjohnwu/) and completely rewritten to eliminate all `com.topjohnwu` package namespaces.
```

with:

```markdown
- Built the **Odin** root-service binding framework, now a standalone library at
  [`trinadhthatakula/Odin`](https://github.com/trinadhthatakula/Odin) and published to Maven
  Central as `com.trinadhthatakula:odin`. Odin was adapted from the architectural design of
  [`libsu`](https://github.com/topjohnwu/libsu) by [topjohnwu](https://github.com/topjohnwu/) and
  completely rewritten to eliminate all `com.topjohnwu` package namespaces.
```

Replace the heading `### Architectural Advances in Odin (suCore)` with `### Architectural Advances in Odin`, and replace:

```markdown
- Refer to the SuCore [README](https://github.com/trinadhthatakula/Thor/blob/master/suCore/README.md) for more details.
```

with:

```markdown
- Refer to the [Odin README](https://github.com/trinadhthatakula/Odin#readme) for more details.
```

- [ ] **Step 5: Verify both links resolve**

```bash
for u in https://github.com/trinadhthatakula/Odin \
         https://github.com/trinadhthatakula/Odin#readme; do
  printf '%s -> %s\n' "$u" "$(curl -s -o /dev/null -w '%{http_code}' -L "$u")"
done
```

Expected: `200` for both.

- [ ] **Step 6: Verify no stale reference remains**

```bash
grep -nE "suCore|Fully Offline|100% offline" README.md
```

Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add README.md
git commit -m "docs(readme): correct the offline and size claims, fix the Odin links

Two Credits links pointed at Thor/tree/master/suCore, which no longer
exists on either branch — the module was extracted to its own repo and
published to Maven Central. Both now point at trinadhthatakula/Odin.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Vendor the schema and write the manifest

The manifest is validated against a committed copy of the upstream schema rather than the live URL, so CI does not depend on a third party's uptime and so upstream schema changes surface as a reviewable diff instead of as a listing that quietly stopped working.

This task ships the English base only. `locales` is added in Task 7.

**Files:**
- Create: `.github/shizu_store.schema.json`
- Create: `shizu_store.json`

**Interfaces:**
- Consumes: the English text from Task 2, verbatim.
- Produces: `shizu_store.json` with these top-level keys and no others — `schema_version, app_name, package_name, min_sdk, target_sdk, short_description, detailed_description, developer_message, icon_url, banner_url, screenshots, repo_url, download_url, changelog, store_issue_number, category, license, open_source, requires_shizuku, ad, donate_url, tags, developer`. Task 7 adds exactly one more: `locales`.

- [ ] **Step 1: Install the validator**

```bash
check-jsonschema --version || brew install check-jsonschema || pipx install check-jsonschema
```

On macOS a bare `pip install` may fail with `externally-managed-environment`; `brew` or `pipx` is the working path. Expected: a version string.

- [ ] **Step 2: Vendor the schema**

```bash
mkdir -p .github/scripts
curl -fsSL https://docshizu.siwane.xyz/schema.json -o .github/shizu_store.schema.json
jq empty .github/shizu_store.schema.json && echo VALID_JSON
```

Expected: `VALID_JSON`.

- [ ] **Step 3: Confirm the vendored copy matches what this plan was written against**

```bash
jq -r '"draft=\(.["$schema"]) required=\(.required|join(",")) addProps=\(.additionalProperties) tagsMax=\(.properties.tags.maxItems) shortMax=\(.properties.short_description.maxLength)"' \
  .github/shizu_store.schema.json
```

Expected exactly:

```
draft=http://json-schema.org/draft-07/schema# required=app_name,package_name,short_description,icon_url addProps=false tagsMax=15 shortMax=200
```

If any value differs, upstream changed the schema after this plan was written. Stop and re-check the affected field decisions before continuing — this is precisely the drift the weekly audit exists to catch, arriving early.

- [ ] **Step 4: Generate the three long string values**

The manifest must contain the English copy byte-for-byte. Do not retype it. Run each command and use its output verbatim as the corresponding JSON value — each prints a complete, already-quoted JSON string with the trailing newline stripped:

```bash
echo '--- short_description ---'
jq -Rs 'rtrimstr("\n")' < fastlane/metadata/android/en-US/short_description.txt
echo '--- detailed_description ---'
jq -Rs 'rtrimstr("\n")' < fastlane/metadata/android/en-US/full_description.txt
echo '--- changelog ---'
jq -Rs 'rtrimstr("\n")' < release-notes/v1.93.0/playstore.txt
```

- [ ] **Step 5: Write `shizu_store.json`**

Create it at the repository root with the Write tool, substituting the three outputs from Step 4 where marked. Every other value below is final and must be copied exactly.

```json
{
  "schema_version": 1,
  "app_name": "Thor - App Manager",
  "package_name": "com.valhalla.thor",
  "min_sdk": 28,
  "target_sdk": 37,
  "short_description": "<paste Step 4 output 1>",
  "detailed_description": "<paste Step 4 output 2>",
  "developer_message": "Thor is built and maintained in the open by one developer, and it will always be free. If it saved you some time, a star on GitHub or a comment below genuinely helps.",
  "icon_url": "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/icon.png",
  "banner_url": "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/featureGraphic.png",
  "screenshots": [
    "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/phoneScreenshots/0.png",
    "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg",
    "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg",
    "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg",
    "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg",
    "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg",
    "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/phoneScreenshots/6.jpg",
    "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/phoneScreenshots/7.jpg",
    "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/phoneScreenshots/8.jpg",
    "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/phoneScreenshots/9.jpg"
  ],
  "repo_url": "https://github.com/trinadhthatakula/Thor",
  "download_url": "https://github.com/trinadhthatakula/Thor/releases/latest/download/foss-release.apk",
  "changelog": "<paste Step 4 output 3>",
  "store_issue_number": 279,
  "category": "Tools",
  "license": "GPL-3.0-or-later",
  "open_source": true,
  "requires_shizuku": true,
  "ad": false,
  "donate_url": "https://www.patreon.com/trinadh",
  "tags": [
    "shizuku",
    "root",
    "dhizuku",
    "app-manager",
    "debloat",
    "freeze",
    "uninstall",
    "apk-installer",
    "split-apk",
    "package-manager",
    "foss",
    "material-you",
    "jetpack-compose",
    "kotlin",
    "device-owner"
  ],
  "developer": {
    "name": "Trinadh Thatakula",
    "username": "trinadhthatakula",
    "account_url": "https://github.com/trinadhthatakula",
    "socials": {
      "github": "https://github.com/trinadhthatakula",
      "telegram": "https://t.me/thorAppDev"
    }
  }
}
```

Note what is deliberately absent: `version_name`, `version_code`, `min_shizuku_version` (no floor is enforced anywhere in the code, so any number would be invented), `app_website` (none exists distinct from `repo_url`), `ads`, and every `developer` field beyond the four present — no `email`, `website`, `portfolio`, or developer `banner_url`.

- [ ] **Step 6: Validate against the vendored schema**

```bash
check-jsonschema --schemafile .github/shizu_store.schema.json shizu_store.json
```

Expected: `ok -- validation done`.

- [ ] **Step 7: Confirm the embedded copy matches the files**

```bash
[ "$(jq -r .detailed_description shizu_store.json)" = "$(cat fastlane/metadata/android/en-US/full_description.txt)" ] && echo DETAILED_OK
[ "$(jq -r .short_description shizu_store.json)" = "$(cat fastlane/metadata/android/en-US/short_description.txt)" ] && echo SHORT_OK
[ "$(jq -r .changelog shizu_store.json)" = "$(cat release-notes/v1.93.0/playstore.txt)" ] && echo CHANGELOG_OK
```

Expected: all three `_OK` lines. `$(...)` strips trailing newlines on both sides of each comparison, which is what makes this robust against the two fastlane files disagreeing about them.

- [ ] **Step 8: Confirm the version fields are absent and the tag count is at the limit**

```bash
jq -e 'has("version_name") or has("version_code")' shizu_store.json && echo "FAIL: version fields present" || echo VERSION_FIELDS_ABSENT
jq -r '.tags|length' shizu_store.json
```

Expected: `VERSION_FIELDS_ABSENT`, then `15`.

- [ ] **Step 9: Commit**

```bash
git add .github/shizu_store.schema.json shizu_store.json
git commit -m "feat(store): add the Shizu CoreFetch manifest

Shizu CoreFetch reads shizu_store.json from the root of the default
branch and uses it to override the defaults it would otherwise derive
from GitHub repository data.

version_name and version_code are omitted on purpose. The store already
reads them from the release, download_url always serves the newest one,
and no workflow can write to master anyway — the CodePush ruleset
requires a pull request and a build-and-test check that a bot-authored
PR would never trigger. Omitting them makes staleness unrepresentable
rather than merely unlikely.

The schema is vendored so validation stays deterministic and upstream
changes arrive as a reviewable diff.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Write the local checker

The store's response to a broken manifest is silence: it ignores the file, falls back to bare GitHub data, and tells nobody. Every check here converts one silent failure into a loud one.

This task covers the checks that need no network. Task 8 adds `--network`.

Note the script collects failures rather than exiting on the first one. A run that reports every problem at once is worth more than one that reports the first — drift usually arrives in batches, because one edit to `full_description.txt` breaks the English check and the length check together.

**Files:**
- Create: `.github/scripts/check-shizu-manifest.sh`

**Interfaces:**
- Consumes: `shizu_store.json` from Task 5, the fastlane files from Tasks 2–3, `gradle.properties`, `gradle/libs.versions.toml`, `release-notes/v*/playstore.txt`.
- Produces: an executable script, exit 0 when everything matches, exit 1 with a report otherwise, exit 2 when a required tool is missing. Tasks 8, 10 invoke it.

- [ ] **Step 1: Write the script**

Create `.github/scripts/check-shizu-manifest.sh`:

```bash
#!/usr/bin/env bash
# Verify shizu_store.json still describes reality.
#
# The Shizu CoreFetch store ignores a manifest it cannot parse or validate and
# silently falls back to default GitHub repository data. Nothing reports the
# failure, so every assertion here exists to make one silent failure loud.
#
# Usage: .github/scripts/check-shizu-manifest.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 2

MANIFEST="shizu_store.json"
SCHEMA=".github/shizu_store.schema.json"
FASTLANE="fastlane/metadata/android"
SHOTS="$FASTLANE/en-US/images/phoneScreenshots"
RAW_BASE="https://raw.githubusercontent.com/trinadhthatakula/Thor/master"

failures=0
fail()    { printf '  FAIL %s\n' "$*" >&2; failures=$((failures + 1)); }
ok()      { printf '  ok   %s\n' "$*"; }
section() { printf '\n== %s ==\n' "$*"; }

need() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'missing required tool: %s\n' "$1" >&2
    printf 'install: brew install %s  (or pipx install %s)\n' "$1" "$1" >&2
    exit 2
  }
}
need jq
need check-jsonschema

# hi lives in fastlane as hi-IN; the other manifest locales have no fastlane
# counterpart and are checked for length and presence only.
fastlane_dir_for_locale() {
  case "$1" in
    hi) printf 'hi-IN' ;;
    *)  printf '' ;;
  esac
}

section "schema"
if check-jsonschema --schemafile "$SCHEMA" "$MANIFEST" >/dev/null 2>&1; then
  ok "validates against $SCHEMA"
else
  fail "does not validate against $SCHEMA"
  check-jsonschema --schemafile "$SCHEMA" "$MANIFEST" >&2
fi

section "omitted by design"
if jq -e 'has("version_name") or has("version_code")' "$MANIFEST" >/dev/null; then
  fail "version_name/version_code must stay absent — the store reads them from the release, and no workflow can push to master to keep them current"
else
  ok "version_name and version_code absent"
fi

section "copy matches fastlane"
compare_text() {
  # $1 label, $2 jq filter, $3 file
  local label="$1" filter="$2" file="$3" from_json from_file
  if [ ! -f "$file" ]; then fail "$label: $file not found"; return; fi
  from_json="$(jq -r "$filter" "$MANIFEST")"
  from_file="$(cat "$file")"
  if [ "$from_json" = "$from_file" ]; then
    ok "$label matches $file"
  else
    fail "$label has drifted from $file"
    diff <(printf '%s\n' "$from_file") <(printf '%s\n' "$from_json") >&2 || true
  fi
}
compare_text "short_description"    '.short_description'    "$FASTLANE/en-US/short_description.txt"
compare_text "detailed_description" '.detailed_description' "$FASTLANE/en-US/full_description.txt"

for loc in $(jq -r 'if has("locales") then .locales | keys[] else empty end' "$MANIFEST"); do
  dir="$(fastlane_dir_for_locale "$loc")"
  [ -n "$dir" ] || continue
  compare_text "locales.$loc.short_description"    ".locales.\"$loc\".short_description"    "$FASTLANE/$dir/short_description.txt"
  compare_text "locales.$loc.detailed_description" ".locales.\"$loc\".detailed_description" "$FASTLANE/$dir/full_description.txt"
done

section "short_description length"
# jq's length counts Unicode codepoints, which is what Google Play limits.
# A byte count would wrongly reject Devanagari and Arabic.
while read -r label len; do
  if [ "$len" -le 80 ]; then ok "$label is $len chars"; else fail "$label is $len chars, over Play's 80"; fi
done < <(jq -r '["en", (.short_description|length)], (if has("locales") then (.locales|to_entries[]|[.key, (.value.short_description|length)]) else empty end) | @tsv' "$MANIFEST")

section "screenshots match the directory"
manifest_shots="$(jq -r '.screenshots[]' "$MANIFEST" | sed 's#.*/##' | sort)"
actual_shots="$(ls "$SHOTS" | sort)"
if [ "$manifest_shots" = "$actual_shots" ]; then
  ok "$(printf '%s' "$actual_shots" | wc -l | tr -d ' ') screenshots listed, matching $SHOTS"
else
  fail "screenshots array does not match $SHOTS"
  diff <(printf '%s\n' "$actual_shots") <(printf '%s\n' "$manifest_shots") >&2 || true
fi

bad_prefix="$(jq -r --arg b "$RAW_BASE" '.screenshots[] | select(startswith($b) | not)' "$MANIFEST")"
if [ -z "$bad_prefix" ]; then
  ok "every screenshot URL is pinned to master"
else
  fail "screenshot URLs not pinned to $RAW_BASE:"
  printf '    %s\n' $bad_prefix >&2
fi

section "sdk versions"
check_int() {
  # $1 manifest key, $2 expected value, $3 source description
  local got; got="$(jq -r ".$1" "$MANIFEST")"
  if [ "$got" = "$2" ]; then ok "$1 = $got (matches $3)"; else fail "$1 = $got but $3 says $2"; fi
}
toml_min="$(grep -E '^minSdk = ' gradle/libs.versions.toml | tr -dc '0-9')"
toml_target="$(grep -E '^targetSdk = ' gradle/libs.versions.toml | tr -dc '0-9')"
check_int min_sdk    "$toml_min"    "gradle/libs.versions.toml"
check_int target_sdk "$toml_target" "gradle/libs.versions.toml"

section "changelog matches the current release notes"
# Anchored on purpose: an unanchored 'versionCode' also matches
# initialVersionCode=1921, which is the bug that made release-manager.yml
# unusable — two lines fed into arithmetic.
version_code="$(grep -E '^versionCode=' gradle.properties | cut -d= -f2 | tr -dc '0-9')"
if [ -z "$version_code" ]; then
  fail "could not read versionCode from gradle.properties"
else
  version_name="$((version_code / 1000)).$(((version_code % 1000) / 10)).$((version_code % 10))"
  notes="release-notes/v$version_name/playstore.txt"
  [ -f "$notes" ] || notes="release-notes/$version_name/playstore.txt"
  if [ ! -f "$notes" ]; then
    fail "no playstore.txt for v$version_name (versionCode $version_code)"
  else
    compare_text "changelog" '.changelog' "$notes"
    printf '       (versionCode %s -> v%s)\n' "$version_code" "$version_name"
  fi
fi

printf '\n'
if [ "$failures" -eq 0 ]; then
  printf 'shizu_store.json: all checks passed\n'
  exit 0
fi
printf 'shizu_store.json: %d check(s) failed\n' "$failures" >&2
printf 'if only the changelog drifted, run: .github/scripts/sync-shizu-changelog.sh\n' >&2
exit 1
```

- [ ] **Step 2: Make it executable and run it**

```bash
chmod +x .github/scripts/check-shizu-manifest.sh
.github/scripts/check-shizu-manifest.sh
```

Expected: every line prefixed `ok`, ending with `shizu_store.json: all checks passed`, exit 0. Confirm with `echo $?` → `0`.

- [ ] **Step 3: Prove the checker actually catches drift**

A checker that has never failed is not known to work. Break one thing at a time and confirm it is caught:

```bash
# 3a. copy drift
printf '\nDRIFT\n' >> fastlane/metadata/android/en-US/full_description.txt
.github/scripts/check-shizu-manifest.sh; echo "exit=$?"
git checkout -- fastlane/metadata/android/en-US/full_description.txt

# 3b. a reintroduced version field
jq '. + {version_code: 1930}' shizu_store.json > /tmp/m.json && cp /tmp/m.json shizu_store.json
.github/scripts/check-shizu-manifest.sh; echo "exit=$?"
git checkout -- shizu_store.json

# 3c. a stray key the schema forbids
jq '. + {totally_invented: true}' shizu_store.json > /tmp/m.json && cp /tmp/m.json shizu_store.json
.github/scripts/check-shizu-manifest.sh; echo "exit=$?"
git checkout -- shizu_store.json

# 3d. a screenshot listed but not present
jq '.screenshots += ["https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/phoneScreenshots/99.jpg"]' \
  shizu_store.json > /tmp/m.json && cp /tmp/m.json shizu_store.json
.github/scripts/check-shizu-manifest.sh; echo "exit=$?"
git checkout -- shizu_store.json
```

Expected: `exit=1` for all four, each naming the specific problem — `detailed_description has drifted`, `version_name/version_code must stay absent`, `does not validate`, `screenshots array does not match`.

- [ ] **Step 4: Confirm the tree is clean again**

```bash
git status --porcelain
```

Expected: only `.github/scripts/check-shizu-manifest.sh` as untracked. If `shizu_store.json` or a fastlane file still shows as modified, a `git checkout --` in Step 3 was missed.

- [ ] **Step 5: Commit**

```bash
git add .github/scripts/check-shizu-manifest.sh
git commit -m "ci(store): add the shizu manifest checker

The manifest restates facts that live elsewhere — the fastlane copy, the
screenshot directory, the SDK versions, the release notes — so it can
drift, and a drifted manifest fails invisibly because the store just
ignores it. These assertions make each drift a red build instead.

Local checks only; the network tier follows.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Add the `locales` block

Five locales: `ar`, `es`, `fr`, `hi`, `zh`. `en` is the top-level base and must **not** appear under `locales`. Each entry carries `short_description`, `detailed_description`, and `developer_message`.

`hi` is copied from the fastlane files rewritten in Task 3, so the checker verifies it. The other four exist only here and cannot be verified by CI beyond length and presence — which is why each is written and then read back against the English before committing.

`changelog` is deliberately not translated even though the schema allows it per-locale. A translated changelog must be regenerated every release or it silently shows non-English users the previous version's notes.

**Files:**
- Modify: `shizu_store.json` (add one top-level `locales` key)

**Interfaces:**
- Consumes: `shizu_store.json` from Task 5; the Hindi files from Task 3.
- Produces: `locales` with exactly the keys `ar, es, fr, hi, zh`, each an object with exactly `short_description`, `detailed_description`, `developer_message`. Task 6's checker already iterates `locales` and will begin exercising the `hi` comparison and the per-locale length check.

#### Terminology

These are the app's own translations, read from `app/src/main/res/values-{ar,es,fr,zh-rCN}/strings.xml`. The listing must use them so a user who installs Thor sees the words the listing promised. Note the store's `zh` maps to the app's `zh-rCN`.

| English | `ar` | `es` | `fr` | `zh` |
|---|---|---|---|---|
| Freeze | تجميد | Congelar | Geler | 冻结 |
| Unfreeze | إلغاء التجميد | Descongelar | Dégeler | 解冻 |
| Suspend | تعليق | Suspender | Suspendre | 暂停 |
| Reinstall | إعادة تثبيت | Reinstalar | Réinstaller | 重新安装 |
| Freezer | المجمد | Congelador | Congélateur | 应用冻结 |
| System apps | تطبيقات النظام | Aplicaciones del sistema | Applis système | 系统应用 |
| Extensions | الإضافات | Extensiones | Extensions | 扩展 |

Product names are never translated: `Thor`, `Shizuku`, `Root`, `Dhizuku`, `Odin`, `Material You`, `Jetpack Compose`, `UAD`, and the literal shell fragments `pm uninstall --user` and `pm install-existing`.

- [ ] **Step 1: Draft the five `short_description` values**

Each must be ≤ 80 characters, counted in Unicode codepoints. The English base is `Freeze, debloat & install apps via Shizuku, Root & Dhizuku. Ad-free & FOSS` (74).

| Locale | Text | Chars |
|---|---|---|
| `ar` | `تجميد وإزالة وتثبيت التطبيقات عبر Shizuku وRoot وDhizuku. بلا إعلانات، FOSS` | 75 |
| `es` | `Congela, depura e instala apps con Shizuku, Root y Dhizuku. Sin anuncios, FOSS` | 77 |
| `fr` | `Gelez, allégez et installez vos apps via Shizuku, Root, Dhizuku. Sans pub, FOSS` | 78 |
| `zh` | `通过 Shizuku、Root、Dhizuku 冻结、精简并安装应用。无广告，开源免费` | 43 |
| `hi` | copied verbatim from `fastlane/metadata/android/hi-IN/short_description.txt` | — |

The margins are thin on purpose: these are already the shortest phrasings that keep all three privilege backends named. If a count comes out over 80, shorten the trailing claim (`Sin anuncios, FOSS` → `Sin anuncios`) rather than dropping a backend name.

- [ ] **Step 2: Draft the five `developer_message` values**

The English base, for reference:

> Thor is built and maintained in the open by one developer, and it will always be free. If it saved you some time, a star on GitHub or a comment below genuinely helps.

| Locale | Text |
|---|---|
| `ar` | `يُطوَّر ثور ويُصان علنًا على يد مطوّر واحد، وسيبقى مجانيًا دائمًا. إن وفّر عليك بعض الوقت، فنجمة على GitHub أو تعليق هنا يساعدان فعلًا.` |
| `es` | `Thor lo desarrolla y mantiene en abierto una sola persona, y siempre será gratuito. Si te ha ahorrado tiempo, una estrella en GitHub o un comentario aquí abajo ayudan de verdad.` |
| `fr` | `Thor est développé et maintenu au grand jour par une seule personne, et restera toujours gratuit. S'il vous a fait gagner du temps, une étoile sur GitHub ou un commentaire ci-dessous aident vraiment.` |
| `zh` | `Thor 由一位开发者公开开发和维护，并将始终免费。如果它为你节省了时间，在 GitHub 点个星或在下方留言，都会有实实在在的帮助。` |
| `hi` | `थॉर एक ही डेवलपर द्वारा खुले तौर पर बनाया और मेंटेन किया जाता है, और यह हमेशा मुफ़्त रहेगा। अगर इसने आपका कुछ समय बचाया हो, तो GitHub पर एक स्टार या नीचे एक कमेंट सचमुच मदद करता है।` |

"a comment below" refers to GitHub issue #279, which the store renders as the listing's comment thread. Every translation must keep that deictic sense — a reader who follows it should land on the thread, not go looking for a review box.

- [ ] **Step 3: Draft `locales.es.detailed_description`**

Seven emoji-headed sections in the same order as the English, same bullets, no additions:

```
Thor App Manager es un gestor e instalador de aplicaciones Android ligero y de código abierto, diseñado para usuarios avanzados que quieren un control total de sus dispositivos. Creado 100% en Kotlin con Jetpack Compose y Material 3, Thor ofrece gestión avanzada de paquetes sin rastreadores, sin anuncios y sin telemetría.

⚡ MÚLTIPLES MODOS DE PRIVILEGIO
Thor funciona sin fricciones con distintos motores de privilegio y un sistema de reserva automático. Elige manualmente tu backend activo:
• Root (su)
• Shizuku
• Dhizuku (propietario del dispositivo)

📦 INSTALADOR DE APLICACIONES AVANZADO
Instala, reinstala o repara registros de instalación con facilidad.
• Instalador rediseñado para root, Shizuku o el gestor de paquetes estándar.
• Compatibilidad total con formatos APK divididos: archivos .apkm, .apks y .xapk.
• Reinstalación automática: sincroniza y reinstala tus aplicaciones con opciones de instalación personalizadas.
• Fix Store: reasigna el registro de instalación a Google Play Store en cualquier modo de privilegio.

🚫 ELIMINACIÓN DE BLOATWARE DEL SISTEMA
Depura tu dispositivo con seguridad siguiendo las directrices de Universal Android Debloater (UAD).
• Chips dinámicos de recomendación de seguridad: Recommended, Advanced, Expert, Unsafe.
• Protección automática: bloquea la congelación de paquetes del sistema marcados como «Unsafe» para evitar bucles de reinicio, y advierte en los marcados como «Expert».
• Desinstalaciones limpias: desinstala aplicaciones del sistema para el usuario actual (pm uninstall --user) y las restaura (pm install-existing) sin complicaciones.
• Caché local de iconos e insignias de peligro para paquetes del sistema desinstalados.

🛠️ OPERACIONES POR LOTES Y HERRAMIENTAS AVANZADAS
Actúa sobre varias aplicaciones a la vez con un registro de terminal en tiempo real.
• Congela, descongela, reinstala, desinstala, suspende y borra caché o datos por lotes.
• Fuerza la detención, restringe la actividad en segundo plano o borra caché y datos en cualquier modo.
• Suspende y reactiva aplicaciones con un diálogo del sistema con la marca de Thor.

🧩 GESTOR DE EXTENSIONES
Amplía Thor con complementos opcionales, que puedes explorar e instalar desde un catálogo integrado.
• Cada extensión se verifica por firma y por hash SHA-256 antes de instalarse.
• Totalmente opcional: si no instalas ninguna extensión, Thor nunca accede a la red.

📱 INTERFAZ ADAPTABLE Y PREFERENCIAS PERSISTENTES
Disfruta de una interfaz Material 3 que se adapta a tu dispositivo.
• Pantalla de inicio rediseñada: una cuadrícula bento adaptable con acceso al gestor de extensiones en un toque.
• Diseños multipanel y barra de navegación vertical optimizados para tablets y plegables.
• Preferencias de diseño persistentes: los modos de cuadrícula y lista se conservan entre reinicios.
• Modo negro AMOLED real, temas claro y oscuro, y colores dinámicos de Material You.
• Bloqueo por huella dactilar o biometría para proteger el acceso a la aplicación.
• Selector de idioma integrado con inglés, español, francés, árabe y chino.

🔒 PRIVACIDAD ANTE TODO Y MUY LIGERA
• Sin analíticas, sin informes de fallos, sin anuncios, sin rastreadores. Nunca.
• El único acceso a la red es la tienda de extensiones opcional, que descarga su catálogo y las extensiones verificadas por HTTPS, con firmante fijado y comprobación SHA-256. Todo lo demás funciona completamente sin conexión.
• Código abierto: con licencia GNU GPL v3.0-or-later (software libre).
• Ultraligera: el APK de descarga directa ocupa unos 3 MB.

ASPECTOS TÉCNICOS (optimizado para agentes de IA)
• Plataforma: Android (Root, Shizuku, Dhizuku)
• Arquitectura y stack: Kotlin, Clean Architecture, MVVM, Room DB (caché de metadatos), Jetpack Compose
• Bypass de API ocultas: módulo propio interno, sin dependencias externas
• Ejecución de shell: Odin, motor de shell root en Kotlin publicado en Maven Central

Sin bloat. Sin rastreadores. Sin tonterías. Toma el control de tus aplicaciones con Thor.
```

- [ ] **Step 4: Draft `locales.fr.detailed_description`**

```
Thor App Manager est un gestionnaire et installateur d'applications Android léger et open source, conçu pour les utilisateurs avancés qui veulent garder la main sur leur appareil. Écrit à 100 % en Kotlin avec Jetpack Compose et Material 3, Thor offre une gestion de paquets avancée, sans traqueurs, sans publicité et sans télémétrie.

⚡ PLUSIEURS MODES DE PRIVILÈGE
Thor s'adapte à différents moteurs de privilège, avec un système de repli automatique. Choisissez manuellement le backend actif :
• Root (su)
• Shizuku
• Dhizuku (propriétaire de l'appareil)

📦 INSTALLATEUR D'APPLICATIONS AVANCÉ
Installez, réinstallez ou réparez les enregistrements d'installation en toute simplicité.
• Installateur repensé pour root, Shizuku ou le gestionnaire de paquets standard.
• Prise en charge complète des APK découpés : fichiers .apkm, .apks et .xapk.
• Réinstallation automatique : synchronisez et réinstallez vos applications avec vos propres options d'installation.
• Fix Store : réattribuez l'enregistrement d'installation au Google Play Store, dans n'importe quel mode de privilège.

🚫 SUPPRESSION DES APPLICATIONS SYSTÈME SUPERFLUES
Allégez votre appareil en toute sécurité, en suivant les recommandations d'Universal Android Debloater (UAD).
• Pastilles de sécurité dynamiques : Recommended, Advanced, Expert, Unsafe.
• Garde-fou automatique : le gel des paquets système marqués « Unsafe » est bloqué pour éviter les boucles de démarrage, et ceux marqués « Expert » déclenchent un avertissement.
• Désinstallations propres : désinstalle les applis système pour l'utilisateur courant (pm uninstall --user) et les restaure (pm install-existing) sans effort.
• Cache d'icônes local et badges d'avertissement pour les paquets système désinstallés.

🛠️ ACTIONS PAR LOT ET OUTILS AVANCÉS
Agissez sur plusieurs applications à la fois, avec un journal de terminal en temps réel.
• Gelez, dégelez, réinstallez, désinstallez, suspendez et videz le cache ou les données, par lot.
• Forcez l'arrêt, restreignez l'activité en arrière-plan ou videz cache et données, dans n'importe quel mode.
• Suspendez et réactivez des applications via une boîte de dialogue système aux couleurs de Thor.

🧩 GESTIONNAIRE D'EXTENSIONS
Étendez Thor avec des modules optionnels, à parcourir et installer depuis un catalogue intégré.
• Chaque extension est vérifiée par signature et par empreinte SHA-256 avant installation.
• Entièrement facultatif : n'installez aucune extension et Thor n'accède jamais au réseau.

📱 INTERFACE ADAPTATIVE ET PRÉFÉRENCES PERSISTANTES
Profitez d'une interface Material 3 qui s'adapte à votre appareil.
• Écran d'accueil repensé : une grille bento adaptative, avec le gestionnaire d'extensions à portée d'un appui.
• Dispositions multi-panneaux et rail de navigation vertical, optimisés pour tablettes et pliables.
• Préférences d'affichage persistantes : les modes grille et liste sont conservés d'une session à l'autre.
• Vrai noir AMOLED, thèmes clair et sombre, et couleurs dynamiques Material You.
• Verrouillage par empreinte digitale ou biométrie pour protéger l'accès à l'application.
• Sélecteur de langue intégré : anglais, espagnol, français, arabe et chinois.

🔒 RESPECT DE LA VIE PRIVÉE ET LÉGÈRETÉ
• Aucune analytique, aucun rapport de plantage, aucune publicité, aucun traqueur. Jamais.
• Le seul accès réseau est la boutique d'extensions facultative, qui récupère son catalogue et les extensions vérifiées en HTTPS, avec signataire épinglé et contrôle SHA-256. Tout le reste fonctionne entièrement hors ligne.
• Open source : sous licence GNU GPL v3.0-or-later (logiciel libre).
• Ultra-léger : l'APK en téléchargement direct pèse environ 3 Mo.

POINTS TECHNIQUES (optimisé pour les agents IA)
• Plateforme : Android (Root, Shizuku, Dhizuku)
• Architecture et stack : Kotlin, Clean Architecture, MVVM, Room DB (cache des métadonnées), Jetpack Compose
• Contournement des API cachées : module interne maison, sans dépendance externe
• Exécution shell : Odin, moteur de shell root en Kotlin publié sur Maven Central

Pas de superflu. Pas de traqueurs. Pas de bêtises. Reprenez le contrôle de vos applications avec Thor.
```

- [ ] **Step 5: Draft `locales.ar.detailed_description`**

Arabic is right-to-left, so keep the `•` and the emoji at the **start of each line in logical order** — exactly as written below. Do not insert directional-override characters; the store renders the plain text and its own layout handles direction. Latin tokens (`Shizuku`, `pm uninstall --user`, `Material You`) stay Latin and stay in logical position.

```
ثور (Thor) هو مدير ومثبِّت تطبيقات أندرويد خفيف ومفتوح المصدر، صُمِّم للمستخدمين المتقدمين الذين يريدون تحكمًا كاملًا في أجهزتهم. مكتوب بلغة Kotlin بنسبة 100% باستخدام Jetpack Compose وMaterial 3، ويوفّر ثور إدارة متقدمة للحزم بلا متتبّعات ولا إعلانات ولا قياس عن بُعد.

⚡ أوضاع صلاحيات متعددة
يعمل ثور بسلاسة مع محرّكات صلاحيات مختلفة، مع نظام تراجع تلقائي. اختر الواجهة الخلفية النشطة يدويًا:
• Root (su)
• Shizuku
• Dhizuku (مالك الجهاز)

📦 مثبِّت تطبيقات متقدم
ثبّت التطبيقات أو أعد تثبيتها أو أصلح سجلّات التثبيت بسهولة.
• مثبِّت أُعيد تصميمه للعمل مع Root أو Shizuku أو مدير الحزم القياسي.
• دعم كامل لصيغ APK المجزّأة: ملفات ‎.apkm‎ و‎.apks‎ و‎.xapk‎.
• إعادة التثبيت التلقائية: زامِن تطبيقاتك وأعد تثبيتها بخيارات تثبيت مخصّصة.
• Fix Store: أعد إسناد سجل التثبيت إلى Google Play Store في أي وضع صلاحيات.

🚫 إزالة تطبيقات النظام غير الضرورية
نظّف جهازك بأمان وفق إرشادات Universal Android Debloater (UAD).
• شارات أمان ديناميكية: Recommended وAdvanced وExpert وUnsafe.
• حماية تلقائية: يمنع تجميد حزم النظام المصنّفة Unsafe تفاديًا لحلقات الإقلاع، وينبّه عند الحزم المصنّفة Expert.
• إلغاء تثبيت نظيف: يلغي تثبيت تطبيقات النظام للمستخدم الحالي (pm uninstall --user) ويستعيدها (pm install-existing) بسهولة.
• تخزين مؤقت محلي للأيقونات وشارات تحذير لحزم النظام التي أُلغي تثبيتها.

🛠️ عمليات مجمّعة وأدوات متقدمة
نفّذ إجراءات على عدة تطبيقات دفعة واحدة، مع سجل طرفية لحظي.
• تجميد وإلغاء التجميد وإعادة التثبيت وإلغاء التثبيت والتعليق ومسح ذاكرة التخزين المؤقت أو البيانات، دفعةً واحدة.
• إيقاف قسري، أو تقييد النشاط في الخلفية، أو مسح ذاكرة التخزين المؤقت والبيانات في أي وضع.
• علّق التطبيقات وأعد تفعيلها عبر مربّع حوار نظام يحمل هوية ثور.

🧩 مدير الإضافات
وسّع ثور بإضافات اختيارية، تتصفّحها وتثبّتها من كتالوج مدمج.
• كل إضافة يُتحقق من توقيعها ومن بصمة SHA-256 قبل تثبيتها.
• اختياري بالكامل: لا تثبّت أي إضافة ولن يتصل ثور بالشبكة إطلاقًا.

📱 واجهة متكيّفة وتفضيلات دائمة
استمتع بواجهة Material 3 تتكيّف مع جهازك.
• شاشة رئيسية أُعيد تصميمها: شبكة bento متكيّفة، ومدير الإضافات على بُعد نقرة واحدة.
• تخطيطات متعددة اللوحات وشريط تنقّل عمودي، محسّنة للأجهزة اللوحية والقابلة للطي.
• تفضيلات عرض دائمة: يُحتفظ بوضعي الشبكة والقائمة بعد إعادة التشغيل.
• أسود AMOLED حقيقي، وسمات فاتحة وداكنة، وألوان Material You الديناميكية.
• قفل ببصمة الإصبع أو بالسمات الحيوية لحماية الوصول إلى التطبيق.
• مبدّل لغة مدمج: الإنجليزية والإسبانية والفرنسية والعربية والصينية.

🔒 الخصوصية أولًا وخفة في الحجم
• لا تحليلات، ولا تقارير أعطال، ولا إعلانات، ولا متتبّعات. أبدًا.
• الوصول الوحيد إلى الشبكة هو متجر الإضافات الاختياري، الذي يجلب كتالوجه والإضافات الموثّقة عبر HTTPS، مع مُوقِّع مثبّت وتحقق من بصمة SHA-256. وكل ما عدا ذلك يعمل دون اتصال تمامًا.
• مفتوح المصدر: مرخّص بموجب GNU GPL v3.0-or-later (برمجيات حرة).
• خفيف للغاية: حجم ملف APK للتنزيل المباشر نحو 3 ميجابايت.

أبرز النقاط التقنية (محسّنة لوكلاء الذكاء الاصطناعي)
• المنصة: أندرويد (Root، Shizuku، Dhizuku)
• البنية والتقنيات: Kotlin، Clean Architecture، MVVM، Room DB (تخزين مؤقت للبيانات الوصفية)، Jetpack Compose
• تجاوز واجهات API المخفية: وحدة داخلية خاصة، بلا اعتماد على مكتبات خارجية
• تنفيذ الأوامر: Odin، محرّك صدفة جذر مكتوب بلغة Kotlin ومنشور على Maven Central

بلا حشو. بلا متتبّعات. بلا تعقيد. تحكّم في تطبيقاتك مع ثور.
```

- [ ] **Step 6: Draft `locales.zh.detailed_description`**

Simplified Chinese, matching the app's `values-zh-rCN` terminology.

```
Thor App Manager 是一款轻量、开源的 Android 应用管理器与安装器，为希望完全掌控自己设备的进阶用户而生。它以 100% Kotlin 编写，采用 Jetpack Compose 与 Material 3，在没有跟踪器、没有广告、没有遥测的前提下提供进阶的软件包管理能力。

⚡ 多种授权模式
Thor 可在不同的授权引擎之间无缝工作，并具备自动回退机制。你也可以手动指定当前使用的后端：
• Root（su）
• Shizuku
• Dhizuku（设备所有者）

📦 进阶应用安装器
轻松安装、重新安装或修复安装来源记录。
• 重新设计的安装器，支持 Root、Shizuku 或系统自带的包管理器。
• 完整支持分包 APK 格式：.apkm、.apks 与 .xapk 文件。
• 自动重装：同步并按自定义安装选项重新安装你的应用。
• Fix Store：在任意授权模式下，将安装来源记录改回 Google Play Store。

🚫 系统应用精简
参照 Universal Android Debloater（UAD）的建议，安全地精简你的设备。
• 动态安全建议标签：Recommended、Advanced、Expert、Unsafe。
• 自动防护：阻止冻结被标记为 Unsafe 的系统应用以避免无限重启，并在 Expert 应用上给出警告。
• 干净卸载：为当前用户卸载系统应用（pm uninstall --user），并可随时恢复（pm install-existing）。
• 本地图标缓存，并为已卸载的系统应用标注风险徽章。

🛠️ 批量操作与进阶工具
借助实时终端日志对话框，一次处理多个应用。
• 批量冻结、解冻、重新安装、卸载、暂停以及清除缓存或数据。
• 在任意模式下强制停止、限制后台活动，或清除应用的缓存与数据。
• 通过带有 Thor 品牌样式的系统对话框暂停与恢复应用。

🧩 扩展管理器
从应用内目录浏览并安装可选扩展，按需增强 Thor。
• 每个扩展在安装前都会经过签名验证与 SHA-256 校验。
• 完全可选：不安装任何扩展，Thor 就永远不会访问网络。

📱 自适应界面与偏好保留
享受可随设备变化的 Material 3 界面。
• 全新主页：自适应便当式网格布局，一键直达扩展管理器。
• 为平板与折叠屏优化的多窗格布局和垂直导航栏。
• 布局偏好保留：网格与列表视图的设置在重启后依然保持。
• 纯黑 AMOLED 模式、浅色/深色主题，以及 Material You 动态取色。
• 指纹／生物识别锁，保护应用访问。
• 内置语言切换：英语、西班牙语、法语、阿拉伯语和中文。

🔒 隐私优先，体积轻巧
• 没有统计分析，没有崩溃上报，没有广告，没有跟踪器——从来没有。
• 唯一的网络访问来自可选的扩展商店：它通过 HTTPS 获取目录与经过验证的扩展 APK，并进行签名固定与 SHA-256 校验。其余功能完全离线运行。
• 开源：基于 GNU GPL v3.0-or-later 授权（自由软件）。
• 极致轻量：直接下载的 APK 约 3 MB。

技术要点（面向 AI 代理优化）
• 目标平台：Android（Root、Shizuku、Dhizuku）
• 架构与技术栈：Kotlin、Clean Architecture、MVVM、Room DB（元数据缓存）、Jetpack Compose
• 隐藏 API 绕过：自研内部模块，不依赖外部库
• Shell 执行：Odin，自研 Kotlin root shell 引擎，已发布至 Maven Central

没有臃肿。没有跟踪。没有废话。用 Thor 掌控你的应用。
```

- [ ] **Step 7: Assemble the `locales` block into the manifest**

Add a single top-level `locales` key to `shizu_store.json`, after `developer`. `hi` reuses the Task 3 files, so generate its two long values rather than retyping them:

```bash
echo '--- hi.short_description ---'
jq -Rs 'rtrimstr("\n")' < fastlane/metadata/android/hi-IN/short_description.txt
echo '--- hi.detailed_description ---'
jq -Rs 'rtrimstr("\n")' < fastlane/metadata/android/hi-IN/full_description.txt
```

The block has exactly this shape — five locales, three keys each, no `en`:

```json
"locales": {
  "ar": { "short_description": "…", "detailed_description": "…", "developer_message": "…" },
  "es": { "short_description": "…", "detailed_description": "…", "developer_message": "…" },
  "fr": { "short_description": "…", "detailed_description": "…", "developer_message": "…" },
  "hi": { "short_description": "…", "detailed_description": "…", "developer_message": "…" },
  "zh": { "short_description": "…", "detailed_description": "…", "developer_message": "…" }
}
```

- [ ] **Step 8: Verify the structure**

```bash
jq -e '.locales | keys == ["ar","es","fr","hi","zh"]' shizu_store.json && echo LOCALE_KEYS_OK
jq -e '[.locales[] | keys == ["detailed_description","developer_message","short_description"]] | all' shizu_store.json && echo LOCALE_SHAPE_OK
jq -e '.locales | has("en") | not' shizu_store.json && echo NO_EN_LOCALE
jq -r '.locales | to_entries[] | "\(.key) short=\(.value.short_description|length) detailed=\(.value.detailed_description|length)"' shizu_store.json
```

Expected: `LOCALE_KEYS_OK`, `LOCALE_SHAPE_OK`, `NO_EN_LOCALE`, then five lines with every `short` ≤ 80 and every `detailed` over 1000. `jq`'s `keys` sorts, which is why the shape check lists the three keys alphabetically.

- [ ] **Step 9: Verify each translation has the same seven sections as the English**

A translation that silently dropped a section would pass every check so far.

```bash
for l in ar es fr hi zh; do
  printf '%s: %s sections\n' "$l" \
    "$(jq -r ".locales.\"$l\".detailed_description" shizu_store.json | grep -cE '^(⚡|📦|🚫|🛠️|🧩|📱|🔒)')"
done
```

Expected: all five print `7`.

- [ ] **Step 10: Run the checker**

```bash
.github/scripts/check-shizu-manifest.sh
```

Expected: all `ok`, exit 0. This run now also exercises the `hi` comparison against the fastlane files and the per-locale length check, which had nothing to test before this task.

- [ ] **Step 11: Read the translations back against the English**

Not a command — a review pass. For each of `ar`, `es`, `fr`, `zh`, read the translation beside the English and confirm:

- No sentence claims Thor is fully offline or free of internet permissions. A mistranslated privacy claim is the same problem as an untrue English one, and it is the failure this whole change exists to fix.
- The language-switcher line names exactly English, Spanish, French, Arabic, Chinese — no Hindi.
- The size reads as roughly 3 MB, with no two-decimal figure.
- `Thor`, `Shizuku`, `Root`, `Dhizuku`, `Odin`, `Fix Store`, `Material You`, `Jetpack Compose`, `UAD`, `pm uninstall --user`, and `pm install-existing` are untranslated.
- Action verbs match the terminology table above, not a synonym.

- [ ] **Step 12: Commit**

```bash
git add shizu_store.json
git commit -m "feat(store): translate the listing into ar, es, fr, hi and zh

Terminology is taken from the app's own strings.xml so the listing and
the UI agree on freeze, suspend and reinstall. The store's zh maps to
the app's zh-rCN.

en stays top-level rather than appearing under locales, and changelog is
not translated: a per-locale changelog has to be regenerated every
release or it quietly shows non-English users the previous version's
notes.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: Add the network tier to the checker

This is the only tier that catches rot originating outside the repository — a renamed screenshot, a deleted release, a dead donate link, a tightened upstream schema — which is most of what can actually break the listing.

The schema is checked two ways on purpose. The diff says *what* changed upstream; validating against the live schema says whether that change breaks us. Running only the diff would mean reading every upstream edit to work out whether it matters. Running only the live validation would mean a red build with no indication of what moved.

**Files:**
- Modify: `.github/scripts/check-shizu-manifest.sh`

**Interfaces:**
- Consumes: the script from Task 6.
- Produces: `--network` flag support. Task 10's workflow calls `check-shizu-manifest.sh --network`.

- [ ] **Step 1: Add flag parsing**

Replace this block near the top of the script:

```bash
failures=0
```

with:

```bash
NETWORK=0
case "${1:-}" in
  --network) NETWORK=1 ;;
  "")        ;;
  *)         printf 'usage: %s [--network]\n' "$0" >&2; exit 2 ;;
esac

failures=0
```

And extend the tool check, replacing:

```bash
need jq
need check-jsonschema
```

with:

```bash
need jq
need check-jsonschema
[ "$NETWORK" -eq 1 ] && need curl
```

- [ ] **Step 2: Add the network section**

Insert this immediately before the final summary block (the `printf '\n'` followed by `if [ "$failures" -eq 0 ]`):

```bash
if [ "$NETWORK" -eq 1 ]; then
  UPSTREAM_SCHEMA_URL="https://docshizu.siwane.xyz/schema.json"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT

  section "image urls"
  # Images must be exactly 200. These are raw.githubusercontent.com URLs with
  # no redirects and no bot protection, so anything else is a real failure.
  # A `for` loop, not a pipe into `while`: a pipeline runs its body in a
  # subshell, where every fail() would increment a copy of $failures that dies
  # with the subshell. URLs contain no whitespace, so word splitting is safe.
  for u in $(jq -r '[.icon_url, .banner_url, (.screenshots[])] | .[]' "$MANIFEST"); do
    code="$(curl -sSL --max-time 25 -o /dev/null -w '%{http_code}' "$u" 2>/dev/null)"
    if [ "$code" = "200" ]; then ok "$code  ${u##*/}"; else fail "image not served: $code $u"; fi
  done

  section "link urls"
  # A 403 or 429 from a site with bot protection is not a dead link, and
  # failing on it would make the weekly audit cry wolf until nobody reads it —
  # which is the exact failure this audit exists to prevent.
  for u in $(jq -r '[.repo_url, .donate_url, .developer.account_url, (.developer.socials // {} | .[])] | map(select(. != null)) | unique | .[]' "$MANIFEST"); do
    code="$(curl -sSL --max-time 25 -A 'thor-shizu-audit/1' -o /dev/null -w '%{http_code}' "$u" 2>/dev/null)"
    case "$code" in
      403|429) ok   "$code  $u  (bot protection, treated as reachable)" ;;
      2??|3??) ok   "$code  $u" ;;
      *)       fail "$code  $u" ;;
    esac
  done

  section "download_url"
  dl="$(jq -r .download_url "$MANIFEST")"
  final="$(curl -sIL --max-time 40 -o /dev/null -w '%{http_code} %{url_effective}' "$dl" 2>/dev/null)"
  dl_code="${final%% *}"
  dl_url="${final#* }"
  if [ "$dl_code" = "200" ]; then
    ok "download_url resolves ($dl_code)"
  else
    fail "download_url returned $dl_code"
  fi
  case "$dl_url" in
    *foss-release.apk*) ok "resolves to the foss artifact" ;;
    *) fail "resolves to something other than foss-release.apk: $dl_url" ;;
  esac

  section "upstream schema"
  if curl -fsSL --max-time 25 "$UPSTREAM_SCHEMA_URL" -o "$tmp/upstream.json"; then
    if check-jsonschema --schemafile "$tmp/upstream.json" "$MANIFEST" >/dev/null 2>&1; then
      ok "manifest validates against the LIVE schema"
    else
      fail "manifest does NOT validate against the live schema — the store is rejecting this file right now"
      check-jsonschema --schemafile "$tmp/upstream.json" "$MANIFEST" >&2
    fi
    if diff -u "$SCHEMA" "$tmp/upstream.json" > "$tmp/schema.diff"; then
      ok "vendored schema is identical to upstream"
    else
      fail "vendored schema differs from upstream — review and re-vendor:"
      cat "$tmp/schema.diff" >&2
    fi
  else
    fail "could not fetch $UPSTREAM_SCHEMA_URL"
  fi
fi
```

- [ ] **Step 3: Run it**

```bash
.github/scripts/check-shizu-manifest.sh --network
```

Expected: exit 0, with a `200` beside all twelve image URLs (icon, banner, ten screenshots), reachable results for the four link URLs, `download_url resolves (200)`, `resolves to the foss artifact`, `manifest validates against the LIVE schema`, and `vendored schema is identical to upstream`.

Note that `--max-time 25` bounds each request, so the worst case for sixteen URLs is bounded rather than open-ended — a hung endpoint slows the audit, it does not hang it.

If the schema diff fires on this very first run, upstream changed between Task 5 and now. Re-vendor with the Step 2 command from Task 5, re-read the affected constraints, and re-run.

- [ ] **Step 4: Prove the network checks fail when they should**

```bash
# 4a. a screenshot that does not exist
jq '.screenshots[0] = "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/phoneScreenshots/nope.png"' \
  shizu_store.json > /tmp/m.json && cp /tmp/m.json shizu_store.json
.github/scripts/check-shizu-manifest.sh --network; echo "exit=$?"
git checkout -- shizu_store.json

# 4b. the wrong release artifact
jq '.download_url = "https://github.com/trinadhthatakula/Thor/releases/latest/download/store-release.apk"' \
  shizu_store.json > /tmp/m.json && cp /tmp/m.json shizu_store.json
.github/scripts/check-shizu-manifest.sh --network; echo "exit=$?"
git checkout -- shizu_store.json

# 4c. a stale vendored schema
printf '\n' >> .github/shizu_store.schema.json
.github/scripts/check-shizu-manifest.sh --network; echo "exit=$?"
git checkout -- .github/shizu_store.schema.json
```

Expected: `exit=1` for all three. 4a must report both `image not served: 404` and the local `screenshots array does not match` check; 4b must report `resolves to something other than foss-release.apk`; 4c must print a diff.

- [ ] **Step 5: Confirm the tree is clean**

```bash
git status --porcelain
```

Expected: only `.github/scripts/check-shizu-manifest.sh` as modified.

- [ ] **Step 6: Commit**

```bash
git add .github/scripts/check-shizu-manifest.sh
git commit -m "ci(store): add the network tier to the manifest checker

Most of what can break this listing lives outside the repo: a renamed
screenshot, a deleted release, a dead donate link, a tightened upstream
schema. None of it is visible to a local check.

The schema is checked twice on purpose. Validating against the live
schema answers whether the store rejects the file today; diffing the
vendored copy explains what moved. A stale vendored copy cannot detect
upstream tightening on its own — that is asking a stale reference
whether it is stale, and it always answers no.

403 and 429 count as reachable. Failing on bot protection would make a
weekly audit cry wolf until nobody reads it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: Release-time changelog sync

`changelog` is the only manifest field that tracks releases. It is refreshed by a human during release prep, in the same commit that bumps `versionCode` and adds `release-notes/v*/`, because no workflow can push to `master` — the `CodePush rules` ruleset requires a pull request plus a `build-and-test` check that a bot-authored PR would never trigger.

This script is deliberately not called by CI. CI verifies; a person writes.

**Files:**
- Create: `.github/scripts/sync-shizu-changelog.sh`

**Interfaces:**
- Consumes: `gradle.properties`, `release-notes/v<version>/playstore.txt`, `shizu_store.json`.
- Produces: an executable script that rewrites exactly one field. Exit 0 whether or not it changed anything; exit 1 if the notes are missing.

- [ ] **Step 1: Write the script**

Create `.github/scripts/sync-shizu-changelog.sh`:

```bash
#!/usr/bin/env bash
# Refresh shizu_store.json's changelog from the current release notes.
#
# Run this during release prep, in the same commit that bumps versionCode and
# adds release-notes/v<version>/. CI never runs it: the branch ruleset on
# master requires a pull request and a build-and-test status check, and a
# GITHUB_TOKEN-authored PR does not trigger pull_request workflows, so no bot
# can land a commit here. PR CI verifies the result instead.
#
# Usage: .github/scripts/sync-shizu-changelog.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

MANIFEST="shizu_store.json"

command -v jq >/dev/null 2>&1 || { printf 'missing required tool: jq\n' >&2; exit 2; }

# Anchored on purpose: an unanchored 'versionCode' also matches
# initialVersionCode=1921, yielding two lines that then feed into arithmetic.
# That is the bug that made release-manager.yml unusable.
version_code="$(grep -E '^versionCode=' gradle.properties | cut -d= -f2 | tr -dc '0-9')"
if [ -z "$version_code" ]; then
  printf 'could not read versionCode from gradle.properties\n' >&2
  exit 1
fi
version_name="$((version_code / 1000)).$(((version_code % 1000) / 10)).$((version_code % 10))"

notes="release-notes/v$version_name/playstore.txt"
[ -f "$notes" ] || notes="release-notes/$version_name/playstore.txt"
if [ ! -f "$notes" ]; then
  printf 'no release notes for v%s (versionCode %s)\n' "$version_name" "$version_code" >&2
  printf 'expected release-notes/v%s/playstore.txt\n' "$version_name" >&2
  exit 1
fi

# --arg via command substitution strips trailing newlines, matching how the
# checker compares. jq handles JSON escaping, so quotes or newlines in the
# notes cannot corrupt the manifest the way sed would.
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
jq --arg cl "$(cat "$notes")" '.changelog = $cl' "$MANIFEST" > "$tmp"

if cmp -s "$MANIFEST" "$tmp"; then
  printf 'changelog already current for v%s\n' "$version_name"
  exit 0
fi

cp "$tmp" "$MANIFEST"
printf 'changelog updated from %s (v%s)\n' "$notes" "$version_name"
printf 'review the diff, then commit shizu_store.json with the version bump.\n'
```

- [ ] **Step 2: Make it executable and confirm it is a no-op right now**

The manifest was written from `release-notes/v1.93.0/playstore.txt` in Task 5, so a sync must change nothing. If it produces a diff, one of the two is wrong.

```bash
chmod +x .github/scripts/sync-shizu-changelog.sh
.github/scripts/sync-shizu-changelog.sh
git diff --stat shizu_store.json
```

Expected: `changelog already current for v1.93.0`, and no diff.

- [ ] **Step 3: Prove it actually writes when the changelog is stale**

```bash
jq '.changelog = "stale"' shizu_store.json > /tmp/m.json && cp /tmp/m.json shizu_store.json
.github/scripts/check-shizu-manifest.sh; echo "checker exit=$?"
.github/scripts/sync-shizu-changelog.sh
.github/scripts/check-shizu-manifest.sh; echo "checker exit=$?"
git diff --stat shizu_store.json
```

Expected: the first checker run exits 1 naming `changelog has drifted` and printing the hint to run this script; the sync reports `changelog updated`; the second checker run exits 0; and `git diff` is empty because the sync restored the committed value.

- [ ] **Step 4: Prove it refuses when notes are missing**

```bash
git stash list >/dev/null   # no-op, just a reminder not to leave state behind
sed -i.bak 's/^versionCode=1930/versionCode=9990/' gradle.properties
.github/scripts/sync-shizu-changelog.sh; echo "exit=$?"
mv gradle.properties.bak gradle.properties
```

Expected: `no release notes for v9.99.0 (versionCode 9990)` and `exit=1`. Confirm `grep -n '^versionCode=' gradle.properties` reads `1930` again afterwards.

- [ ] **Step 5: Commit**

```bash
git add .github/scripts/sync-shizu-changelog.sh
git commit -m "ci(store): add the release-time changelog sync script

changelog is the one manifest field that tracks releases. A human runs
this during release prep, in the same commit as the versionCode bump —
no workflow can push to master, since the ruleset requires a PR plus a
build-and-test check that a bot-authored PR never triggers.

jq rather than sed: notes containing quotes or newlines would corrupt
the manifest otherwise.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: Wire both tiers into CI and delete `release-manager.yml`

**Files:**
- Modify: `.github/workflows/pr-ci.yml` (add a second job)
- Create: `.github/workflows/shizu-store-audit.yml`
- Delete: `.github/workflows/release-manager.yml`

**Interfaces:**
- Consumes: `check-shizu-manifest.sh` from Tasks 6 and 8.
- Produces: a `shizu-manifest` job on every PR, and a weekly `audit` job that manages a `shizu-store-audit`-labelled issue.

#### Deviation from the spec, and why

The spec calls for the PR job to be *path-filtered* to `shizu_store.json`, `fastlane/**`, `release-notes/**`, and `gradle.properties`. It cannot be, at the workflow level: `on.pull_request.paths` filters the **whole workflow**, and `pr-ci.yml` also contains `build-and-test` — the one required status check in the `CodePush rules` ruleset. A required check that never runs never reports, so every PR touching none of those paths would sit pending forever and be unmergeable.

Job-level filtering would need a third-party action such as `dorny/paths-filter`. The checker takes seconds and needs no network, so the job simply runs on every PR instead. That is a superset of the intended coverage with one fewer dependency.

- [ ] **Step 1: Add the `shizu-manifest` job to `pr-ci.yml`**

Append to the end of the file, after the existing `Android Lint (advisory)` step, at job indentation level:

```yaml

  shizu-manifest:
    name: shizu-manifest
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v7

      # Runs on every PR rather than being path-filtered. on.pull_request.paths
      # filters the whole workflow, which would also gate build-and-test — the
      # required status check — leaving unrelated PRs pending forever.
      - name: Install check-jsonschema
        run: pipx install check-jsonschema

      - name: Check the Shizu store manifest
        run: |
          if [ ! -f shizu_store.json ]; then
            echo "No shizu_store.json on this branch — nothing to check."
            exit 0
          fi
          .github/scripts/check-shizu-manifest.sh
```

The existence guard matters while `master` and `dev` are out of sync: between this PR merging to `master` and the follow-up merge into `dev`, a PR based on `dev` carries this workflow but not the manifest.

- [ ] **Step 2: Create `.github/workflows/shizu-store-audit.yml`**

```yaml
name: 3. Shizu Store Audit

# The store ignores a manifest it cannot validate and silently falls back to
# default GitHub data, so nothing reports a broken listing. This job is the
# only thing that catches rot originating outside the repository.
on:
  schedule:
    - cron: "0 6 * * 1" # Mondays 06:00 UTC
  workflow_dispatch:

permissions:
  contents: read
  issues: write

concurrency:
  group: shizu-store-audit
  cancel-in-progress: false

jobs:
  audit:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v7

      - name: Install check-jsonschema
        run: pipx install check-jsonschema

      # The label has to exist before `gh issue create --label` will accept it,
      # and a failure here would land at exactly the moment it matters most.
      - name: Ensure the tracking label exists
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          gh label create shizu-store-audit \
            --description "Automated Shizu CoreFetch listing audit" \
            --color B60205 2>/dev/null || true

      - name: Run the audit
        id: audit
        run: |
          set +e
          .github/scripts/check-shizu-manifest.sh --network 2>&1 | tee audit.log
          echo "status=${PIPESTATUS[0]}" >> "$GITHUB_OUTPUT"

      - name: Open or update the tracking issue
        if: steps.audit.outputs.status != '0'
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          RUN_URL: ${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}
        run: |
          {
            echo "The weekly Shizu CoreFetch audit failed."
            echo
            echo "A manifest the store rejects is ignored silently — the listing"
            echo "falls back to bare GitHub data and loses its banner, screenshots,"
            echo "translations and comment thread. Nothing else reports this."
            echo
            echo "Run: $RUN_URL"
            echo
            echo '```'
            cat audit.log
            echo '```'
          } > issue-body.md

          existing="$(gh issue list --label shizu-store-audit --state open \
                        --limit 1 --json number -q '.[0].number')"
          if [ -n "$existing" ]; then
            gh issue comment "$existing" --body-file issue-body.md
          else
            gh issue create \
              --title "Shizu store listing audit failed" \
              --label shizu-store-audit \
              --body-file issue-body.md
          fi

      - name: Close the tracking issue
        if: steps.audit.outputs.status == '0'
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          RUN_URL: ${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}
        run: |
          existing="$(gh issue list --label shizu-store-audit --state open \
                        --limit 1 --json number -q '.[0].number')"
          if [ -n "$existing" ]; then
            gh issue close "$existing" --comment "Audit passed. Run: $RUN_URL"
          fi
```

`${PIPESTATUS[0]}` rather than `$?` is required: `tee` is the last command in the pipeline and always succeeds, so `$?` would report the audit as passing every week no matter what it found.

- [ ] **Step 3: Delete `release-manager.yml`**

```bash
git rm .github/workflows/release-manager.yml
```

It has never been used and could not have worked as written. `grep "versionCode" gradle.properties | cut -d'=' -f2` matches both `initialVersionCode` and `versionCode`, producing two lines that then feed into `$((CURRENT_CODE + 1))` — an arithmetic syntax error. And `grep "versionName"` matches only commented-out lines, because `versionName` is computed in Gradle rather than stored. It is also the only file referencing a `PAT_TOKEN` secret, which does not exist.

- [ ] **Step 4: Lint the workflows**

```bash
command -v actionlint >/dev/null || brew install actionlint
actionlint .github/workflows/shizu-store-audit.yml .github/workflows/pr-ci.yml
```

Expected: no output. If `actionlint` cannot be installed, validate the YAML parses at minimum:

```bash
python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in sys.argv[1:]]; print('YAML OK')" \
  .github/workflows/shizu-store-audit.yml .github/workflows/pr-ci.yml
```

- [ ] **Step 5: Confirm the required check name is untouched**

The ruleset requires a check named exactly `build-and-test`. Renaming or nesting it would block every PR.

```bash
grep -n "name: build-and-test" .github/workflows/pr-ci.yml
```

Expected: one hit, unchanged from before this task.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/pr-ci.yml .github/workflows/shizu-store-audit.yml
git commit -m "ci(store): check the manifest on every PR, audit it weekly

The PR job runs unfiltered rather than path-filtered. on.pull_request
paths filter the whole workflow, and pr-ci.yml also carries
build-and-test — the required status check — so a path filter would
leave unrelated PRs pending forever. The checker takes seconds and needs
no network, so running it always is cheaper than the alternative.

The weekly job uses PIPESTATUS[0]; tee always succeeds, so \$? would
report a pass every week regardless of what the audit found.

Also deletes release-manager.yml. It was never used and never worked:
an unanchored versionCode grep matched initialVersionCode too, feeding
two lines into arithmetic, and the versionName grep only ever matched
commented-out lines. It was also the sole reference to a PAT_TOKEN
secret that does not exist.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 11: Full verification and the pull request

**Files:** none created or modified. This task only runs checks and opens the PR.

**Interfaces:**
- Consumes: everything from Tasks 1–10.
- Produces: an open PR from `feat/shizu-store-manifest` into `master`.

- [ ] **Step 1: Run the full local checker one more time, from a clean tree**

```bash
git status --porcelain
```

Expected: empty. Everything from Tasks 1–10 is committed.

```bash
.github/scripts/check-shizu-manifest.sh
```

Expected: every check `ok`, exit 0.

- [ ] **Step 2: Run the network tier**

```bash
.github/scripts/check-shizu-manifest.sh --network
```

Expected: every check `ok`, exit 0. This is the only run before merge that proves the ten screenshot URLs, the icon, and the feature graphic actually resolve on `master` — and they cannot, yet, because the images are only on this branch. Two of the three are already on `master`:

| Asset | On `master` before this PR? |
|---|---|
| `fastlane/.../images/icon.png` | yes |
| `fastlane/.../images/featureGraphic.png` | yes, after Task 1's cherry-pick lands |
| `fastlane/.../images/phoneScreenshots/*.png,jpg` | yes |

So a pre-merge `--network` run is expected to pass in full. If any image 404s, the raw URL path is wrong — check for a `phoneScreenshots` vs `phone-screenshots` mismatch and for the `0.png` / `1.jpg` extension split.

- [ ] **Step 3: Validate against the live schema explicitly**

```bash
check-jsonschema --schemafile https://docshizu.siwane.xyz/schema.json shizu_store.json && echo "LIVE SCHEMA OK"
```

Expected: `LIVE SCHEMA OK`. The store validates against the live schema, not the vendored copy, so this is the check that actually predicts whether the listing renders.

- [ ] **Step 4: Confirm the version fields really are absent**

```bash
jq 'has("version_name"), has("version_code")' shizu_store.json
```

Expected:
```
false
false
```

Both must be false. Including them would freeze the listing at 1.93.0 forever, since nothing in this design ever writes them again.

- [ ] **Step 5: Confirm no unrelated files are staged**

```bash
git diff --stat origin/master...HEAD
```

Expected: exactly these paths, and nothing else.

```
.github/scripts/check-shizu-manifest.sh
.github/scripts/sync-shizu-changelog.sh
.github/shizu_store.schema.json
.github/workflows/pr-ci.yml
.github/workflows/release-manager.yml   (deleted)
.github/workflows/shizu-store-audit.yml
README.md
fastlane/metadata/android/en-US/full_description.txt
fastlane/metadata/android/en-US/short_description.txt
fastlane/metadata/android/hi-IN/full_description.txt
fastlane/metadata/android/hi-IN/short_description.txt
fastlane/metadata/android/images/featureGraphic.png
shizu_store.json
```

If `docs/audit/` or anything under `docs/enforcement/` appears, a bare `git add -A` was used somewhere. `docs/audit/` is untracked and *not* gitignored; `docs/enforcement/` holds unsent legal drafts. Unstage them before continuing.

- [ ] **Step 6: Confirm `versionCode` was not touched**

```bash
git diff origin/master...HEAD -- gradle.properties
```

Expected: empty. This PR ships listing metadata, not a release. `versionCode` stays at 1930.

- [ ] **Step 7: Sanity-check the rendered description length**

Play caps the full description at 4000 characters. The Shizu store has no such cap, but these files serve both.

```bash
for f in fastlane/metadata/android/en-US/full_description.txt \
         fastlane/metadata/android/hi-IN/full_description.txt; do
  printf '%s: %s\n' "$f" "$(wc -m < "$f" | tr -d ' ')"
done
```

Expected: both under 4000. `wc -m` counts characters rather than bytes, which matters for Devanagari — a byte count would overstate the Hindi file by roughly a factor of three and cause a false alarm.

- [ ] **Step 8: Push the branch**

```bash
git push -u origin feat/shizu-store-manifest
```

- [ ] **Step 9: Open the PR against `master`**

```bash
gh pr create --base master --head feat/shizu-store-manifest \
  --title "feat(store): list Thor on Shizu CoreFetch" \
  --body "$(cat <<'EOF'
Adds `shizu_store.json` so Thor appears on [Shizu CoreFetch](https://docshizu.siwane.xyz)
with its real banner, screenshots, description and translations rather than
bare GitHub repo data.

The store reads the manifest from the **default branch**, which is `master` —
hence a PR here rather than to `dev`. Once merged, `master` merges back into
`dev` so the two do not diverge.

## What's in it

- `shizu_store.json` at the repo root, with six locales: `en`, `hi`, `ar`, `es`, `fr`, `zh`
- `.github/shizu_store.schema.json` — a vendored copy of the upstream schema
- `.github/scripts/check-shizu-manifest.sh` — local checks always, network checks with `--network`
- `.github/workflows/shizu-store-audit.yml` — weekly audit that opens/updates a tracking issue
- A `shizu-manifest` job on `pr-ci.yml`
- Refreshed English and Hindi store descriptions, and a matching README pass
- The feature graphic, cherry-picked from `dev`
- Deletes `release-manager.yml`

## No version fields

`version_name` and `version_code` are deliberately absent. The store already
reads the version from the repository's releases, and `download_url` points at
`releases/latest/download/foss-release.apk`, so the listing tracks releases on
its own. Writing them into the manifest would need a bot push to `master`,
which the `CodePush rules` ruleset forbids — and a stale hardcoded version is
worse than no version at all.

`changelog` is the one field that does track releases. It is updated by hand
during release prep via `.github/scripts/sync-shizu-changelog.sh`; a stale
changelog is cosmetic, a stale version is a lie.

## Two corrections found along the way

- The Hindi listing claimed the in-app language switcher supports Hindi. It
  does not — `localeFilters` is `en, ar, es, fr, zh-rCN` and there is no
  `values-hi`. Fixed.
- Two README links pointed at `Thor/tree/master/suCore`, a module that no
  longer exists. They now point at the standalone
  [Odin](https://github.com/trinadhthatakula/Odin) repository.

## Post-merge

1. Merge `master` into `dev`.
2. Pin issue #279 as the store comment thread.
3. Confirm the live listing shows version 1.93.0 — the one assumption this
   design makes that cannot be verified before publication.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 10: Wait for both checks**

```bash
gh pr checks --watch
```

Expected: `build-and-test` pass and `shizu-manifest` pass. `build-and-test` is the required check; `shizu-manifest` is advisory until someone adds it to the ruleset.

If `shizu-manifest` fails on CI but passed locally, the difference is almost always `jq` or `check-jsonschema` version skew, or a `bash` construct that macOS's bash 3.2 tolerates and CI's bash 5 does not — or vice versa. Task 6 already pins the script to bash-3.2-compatible constructs for exactly this reason.

- [ ] **Step 11: Post-merge actions**

These cannot be done from the branch. Record them; they run after the PR merges.

```bash
# 1. Sync master back into dev
git checkout dev && git pull && git merge origin/master && git push

# 2. Pin the store comment thread
gh issue pin 279

# 3. Confirm the audit workflow can run at all
gh workflow run "3. Shizu Store Audit"
gh run watch
```

The manual dispatch in step 3 matters: a scheduled workflow that has never run is indistinguishable from one that is broken, and GitHub disables `schedule` triggers after 60 days without repository activity. Run it once by hand to prove the label creation, the audit, and the issue paths all work.

- [ ] **Step 12: Verify the live listing**

After the merge, open the Shizu CoreFetch listing for `com.valhalla.thor` and confirm:

| Check | Why it matters |
|---|---|
| The banner and all ten screenshots render | Proves the `raw.githubusercontent.com/.../master/` URLs resolve for a third party |
| The version shows 1.93.0 | The one unverifiable assumption — that the store falls back to release data when the manifest omits version fields |
| The description is the new English copy | Proves the manifest validated; if it silently fell back, the description will be the GitHub repo blurb instead |
| Issue #279 is the comment thread | Confirms `store_issue_number` was accepted |

If the version is blank or wrong, the fallback assumption was incorrect. The fix is to add `version_name` and `version_code` to the manifest and to `sync-shizu-changelog.sh`, which already parses `versionCode` from `gradle.properties` and would need only two more `jq` assignments — plus a Task 6 check asserting they match. Note this in the tracking issue rather than patching silently, since it reverses a documented decision.

---

## Self-Review

Run against `docs/superpowers/specs/2026-07-27-shizu-store-manifest-design.md`.

**Spec coverage.** All twelve scope rows map to a task: featureGraphic cherry-pick → 1; en-US copy → 2; hi-IN copy → 3; README → 4; vendored schema + manifest → 5; local checker → 6; translations → 7; network tier → 8; changelog sync script → 9; audit workflow, pr-ci job and `release-manager.yml` deletion → 10. The spec's risk list is addressed: silent rot by Tasks 6/8/10, the schema-drift diff by Task 8, and the version-fallback assumption by Task 11 step 12.

**Placeholders.** None. Every description file, translation, script and workflow appears in full; no task says "similar to Task N".

**Type consistency.** The checker is `check-shizu-manifest.sh` and the sync script `sync-shizu-changelog.sh` in every reference. Both use the same anchored `grep -E '^versionCode='`. `fastlane_dir_for_locale()` is defined once in Task 6 and reused in Task 8. Locale keys are `en, hi, ar, es, fr, zh` throughout — never `zh-rCN`, which is the Android resource qualifier and not a Shizu locale code.

**One deviation from the spec, documented in place:** the pr-ci job runs unfiltered rather than path-filtered, because a workflow-level path filter would also gate the required `build-and-test` check.
