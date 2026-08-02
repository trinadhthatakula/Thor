# Deploying thor.trinadhthatakula.com

The site deploys from **GitHub Actions**, not from Vercel's Git integration.
`.github/workflows/web-deploy.yml` is the whole pipeline; there is no step that happens only inside
Vercel.

| Trigger | Target | Result |
|---|---|---|
| push to `dev` touching the web paths | production | live at `thor.trinadhthatakula.com` |
| pull request into `dev` or `master` touching the web paths | preview | URL commented on the PR |
| `workflow_dispatch` from `dev` | production | same as a push |
| `workflow_dispatch` from any other branch | preview | escape hatch, deploys nothing live |

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

## The three secrets

Repository secrets, Settings → Secrets and variables → Actions.

| Secret | Where it comes from |
|---|---|
| `VERCEL_TOKEN` | Vercel → Account Settings → Tokens → Create. Scope it to the team that owns the project. |
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

- **Root Directory — leave at the repository root (the default).** The workflow already runs the
  CLI from inside `web/`. Setting it to `web` as well risks resolving to `web/web`. If it is wrong,
  the "Gate the staged artifact" step fails with a message naming this cause rather than deploying
  something empty.
- **"Include files outside the Root Directory"** — no longer relevant. It exists because
  `repo-facts` reads `gradle.properties` from the repo root, and under Actions the whole repository
  is checked out, so the read just works.
- **Build Command / Install Command / Output Directory** — do not set these in the dashboard.
  `web/vercel.json` sets `buildCommand`, `installCommand` and `outputDirectory`, and `vercel.json`
  takes precedence over project settings. That precedence is what keeps a dashboard edit from
  silently removing the gate chain.
- **Production Branch** — irrelevant to this pipeline. Which deploy is production is decided by
  `web-deploy.yml` (`--prod` only for `dev`), not by Vercel. Setting it does no harm.
- **Domain** — add `thor.trinadhthatakula.com` in Vercel and let it issue the certificate. A
  production deployment picks up the domain automatically.

## The one thing that must stay true

`web/vercel.json` contains:

```json
"git": { "deploymentEnabled": false }
```

If that is removed while `web-deploy.yml` exists, **every web commit deploys twice** — once by
Vercel's Git integration and once by Actions — and the two race for the production alias. The
symptom is the site intermittently serving the older of two commits, and nothing reports it. Vercel's
own guidance names this as the most common failure of the Actions approach. The two settings are one
decision.

`ignoreCommand` is still in `vercel.json` and is currently **inert**: it is a Git-integration
feature, and the Git integration is off. It is kept as defence in depth for the case where someone
flips `deploymentEnabled` back on without reading this file, where it would at least confine the
duplicate builds to commits that actually touched the site. Do not "clean it up" on the grounds that
nothing reads it, and do not assume it is protecting anything today.

## What the pipeline checks before it uploads

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

```
vercel rollback              # to the previous production deployment
vercel promote <deploy-url>  # to a specific one
```

or Vercel → Project → Deployments → "…" → Promote to Production. Rolling back does not revert git,
so follow it with a revert commit or the next push to `dev` will put the bad build straight back.

## Path filters and required checks

`web-deploy.yml`, like `web-ci.yml`, is path-filtered and **must never be added to a branch ruleset**.
GitHub reports a path-skipped required check as "Expected — Waiting for status" forever, so an
Android-only PR would sit unmergeable. That is why `pr-ci.yml` carries no path filter at all —
`build-and-test` is the check the `dev` and `master` rulesets require.
