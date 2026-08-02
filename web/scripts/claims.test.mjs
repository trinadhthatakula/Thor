/**
 * The claims gate.
 *
 * Two assertions per rule, and they are not symmetric:
 *
 *  - the `fail` fixture must produce a violation **carrying that rule's id**, so
 *    a fixture cannot pass by tripping some other rule;
 *  - the `pass` fixture must produce **no violation from any rule at all**,
 *    which is what catches a rule that over-triggers on correct copy. An
 *    over-triggering rule is not a stricter gate, it is a gate somebody deletes.
 *
 * Everything else here is about the ways a claims gate dies quietly: smart
 * quotes it can never match, zero pages scanned, a rule that cannot fire, and an
 * allowlist entry that outlives the sentence it exempted.
 */
import { describe, expect, it } from 'vitest'
import { fileURLToPath } from 'node:url'
import { runClaimsCheck } from './check-claims.mjs'
import { evaluatePage, unusedAllowEntries, validateRules } from './lib/claims-engine.mjs'
import { claimRules } from '../src/content/claims.mjs'

const fixture = (id, side) =>
  fileURLToPath(new URL(`./fixtures/claims/${id}/${side}`, import.meta.url))

/** Fixture runs scan one page, so most allowlist entries legitimately go unused. */
const scan = (dir) => runClaimsCheck(dir, { enforceAllowlistUsage: false })

describe.each(claimRules.map((rule) => rule.id))('%s', (id) => {
  it('fires on the tempting wrong phrasing', async () => {
    const { violations, counts } = await scan(fixture(id, 'fail'))
    expect(counts.pages).toBe(1)
    expect(violations.map((v) => v.ruleId)).toContain(id)
  })

  it('stays silent on the corrected phrasing', async () => {
    const { violations, counts } = await scan(fixture(id, 'pass'))
    expect(counts.pages).toBe(1)
    expect(violations).toEqual([])
  })
})

describe('the shapes that make a gate stop being a gate', () => {
  it('fails when it scanned no pages', async () => {
    const { failures, counts } = await scan(fixture('C1', 'nowhere'))
    expect(counts.pages).toBe(0)
    expect(failures).toHaveLength(1)
    expect(failures[0].what).toBe('no HTML pages found')
  })

  it('rejects a forbid rule with no patterns', () => {
    expect(() =>
      validateRules([
        { id: 'X', kind: 'forbid', appliesTo: '**', rationale: 'r', source: 's' },
      ]),
    ).toThrow(/can never fire/)
  })

  it('rejects a require rule with no correction', () => {
    expect(() =>
      validateRules([
        { id: 'X', kind: 'require', appliesTo: '**', topic: [/x/], rationale: 'r', source: 's' },
      ]),
    ).toThrow(/would fail every page/)
  })

  it('rejects a source cited by line number', () => {
    // A line citation reads as verified and drifts within a release; one gateway
    // symbol moved about 180 lines in a single PR.
    expect(() =>
      validateRules([
        {
          id: 'X',
          kind: 'forbid',
          appliesTo: '**',
          patterns: [/x/],
          rationale: 'r',
          source: 'RootSystemGateway.kt:232',
        },
      ]),
    ).toThrow(/cite the symbol/)
  })

  it('rejects a duplicate rule id, which would break the fixture pairing', () => {
    const rule = { id: 'X', kind: 'forbid', appliesTo: '**', patterns: [/x/], rationale: 'r', source: 's' }
    expect(() => validateRules([rule, { ...rule }])).toThrow(/duplicate id/)
  })

  it('accepts the live blocklist', () => {
    expect(() => validateRules(claimRules)).not.toThrow()
  })
})

describe('normalisation is load-bearing, not cosmetic', () => {
  const rules = [
    {
      id: 'T',
      kind: 'forbid',
      appliesTo: '**',
      patterns: [/\bdon't install\b/i],
      rationale: 'r',
      source: 's',
    },
  ]

  it('matches copy that smartypants has curled', async () => {
    // Written with the straight apostrophe on the author's keyboard; the page
    // carries U+2019. Without lib/text.mjs this rule matches nothing, forever,
    // and the build stays green.
    const page = { rel: 'index.html', text: 'Seriously, don’t install that build.' }
    expect(evaluatePage(page, rules).violations).toHaveLength(1)
  })

  it('and the fixture for C8 depends on exactly that', async () => {
    const { violations } = await scan(fixture('C8', 'fail'))
    expect(violations.map((v) => v.ruleId)).toContain('C8')
    // The matched text is reported normalised, which is also what a human has to
    // paste into an allowlist entry for it to ever match.
    expect(violations[0].matched).toContain("don't run any servers")
  })
})

describe('appliesTo scopes a rule to pages, and is checked against the real path', () => {
  const rules = [
    {
      id: 'S',
      kind: 'forbid',
      appliesTo: 'privacy/index.html',
      patterns: [/\bforbidden\b/i],
      rationale: 'r',
      source: 's',
    },
  ]

  it('applies on the page it names', () => {
    const page = { rel: 'privacy/index.html', text: 'A forbidden claim.' }
    expect(evaluatePage(page, rules).applied).toBe(1)
    expect(evaluatePage(page, rules).violations).toHaveLength(1)
  })

  it('does not apply elsewhere', () => {
    const page = { rel: 'faq/index.html', text: 'A forbidden claim.' }
    expect(evaluatePage(page, rules).applied).toBe(0)
    expect(evaluatePage(page, rules).violations).toEqual([])
  })
})

describe('the allowlist cannot rot', () => {
  const base = {
    id: 'A',
    kind: 'forbid',
    appliesTo: '**',
    patterns: [/\b100% offline\b/i],
    rationale: 'r',
    source: 's',
  }

  it('exempts a sentence on exact equality, written with straight quotes', () => {
    const rules = [{ ...base, allow: ['The old listing said "100% offline" and was wrong.'] }]
    const page = {
      rel: 'index.html',
      text: 'The old listing said “100% offline” and was wrong.',
    }
    const used = new Map()
    expect(evaluatePage(page, rules, used).violations).toEqual([])
    expect(unusedAllowEntries(rules, used)).toEqual([])
  })

  it('does not exempt a sentence that merely contains the allowed one', () => {
    // Equality, not substring. Substring matching would let an exemption widen
    // silently as the sentence around it is edited — the claim gets restated in
    // a longer clause and sails through a carve-out written for a shorter one.
    const rules = [{ ...base, allow: ['The old listing said "100% offline".'] }]
    const page = {
      rel: 'index.html',
      text: 'Even now the old listing said "100% offline".',
    }
    expect(evaluatePage(page, rules).violations).toHaveLength(1)
  })

  it('reports an entry that matched nothing as a failure of its own', () => {
    const rules = [{ ...base, allow: ['A sentence nobody writes any more.'] }]
    const page = { rel: 'index.html', text: 'Nothing to see.' }
    const used = new Map()
    evaluatePage(page, rules, used)
    expect(unusedAllowEntries(rules, used)).toEqual([
      { ruleId: 'A', entry: 'A sentence nobody writes any more.' },
    ])
  })

  it('surfaces that as a build failure, not a warning', async () => {
    const { failures } = await runClaimsCheck(fixture('C1', 'pass'), {
      rules: [{ ...base, allow: ['Never written.'] }],
    })
    expect(failures).toHaveLength(1)
    expect(failures[0].what).toContain('allowlist entry matched nothing')
    expect(failures[0].where).toBe('src/content/claims.mjs')
  })

  it('is empty across the live blocklist today', () => {
    // Recorded so that adding the first real exemption is a deliberate act with
    // a diff, rather than something that accumulates.
    expect(claimRules.flatMap((rule) => rule.allow ?? [])).toEqual([])
  })
})

describe('a require rule catches a paraphrase, which is the point of the shape', () => {
  it('fires on a page that raises the topic and never carries the correction', async () => {
    const { violations } = await scan(fixture('C1R', 'fail'))
    const c1r = violations.filter((v) => v.ruleId === 'C1R')
    expect(c1r).toHaveLength(1)
    expect(c1r[0].kind).toBe('require')
  })

  it('accepts a correction that lives in a different paragraph from the trigger', async () => {
    // The correction is matched against the whole page for exactly this reason:
    // nobody writes the claim and its qualification in one sentence.
    const { violations } = await scan(fixture('C1R', 'pass'))
    expect(violations).toEqual([])
  })
})

describe('counts', () => {
  it('reports rules, pages and allowlist usage on every run', async () => {
    const { counts } = await scan(fixture('C1', 'pass'))
    expect(counts.rules).toBe(claimRules.length)
    expect(counts.pages).toBe(1)
    expect(counts.allowDeclared).toBe(0)
    expect(counts.allowUsed).toBe(0)
  })
})
