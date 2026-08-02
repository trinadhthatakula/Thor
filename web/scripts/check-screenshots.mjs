/**
 * Refuse to publish a production deploy that still shows placeholder frames.
 *
 * ## Why this is not just the launch checklist
 *
 * `docs/launch-checklist.md` already carries this as a manual step, with the
 * exact grep, and says of it: *"The build is green with placeholders by design;
 * this is the check that stops 'green' from meaning 'finished'."* That design is
 * right and this file does not change it — `DeviceFrame` renders a labelled
 * placeholder precisely so a page can reserve a screenshot slot before anyone has
 * captured one, instead of failing `astro build` from the day it is written.
 *
 * What the checklist cannot do is choose its moment. Vercel's production branch
 * is `dev`, so merging the PR **is** the production deploy — there is no window
 * between "merged" and "live" in which a human runs a checklist. A manual gate in
 * front of an automatic action is a gate in front of an open door.
 *
 * So: placeholders stay green everywhere they are useful — local builds, `npm run
 * build`, CI, and Vercel *preview* deploys, where seeing the unfilled slots on a
 * real URL is the point — and hard-fail on a Vercel **production** build only.
 *
 * ## The environment switch
 *
 * `VERCEL_ENV` is `production`, `preview` or `development`, and Vercel sets it on
 * every build. `CI` is no good here (true for every PR run) and neither is
 * `NODE_ENV` (`production` for any `astro build`, including a local one). Set
 * `REQUIRE_SCREENSHOTS=1` to run the strict check anywhere, which is how you
 * verify this gate without waiting for a deploy.
 *
 * ## Counting
 *
 * Counted from the built HTML rather than from `src/`, for the same reason every
 * other gate here reads `dist`: what matters is what a visitor is served, and a
 * `DeviceFrame` reached through a component or an MDX partial is invisible to a
 * grep over pages. The marker is the placeholder's own aria-label prefix.
 */
import { loadPages } from './lib/dist.mjs'
import { dirArg, emptyScanFailure, isMain, report } from './lib/report.mjs'

/**
 * `aria-label="Screenshot pending: …"`, which DeviceFrame emits once per unfilled
 * frame. The visible `<span>` says the same thing, but the aria-label is the one
 * that carries the alt text, so it names *which* screenshot is missing.
 */
const PLACEHOLDER = /aria-label="Screenshot pending:\s*([^"]*)"/g

/** True when this build's output is what visitors will actually be served. */
export function isProductionDeploy(env = process.env) {
  if (env.REQUIRE_SCREENSHOTS === '1') return true
  return env.VERCEL_ENV === 'production'
}

export function runScreenshotCheck(dir, env = process.env) {
  const pages = loadPages(dir)
  const counts = { pages: pages.length, placeholders: 0 }
  if (pages.length === 0) {
    return { failures: [emptyScanFailure(dir)], counts, strict: isProductionDeploy(env) }
  }

  const pending = []
  for (const page of pages) {
    for (const match of page.html.matchAll(PLACEHOLDER)) {
      pending.push({ rel: page.rel, alt: match[1] })
    }
  }
  counts.placeholders = pending.length

  const strict = isProductionDeploy(env)
  if (!strict) return { failures: [], counts, strict }

  const failures = pending.map(({ rel, alt }) => ({
    where: rel,
    what: `unfilled screenshot frame: "${alt}"`,
    why:
      'This is a production deploy and this frame would ship as a dashed box reading ' +
      '"Screenshot pending". Capture it per web/docs/screenshot-checklist.md, commit it under ' +
      'web/src/assets/screenshots/, and pass it to the DeviceFrame as `src`. Placeholders are ' +
      'deliberate and stay green on local builds, in CI and on preview deploys — this check only ' +
      'refuses the one build a visitor would see.',
  }))

  return { failures, counts, strict }
}

if (isMain(import.meta.url)) {
  const dir = dirArg()
  const { failures, counts, strict } = runScreenshotCheck(dir)
  // The count is printed either way. A "0 placeholders" line is what tells a
  // reader of a preview build log that the screenshots have landed, and a
  // non-zero one is the reminder that the production deploy will refuse.
  process.exit(
    report(
      'check-screenshots',
      [
        ['pages checked', counts.pages],
        [strict ? 'placeholders (strict: production)' : 'placeholders (advisory)', counts.placeholders],
      ],
      failures,
    ),
  )
}
