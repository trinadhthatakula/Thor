/**
 * The matching engine behind `check-claims.mjs`, and the shape validation that
 * keeps a rule from being silently inert.
 *
 * Split out from the CLI so the fixtures, the allowlist tests and the meta-test
 * all drive the same code the deploy does, and so a rule can be evaluated
 * against a plain string in a unit test without a directory on disk.
 */
import { flatten, normalise, toSentences } from './text.mjs'
import { matchGlob } from './dist.mjs'

/**
 * @typedef {Object} ClaimRule
 * @property {string} id
 * @property {'forbid'|'require'} kind
 * @property {string} appliesTo          glob over dist-relative HTML paths
 * @property {RegExp[]} [patterns]       forbid: any match on a sentence is a violation
 * @property {RegExp} [unless]           forbid: exemption, tested near the match
 * @property {RegExp[]} [topic]          require: any match turns the rule on for a page
 * @property {RegExp[]} [correction]     require: at least one must match the page
 * @property {string[]} [allow]          exact normalised sentences that are exempt
 * @property {string} rationale
 * @property {string} source
 */

/**
 * How much text before a match the `unless` clause sees.
 *
 * Not the whole sentence, on purpose: a correction of one of these claims often
 * contains the claim itself in negated or attributed form, and a whole-sentence
 * exemption would let an unrelated "not" three clauses away switch the rule off
 * for the clause that actually makes the claim.
 */
const UNLESS_LOOKBEHIND = 60

/**
 * Reject a rule that cannot fire.
 *
 * A `forbid` with no patterns and a `require` with no correction both exit 0
 * forever and look exactly like a passing gate. This runs before any page is
 * read, so the failure is loud and immediate rather than never.
 */
export function validateRules(rules) {
  const problems = []
  const seen = new Set()

  for (const rule of rules) {
    const at = 'rule ' + (rule.id ?? '(no id)')
    if (!rule.id) problems.push(at + ': missing id')
    else if (seen.has(rule.id)) problems.push(at + ': duplicate id')
    else seen.add(rule.id)

    if (!rule.appliesTo) problems.push(at + ': missing appliesTo')
    if (!rule.rationale) problems.push(at + ': missing rationale')
    if (!rule.source) problems.push(at + ': missing source')

    if (rule.kind === 'forbid') {
      if (!Array.isArray(rule.patterns) || rule.patterns.length === 0) {
        problems.push(at + ': kind "forbid" with no patterns can never fire')
      }
    } else if (rule.kind === 'require') {
      if (!Array.isArray(rule.topic) || rule.topic.length === 0) {
        problems.push(at + ': kind "require" with no topic can never fire')
      }
      if (!Array.isArray(rule.correction) || rule.correction.length === 0) {
        problems.push(at + ': kind "require" with no correction would fail every page it touches')
      }
    } else {
      problems.push(at + ': kind must be "forbid" or "require", got ' + JSON.stringify(rule.kind))
    }

    // A line number in `source` will be wrong within a release and reads as
    // verified until someone checks. One gateway symbol moved about 180 lines
    // in a single PR, which is how this check came to exist.
    if (/\.(?:kts?|ts|mjs|xml)\s*:\s*\d+/.test(rule.source ?? '')) {
      problems.push(at + ': source cites a line number; cite the symbol instead')
    }
  }

  if (problems.length > 0) {
    throw new Error('Claim rules are malformed:\n  ' + problems.join('\n  '))
  }
  return rules
}

/** True when `unless` matches the region around a hit. */
function exempted(rule, sentence, index, matched) {
  if (!rule.unless) return false
  const start = Math.max(0, index - UNLESS_LOOKBEHIND)
  return rule.unless.test(sentence.slice(start, index) + matched)
}

/**
 * Evaluate every rule that applies to one page.
 *
 * @param page `{ rel, text }` — `text` is the page's extracted prose, unnormalised
 * @param rules the blocklist
 * @param used  `Map<ruleId, Set<entry>>` the caller shares across pages, so an
 *   allowlist entry used on any page counts as used
 */
export function evaluatePage(page, rules, used = new Map()) {
  const violations = []
  const sentences = toSentences(page.text)
  const whole = flatten(page.text)
  let applied = 0

  const markUsed = (ruleId, entry) => {
    if (!used.has(ruleId)) used.set(ruleId, new Set())
    used.get(ruleId).add(entry)
  }

  for (const rule of rules) {
    if (!matchGlob(rule.appliesTo, page.rel)) continue
    applied++
    const allow = new Set((rule.allow ?? []).map((entry) => normalise(entry)))

    if (rule.kind === 'forbid') {
      for (const sentence of sentences) {
        for (const pattern of rule.patterns) {
          // No /g anywhere in the blocklist: these RegExp objects are shared
          // module state, and a global one would carry `lastIndex` from one
          // sentence into the next and start skipping matches.
          const hit = sentence.match(pattern)
          if (!hit) continue
          if (exempted(rule, sentence, hit.index, hit[0])) continue
          if (allow.has(sentence)) {
            markUsed(rule.id, sentence)
            continue
          }
          violations.push({
            ruleId: rule.id,
            page: page.rel,
            kind: 'forbid',
            matched: hit[0],
            sentence,
            rationale: rule.rationale,
            source: rule.source,
          })
          break // One violation per sentence per rule; the rest is noise.
        }
      }
      continue
    }

    // require: the topic turns the rule on for this page, the correction turns
    // it off again. Both are matched against the whole page, not a sentence —
    // the correction usually lives in a different paragraph from the trigger.
    const triggered = rule.topic.find((topic) => topic.test(whole))
    if (!triggered) continue
    if (rule.correction.some((correction) => correction.test(whole))) continue
    if (allow.has(page.rel)) {
      markUsed(rule.id, page.rel)
      continue
    }
    violations.push({
      ruleId: rule.id,
      page: page.rel,
      kind: 'require',
      matched: whole.match(triggered)?.[0] ?? '',
      sentence: '',
      rationale: rule.rationale,
      source: rule.source,
    })
  }

  return { violations, applied }
}

/**
 * Allowlist entries that never matched anything.
 *
 * These are a failure, not a warning. An exemption outliving the sentence it
 * was written for is how a blocklist rots into a list of historical opinions:
 * the copy gets rewritten, the entry stops applying, and the next person to
 * reintroduce the claim sails past a rule everyone believes is on.
 *
 * A `forbid` rule allowlists a **sentence**, normalised so the entry can be
 * written with the straight quotes on the author's keyboard rather than the
 * curly ones smartypants emits. A `require` rule allowlists a **page path**.
 */
export function unusedAllowEntries(rules, used) {
  const unused = []
  for (const rule of rules) {
    const seen = used.get(rule.id) ?? new Set()
    for (const entry of rule.allow ?? []) {
      const key = rule.kind === 'require' ? entry : normalise(entry)
      if (!seen.has(key)) unused.push({ ruleId: rule.id, entry })
    }
  }
  return unused
}

/** Total allowlist entries declared, for the counts line. */
export function countAllowEntries(rules) {
  return rules.reduce((total, rule) => total + (rule.allow?.length ?? 0), 0)
}
