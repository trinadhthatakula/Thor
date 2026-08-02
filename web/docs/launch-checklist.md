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

      **This one is enforced, not just listed.** `check:screenshots` runs in the `build` chain on
      every build, but it only *fails* when `VERCEL_ENV=production` or `REQUIRE_SCREENSHOTS=1` — so
      placeholders stay green locally, in CI and on `dev` previews, and a production deploy carrying
      one is refused. Vercel injects `VERCEL_ENV` itself, which is why the "Enable access to System
      Environment Variables" item in §5 matters: with it off, the strict half never runs.

      Nothing sets `REQUIRE_SCREENSHOTS=1` on a pull request into `master` any more — that was the
      Actions pipeline, which is dormant. So the release PR's preview is *not* held to the production
      standard; the production build is the first strict one, and it fails rather than publishing a
      placeholder. Running the command above is therefore the only way to see the strict verdict
      before the merge that publishes.

## 5. Deploy configuration — ✅ done, and what has to stay true

**Done 2026-08-02.** The Vercel project was created by importing this repository from the Vercel
dashboard, and the site deploys from **Vercel's Git integration**. That is the opposite of what this
section originally prescribed; `web/docs/deploy.md` is the reference for the model that is actually
running, and `docs/follow-ups/vercel-actions-deploy.md` holds the Actions path that was deferred.

The remaining items are settings to confirm rather than steps to perform, and they are worth
confirming because **none of them is visible in a diff and most fail without an error**.

- [x] **`web/` is on `master`.** `master` publishes, `dev` stages.
- [x] **The project is created and connected**, Production Branch `master`.
- [ ] **Confirm Root Directory is `web`** in Settings → Build & Deployment. Unset, Vercel builds from
      the repository root, finds no `package.json`, and fails. Note this is the exact inverse of what
      the deferred Actions path needs, where it must be *unset* — do not carry one model's value into
      the other.
- [ ] **Confirm "Include files outside the Root Directory" is on.** The build reads
      `../gradle.properties` and `../gradle/libs.versions.toml`. This one fails *loudly*
      (`RepoFactsError`), and it is currently correct — `/download` renders "Android 9 (API 28)",
      which is derived from `gradle/libs.versions.toml`.
- [ ] **Confirm "Enable access to System Environment Variables" is on**, Settings → Environment
      Variables. `check:screenshots` is strict only when `VERCEL_ENV=production`, and Vercel is what
      injects it. If the box is off the gate silently downgrades to advisory **on the production
      build**, which is the one place it is supposed to bite. Vercel does not document the default
      for a portal import, so this is a one-click check worth actually doing.
- [ ] **Set no Build / Install / Output override in the dashboard.** `web/vercel.json` carries all
      three and takes precedence over project settings — that precedence is the only thing keeping a
      dashboard edit from silently removing the gate chain.
- [ ] **Leave the dashboard's Ignored Build Step empty.** `web/vercel.json`'s `ignoreCommand`
      overrides it, and the reviewed copy should be the one that wins. Read `web/docs/deploy.md`
      before changing it: exit 0 means *skip*, and the `:/` prefixes are load-bearing.
- [ ] **Do not set `VERCEL_TOKEN`, `VERCEL_ORG_ID` or `VERCEL_PROJECT_ID` as repository secrets.**
      Those three being unset is what keeps `web-deploy.yml` dormant. Setting them without first
      disconnecting the Git integration starts two production deployments racing for the same alias
      on every `master` push, with no error anywhere. See the follow-up for the interlock.
- [ ] **The first real push is still untested.** The live deployment was started from the dashboard
      at import; no commit has been pushed to any branch since the project existed. So the first
      `master` push touching `web/` is the first time the trigger path runs at all — watch the
      Deployments list for it, and confirm exactly one row appears.

Two traps that will corrupt that first test if the commit does not touch `web/`: `ignoreCommand` will
cancel the build, and Vercel's "Skipping unaffected projects" setting can skip it before that. Test
with a commit that touches `web/`.

Optional, for the dormant Lighthouse job only: **`VERCEL_AUTOMATION_BYPASS_SECRET`** (Vercel →
Project → Settings → Deployment Protection → Protection Bypass for Automation). Nothing needs it
today.

## 6. Domain and certificate — ✅ done

**Passed 2026-08-02.** The domain is attached and the certificate is this project's, not the stale
wildcard:

```console
$ openssl s_client -connect thor.trinadhthatakula.com:443 \
    -servername thor.trinadhthatakula.com </dev/null 2>/dev/null \
    | openssl x509 -noout -ext subjectAltName -dates -issuer
X509v3 Subject Alternative Name:
    DNS:thor.trinadhthatakula.com
notBefore=Aug  2 17:29:49 2026 GMT
notAfter=Oct 31 17:29:48 2026 GMT
issuer=C=US, O=Let's Encrypt, CN=YR1

$ curl -sSI https://thor.trinadhthatakula.com | head -1
HTTP/2 200
```

DNS needed no change: the `thor` record was already a **DNS-only (grey cloud)** CNAME to
`cname.vercel-dns.com`, which Vercel accepts — it validates that the name resolves *to Vercel*, not
that it matches the currently recommended target string.

The rest of this section is kept because it is what to re-read if the hostname ever stops answering,
and because the pre-launch state was a convincing-looking false alarm.

**Read the SAN, not the subject.** The identity of a modern certificate lives in `subjectAltName`;
the CN is legacy and may be absent or may not be the name you connected to. The check passes only
when the SAN lists `thor.trinadhthatakula.com` **explicitly**.

**A SAN of only `*.trinadhthatakula.com` is the failing case**, even though that wildcard does
technically match the host under RFC 6125. Before this project claimed the hostname, Vercel's edge
answered for it by falling back to exactly that wildcard — expired on 11 May 2026, unrenewable
because a wildcard needs a DNS-01 record and the zone is on Cloudflare nameservers — and returned
`HTTP/2 404` with `x-vercel-error: DEPLOYMENT_NOT_FOUND`. Seeing that combination means the hostname
is unclaimed, whatever the dashboard says. Checking dates alone is not enough either: a *renewed*
wildcard would pass a date check and still not be this project's certificate.

**Do not diagnose this in a browser first.** Vercel serves a two-year HSTS header and 308s plain HTTP
to HTTPS, so a browser that ever saw the valid certificate will not let you click through, and there
is no HTTP fallback to test with. `ERR_CERT_DATE_INVALID` on this host means "no project has claimed
this hostname" — it does not mean the DNS record is wrong, and the obvious fix breaks a working
record.

If it ever needs re-doing:

- Add the domain in Vercel **after** a production deployment exists. A newly added domain is applied
  to the project's latest production deployment; added first, it attaches to nothing.
- `vercel domains inspect thor.trinadhthatakula.com` → expect Valid Configuration, then
  `vercel certs ls` → expect a cert for the exact host, then the `openssl` command above. That order.
- Nothing after 15 minutes means the domain is not attached. Re-run `domains inspect`. **Do not touch
  Cloudflare.**
- "Domain already in use" → check `vercel domains ls` first. The expired wildcard came from
  somewhere, so `*.trinadhthatakula.com` may still be registered with Vercel.
- A Cloudflare **526 cannot happen here**. 526 requires Cloudflare in the request path, which means an
  orange-clouded record; this one is grey. If a record is ever orange-clouded and a 526 appears, **do
  not switch the SSL mode to Flexible** — it appears to fix the error and serves the origin hop
  unencrypted, on a site whose privacy stance is the entire argument.

## 7. After launch

- [ ] Weekly external link check runs against the live site and opens an issue on failure. It is
      dormant today: `schedule` and the Run-workflow button only exist for workflows on the **default
      branch**, and `web-link-check.yml` is on `dev`. Putting `web/` on `master` arms it — the same
      merge that makes production deploys possible at all — so the window between "armed" and
      "deployed" is however long the Vercel setup in §5 takes. It runs Tuesdays at 06:00 UTC and
      files an issue against a site that is not up yet; finish §5 and §6 inside that window, or
      expect one issue and close it.
- [ ] The Lighthouse job (now the second job in `web-deploy.yml`) reports as a warning only. If it
      ever becomes a required check, the site cannot ship a legitimate large screenshot.
- [ ] Neither `web-ci.yml` nor `web-deploy.yml` has been added to a branch ruleset. Both are
      path-filtered, and GitHub reports a path-skipped required check as "Expected — Waiting for
      status" forever, which would leave every Android-only PR unmergeable.
