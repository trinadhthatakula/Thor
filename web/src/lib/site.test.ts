import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { findRepoRoot } from './repo-facts/read.ts'
import { FUNDING_LINKS, PROJECT_LINKS, SITE, canonical } from './site.ts'

/**
 * Locks the footer's outbound links to the repository's own answers.
 *
 * Nothing else can. `check-links` resolves internal paths against `dist` and
 * skips anything on another origin by design; `check-claims` reads visible prose
 * and never looks at an `href`; the scheduled `lychee` sweep runs after deploy,
 * from the default branch only — and most of these hosts answer 200 with a
 * generic page for an unknown handle, so a wrong-but-live URL reads as healthy.
 *
 * That gap shipped a real bug: the footer pointed "Telegram" at
 * `t.me/thor_app_updates`, a handle that appears nowhere else in the project's
 * history, on all eight pages, while three of this site's own pages linked
 * `t.me/thorAppDev` in their prose. The same deployment contradicted itself and
 * every gate was green.
 *
 * So these tests do not check that the links *work*. They check that the site
 * and the repository name the same accounts — which is the property that was
 * actually violated, and the only one checkable without a network.
 */
describe('outbound links agree with the repository', () => {
  const root = findRepoRoot()
  const readRepo = (rel: string) => readFileSync(join(root, rel), 'utf8')

  const href = (links: readonly { href: string; label: string }[], label: string) => {
    const found = links.find((l) => l.label === label)
    expect(found, `no outbound link labelled "${label}"`).toBeDefined()
    return found!.href
  }

  it('points Telegram at the handle the app itself opens', () => {
    // The app is the authority: these are the two places a user can reach the
    // channel from inside Thor, so the website disagreeing with them is the
    // website being wrong.
    //
    // The settings half lives in SettingsCategoryScreen.kt, not SettingsScreen.kt:
    // the one long panel was split into eight categories, and the link went with
    // the About & support one. That move landed without this test noticing, because
    // web-ci is path-filtered to web/** and the two Gradle version files — so a
    // Kotlin change that breaks a web assertion stays green until a release PR
    // bumps versionCode. Hence the loud message below rather than a silent skip.
    const inApp = [
      'app/src/main/java/com/valhalla/thor/presentation/settings/SettingsCategoryScreen.kt',
      'app/src/main/java/com/valhalla/thor/presentation/home/components/SupportCommunitySection.kt',
    ].map((rel) => {
      const match = /https:\/\/t\.me\/([A-Za-z0-9_]+)/.exec(readRepo(rel))
      expect(match, `${rel} no longer links to Telegram — update this test`).not.toBeNull()
      return match![1]
    })

    expect(new Set(inApp).size, 'the app links two different Telegram handles').toBe(1)
    expect(href(PROJECT_LINKS, 'Telegram')).toBe(`https://t.me/${inApp[0]}`)
  })

  it('agrees with the README and the issue-template chooser on that handle', () => {
    // Three independent files, none of which the website build reads. A typo in
    // any single one of them is visible here as a disagreement rather than as a
    // link nobody clicks until it matters.
    const handles = ['README.md', '.github/ISSUE_TEMPLATE/config.yml', 'shizu_store.json'].map(
      (rel) => {
        const match = /https:\/\/t\.me\/([A-Za-z0-9_]+)/.exec(readRepo(rel))
        expect(match, `${rel} no longer mentions Telegram — update this test`).not.toBeNull()
        return match![1]
      },
    )

    for (const handle of handles) {
      expect(href(PROJECT_LINKS, 'Telegram')).toBe(`https://t.me/${handle}`)
    }
  })

  it('derives every funding link from .github/FUNDING.yml, with none added or dropped', () => {
    // FUNDING.yml is GitHub's own manifest for this repository, so it is the
    // canonical list — and it stores usernames rather than URLs, which is exactly
    // why a hand-written footer drifts from it without anything noticing.
    const funding = readRepo('.github/FUNDING.yml')
    const field = (key: string) => {
      const match = new RegExp(`^${key}:\\s*(.+)$`, 'm').exec(funding)
      expect(match, `FUNDING.yml has no ${key}: entry`).not.toBeNull()
      return match![1].trim()
    }

    const custom = /^custom:\s*\[\s*"([^"]+)"\s*\]$/m.exec(funding)
    expect(custom, 'FUNDING.yml has no custom: entry').not.toBeNull()

    expect(FUNDING_LINKS.map((l) => l.href).sort()).toEqual(
      [
        `https://github.com/sponsors/${field('github')}`,
        `https://www.patreon.com/${field('patreon')}`,
        `https://ko-fi.com/${field('ko_fi')}`,
        `https://www.buymeacoffee.com/${field('buy_me_a_coffee')}`,
        custom![1],
      ].sort(),
    )
  })

  it('points at the repository the README and the issue chooser point at', () => {
    for (const rel of ['README.md', '.github/ISSUE_TEMPLATE/config.yml']) {
      expect(readRepo(rel), `${rel} disagrees about the repository URL`).toContain(SITE.repo)
    }
    // The three GitHub links are built from SITE.repo, so this pins the shape
    // rather than repeating the URL.
    expect(href(PROJECT_LINKS, 'Issue tracker')).toBe(`${SITE.repo}/issues`)
    expect(href(PROJECT_LINKS, 'Releases')).toBe(`${SITE.repo}/releases`)
  })

  it('agrees with astro.config.mjs about the site origin', () => {
    // `canonical()` and the link checker both branch on this. If it drifts from
    // the Astro config, every canonical URL on the site points somewhere else.
    const config = readFileSync(join(root, 'web', 'astro.config.mjs'), 'utf8')
    const match = /site:\s*'([^']+)'/.exec(config)
    expect(match, 'astro.config.mjs has no `site`').not.toBeNull()
    expect(match![1].replace(/\/$/, '')).toBe(SITE.origin)
    expect(canonical('/faq')).toBe(`${SITE.origin}/faq`)
  })
})
