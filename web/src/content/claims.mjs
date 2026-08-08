/**
 * The claims blocklist: statements this site is not allowed to make, and
 * corrections it is required to carry.
 *
 * Every entry here was drafted by someone, checked against the source, and found
 * false. They are recorded because they are all *plausible* — the next person
 * writing this copy will reach for them again. `scripts/check-claims.mjs`
 * enforces them against `dist`, inside the build, so a false claim fails the
 * deploy instead of shipping.
 *
 * ## Why `.mjs` and not `.json`
 *
 * Every pattern here is a regular expression. In JSON they would have to be
 * strings, which means every `\b`, `\s` and `\.` doubles its backslash and no
 * editor or parser checks the result — a mis-escaped pattern is still valid JSON
 * and still compiles to *a* regex, just not the one that was meant. It would
 * also have no place to put flags. A module keeps them as literals that Node
 * validates at load time and that a reviewer can read.
 *
 * ## Rule shape
 *
 * - `id` — stable; `scripts/fixtures/claims/<id>/{fail,pass}` must both exist,
 *   and `fixtures.test.mjs` fails the suite if either is missing.
 * - `kind` — `'forbid'` or `'require'`.
 *   - **forbid**: any `patterns` match on any sentence is a violation.
 *   - **require**: if any `topic` matches the page, at least one `correction`
 *     must match it too. This is the shape that catches a *paraphrase*; `forbid`
 *     alone only catches the exact wrong wording someone already thought of.
 * - `unless` (forbid only) — tested against the matched text plus the 60
 *   characters before it, **not** the whole sentence. The correction for one of
 *   these claims usually contains the forbidden phrase in negated or attributed
 *   form ("that is out of date", "used to say"), and a whole-sentence exemption
 *   would let a negation anywhere in a long paragraph switch the rule off.
 * - `appliesTo` — glob over dist-relative HTML paths. Every rule here is `**`:
 *   each of these statements is false on any page, and narrowing a rule's scope
 *   is the quietest way to turn a gate off.
 * - `allow` — exact, normalised **sentence** strings. An allowlist entry that
 *   matches nothing is itself a failure, so an exemption cannot outlive the copy
 *   it was written for.
 * - `source` — **cited by symbol, never by line number.** One of the gateway
 *   symbols below moved about 180 lines in a single PR; a stale line citation is
 *   worse than no citation, because it reads as verified.
 */

/** Negations and attributions that mean a sentence is discussing a claim, not making it. */
const NEGATED =
  /\b(?:no longer|not|never|none|doesn'?t|does not|did ?n'?t|isn'?t|is not|won'?t|will not|cannot|can'?t|used to|rather than|instead of|no reason)\b/i

/** @type {ReadonlyArray<import('../../scripts/lib/claims-engine.mjs').ClaimRule>} */
export const claimRules = [
  {
    id: 'C1',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      /\b(?:freez\w*|frozen|unfreez\w*|thaw\w*)\b[\s\S]{0,160}?\b(?:factory[ -](?:state|fresh|default|defaults)|comes? back clean|comes? back fresh|starts? over clean|los(?:e|es|t|ing) (?:its|their|your|all|the) data|(?:destroys?|deletes?|wipes?|erases?|clears?) (?:its|their|your|the|all|app) data|(?:app )?data (?:is|are|gets?|will be) (?:lost|deleted|destroyed|wiped|erased|gone))/i,
      /\b(?:factory[ -](?:state|fresh|default)|comes? back clean|los(?:e|es|t|ing) (?:its|their|your|all|the) data|(?:app )?data (?:is|are|gets?) (?:lost|deleted|destroyed|wiped|erased))\b[\s\S]{0,160}?\b(?:freez\w*|frozen|unfreez\w*)/i,
    ],
    unless: NEGATED,
    rationale:
      'INVERTED by PR #314. Freezing a system app used to mean uninstalling it for the user ' +
      'with no -k, so the app came back in factory state — the spec was written against that ' +
      'behaviour. It is no longer true. Root tries `pm disable --user` first; the uninstall rung ' +
      'is gated and the gate answers false for ROOT on every release, so under root it is ' +
      'unreachable and a failed disable is reported as a failure. Where the rung is reachable it ' +
      'carries `-k` (DELETE_KEEP_DATA), so the data directories survive the round trip — and a ' +
      'runtime permission measured across the same round trip came back granted, though that was ' +
      'one shell-granted permission on one API 36 build and says nothing about app-ops. What `-k` ' +
      'does not do is preserve the whole PackageUserState. The fallback still clears FLAG_INSTALLED, so the app ' +
      'reads as uninstalled-for-this-user to anything that does not pass ' +
      'MATCH_UNINSTALLED_PACKAGES — that is worth saying, and it is not the same as losing data. ' +
      'Dhizuku was the exception until PR #332; it now routes through the same disable chain and ' +
      'consults the same gate as Shizuku, so it too reaches the rung only where the platform ' +
      'actually refused, and carries -k when it does.',
    source:
      'RootSystemGateway.freezeSystemApp; ShizukuSystemGateway.freezeSystemApp; ' +
      'FreezePolicy.uninstallFreezeFallbackAllowed; DhizukuSystemGateway.freezeSystemApp',
    allow: [],
  },

  {
    id: 'C1R',
    kind: 'require',
    appliesTo: '**',
    topic: [
      /\b(?:freez\w*|frozen|unfreez\w*)\b[\s\S]{0,80}?\b(?:system apps?|preinstalled apps?|pre-installed apps?|bloatware|carrier apps?)\b/i,
      /\b(?:system apps?|preinstalled apps?|pre-installed apps?|bloatware)\b[\s\S]{0,80}?\b(?:freez\w*|frozen|unfreez\w*)\b/i,
    ],
    correction: [
      /\b(?:app |its |their |your )?data (?:survives?|is preserved|are preserved|is kept|are kept|is retained|stays|remains?)\b/i,
      /\bdata (?:directories|directory) (?:survives?|survive|are kept|is kept|remains?)\b/i,
      /\b(?:keeps?|keeping|preserves?|preserving|preserved|retains?|retaining) (?:its|their|your|the|all|app) data\b/i,
      /\bwithout (?:losing|deleting|erasing|wiping) (?:its|their|your|the|any|app) data\b/i,
    ],
    rationale:
      'The forbid half of C1 only catches phrasings someone already thought of. A page that ' +
      'discusses freezing a preinstalled app and simply says nothing about its data leaves the ' +
      'reader with the pre-#314 assumption, which is now wrong in the direction that matters ' +
      '(they will assume they are about to lose something and not do it). If the topic is on the ' +
      'page, the correction has to be too.',
    source:
      'RootSystemGateway.freezeSystemApp (rung 1 `pm disable --user`, "app data preserved"); ' +
      'ShizukuSystemGateway.freezeSystemApp (rung 3 `pm uninstall -k`)',
    allow: [],
  },

  {
    id: 'C2',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      /\bcache size\b[\s\S]{0,120}?\b(?:fails?(?: explicitly)?|throws?|errors? out|returns? an error|reports? an error|raises? an (?:error|exception))\b/i,
      /\b(?:fails? explicitly|throws?|returns? an error)\b[\s\S]{0,120}?\bcache size\b/i,
    ],
    unless: NEGATED,
    rationale:
      'There is no failure channel to fail through. Both non-root gateways return 0L, and the ' +
      'declared return type is a bare Long — not a Result, not a nullable. The honest sentence ' +
      'is "cache size reads zero without root".',
    source:
      'ShizukuSystemGateway.getAppCacheSize; DhizukuSystemGateway.getAppCacheSize ' +
      '(both `return 0L`, return type `Long`)',
    allow: [],
  },

  {
    id: 'C3',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      /\bno (?:record|list|trace|log|way to (?:know|tell|find out|recover))\b[\s\S]{0,100}?\b(?:froze|frozen|disabled)\b/i,
      /\b(?:froze|frozen|disabled) apps?\b[\s\S]{0,100}?\b(?:cannot|can'?t|could not|couldn'?t) be (?:recovered|restored|found|listed|re-?enabled)\b/i,
      /\b(?:uninstall\w*|remov\w+) (?:Thor|the app)\b[\s\S]{0,120}?\b(?:lose|loses|losing|lost) (?:track of|the list of|the record of)\b/i,
    ],
    unless: NEGATED,
    rationale:
      'Thor ships a recovery path. The "Import Disabled Apps" prompt re-enumerates frozen ' +
      'packages, and every package query that matters carries MATCH_UNINSTALLED_PACKAGES, so the ' +
      'apps themselves are recoverable after a reinstall. What is genuinely unrecoverable is the ' +
      'freezer watchlist and the profiles — say that instead, because it is the true and more ' +
      'useful warning.',
    source:
      'strings.xml `import_disabled_apps_title` / `import_disabled_apps_button`; ' +
      'RootSystemGateway.getApplicationInfoCompat (MATCH_UNINSTALLED_PACKAGES + FLAG_INSTALLED)',
    allow: [],
  },

  {
    id: 'C4',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      /\b(?:google play|play store|play build|play)\b[\s\S]{0,140}?\b(?:is signed with|signs? it with|uses|carries|has) a different (?:signing )?(?:key|certificate)\b/i,
      /\ba different (?:signing )?(?:key|certificate)\b[\s\S]{0,140}?\b(?:google play|play store|play build)\b/i,
    ],
    unless:
      /\b(?:unverified|not verified|never verified|never obtained|has not been (?:verified|obtained)|assume it differs|almost certainly|presumably|probably)\b/i,
    rationale:
      'Almost certainly true and never actually checked — nobody has obtained the certificate ' +
      'Play holds. On a page whose entire value is being accurate, an unverified claim stated as ' +
      'fact is the exact failure being guarded against. Write "unverified; assume it differs".',
    source:
      'No repo source exists, which is the point. app/build.gradle.kts signs both flavours with ' +
      'the same local keystore; what Play re-signs with was never obtained. Spec ' +
      'docs/superpowers/specs/2026-08-01-thor-landing-page-design.md §3.6 row 4.',
    allow: [],
  },

  {
    id: 'C5',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      /\b(?:the )?(?:only|single|sole) difference between (?:the )?(?:two )?(?:flavou?rs|builds|variants)\b/i,
      /\b(?:flavou?rs|builds|variants) (?:differ|are different)\b[\s\S]{0,60}?\b(?:in exactly one|only in one|in one respect|in a single)\b/i,
      /\bidentical (?:in )?(?:every|all) (?:other )?respects?\b/i,
      /\b(?:differs?|differing) in exactly one (?:respect|way|place)\b/i,
    ],
    unless: NEGATED,
    rationale:
      'One *functional* difference, several others. The foss flavour also sets a `-foss` ' +
      'versionNameSuffix and adds proguard-rules-foss.pro, and the benchmark build type is ' +
      'created only for store. "One difference" is a claim a reader can falsify by opening one ' +
      'Gradle file. Locales are NOT one of them any more: localeFilters moved from the foss-only ' +
      'variant hook to onVariants {} once bundle.language.enableSplit was turned off, so both ' +
      'flavours ship the same five. Any page still saying the store build keeps every locale is ' +
      'wrong for the opposite reason this rule exists.',
    source:
      "app/build.gradle.kts: productFlavors.create(\"foss\") (versionNameSuffix, " +
      'proguardFile("proguard-rules-foss.pro")), the store-only benchmark buildType, and the ' +
      'variant-wide androidComponents onVariants hook that sets localeFilters for every variant',
    allow: [],
  },

  {
    id: 'C6',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      /\b(?:keystore|signing key|signing certificate|certificate)\b[\s\S]{0,100}?\b(?:years old|been around for years|for many years|a decade|long-?lived|from years ago|ancient)\b/i,
      /\b(?:years old|long-?lived|a decade old)\b[\s\S]{0,100}?\b(?:keystore|signing key|signing certificate)\b/i,
    ],
    unless: NEGATED,
    rationale:
      'The certificate is valid from 26 January 2025. The download page\'s own apksigner ' +
      'instructions print that date to the reader, so this claim is refuted by the paragraph ' +
      'next to it — the worst possible place to be wrong.',
    source:
      'apksigner output on a released APK (the same command /download tells the reader to run). ' +
      'Spec docs/superpowers/specs/2026-08-01-thor-landing-page-design.md §3.6 row 6.',
    allow: [],
  },

  {
    id: 'C7',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      /-dev-[\s\S]{0,140}?\b(?:pre-?release|do not install|don'?t install|not a (?:real|stable|full) release|unstable|nightly|test build)\b/i,
      /\b(?:pre-?release|do not install|don'?t install|not a (?:real|stable|full) release)\b[\s\S]{0,140}?-dev-/i,
    ],
    unless: NEGATED,
    rationale:
      'Tag shape is not the signal. v1.81.9-dev-82 was a full release and is one of the builds ' +
      "IzzyOnDroid's rbtlog lists as reproducible. Point the reader at GitHub's Pre-release " +
      'badge, which is the field that actually carries that meaning.',
    source:
      'GitHub release v1.81.9-dev-82 (not flagged pre-release) and its rbtlog reproducible-build ' +
      'entry. Spec docs/superpowers/specs/2026-08-01-thor-landing-page-design.md §3.6 row 7.',
    allow: [],
  },

  {
    id: 'C8',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      /\bno request touches any server I run\b/i,
      /\bbecause I run none\b/i,
      /\bI run no servers?\b/i,
      /\bthere (?:is|are) no servers? (?:that )?I run\b/i,
      /\bI (?:do not|don'?t) run any servers?\b/i,
    ],
    unless: /\b(?:used to say|previously said|the old policy|no longer true|until this site)\b/i,
    rationale:
      'False the moment this site ships. Visiting it means Vercel — infrastructure deliberately ' +
      'chosen — sees the request IP. That is precisely the class of overstatement the privacy ' +
      'rewrite exists to correct, and it is the first thing a sceptical reader checks. The ' +
      'honest disclosure names why the IP is collected, who sees it, how long they keep it, and ' +
      'what Cloudflare does and does not see.',
    source:
      'This repository: web/vercel.json and the Vercel project itself. Spec ' +
      'docs/superpowers/specs/2026-08-01-thor-landing-page-design.md §7.',
    allow: [],
  },

  {
    id: 'C9',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      /\b100\s*%\s*offline\b/i,
      /\b(?:fully|completely|entirely|totally) offline\b/i,
      /\b(?:no|without) internet permissions?\b/i,
      /\bdoes not (?:request|declare|use|have|need) (?:the )?internet permission\b/i,
    ],
    unless:
      /\b(?:incorrect|inaccurate|stale|out of date|outdated|no longer true|wrong|used to say|still says|says|claims?|listing|correction|not true)\b/i,
    rationale:
      'The manifest declares android.permission.INTERNET. Two live pages still say otherwise — ' +
      "the old policy at rxspectra.web.app and IzzyOnDroid's summary line, which reads " +
      '"100% offline & FOSS" on a page that lists INTERNET under Permissions. The site may quote ' +
      'either of those to correct it; the `unless` clause is what distinguishes quoting a stale ' +
      'claim from making one.',
    source: 'app/src/main/AndroidManifest.xml: <uses-permission android:name="android.permission.INTERNET" />',
    allow: [],
  },

  {
    id: 'C10',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      /\bandroid\s+\d{1,2}(?:\.\d+)?\b[\s\S]{0,60}?\bapi\s*(?:level\s*)?37\b/i,
      /\bapi\s*(?:level\s*)?37\b[\s\S]{0,60}?\bandroid\s+\d{1,2}(?:\.\d+)?\b/i,
    ],
    rationale:
      'API 36 is Android 16. API 37 has no settled public marketing name. The features draft ' +
      'types "Android 16 (API 37 targetSdk/compileSdk)", so replacing only the numbers with a ' +
      '<Fact /> component would leave the wrong marketing name in place and make it look ' +
      'derived. androidNameForApi throws rather than guessing for exactly this reason, and only ' +
      'minSdk is mapped; the site states the API level and no name.',
    source: 'web/src/lib/repo-facts/parse.ts androidNameForApi (throws on an unmapped level)',
    allow: [],
  },

  {
    id: 'C11',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      /(?<![\w.])thor\.extension\.api\.version\b/i,
      // Any `thor.extension.*` manifest key other than the one that exists. The
      // lookbehind keeps the fully-qualified `com.valhalla.thor.extension.*`
      // class and action names out of it.
      /(?<![\w.])thor\.extension\.(?!class\b)[a-z][\w.]*/i,
    ],
    rationale:
      'This rule is not really about one key. It is about **instructing the reader to do ' +
      'something the app does not implement**, which is the worst kind of confidently-wrong ' +
      'prose a developer-facing page can carry: the reader follows it, it silently does nothing, ' +
      'and they blame their own code. ExtensionManager reads exactly one manifest meta-data key, ' +
      'thor.extension.class, and thor.extension.api.version appears nowhere in app/src.\n\n' +
      'This rationale used to say that key "was invented by a draft". That was wrong, and the ' +
      'truth is more awkward: it is a real convention that Thor-Extensions instructs (its ' +
      'CONTRIBUTING.md prescribes the meta-data block and its submission checklist requires the ' +
      'key) and that every shipped extension declares — stormbringer, thor-automation-extension ' +
      'and VirusScanner all carry it. Thor simply never reads it. So the key is not fabricated; ' +
      'it is inert, which is why a page that tells a reader to add it still misleads them about ' +
      'what makes an extension load. Two further signs the convention is unowned: the extensions ' +
      'repo disagrees with itself about the value (CONTRIBUTING.md and the per-extension READMEs ' +
      'say 2, the per-extension CLAUDE.md files say 1), and nothing anywhere validates it.\n\n' +
      'Keep this rule a forbid rather than adding an unless for a submission-checklist framing. ' +
      'The site describes what Thor does; the checklist is Thor-Extensions\' to document, and a ' +
      'site sentence that names the key implies the loader cares about it. The second pattern ' +
      'generalises to any other key under the same namespace. When the next one turns up, add it ' +
      'here rather than opening a new rule.',
    source:
      'ExtensionManager (data/manager/ExtensionManager.kt) — the only two metaData.getString ' +
      'call sites both read "thor.extension.class". For the other half of the contradiction see ' +
      'Thor-Extensions CONTRIBUTING.md (the meta-data snippet and the submission checklist) and ' +
      'the AndroidManifest.xml of each extension under verified/.',
    allow: [],
  },
  {
    id: 'C12',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      // "the store build is signed by Google", in either order.
      /\b(?:store|play)\s+(?:build|flavou?r|variant|apk)\b[\s\S]{0,90}?\bsigned\s+by\s+Google\b/i,
      /\bsigned\s+by\s+Google\b[\s\S]{0,90}?\b(?:store|play)\s+(?:build|flavou?r|variant|apk)\b/i,
      // Google "re-signing" what CI built. Nothing of ours is re-signed: CI
      // uploads an AAB with skip_upload_apk, so Play generates the APKs itself
      // and there is no artefact of ours for it to replace a signature on.
      /\bGoogle\b[\s\S]{0,40}?\bre-?signs?\b/i,
      /\bre-?signed\s+by\s+Google\b/i,
    ],
    unless:
      /\b(?:generates?|produces?|from the (?:uploaded )?(?:aab|app bundle)|app bundle|channel|not (?:a )?flavou?r|build type)\b/i,
    rationale:
      'Signing is a **build-type** property here, not a flavour property. `signingConfigs.create' +
      '("release")` is assigned by the release build type, and neither `productFlavors` block ' +
      'mentions a signingConfig at all — so `store-release.apk` and `foss-release.apk` are signed ' +
      'with the same keystore. What actually differs is the *channel*: Play receives an AAB ' +
      '(fastlane runs with skip_upload_apk) and generates its own device APKs, which is why a Play ' +
      'install carries Play\'s certificate. "Google re-signs it" describes a different and ' +
      'non-existent step, in which an APK we built is taken and its signature swapped.\n\n' +
      'This matters beyond pedantry because the site tells readers to verify a signature ' +
      'themselves. A reader who believes the flavour determines the signer will compare the wrong ' +
      'two things and conclude the build was tampered with. The exact sentence this rule was ' +
      'written for shipped on the download page: "the `store` build is signed by Google and is not ' +
      'reproducible; the `foss` build is signed by me and is." Both halves of the signing contrast ' +
      'are false; only the reproducibility contrast survives.\n\n' +
      'The `unless` window exempts the channel-attributed forms — "Play generates the APKs from ' +
      'the uploaded app bundle" — which are the true way to say this.',
    source:
      'app/build.gradle.kts — signingConfigs.create("release") is applied by the release build ' +
      'type, and the store/foss productFlavors blocks set only dimension, versionNameSuffix and a ' +
      'ProGuard file. fastlane\'s upload_to_play_store runs with skip_upload_apk.',
    allow: [],
  },
  {
    id: 'C13',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      /\bAutomationExtension\b[\s\S]{0,90}?\b(?:is gone|are gone|was removed|were removed|has been removed|no longer exists|does not exist|no longer ships|was deleted|has been deleted|was dropped|has been dropped|is no longer part of)\b/i,
      /\b(?:removed|deleted|dropped|retired)\b[\s\S]{0,60}?\bAutomationExtension\b/i,
    ],
    // The true statement is about dispatch, not existence: Thor stopped calling
    // onTrigger and replaced it with a provider. Sentences that say so are fine.
    unless: /\b(?:dispatch\w*|onTrigger|broadcast|invoke\w*|call\w*|trigger\w*|provider)\b/i,
    rationale:
      'The interface still ships in the artefact the site tells authors to depend on. ' +
      'thor-extension-api 3.0.0 contains AutomationExtension in both classes.jar and the sources ' +
      'jar, and the extension template\'s own entry class implements it. What Thor retired is the ' +
      'dispatch, not the type: ExtensionOpsProvider replaced "the old broadcast to onTrigger ' +
      'path", and ExtensionManager casts a loaded entry class to ThorExtension rather than to ' +
      'AutomationExtension.\n\n' +
      'Telling an author the type is gone is worse than a harmless imprecision, because it is ' +
      'actionable in the wrong direction: they delete a class their template still implements, ' +
      'and the resulting build failure has nothing to do with the sentence that caused it. Say ' +
      'that Thor no longer calls onTrigger instead — which the `unless` window allows.',
    source:
      'thor-extension-api 3.0.0 (classes.jar and -sources.jar both contain ' +
      'com/valhalla/thor/extension/api/AutomationExtension); ExtensionOpsProvider ' +
      '(data/provider/ExtensionOpsProvider.kt) documents replacing the onTrigger path; ' +
      'ExtensionManager (data/manager/ExtensionManager.kt) casts to ThorExtension.',
    allow: [],
  },
  {
    id: 'C14',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      /\bno third-?party SDKs?\b/i,
      // "exactly one network request", in either order.
      /\b(?:exactly|just|only)\s+one\b[\s\S]{0,60}?\bnetwork\s+(?:request|connection)s?\b/i,
      /\bnetwork\s+(?:request|connection)s?\b[\s\S]{0,60}?\b(?:exactly|just|only)\s+one\b/i,
      // "exactly one source file ... opens a connection" — the same claim with
      // the word "network" left out, which is how it first evaded this rule.
      /\b(?:exactly|just|only)\s+one\s+(?:source\s+)?file\b[\s\S]{0,60}?\bopens?\s+an?\s+connection\b/i,
      /\b(?:makes?|opens?|sends?)\s+no\s+network\s+(?:requests?|connections?)\b/i,
      /\bnever\s+(?:makes?|opens?|sends?)\s+an?\s+network\s+(?:request|connection)\b/i,
    ],
    // The window is the match plus the 60 characters before it, so a scope named
    // in the same clause satisfies this: "the foss build makes no network
    // request", "Thor's own code makes exactly one kind of network request".
    unless:
      /\b(?:foss|f-?droid|izzyondroid|own code|own sources|its own|app\/src|source tree)\b/i,
    rationale:
      'There are two flavours and they do not contain the same code. `storeImplementation` pulls ' +
      'in Google Play Billing (app/build.gradle.kts), so the Play build ships a third-party SDK ' +
      'that opens its own connections on its own schedule. A sentence that says "no third-party ' +
      'SDK" or "exactly one network request" without naming which build it is about is therefore ' +
      'false for half the people reading it — and it is false for exactly the half who did not ' +
      'choose their build for privacy reasons and are least likely to check.\n\n' +
      'This is the rule this file exists for and it is the one that was missing. A flavour-blind ' +
      'privacy absolute shipped on the privacy page — "no third-party SDK", "exactly one kind of ' +
      'network request" — and passed every gate, because the other rules test claims about ' +
      'behaviour and this is a claim about composition. Composition claims fail silently: nothing ' +
      'in a build of the site knows which Gradle configuration a dependency was declared on.\n\n' +
      'The fix is never to weaken the claim, it is to scope it. "The FOSS build has no third-party ' +
      'SDK" is true, verifiable and stronger than the unqualified form, because a reader can act ' +
      'on it. The `unless` window accepts any scope in the same clause — a flavour name, "Thor\'s ' +
      'own code", "its own sources" — and rejects the bare absolute.',
    source:
      'app/build.gradle.kts — storeImplementation(libs.play.billing) and ' +
      'storeImplementation(libs.play.billing.ktx) are declared on the store configuration only, so ' +
      'foss-release.apk does not contain them. The store BillingProcessorImpl is a @Single whose ' +
      'init calls connectToBilling(), and MainScreen injects BillingProcessor as a default ' +
      'parameter, so that connection opens when the app opens rather than on demand.',
    allow: [],
  },

  {
    id: 'C15',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [
      // "granted permissions all stay", "every permission survives", "keeps its
      // permissions" — the quantifier is the violation, not the verb.
      /\b(?:all|every|any|each|its|their|your|the)\s+(?:granted\s+|runtime\s+)?permissions?\b[\s\S]{0,40}?\b(?:all\s+)?(?:stay|survive|survives|are\s+(?:kept|preserved|retained|intact)|remain|is\s+(?:kept|preserved|retained))\b/i,
      /\bpermissions?\s+(?:all\s+)?(?:stay|survive|are\s+preserved|are\s+kept|remain\s+granted)\b/i,
      /\b(?:keeps?|keeping|preserves?|preserving|retains?|retaining)\s+(?:all\s+|every\s+)?(?:its|their|your|the)?\s*(?:granted\s+|runtime\s+)?permissions?\b/i,
    ],
    // Satisfied by a scope in the same clause: a count ("one permission"), the
    // measurement ("was measured", "came back granted"), or the named limits
    // ("one build", "app-ops were never measured").
    unless:
      /\b(?:one|single|a\s+runtime)\s+permission\b|\bmeasured\b|\bapp-?ops\b|\bone\s+(?:platform\s+)?build\b|\bnot\s+measured\b|\bshell-?granted\b/i,
    rationale:
      'The evidence is one runtime permission, granted from the shell rather than by tapping ' +
      'Allow, measured across one `pm uninstall -k` round trip on one API 36 build. App-ops were ' +
      'never measured at all, and neither were user-granted permissions. "Granted permissions all ' +
      'stay" turns that single observation into a guarantee about every grant on every supported ' +
      'platform.\n\n' +
      'This is the same failure mode as C14 and it shipped for the same reason: the rules test ' +
      'claims about behaviour, and a quantifier is not behaviour. The features page had the ' +
      'scoping exactly right — "one permission ... on one platform build, and app-ops were never ' +
      'measured, so read it for the scope it has" — and the homepage, which far more people read, ' +
      'promised the unqualified version. One deployment, both sentences, every gate green.\n\n' +
      'A reader acts on this one: it is the sentence that decides whether they freeze a banking ' +
      'or authenticator app. Scope it or drop it; do not round it up.',
    source:
      'ShizukuSystemGateway.freezeSystemApp (rung 3 `pm uninstall -k`, DELETE_KEEP_DATA); ' +
      'the API 36 measurement recorded in web/src/pages/features.mdx under "the fallback", which ' +
      'states its own scope. No app-op or user-granted-permission measurement exists.',
    allow: [],
  },
]

export default claimRules
