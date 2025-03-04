import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinSerialization)
}

android {

    namespace = "com.valhalla.thor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.valhalla.thor"
        minSdk = 24
        targetSdk = 35
        versionCode = 1500
        versionName = "1.500"
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val p = Properties()
        p.load(project.rootProject.file("local.properties").reader())
        buildConfigField(
            type = "String",
            name = "API_URL",
            value = "\"${p.getProperty("API_URL")}\""
        )
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

    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.accompanist.drawablepainter)

    /// Kotlinx
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.topjohnwu.libsu.core)

    implementation(libs.lottie.compose)

    implementation(libs.androidx.ui.text.google.fonts)
    implementation(libs.play.integrity)


    //implementation(libs.shizuku.api)
    //implementation(libs.shizuku.provider)

}