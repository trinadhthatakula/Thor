# Launch checklist — thor.trinadhthatakula.com

Run top to bottom before pointing the domain at the deployment. Everything in "Owner action" needs a
person; everything else is a command whose output is the evidence.

## 1. Owner action — two external claims that the new site contradicts

The homepage trust note says Thor declares `INTERNET` and names the one file that uses it. Two
published pages currently say the opposite. Whoever reads "check it yourself" and does so will land
on one of them, and the site will look like the thing that is lying.

- [ ] **`rxspectra.web.app` privacy policy** — still states Thor has no internet access. `/privacy`
      on the new site is its replacement. Either redirect it or correct it; leaving both live means
      two policies of record disagree about a network permission.
- [ ] **IzzyOnDroid summary line** — reads "100% offline & FOSS" while the same page lists `INTERNET`
      in its own permission table. Needs a metadata PR to fix, so start it early rather than at
      launch.

Neither of these is something to change on the owner's behalf: one is another person's repository,
the other is a site under the owner's name whose current text may be load-bearing somewhere else.

## 2. Owner action — file the narrowed product-gap issue

PR #314 closed the general "freezing a system app destroys its data" gap. What is left is narrower
and still real, and the site now describes it accurately, which means the issue and the page should
be filed together:

- **Dhizuku is unconverted.** `DhizukuSystemGateway` still removes the package for the user
  unconditionally and still does not pass `-k`, so it is the one privilege mode where freezing a
  system app really does lose the app's data.
- **The Android 17 Shizuku dead end.** At the shell uid, `pm uninstall -k --user N` on a system app
  is refused outright on Android 17. A Shizuku user whose device also refuses `disable` therefore
  cannot freeze that system app at all. Thor reports the failure rather than doing something else,
  which is correct behaviour and still a gap.

## 3. Gates — all four green locally

Run from `web/`. Each has to be run, not assumed: the whole point of the must-fail fixtures is that a
gate which exits 0 for the wrong reason looks identical to one that passes.

- [ ] `npm run build` — chains `check:types`, `check:links`, `check:claims`, `check:markup` and
      `check:sitemap`, so a stray `<em>` or a 404ing `Sitemap:` line fails here rather than in review.
- [ ] `npm run check:links` — both directions. A broken fragment fails; a correct `/faq` link and a
      correct `#you-may-not-need-root` fragment do not false-fail.
- [ ] `npm run check:claims` — runs against `dist`, so copy assembled from components and `<Fact>` is
      covered. An allowlist entry matching nothing is itself a failure.
- [ ] `npm run check:a11y` — not in the build chain; it needs jsdom and a real CSSOM.
- [ ] `npm test` — the fixture meta-test included, which is what proves every rule has both a
      must-fail and a must-pass case, plus `props-detection.test.ts`, which is the only place an
      Astro component silently losing its call-site prop checking is visible.

## 4. Sweep — nothing internal survived into the build

- [ ] No review scaffolding in the output:

      grep -riE 'draft for owner review|Notes for the owner|Open questions|Settled 1 August 2026|on your behalf|⚠️ Confirm' web/dist

      Expect no matches. These strings come from the internal drafts and reaching production is the
      most embarrassing available failure.

- [ ] No `/styleguide` in `dist`:

      ls web/dist/styleguide 2>&1

      Expect "No such file or directory". `getStaticPaths` returns `[]` under `import.meta.env.PROD`;
      if the directory exists, that exclusion has broken.

      `/styleguide` is also kept out of the sitemap, and out of `robots.txt`, independently of this
      exclusion. `check:sitemap` asserts all three agree, so it needs no manual step here.

- [ ] Five screenshots in place and no `DeviceFrame` placeholder left in `dist`:

      cd web && REQUIRE_SCREENSHOTS=1 npm run check:screenshots

      See `web/docs/screenshot-checklist.md`. The build is green with placeholders by design; this
      is the check that stops "green" from meaning "finished".

      **This one is now enforced, not just listed.** `check:screenshots` runs in the `build` chain
      on every build, but it only *fails* when `VERCEL_ENV=production` — so placeholders stay green
      locally, in CI and on preview deploys, and a production deploy carrying one is refused. That
      matters because the production branch is `dev`: merging the release PR **is** the deploy, so
      there is no window in which a human runs this list first. Run the command above to see the
      strict verdict early; a green production deploy has already asserted it.

## 5. Deploy configuration — verify visually in the Vercel dashboard

Four settings that produce no error when wrong.

- [ ] **Root Directory** = `web/`
- [ ] **Production Branch** = `dev`
- [ ] **"Include files outside of the Root Directory in the Build Step"** = ON. Off breaks
      `repo-facts` with a file-not-found deep in the build, because it reads `gradle.properties` from
      the repo root.
- [ ] **Build Command** = default, *not* overridden. `@vercel/static-build`'s `getCommand()` returns
      null when `package.json` has a `build` script, so `npm run build` wins and the gates run.
      Overriding it to the preset's `astro build` makes all four gates disappear from the deploy path
      with no error at all.

Then confirm the Ignored Build Step still reads:

    git diff --quiet HEAD^ HEAD -- . ':/gradle.properties' ':/gradle/libs.versions.toml'

The `:/` prefixes are load-bearing. The step runs inside the Root Directory, so a bare
`gradle.properties` pathspec resolves to `web/gradle.properties`, matches nothing, and exits 0 for
every commit — the site would simply stop rebuilding, silently.

## 6. Domain and certificate

- [ ] Add `thor.trinadhthatakula.com` in Vercel and let it issue the certificate. DNS needs no
      change; the Cloudflare wildcard already resolves.
- [ ] Confirm the certificate is actually issued before announcing. A proxied `*` wildcard means the
      name resolves whether or not anything is serving it, so "it resolves" is not a test.
- [ ] **If you see a 526, do not switch Cloudflare's SSL mode to Flexible.** It appears to fix the
      error and serves the site over an unencrypted hop to the origin — on a site whose privacy
      stance is the entire argument. A 526 means the certificate is not issued yet; wait for it.

## 7. After launch

- [ ] Weekly external link check runs against the live site and opens an issue on failure.
- [ ] Lighthouse workflow reports as a warning only. If it ever becomes a required check, the site
      cannot ship a legitimate large screenshot.
