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

- [ ] All six screenshots in place and no `DeviceFrame` placeholder left in `dist`:

      cd web && REQUIRE_SCREENSHOTS=1 npm run check:screenshots

      See `web/docs/screenshot-checklist.md`. The build is green with placeholders by design; this
      is the check that stops "green" from meaning "finished".

      **This one is now enforced, not just listed.** `check:screenshots` runs in the `build` chain
      on every build, but it only *fails* when `VERCEL_ENV=production` — so placeholders stay green
      locally, in CI and on preview deploys, and a production deploy carrying one is refused. That
      matters because the production branch is `dev`: merging the release PR **is** the deploy, so
      there is no window in which a human runs this list first. Run the command above to see the
      strict verdict early; a green production deploy has already asserted it.

## 5. Deploy configuration — create the Vercel project and wire the secrets

Deploys run from `.github/workflows/web-deploy.yml`, not from Vercel's Git integration, so almost
nothing here is a dashboard setting. `web/docs/deploy.md` is the full reference; this is the launch
sequence.

- [ ] Create the Vercel project, then `cd web && npx vercel link` locally. That writes
      `.vercel/project.json` — gitignored, so it stays local.
- [ ] Add three repository secrets (Settings → Secrets and variables → Actions):
      **`VERCEL_TOKEN`** (Vercel → Account Settings → Tokens), **`VERCEL_ORG_ID`** and
      **`VERCEL_PROJECT_ID`** (the `orgId` and `projectId` fields of that file).

      Until all three exist the workflow exits **green** with a notice naming what is missing, so
      web PRs stay mergeable in the meantime. A green `web-deploy` run before this step is done has
      deployed nothing — check the notice, not the tick.

- [ ] **Leave Root Directory at the repository root.** The workflow already runs the CLI from inside
      `web/`; setting it to `web` as well risks resolving to `web/web`. If it is wrong, the "Gate the
      staged artifact" step fails naming this cause rather than shipping an empty tree.
- [ ] **Set no Build / Install / Output override in the dashboard.** `web/vercel.json` carries all
      three and takes precedence over project settings — that precedence is the only thing keeping a
      dashboard edit from silently removing the gate chain.
- [ ] **Confirm `web/vercel.json` still contains `"git": { "deploymentEnabled": false }`.** Removing
      it while this workflow exists makes every web commit deploy twice — Git integration and Actions
      racing for the same production alias — and the only symptom is the site intermittently serving
      the older of two commits.
- [ ] Optional, for the advisory Lighthouse job only: **`VERCEL_AUTOMATION_BYPASS_SECRET`**
      (Vercel → Project → Settings → Deployment Protection → Protection Bypass for Automation).
      Without it that job skips with a notice; nothing else is affected.

"Production Branch" and "Include files outside of the Root Directory" no longer matter: which deploy
is production is decided by the workflow (`--prod` only for `dev`), and the whole repository is
checked out in Actions, so `repo-facts` reading `gradle.properties` from the root just works.

The Ignored Build Step is likewise obsolete — the path filter now lives in the workflow's `paths:`
list. `ignoreCommand` remains in `vercel.json` as inert defence in depth; see `web/docs/deploy.md`
before touching it.

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
- [ ] The Lighthouse job (now the second job in `web-deploy.yml`) reports as a warning only. If it
      ever becomes a required check, the site cannot ship a legitimate large screenshot.
- [ ] Neither `web-ci.yml` nor `web-deploy.yml` has been added to a branch ruleset. Both are
      path-filtered, and GitHub reports a path-skipped required check as "Expected — Waiting for
      status" forever, which would leave every Android-only PR unmergeable.
