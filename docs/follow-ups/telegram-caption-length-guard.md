# Follow-up: assert the Telegram caption length before `sendDocument`

**Status:** open. Raised in review of PR #334 (the v1.93.2 release notes). Deliberately **not**
fixed there — see "Why not in #334".

## The failure it prevents

Release notes reach Telegram as a `sendDocument` **caption**, not as a message
(`.github/workflows/telegram-release.yml:144`, `.github/workflows/dev-check.yml:281`). Telegram caps
captions at **1024 UTF-16 code units** and **rejects** an oversized one outright — it does not
truncate. The `curl` carries no `--fail` and its output goes to `/dev/null`, so the step exits 0
having broadcast nothing. Every downstream signal — the job, the check, the release — stays green.

`release-notes/README.md` documents a ~870-unit budget for `telegram.md`, but that is a *manual*
gate applied to only one input. The value actually sent is `CAPTION`, which is `telegram.md` plus a
header the workflow builds and, on the release path, a GitHub link footer.

## Measured, for v1.93.2

| | UTF-16 units | headroom to 1024 |
|---|---|---|
| `telegram.md` alone | 825 | — |
| `dev-check.yml` final caption | 974 | 50 |
| `telegram-release.yml` final caption | 966 | 58 |

Wrapper cost: **149** units on the dev path, **141** on the release path. It is not a constant — the
dev header interpolates `github.ref_name`, `github.actor` and a track label, so a longer branch name
or actor pushes it up with nothing to notice.

v1.93.2 ships with 50 units of headroom, and only after two bullets were trimmed to buy it back: a
one-line accuracy fix in review took the dev caption to 1006 units — 18 from silence — and nothing
but a hand-run `python3` one-liner said so. v1.93.0's notes were 1008 units *before* any wrapper,
i.e. already over.

## What to add

After `CAPTION` is fully assembled and before the `curl`, in **both** workflows:

```bash
CAPTION_UNITS=$(printf '%s' "$CAPTION" | python3 -c \
  'import sys; t=sys.stdin.read(); print(sum(2 if ord(c) > 0xFFFF else 1 for c in t))')
if [ "$CAPTION_UNITS" -ge 1024 ]; then
  echo "::error::Telegram caption is $CAPTION_UNITS UTF-16 units; the cap is 1024. \
Trim release-notes/v\$VERSION_NAME/telegram.md."
  exit 1
fi
```

## Why not in #334

Two reasons, and the second is the design question that makes this its own change.

1. #334 is a release PR that ships a 988-unit caption on the path the guard would run on. A guard
   with an off-by-one in its own arithmetic would redden the release it is meant to protect, and the
   only way to find out is to publish.

2. **Placement is the actual decision, and a naive fix gets it wrong.** In `dev-check.yml` the
   Telegram step runs *after* fastlane has uploaded to Play and *before* "Create GitHub
   Pre-Release". A guard there fails loudly — but it fails a release that is already half-published:
   on Play, absent from GitHub, silent on Telegram. That is arguably worse than the silent skip it
   replaces.

   The gate belongs **before the build**, as a pre-flight step that reads `versionCode`, derives the
   version name, reconstructs the caption the later step would send, and fails while nothing has
   shipped. That step does not exist yet, and inventing it inside a release PR is how a release PR
   stops being one.

A pre-flight step is also the natural home for the other unchecked size — `playstore.txt` must stay
under 500 characters and nothing in CI verifies that either. Both belong in the same script.

## See also

* `release-notes/README.md` — trap 1, and Step 6, which is the manual gate this would replace.
* `.github/scripts/check-shizu-manifest.sh` — the precedent: a script whose every assertion exists
  to make one silent failure loud, wired into `pr-ci.yml` on every PR.
