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

---

## 3. Site map

Seven routes. Five have a written content source already; two need writing.

| Route | Job | Content source |
|---|---|---|
| `/` | Convince someone in ~15 seconds that Thor is worth installing | **needs writing** |
| `/features` | The complete capability map, skimmable by section | `docs/site-content/features.md` |
| `/download` | Pick the right build and understand the trade-off | **needs writing** |
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

Layout A is screenshot-led, so the screenshots drive the structure:

1. **Hero** — one-line statement of what Thor is, Download CTA, framed screenshot
2. **The three differentiators**, each with a screenshot band:
   - Works **without root** — Shizuku and Dhizuku, not just root
   - **Freezer with launcher shortcuts** that survive freezing
   - **No telemetry**; no network access at all unless you open the extension store
3. **Trust note** — FOSS, GPL-3.0-or-later, reproducible `foss` builds verified by IzzyOnDroid
4. **Footer** — links to every sub-page, the repo, and the funding routes

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
| Include files outside Root Directory | **enabled** (required — see §5.5) |

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

  **`web/` alone is the wrong trigger.** §5.5 derives the version number and the SDK levels from
  files *outside* `web/`. A `versionCode` bump lands in `gradle.properties` and touches nothing under
  `web/`, so a plain `-- .` filter would skip the rebuild and leave a stale version on the site —
  precisely the content drift §5.5 exists to prevent. The pathspec list here and the file list in
  §5.5 are the same list; adding a third derived fact means editing both.

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

## 5. Testing and error handling

### 5.1 Deploy failure

Already correct with no work: Vercel keeps the last good deployment live when a build fails. A broken
commit on `dev` makes the site **stale**, never **down**.

### 5.2 Build-time gates

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
  no network requests — the same tool §5.3 then runs weekly *with* the network for external links.
  One tool, two modes, one set of results to learn to read.

### 5.3 External links — scheduled, not per-build

The site will carry 20+ outbound links (GitHub, IzzyOnDroid, Play Store, Telegram, five funding
routes). Checking them on every build reddens PRs whenever someone else's host has a bad minute.
A **weekly** GitHub Action that opens an issue on failure is the right shape.

### 5.4 404

`src/pages/404.astro`, served automatically by Vercel for static output.

### 5.5 Content drift is the real risk

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

### 5.6 Lighthouse — warning, not gate

Run Lighthouse CI on preview deployments as a **warning** initially. Promote it to a gate once the
real baseline is known; a performance budget guessed in advance mostly teaches people to ignore it.

### 5.7 Screenshots

The one thing automation cannot save. UI changes; screenshots don't. This belongs on the release
checklist, not in CI.

---

## 6. Privacy policy consequence

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

## 7. Open items for implementation planning

1. Homepage and `/download` copy must be written; the other five pages have drafts to work from.
2. Screenshots must be captured and framed — none exist yet.
3. Visual design (palette, typography) should follow the app's Asgardian theme; not specified here.
