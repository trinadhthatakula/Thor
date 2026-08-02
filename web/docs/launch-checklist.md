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

**The order matters more than any individual step.** Each of these is what stops the next one from
failing in a way that does not look like a failure.

- [ ] **Create the project from the CLI. Do not use `vercel.com/new` → Import Git Repository.**

      cd web && npx vercel@58 login && npx vercel@58 link   # choose "create a new project"

      This is the whole ball game. Importing the repository sets Root Directory to `web` (the only
      sane-looking answer in that UI), installs the deploy webhook, and writes `framework: astro`
      into the project settings. The workflow already runs the CLI inside `web/`, so a Root
      Directory of `web` resolves to `web/web` — and with `framework: astro` pulled it fails at
      `Command "astro build" exited with 127`, which names nothing about paths. `vercel link` from
      `web/` leaves Root Directory unset and connects no repository, which is what this pipeline
      wants. If it has already been imported: Settings → Git → Disconnect, and clear Root Directory.

      `vercel link` writes `.vercel/project.json` — gitignored, so it stays local.

- [ ] **Add all three repository secrets in one sitting** (Settings → Secrets and variables →
      Actions): **`VERCEL_TOKEN`** (Vercel → Account Settings → Tokens; scope it to whatever owns
      the project — on a personal account that is your username, not a team), **`VERCEL_ORG_ID`**
      and **`VERCEL_PROJECT_ID`** (the `orgId` and `projectId` fields of `.vercel/project.json`).

      A partial set behaves exactly like no set at all: the workflow exits **green** with a notice
      naming what is missing, having skipped even the checkout. A green `web-deploy` run before this
      step is complete deployed nothing. Read the notice, or the run duration — the credential-less
      runs finish in under ten seconds.

- [ ] **Make the first credentialed run a push to `dev`, not a test PR.** Vercel makes the first
      deployment of a new project a production deployment regardless of `--prod`. A PR run would be
      built with `VERCEL_ENV=preview` — screenshot gate advisory, preview environment variables —
      and would still be the deployment the domain attaches to, while the PR comment calls it a
      preview.
- [ ] **Confirm Root Directory is empty** in Settings → Build & Deployment, whichever way the
      project was made.
- [ ] **Set no Build / Install / Output override in the dashboard.** `web/vercel.json` carries all
      three and takes precedence over project settings — that precedence is the only thing keeping a
      dashboard edit from silently removing the gate chain.
- [ ] **Leave Production Branch alone, and never set it to `dev`.** It defaults to `master` here,
      which is harmless. Pointing it at `dev` is the one edit that would make a connected Git
      integration race this workflow for the production alias.
- [ ] **Confirm both copies of the kill switch survive**: `"git": { "deploymentEnabled": false }` in
      `web/vercel.json` *and* in `/vercel.json` at the repository root. Two copies because Vercel
      does not document which one its Git integration reads. Neither is a substitute for leaving the
      repository disconnected; see `web/docs/deploy.md`.
- [ ] Optional, for the advisory Lighthouse job only: **`VERCEL_AUTOMATION_BYPASS_SECRET`**
      (Vercel → Project → Settings → Deployment Protection → Protection Bypass for Automation).
      Without it that job skips with a notice; nothing else is affected. Set it before the first PR
      run or it skips invisibly on every one.
- [ ] **Before the first `dev` → `master` release merge**, confirm the project is still not
      connected to the repository. That merge is what puts `web/` on `master`, and on a connected
      project a `master` push is a real Vercel production deploy built from the Gradle repo root,
      with no Actions run competing. Getting this wrong breaks the site at release time, not at
      setup time.

"Include files outside of the Root Directory" no longer matters: the whole repository is checked out
in Actions, so `repo-facts` reading `gradle.properties` from the root just works.

Leave the Ignored Build Step on **Automatic** — the path filter lives in the workflow's `paths:`
list, and Vercel has previously shipped a build where the Ignored Build Step cancelled `--prebuilt`
CLI deploys. `ignoreCommand` remains in `web/vercel.json`; see `web/docs/deploy.md` before touching
it, and never add `--no-wait` to the deploy step.

## 6. Domain and certificate

**Do not check this in a browser until `vercel certs ls` says the certificate exists.** The browser
will fail for a reason that has nothing to do with anything you did, and the obvious diagnosis is
the one that breaks the working DNS record.

Right now, before any Vercel project claims the hostname, `https://thor.trinadhthatakula.com`
answers like this:

```console
$ openssl s_client -connect thor.trinadhthatakula.com:443 \
    -servername thor.trinadhthatakula.com </dev/null 2>/dev/null | openssl x509 -noout -subject -dates
subject=CN=*.trinadhthatakula.com
notBefore=Feb 10 12:08:05 2026 GMT
notAfter=May 11 12:08:04 2026 GMT        # expired

$ curl -sSI -k https://thor.trinadhthatakula.com | head -3
HTTP/2 404
server: Vercel
x-vercel-error: DEPLOYMENT_NOT_FOUND
```

Vercel's edge is already answering for the name and falling back to a stale `*.trinadhthatakula.com`
wildcard that expired on 11 May 2026. It cannot renew a wildcard without a DNS-01 record and the zone
is on Cloudflare nameservers, so this will not fix itself. Vercel also serves a two-year HSTS header
and 308s plain HTTP to HTTPS, so a browser that ever saw the valid certificate will refuse to let you
click through, and there is no HTTP fallback to test with. `ERR_CERT_DATE_INVALID` here means "no
project has claimed this hostname yet" — it does not mean the DNS record is wrong.

- [ ] Add `thor.trinadhthatakula.com` in Vercel **after** the first production deployment exists.
      A newly added domain is applied to the project's latest production deployment; added first, it
      attaches to nothing.
- [ ] DNS needs no change. The `thor` record is already a **DNS-only (grey cloud)** CNAME to
      `cname.vercel-dns.com`, which Vercel accepts — it validates that the name resolves *to Vercel*,
      not that it matches the currently recommended target string.
- [ ] Verify in this order, and only this order:

      npx vercel@58 domains inspect thor.trinadhthatakula.com   # expect Valid Configuration
      npx vercel@58 certs ls                                    # expect a cert for the exact host
      openssl s_client -connect thor.trinadhthatakula.com:443 \
        -servername thor.trinadhthatakula.com </dev/null 2>/dev/null | openssl x509 -noout -subject -dates

      The `openssl` step must show `CN=thor.trinadhthatakula.com`, **not** the
      `*.trinadhthatakula.com` wildcard. Seeing the wildcard means the hostname is still unclaimed,
      whatever the dashboard says.

- [ ] If `vercel certs ls` shows nothing after about 15 minutes, the domain is not attached —
      re-run `domains inspect`. **Do not touch Cloudflare.**
- [ ] If Vercel reports the domain is already in use by another project or account, check
      `vercel domains ls` first: the expired wildcard being served from Vercel's edge means
      `*.trinadhthatakula.com` was registered with Vercel at some point, and it may still be.
- [ ] A Cloudflare **526 cannot happen here** and is not the error to look for. 526 requires
      Cloudflare to be in the request path, which means an orange-clouded record; this one is grey.
      If a record is ever orange-clouded and a 526 appears, **do not switch the SSL mode to
      Flexible** — it appears to fix the error and serves the origin hop unencrypted, on a site
      whose privacy stance is the entire argument.

## 7. After launch

- [ ] Weekly external link check runs against the live site and opens an issue on failure. It is
      dormant today: `schedule` and the Run-workflow button only exist for workflows on the **default
      branch**, and `web-link-check.yml` is on `dev`. The first `dev` → `master` release merge arms
      it — so if that merge lands before the site is deployed, it files an issue every Tuesday until
      it is. Deploy first, or expect the noise.
- [ ] The Lighthouse job (now the second job in `web-deploy.yml`) reports as a warning only. If it
      ever becomes a required check, the site cannot ship a legitimate large screenshot.
- [ ] Neither `web-ci.yml` nor `web-deploy.yml` has been added to a branch ruleset. Both are
      path-filtered, and GitHub reports a path-skipped required check as "Expected — Waiting for
      status" forever, which would leave every Android-only PR unmergeable.
