// @ts-check
import { defineConfig } from 'astro/config'
import mdx from '@astrojs/mdx'

// The landing page for Thor. Static output, no adapter, no SSR.
//
// `site` is load-bearing twice over: canonical URLs and Open Graph tags need it,
// and the offline link checker uses it to tell an internal absolute URL from a
// genuinely external one.
//
// There is deliberately no `@astrojs/vercel` adapter. That package exists for SSR,
// Vercel Analytics and Vercel Image Optimization — all three are excluded by the
// spec, and adding it would switch the build to a serverless output shape.
export default defineConfig({
  site: 'https://thor.trinadhthatakula.com',

  // `directory` emits /faq -> dist/faq/index.html, which is what Vercel serves.
  // `trailingSlash: 'never'` makes the dev server agree with `vercel.json`'s
  // `"trailingSlash": false`, so a link that works locally works in production.
  build: { format: 'directory' },
  trailingSlash: 'never',

  integrations: [mdx()],

  markdown: {
    // `css-variables` keeps syntax highlighting inside our own palette. Importing a
    // stock Shiki theme would inject foreign hexes into the built HTML and trip the
    // "every colour in dist comes from tokens.css" assertion.
    shikiConfig: { theme: 'css-variables', wrap: true },
  },

  // No prefetch, no view transitions, no client islands. The theme toggle is the
  // only script on the site and it is inlined by hand, not bundled.
  prefetch: false,

  devToolbar: { enabled: false },
})
