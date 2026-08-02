# Deploying thor.trinadhthatakula.com

The site deploys from **Vercel's Git integration**. The Vercel project was created by importing this
repository from the Vercel dashboard, its Root Directory is `web`, and every build happens inside
Vercel. `web/vercel.json` is the only part of that pipeline that lives in git.

`.github/workflows/web-deploy.yml` is a complete, reviewed, CLI-driven alternative that has **never
deployed anything**. It is not the deployer; see "The dormant Actions pipeline" at the end, and
`docs/follow-ups/vercel-actions-deploy.md` for the reactivation procedure.

| Trigger | What Vercel does | Result |
|---|---|---|
| push to `master` touching the web paths | production build | live at `thor.trinadhthatakula.com` |
| push to `master` touching nothing the site reads | starts, then aborts | `ignoreCommand` exits 0, the deployment is `CANCELED`, the previous production deployment keeps serving |
| push to `dev` touching the web paths | preview build | staging URL, on the commit status |
| push to any other branch touching the web paths | preview build | same |
| pull request from a branch in this repository | preview build | aliased at `thor-git-<branch>-…vercel.app`, `success` commit status, URL commented by the Vercel bot |
| pull request from a fork | not yet observed here | Vercel documents a deployment and a comment per PR; confirm on the first fork PR rather than assuming |

Preview URLs sit behind Deployment Protection, so the alias answers 302 rather than serving. That is
the protected state, not a broken deploy — a nonexistent alias 404s.

A failed or cancelled build does not replace what is serving. Vercel swaps the alias only on success,
so the worst case is a **stale** site, never a wrong one. That property is the reason several of the
gates below are allowed to be strict.

## The branch model: `master` publishes, `dev` stages

The policy is unchanged from when Actions was to be the deployer; what changed is who enforces it.
Vercel's Production Branch is `master`, so a push there publishes and every other branch is a preview
by Vercel's own rule. Site work merges to `dev` like everything else, and the live site moves only
when `dev` is merged to `master`.

Vercel picked `master` at import time by the second of four documented rules: a branch named `main`,
else a branch named `master`, else (Bitbucket only) the repository's production-branch setting, else
the repository's default branch. This repo has no `main`. That `master` is also the default branch is
a coincidence — do not reason from it.

**One property did not survive the change.** Under the Actions design, a pull request into `master`
was built with `REQUIRE_SCREENSHOTS=1` so that the release PR's preview was held to production
strictness *before* the merge that publishes. Nothing sets that variable now, so the first build ever
held to the production standard is the production build itself. The consequence is bounded by the
atomicity above — a placeholder reaching `master` fails the production build, leaving the previous
deployment serving — so the failure mode is a stale site and a red deployment on the release merge,
not a placeholder on the live domain. Restoring the old property is a small change to `web-ci.yml`'s
build step, in git and reviewable.

## What triggers a deploy: the `ignoreCommand` path filter

Most commits here are Android changes. The site must not rebuild unless something it *reads* has
moved, and the live filter that decides is the `ignoreCommand` in `web/vercel.json` — not the
`paths:` list in any workflow.

```
git diff --quiet HEAD^ HEAD -- . ':/gradle.properties' ':/gradle/libs.versions.toml'
```

- **The exit codes are inverted.** Exit 0 aborts the build and marks the deployment `CANCELED`; exit
  1 or greater proceeds. So `--quiet`'s "no differences → 0" reads as "nothing the site renders has
  changed → do not rebuild", which is the intent.
- **It fails open.** A typo, a crash or a `command not found` returns something non-zero and a
  deployment is built. A broken filter over-deploys; it cannot silently stop publishing.
- **It runs inside the Root Directory.** That is why `.` means `web/`, and why the two Gradle paths
  carry the `:/` root-relative prefix. Drop the `:/` and `gradle.properties` resolves to
  `web/gradle.properties`, matches nothing, and every `chore(release)` commit exits 0 — "Build
  skipped", correctly, forever, while the site keeps rendering the previous version number and no
  check anywhere goes red. This is the silent failure this section exists to prevent.
- **The window is one commit, not one push.** Vercel clones with `git clone --depth=10`, so `HEAD^`
  is always available, but `HEAD^ HEAD` examines only the tip commit — where GitHub's `paths:` filter
  examines a whole PR diff. A three-commit push to `dev` in which only the first touches `web/` is
  skipped. **What that costs is a missing preview, not a stale production site.** A release merge
  into `master` is safe by construction: `HEAD^` on a merge commit is the previous `master` tip, so
  the diff spans everything merged — and nothing pushes to `master` except merges, so the gap cannot
  reach production while that holds. Closing it properly would need the push's base commit, which
  Vercel does not expose to `ignoreCommand`; the alternative of building unconditionally rebuilds the
  site on every Android commit, which is the thing this filter exists to stop. The gap is accepted
  knowingly. If `master` ever takes a direct multi-commit push, this becomes real.
- **A skipped build still costs a deployment record and a build slot.** It is fast, not free.
- `vercel.json`'s `ignoreCommand` **overrides** the dashboard's Ignored Build Step, so leave that
  dashboard field empty and let the reviewed copy win.

The same path list lives in four places — this `ignoreCommand`, `web-ci.yml`'s `paths:`,
`web-deploy.yml`'s `paths:` (dormant), and the `GRADLE_PROPERTIES` / `VERSION_CATALOG` constants in
`src/lib/repo-facts/read.ts`. Adding a third derived fact from a third file means editing all four in
one commit, or the site serves a value that no longer rebuilds when it changes.

## Vercel project settings, all of which are now load-bearing

Under the Actions design most of these did not matter, because the build did not happen on Vercel.
Now every one of them does, **and none of them is visible in a diff**. Two named examples of what
that costs: a Build Command set in the dashboard would replace `npm run build` and all six content
checkers would silently stop running; a wrong pathspec in the Ignored Build Step would leave every
check green while the site never rebuilt again.

The mitigation is `web-ci.yml`, which runs `npm ci && npm test && npm run build` on every push and
every PR touching the web paths, on both branches, from a file in git. A dashboard edit cannot reach
it, so the gates cannot vanish — the commit still goes red. What CI cannot do is stop the deploy. It
can only report on it afterwards.

- **Root Directory — `web`.** Settings → Build & Deployment. Unset, Vercel builds from the repository
  root, finds no `package.json`, and fails; set to anything else, `web/vercel.json` is not read at
  all and the dashboard's own Build Command silently replaces the gate chain. This is the exact
  inverse of what the Actions path needs, where it must be **unset** — see the follow-up.
- **"Include files outside the Root Directory" — on.** This is what makes the site render the right
  version number. `src/lib/repo-facts/read.ts` walks up until it finds `settings.gradle.kts`, then
  reads `gradle.properties` and `gradle/libs.versions.toml`. It is the one setting here that fails
  *loudly*: `findRepoRoot` throws a `RepoFactsError` naming the cause and the build goes red. It is
  currently correct — the live `/download` page renders values derived from those two files.
- **Build Command, Install Command, Output Directory — leave all three unset in the dashboard.**
  `web/vercel.json` supplies them and takes precedence. That precedence is the only in-git lever left
  over what the deploy actually runs.
- **Production Branch — `master`.** See the branch model above.
- **System Environment Variables — enabled.** Settings → Environment Variables → "Enable access to
  System Environment Variables". `check:screenshots` is strict only when `VERCEL_ENV=production`, and
  Vercel injects that variable. If the box is off, `VERCEL_ENV` is empty, `isProductionDeploy()`
  returns false, and the screenshot gate silently downgrades from gate to advisory **on the
  production build**. Vercel does not document whether it is on by default for a portal import.
  Verify it once, in one click.
- **Deployment Protection.** A protected preview URL returns 401 with no notice explaining why. Every
  branch and every PR now gets a preview URL posted by the Vercel bot, so this is more visible than
  it was.
- **Domain.** `thor.trinadhthatakula.com` is attached and serving. Vercel applies a newly added
  domain to the project's latest *production* deployment, which is also why a rollback takes effect
  through `vercel promote` rather than through DNS.

Verify any change to these **by observation, not by reading the setting back**: push one no-op commit
that touches `web/` and count the deployments the project creates.

## Where a failure shows up

Vercel posts a GitHub commit status for every commit it creates a deployment for, and comments the
preview URL on pull requests. Both are on by default. So a failed Git-integration build is visible on
the commit and on the PR without any Actions job; this is not a red box on a dashboard nobody has
open.

Success and failure have both been observed here. What a commit *skipped* by `ignoreCommand` reports
— a status GitHub treats as passing, or no status at all — has not been, which is why the required-
checks section below says not to make Vercel's status required until an Android-only commit has been
watched through.

## What is gated, and what is not

`buildCommand` is `npm run build`, which is the chain: `check:types`, `astro build`, `check:links`,
`check:claims`, `check:markup`, `check:sitemap`, `check:screenshots`. A content failure fails the
deployment, and the previous deployment keeps serving.

Three honest gaps:

- **`npm test` does not run in the deploy path.** It runs in `web-ci.yml` only, as does `check:a11y`.
  A red unit test does not stop a deploy; it shows as a red `web` check on the commit.
- **Nothing re-gates the uploaded artifact.** Vercel uploads what its own build produced. The Actions
  path had a separate check on the staged output; that is one of the things reactivating it would buy.
- **`check:screenshots` strictness rides entirely on Vercel injecting `VERCEL_ENV`.** See the System
  Environment Variables bullet. The mechanism itself is unchanged in code: strict when
  `VERCEL_ENV=production` **or** `REQUIRE_SCREENSHOTS=1` (`web/scripts/check-screenshots.mjs`).

## The one thing that must stay true

**Exactly one deploy path may be able to reach production.**

Today that is Vercel's Git integration, and the thing guaranteeing it is that
`VERCEL_TOKEN` / `VERCEL_ORG_ID` / `VERCEL_PROJECT_ID` are unset, so `web-deploy.yml` skips. Setting
them without first disconnecting the Git integration starts two production deployments racing for the
same alias on every `master` push, with no error anywhere and no symptom except the live site
intermittently serving the older build.

`web/vercel.json` no longer carries `"git": { "deploymentEnabled": false }`, and the repository-root
`/vercel.json` that held a second copy has been deleted. That flag was the kill switch for the
opposite arrangement; keeping it while relying on the Git integration is what would stop the site
deploying. If the Actions path is ever reactivated, note that reactivation *also* clears Root
Directory to empty, and which `vercel.json` Vercel reads follows Root Directory — so the flag would
belong at `/vercel.json`, not in `web/vercel.json`. Either way it is defence in depth.
**Disconnecting the repository is the guarantee.**
`docs/follow-ups/vercel-actions-deploy.md` carries the full interlock.

## Rolling back

Rollback is a Vercel action under either model — Vercel keeps every deployment.

```sh
vercel rollback              # to the previous production deployment
vercel promote <deploy-url>  # to a specific one
```

or Vercel → Project → Deployments → "…" → Promote to Production. **Rolling back does not revert
git**, and that matters more now than it did: the next push to `master` that touches a web path
redeploys the bad build automatically. Follow a rollback with a revert commit, not with a plan to
remember.

## Path filters and required checks

`web-ci.yml`, like the dormant `web-deploy.yml`, is path-filtered and **must never be added to a
branch ruleset**. GitHub reports a path-skipped required check as "Expected — Waiting for status"
forever, so an Android-only PR would sit unmergeable. That is why `pr-ci.yml` carries no path filter
at all — `build-and-test` is the check the `dev` and `master` rulesets require.

The same caution applies to Vercel's own commit status, for a different reason: it is not
path-filtered in GitHub's sense, and whether a *skipped* deployment posts a status GitHub treats as
passing has not been observed here. Do not make it a required check until an Android-only commit has
been watched through.

## Lighthouse

The advisory Lighthouse run is a second job in `web-deploy.yml` and is therefore dormant with it — it
is `needs: deploy` and gated on a non-empty deploy URL.

It used to be `web-lighthouse.yml`, which found the preview URL by polling the GitHub Deployments
API. That only works because Vercel's Git integration writes those deployment records, and CLI
deployments do not carry full `gitSource` metadata — which is why it was folded into the deploy job
when Actions was to be the deployer. Under the current model the records exist again, so
resurrecting a standalone Lighthouse job is a live option rather than a broken one. It remains
advisory either way: every assertion in `lighthouserc.json` is `warn`.

## The dormant Actions pipeline

`.github/workflows/web-deploy.yml` installs, tests, builds, re-gates the built artifact, uploads it
with the Vercel CLI and comments the URL. Its first step looks for the three secrets, finds none,
prints a notice and skips the other eleven steps.

**Every green `Web Deploy` tick in this repository's history is that skip.** A partial secret set
behaves identically — green, with a notice, having deployed nothing. Read the notice, not the tick.

`docs/follow-ups/vercel-actions-deploy.md` is the reactivation procedure, the interlock that makes it
one change rather than two, and an honest account of what it would and would not buy.
