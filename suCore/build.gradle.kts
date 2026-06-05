import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

android {
    namespace = "com.valhalla.superuser"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("proguard-rules.pro")
    }
    buildTypes {
        create("foss_release") {
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        buildConfig = true
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
