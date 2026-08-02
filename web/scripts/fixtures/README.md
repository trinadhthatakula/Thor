# Checker fixtures

Miniature built sites. Each directory is a standalone `dist` that one of the
checkers in `web/scripts/` is pointed at from `web/scripts/*.test.mjs`.

They exist because **a checker that passes proves nothing on its own.** An
unanchored regex, a glob that matches no files, a `dist` path that does not
exist, and a rule whose pattern was written with straight quotes against
smartypants output all exit 0 — forever, and identically to a clean run. The
only way to know a gate is a gate is to hand it something it must reject.

So every rule is proved in **both** directions:

| Directory | What it proves |
|---|---|
| `links/pass` | A correct `/faq` link, a correct same-page `#you-may-not-need-root` fragment, a cross-page fragment, an internal absolute URL, an external one, and the hashed stylesheet and preloaded font under `_astro/` all pass. A checker that fails on correct links gets disabled inside a week — and this one did false-fail every page's own stylesheet until `_astro/` came off the walker's skip list, which is why those two assets are in the fixture. |
| `links/broken-link` | A typo'd route fails. |
| `links/broken-fragment` | A fragment with no matching `id` fails, same-page and cross-page. |
| `links/trailing-slash` | `/faq/` fails, because `trailingSlash: 'never'` makes it a redirect. |
| `claims/<id>/fail` | The tempting wrong phrasing for that rule, and nothing else. The test asserts **that specific rule id** fired, so a fixture cannot pass by tripping some other rule. |
| `claims/<id>/pass` | The corrected phrasing for the same fact. The test asserts **zero** violations from **any** rule, which is what catches a rule that over-triggers on correct copy. |
| `markup/*` | One structural defect each, plus a clean page. |
| `a11y/*` | Proves the axe-in-jsdom plumbing actually runs, before `dist` exists. |
| `screenshots/pending` | The same input twice: green on a local or preview build, refused on a production one. A gate that only ever fails would have broken every build since the day `DeviceFrame` was written. |
| `screenshots/captured` | A filled frame passes even in strict mode — and so does a paragraph that quotes "Screenshot pending" while explaining the mechanism. |

`fixtures.test.mjs` asserts that every rule id in `src/content/claims.mjs` has
both a `fail` and a `pass` directory. Adding a rule without fixtures fails the
suite rather than shipping a decoration.

The prose in these files is written to be *plausible* — it is the sentence
someone would actually write — not a keyword soup. A fixture that only trips its
rule because it contains an implausible phrase proves nothing about the copy that
will really be written.
