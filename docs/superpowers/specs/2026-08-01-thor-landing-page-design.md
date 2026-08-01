# Thor Landing Page — Design

**Date:** 1 August 2026
**Status:** approved design, ready for implementation planning
**Domain:** `thor.trinadhthatakula.com`
**Code location:** `web/` in the Thor repository

---

## 1. Purpose and scope

Thor has no web presence. Everything a prospective user might want to know — what it does, whether it
needs root, what it sends over the network, how to get it — currently lives in a README, a GitHub
issue tracker, and an out-of-date privacy policy hosted on an unrelated domain.

This project builds a small static marketing and documentation site: a landing page plus six
sub-pages. It is not a web app, has no backend, no database, no accounts, and no forms.

**Out of scope for launch:** localisation (English only), a blog, changelog automation, and any
in-app link changes. Those are separate pieces of work if they're wanted later.

---

## 2. Decisions

These were settled during design and should not be relitigated during implementation without a
deliberate revisit.

| # | Decision | Rationale |
|---|---|---|
| 1 | Landing page **plus** sub-pages, not a single long page | The content is genuinely multi-audience — a user deciding whether to install, and a developer writing an extension, want different pages |
| 2 | **Astro** | Static output, zero client JS by default, first-class image optimisation. The site is content, not an application |
| 3 | **Vercel project on the Thor repo**, Root Directory `web/` | Keeps the site next to the source it describes, so a feature and its documentation move in one PR. Avoids a second repo to keep in sync |
| 4 | **Framed screenshots** | Raw screenshots of a device UI read as clutter; framing makes them read as product |
| 5 | Production branch is **`dev`** | Matches how the project actually works — `dev` is the integration branch, `master` only moves on release. A site that only updates at release time would be perpetually behind the app |
| 6 | Homepage layout **A** (screenshot-led) | The app is visual and the screenshots are its strongest argument |
| 7 | `/features` layout **B** (sticky index + content bands) | Eight capability areas is too many for a flat page; the sticky index makes it skimmable and deep-linkable |
| 8 | **English only** at launch | The app ships five locales, but nobody has asked for a translated site, and stale translations are worse than none |
| 9 | The app's **static Asgardian palette**, no dynamic colour | Not a constraint invented for the site — `Theme.kt:102` already sets `dynamicColor = false` with the comment *"Disabled for Asgardian Terminal look"*. The static palette **is** what the app looks like for every user. See §5 |

---

## 3. Site map

Seven routes, each with a written content source.

| Route | Job | Content source |
|---|---|---|
| `/` | Convince someone in ~15 seconds that Thor is worth installing | `docs/site-content/index.md` |
| `/features` | The complete capability map, skimmable by section | `docs/site-content/features.md` |
| `/download` | Pick the right build and understand the trade-off | `docs/site-content/download.md` |
| `/faq` | Answer recurring questions before they become issues | `docs/site-content/faq.md` |
| `/privacy` | The corrected privacy policy | `docs/site-content/privacy.md` |
| `/extensions-policy` | What a user takes on by installing an extension | `docs/site-content/extensions-policy.md` |
| `/build-an-extension` | Developer guide to the extension API | `docs/site-content/build-an-extension.md` |

> **Note on content sources — read this before planning.** `docs/site-content/` is **gitignored**
> (PR #312) and exists **only on the owner's machine**. It is not in the repository and will not be
> present in a fresh clone, a CI checkout, or another contributor's tree.
>
> Those files are drafts for owner review, not a tracked deliverable. Once a page ships, the page's
> own source under `web/` is the single source of truth — a second tracked copy would drift and then
> get quoted back as if it were current, which for a privacy policy is worse than having no copy.
>
> **Implication for implementation:** the draft content must be transcribed into `web/src/` as part of
> building each page. After that the drafts are dead weight. Any implementation step that assumes it
> can read `docs/site-content/` must run on the owner's machine, or the content must be moved first.

### 3.1 `/download` is a page, not a button

Thor ships three ways and they are **not** interchangeable:

- **IzzyOnDroid** — the `foss` build. Reproducible; IzzyOnDroid rebuilds from source and verifies the
  result matches on every major release.
- **GitHub Releases** — the same `foss` APK, direct.
- **Play Store** — the `store` build. Not reproducible by design, because Google re-signs it.

Different signing keys mean a user cannot switch sources without uninstalling first, losing app data.
That consequence needs a paragraph, not a tooltip. The homepage keeps a prominent **Download** CTA;
it points here.

### 3.2 `/extensions-policy` stays separate from `/privacy`

It is a liability agreement, not a privacy disclosure. Keeping it separate lets the FAQ and, later,
the in-app Extension Manager deep-link straight to it.

### 3.3 `/build-an-extension` is linked, not featured

Reached from a call-out at the end of the Extensions band on `/features`, and from the extensions
policy. It serves a handful of developers; a homepage slot would spend prime space on the wrong
audience.

### 3.4 Homepage content outline

Three homepage angles were drafted independently — *the power user*, *the verifiability argument*,
and *the outcome* — then scored by two judges with different lenses. **They disagreed, and the
disagreement is the useful result.**

| Angle | Conversion lens | Accuracy lens |
|---|---|---|
| The outcome | **9** — best prose in the pack | **5** — worst facts in the pack |
| The power user | 6 | **7** |
| The verifiability argument | 5 | 6 |

The outcome angle wins the page and loses the fact-check, so the plan is its structure with its
claims corrected. Full copy is in `docs/site-content/index.md`; the structure is:

1. **Hero** — *"Turn off the apps your phone won't let you uninstall."* Primary CTA to `/download`,
   secondary "Do I need root?" anchoring to section 4. The subhead must contain the sentence
   **"You will need one of the three"** — see §3.6.
2. **"Freeze it, and it stops."** Freezer list showing active and frozen side by side. Ends on the
   greyscale pinned-shortcut detail, which is the one line that produces delight rather than assent.
3. **"The ones that would break your phone, it refuses."** Screenshot: the refusal dialog on an
   Unsafe system app, *with no "proceed anyway" button in frame*. The refusal is the argument —
   a success state would not be.
4. **"Grant nothing, and look around first."** What Thor does with no privilege mode at all: list,
   search, sort, filter by permission group, show install sources, export an APK.
5. **"You may not need root."** Shizuku and Dhizuku, plus the honest setup cost — developer options,
   an ADB or wireless-debugging step, and Shizuku needing to be re-armed after most reboots.
6. **Trust note** — FOSS, GPL-3.0-or-later, and the reproducible-build claim attributed to
   IzzyOnDroid rather than asserted: *"their check, not my claim."*
7. **Footer** — every sub-page, the repo, and the funding routes.

Four content sections rather than three, each *shorter*, so the page still scans in fifteen seconds.

### 3.5 `/download` content outline

Fourteen sections, drafted then adversarially critiqued; the critique returned eleven factual errors
and eleven omissions against the first draft, all folded into `docs/site-content/download.md`.

Order: decision aid → channel table → IzzyOnDroid (recommended) → GitHub Releases → Google Play →
Indus Appstore → other places you may see Thor → not available: F-Droid → the two flavours →
what reproducible builds prove and don't → verify what you downloaded → switching channels →
already on the wrong one → build from source.

Two structural requirements that came out of the critique:

- **A privilege-requirement notice sits above the channel table.** The first draft never said Thor
  needs Root, Shizuku or Dhizuku at all. A reader could follow that page start to finish, install
  from the recommended channel, and find nothing works. That is the single largest gap a download
  page can have.
- **The uninstall consequence is not framed as a cost of switching channels.** It applies to *any*
  uninstall — device migration, factory reset, "I'll reinstall it later." Filed under "switching"
  it reads as not applying to a reader who never intends to switch.

### 3.6 Claims the site must not make

Each of these was drafted by an agent, checked against the source, and found false. They are
recorded because they are all *plausible* — the next person writing this copy will reach for them
again.

| Tempting claim | What the source says |
|---|---|
| Freezing preserves app data | **Only for user apps.** `RootSystemGateway.kt:232-248` freezes a *system* app with `pm uninstall --user` — no `-k` — and unfreezes with `pm install-existing --user`. It comes back in factory state. The homepage headline is about preinstalled apps, so this is false for exactly the case the page sells |
| Cache size "fails explicitly" without root | It returns `0L`. `ShizukuSystemGateway.kt:117` and `DhizukuSystemGateway.kt:105` both `return 0L`; the signature is `Long`, so there is no failure channel. Say "reads zero without root" |
| Uninstalling leaves no record of what was frozen | Thor ships a recovery path — the "Import Disabled Apps" prompt, plus `MATCH_UNINSTALLED_PACKAGES` enumeration. What is genuinely unrecoverable is the **watchlist and profiles**, not the apps |
| The Play build uses a different signing key | Almost certainly true and **not verified** — the certificate Play holds was never obtained. Write "unverified; assume it differs" |
| The two flavours differ in exactly one respect | `foss` also carries `localeFilters` (5 locales vs the store build's full set), a `-foss` `versionNameSuffix`, and an extra ProGuard file. One *functional* difference, several others |
| The signing keystore is "years old" | The certificate is valid from 26 January 2025 — and the page's own `apksigner` instructions show the reader that date |
| A `-dev-` tag means "pre-release, don't install" | Tag shape is not the signal. `v1.81.9-dev-82` was a full release and is one of the reproducible builds rbtlog lists. Point at GitHub's Pre-release badge instead |

**Two blockers to clear before this page can be honest**, both outside the site:

1. The published policy at `rxspectra.web.app` still claims Thor has no internet access. The
   homepage trust note contradicts it directly, and it is the first thing a reader who takes
   "check it yourself" seriously will check.
2. IzzyOnDroid's live listing says *"100% offline & FOSS"* and *"without trackers, ads, or internet
   permissions"* while the same page lists `INTERNET` under Permissions. A correction should cover
   the **summary line**, which is the most-read stale claim.

---

## 4. Deploy plumbing

### 4.1 Vercel

| Setting | Value |
|---|---|
| Repository | `trinadhthatakula/Thor` |
| Root Directory | `web/` |
| Framework preset | Astro |
| Output | static |
| Production Branch | `dev` |
| Include files outside Root Directory | **enabled** (required — see §6.5) |

Pull requests get preview deployments automatically.

### 4.2 DNS

`thor` CNAME → `cname.vercel-dns.com` in Cloudflare, **DNS-only (grey cloud)**.

Proxying puts Cloudflare's CDN in front of Vercel's, which buys nothing and breaks certificate
issuance. Verified correct on 1 August 2026 via Google and Cloudflare DoH resolvers.

**Two traps recorded from setting this up:**

1. **A proxied `*` wildcard exists on the zone.** Every subdomain of `trinadhthatakula.com` resolves,
   including names that were never configured. Consequences: "does it resolve?" is never a valid test
   of whether a record is right, and if the `thor` record is ever deleted the site will silently fall
   back to the wildcard rather than failing loudly.
2. **Never fix a Cloudflare 526 by switching SSL mode to Flexible.** It appears to work and serves the
   site over an unencrypted hop to the origin — on a site whose privacy stance is a selling point.
   A 526 during setup means the Vercel project hasn't claimed the hostname yet. Correct order:
   grey cloud → create Vercel project → add domain → Vercel issues the certificate. Errors between
   those steps are expected.

### 4.3 Build triggers — deliberately asymmetric

Two similar-looking problems that want **opposite** answers:

- **Vercel: skip builds when nothing the site reads has changed.** Most commits to this repo are
  Android changes. Set this as the Ignored Build Step:

  ```sh
  git diff --quiet HEAD^ HEAD -- . ':/gradle.properties' ':/gradle/libs.versions.toml'
  ```

  Exit 0 skips the build, non-zero builds it — so a git failure fails toward building, which is the
  right direction.

  **`web/` alone is the wrong trigger.** §6.5 derives the version number and the SDK levels from
  files *outside* `web/`. A `versionCode` bump lands in `gradle.properties` and touches nothing under
  `web/`, so a plain `-- .` filter would skip the rebuild and leave a stale version on the site —
  precisely the content drift §6.5 exists to prevent. The pathspec list here and the file list in
  §6.5 are the same list; adding a third derived fact means editing both.

  **The `:/` prefix is load-bearing.** The Ignored Build Step runs in the Root Directory, where a
  bare `gradle.properties` pathspec resolves to `web/gradle.properties`, matches nothing, and exits
  0 — for *every* commit, so the site would never rebuild at all, silently. `:/` is git's
  top-of-tree pathspec magic and is immune to where the command runs. `../gradle.properties` works
  today but breaks if the Root Directory ever moves.

  **This assumes `dev` advances by merge commits**, which it does — PRs land with
  `gh pr merge --merge`, so `HEAD^` is the previous `dev` tip and the diff spans the whole PR.
  A fast-forward push of several commits would only diff the last one.
- **GitHub Actions: do *not* path-filter the Android workflows.** The tempting move is
  `paths-ignore: ['web/**']` so a site-only PR skips the ~7-minute Android build. Don't.
  `build-and-test` is a **required check**, and a required check that never runs leaves the PR stuck
  on "Expected — Waiting for status" permanently. Seven minutes of CI is far cheaper than an
  unmergeable PR.

### 4.4 Assets

Screenshots live in `web/src/assets/screenshots/`, **not** `web/public/`. Astro's `<Image>` only
optimises what's under `src/`; anything in `public/` ships byte-for-byte as committed.

**Framing means a CSS/component device frame wrapping an unmodified screenshot** — not a
pre-composited image baked in an image editor. Committing raw device captures and framing them in
markup keeps the captures reusable (a frame restyle touches no assets) and keeps re-capturing after a
UI change a one-step job.

### 4.5 `.gitignore` additions

```text
web/node_modules/
web/dist/
web/.astro/
.vercel
```

### 4.6 No impact on the Android build

`settings.gradle.kts` includes only `:app`, `:bypass`, and `:vm-runtime`. A `web/` directory adds no
Gradle module and changes no emitted bytecode, so **the `foss` build's reproducibility is unaffected**
and IzzyOnDroid's verification continues to work.

---

## 5. Visual design — the Asgardian palette

The site uses the app's own colours and type. Not an approximation: the literal token values from
`presentation/theme/`.

**Dynamic colour is not ditched for the site — it was never on.** `Theme.kt:102` reads
`dynamicColor: Boolean = false` with the comment *"Disabled for Asgardian Terminal look"*, so the
static palette is what every user already sees. There is nothing to match.

Two hand-authored schemes exist: **Asgardian Technical Alchemist** (light) and **Asgardian
Terminal** (dark).

### 5.1 Tokens

| Role | Light | Dark |
|---|---|---|
| Page background | `#F8FAF3` | `#0E0E0E` |
| Surface ramp (lowest → highest) | `#FFFFFF` `#F2F4ED` `#EDEFE8` `#E7E9E2` `#E1E3DD` | `#000000` `#131313` `#191919` `#1F1F1F` `#262626` |
| Text | `#191C18` | `#E5E5E5` |
| Muted text | `#43493E` | `#ABABAB` |
| Accent (links, emphasis) | `#354E15` | `#F0FFD7` |
| CTA fill / label | `#4C662B` / `#F0FFD7` | `#D5F6AB` / `#445E25` |
| Hairlines / strong borders | `#C3C8BC` / `#74796D` | `#484848` / `#757575` |
| Danger / danger bg | `#BA1A1A` / `#FFDAD6` | `#FE7453` / `#881F05` |

### 5.2 Four traps in copying the palette

1. **Do not invert or filter one scheme to make the other.** They are separately hand-authored and
   are not hue-matched — light accent is a dark olive `#354E15`, dark accent is a pale lime
   `#F0FFD7`. The roles swap lightness, so an algorithmic inversion looks wrong.
2. **Do not copy tokens the app never wires up.** `onErrorContainer` is declared in `Color.kt` and
   never passed to either scheme, and the light scheme omits `inversePrimary`, `inverseSurface` and
   `surfaceTint` entirely. At runtime those fall through to Material 3 baselines, which are
   **purple** — `inversePrimary` resolves to `#D0BCFF`. Taking "whatever the app resolves" would
   import Material You colour into a palette whose entire point is not being Material You. Use the
   table above; it contains only values the app actually sets.
3. **AMOLED blacks the page, not the cards.** The app's AMOLED override sets `background`, `surface`
   and `surfaceVariant` to `#000000` and leaves the whole surface-container ramp alone. A `#191919`
   card on a `#000000` page *is* the intended look; flattening containers to black too is wrong.
4. **AMOLED is off by default.** `ThorTheme` defaults `amoledMode = true`, but both call sites pass
   `prefs.useAmoled`, which defaults `false`. The real default dark background is `#0E0E0E`, not
   black. Offer AMOLED as an optional third mode nested under dark, mirroring the app.

### 5.3 Type

The terminal quality is the type pairing, not the colour.

- **Outfit** for headings and body.
- **Fira Code** for label-role text *only* — nav labels, badges, chips, table headers, version
  strings, and code. That monospace-labels-only split is the most distinctive thing about the app's
  typography, and it is what makes "terminal" read as intentional rather than as a theme.

**Self-host both.** Both are on Google Fonts, and linking the CDN would send every visitor's IP to
Google on a site whose privacy stance is the argument — directly contradicting §7.

**No italics.** `Type.kt` maps every italic style to the same file as its upright, so Thor has no
real italic. The site should not invent one.

### 5.4 Light, dark, and which one is default

Mirror the app's three-state `ThemeMode` — light, dark, system — defaulting to `prefers-color-scheme`
with a manual toggle persisted in `localStorage`.

**Dark is the fallback when the visitor expresses no preference**, and dark is what the screenshots
and the social preview image use. The owner named the scheme by its dark name; a site that opens
light after that is a different product than the one described.

---

## 6. Testing and error handling

### 6.1 Deploy failure

Already correct with no work: Vercel keeps the last good deployment live when a build fails. A broken
commit on `dev` makes the site **stale**, never **down**.

### 6.2 Build-time gates

These run inside the Vercel build, so a failure blocks the bad deploy. Wire them into the `build`
script rather than into Vercel's UI, so they also run locally and are visible in the repo. Vercel's
Build Command stays the default `npm run build`:

```json
{
  "scripts": {
    "build": "astro check && astro build && lychee --offline dist"
  }
}
```

- **`astro check`** — type errors. `astro build` does **not** type-check on its own; chaining
  `astro check` in front of it is how Astro's own docs recommend gating a build.
- **Internal-link validation** — a typo'd `/faq` link should fail the build rather than 404 in
  production. **Astro has no built-in link checker.** That was checked against the docs rather than
  assumed, and it means the check has to be added deliberately or it silently doesn't exist.
  Recommendation: `lychee --offline dist`, which resolves links against the built output and makes
  no network requests — the same tool §6.3 then runs weekly *with* the network for external links.
  One tool, two modes, one set of results to learn to read.

### 6.3 External links — scheduled, not per-build

The site will carry 20+ outbound links (GitHub, IzzyOnDroid, Play Store, Telegram, five funding
routes). Checking them on every build reddens PRs whenever someone else's host has a bad minute.
A **weekly** GitHub Action that opens an issue on failure is the right shape.

### 6.4 404

`src/pages/404.astro`, served automatically by Vercel for static output.

### 6.5 Content drift is the real risk

The failure mode for this site is not bugs, it is confidently-wrong prose. The content contains many
specific, checkable facts: minSdk 28, reproducible `foss` builds, `thor-extension-api:3.0.0`,
`api.version="2"`, the current version number. Prose rots silently, and a developer-facing page that
is wrong is worse than one that doesn't exist.

**Derive these at build time from the repo rather than typing them:**

| Fact | Source |
|---|---|
| Version name | `gradle.properties` → `versionCode`, using the documented `1900 → 1.90.0` derivation |
| minSdk / targetSdk / compileSdk | `gradle/libs.versions.toml` |
| Extension API version | `gradle/libs.versions.toml` → `thorExtensionApi` |

**Deliberately not the extensions catalog.** The catalog lives in the separate `Thor-Extensions`
repo, so reading it would put a cross-repo network fetch inside the build. The version Thor is
actually compiled against is already in the version catalog, one directory up, and is the more
truthful number anyway.

This is why **"Include files outside the Root Directory" must stay enabled** in Vercel (§4.1).
`web/` reads exactly two files from outside its Root Directory — `../gradle.properties` and
`../gradle/libs.versions.toml` — and the same two appear in §4.3's Ignored Build Step. The setting
is on by default and is exactly the kind of thing that gets switched off during unrelated cleanup,
so it is recorded here.

### 6.6 Lighthouse — warning, not gate

Run Lighthouse CI on preview deployments as a **warning** initially. Promote it to a gate once the
real baseline is known; a performance budget guessed in advance mostly teaches people to ignore it.

### 6.7 Screenshots

The one thing automation cannot save. UI changes; screenshots don't. This belongs on the release
checklist, not in CI.

---

## 7. Privacy policy consequence

The rewritten policy currently says *"no request touches any server I run, because I run none."*

**Publishing this site makes that false.** Visiting it means Vercel sees the visitor's IP, and Vercel
is infrastructure deliberately chosen. That is precisely the class of overstatement the policy rewrite
exists to correct — and the first thing a sceptical reader checks.

Therefore:

- **No analytics at all**, including Vercel's own. The app has no telemetry; the site matching that is
  both consistent and one less thing to disclose.
- **Add an "About this website" section** to `/privacy`, stating that the site is static, sets no
  cookies, and runs no analytics.

  On the IP question, *"Vercel sees request IPs"* is not enough on its own — it names the fact and
  omits everything a sceptical reader actually wants, which is the same shape of disclosure this
  rewrite exists to correct. The section must answer four things:

  - **Why it is collected** — the IP is the address the response is sent back to. It is inherent to
    answering an HTTP request, not a choice anyone made.
  - **Who sees it** — Vercel, as the host. **I never receive it.** No log drain is configured, no
    analytics is enabled, and there is no server-side code, so nothing forwards request data
    anywhere.
  - **How long it is kept** — by Vercel, under Vercel's retention, not under anything I set. Link
    [Vercel's privacy notice](https://vercel.com/legal/privacy-notice) rather than paraphrasing a
    retention period that can change without notice and would then be a false claim on a page whose
    whole value is being accurate.
  - **What Cloudflare does and does not see** — the record is DNS-only (§4.2), so Cloudflare answers
    the DNS query — normally from the visitor's resolver, not the visitor — and never sees the HTTP
    request at all. Worth stating, because a reader who checks the DNS will otherwise assume the
    site sits behind Cloudflare's proxy.

---

## 8. Open items for implementation planning

1. **Screenshots must be captured and framed — none exist yet.** §3.4 names the specific state each
   band needs, and two are not the obvious capture: the refusal dialog needs no "proceed anyway"
   button in frame, and the zero-privilege app list must be captured on a device with **no**
   privilege mode enabled. The Work Mode selector only renders when at least two modes are
   available, so that capture needs a device set up for it.
2. **Confirm the stable version before any version number or file size appears on `/download`.**
   `gradle.properties` reads `1931`, but v1.93.1 shipped as a pre-release, so the stable channel may
   still be v1.93.0. Every homepage draft withheld a version number and that judgement should hold.
   Size figures have the same problem — a local unsigned `foss` build is not what a user downloads,
   and Play delivers splits.
3. **The two external corrections in §3.6** — the `rxspectra.web.app` policy and the IzzyOnDroid
   listing summary — are prerequisites for the trust note, not follow-ups.
4. **Freezing a system app destroys its data, and nothing in the app says so.** No string in
   `strings.xml` warns about it. That is a product gap this project surfaced rather than a website
   one; worth its own issue.
