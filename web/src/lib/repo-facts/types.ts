/** The facts the site is allowed to state about the app without a human retyping them. */
export interface RepoFacts {
  /** `versionCode` from `gradle.properties`, e.g. 1931. */
  readonly versionCode: number

  /**
   * The version **in source**, derived from `versionCode` — e.g. "1.93.1".
   *
   * Not necessarily the latest *published stable* release. v1.93.1 shipped as a
   * pre-release while stable was still v1.93.0, and the repo holds no "latest
   * stable" value. `/download` therefore prints no version at all; this field
   * exists because the spec requires deriving it, and it is named this way so
   * nobody wires it into a "Latest release" badge by reflex.
   */
  readonly versionName: string

  /**
   * `versionName` plus the `-foss` suffix `app/build.gradle.kts` sets on the FOSS
   * flavour — what a user of the recommended channel actually sees on-device.
   */
  readonly fossVersionName: string

  readonly minSdk: number
  readonly targetSdk: number
  readonly compileSdk: number

  /** Marketing name for `minSdk`, e.g. "Android 9". Only `minSdk` is mapped. */
  readonly minSdkAndroidName: string

  /** `thorExtensionApi` from the version catalog, as a string, e.g. "3.0.0". */
  readonly extensionApiVersion: string
}
