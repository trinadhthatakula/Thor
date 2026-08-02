/**
 * Enforce the claims blocklist against the **built** site.
 *
 * Against `dist`, not `src`, and that is the whole point. A page's copy is
 * assembled from MDX, layout components, `<Fact />` and a footer; a sentence
 * that reads as true in one MDX file can be false once the component around it
 * has added its own clause, and a `<Fact />` renders a number that no source
 * file contains. `dist` is what the reader gets, so `dist` is what gets checked.
 *
 * Three failure modes this file is shaped around, all of which exit 0 forever if
 * you get them wrong:
 *
 *  1. **Smart quotes.** MDX runs smartypants, so straight quotes and `--` never
 *     appear in the output. `lib/text.mjs` normalises before anything matches.
 *  2. **Zero pages.** Running before `astro build`, or against the wrong
 *     directory, scans nothing and passes. Scanning zero pages is a failure.
 *  3. **A rule that cannot fire.** `validateRules` rejects a `forbid` with no
 *     patterns or a `require` with no correction before any page is read, and
 *     `scripts/fixtures.test.mjs` proves every rule id both fires and clears.
 *
 * The gate is **lexical, not semantic**. It stops known phrasings from
 * recurring; it cannot tell whether new prose is true. Do not read a green run
 * as "the copy is fact-checked" — that is itself the overclaim this exists to
 * prevent.
 */
import { extractText, parseHtml } from './lib/dom.mjs'
import { loadPages } from './lib/dist.mjs'
import {
  countAllowEntries,
  evaluatePage,
  unusedAllowEntries,
  validateRules,
} from './lib/claims-engine.mjs'
import { dirArg, emptyScanFailure, isMain, report } from './lib/report.mjs'
import { claimRules } from '../src/content/claims.mjs'

export async function runClaimsCheck(dir, options = {}) {
  const rules = validateRules(options.rules ?? claimRules)
  // The fixture suite scans one page at a time, so most allowlist entries would
  // legitimately go unused; only a run over the whole site can judge that.
  const enforceAllowlistUsage = options.enforceAllowlistUsage ?? true

  const pages = loadPages(dir)
  const counts = {
    rules: rules.length,
    pages: pages.length,
    allowDeclared: countAllowEntries(rules),
    allowUsed: 0,
  }

  if (pages.length === 0) {
    return { failures: [emptyScanFailure(dir)], counts, violations: [] }
  }

  const used = new Map()
  const violations = []
  for (const page of pages) {
    const text = extractText(parseHtml(page.html))
    violations.push(...evaluatePage({ rel: page.rel, text }, rules, used).violations)
  }

  counts.allowUsed = [...used.values()].reduce((total, set) => total + set.size, 0)

  const failures = violations.map((violation) => ({
    where: violation.page,
    what:
      violation.kind === 'forbid'
        ? `[${violation.ruleId}] forbidden claim: "${violation.matched}"`
        : `[${violation.ruleId}] page discusses "${violation.matched}" without the correction`,
    why:
      (violation.kind === 'forbid'
        ? `In: ${violation.sentence}\n`
        : 'The topic trigger matched but no correction pattern did.\n') +
      `${violation.rationale}\n` +
      `Source: ${violation.source}`,
  }))

  if (enforceAllowlistUsage) {
    for (const { ruleId, entry } of unusedAllowEntries(rules, used)) {
      failures.push({
        where: 'src/content/claims.mjs',
        what: `[${ruleId}] allowlist entry matched nothing`,
        why:
          `"${entry}"\n` +
          'An exemption that no longer applies is worse than no exemption: it reads as a ' +
          'live carve-out while the rule it disables is fully in force. Delete it, or fix ' +
          'the entry to match the sentence that is actually on the page.',
      })
    }
  }

  return { failures, counts, violations }
}

if (isMain(import.meta.url)) {
  const dir = dirArg()
  const { failures, counts } = await runClaimsCheck(dir)
  process.exit(
    report(
      'check-claims',
      [
        ['rules evaluated', counts.rules],
        ['pages scanned', counts.pages],
        [`allowlist entries used (of ${counts.allowDeclared} declared)`, counts.allowUsed],
      ],
      failures,
    ),
  )
}
