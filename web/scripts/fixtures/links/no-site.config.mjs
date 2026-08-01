// An astro.config.mjs with no `site`, for the one test that proves check-links
// refuses to guess an origin.
//
// Falling back to a default would be the quietest possible failure: every
// internal absolute URL would classify as external, the checker would report a
// large "external skipped" count and zero problems, and it would stay that way.
export default {
  build: { format: 'directory' },
  trailingSlash: 'never',
}
