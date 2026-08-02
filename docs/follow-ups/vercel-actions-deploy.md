# Follow-up: deploy the site from GitHub Actions instead of Vercel's Git integration

**Status:** OPEN — deferred 2026-08-02, the day the site went live. The pipeline is written, reviewed
and merged; it has simply never run. The owner created the Vercel project from the portal, Vercel's
own Git integration now builds and deploys `web/`, and that is enough.
**Severity:** none today. This is an improvement to where decisions live, not a fix for anything
broken. Nothing about the live site is wrong while this stays open.
**Effort:** small — the workflow already exists. The work is five owner actions and one interlock.
**Raised by:** the branch-model change (#322/#323) and the portal setup that followed it.

Files: `.github/workflows/web-deploy.yml`, `web/vercel.json`, `web/docs/deploy.md`,
`web/docs/launch-checklist.md`

## What is deferred

`.github/workflows/web-deploy.yml` is a complete Vercel deploy pipeline driven by the Vercel CLI:
it installs, tests, builds, re-gates the built artifact, uploads it, and comments the URL on the pull
request. It has been merged since PR #321 and **has never deployed anything**. Its first step checks
for `VERCEL_TOKEN` / `VERCEL_ORG_ID` / `VERCEL_PROJECT_ID`, finds none, prints a notice and skips the
other eleven steps. Every `Web Deploy` tick you have ever seen on a commit is that skip.

Activating it means setting those three secrets and, in the same change, turning Vercel's Git
integration off. That second half is not optional; see the interlock below.

## Why it was deferred rather than finished

The site is live and deploying. `https://thor.trinadhthatakula.com` serves from Vercel with a
Let's Encrypt certificate whose SAN is exactly `thor.trinadhthatakula.com`, and the pages render facts
derived from `gradle.properties` and `gradle/libs.versions.toml` at the repository root — so the
Vercel build is reading files outside its Root Directory correctly, which was the setting most likely
to be silently wrong.

Finishing the Actions path from here would mean tearing down a working deployment to replace it with
an equivalent one, and the equivalence is the point: both end with the same gated artifact on the
same alias.

## What it would actually buy

Less than the original design assumed, and the honest version is worth writing down.

The original argument was that under the Git integration four dashboard settings decide whether the
site's gates run at all — Root Directory, "Include files outside the Root Directory", Build Command,
and the Ignored Build Step — and that every one of them is invisible to review and produces no error
when wrong. That is still true, and it is still the reason to do this eventually.

What blunts it is `web-ci.yml`. It runs `npm ci && npm test && npm run build` on every push and every
pull request touching the web paths, on both `master` and `dev`, from a file in git. `npm run build`
*is* the gate chain — `check:types`, `astro build`, `check:links`, `check:claims`, `check:markup`,
`check:sitemap`, `check:screenshots`. So a dashboard edit that removed the gates from the *deploy*
would not remove them from CI: the commit would still go red on GitHub. What such an edit could still
do is ship an artifact that was never gated, on a commit whose CI was green for a different build.
That gap is real, and narrower than "the gates would vanish".

The second thing it buys is a deploy that fails in the same place as every other CI failure, rather
than on a Vercel page nobody has open. Vercel does report deployment status back to the pull request,
so this is a smaller gap than it was before the project existed.

## What it would cost

- A `VERCEL_TOKEN` in repository secrets — one more credential to scope, rotate and lose.
- Three more minutes of CI per web commit, since the build happens twice: once in `web-ci.yml`, once
  in `web-deploy.yml`.
- Fork pull requests get no preview at all. The deploy job skips itself when the head repository is
  not this one, because a fork gets no secrets and every Vercel command would fail on an empty token.
  Vercel's Git integration is documented as creating a deployment for each pull request and
  commenting on it; whether that holds for a *fork* PR under this project's settings has not been
  observed here, so confirm it on the first fork PR rather than assuming the trade.
- Rollback stays a Vercel-side action either way, so nothing is gained there.

## The interlock: this is one change, not two

**Turning the Actions path on requires turning Vercel's Git integration off in the same commit.**

Vercel resolved this project's Production Branch to `master` at import time, by the second of its
four documented rules — a branch named `main`, else a branch named `master`, else (Bitbucket only)
the repository's production-branch setting, else the repository's default branch. This repo has no
`main`, so `master` won on rule two; that it is also the default branch is a coincidence, not the
cause. `web-deploy.yml`'s production branch is also `master`. Leave both active and a single push to
`master` starts two production deployments aimed at the same alias — Vercel's, built from its own
checkout, and the workflow's, carrying the gated artifact. Whichever finishes last wins. Neither
system reports the collision. The only symptom is the live site intermittently being the wrong build,
which is the failure mode that looks least like a failure.

There are two ways to turn the Git integration off, and the second is the one to rely on:

1. Restore `"git": { "deploymentEnabled": false }` to `web/vercel.json` — and only there. With Root
   Directory set to `web`, that is the copy Vercel reads, which is why the repository-root
   `/vercel.json` that used to hold a second copy was deleted rather than kept. Treat it as defence
   in depth rather than the guarantee:
   [`vercel/vercel#11176`](https://github.com/vercel/vercel/issues/11176) ("deploymentEnabled option
   in vercel.json is ignored") has been open since 19 February 2024 with three independent
   confirmations, no labels and no Vercel response; the file has to exist on the branch being pushed
   for it to apply at all; and Vercel scopes it only to deployments triggered *upon commits*, saying
   nothing about dashboard, CLI or deploy-hook deployments.
2. Vercel → Project → Settings → Git → **Disconnect**. This is the guarantee.

Then verify by observation rather than by reading the setting back: push one no-op commit under
`web/` and count the deployments the project creates. One is correct. Two means the switch did not
take.

## The Root Directory inversion

This is the part most likely to be got wrong on the day, because the correct value is the *opposite*
under each model and neither wrong value produces a clear error.

| | Vercel Git integration (today) | GitHub Actions (this follow-up) |
|---|---|---|
| Root Directory | **`web`** | **unset** |
| Why | Vercel builds from the repository root otherwise, where there is no `package.json` | the workflow already runs the CLI from inside `web/` |

`vercel build` computes its work path as `join(cwd, rootDirectory)`
(`packages/cli/src/commands/build/index.ts`, read from `vercel@58.4.4`). The workflow runs the CLI
from `web/` unconditionally, so a Root Directory of `web` resolves to `web/web`, which does not
exist. Either half alone is fine; it is the combination that breaks.

It does not break the way this repository advertises, either. With `framework: "astro"` in the pulled
project settings — which the portal import writes, and which is what the live project has — the build
dies first at `Command "astro build" exited with 127`, which reads as a broken toolchain and says
nothing about paths. The workflow's "Gate the staged artifact" tripwire only fires when the pulled
settings are all null, which is to say only when the project was created with `vercel link`.

So reactivating means clearing Root Directory on the existing project, or making a second project
with the CLI.

## Reactivation, in order

The order is the content. Each step is what stops the next one from failing in a way that does not
look like a failure.

1. **Decide it is worth it.** Nothing below is reversible in one click, and the current setup works.
2. **Disconnect the repository** in Vercel → Settings → Git, and clear **Root Directory** to empty in
   Settings → Build & Deployment. Restore the `git.deploymentEnabled` block to `web/vercel.json` in
   the same pull request.
3. **Get the project id and org id**: `cd web && npx vercel@58 login && npx vercel@58 link`, then read
   `orgId` and `projectId` out of `.vercel/project.json`. That file is gitignored, so it stays local.
4. **Add all three secrets in one sitting** (Settings → Secrets and variables → Actions). A partial
   set behaves exactly like no set at all — green, with a notice, having deployed nothing. Scope
   `VERCEL_TOKEN` to whatever owns the project; on a personal account that is the username, not a
   team, and a team-scoped token fails at `vercel pull` with an authorization error that does not
   mention scope.
5. **Make the first credentialed run a `workflow_dispatch` from `master`**, not a test pull request.
   Add the secrets when no web pull request is open and no web push is imminent, then dispatch
   immediately.
6. **Verify by counting deployments**, per the interlock above. Then re-read
   `web/docs/deploy.md` — it documents the Git-integration model as of this deferral, and reactivating
   inverts most of it.

## How to tell it has become worth doing

Any one of these turns this from an improvement into a fix:

- A deploy ships an artifact that CI never gated — the gap `web-ci.yml` cannot close.
- Someone changes a Vercel dashboard setting and nobody can tell from the repository that it happened.
- The Ignored Build Step's behaviour drifts and the site stops rebuilding while every check stays
  green. `web/vercel.json`'s `ignoreCommand` is live under the current model and its `:/` pathspec
  prefixes are load-bearing: the command runs inside the Root Directory, so a bare
  `gradle.properties` would resolve to `web/gradle.properties`, match nothing, and exit 0 for every
  commit — "Build skipped", correctly, forever.
- The project needs preview deployments that Vercel's protection settings are making unreachable, and
  moving the build into Actions is simpler than fixing the protection.

## Acceptance

- A push to `master` produces exactly **one** production deployment, and it is the one carrying the
  artifact `web-deploy.yml` gated.
- A push to `dev` produces exactly one preview deployment.
- `web/docs/deploy.md`, `web/docs/launch-checklist.md` and `web/README.md` describe the model that is
  actually running, with no sentence left over from the other one.
