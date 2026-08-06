#!/usr/bin/env bash
# supply enumerates locales from the metadata directory, so a locale with no
# changelogs/ dir still gets a LocalizedText - with empty text. That blanks
# the what's-new for those users rather than leaving the previous one. Every
# locale that has metadata must therefore have a changelogs dir, and every
# changelog present in the reference locale must be present in all of them.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
meta="$repo_root/fastlane/metadata/android"
reference="en-US"
# 1600 predates the current release-notes tree and has no counterpart. It is
# kept for history and deliberately not mirrored.
exempt="1600.txt"
assertions=0

[ -d "$meta/$reference/changelogs" ] || { echo "  reference locale has no changelogs dir"; exit 1; }

# shellcheck disable=SC2012 # changelog filenames are version codes (digits only); ls is safe here
ref_files="$(cd "$meta/$reference/changelogs" && ls -1 ./*.txt | sed 's|^\./||' | sort)"
[ -n "$ref_files" ] || { echo "  reference locale has no changelog files - vacuous"; exit 1; }

failed=0
for dir in "$meta"/*/; do
  locale="$(basename "$dir")"
  [ "$locale" = "$reference" ] && continue
  # A locale directory with no metadata at all is not a locale supply sees.
  ls "$dir"/*.txt >/dev/null 2>&1 || continue

  if [ ! -d "$dir/changelogs" ]; then
    echo "  MISSING: $locale has metadata but no changelogs/ dir"
    failed=1
    continue
  fi

  for f in $ref_files; do
    case " $exempt " in *" $f "*) continue ;; esac
    assertions=$((assertions + 1))
    if [ ! -f "$dir/changelogs/$f" ]; then
      echo "  MISSING: $locale/changelogs/$f"
      failed=1
    elif [ ! -s "$dir/changelogs/$f" ]; then
      echo "  EMPTY:   $locale/changelogs/$f"
      failed=1
    fi
  done
done

[ "$failed" -eq 0 ] || exit 1
echo "  ok: every locale mirrors $reference's changelogs"
echo "  ${assertions} assertion(s)"
