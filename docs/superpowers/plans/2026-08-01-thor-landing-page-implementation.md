# Thor Landing Page — Implementation Plan

**Spec:** [`docs/superpowers/specs/2026-08-01-thor-landing-page-design.md`](../specs/2026-08-01-thor-landing-page-design.md) (approved 1 Aug 2026).
Where this plan and the spec disagree, the spec wins and the divergence is named inline.

Produced by a six-agent planning workflow (five parallel slices + a synthesis pass), then reviewed
by an adversarial verifier that re-derived every claim against the repo, the npm registry, live DNS
and a real `create-astro` scaffold. The verifier found nineteen defects; all nineteen are folded in
below and listed under [Reconciliation](#reconciliation-what-the-adversarial-pass-changed).

---

## What we are building

A static marketing and documentation site for Thor at `thor.trinadhthatakula.com`: seven routes
(`/`, `/features`, `/download`, `/faq`, `/privacy`, `/extensions-policy`, `/build-an-extension`)
plus a 404, built with Astro, static output, no backend, no forms, no analytics, and no client
JavaScript except the theme toggle. It lives in `web/` inside this repository and deploys from `dev`
via Vercel.

Its visual language is the app's literal Asgardian palette and Outfit/Fira Code pairing. **Its
defining risk is not bugs but confidently-wrong prose**, so every version number and SDK level is
derived from the repo at build time, and a claims blocklist is enforced mechanically against the
built HTML.

---

## Ground truth

Verified on disk on 1 August 2026, at `dev` = `d8137191`.

**Repository**

- `web/` did not exist. `settings.gradle.kts` includes exactly `:app`, `:bypass`, `:vm-runtime` —
  `web/` adds no Gradle module.
- `gradle.properties`: `initialVersionCode=1921` on **line 51**, `versionCode=1931` on **line 54**.
  No `versionName` property (lines 55–57 are a comment saying so explicitly). The ordering trap is
  real: a first-match, substring or case-insensitive lookup yields 1921 → "1.92.1".
- `gradle/libs.versions.toml` `[versions]`: `compileSdk = "37"`, `targetSdk = "37"`,
  `minSdk = "28"`, `thorExtensionApi = "3.0.0"`. Eight bare keys collide between `[versions]` and
  other tables, so a whole-file regex is unsafe.
- Version arithmetic already lives in three places — `app/build.gradle.kts`,
  `.github/scripts/sync-shizu-changelog.sh:25`, `.github/scripts/check-shizu-manifest.sh:249` — the
  latter two carrying mutual `# LOCKSTEP:` comments. The web reader is the fourth.
- `pr-ci.yml` has no `paths` filter at any level, and `build-and-test` is the required status
  context on the `dev` and `master` rulesets. `dev-check.yml` and `production-deploy.yml` *do*
  filter, and their rulesets do not require them.
- `.github/dependabot.yml` has no npm ecosystem. `.github/labeler.yml` has no website rule, and its
  `documentation` rule is `**/*.md` (unscoped).

**The app**

- `presentation/theme/` contains exactly `Color.kt`, `Theme.kt`, `Type.kt`. There is **no**
  `Dimens.kt` — no spacing, radius or elevation scale to inherit.
- `Theme.kt` overrides exactly `background`, `surface`, `surfaceVariant` to black under AMOLED.
  Both schemes omit `onErrorContainer`; the light scheme also omits `inversePrimary`,
  `inverseSurface`, `surfaceTint`.
- `app/src/main/res/font/` ships nine static Outfit faces plus `firacode_variable.ttf`. The repo
  carries **no font licence text**.
- `ExtensionManager.kt` reads exactly one manifest meta-data key: `thor.extension.class` (lines 109
  and 246). `thor.extension.api.version` **appears nowhere in `app/src`**.
- **PR #314 is merged** (`d8137191`, 04:12 UTC). System-app freezing now tries `pm disable` first
  and only falls back to `pm uninstall -k` where the platform actually refused. See
  [The C1 inversion](#the-c1-inversion) — this is the single most consequential fact in this plan.

**Content drafts**

- Seven drafts under `docs/site-content/`, ~14,300 words, **untracked** (`.gitignore:42`). They do
  not appear in a `git worktree`, a CI checkout, or a fresh clone.
- `index.md` names **five** screenshot slots, not four. Two of them need mutually exclusive device
  states (one with no privilege mode available, one with at least two).
- 334 asterisk runs across the drafts, against the spec's flat no-italics rule.
- Review scaffolding in every file that must not ship, including the sentence *"I have deliberately
  not published it on your behalf."*

**Toolchain (verified against the live registry)**

- `astro@7.1.6`, `engines.node >= 22.12.0`; local node is 26.5.1.
- `typescript@latest` is **7.0.2**, while `@astrojs/check@0.9.10`'s peer range stops at `^6.0.0`.
  The newest stable 6.x is **6.0.3**. An unpinned `npm i -D typescript` breaks `astro check`, and
  therefore the deploy.
- `lychee` has no npm distribution; the npm package of that name is an abandoned 2013 database
  library.
- DNS: `thor.trinadhthatakula.com` already CNAMEs to `cname.vercel-dns.com` (grey cloud) and chains
  to Vercel anycast. **No DNS change is needed.** A proxied `*` wildcard on the zone means *any*
  subdomain resolves, so "it resolves" is not a test.

---

## The C1 inversion

The spec's §3.6 claims blocklist exists to stop seven specific false statements from reaching the
site. Row 1 was *"freezing preserves app data"* — false, because a system app was frozen by
uninstalling it for the user with no `-k`.

**PR #314 merged this morning and inverted it.** What `dev` does now:

| Gateway | Rung 1 | Rung 2 | Rung 3 |
|---|---|---|---|
| Root | `pm disable --user N` — data preserved | `pm uninstall -k --user N`, **unreachable**: the gate answers `false` for `ROOT` on every release | — |
| Shizuku | `IPackageManager.setApplicationEnabledSetting` via `:bypass` | `pm disable-user --user N` | `pm uninstall -k --user N`, only where the platform **refused** the disable |
| Dhizuku | *(unconverted)* uninstalls for the user, unconditionally, without `-k` | — | — |

So the honest statements now are:

1. **App data survives both rungs.** `-k` sets `DELETE_KEEP_DATA`; measured on-device, the CE and DE
   data inodes are byte-identical across the round trip, and runtime permission grants survive too.
2. **The fallback still clears `FLAG_INSTALLED`**, so the app reads as uninstalled-for-this-user to
   everything that does not pass `MATCH_UNINSTALLED_PACKAGES`.
3. **On Android 17 the fallback does not exist at shell uid.** `pm uninstall -k --user N` on a
   system app answers `Failure [only root can delete system app for a particular user]`. A Shizuku
   user on Android 17 whose device refuses `disable` cannot freeze that system app at all — the
   chain fails closed rather than doing something else.
4. **Dhizuku is the exception.** It still removes for the user unconditionally and without `-k`.

Three consequences, all of them prerequisites rather than follow-ups:

- **C1's regexes invert.** The old rule banned "freeze … preserves data" — now the true statement —
  and *required* pages to say "factory state" / "loses its data" — now false. Rewriting C1 comes
  before the blocklist is written, not after.
- **Two drafts are stale on this exact fact** (`index.md:53-56`, `faq.md:107-109`). They are
  corrected at source before transcription, not patched afterwards.
- **The spec's §8.4 product gap is partly closed.** "Freezing a system app destroys its data and no
  string warns about it" is no longer the gap it was. What remains is narrower and still worth an
  issue: Dhizuku, and the Android 17 Shizuku dead end.

---

## Decisions this plan makes that the spec left open

| # | Decision | Why |
|---|---|---|
| D1 | **MDX** for the seven routes; `404.astro` stays `.astro`. | `.md` cannot interpolate a derived fact, and a misspelled `{{minSdk}}` placeholder renders literally into production with nothing failing. MDX plus a typed `<Fact name="minSdk" />` makes a typo an `astro check` error. |
| D2 | **Vanilla CSS custom properties**, no Tailwind. | The palette is the one thing that must survive a later tooling change, and the site is seven static pages. |
| D3 | **A first-party offline link checker** (`web/scripts/check-links.mjs`), not lychee, for the build gate. `lycheeverse/lychee-action` still runs the weekly *external* sweep on GitHub Actions. | Diverges from spec §6.2's "one tool, two modes". lychee has no npm distribution, so the build gate would depend on a pinned tarball download succeeding on Vercel's build image on every deploy — and a `preinstall` hook is wiped by `npm ci`, while a `postinstall` hook is skipped when Vercel restores a `node_modules` cache. A ~200-line checker over `dist` has no download, no network, and validates `#fragments` and directory-style routes precisely, which is the part that actually protects the anchor contract. The weekly external check keeps lychee, where the action owns installation. |
| D4 | `build` = `check:types` → `astro build` → `check:links` → `check:claims` → `check:markup`. Accessibility, contrast and unit tests are CI-only. | The spec names two build gates; the claims blocklist earns a third because a false claim shipping is the failure mode it exists to prevent, and `check:markup` earns a fourth because the no-italics rule would otherwise live only in a path-filtered, non-required workflow. |
| D5 | **Web CI is a new `.github/workflows/web-ci.yml`**, path-filtered, branch-scoped to `master`/`dev`, and never added to a ruleset. `pr-ci.yml`'s trigger is untouched. | The spec's no-path-filter rule protects `pr-ci.yml` *because* `build-and-test` is required; that argument does not transfer to a new non-required workflow. |
| D6 | **The Ignored Build Step lives in `web/vercel.json` as `ignoreCommand`**; the dashboard field stays empty. | In-repo makes it reviewable and versioned, and an empty dashboard field removes the undocumented precedence question. |
| D7 | **`/download` follows the draft's nine-H2 structure**, not §3.5's fourteen-item order. | §3.5's own second structural requirement — that the uninstall consequence is *not* framed as a cost of switching channels — is incompatible with an order that puts "switching channels" at position 12. The fourteen-item list is the pre-critique order. |
| D8 | **The hero's secondary CTA targets "You may not need root."** | §3.4 numbers content sections 1–4 independently of the hero, making "section 4" the fifth item. Matches `index.md`. |
| D9 | **`repoFacts.versionName` is derived and tested but has no rendered consumer at launch.** | §6.5 mandates deriving it; §8.2 bars a version number from `/download` until the stable channel is settled. Its doc comment names it "the version in source" so nobody wires it into a "Latest release" badge by reflex. |
| D10 | **`:root` carries dark; `@media (prefers-color-scheme: light)` applies light; `localStorage` overrides both.** | `prefers-color-scheme: no-preference` was dropped from Media Queries Level 5, so "defaulting to no preference" is unimplementable literally. Dark-first is the stated intent. |
| D11 | **`/styleguide` is a rest route whose `getStaticPaths` returns `[]` when `import.meta.env.PROD`.** | Astro has no per-page build-exclusion API, and an `_`-prefixed file is excluded from dev too, which defeats the purpose. This renders in `astro dev` and emits nothing in `astro build`. |
| D12 | **`DeviceFrame` renders a labelled placeholder when given no image.** | `<Image>` resolves at build time, so a missing screenshot import is a hard build error, and git carries no empty directory. This keeps the build green from Phase 4 through Phase 8 without committing fake PNGs. |
| D13 | **`thor.extension.api.version` is dropped from `/build-an-extension` entirely.** | `ExtensionManager` reads only `thor.extension.class`. Instructing developers to declare a key the app ignores is precisely the confidently-wrong prose the blocklist exists to prevent. A new blocklist rule covers the whole class. |

---

## Phases

Branch `feat/web-landing-page` off `dev`. Never commit to `dev` or `master`. Stage explicit paths —
never `git add -A`, because `docs/audit/` and `docs/enforcement/` must never be committed.

### Phase 1 — Repo hygiene and scaffold ✅

1. **Snapshot the drafts** to `~/thor-site-drafts-2026-08-01/`, outside the working tree, because
   they are untracked and a worktree or fresh clone would lose them.
2. **Add the four `.gitignore` entries** — `web/node_modules/`, `web/dist/`, `web/.astro/`,
   `.vercel` — as a standalone commit, **before** any `npm install`, so `node_modules` is never
   stageable for even one commit. The repo's `/build` and `/*/build` rules are anchored and match
   none of them.
3. **Hand-author `web/package.json`** rather than running `create-astro`. The scaffolder has no
   `--typescript` flag, prompts for AI-agent files without `-y --no-ai`, refuses a non-empty
   directory, and writes a `web/.gitignore` that duplicates the root entries. Writing the manifest
   directly gives an exact dependency set and one authoritative ignore file.
4. **Pin `typescript` to `^6.0.3`** with the reason recorded in `web/README.md` and mirrored as a
   Dependabot ignore rule. `engines.node >= 22.12.0` matches Astro's own.
5. **`astro.config.mjs`**: `site` set (canonical URLs, and the link checker uses it to tell an
   internal absolute URL from an external one), static output with no adapter, `mdx()`,
   `build.format: 'directory'` + `trailingSlash: 'never'` so the dev server agrees with
   `vercel.json`, and Shiki's `css-variables` theme so highlighting stays inside our palette.

### Phase 2 — Build-time fact derivation ✅

Reads exactly two files outside `web/`: `gradle.properties` and `gradle/libs.versions.toml`. That
list is identical to the Ignored Build Step pathspec — adding a third derived fact means editing
both.

- **`parse.ts`** — pure, no I/O. Exact key matching after trimming (never substring, never `/i`);
  last duplicate key wins, as `java.util.Properties` does; TOML parsed with `smol-toml` and scoped
  to `[versions]`; version strings returned **as strings** (the repo's `tr -dc '0-9'` idiom turns
  `"3.0.0"` into `300`). Every validation throws with a message naming file, key and fix.
  `androidNameForApi` is hand-maintained and **throws** on an unmapped level — API 37 has no
  settled public name and inventing one is the exact failure mode this module prevents.
- **`read.ts`** — `findRepoRoot` walks up to `settings.gradle.kts`. Deliberately **not** resolved
  from `import.meta.url`: Vite bundles `src/lib/**` into the SSR output, so that URL points at an
  emitted chunk — green under Vitest, broken under `astro build`. Every `ENOENT` message names
  Vercel's "Include files outside the Root Directory" setting as the usual cause.
- **`index.ts`** — one frozen `RepoFacts`, computed at module scope.
- **Tests** — `parse.test.ts` pins `deriveVersionName` to values fixed by *documentation*, not by
  current repo state, and carries the `initialVersionCode`-before-`versionCode` regression case.
  `contract.test.ts` asserts **shape only, never values**, so a `chore(release)` commit — the one
  commit that must never be blocked by the website — cannot redden it. `lockstep.test.ts` re-runs
  the shell arithmetic from `.github/scripts/check-shizu-manifest.sh` through `/bin/sh` and compares
  it to ours; that is a genuinely independent implementation, unlike asserting our own function
  against itself.
- **`Fact.astro`** — `name` typed as `keyof RepoFacts`, so a typo is an `astro check` error. This is
  the single grep target for "where does the site print a number".

### Phase 3 — Design tokens, fonts, theme runtime

- **`tokens.css`**, three scopes: `:root` = dark, `@media (prefers-color-scheme: light)` +
  `[data-theme='light']` = light, `[data-theme='dark'][data-amoled='true']` = AMOLED. Variables
  named after the Material 3 tokens the app actually sets, so a reviewer can diff against `Color.kt`
  line by line. Transcribe only the spec's values; never derive one scheme from the other; never
  take "whatever the app resolves", because unwired tokens fall through to Material 3 purple
  baselines. AMOLED overrides **exactly three** variables. Do **not** alias `--surface-variant` to
  `--surface-container-highest` — both are `#262626` in dark, but AMOLED splits them, so the alias
  looks correct in every mode anyone checks.
- **Spacing, radius, measure, focus ring, motion** — the app has none to inherit, so these are
  defined here as placeholders in one file, ready for the owner's values as a one-file edit.
- **Fonts** — the ten shipped faces, subset to Latin, converted to woff2 via the repo's existing
  `.tools-venv` fonttools convention. `font-display: swap`, preload only above-the-fold faces, Fira
  Code declared as a variable face. **No italic `@font-face` at all.** Ship OFL-1.1 — the repo
  carries no font licence today and self-hosting on a public site creates a redistribution
  obligation.
- **Theme runtime** — a blocking inline script in `<head>` sets `data-theme`/`data-amoled` before
  first paint. AMOLED defaults **false**, matching what the app really renders (`ThorTheme`'s
  default is `true` but both call sites pass `prefs.useAmoled`, which is false). This is the only
  client JavaScript on the site.

### Phase 4 — Layout, components, routes

`BaseLayout` owns `<html lang="en">`, meta, canonical, Open Graph, the theme script, a skip link,
`<Nav>`, `<slot>`, `<Footer>`. Eight stub routes land here so every internal link has a target from
the first commit and the link gate has something to resolve against.

Components: `Nav` (Fira Code labels, `aria-current`, hosts the toggle), `Footer` (all seven routes,
the repo, Telegram, and **all five** funding routes from `FUNDING.yml` — the draft's footer line
omits PayPal), `Hero`, `DeviceFrame` (D12), `FeatureBand`, `SectionIndex`, `Callout`, `CodeBlock`.

Two accessibility constraints are load-bearing and easy to get wrong: the hairline tokens are
1.62:1 and 2.11:1, correct as decorative separators but never as the sole boundary of a control or a
data table rule (use `--outline`, 4.25:1 / 4.19:1); and `--surface-container-lowest` is `#FFFFFF` in
light but `#000000` in dark, so a code block using it is invisible on an AMOLED page.

`/styleguide` per D11 — every token, all fifteen type roles, every component, in light, dark and
AMOLED. It is the only practical way to catch an AMOLED regression, since AMOLED is off by default.

### Phase 5 — Correctness gates and web CI

- **`check:links`** (D3) — walk `dist/**/*.html`, collect every `id`/`name` per page, resolve every
  internal href including fragments, understand directory-style routes. Must be proved in both
  directions: a broken link and a broken fragment fail, and a *correct* `/faq` link and a correct
  `#you-may-not-need-root` fragment do not false-fail. A checker that fails on correct links gets
  disabled within a week.
- **The claims blocklist** — one entry per §3.6 row, **with C1 rewritten for the merged #314**, plus
  derived entries: C8 forbids *"I run none"* / *"no request touches any server I run"* (false the
  moment the site ships); C9 forbids *"100% offline"* / *"no internet permission"* as assertions
  about Thor; C10 forbids pairing an Android marketing name with API 37; C11 forbids documenting
  `thor.extension.api.version` (D13). Two rule shapes — `forbid` for always-wrong phrasings and
  `require` for "if a page touches this topic it must carry the correction", which is what actually
  catches a paraphrase. **Cite sources by symbol, not line number.**
- **`check:claims`** — runs against `dist`, not `src`, so copy assembled from components and
  `<Fact>` is covered. Normalises smart quotes and dashes before matching: MDX runs smartypants by
  default, so a regex written with straight quotes silently never matches. The allowlist matches on
  exact sentence equality, and **an allowlist entry that matches nothing is itself a failure**, so
  exemptions cannot rot.
- **`check:markup`** (D4) — no `<em>`/`<i>` in any built page. In `build`, not CI, because 334
  asterisk runs across the drafts make this the likeliest content regression on the site.
- **Must-fail fixtures** — per rule id, one fixture in the tempting phrasing and one in the
  corrected phrasing, plus a meta-test asserting every rule has both. This is the difference between
  a gate and a decoration: an unanchored regex, a wrong glob, or a `dist` path that does not exist
  all exit 0 forever.
- **`check:a11y`** (CI only) — axe-core over jsdom with an explicit `runOnly` list. `color-contrast`
  is **disabled**: jsdom has no layout or CSSOM cascade, so it can only ever return "incomplete",
  and a rule that can only return "incomplete" is worse than no rule. Contrast is covered by a
  separate arithmetic test over the token values.
- **`web-ci.yml`** (D5) — path-filtered on `web/**`, `gradle.properties`,
  `gradle/libs.versions.toml`, scoped to `branches: [master, dev]` to match `pr-ci.yml`, with a
  header comment stating that it is path-filtered **because** it must never become a required check.
- **A comment in `pr-ci.yml`** recording why it has no path filter and must never gain one:
  `build-and-test` is required, and GitHub reports a path-skipped required check as pending forever,
  so a site-only PR would be unmergeable. No behaviour change.
- **`web/**` added to `dev-check.yml` and `production-deploy.yml`'s `paths-ignore`** — both already
  filter `docs/**` and `*.md` on the stated rationale that a change which cannot reach the APK
  should not spend seven minutes proving it. Neither is a required check on its ruleset.

### Phase 6 — Assets

Owned by no phase in the first draft of this plan, and consumed by three: favicon set, a 1200×630
**dark** Open Graph image, `@astrojs/sitemap` (with `/styleguide` excluded), and `robots.txt`.
Favicons live in `public/` — so the "no images in `public/`" assertion narrows to "no *screenshots*
in `public/`", since `<Image>` only optimises `src/`.

### Phase 7 — Deploy plumbing

`web/vercel.json` carries `framework`, `outputDirectory`, `buildCommand`, `trailingSlash: false`,
and the Ignored Build Step with the `:/` prefixes intact:

```
git diff --quiet HEAD^ HEAD -- . ':/gradle.properties' ':/gradle/libs.versions.toml'
```

The `:/` prefix is load-bearing. The step runs in the Root Directory, where a bare
`gradle.properties` pathspec resolves to `web/gradle.properties`, matches nothing, and exits 0 for
*every* commit — so the site would never rebuild at all, silently.

Dashboard-only: Root Directory `web/`, Production Branch `dev`, and **"Include files outside of the
Root Directory in the Build Step" ON** — verify visually; turning it off breaks `repo-facts` with a
file-not-found deep in the build. Leave the Build Command at its default: overriding it to the
preset's `astro build` makes `@vercel/static-build` stop falling through to the `package.json`
script, and **all four gates disappear from the deploy path with no error**.

DNS needs no change. Add the domain in Vercel and let it issue the certificate. **Never fix a 526 by
switching Cloudflare's SSL mode to Flexible** — it appears to work and serves the site over an
unencrypted hop to the origin, on a site whose privacy stance is the argument.

### Phase 8 — Content transcription

Seven pages, ~14,300 words, from the snapshot — with `index.md` and `faq.md` corrected for the
merged #314 **before** transcription, not after.

Applies to every page: strip all review scaffolding (`> **Status: draft for owner review.**`,
`Notes for the owner`, `Open questions`, `Settled 1 August 2026`, `⚠️ Confirm` blockquotes). Every
asterisk run needs a deliberate re-treatment — bold, a callout, or nothing — never `<em>`. No typed
version numbers, SDK levels or extension API versions anywhere; use `<Fact>`.

Page-specific traps the verifier caught:

- **`/faq`** has **six** `##` headings, not five — five numbered categories plus "Still stuck?".
- **`/features`** types *"Android 16 (API 37 `targetSdk`/`compileSdk`)"*. API 36 is Android 16; 37 is
  unnamed. Replacing only the numbers with `<Fact>` ships the wrong marketing name.
- **`/extensions-policy`** quotes the in-app disclaimer. Byte-equality against `strings.xml` is
  impossible — the draft renders `**NOT**` where the resource has plain `NOT`, and the resource uses
  `\n\n` escapes. The comparison normalises emphasis and escapes.
- **`/build-an-extension`** hardcodes the extension API version twice and documents a meta-data key
  the app ignores (D13).
- **Heading anchors are a silent external contract.** `/extensions-policy`'s anchors are the
  intended in-app Extension Manager deep-link target. The link checker sees only links inside
  `dist`, so a heading reword breaks an APK-side link with a green build — hence a checked-in
  anchor fixture.
- **Close by retiring the drafts.** The spec is emphatic that once a page ships, the page's own
  source is the single source of truth: *"a second tracked copy would drift and then get quoted back
  as if it were current, which for a privacy policy is worse than having no copy."*

### Phase 9 — Screenshots

**Five** captures, not four, across **two device profiles** — the one thing automation cannot save.
The ten existing files under `fastlane/metadata/.../phoneScreenshots/` are store crops in the wrong
states, usable as an aspect-ratio reference and nothing else.

| # | Capture | Constraint |
|---|---|---|
| 1 | Home screen, bento grid | hero |
| 2 | Freezer list, active and frozen side by side | ends on the greyscale pinned shortcut |
| 3 | The refusal dialog on an Unsafe system app | **no "proceed anyway" button in frame** — the refusal is the argument |
| 4 | App list, permission filter chips expanded | device with **no** privilege mode enabled |
| 5 | Settings → Work Mode selector | device with **at least two** modes available — mutually exclusive with #4 |

Dark theme throughout. Commit raw captures only; framing is the CSS `DeviceFrame`. Both device
profiles and both framing constraints go on the release checklist, so a re-capture after a UI change
does not silently produce the wrong frame.

### Phase 10 — Scheduled and advisory tooling

- **Weekly external link check** — `lycheeverse/lychee-action` against the **live site**, seeded
  from the sitemap. Not against `web/dist`, which is gitignored and absent from a fresh checkout: as
  written that job either builds the whole site again or matches an empty glob and exits 0 forever,
  which is the exact silent-pass shape this plan warns about elsewhere. Modelled on
  `shizu-store-audit.yml`, action pinned by SHA, opens an issue on failure.
- **Lighthouse as a warning** — every assertion at `warn`, `continue-on-error`, path-filtered
  because it must never become a gate. Guard on `head.repo.full_name == github.repository`, because
  forked PRs have no secrets. Without an `x-vercel-protection-bypass` header the run silently scores
  Vercel's authentication wall and reports a plausible number for the wrong page.
- **Dependabot, labeler, CodeQL** — an npm ecosystem at `/web` with a **typescript ignore rule**
  (without it, a routine bump to 7.x breaks `astro check` and therefore the deploy); an
  `area: website` rule for `web/**`; and `javascript-typescript` added to CodeQL, which today gives
  a brand-new JS surface zero coverage.

### Phase 11 — Launch prerequisites

1. **Correct two external stale claims.** The published policy at `rxspectra.web.app` still says
   Thor has no internet access, and IzzyOnDroid's *summary line* says "100% offline & FOSS" while
   the same page lists `INTERNET`. The homepage trust note contradicts both directly, and they are
   the first thing a reader who takes "check it yourself" seriously will check.
2. **File the narrowed product-gap issue** — Dhizuku's unconverted path, and the Android 17 Shizuku
   dead end. Not the original "freezing destroys data" gap, which #314 closed.
3. **Pre-launch sweep** — all gates green, certificate valid, five screenshots in place, and
   `grep -riE 'draft for owner review|Notes for the owner|Open questions|Settled 1 August 2026|⚠️ Confirm' web/dist`
   returning nothing.

---

## Reconciliation: what the adversarial pass changed

| # | Defect | Resolution |
|---|---|---|
| 1 | PR #314 is merged; C1 and the whole §3.6 row-1 story are inverted | [The C1 inversion](#the-c1-inversion); C1 rewritten before the blocklist is authored |
| 2 | Drafts are stale on the same fact; snapshotting froze the staleness | Drafts corrected at source before transcription (Phase 8) |
| 3 | `create-astro` has no `--typescript` flag and would hang | Hand-authored `package.json` (Phase 1.3) |
| 4 | The `.gitignore` verification left `web/` non-empty, breaking the scaffold | Directories removed immediately after the assertion |
| 5 | A lychee `preinstall` hook is deleted by `npm ci` | D3 — first-party checker for the build gate |
| 6 | Favicon, OG image and sitemap were consumed but never produced | Phase 6 |
| 7 | `/styleguide` build-exclusion had no mechanism | D11 |
| 8 | Five screenshots, not four; two need different devices | Phase 9 table |
| 9 | A missing `<Image>` import is a hard build error | D12 |
| 10 | The contract test's headline assertion was a tautology | `lockstep.test.ts` re-runs the repo's shell arithmetic |
| 11 | The no-italics rule was not on the deploy path | D4 — `check:markup` inside `build` |
| 12 | `thor.extension.api.version` is fabricated, not merely unsourced | D13 + blocklist rule C11 |
| 13 | `features.md` types "Android 16" for API 37 | Blocklist rule C10 |
| 14 | Five verification commands would false-fail or could not detect failure | Corrected in Phases 2, 5, 7, 8 |
| 15 | `web/**` missing from two release-train workflows' `paths-ignore` | Phase 5 |
| 16 | The weekly link check had no `web/dist` to check | Phase 10 — live site, sitemap-seeded |
| 17 | The "cite by symbol" rationale cited wrong drift figures | Rationale kept, figures dropped |
| 18 | The drafts were never retired, leaving two privacy policies | Phase 8 closing step |
| 19 | PayPal missing from the draft's footer; `web-ci` trigger unscoped | Phase 4, Phase 5 |

---

## Open questions for the owner

Not blockers — each has a defensible default implemented, recorded here so it can be overridden in
one place. Grouped by how much the answer costs to change later.

**Cheap to change (one file):**

1. Spacing, radius, elevation, focus-ring and prose-measure scales. The app has none to inherit;
   placeholders live in `tokens.css`.
2. The web type scale. `Type.kt` sets only family and weight — every size is the untouched Material 3
   phone baseline, and `displayLarge` at 57sp reads small as a desktop hero.
3. Which Fira Code weight matches the app. The shipped variable font defaults to `wght` 300 with
   family name "Fira Code Light" while `Type.kt` asks for `Medium`; what the app actually renders is
   unverified.
4. Nav composition — wordmark, mobile pattern, and which routes are header versus footer-only.
5. Whether `/download` and `/faq` also get on-page indexes.

**Needs a decision before a specific page is right:**

6. **Indus Appstore is in and out at the same time.** `download.md` carries a live section and a
   channel-table row; `features.md`'s settled note says it is dropped as outdated and "the site will
   not mention it". Default taken: **keep it**, since `download.md` is the newer file and the
   channel table is where a reader looks.
7. **Which version number the site prints, and where.** §6.5 mandates deriving it; §8.2 bars one
   from `/download`. Default taken: derive it, print it nowhere (D9).
8. **Base or `-foss`-suffixed version name**, if one is ever printed. The recommended channel's
   on-device string is `1.93.1-foss`.
9. **No foreground colour for the danger background.** `onErrorContainer` is never passed to either
   scheme, and inventing one violates the palette rule. Default taken: a site-only token, marked as
   such in `tokens.css` and exempted from the "every hex comes from `Color.kt`" assertion.
10. **Dark danger text `#FE7453` on `#881F05` is 3.49:1** — below AA for normal text, with both
    values fixed by the spec. Default taken: that pairing is restricted to large text and icons, and
    the contrast test pins it at the large-text threshold with a comment, so the number is never
    rediscovered by a user.
11. **No secondary/tertiary tokens.** The app's dark ones are lavender and purple, which sits
    awkwardly beside the "no Material You leakage" rule.

**Operational, needs an account or a secret:**

12. **Which Vercel account and plan owns the project.** Hobby is limited to personal, non-commercial
    use, and the site links five funding routes.
13. **Lighthouse against a protected preview** needs `VERCEL_AUTOMATION_BYPASS_SECRET` in repo
    secrets, or Deployment Protection disabled for previews. Without one, the workflow scores
    Vercel's auth page.
14. **Blocklist scope.** Does it apply only to `web/dist`, or also to tracked prose — `README.md`,
    `docs/**`, release notes — which make some of the same freeze-semantics claims? Widening it
    means the gate can fail Android-only PRs. Default taken: `dist` only.

---

## Standing risks

**Gates that silently stop being gates.** A gate that never fires looks exactly like a gate that
passes. The highest-risk instances here are a claims regex written with straight quotes that never
matches smartypants-rendered copy, a `dist` glob matching zero files because the check ran before
`astro build`, and the weekly link check pointed at a path that does not exist. All three exit 0
forever. The must-fail fixtures exist for exactly this.

**The claims gate is lexical, not semantic.** It stops known phrasings from recurring; it cannot
tell whether new prose is true. The danger is that its existence gets read as "the copy is
fact-checked" — precisely the overclaim the spec exists to prevent.

**A failed deploy makes the site stale, not down**, which means it is easy not to notice: there is
no alerting and Vercel serves the last good build indefinitely.

**Deleting or mistyping the Cloudflare `thor` record does not produce NXDOMAIN.** The proxied
wildcard absorbs it and the subdomain keeps resolving — to Cloudflare rather than Vercel. The
failure is a wrong site or a TLS error, so ordinary DNS smoke tests will not catch it.

**The npm surface is new to a repo that has only ever had Gradle, Actions and Bundler.** Until
Dependabot gains an npm entry, `web/package-lock.json` has no update pressure — the same condition
that let `Gemfile.lock` drift far enough that a routine `bundle lock` moved seventeen gems.

**Site-only PRs pay ~7 minutes of Android CI on every push**, and `pr-ci.yml` cancels in-progress
runs. That is the accepted cost of not path-filtering a required check, but it will feel wrong to
the next contributor and invite exactly the change the spec forbids — hence the comment.
