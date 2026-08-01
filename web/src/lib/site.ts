/**
 * Every route and outbound link the chrome knows about, in one place.
 *
 * The nav and the footer both read from here so a renamed route cannot be
 * correct in one and stale in the other — the internal link checker would catch
 * that eventually, but only after the wrong URL had already been written twice.
 */

export const SITE = {
  /** Must match `site` in astro.config.mjs; the link checker uses it to tell internal from external. */
  origin: 'https://thor.trinadhthatakula.com',
  name: 'Thor',
  /** Used as the default <title> suffix and in the OG card. */
  tagline: 'Turn off the apps your phone will not let you uninstall.',
  repo: 'https://github.com/trinadhthatakula/Thor',
} as const

export interface NavLink {
  href: string
  label: string
  /** Shown in the footer only. Keeps the header to the five routes a visitor navigates by. */
  footerOnly?: boolean
}

/**
 * Route order is the reading order, not the site map order: a first-time visitor
 * goes home → features → download, and only reaches the policies from a link
 * inside a page. `/build-an-extension` is footer-only on purpose — §3.3 of the
 * spec keeps it linked rather than featured, because a header slot would spend
 * prime space on the handful of developers who need it.
 */
export const ROUTES: NavLink[] = [
  { href: '/', label: 'Home' },
  { href: '/features', label: 'Features' },
  { href: '/download', label: 'Download' },
  { href: '/faq', label: 'FAQ' },
  { href: '/privacy', label: 'Privacy', footerOnly: true },
  { href: '/extensions-policy', label: 'Extensions policy', footerOnly: true },
  { href: '/build-an-extension', label: 'Build an extension', footerOnly: true },
]

export const HEADER_LINKS = ROUTES.filter((r) => !r.footerOnly)

export interface ExternalLink {
  href: string
  label: string
}

export const PROJECT_LINKS: ExternalLink[] = [
  { href: SITE.repo, label: 'Source on GitHub' },
  { href: `${SITE.repo}/issues`, label: 'Issue tracker' },
  { href: `${SITE.repo}/releases`, label: 'Releases' },
  { href: 'https://t.me/thor_app_updates', label: 'Telegram' },
]

/**
 * All five routes from .github/FUNDING.yml. The drafts' footer line lists four
 * and drops PayPal, which is the `custom:` entry — easy to miss because it is
 * the only one that is a bare URL rather than a username.
 */
export const FUNDING_LINKS: ExternalLink[] = [
  { href: 'https://github.com/sponsors/trinadhthatakula', label: 'GitHub Sponsors' },
  { href: 'https://www.patreon.com/trinadh', label: 'Patreon' },
  { href: 'https://ko-fi.com/trinadh', label: 'Ko-fi' },
  { href: 'https://www.buymeacoffee.com/trinadh', label: 'Buy Me a Coffee' },
  { href: 'https://www.paypal.me/trinadhthatakula', label: 'PayPal' },
]

/** Canonical absolute URL for a route, for <link rel="canonical"> and og:url. */
export function canonical(pathname: string): string {
  // trailingSlash is 'never', so '/faq/' and '/faq' must not produce two canonicals.
  const clean = pathname !== '/' && pathname.endsWith('/') ? pathname.slice(0, -1) : pathname
  return new URL(clean, SITE.origin).href
}
