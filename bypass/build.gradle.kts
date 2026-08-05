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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    compileOnly(project(":vm-runtime"))
}