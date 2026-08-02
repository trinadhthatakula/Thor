/**
 * axe-core over jsdom. **CI only — deliberately not in the build chain.**
 *
 * It is slower than the other three by an order of magnitude and, unlike them, a
 * finding here is occasionally a judgement call rather than a defect. A gate
 * that sometimes needs a human to overrule it does not belong on the path
 * between a merge and a deploy.
 *
 * ## The explicit `runOnly` list, and why `color-contrast` is off
 *
 * jsdom has no layout engine and no CSSOM cascade. It cannot compute the
 * rendered colour of anything, so axe's `color-contrast` rule can only ever
 * return **"incomplete"** — never a pass, never a fail. A rule that can only
 * return "incomplete" is worse than no rule: it produces output that looks like
 * analysis, and the noise trains everyone to skim past the section that would
 * have held a real finding.
 *
 * Contrast is covered properly, by arithmetic over the token values themselves,
 * in `web/src/lib/tokens/contrast.test.ts`. That test knows the actual hex pairs
 * the site uses — including the two the spec fixes at values that cannot pass AA
 * for normal text — which is something no DOM-walking tool could work out from a
 * page that has never been laid out.
 *
 * Everything else in the list below is structural: decidable from the markup
 * alone, which is exactly what jsdom can answer honestly.
 */
import { JSDOM } from 'jsdom'
import axe from 'axe-core'
import { loadPages } from './lib/dist.mjs'
import { dirArg, emptyScanFailure, isMain, report } from './lib/report.mjs'

/**
 * Rules that are decidable without layout. Named one by one rather than run as a
 * tag set: axe adds rules between minor versions, and a tag-based run would
 * silently start reporting a layout-dependent rule the day it ships.
 */
export const RUN_ONLY = [
  'area-alt',
  'aria-allowed-attr',
  'aria-allowed-role',
  'aria-hidden-body',
  'aria-hidden-focus',
  'aria-required-attr',
  'aria-required-children',
  'aria-required-parent',
  'aria-roles',
  'aria-valid-attr',
  'aria-valid-attr-value',
  'button-name',
  'definition-list',
  'dlitem',
  'document-title',
  'duplicate-id-aria',
  'empty-heading',
  'form-field-multiple-labels',
  'frame-title',
  'heading-order',
  'html-has-lang',
  'html-lang-valid',
  'image-alt',
  'input-image-alt',
  'label',
  'landmark-one-main',
  'link-name',
  'list',
  'listitem',
  'meta-viewport',
  'nested-interactive',
  'page-has-heading-one',
  'role-img-alt',
  'select-name',
  'svg-img-alt',
  'td-headers-attr',
  'th-has-data-cells',
  'valid-lang',
]

/** Run axe against one page's HTML and return its violations. */
export async function auditHtml(html, { runOnly = RUN_ONLY } = {}) {
  // `outside-only` gives us `window.eval`, which is how axe-core gets injected,
  // without executing anything the page itself ships. The theme toggle is the
  // only script on the site and running it would change nothing axe inspects.
  const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true })
  try {
    dom.window.eval(axe.source)
    const results = await dom.window.axe.run(dom.window.document, {
      runOnly: { type: 'rule', values: runOnly },
      // Belt and braces: even if `color-contrast` somehow arrived through the
      // list above, it stays off. See the module comment.
      rules: { 'color-contrast': { enabled: false } },
      resultTypes: ['violations'],
    })
    return results.violations
  } finally {
    dom.window.close()
  }
}

export async function runA11yCheck(dir, options = {}) {
  const pages = loadPages(dir)
  const counts = { pages: pages.length, rules: (options.runOnly ?? RUN_ONLY).length, nodes: 0 }
  if (pages.length === 0) {
    return { failures: [emptyScanFailure(dir)], counts }
  }

  const failures = []
  for (const page of pages) {
    for (const violation of await auditHtml(page.html, options)) {
      counts.nodes += violation.nodes.length
      failures.push({
        where: page.rel,
        what: `[${violation.id}] ${violation.help}`,
        why:
          violation.nodes
            .slice(0, 5)
            .map((node) => node.html)
            .join('\n') +
          '\n' +
          violation.helpUrl,
      })
    }
  }

  return { failures, counts }
}

if (isMain(import.meta.url)) {
  const dir = dirArg()
  const { failures, counts } = await runA11yCheck(dir)
  process.exit(
    report(
      'check-a11y',
      [
        ['pages audited', counts.pages],
        ['axe rules enabled (color-contrast deliberately excluded)', counts.rules],
        ['failing nodes', counts.nodes],
      ],
      failures,
    ),
  )
}
