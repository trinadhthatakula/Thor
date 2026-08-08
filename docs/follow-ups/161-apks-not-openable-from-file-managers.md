# Follow-up: #161 — `.apks` files won't open from Samsung My Files

**Status:** OPEN, diagnosed but unfixed. GitHub issue #161, named reporter, unanswered since
2026-07-18.
**Severity:** Moderate — Thor's headline install path is unreachable from the file manager most
Samsung users have, and the bug reads to the reporter as "Thor can't do what its competitors do".
**Effort:** small for the diagnostic + likely fix; medium if the fallback is needed.
**Raised by:** the #161 reconnaissance pass (2026-07-30).

Files: `app/src/main/AndroidManifest.xml` (the four `PortableInstallerActivity` filters),
`app/src/main/java/com/valhalla/thor/presentation/installer/PortableInstaller.kt:121`

## Root cause

Thor declares four `ACTION_VIEW` filters. Verified shape:

| # | scheme | `android:host` | mimeType | pathPatterns |
|:-:|---|---|---|:-:|
| 1 | content, file | — | `application/vnd.android.package-archive` | 0 |
| 2 | content, file | — | 13 types (`application/octet-stream`, `application/zip`, `application/x-apks`, …) | 0 |
| 3 | content, file | **`*`** | `*/*` | 35 |
| 4 | content, file | **`*`** | — | 35 |

`android:host="*"` is the defect. Under `IntentFilter.matchData`, declaring a host (or a path)
promotes the URI-shape matchers from "another way to match" to a **mandatory gate**. So filters 3 and
4 — the wildcard ones, the ones carrying all 35 extension patterns — only match if the pathPattern
matches too.

Samsung My Files hands over an opaque MediaStore `content://` URI whose path is a numeric row id with
**no filename in it**, so no `.*\.apks` pattern can ever match. Filters 3 and 4 are out on the path.
Filters 1 and 2 have no path gate and would match, but only if the provider's reported MIME type is
one of their 14 — and a `.apks` file typically resolves to something outside that list, or to nothing.

All four miss. **Adding more extensions to filters 3 and 4 cannot fix this** — the extension is not
being consulted.

## Why the diagnosis is more than a guess

The reporter's own screenshot is a control group. **SAI is absent from the Samsung chooser too** —
and SAI ships ~10× Thor's pathPattern coverage with the identical `host="*"` + `*/*` shape. So do
ZArchiver, RAR, WPS and Solid Explorer. The three apps that *do* appear (InstallerX Revived,
Universal Installer, TeraBox) all use a path-free wildcard; InstallerX's public manifest declares no
`android:host` at all, which makes its `pathPattern=".*"` a decoy that is never evaluated.

Pattern count correlates with nothing. Presence or absence of a host gate correlates perfectly.

*(This rests on reading the reporter's screenshot and third-party manifests, not on a device in
hand — confirm with the diagnostic below before committing to the fix.)*

## Run this first

Establish what MIME type Samsung's provider actually reports, because it decides which fix is needed:

```bash
adb shell content query --uri content://media/external/file \
  --projection _id:_data:mime_type --where "_data LIKE '%.apks'"

adb shell pm query-activities --brief \
  -a android.intent.action.VIEW \
  -t <the mime_type from above> \
  -d content://media/external/downloads/<id>
```

Thor should be absent before the fix and present after.

## The fix

If the reported type is a stable constant already in filter 2's list, the minimal change is to add
that one string — one line, zero collateral. If it is a type Thor does not declare, add it. Only if
it **varies by device or file** is a wildcard needed, in which case add one path-free filter:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <action android:name="android.intent.action.INSTALL_PACKAGE" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="content" />
    <data android:mimeType="*/*" />
</intent-filter>
```

**The `*/*` trade-off is real and should be avoided if the diagnostic allows it.** A path-free `*/*`
makes Thor a candidate for *every* file of unknown type — user-hostile, and the kind of thing that
gets an app uninstalled. If it turns out to be necessary, put it on an `<activity-alias>` disabled by
default behind a Settings toggle ("show Thor when opening any file"), so the broad filter is opt-in.

## What shipped (PR #366, 2026-08-08)

The diagnostic above was **not** run — no Samsung device was available — so the broad route was taken
blind, and therefore taken opt-in exactly as prescribed above.

A **typeless** filter rather than `*/*`: it declares two schemes and no `android:mimeType` at all.
That is narrower than the block above, because `matchData` returns `NO_MATCH_TYPE` for any intent
carrying a type and `Intent.resolveTypeIfNeeded` asks the ContentResolver first — so a `content://`
URI reaches it only when the provider's own `getType()` also returned null, which is the reported
symptom. It is still unbounded: every `file://` VIEW intent matches unconditionally, since
`resolveType` never consults a provider for that scheme.

**It cannot be narrowed by path.** Under `IntentFilter.matchData` the path list is consulted only
inside the `authorities != null` branch, so path matchers are inert without a host and *mandatory*
with one. Adding a host to reach the extension patterns would make an opaque provider URI — a
MediaStore row id with no filename — unmatchable, excluding the exact case the filter exists to
serve. There is no middle setting; the filter is either this broad or absent.

So it lives on `AnyFileInstallerAlias` with `android:enabled="false"`, flipped by
`AnyFileOpenerManager` from a Settings switch. `PackageManager` owns the state — there is no
DataStore preference mirroring it, so the two cannot disagree — and the switch reads the state back
after writing rather than flipping optimistically. `AnyFileOpenerAliasTest` pins the manifest and the
Kotlin class name to each other and asserts the alias ships disabled.

**Still open:** the diagnostic. Until someone runs it on a real Samsung device, nobody knows whether
a narrow `android:mimeType` would have solved this outright, and the opt-in stays the only route —
which means the users who reported #161 get nothing until they find the switch. Running it is the
thing that could retire the broad filter entirely.

## Two side findings

- **This is not a "fix never shipped" issue.** v1.92.0 already carries these filters. The reporter is
  on a version that has them; they simply do not match. Do not close #161 by pointing at a release.
- **Thor declares no `ACTION_SEND` filter at all**, and `PortableInstaller.kt:121` reads only
  `intent.data`, never `EXTRA_STREAM`. So the *share sheet* route into the installer is a second,
  separate gap needing a code change, not just manifest lines. Worth deciding whether #161's fix
  covers it or whether it is its own item — the reporter may well be using share, not open-with.

## Acceptance

- The `pm query-activities` command above lists Thor for whatever type Samsung My Files reports.
  — **not met.** No Samsung device; the diagnostic has not been run.
- Opening a `.apks` from Samsung My Files reaches `PortableInstallerActivity` on a real Samsung
  device (the reporter's, ideally — they are responsive). — **not met**, same reason. The shipped
  filter is a reasoned guess at the mechanism, not a confirmed fix.
- If `*/*` was used: Thor does **not** appear when opening a `.txt`, `.pdf` or `.jpg` unless the user
  has enabled the toggle. — **met by construction**: the filter is on a component that ships
  disabled, so with the switch off Thor is not a candidate for anything, and
  `AnyFileOpenerAliasTest` fails the build if that stops being true. Not yet confirmed on hardware.
