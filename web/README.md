# Thor landing page

The static site at **<https://thor.trinadhthatakula.com>** — seven routes plus a 404, built with
Astro, deployed on Vercel from the `master` branch. No backend, no forms, no analytics, and no client
JavaScript except the theme toggle.

It lives inside the app repository on purpose: a feature and the page that describes it move in one
PR. It adds no Gradle module (`settings.gradle.kts` still includes only `:app`, `:bypass` and
`:vm-runtime`), so the `foss` build stays byte-reproducible and IzzyOnDroid's verification is
unaffected.

Design spec: [`docs/superpowers/specs/2026-08-01-thor-landing-page-design.md`](../docs/superpowers/specs/2026-08-01-thor-landing-page-design.md).
Implementation plan: [`docs/superpowers/plans/2026-08-01-thor-landing-page-implementation.md`](../docs/superpowers/plans/2026-08-01-thor-landing-page-implementation.md).

---

## Running it locally

```sh
cd web
npm ci          # not `npm install` — see "Pinned versions" below
npm run dev     # http://localhost:4321
```

`npm ci` must be run from `web/`, but the **build reads two files above it**
(`../gradle.properties` and `../gradle/libs.versions.toml`). Running Astro from the repo root works
too — `findRepoRoot()` walks up to `settings.gradle.kts` rather than trusting the working directory.

## The scripts, and what each one protects

| Script | What it is for |
|---|---|
| `npm run dev` | Astro dev server. The only place `/styleguide` renders. |
| `npm run preview` | Serves `dist/` as Vercel will. Use it to check `trailingSlash` behaviour. |
| `npm run check:types` | `astro check`. **`astro build` does not type-check on its own** — without this, a type error reaches production. |
| `npm run check:links` | Offline internal-link and `#fragment` checker over `dist/`. Astro has no built-in link validation, so a typo'd `/faq` would 404 in production instead of failing the build. |
| `npm run check:claims` | The claims blocklist from spec §3.6, run against the **built HTML** so copy assembled from components and `<Fact>` is covered. |
| `npm run check:markup` | No `<em>`/`<i>` in any built page. The app has no real italic — `Type.kt` maps every italic style to the same file as its upright — so italic copy on the site describes a typeface that does not exist. |
| `npm run check:sitemap` | Asserts the sitemap, `robots.txt` and the built page set agree, and that `/styleguide` is in none of them. |
| `npm run check:screenshots` | Every `DeviceFrame` resolves to a real image. Green with placeholders everywhere *except* a production deploy, where it fails — see `docs/launch-checklist.md` §4. |
| `npm run check:a11y` | axe-core over jsdom. Not in the `build` chain, because it needs `dist/` and jsdom, but the deploy workflow runs it against the staged artifact anyway. `color-contrast` is disabled in it — jsdom has no layout, so that rule can only ever answer "incomplete", and a rule that can only answer "incomplete" is worse than no rule. Contrast is covered by an arithmetic test over the token values. |
| `npm test` | Vitest. Mostly `src/lib/repo-facts/`. |
| `npm run build` | The deploy gate stack, in order: `check:types` → `astro build` → `check:links` → `check:claims` → `check:markup` → `check:sitemap` → `check:screenshots`. |

The checkers themselves live in `web/scripts/`. Each takes the directory to scan as its first
argument and defaults to `dist`, which is what lets the deploy workflow re-run all six against
`.vercel/output/static` — the tree that is actually uploaded — rather than trusting that it matches
the one that was gated. Each also **fails on an empty or missing directory** rather than reporting
success over nothing, which is the property that makes that second pass an assertion instead of a
formality.

**`npm run build` is the whole deploy gate.** `vercel build` runs it verbatim, so every gate above
is a deploy gate rather than a CI courtesy — which is the point of putting them in the npm script
instead of in Vercel's UI.

## Pinned versions

**`typescript` is pinned to `^6.0.3`, deliberately.** `@astrojs/check@0.9.10` declares
`"typescript": "^5.0.0 || ^6.0.0"` as a peer dependency, and npm's `typescript@latest` is already
7.x. An unpinned bump therefore breaks `astro check` — the *first* gate in `npm run build` — so it
does not merely redden CI, it breaks the deploy. `.github/dependabot.yml` carries a matching
`ignore` rule for typescript majors; lift both together, and only alongside an `@astrojs/check`
release that widens the range.

**`engines.node` is `>=22.12.0`**, which is Astro 7's own floor rather than a number picked here.
`.github/workflows/web-ci.yml` reads it via `node-version-file: web/package.json` instead of pinning
a version in the workflow, so there is one place to bump and CI cannot drift from what the manifest
claims to support.

Use `npm ci`, not `npm install`. `npm install` will happily re-resolve `typescript` past the peer
ceiling and hand you a green local build for the wrong reason.

---

## Deploying

**Deploys run from `.github/workflows/web-deploy.yml`, not from Vercel's Git integration.**
`master` publishes and `dev` stages: a push to `master` touching the site deploys production, a push
to `dev` deploys a preview of the integrated site, and a PR into either deploys a preview and
comments the URL. Site work therefore merges to `dev` like everything else, and the live site changes
only when `dev` is merged to `master`.

A PR based on `master` additionally builds with `REQUIRE_SCREENSHOTS=1`, so `check:screenshots` is
strict on the release PR's preview rather than first becoming strict on the deploy that is already
live. `web/docs/deploy.md` is the reference — secrets, project settings, rollback. What follows is
only the part you need in order not to break it from inside this directory.

The trade is deliberate: under the Git integration, four dashboard settings decide whether the gates
above run at all, none of them is visible in a diff, and every one produces **no error when wrong**.
Driving the CLI from Actions puts those decisions in `vercel.json` and the workflow, where they get
reviewed.

### The Vercel project is not connected to the repository

That is the actual guarantee that the site deploys once per commit, and it is a property of the
Vercel project, not of anything in this directory. Create the project with `cd web && vercel link`;
importing the repository from `vercel.com/new` connects it *and* sets Root Directory to `web`, which
resolves to `web/web` once the workflow runs the CLI from inside `web/`.

```json
"git": { "deploymentEnabled": false }
```

That flag is defence in depth for the case where the repository gets connected anyway, and it lives
in **two** files: here in `vercel.json`, and in `/vercel.json` at the repository root. Vercel does
not document which one its Git integration reads — the setting is evaluated at webhook time, before
a build container exists, so the CLI's resolution rules do not apply. Two copies remove the guess;
delete neither. It is also not fully dependable even when placed correctly, so treat a connected
repository as something to verify by counting deployments, not something the flag has handled.

`vercel.json` also carries `buildCommand`, `installCommand` and `outputDirectory`, and it takes
precedence over the dashboard's fields — which is what stops a dashboard edit from silently
dropping `npm run build`, and with it all six gates, off the deploy path.

### The path list lives in four places

Most commits here are Android changes, so the site does not rebuild unless something it *reads* has
moved. That list is:

```text
web/**   gradle.properties   gradle/libs.versions.toml
```

and it appears in **`web-ci.yml`**'s `paths:`, **`web-deploy.yml`**'s `paths:`, `vercel.json`'s
`ignoreCommand`, and — as the two constants `GRADLE_PROPERTIES` and `VERSION_CATALOG` — in
`src/lib/repo-facts/read.ts`. **Adding a third derived fact from a third file means editing all
four**, in the same commit, or the site serves a value that no longer rebuilds when it changes.

`web/**` alone is the wrong trigger, which is why the two Gradle files are there: a `chore(release)`
commit bumps `versionCode` and touches nothing under `web/`, yet changes the version name the site
renders.

One file is read that is deliberately *not* in the list: `findRepoRoot()` reads `settings.gradle.kts`
to locate the repository root. Only its existence matters, never its contents, so a change to it
cannot change a rendered fact and must not trigger a rebuild.

`ignoreCommand` is a Git-integration feature and the Git integration is off, so it should do nothing
today — but do not treat it as provably inert. `vercel deploy` sends
`projectSettings.commandForIgnoringBuildStep`, derived from `vercel.json`, on the same payload as
`--prebuilt`, and Vercel has previously shipped (then rolled back) a build in which the Ignored Build
Step cancelled `--prebuilt` deploys. If that recurs the job goes red rather than silently green.
Its `:/` prefixes are load-bearing whenever it does run: the command executes inside the Root
Directory, where a bare `gradle.properties` pathspec resolves to `web/gradle.properties`, matches
nothing, and exits 0 for *every* commit — the site would show "Build skipped", correctly, forever.

### GitHub Actions, and why the asymmetry

- **`web-ci.yml` / `web-deploy.yml`** — path-filtered on `web/**` plus the two derived-fact files,
  and **neither may ever be added to a branch ruleset**. The filter is only safe because they are not
  required checks.
- **`pr-ci.yml`** — **no path filter, ever.** `build-and-test` is the required status context on the
  `dev` and `master` rulesets, `on.pull_request.paths` filters the whole workflow, and GitHub reports
  a path-skipped required check as pending forever. A site-only PR would be unmergeable. The ~7
  minutes of Android CI on a CSS change is the accepted price.
- **`dev-check.yml` / `production-deploy.yml`** — `web/**` is in their `paths-ignore`, and so are
  `vercel.json`, `.github/workflows/web-*.yml` and `.github/labeler.yml`, because `web/**` alone does
  not cover the site's own plumbing. Neither is a required check, and a change that cannot reach the
  APK should not spend seven minutes proving it.

---

## DNS

**No DNS change is needed.** `thor.trinadhthatakula.com` already exists in Cloudflare as a
**DNS-only (grey cloud)** `CNAME` to `cname.vercel-dns.com`, and resolves through to Vercel anycast:

```console
$ dig +short thor.trinadhthatakula.com @8.8.8.8
cname.vercel-dns.com.
76.76.21.98
66.33.60.67
```

Add the domain in the Vercel project and let Vercel issue the certificate. Errors between "grey
cloud" and "Vercel has claimed the hostname" are expected.

**The error you will actually see is an expired certificate, not a Cloudflare one.** Vercel's edge
already answers for this hostname and, with no project claiming it, falls back to a stale
`*.trinadhthatakula.com` certificate that expired on 11 May 2026 (`x-vercel-error:
DEPLOYMENT_NOT_FOUND`, `server: Vercel`). Vercel cannot renew a wildcard without a DNS-01 record and
the zone is on Cloudflare nameservers, so it will not self-heal. It also serves a two-year HSTS
header and 308s plain HTTP to HTTPS, so a browser cannot be clicked through. `ERR_CERT_DATE_INVALID`
here means *unclaimed hostname*, not *broken DNS* — verify with `vercel domains inspect` and
`vercel certs ls`, never a browser, until the certificate exists.

**The zone has a proxied `*` wildcard.** Every subdomain of `trinadhthatakula.com` resolves,
including names that were never configured:

```console
$ dig +short definitely-not-configured-xyz.trinadhthatakula.com @8.8.8.8
172.67.176.17
104.21.72.68        # Cloudflare, not Vercel
```

Two consequences:

- **"Does it resolve?" is never a valid test.** The A lookup has to chain to `76.76.21.x` /
  `66.33.60.x`. Anything in `104.21.x` or `172.67.x` is the wildcard answering.
- **If the `thor` record is ever deleted the site does not go down loudly** — it silently falls back
  to the wildcard and serves the wrong thing, or a TLS error. Ordinary DNS smoke tests will not
  catch it.

**A Cloudflare 526 cannot occur while the record stays grey.** 526 requires Cloudflare to be in the
request path, which means an orange cloud; this record is DNS-only. If one is ever orange-clouded and
a 526 appears, **never fix it by switching the SSL mode to Flexible** — it appears to work, and it
serves the origin hop unencrypted, on a site whose privacy stance is the entire argument.

The correct order is grey cloud → create the Vercel project → first production deploy → add the
domain → Vercel issues the certificate. Adding the domain before a production deployment exists
leaves it attached to nothing, which is indistinguishable from "still issuing".

---

## Adding a new derived fact

Every specific, checkable number about the app is derived from the repo at build time rather than
typed. Prose rots silently, and a developer-facing page that is confidently wrong is worse than one
that does not exist.

To add one:

1. **`src/lib/repo-facts/types.ts`** — add the field to `RepoFacts`, with a doc comment saying what
   it is *and is not*. (`versionName` is the working example: it is the version **in source**, not
   the latest published stable release, and the comment says so precisely so nobody wires it into a
   "Latest release" badge by reflex.)
2. **`src/lib/repo-facts/parse.ts`** — add the pure parsing function. No I/O here. Exact key matching
   after trimming: never substring, never a case-insensitive regex. `gradle.properties` has
   `initialVersionCode` on line 51 and `versionCode` on line 54, so a first-match or substring lookup
   silently returns the wrong number. Version strings stay **strings** — the repo's `tr -dc '0-9'`
   idiom turns `"3.0.0"` into `300`. Throw, with a message naming the file, the key and the fix.
3. **`src/lib/repo-facts/index.ts`** — compute it in `computeRepoFacts()`.
4. **A test in `parse.test.ts`** pinned to values fixed by *documentation*, not by current repo
   state. `contract.test.ts` asserts shape only, never values, so that a `chore(release)` commit —
   the one commit that must never be blocked by the website — cannot redden it.
5. **If the value comes from a file that is not already read**, add that file in all four places:
   `read.ts`, the `paths:` filter of `web-ci.yml` *and* of `web-deploy.yml`, and the `ignoreCommand`
   pathspec in `vercel.json`. See "The path list lives in four places" above. Miss the workflow
   filters and the site keeps serving a value that no longer triggers a rebuild when it changes —
   which is the exact failure the derivation exists to prevent, arrived at from the other side.

Render it with `<Fact name="…" />`, never by importing `repoFacts` into a page. `name` is typed as
`keyof RepoFacts`, so a typo is an `astro check` error rather than a literal `{{minSdk}}` in
production — and `Fact.astro` stays the single grep target for "where does the site print a number".

---

## Assets

`public/` holds the favicon set, the social card and `robots.txt`. **Screenshots do not go in
`public/`** — Astro's `<Image>` only optimises files under `src/`, so anything in `public/` ships
byte-for-byte as committed. Screenshots live in `src/assets/screenshots/` and are framed by the CSS
`DeviceFrame` component rather than being pre-composited in an image editor.

| File | Source | Notes |
|---|---|---|
| `public/favicon.svg` | itself | The primary favicon. Single path, adapts to the browser chrome via `prefers-color-scheme`. |
| `public/favicon.ico` | `favicon.svg` | 16/32/48. Exists only so the browser's unconditional `/favicon.ico` request is not a 404; no `<link>` needed. |
| `public/apple-touch-icon.png` | `favicon.svg` | 180×180, opaque. |
| `public/icon-192.png`, `public/icon-512.png` | `favicon.svg` | Referenced by `site.webmanifest`. |
| `public/og-image.png` | `src/assets/og-image.svg` | 1200×630. |

The mark in `favicon.svg` is copied **verbatim** from
`app/src/main/res/drawable/thor_drawn_foreground.xml` — the app's own launcher-icon foreground.
Redrawing it by hand would produce something recognisably similar that drifts every time either one
is touched. `src/assets/og-image.svg` carries a second copy of the same path data. Nothing enforces
that the three agree; if the launcher icon changes, all three move together.

The webmanifest icons declare no `"purpose": "maskable"`. The mark is fitted tightly to its viewBox
(the adaptive-icon safe-zone inset is deliberately dropped, so it does not render weak at 16px),
which is the wrong shape for a maskable icon's 40 % safe circle.

### Regenerating the icon set and the social card

Both need `rsvg-convert` (`brew install librsvg`); the `.ico` also needs ImageMagick.

**The fonts are the trap.** Outfit and Fira Code are not installed system-wide anywhere — they exist
as TTFs in `app/src/main/res/font/`. Rasterising without wiring them up substitutes a default sans,
and the card ships in the wrong typeface. It looks fine. It just is not the product. Worse, on macOS
`pangocairo` defaults to the **CoreText** backend, which ignores `FONTCONFIG_FILE` entirely — so
pointing fontconfig at the repo fonts is not enough on its own and produces no warning.

Save this as `/tmp/thor-fonts.conf` (adjust the first `<dir>` to your checkout):

```xml
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
<fontconfig>
  <dir>/absolute/path/to/Thor/app/src/main/res/font</dir>
  <dir>/System/Library/Fonts</dir>
  <dir>/Library/Fonts</dir>
  <cachedir>/tmp/thor-fc-cache</cachedir>
  <include ignore_missing="yes">/opt/homebrew/etc/fonts/conf.d</include>
</fontconfig>
```

Then, from `web/`:

```sh
export FONTCONFIG_FILE=/tmp/thor-fonts.conf
export PANGOCAIRO_BACKEND=fc          # without this, macOS silently uses CoreText
printf 'path{fill:#f0ffd7!important}' > /tmp/thor-icon.css

# Social card. Verify with `fc-match Outfit` first — it must answer outfit_regular.ttf,
# not Verdana. The shipped Fira Code registers under the family name "Fira Code Light",
# because the variable font's default instance is wght 300; that is why og-image.svg
# lists both names.
rsvg-convert src/assets/og-image.svg -w 1200 -h 630 -o public/og-image.png

# Opaque app-icon rasters. The stylesheet forces the dark-scheme fill, because
# favicon.svg's default is the light one and the backgrounds here are the launcher
# icon's own #000000.
rsvg-convert public/favicon.svg -s /tmp/thor-icon.css -b '#000000' \
  -w 140 -h 140 --page-width 180 --page-height 180 --top 20 --left 20 -o public/apple-touch-icon.png
rsvg-convert public/favicon.svg -s /tmp/thor-icon.css -b '#000000' \
  -w 150 -h 150 --page-width 192 --page-height 192 --top 21 --left 21 -o public/icon-192.png
rsvg-convert public/favicon.svg -s /tmp/thor-icon.css -b '#000000' \
  -w 400 -h 400 --page-width 512 --page-height 512 --top 56 --left 56 -o public/icon-512.png

for s in 16 32 48; do
  pad=$(( s * 11 / 100 )); inner=$(( s - 2 * pad ))
  rsvg-convert public/favicon.svg -s /tmp/thor-icon.css -b '#000000' \
    -w $inner -h $inner --page-width $s --page-height $s --top $pad --left $pad -o /tmp/ico-$s.png
done
magick /tmp/ico-16.png /tmp/ico-32.png /tmp/ico-48.png public/favicon.ico
```

The PNG is committed, not generated at build time: Open Graph consumers handle SVG badly, and the
render needs fonts that are not on a CI runner.

### Sitemap

`@astrojs/sitemap` emits `sitemap-index.xml` and `sitemap-0.xml`, and its `filter` drops
`/styleguide` — the second of three independent exclusions, alongside `getStaticPaths` returning
`[]` under `import.meta.env.PROD` and the `Disallow` in `public/robots.txt`.

`npm run check:sitemap` runs inside `npm run build` and asserts the pair agrees: `robots.txt`
declares exactly one `Sitemap:`, that path exists in `dist`, every child the index names exists too,
and nothing `Disallow`ed appears in any sitemap.

That gate exists because this was wrong once and looked fine. `robots.txt` advertised
`/sitemap-index.xml` while the integration was commented out of `astro.config.mjs` for want of the
dependency. Both files read correctly on their own; the pair 404ed. A crawler reports nothing about
a missing sitemap — it just follows links instead — so the only symptom is slower indexing months
later, with nothing in any log to attribute it to.
