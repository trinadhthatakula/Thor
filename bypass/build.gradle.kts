import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}


android {
    namespace = "com.valhalla.bypass"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // This module had no lint block at all, so its "No issues found." was unconfigured rather
        // than clean — nothing here was enforced, and a warning introduced later would have passed
        // silently. That asymmetry pointed the wrong way: :app was held to warningsAsErrors while
        // :bypass, which holds the reflection and hidden-API code, was held to nothing.
        //
        // Measured before enabling: :bypass:lintDebug and :bypass:lintRelease both report
        // "No issues found.", so this pins the state the module is already in rather than asking
        // for new work.
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        checkTestSources = true
        printTextReport = true

        // SyntheticAccessor ships enabledByDefault = false (verified in the detector's static
        // initializer in lint-checks-32.4.0-alpha07, the jar that pairs with the pinned AGP), so
        // nothing above turns it on. It is enabled BY ID rather than via checkAllWarnings: only 4
        // of the registry's 39 disabled checks fire on this codebase at all, and the global flag
        // would drag in ~250 cosmetic findings that warningsAsErrors would then make fatal.
        //
        // It pins 18 findings from 6 companion methods in DexFieldLayout.kt (descriptorString ×5,
        // isAligned ×4, componentSize ×3, slice ×2, roundUp ×2, primitiveOrder ×2), all closed in
        // the same commit by widening those six from private to internal — the class is already
        // internal, so effective visibility is unchanged and no reflection target moves (nothing
        // keeps DexFieldLayout, and no string literal in the repo names those methods). Counted
        // per lint variant, NOT summed across lintDebug + lintRelease: :bypass has one src/main
        // and no flavors, so both variants analyse identical sources and report the identical 18.
        //
        // Do not assume a call written inside the companion object is exempt. The detector's only
        // companion bail-out is `getNameFromSource(node.getContainingUClass()) == "Companion"`,
        // and a companion `val` initializer — plus any lambda inside one — is hosted by UAST on
        // the OUTER class, because that is where the JVM static field lives. So descriptorString
        // (called from five companion `val` initializers) and primitiveOrder (called twice from
        // the FIELD_COMPARATOR lambda) are reported, and only a call in a companion *function*
        // body would be skipped.
        //
        // Helper.kt is the reason this is enabled narrowly and not swept: its private members are
        // resolved by string literal from Bypass.kt and kept by consumer-rules.pro. Widening one
        // of those renames it and breaks the hidden-API bypass at runtime, with no compile error
        // and no lint error. Lint does not flag them today; only a reviewer will.
        enable += "SyntheticAccessor"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    compileOnly(project(":vm-runtime"))
}