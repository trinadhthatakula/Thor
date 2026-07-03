# Thor — GitHub Setup Strategy: Current vs. Projects vs. Organization

**Date:** 2026-07-02
**Author:** decision aid prepared for @trinadhthatakula
**Scope:** How to organize/sort issues and govern the repo, comparing three paths:
**(A)** keep the current personal-account setup, **(B)** add a GitHub Project on top of it, **(C)** transfer the repo to a free Organization.

> **Verified facts this doc is built on**
> - `trinadhthatakula/Thor` owner type = **User** (personal account), public, GPL-3.0.
> - **Issue Types** (the Bug/Feature/Task structured field) are **organization-only** — confirmed: issues here expose no `type` field, and both the org and user issue-types API endpoints return 404.
> - **Projects (v2)** are available on **both** personal and org accounts (free).
> - So the real "you must be an org" differentiator is **Issue Types + org-level governance/teams**, *not* Projects.

---

## 0. TL;DR / Recommendation

| | **A. Current (status quo)** | **B. Add a Project** | **C. Transfer to an Org** |
|---|---|---|---|
| Issue **sorting** power | Labels + search only | Strong (custom fields, views, status lifecycle) | Strongest (Issue Types *repo-visible* + Projects + org rulesets) |
| Effort to adopt | none | **~1–2 hrs, reversible** | **half-day migration + risk + ongoing admin** |
| Risk | none | ~none (delete the project to undo) | **Real** (secrets/Actions/app re-auth, URL change, ruleset/role re-eval) |
| Cost ($) | free | free | free (public repos) |
| Scales to more repos/contributors | poorly | moderately (cross-repo project) | **best** (teams, org rulesets, shared secrets) |
| Reversibility | — | trivial | possible but high-friction |

**Recommendation:** **Do B now.** It solves the actual pain (tracking the "fixed-but-awaiting-stable" issue lifecycle) with near-zero risk and no migration, and it's fully reversible. **Defer C** until there's a concrete trigger — a second active contributor, a second repo you want governed together (e.g. `extension-api`, `asgard`), or a genuine need for repo-visible Issue Types. B and C are **not** mutually exclusive: if you later move to an org, you keep the Project and *add* Issue Types.

---

## 1. Context: what problem are we actually solving?

The recurring friction in day-to-day maintenance (visible across #159, #197, #207) is **issue lifecycle tracking**, not classification. Your real flow is:

```text
report → triage → confirm → fix on dev → ship pre-release → ask reporter to test → close on stable release
```

Today that "fixed but awaiting the stable build" state lives in your head and in issue comments. Labels classify *what* an issue is (`bug`, `area:installer`, `priority:high`) but don't model *where it is in that pipeline*. That's the gap. Keep this lens while reading — most of the value below comes from modeling **status**, and only secondarily from a **type** field.

---

## 2. Option A — Current setup (personal account + labels)

### What you have today (already solid)
- **Branches & release flow:** `master` (default; auto-ships to Play internal via `dev-check.yml`), `dev` (integration), `production` (closed-track). Rulesets on all three (require PR + `build-and-test`, block force-push/deletion, 0 approvals for solo, admin bypass).
- **Security:** secret scanning + push protection, Dependabot security + version updates (gradle + github-actions), CodeQL, vulnerability alerts.
- **CI/CD:** `pr-ci.yml` (build+test gate), `codeql.yml`, SHA-pinned release workflows with concurrency guards, labeler, stale bot.
- **Community health:** SECURITY.md, CODE_OF_CONDUCT.md, CONTRIBUTING.md, issue forms (bug/feature) + config, PR template, CODEOWNERS, FUNDING.
- **Label taxonomy:** `area:*`, `mode:*`, `priority:*`, plus `bug`/`enhancement`/`question`/etc.

### Pros
- **Zero overhead / already working.** Nothing to learn or maintain beyond what you do now.
- Labels are **repo-visible** — anyone browsing issues sees them; filterable via `label:`.
- Fully within a personal account: simplest permission model (just you).

### Cons
- **No status pipeline.** You can't see "everything fixed and awaiting the stable release" at a glance.
- **Labels are a flat, many-per-issue space.** Using labels for *type* (`bug` vs `enhancement`) works but there's no single enforced "type" axis, no grouping, no board.
- **No planning surface** — no roadmap, no iterations, no cross-repo view.
- Sorting caps out at GitHub's issue-list search (`is:open label:bug sort:updated`) — no group-by, no saved custom views beyond the search box.

### Opportunity cost of staying here
You keep spending small amounts of attention manually tracking lifecycle state and re-deriving "what's ready to release." Low per-instance, but it compounds and is exactly the kind of thing that slips when you're busy (e.g. an issue that's fixed but never gets closed on release).

---

## 3. Option B — Add a GitHub Project (v2) on the current account

A Project is a planning layer **on top of** your existing issues/PRs. It changes nothing about the issues themselves; it adds fields/views around them. **Available free on your personal account today** — no migration.

### What it adds
- **Custom fields** the repo can't have: **Status** (single-select), **Type** (Bug/Feature/Task — the Issue-Types stand-in), **Priority**, **Target** release, **Iteration** (date ranges).
- **A Status lifecycle that mirrors your flow:** `Triage → Confirmed → Fixed (on dev) → In pre-release → Released`. One column each — the missing piece from Option A.
- **Multiple views of the same items:** Board (Kanban triage), Table (spreadsheet bulk-edit), Roadmap (release timeline). Each filters/groups/sorts independently.
- **Group / sort / filter by any field** — group the board by `area`, sort the table by `priority`, filter the roadmap by target version.
- **Built-in automation (no code):** auto-add new issues → `Triage`; auto-set `Done`/`Released` when an issue closes; auto-archive. Good for a solo maintainer.
- **Draft issues** (capture ideas before filing) and **cross-repo** items (pull Thor + `extension-api` + `asgard` into one board — labels can't span repos).

### Pros
- **Directly solves the real problem** (status pipeline) — see §1.
- **Low effort, ~1–2 hrs** to set up fields + automations + seed open issues.
- **Fully reversible** — delete the project, issues are untouched.
- **No org, no migration, no risk** to secrets/Actions/release pipeline.
- Composes later with an org if you migrate.

### Cons / limits
- **Project-scoped, not repo-visible.** The Status/Type fields show *inside the project only* — someone reading the raw issues list won't see them. (Mitigation: keep `area:/priority:` as **labels** for repo-facing classification; use the project for workflow/status.)
- **Not true Issue Types.** No `type:Bug` search in the issues list, no issue-form auto-typing, no org-wide consistency.
- **Another surface to keep in sync.** Automation covers add/close, but field upkeep (setting Type/Target) is manual unless you wire a GitHub Action.
- Slight duplication with labels if you're not deliberate about which tool owns what.

### Opportunity cost
Minimal and reversible. The only real cost is the discipline to actually *use* the board; if you set it up and ignore it, it's dead weight. Because it's free and undo-able, the downside is tiny relative to the upside.

---

## 4. Option C — Transfer the repo to a (free) Organization

Create a free org (e.g. `valhalla` / `valhalla-apps`) and **transfer** Thor into it. This is the only way to unlock **Issue Types** and org-level governance.

### What it unlocks (that A/B can't)
- **Issue Types** — repo-visible, org-wide, single-select type on the issue itself; filterable via `type:Bug`; settable in issue forms so new issues arrive pre-typed. This is the "real" version of the Type field, strictly better than a Project field for repo-facing classification.
- **Org-level rulesets** — govern *all* repos with one policy; layer on top of repo rulesets.
- **Teams & granular roles** — add contributors/maintainers with scoped permissions; CODEOWNERS can reference teams.
- **Org-wide Dependabot / security / secret policies**, org secrets shared across repos, org-level Actions allow-lists.
- **Cleaner multi-repo home** for the Valhalla ecosystem (`extension-api`, `asgard`, Thor) under one owner.

### Pros
- Everything in A **and** B **and** the org-only features above.
- **Best long-term scalability** — the right structure if you add contributors or repos.
- Repo-visible **Issue Types** (the feature you originally asked about).
- Free for public repos.

### Cons / risks (this is the heavy option — read carefully)
Transferring a repo is mostly safe (issues, PRs, stars, watchers, wiki move; **redirects** are created from the old URL, so existing clones/links keep working), **but** several things need hands-on verification because Thor has a **live Play Store publishing pipeline**:

1. **Actions secrets & variables** — `ANDROID_KEYSTORE_BASE64`, `PLAY_STORE_JSON_KEY`, `KEY_ALIAS`, `KEY_PASSWORD`, `KEYSTORE_PASSWORD`, `TELEGRAM_TOKEN`, etc. **You must confirm these survive the transfer (and that Actions is still enabled) before the next release run**, or a deploy will fail. Budget time to re-add any that don't carry over.
2. **Third-party GitHub Apps** (CodeRabbit, Gemini, Dependabot config) are installed **per account/org** — after transfer you'll likely need to (re)install/authorize them on the org. (Note: Gemini's consumer code review is being **sunset July 2026** anyway.)
3. **Rulesets & bypass actors** — repo rulesets move with the repo, but bypass actors keyed to `RepositoryRole` (your admin bypass) are re-evaluated under the org's role model (org owners/members/teams). Re-check that you still have the intended bypass and that no org ruleset unexpectedly blocks your solo flow.
4. **CODEOWNERS** — code-owner review requires owners to have access; under an org that's usually via team membership.
5. **Canonical URL changes** — redirects help, but the "true" URL becomes `github.com/<org>/Thor`. Update README badges, store listings, and any hardcoded links at your leisure.
6. **More admin surface** — org settings, member management, billing entity (still free for public) — a permanent, if small, ongoing tax.

### Opportunity cost
- **Up-front:** a half-day of careful migration + verification, during a window where you're not mid-release.
- **Ongoing:** low but non-zero org administration.
- **Reversibility:** you *can* transfer back to a personal account, but flip-flopping is disruptive (more redirects, more app re-auth). Treat C as a **one-way-ish** decision — do it when you mean it.
- **Cost of doing it too early:** you take on migration risk + admin overhead before you have contributors/repos that justify it. **Cost of doing it too late:** you accumulate issue history/links under the personal URL, making the eventual move slightly heavier (still fine — redirects hold).

---

## 5. Feature comparison matrix

| Capability | A. Current | B. + Project | C. Org |
|---|:--:|:--:|:--:|
| Labels (`area:/mode:/priority:`) | ✅ | ✅ | ✅ |
| Issue-list search / filter | ✅ | ✅ | ✅ |
| **Status lifecycle** (triage→released) | ❌ | ✅ | ✅ |
| Board / Table / Roadmap views | ❌ | ✅ | ✅ |
| Group / sort by custom field | ❌ | ✅ | ✅ |
| **Repo-visible "Type" field** (`type:Bug`) | ❌ | ❌ (project-scoped only) | ✅ **Issue Types** |
| Type set via issue form | ❌ | ❌ | ✅ |
| Cross-repo planning board | ❌ | ✅ | ✅ |
| Draft issues / iterations / roadmap | ❌ | ✅ | ✅ |
| Org-level rulesets across repos | ❌ | ❌ | ✅ |
| Teams / granular roles | ❌ | ❌ | ✅ |
| Org secrets shared across repos | ❌ | ❌ | ✅ |
| Migration risk | none | none | **moderate** |
| Ongoing admin overhead | none | low | low–moderate |
| Reversible | — | trivial | high-friction |

---

## 6. Decision guide

- **You want issue sorting + a status pipeline, now, with no risk** → **B**.
- **You specifically need repo-visible Issue Types** (`type:` in the issues list / issue forms) → only **C** delivers this.
- **You're about to add contributors or a second governed repo** → **C** (and still use Projects inside it).
- **You want the maximum with a staged path** → **B now → C when a trigger appears.** Nothing you do in B is wasted after a move to C.

### Suggested phased plan
1. **Today (B):** one Project — fields **Status** (Triage/Confirmed/Fixed/In pre-release/Released), **Type** (Bug/Feature/Task), **Target** (version); automations (auto-add → Triage, auto-Done on close); Board grouped by Status + a Table view. Seed the 13 open issues.
2. **Trigger for C:** first co-maintainer, or you decide to house `extension-api`/`asgard`/Thor together, or you want `type:` in the issues list. Then: create org → dry-run a checklist (secrets, Actions, apps, rulesets, badges) → transfer → verify a release run → re-point the Project (it can move to the org).

---

## 7. Bottom line

Your current setup (A) is already strong on **governance and CI/security** — the gap is purely **issue workflow visibility**, and that gap is closed by **B (a Project)** cheaply and reversibly. **C (an org)** is the right *destination* if/when Thor grows into a multi-repo or multi-contributor project, and it's the only path to repo-visible Issue Types — but its migration touches your live Play pipeline, so do it deliberately, not speculatively.

**Recommended next step:** set up the Project (B). Say the word and I'll create it with the fields, automations, and views above and seed it with your open issues.
