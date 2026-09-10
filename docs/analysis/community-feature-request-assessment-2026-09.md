# Thor community feature-request assessment

- **Working snapshot:** 2026-09-10 (targeted implementation updates; not a new full backlog sweep)
- **Assessment baseline:** `origin/dev` at `d091ecb7`. Doctor was subsequently synced to `dev` at
  `504e3418`, then `5c9672e6` after the web-only Dependabot sync in
  [PR #463](https://github.com/trinadhthatakula/Thor/pull/463). These synchronizations alone do not
  revalidate every historical assessment below.
- **First selected implementation:** TG-001, on `feat/legacy-apk-install` from `dev` at `504e3418`.
  Implemented locally with passing test/lint gates and an API-36 package-manager smoke test.
  End-to-end device acceptance and Store-policy review remain open; nothing is claimed released.
- **Next selected implementations:** RD-001 and RD-004, locally on the same feature branch.
  Screenshot-protection copy and fully expanded Freezer/security switch explanations are implemented;
  native-layout tests, full unit tests, both lint gates, and debug APK assembly pass.
  App-lock/screenshot-protection behavior is unchanged. The maintainer confirmed all settings changes
  in the tested debug APK on a physical device on 2026-09-10; these changes remain unmerged/unreleased.
- **Sources loaded so far:** live GitHub issues, `docs/follow-ups/`, `docs/feature-request-roadmap.md`, `README.md`, current source and release history, Thor-Extensions branch state, and the public FAQ.
- **Manual intake so far:** the maintainer's 2026-09-09 Telegram and Reddit excerpts below; more
  messages can be appended without changing the evidence rules.

This is a living intake and assessment, not a final roadmap.  It deliberately separates what a user
asked for from what is actually unbuilt, because an open issue can be fully shipped, a merged change
can still need device validation, and a historical document can describe a superseded state.

## Status vocabulary

| Status | Meaning |
|---|---|
| `implementation gap` | A defined, unbuilt capability remains. |
| `scope decision` | The request is real, but product, safety, privacy, or policy choices must be made before estimating it. |
| `deferred` | Known work deliberately postponed; it is not a promise of imminent delivery. |
| `partially shipped` | Some requested outcome exists; the residual must be named precisely. |
| `validation-bound` | Code exists, but a device/OEM/privilege check is still required before calling it delivered. |
| `awaiting release` | Merged into `dev`, but not included in a stable release. |
| `in progress — extension` | Active implementation is happening in a separate extension; integration, signing, release, and validation are still outstanding. |
| `brainstorming required` | Some capability exists, but the remaining user outcome has competing plausible designs and no selected target behavior. |
| `closeable` | The issue remains open even though its achievable scope shipped or was explicitly declined. |
| `non-feature` | A bug, operational item, feedback thread, or external dependency; keep it out of feature ranking. |
| `documentation stale` | An older source states a status superseded by current code or release evidence; retain it as historical context, not current truth. |

## Live GitHub tracker snapshot

The tracker had 12 open issues at the snapshot: nine labelled `enhancement`, one installer bug,
one CI/link-check item, and the Shizu Store feedback thread.  The table records the **remaining**
need, not merely the original title.

| Source | Normalized request | Assessment | Evidence and next action |
|---|---|---|---|
| [GH #445](https://github.com/trinadhthatakula/Thor/issues/445) | Do not silently grant every requested runtime permission during privileged installs; consider helping affected users review past grants. | `awaiting release` for the safety fix; the retrospective prompt is a separate `scope decision`. | PR #446 removed the default `-g` behavior and added an opt-in setting/per-install control. It is in this `dev` snapshot but no stable tag contains it. Keep the issue open through a stable release, then close it or explicitly split the historical-permission notice. |
| [GH #390](https://github.com/trinadhthatakula/Thor/issues/390) | Work around HyperOS/MIUI isolation that rejects updates to existing system apps from Thor. | `validation-bound` / `scope decision`. | Some Xiaomi ROMs reportedly expose **Developer Options → Update system apps**, which may control this exact operation. Verify its exact path, ROM/version availability, and effect on the reporter's `INSTALL_FAILED_HYPEROS_ISOLATION_VIOLATION` before considering installer spoofing. If it resolves the failure, clear device guidance/capability detection is preferable to impersonating another installer; if it does not, separately establish whether the remaining gate is caller identity, installer attribution, or both. This is not the unrelated **Install via USB** option. |
| [GH #382](https://github.com/trinadhthatakula/Thor/issues/382) | Select a work mode and authorize Dhizuku without manual permission setup. | `partially shipped`. | In-app Dhizuku grant and refresh shipped in v1.95.1, and the existing selector chooses among available modes. The request's literal “show/select unavailable modes” half is not met because the selector is built from available modes only. Ask whether the shipped in-app grant resolves the reporter's need; otherwise split and scope the unavailable-provider UX. |
| [GH #209](https://github.com/trinadhthatakula/Thor/issues/209) | VirusTotal scan for install archives and installed apps. | `in progress — extension`. | The separate S.H.I.E.L.D. extension is under active development on Thor-Extensions' unmerged [`feat/virus-scanner-extension`](https://github.com/trinadhthatakula/Thor-Extensions/tree/feat/virus-scanner-extension) branch. It is not merged, signed/catalog-published, or released, so keep the issue open and track extension integration, API-key/privacy UX, release, and validation rather than scheduling a base-app scanner. |
| [GH #178](https://github.com/trinadhthatakula/Thor/issues/178) | User-defined app tags for browsing/filtering; pair with per-app notes. | `implementation gap`. | Permission filters and Freeze Profiles are not general app tags. The two Reddit requests remove the old “zero demand” rationale; tags and notes should share one Room migration and app-list surface. Clarify tags-for-browsing versus custom grouping/sorting during intake. |
| [GH #164](https://github.com/trinadhthatakula/Thor/issues/164) | Export APK/APKS/XAPK to a chosen folder. | `closeable`. | Folder export, APK/APKS/XAPK, and XAPK OBB support are in stable v1.95.1. Loose split folders remain an explicit decline. Close with that scope rather than silently implying every interpretation shipped. |
| [GH #161](https://github.com/trinadhthatakula/Thor/issues/161) | Make Thor appear for `.apks` files opened from Samsung My Files. | `partially shipped`, `validation-bound`. | The opt-in broad alias shipped, but the actual Samsung `pm query-activities`/open-with diagnostic is unrun. Keep open until the reporter-device result decides whether a narrow MIME change is enough. `ACTION_SEND` is a separate, currently unimplemented route. |
| [GH #130](https://github.com/trinadhthatakula/Thor/issues/130) | Friendly installer labels and a chart tap-through to apps from that installer. | `brainstorming required`. | Labels and named-legend chart-to-filter navigation shipped, but the desired residual is not selected. Brainstorm whether the priority is direct bar-segment interaction, an explorable aggregate/unknown-source path, or voluntary user provenance annotations. Android may still report `com.android.shell` or no installer for shell installs, which Thor cannot reconstruct as factual historical provenance. |
| [GH #58](https://github.com/trinadhthatakula/Thor/issues/58) | Lock *other* apps behind authentication. | `deferred`. | This is distinct from Thor's own biometric lock. Launch detection, overlay, foreground service, boot persistence, OEM behavior, and Play-policy exposure are all new; Dhizuku has no per-launch route. The maintainer reconfirmed on 2026-09-09 that it stays deferred. |
| [GH #55](https://github.com/trinadhthatakula/Thor/issues/55) | Freeze profiles plus a process manager. | `partially shipped`, residual `deferred`. | Profiles shipped. A RAM/CPU process manager remains a fragile Root/Shizuku-only parser with no Dhizuku path. Split or retitle the issue so the delivered profiles do not obscure the remaining process request; separately assess a smaller “is running” flag. |
| [GH #337](https://github.com/trinadhthatakula/Thor/issues/337) | External-link checker failures. | `non-feature`. | Operational/CI handling only; exclude from this assessment. |
| [GH #279](https://github.com/trinadhthatakula/Thor/issues/279) | Shizu Store feedback/comments. | `non-feature`. | Intentional public feedback thread, not a product request. Keep open and mine only genuinely new requests that appear there. |

### Focused status notes from the 2026-09-09 maintainer clarification

#### GH #390 — HyperOS/MIUI system-app updates: configuration and diagnosis first

The relevant Xiaomi control is reported as **Settings → Additional settings → Developer options →
Update system apps**; some ROMs use close wording such as **Allow updating system apps**.  It is not
**Install via USB**, and it is not a recommendation to disable MIUI/HyperOS optimization.

The first-line support flow for the exact
`INSTALL_FAILED_HYPEROS_ISOLATION_VIOLATION` error should be: preserve the raw error, explain that
the setting is Xiaomi-specific and may be absent or differently translated, ask the user to enable
it and retry, then collect the model, region, HyperOS/MIUI version, target package, and privilege
mode.  [AppManager #1484](https://github.com/MuntashirAkon/AppManager/issues/1484) independently
records the same error and reports success after enabling the control; [APKMirror Installer
#311](https://github.com/illogical-robot/apkmirror-public/issues/311) has HyperOS 2 reports that
also distinguish this problem from HyperOS optimization.

That is strong enough to recommend troubleshooting guidance, not to claim universal behavior.
The setting is OEM/ROM/version/package dependent and does not bypass signing lineage, version or
downgrade rules, split/ABI requirements, Android's low-target-SDK block, or other Package Manager
checks.  Thor should not claim it can read or change this vendor Developer Option.  Do a physical
off/on comparison on an affected Xiaomi device before considering any narrow privileged retry; do
not ship arbitrary installer-ID presets or a general installer-spoofing switch.  `-i` changes
installer attribution, not a trustworthy caller identity.

#### GH #209 — VirusTotal: active extension work, not a deferred core feature

The feature is in development as the separate **S.H.I.E.L.D.** extension.  The Thor-Extensions
`feat/virus-scanner-extension` branch is unmerged and unreleased, so it is not proof of an
available scanner; it moves the remaining work into the extension workstream: extension API and
integration behavior, signing/catalog publication, API-key and privacy consent UX, rate-limit
handling, and device/release validation.  Keep #209 open until that work is available to users.

#### GH #130 — choose the remaining outcome before closing it

v1.95.1 already supplies friendly installer labels and named-legend navigation into the matching
app-list filter.  The issue should remain open for a short design decision rather than being
treated as a defect or automatically closed.  Viable directions are:

1. Make the visual distribution-bar segments directly selectable, with accessible equivalents and
   a deliberate behavior for tiny slices.
2. Make **Other** explorable through an all-sources/unknown-source sheet, rather than pretending
   its aggregate has one installer identifier.
3. If real demand remains, add user-authored provenance annotations as clearly non-Android-derived
   metadata; this is a separate Room/backup/editing feature, not a fix for Android attribution.

Android can legitimately return `com.android.shell`, a missing source, or an unavailable installer
label.  Thor must not infer a historical installer such as InstallWithOptions where Android did not
record one.

#### GH #58 — stays deferred

No new implementation decision is implied by the existing request.  Keep the tracker and this
assessment at `deferred` until demand or support/policy requirements change materially.

## Existing community and documentation signal

The older roadmap and follow-up index are useful evidence, but neither is a current truth source on
its own.  The last broad follow-up sweep predates recent `dev` changes, and many retained rows are
kept specifically to preserve the reasoning for a shipped decision.

| Canonical opportunity | Sources already found | Provisional position before new message imports |
|---|---|---|
| Tags + per-app notes | GH #178; Reddit r/howtomen record; follow-up index | One combined app-list/Room migration. Genuine gap with corroborated demand. |
| Process visibility / process manager | GH #55; Reddit request | Keep the lightweight “is running” flag separate from live RAM/CPU statistics. Neither should be treated as Dhizuku-compatible without evidence. |
| App lock | GH #58; feature roadmap | Defer: high ongoing platform/policy burden, not a small extension of Thor's own biometric lock. |
| VirusTotal integration | GH #209; feature roadmap; S.H.I.E.L.D. extension branch | `in progress — extension`, not a base-app scanner commitment. Track extension signing/catalog publication, API-key and privacy UX, integration hooks, and validation. |
| Change/update history | Reddit follow-up | One shared event-history data model, not two isolated features. Needs a current code re-check before sizing. |
| Abandoned-app notifications | Reddit follow-up | Existing usage-access plumbing reduces setup cost, but usage query, scheduling, notification behavior, and product wording remain. |
| Permission grant/revoke | Reddit follow-up; current Permission Manager | Thor now exposes per-package permission toggles through all three privilege gateways. Treat the original grant/revoke ask as shipped subject to Android/platform limits; a broader App Ops manager would be a separate new request. |
| Backup/restore and XAPK behavior | README promise; follow-ups; GH #164/#51 history | The current release contains the feature work, but documented Root/Shizuku/OEM acceptance checks remain unrun. Keep hardware validation outside the feature-priority score rather than re-listing it as a new feature. |
| Editing `packages.xml`, batch install, authenticated extension trigger | README Upcoming Features | Standing promises with no sufficient design/evidence yet. Keep as unscoped candidates, not ranked commitments. |

### Residual candidates from `docs/follow-ups`

These are the historical community/standing asks that still warrant intake after stale or shipped rows
are removed.  They are deliberately not yet ordered: the missing Telegram and Reddit imports may
change the demand signal.

| Candidate | Status before new imports | What must be resolved before it can be ranked |
|---|---|---|
| Change history + update history | `implementation gap` | Design one event/history model; current scans overwrite version facts, so two separate screens would duplicate the same storage work. |
| Abandoned-app notifications | `implementation gap` | Decide the usage interval, query cadence, notification behavior, privacy copy, and action affordances. Existing usage-access setup lowers plumbing cost but does not settle the product behavior. |
| Lightweight running-app signal | `implementation gap`, unsized | Name the privileged API/command and an honest unsupported-mode fallback before treating it as the cheap half of a process manager. |
| Interactive, sort-aware scrollbar scrubber | `implementation gap` | The non-interactive indicator shipped. Define real bucket semantics for each sort family and preserve list ordering/locales. |
| Editing `packages.xml` | `scope decision` | No design, issue, or safe supported use case is recorded. |
| Batch install | `scope decision` | Define archive selection, ordering, confirmation, failure/retry behavior, privilege modes, and interaction with the existing installer queue. |
| Authenticated extension trigger | `scope decision` | Replace the removed exported deep link with an explicit-component or nonce-authenticated handoff; security model precedes UX. |

### Product decisions and validation debt kept outside feature ranking

| Item | Why it is not a normal feature-score row |
|---|---|
| “Remove this system app for my user, preserving data” | A consent path remains after the unsafe freeze escalation was removed. Its watchlist semantics, inverse operation, headless callers, and hardware reproduction are product/safety decisions first. |
| Freezer rows that cannot thaw | Auto-pruning genuinely uninstalled packages shipped. Any remaining “remove anyway” escape hatch changes destructive behavior and needs an explicit policy. |
| Release logging and subscription-downgrade replacement mode | Both are product/privacy or billing-policy choices with tests needed before implementation. They should not be inflated by community-request counts. |
| Backup/restore, XAPK/OBB, cross-privilege suspend, and Samsung My Files | Code/release evidence exists, while specific hardware/OEM/privilege checks remain unrun. These are acceptance gates, not proof of an empty feature backlog. |
| Engineering-only follow-ups | `BulkFreezeRunner` test seams, `code_cache` behavior, Perfetto work, Vercel deployment, and Odin items were reviewed but belong to engineering/upstream tracking rather than community feature scoring. |

## Evidence and conflict handling

- The live tracker is a snapshot of 2026-09-09.  Issue state alone is never capability evidence.
- `docs/feature-request-roadmap.md` explicitly says its broad review is from July with only a
  partial August re-check, so it is historical sizing/context rather than a September status source.
- `docs/follow-ups/README.md` was last broadly swept on 2026-08-19.  It remains useful for user
  rationale and unrun acceptance checklists, but current code and release history override a
  contradictory implementation claim.
- #382: the available-mode selector is in `SettingsScreen` / `DashboardHeader`; the in-app Dhizuku
  request is implemented by `DhizukuHelper.requestPermission` and released in v1.95.1.
- #445: PR #446 is merged into this `dev` snapshot.  Its default-off setting and one-install choice
  are wired through `InstallerViewModel` and each Root, Shizuku, and Dhizuku session path; no stable
  release tag contains that merge yet.
- #390: do not confuse Xiaomi's **Update system apps** Developer Options control with **Install via
  USB** or system optimization.  The former is a reported first-line workaround for this exact
  vendor error; its availability and effect need an affected-device off/on comparison before Thor
  can make anything stronger than conditional troubleshooting guidance.
- #209: the older feature roadmap and follow-up index say deferred, but the current FAQ and the
  unmerged Thor-Extensions `feat/virus-scanner-extension` branch show active extension work.  The
  implementation is neither merged nor released, so `in progress — extension` is not `shipped`.
- #164: `BundleFormat`, `ExportBottomSheet`, and `ObbInstaller` evidence the output formats and OBB
  path; v1.95.1 release notes confirm the released scope.  Retain the documented device-validation
  debt rather than inferring every mode/OEM path has run.
- #130: `InstallerDistribution`, `HomeScreen`, and `AppListViewModel` implement labels and
  named-legend chart-to-filter navigation.  The remaining Android attribution limit is source data,
  while direct chart interaction and aggregate-source exploration require a maintainer design choice.
- Runtime permission grant/revoke is current code (`TogglePermissionUseCase`, `PermissionManager`,
  and all three gateway implementations).  A request for arbitrary Android App Ops is broader and
  must be recorded as a new scope if it arises.

## Manual Telegram and Reddit intake

Paste each message verbatim into the task conversation.  This document will preserve a normalized
row and retain enough source context to distinguish repeated demand from duplicate implementation
work.

| ID | Channel / date | Verbatim source retained externally | Normalized request | Related canonical opportunity | Demand signal | Current capability / gap | Status | Questions before ranking |
|---|---|---|---|---|---|---|---|---|
| TG-001 | Telegram / 2026-09-09 | Task conversation | Let a Shizuku user install an APK rejected on Android 16 for its old SDK level (reported target/SDK 23 on Galaxy S24 Ultra). | Low-target-SDK installation override | 1 report | This is a `targetSdkVersion` block, not `minSdkVersion`: Android 16 reports `INSTALL_FAILED_DEPRECATED_SDK_VERSION` below target API 24. Root and Shizuku shell can use the bypass; Dhizuku, normal, and external modes cannot. | `validation-bound`: implemented locally, not merged/released | Follow [implementation and acceptance record](../follow-ups/legacy-target-sdk-installation.md); end-to-end device acceptance and Store-policy review remain open. |
| TG-002 | Telegram / 2026-09-09 | Task conversation | Fix a mobile-responsiveness problem on thor.trinadhthatakula.com in Android Brave. | Website mobile layout | 1 report | No URL, screenshot, viewport, or affected section supplied. `/follow-ups-report.html` is a plausible narrow-screen risk: its sticky header does not reflow and its search input has a 260px minimum width. | `needs reproduction` | Which page/section, Brave version, viewport/font scale, and expected versus actual layout? |
| RD-001 | Reddit: onlytanmoy / imported 2026-09-09 | Task conversation | Explain why Thor blocks screenshots. | Biometric-lock discoverability | 1 report | Biometric-lock copy now explains launch authentication, screenshot/recording protection and the hidden Recents preview in all eight locales; its row expands fully. `FLAG_SECURE` behavior is unchanged, including after authentication. | `implemented locally; automated and physical-device settings checks passed` | Maintainer confirmed the settings changes in the debug APK on a physical device on 2026-09-10. Not merged/released; screenshot/recording/Recents security testing is not implied. If screenshots remain blocked with app lock disabled, collect a separate reproduction. |
| RD-002 | Reddit: onlytanmoy / imported 2026-09-09 | Task conversation | Explain the first top-right Home control that appears to do nothing. | Privilege-mode control discoverability | 1 report | The status icon cycles available privilege modes when more than one is ready; otherwise it opens the privilege check. The action has weak visual affordance. | `discoverability` | Which modes were available, and did its icon/change state update? Is a label, tooltip, or mode picker clearer than silent cycling? |
| RD-003 | Reddit: onlytanmoy / imported 2026-09-09 | Task conversation | Make App Distribution “Other” useful when tapped. | Installer-distribution drill-down | 1 report | “Other” intentionally groups several/null installer identities, while current filtering accepts exactly one installer id; it therefore has no click handler. | `UX / scope decision` | Should it open a multi-source filter, an explanatory sheet, or remain noninteractive with clearer affordance? |
| RD-004 | Reddit: onlytanmoy / imported 2026-09-09 | Task conversation | Prevent Settings → Freezer descriptions from truncating; user suggests three lines or a detailed explanation before toggling. | Settings readability / accessibility | 1 report | All four Freezer switches now use fully wrapping titles and descriptions. A shared expanded row preserves one switch target and lets the scrolling category grow at larger text sizes. Other settings retain their compact defaults. | `implemented locally; automated and physical-device settings checks passed` | Native text-layout checks pass in all eight locales at narrow/large-text sizes, including enabled/disabled and touch semantics. Maintainer confirmed the settings changes in the debug APK on a physical device on 2026-09-10. Not merged/released. No extra tap or bottom sheet is needed to read the explanation. |
| RD-005 | Reddit / imported 2026-09-09 | Task conversation | Name the active apps in the “N of N apps are active; freeze them?” confirmation. | Bulk-freeze confirmation detail | 1 report | The dialog counts enabled apps but does not render their labels. | `implementation gap`, likely small | How many names should be shown before truncation, and should the full selectable list be visible in a sheet? |

### Triage notes for the newly imported requests

#### TG-001 — legacy target-SDK installation

**Selected by the maintainer as the first implementation on 2026-09-09.** Work is isolated in Doctor
on `feat/legacy-apk-install`. The [implementation and acceptance record](../follow-ups/legacy-target-sdk-installation.md)
tracks the actual verification separately from this intake snapshot.

Android 15 raised the install floor to target API 24; Android 16 inherits it.  A target-23 APK can
produce:

```text
INSTALL_FAILED_DEPRECATED_SDK_VERSION:
App package must target at least SDK version 24, but found 23
```

The documented Android escape hatch is `adb install --bypass-low-target-sdk-block`; Thor's matching
session command is `pm install-create --bypass-low-target-sdk-block`.  The platform only retains that
hidden flag for system/root/shell or a debuggable-build caller.  Therefore this is feasible only on
Thor's Root and ADB/wireless-Shizuku shell paths, not Dhizuku or ordinary installation paths.

First capture the actual installer error and inspect the APK manifest: the requested option applies
only if it is this low-*target*-SDK block, not a generic answer to every old-APK failure.
Selected UX, amended by the maintainer: the installer warns and asks for confirmation when Install
is tapped for an eligible old-target APK. **Settings → Installing → Allow legacy APK installs
without asking** is off by default; deliberately enabling it skips that confirmation for eligible
Root/Shizuku installs while retaining the inline warning. One-time approval never enables the
setting, and background restore/reinstall behavior is unchanged. It must never silently retry a
failed ordinary install. Test on the reporter's
Android 16 device with a known-safe target-23 APK, split and monolithic packages, update/fresh install,
and all privilege modes.  Official reference: [Android 15 minimum target API level](https://developer.android.com/about/versions/15/behavior-changes-all#minimum-target-api-level).

#### TG-002 — website responsiveness

No implementation should be selected until the reporter supplies a URL/section and a screenshot or
short recording.  The most concrete code candidate is
`web/public/follow-ups-report.html`: its header packs a badge, long title, and two labelled theme
buttons into a non-wrapping row, and its mobile media query does not alter that header.  The normal
Astro pages have mobile breakpoints, so a report against a different page cannot be inferred from
this candidate.

Ask for the exact URL (including `#section`), device/Android/Brave version, a full-screen capture,
whether the issue is clipping, overlap, horizontal scroll, tiny content, or a dead interaction, and
the page zoom/font-size setting.

#### Reddit UI findings

- Screenshot blocking is expected while Thor's biometric lock is armed or authentication is pending:
  `HomeActivity` applies `FLAG_SECURE` to prevent Recents, screenshots, and recording from exposing
  app data.  If the lock is off and the problem persists, it becomes a device-reproduction bug.
  The 2026-09-10 local change explains this in all eight biometric-lock descriptions without
  changing the security behavior; the explanation remains visible after wrapping.
- The first Home control is the privilege-mode status icon.  It silently cycles available modes on a
  normal tap and opens Privilege Check on long press; this is a discoverability/feedback problem
  unless a device trace shows no mode change.
- “Other” is intentionally not clickable today because it is an aggregate with no single installer
  id.  Making it actionable requires a multi-source/unknown-source filter or an explanatory surface.
- The default Settings subtitle renderer retains its two-line limit, but the selected 2026-09-10
  implementation opts all Freezer switches and biometric lock into a fully expanded row. A fixed
  three-line allowance can still truncate longer translations at larger font scales. The category
  already scrolls, and reading the explanation does not add a competing tap action.
- The bulk-freeze confirmation has the selected `AppInfo` values and currently discards their labels.
  This is a small UX request once a localized list/truncation rule is agreed.

#### RD-001 / RD-004 verification — 2026-09-10

- `SettingsExpandedSwitchRowTest` covers five rows across eight locales at 320dp/2x text and
  600dp/1.5x text, with the category's actual 24dp side padding: 80 row/locale/viewport combinations.
  Native font metrics verify no overflow or ellipsis, Arabic RTL, and scroll reachability. Additional
  checks preserve a single switch, semantic/touch activation, and disabled no-callback behavior.
- Expanded text occupies the available column. This also avoids Compose 1.12.0's reconstructed
  semantic paragraph reporting the maximum constraint width against a short label's intrinsic size.
  The overflow assertions remain strict rather than suppressing that mismatch.
- The focused run passed 34 tests, including the existing app-lock/cold-start and legacy-warning tests.
- `test lintFossDebug lintStoreRelease assembleFossDebug` passed with JDK 21, serial execution and a
  command-line-only 4 GiB Kotlin compiler heap. Both variants passed 2,731 tests across 229 suites,
  with no failures/errors/skips. Lint has zero errors or `MissingTranslation` findings; existing
  warnings remain (66 FOSS / 53 Store). The FOSS debug APK was assembled.
- **Physical-device settings acceptance:** on 2026-09-10, the maintainer confirmed that all settings
  changes in the tested debug APK display and behave correctly on a physical device, including the
  screenshot-protection explanation and expanded Freezer descriptions. Device model, OS, font scale
  and locale were not supplied; this is not an exhaustive device/font/locale matrix.
- This confirmation does not establish screenshot/recording/Recents security behavior or TG-001's
  end-to-end legacy APK installation acceptance. These settings changes are prepared for PR review on
  `feat/legacy-apk-install` and are not yet merged or released; the existing app-lock behavior was not changed.

## Rules for the final consolidated ranking

1. Count independent people and channels, not duplicate wording or GitHub reactions alone.
2. Score the **remaining capability**, not a whole historical issue whose easiest half already shipped.
3. Preserve mode boundaries: Root, Shizuku, Dhizuku, and unprivileged Android do not have the same APIs.
4. Treat a hardware/OEM validation matrix as a release gate, not evidence that the underlying feature is unbuilt.
5. Keep safety, privacy, security, and Play-policy decisions visible; never bury them in a generic effort estimate.
6. Do not turn a README “Upcoming” bullet into a delivery promise until a use case, scope, and acceptance criteria exist.

## Immediate next actions

1. Complete TG-001's end-to-end device acceptance and release-policy review before calling it delivered.
2. Append the next Telegram and Reddit messages to the intake table and merge duplicates into canonical opportunities.
3. Re-check any older documentation claim that conflicts with current `dev` before assigning an implementation status.
4. Produce a cross-source priority order only after the manual imports are complete.
5. Separately clean up tracker state for closeable issues; that is administrative work, not evidence of new feature delivery.
