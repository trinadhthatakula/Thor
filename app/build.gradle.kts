import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties
import com.android.build.api.artifact.SingleArtifact // REQUIRED IMPORT

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinSerialization)
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

val keystorePropertiesFile: File = rootProject.file("jks.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun calculateVersionName(code: Int): String {
    // Logic: 1709 -> 1.70.9
    val major = code / 1000
    val minor = (code % 1000) / 10
    val patch = code % 10
    return "$major.$minor.$patch"
}

// Helper task for Fastlane to retrieve the version name
tasks.register("printVersionName") {
    // Read the -PversionCode property passed from command line
    val fallbackCode = providers.gradleProperty("initialVersionCode").getOrElse("1709").toInt()
    val code = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: fallbackCode
    doLast {
        // Output ONLY the version name to stdout
        println(calculateVersionName(code))
    }
}

android {
    namespace = "com.valhalla.thor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.valhalla.thor"
        minSdk = 28
        targetSdk = 36
        val fallbackCode = providers.gradleProperty("initialVersionCode").getOrElse("1709").toInt()
        val code = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: fallbackCode
        versionCode = code
        versionName = calculateVersionName(code)
        println("🔨 Building Version: $versionName (Code: $versionCode)")
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            debugSymbolLevel = "SYMBOL_TABLE"
        }
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
                println("⚠️ keystore.properties not found or environment variables not set. Release build will not be signed properly.")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("store") {
            dimension = "distribution"
        }

        create("foss") {
            dimension = "distribution"
            versionNameSuffix = "-foss"
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
    }

    packaging {
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
    onVariants(selector().withFlavor("distribution", "foss")) { variant ->
        if (variant.buildType == "release") {
            val apkDir = variant.artifacts.get(SingleArtifact.APK)
            tasks.register<Copy>("copyFossReleaseApk") {
                dependsOn("assembleFossRelease")
                from(apkDir) {
                    include("*.apk")
                }
                into(layout.buildDirectory.dir("outputs/apk/foss/release"))
                rename(".*\\.apk", "foss-release.apk")
            }
        }
    }
}

dependencies {
    implementation(project(":suCore"))
    implementation(libs.androidx.datastore.preferences)
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.accompanist.drawablepainter)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lottie.compose)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    implementation(libs.bundles.coil)
    implementation(libs.bundles.koin)
}