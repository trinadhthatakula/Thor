# Store listing source and publication

The Google Play title, short description and full description live in
`fastlane/metadata/android/{en-US,en-GB,hi-IN}/`. Keep the English Shizu Store
copy in `shizu_store.json` aligned with `en-US`, and its Hindi copy aligned with
`hi-IN`. The app name in the Shizu manifest follows the Play title. The website
homepage and feature page should use the same feature vocabulary and limits.

The Play phone screenshots and feature graphic are generated from the reviewed,
real app captures under `web/src/assets/screenshots/`. To rebuild them, install
Pillow and run `python3 fastlane/scripts/build-listing-images.py` from any
directory. Review all six phone images and the feature graphic at their actual
size after each change. The icon is the existing Thor brand mark. Captions are
written to help people understand a screenshot while browsing; do not assume
Google indexes text baked into images as search keywords.

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
