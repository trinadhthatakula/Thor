# Store listing source and publication

The Google Play title, short description and full description live in
`fastlane/metadata/android/{en-US,en-GB,hi-IN}/`. Keep the English Shizu Store
copy in `shizu_store.json` aligned with `en-US`, and its Hindi copy aligned with
`hi-IN`, including the localized title. The main app name in the Shizu manifest
follows the en-US Play title. The website
homepage and feature page should use the same feature vocabulary and limits.

Fastlane currently has Play listing text only for `en-US`, `en-GB`, and `hi-IN`.
Shizu Store also has Arabic, Spanish, French, European Portuguese, and Chinese
descriptions and localized app names in `shizu_store.json`. These Shizu-only
translations do not automatically create Google Play locale listings. Review
every localized claim against the current app before reusing that copy on Play.

The Play screenshots and feature graphic are generated from real app captures
under `web/src/assets/screenshots/` and `fastlane/assets/captures/`. The latter
includes the UAD and Backup & Restore phone captures, plus unfolded foldable
and tablet captures. To rebuild, install Pillow and run
`python3 fastlane/scripts/build-listing-images.py` from any directory. Review
all eight phone, six foldable, and five tablet images and the feature graphic
at their actual size after each change. The foldable set is generated in
`sevenInchScreenshots/` and the tablet set in `tenInchScreenshots/`, the Play
Console slots recognized by Fastlane. The eight phone screenshots fill Play's
phone limit; the icon is the existing Thor brand mark. Captions help people
understand a screenshot while browsing; do not assume Google indexes text
baked into images as search keywords.

The large-screen source captures were recorded in July 2026 and some visibly
show `v1.92.1-foss`. Check them against the current UI before publishing; replace
the captures and regenerate if the pictured flow has changed.

Keep titles within 30 characters, short descriptions within 80, and full
descriptions within 4,000. Use precise terms people search for, but describe
the actual capability: uninstalling a system app removes it for the current
Android user, not from the system partition; some actions require a configured
Root, Shizuku or Dhizuku mode. Screenshots must depict the behavior they name.

The release Fastlane lanes deliberately set `skip_upload_metadata`,
`skip_upload_images` and `skip_upload_screenshots` to true. **Merging a listing
change does not update Google Play.** Publish the reviewed text and images
through Play Console's store listing editor as a separate listing change after
the PR lands. A listing change does not require a new `versionCode` or a binary
upload. Use Play Console's preview and review flow to verify each locale and the
first two screenshots before submitting it. The live listing may lag approval;
measure search impressions and listing conversion in Play Console afterward
instead of treating keyword placement as a ranking guarantee.
