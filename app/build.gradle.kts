plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.baselineprofile)
}

android {

    namespace = "com.valhalla.thor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.valhalla.thor"
        minSdk = 24
        targetSdk = 36
        versionCode = 1605
        versionName = "1.605"
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
        /*create("foss_release") {
            versionNameSuffix = "_foss"
            isMinifyEnabled = false
        }*/
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {

    implementation(project(":suCore"))

    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.profileinstaller)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    "baselineProfile"(project(":app:baselineprofile"))
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.accompanist.drawablepainter)

    /// Kotlinx
    implementation(libs.kotlinx.serialization.json)

    //implementation(libs.topjohnwu.libsu.core)
    implementation(libs.lottie.compose)

    //implementation(libs.shizuku.api)
    //implementation(libs.shizuku.provider)

}