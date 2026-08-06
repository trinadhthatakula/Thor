import com.android.build.api.artifact.SingleArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
    alias(libs.plugins.koin.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        optIn.add("kotlin.RequiresOptIn")
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("org.koin.core.annotation.KoinExperimentalAPI")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

koinCompiler {
    compileSafety = true
    strictSafety = true
    unsafeDslChecks = true
}

val keystorePropertiesFile: File = rootProject.file("jks.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// Whether this machine can sign a release at all: a local jks.properties, or the CI env vars.
// Single-sourced here because both the signingConfig block and the release build type need the
// same answer — see the comment on `signingConfig` below for why the build type has to ask.
val hasSigningCredentials: Boolean =
    keystorePropertiesFile.exists() || System.getenv("KEY_ALIAS") != null

// The locales Thor actually translates, and therefore the only ones any variant keeps.
//
// Declared once and applied variant-wide in `androidComponents` below, deliberately rather than
// per-flavour: two copies of this set is how foss and store drift into shipping different
// languages, and the whole point of the in-app picker is that the answer does not depend on how
// the user got the app. Adding a `values-xx` directory means adding it here too, or the resources
// are compiled and then filtered straight back out.
//
// `zh-rCN` and not `zh`: the resource directory is `values-zh-rCN`, and `localeFilters` matches on
// the qualifier as written. The picker's tag is region-less `zh`, which still resolves, because
// since API 24 ResourcesImpl selects via LocaleList.getFirstMatch with likely-subtag expansion
// (`zh` -> `zh-Hans` matches `zh-CN` -> `zh-Hans-CN`).
val translatedLocales: Set<String> = setOf("en", "ar", "es", "fr", "zh-rCN")

// --- VERSIONING HELPERS (Private & Modernized) ---

// 1. Resolve Code: Checks property 'versionCode' first, falls back to 'initialVersionCode'
private fun resolveVersionCode(): Int {
    val initial = providers.gradleProperty("initialVersionCode")
        .orNull
        ?.toIntOrNull()
        ?: throw GradleException("Required 'initialVersionCode' missing in gradle.properties")

    val override = providers.gradleProperty("versionCode")
        .orNull
        ?.toIntOrNull()

    return override ?: initial
}

// 2. Calculate Name: The math logic (1712 -> 1.71.2)
private fun calculateVersionName(code: Int): String {
    val major = code / 1000
    val minor = (code % 1000) / 10
    val patch = code % 10
    return "$major.$minor.$patch"
}

// 3. Resolve Name: Checks property 'versionName' first, falls back to math
private fun resolveVersionName(code: Int): String {
    return providers.gradleProperty("versionName").orNull
        ?: calculateVersionName(code)
}

android {
    namespace = "com.valhalla.thor"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.valhalla.thor"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        // Calculate versions using the private helpers
        val code = resolveVersionCode()
        versionCode = code
        versionName = resolveVersionName(code)

        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            debugSymbolLevel = "SYMBOL_TABLE"
        }

        // Startup-timing instrumentation switch, read by PrivilegeProbeTrace and by
        // ThorApplication's Logger.isDebug wiring. Off by default so `release` inherits false and
        // stays silent; `debug` and `benchmark` turn it back on below. It exists as its own field
        // rather than reusing BuildConfig.DEBUG because the benchmark build type is release-shaped,
        // so BuildConfig.DEBUG is false there and the trace would compile out again.
        buildConfigField("boolean", "PRIVILEGE_TRACE", "false")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            } else if (System.getenv("KEY_ALIAS") != null) {
                // CI/CD Build (GitHub Actions)
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                storeFile = file(System.getenv("KEYSTORE_FILE_PATH") ?: "release.jks")
            } else {
                logger.warn("⚠️ keystore.properties not found or environment variables not set. Release build will not be signed properly.")
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only assign the config when credentials actually exist. The `release` signingConfig
            // above leaves every field unset in the no-credentials case, and an assigned-but-empty
            // config fails validateSigning<Flavour>Release with "Keystore file not set for signing
            // config release" — so a fresh clone (and anyone rebuilding from source, F-Droid's
            // buildserver included) cannot produce a release APK at all. Unsigned output is the
            // correct result there; signing happens downstream. CI and local signing are unchanged,
            // because both satisfy the condition.
            signingConfig = signingConfigs.getByName("release").takeIf { hasSigningCredentials }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "PRIVILEGE_TRACE", "true")
        }

        // Release-shaped build for on-device startup measurement, and nothing else. It exists
        // because the numbers that matter cannot be taken from either shipping build type: `debug`
        // runs at a different compilation tier (run-from-apk vs speed-profile) so its timings do
        // not transfer, and `release` compiles the trace out entirely.
        //
        // Never distributed. It is restricted to the store flavour below, so the foss variant's
        // inputs are untouched and IzzyOnDroid reproducibility cannot be affected by anything here.
        //
        // No applicationIdSuffix on purpose: it installs over the release build, keeping the same
        // package name and signature, so the Magisk/KernelSU/Shizuku grants already given to
        // `com.valhalla.thor` carry over. KernelSU Next has no request mode, so a new application id
        // would mean granting root by hand again before every measurement session. The
        // versionNameSuffix is what tells the two apart on-device.
        create("benchmark") {
            initWith(getByName("release"))
            versionNameSuffix = "-benchmark"
            matchingFallbacks += "release"
            buildConfigField("boolean", "PRIVILEGE_TRACE", "true")
        }
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("store") {
            dimension = "distribution"
        }

        create("foss") {
            dimension = "distribution"
            // No versionNameSuffix on purpose. Obtainium cannot reconcile
            // "1.94.0-foss" against tag v1.94.0 - "foss" is not in its
            // recognised suffix list - so it sets versionDetection = false and
            // marks outdated users as up to date. F-Droid's Binaries: field
            // also substitutes only %v, which would resolve to a tag that does
            // not exist. The flavour is identified by its APK filename
            // (foss-release.apk) and its ProGuard file, not by versionName.
            proguardFile("proguard-rules-foss.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
        compose = true
        aidl = true
    }

    lint {
        // The severity overrides live in lint.xml, not in this block, so the reason for each one
        // travels with the rule instead of with the build script. AGP would pick app/lint.xml up by
        // itself; naming it keeps the link visible from here.
        lintConfig = file("lint.xml")

        // :app is at 0 errors / 0 warnings today (the only warnings, VectorPath, are downgraded in
        // lint.xml). Enforce that rather than let it rot: a warning is a build failure, and release
        // variants are checked too.
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true

        // Analyse :app's own sources only. :bypass has its own lint task, and with warnings fatal we
        // do not want this module's result to move because a dependency we do not control changed.
        checkDependencies = false

        // Off by default, which meant 61 unit-test files and the androidTest tree were analysed by
        // nothing at all — "0 errors" was bounded by what lint was pointed at, not by the code.
        // Measured cost of turning it on: 9 SdCardPath hits, every one of them a test asserting
        // *about* a path rather than hardcoding one, so that check is scoped away from src/test in
        // lint.xml and the net is 0 new findings today. What it buys is the next real defect in
        // those files being visible.
        checkTestSources = true

        // AGP 9 always writes the HTML/XML/SARIF reports and has deprecated the toggles and *Output
        // paths, so the only thing left worth asking for is the text report on stdout — otherwise a
        // CI failure just points at an HTML file nobody can open from the log.
        printTextReport = true
    }

    bundle {
        language {
            // An in-app language picker and per-locale delivery cannot both be true.
            //
            // Play splits an AAB by locale and installs only the ones the device's system languages
            // ask for. Picking Français then sets a Configuration whose `values-fr` was never
            // delivered, resources fall back to `values/`, and the picker is a no-op on Play
            // installs for exactly the languages the user does not already have — which is all of
            // the ones they would go looking for the picker to get.
            //
            // API 33+ is the exception and the reason this was survivable until now:
            // `LocaleManager.setApplicationLocales` tells `LocaleManagerService`, which has Play
            // fetch the missing language split. There is no such path below 33, and the only
            // supported substitute is Play Core's `SplitInstallManager` — a proprietary dependency
            // the FOSS flavour must not carry. Disabling the split is the documented alternative:
            // https://developer.android.com/guide/app-bundle/configure-base#handling_language_changes
            //
            // Cost: a Play install downloads one base APK carrying every locale it will ever have,
            // instead of a split carrying its own. That is paid for by the variant-wide
            // `localeFilters` in `androidComponents` below — the two changes are a pair, and either
            // one alone is wrong. Splits off without the filter means shipping ~75 locales of
            // AndroidX/Material strings to every device; the filter without splits off means the
            // picker still cannot reach `values-fr`.
            //
            // Measured on storeRelease (unsigned, 2026-08-04): filtering takes resources.arsc from
            // 663,672 to 230,132 bytes and the APK from 4,016,164 to 3,582,624 — a 433,540-byte
            // saving that is *entirely* arsc, the two deltas being equal to the byte and no other
            // zip entry differing. The residual regression against per-locale delivery is bounded
            // by an en-only build at 3,439,320, i.e. at most 143,304 bytes (4.00%), and that is an
            // over-estimate because a real base-plus-one-split download pays split overhead this
            // single APK does not.
            //
            // Applies only to `bundle*` tasks, so the reproducible `assembleFossRelease` APK is
            // untouched.
            enableSplit = false
        }
    }

    packaging {
        dex {
            // Compress dex in generated APKs. dex is otherwise STORED uncompressed (~76% of the
            // foss-release APK), so this roughly halves the direct-download size. By AGP design this
            // flag does NOT affect App Bundles — Play still delivers uncompressed, run-from-APK dex
            // to the store AAB, so only APK generation (foss + store sideload) is affected.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/specs/**"
            excludes += "**/*.dll"
            excludes += "**/*.dylib"
            excludes += "**/x64/**"
            excludes += "**/x86_64/*.dll"
            excludes += "**/META-INF/*.{kotlin_module,dot}"
            excludes += "META-INF/services/javax.annotation.processing.Processor"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

androidComponents {
    // 0. Confine the benchmark build type to the store flavour.
    //
    // Build types and flavours are a cross product in AGP, so declaring `benchmark` would otherwise
    // create `fossBenchmark` as well as `storeBenchmark`. Disabling the foss half here means the
    // foss flavour has exactly the two variants it always had and no task, artifact or input of the
    // reproducible `fossRelease` build can be reached from the benchmark configuration at all.
    // storeBenchmark is the only benchmark variant, and it is never published.
    beforeVariants(
        selector().withBuildType("benchmark").withFlavor("distribution", "foss")
    ) { variantBuilder ->
        variantBuilder.enable = false
    }

    // 1. Locale filtering — every variant, one set.
    //
    // Both flavours now ship every locale they will ever have in a single file: foss has always
    // been one universal APK (GitHub direct download), and store became one the moment
    // `bundle { language { enableSplit = false } }` was set above so that the in-app picker works
    // below API 33. Read that comment for why the split had to go; this is the other half of that
    // decision and the two are only correct together.
    //
    // Without this filter, disabling the language split would mean every Play install downloading
    // ~75 locales of AndroidX/Material translations it cannot reach: Thor's own UI is one of five
    // languages, so those library strings (date pickers, accessibility labels) would render in a
    // language the app is not being displayed in. The filter is what keeps "no per-locale delivery"
    // from meaning "pay for all locales".
    //
    // The cost is real and bounded: a user whose system language is, say, Polish loses Polish
    // library strings and gets English ones inside an app whose own UI is already English. That is
    // the same experience foss users have always had.
    onVariants { variant ->
        variant.androidResources.localeFilters.set(translatedLocales)
    }

    // 2. Existing FOSS Copy Task
    onVariants(selector().withFlavor("distribution", "foss")) { variant ->
        if (variant.buildType == "release") {
            val apkDir = variant.artifacts.get(SingleArtifact.APK)
            tasks.register<Copy>("copyFossReleaseApk") {
                description = "Copy Foss Release APK to the destination"
                dependsOn("assembleFossRelease")
                from(apkDir) { include("*.apk") }
                into(layout.buildDirectory.dir("distribution/foss"))
                rename(".*\\.apk", "foss-release.apk")
            }
        }
    }

    // 3. Store Copy Task
    onVariants(selector().withFlavor("distribution", "store")) { variant ->
        if (variant.buildType == "release") {
            val apkDir = variant.artifacts.get(SingleArtifact.APK)
            tasks.register<Copy>("copyStoreReleaseApk") {
                description = "Copy Store Release APK to the destination"
                dependsOn("assembleStoreRelease")
                from(apkDir) { include("*.apk") }
                into(layout.buildDirectory.dir("distribution/store"))
                rename(".*\\.apk", "store-release.apk")
            }
        }
    }
}

dependencies {
    implementation(libs.odin) // published com.trinadhthatakula:odin (was project(":suCore"))
    implementation(project(":bypass"))
    implementation(libs.thor.extension.api)
    implementation(libs.asgard)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    testImplementation(libs.junit)
    // Virtual time (`runTest`, `StandardTestDispatcher`) and Flow-emission assertions — without these
    // every behavioural test of a ViewModel or of BulkFreezeRunner has to sleep in wall-clock, which
    // is why docs/follow-ups/{viewmodel-behavior-tests,bulk-freeze-runner-concurrency-tests}.md were
    // filed as blocked. No mocking library: those follow-ups all specify "fake, don't mock", matching
    // the hand-written fakes the existing suite already uses.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.accompanist.drawablepainter)
    implementation(libs.kotlinx.serialization.json)
    // :app has ~300 `import kotlinx.coroutines.*` and no direct declaration — they arrive through
    // Odin's `api(kotlinx-coroutines-android)`. Declared here so Thor pins its own version instead of
    // silently tracking whatever Odin ships, and so coroutines-test stays on the same 1.11.0.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lottie.compose)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.dhizuku.api)
    implementation(libs.bundles.coil)
    implementation(libs.bundles.koin)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    // Adaptive Layouts
    implementation(libs.androidx.adaptive)
    implementation(libs.androidx.adaptive.layout)
    implementation(libs.androidx.adaptive.navigation)
    implementation(libs.androidx.adaptive.navigation3)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Store-only dependencies
    "storeImplementation"(libs.play.billing)
    "storeImplementation"(libs.play.billing.ktx)
}

// These rely on the private functions above, which is allowed in the same file scope
val currentVersionCode = resolveVersionCode()
val currentVersionName = resolveVersionName(currentVersionCode)

tasks.register("printVersionName") {
    description = "Prints the Version Name"
    val vName = currentVersionName
    doLast {
        println(vName)
    }
}