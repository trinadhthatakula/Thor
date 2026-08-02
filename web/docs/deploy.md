# Deploying thor.trinadhthatakula.com

The site deploys from **GitHub Actions**, not from Vercel's Git integration.
`.github/workflows/web-deploy.yml` is the whole pipeline; there is no step that happens only inside
Vercel.

| Trigger | Target | Result |
|---|---|---|
| push to `master` touching the web paths | production | live at `thor.trinadhthatakula.com` |
| push to `dev` touching the web paths | preview | staging URL for the integrated site, in the run summary |
| pull request into `master` touching the web paths | preview, production-strict | URL commented on the PR |
| pull request into `dev` touching the web paths | preview | URL commented on the PR |
| `workflow_dispatch` from `master` | production | same as a push to `master` |
| `workflow_dispatch` from any other branch | preview | escape hatch, deploys nothing live |

## The branch model: `master` publishes, `dev` stages

Site work merges to `dev` like everything else. The live site changes only when `dev` is merged to
`master`, which is a deliberate act rather than a side effect of pushing — that is the whole reason
production is not `dev`.

Three consequences worth stating, because each is the kind of thing that is obvious once and then
forgotten:

- **A `dev` push still deploys**, as a preview. That is on purpose. A per-PR preview only ever shows
  one branch; the `dev` preview is the only URL that shows several merged site PRs *together*, which
  is the state the release merge is about to publish.
- **A pull request into `master` is built to the production standard.** The workflow sets
  `REQUIRE_SCREENSHOTS=1` when `github.base_ref` is `master`, which makes `check:screenshots` refuse a
  placeholder frame on a *preview* build. Without it, the first build ever held to the production
  standard would be the one already serving on the live domain.
- **`workflow_dispatch` needs this file on `master`**, because GitHub only offers the Run-workflow
  button for workflows on the **default branch**. That is the same condition production deploys need,
  so the two arrive together: until `web/` reaches `master` there is no production deploy and no
  manual trigger, and after it there are both.

"the web paths" is `web/**`, `gradle.properties`, `gradle/libs.versions.toml` and the workflow file
itself. The two Gradle files are in the list because `web/src/lib/repo-facts` derives the site's
version name and SDK levels from them, so a `chore(release)` commit changes what the site renders
without touching a byte under `web/`.

## Why not Vercel's Git integration

It would work, and it would be less code. The reason it is not used is that it puts the decisions
somewhere they cannot be reviewed.

Under the Git integration, four dashboard settings determine whether the site's gates run at all —
Root Directory, "Include files outside the Root Directory", Build Command, and the Ignored Build
Step. Every one of them produces **no error when wrong**: override the Build Command to the Astro
preset's `astro build` and all six checkers vanish from the deploy path silently; get the Ignored
Build Step's pathspec wrong and the site simply stops rebuilding, green forever. None of that is
visible in a diff.

Driving the CLI from Actions moves each of those into `web-deploy.yml` and `web/vercel.json`, where
they are in git and under review, and it puts a gate failure in the same place as every other CI
failure instead of on a Vercel page nobody has open.

## Create the project with the CLI, not by importing the repository

This is the first decision and it silently sets three others. It was not written down until after the
pipeline shipped, and it is the single most likely way a first deploy fails.

```sh
cd web
npx vercel@58 login
npx vercel@58 link      # answer "create a new project" when it offers
```

| | `vercel.com/new` → Import Git Repository | `cd web && vercel link` |
|---|---|---|
| Root Directory | the import UI asks, and `web` is the only sane-looking answer | unset — which is what this workflow needs |
| Git connected | **yes**, webhook installed | **no** — `web/.git/config` does not exist, so the connect prompt never fires |
| Framework in the pulled settings | `astro`, auto-detected inside `web/` | `null` |

The import flow produces the one combination that is confirmed broken. `vercel build` computes its
work path as `join(cwd, rootDirectory)`, and this workflow already runs the CLI from inside `web/`,
so Root Directory `web` resolves to `web/web`
(`packages/cli/src/commands/build/index.ts`; `vercel/vercel#16749` documents the same double-append
as a live bug). Worse, it does not fail the way this repo advertises: with `framework: "astro"` in
the pulled settings the build dies first at `Command "astro build" exited with 127`, which reads as a
broken toolchain and says nothing about paths. The "Gate the staged artifact" tripwire only fires
when the pulled settings are all null — that is, only when the project was made with the CLI.

If the project has already been imported: Settings → Git → Disconnect, and clear Root Directory back
to empty.

**Not connecting the repository is also the only guarantee that the site deploys once per commit.**
See "The one thing that must stay true" below — the `vercel.json` kill switch is real but not
dependable, and disconnection is.

## The three secrets

Repository secrets, Settings → Secrets and variables → Actions.

| Secret | Where it comes from |
|---|---|
| `VERCEL_TOKEN` | Vercel → Account Settings → Tokens → Create. **Scope must match `VERCEL_ORG_ID`.** On a personal/Hobby account the scope is your own username, not a team; picking a team scope for a personal project fails at `vercel pull` with an authorization error that does not say the scope is the problem. |
| `VERCEL_ORG_ID` | `.vercel/project.json` after running `vercel link` locally in `web/` (field `orgId`). |
| `VERCEL_PROJECT_ID` | the same file, field `projectId`. |

A fourth, `VERCEL_AUTOMATION_BYPASS_SECRET`, is optional and only enables the advisory Lighthouse
job: Vercel → Project → Settings → Deployment Protection → Protection Bypass for Automation.

**Until all three are set the workflow exits green with a notice naming what is missing.** That is
deliberate. A missing secret is a configuration state, not a regression in the site, and this file
landing before the Vercel project exists must not redden every web PR in between.

`.vercel/` is gitignored (`.gitignore:49`), so `vercel link` locally will not commit the project id.

## Vercel project settings

Far fewer matter than under the Git integration, because the build no longer happens on Vercel.

- **Root Directory — leave it UNSET.** The workflow already runs the CLI from inside `web/`, and
  `vercel build`/`vercel deploy` both resolve `join(cwd, rootDirectory)`, so a Root Directory of
  `web` becomes `web/web`. See the creation table above for why this is the setting most likely to
  be wrong, and why the tripwire that is supposed to catch it often does not.
- **"Include files outside the Root Directory"** — no longer relevant. It exists because
  `repo-facts` reads `gradle.properties` from the repo root, and under Actions the whole repository
  is checked out, so the read just works.
- **Build Command / Install Command / Output Directory** — do not set these in the dashboard.
  `web/vercel.json` sets `buildCommand`, `installCommand` and `outputDirectory`, and `vercel.json`
  takes precedence over project settings. That precedence is what keeps a dashboard edit from
  silently removing the gate chain.
- **Production Branch — it does nothing here, and there is no safe value for it.** Which deploy is
  production is decided by `web-deploy.yml` (`--prod` only for a `master` push), not by Vercel. The
  setting only becomes live if the repository is connected, and then the *default* is the dangerous
  one: Vercel defaults to `main` if it exists, otherwise `master`, and `master` is now this
  workflow's production branch too. Do not look for a setting that makes a connected project safe —
  there isn't one. Leave the repository disconnected; see the section below.
- **Deployment Protection** — a team-level default can protect preview deployments. That does not
  break the deploy, but the URL commented on a PR then returns 401 to anyone outside the team, with
  no notice explaining why.
- **Domain** — add `thor.trinadhthatakula.com` **after** the first production deployment exists, not
  before. Vercel applies a newly added domain to the project's latest *production* deployment; add
  it to an empty project and the hostname sits attached to nothing. See "Domain and certificate" in
  `launch-checklist.md` for what that failure looks like, because it does not look like an error.

## The one thing that must stay true

**The Vercel project must not be connected to the GitHub repository.** That is the guarantee. The
`vercel.json` kill switch below is defence in depth, and it is worth having, but it is not the thing
being relied on.

If the project *is* connected while `web-deploy.yml` exists, two things go wrong:

- every commit deploys, Android-only ones included, because the path filter lives in a file that may
  not be read (below);
- a push to `master` produces **two production deploys racing for the same alias**. Vercel's default
  production branch is `master`, which is also this workflow's, so the release merge fires both a
  Git-sourced Vercel build — started from the repository root, a Gradle project with no
  `package.json` — and this workflow's gated one. Whichever finishes last wins. Nothing in either
  system reports the collision; the only symptom is the live site sometimes being the wrong thing.

The kill switch is:

```json
"git": { "deploymentEnabled": false }
```

It is in **two** files on purpose. `web/vercel.json` is what the CLI reads. `/vercel.json` at the
repository root exists only for this flag, because **Vercel does not document which `vercel.json`
its Git integration reads for `git.deploymentEnabled`.** The setting is evaluated at webhook time,
before any build container exists, so the CLI's `join(cwd, rootDirectory)` rule verified in source
does not transfer to it. With Root Directory unset — which is what this pipeline requires — the
repo-root copy is the one that is plausibly read, and the `web/` copy plausibly is not. Two copies
cost nothing and remove the guess. Do not delete either.

Even correctly placed, the flag is not a dependable kill switch: `vercel/vercel#11176`
("deploymentEnabled option in vercel.json is ignored") has been open since November 2024 with
independent confirmations, and the file must exist on the branch being pushed for it to apply at
all. Set it, then **verify by observation** — push one no-op commit under `web/` and count the
deployments the Vercel project creates. If there is more than one, disconnect the repository.

`ignoreCommand` is still in `web/vercel.json`. It was previously described here as inert. That is
not provable: `vercel deploy` sends `projectSettings.commandForIgnoringBuildStep` derived from
`vercel.json` on the same payload as `--prebuilt`, so the CLI actively uploads an ignore command on
prebuilt deploys, and Vercel has shipped — then rolled back — a build where the Ignored Build Step
cancelled `--prebuilt` deployments. If that happens again the job goes **red**, not silently green:
`vercel deploy` prints "The deployment has been canceled." and exits 1, under `set -euo pipefail`.
**Never add `--no-wait` to the deploy step** — that single flag is what would convert this from loud
to silently green. Leave the dashboard's Ignored Build Step on "Automatic".

## What the pipeline checks before it uploads

**Make the first credentialed run a `workflow_dispatch` from `master`, not a test pull request.**
Vercel documents that "the first deployment of a new project is always a production deployment",
including when the CLI is run without `--prod`. Whatever runs first after the three secrets land is
therefore the production deployment, and the domain will later attach to it — so it should be a run
that *knew* it was production. A dispatch from `master` builds with `VERCEL_ENV=production`,
`--prod`, and the strict screenshot gate.

The trap is the natural instinct: add the secrets, then push a test PR to see whether it works. That
run is built with `VERCEL_ENV=preview` and preview environment variables, becomes the production
deployment anyway, and the PR comment calls it a preview. Add the secrets when no web pull request is
open and no web push is about to land, and dispatch immediately.

`vercel build` runs `npm run build`, which is the gate chain: `check:types`, `astro build`,
`check:links`, `check:claims`, `check:markup`, `check:sitemap`, `check:screenshots`. `npm test` runs
before it.

Then every checker runs **again**, against `.vercel/output/static` — the directory that is actually
uploaded — rather than against `dist`, which is only the intermediate the Vercel builder consumes.
This is nearly free and it closes the gap between "the build was gated" and "the artifact that
shipped was gated". It is a real assertion rather than a formality because every checker takes its
directory as `argv[2]` and fails on an empty or missing scan by design; that was verified against
both an empty and a nonexistent path, so a wrong directory reports itself instead of passing
vacuously.

`VERCEL_ENV` is set explicitly by the workflow on both the build and the re-gate. This matters:
`check:screenshots` only *fails* on a placeholder frame when `VERCEL_ENV=production`, and under
`vercel build` the build runs outside Vercel's infrastructure, where Vercel's documentation warns
that System Environment Variables are not injected. Letting `--prod` imply it would have quietly
downgraded that gate to advisory.

`REQUIRE_SCREENSHOTS` is set on the same two steps and is the other half of that switch — the check
is strict when `VERCEL_ENV=production` **or** `REQUIRE_SCREENSHOTS=1`. The workflow sets it to `1` on
a pull request whose base is `master`, so the release PR's preview is held to the production standard
before the merge that publishes, and it is empty everywhere else, because work in progress is allowed
to carry a placeholder.

## Lighthouse

The advisory Lighthouse run is now a second job in `web-deploy.yml`. It used to be
`web-lighthouse.yml`, which discovered the preview URL by polling the GitHub Deployments API for up
to ten minutes.

That only ever worked because **Vercel's Git integration** writes those deployment records. Vercel
documents that CLI deployments do not carry full `gitSource` metadata, so under this pipeline the
poll would have found nothing — and it would not have failed, it would have skipped with a notice on
every PR, forever. The job that does the deploying now hands the URL over directly, so the polling
loop is deleted rather than left quietly broken.

It remains advisory and structurally unable to become a gate: every assertion in `lighthouserc.json`
is `warn`, the job is `continue-on-error`, and the workflow is path-filtered.

## Rolling back

Actions is not in the loop for a rollback — Vercel keeps every deployment.

```sh
vercel rollback              # to the previous production deployment
vercel promote <deploy-url>  # to a specific one
```

or Vercel → Project → Deployments → "…" → Promote to Production. Rolling back does not revert git,
so follow it with a revert commit on `master` or the next `master` push will put the bad build
straight back.

## Path filters and required checks

`web-deploy.yml`, like `web-ci.yml`, is path-filtered and **must never be added to a branch ruleset**.
GitHub reports a path-skipped required check as "Expected — Waiting for status" forever, so an
Android-only PR would sit unmergeable. That is why `pr-ci.yml` carries no path filter at all —
`build-and-test` is the check the `dev` and `master` rulesets require.
