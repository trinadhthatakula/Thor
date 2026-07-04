pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

buildCache {
    local {
        isEnabled = true
    }
}

// Local cross-repo development: set `thorExtensionApiDir` in a Gradle properties file
// (for example ~/.gradle/gradle.properties) or pass it with -PthorExtensionApiDir=... to point at a
// local thor-extension-api checkout and build against its source without publishing.
// Note: this is read via providers.gradleProperty(...), so local.properties is NOT consulted.
// Leave it unset to use the pinned published version.
//
// The included build's Gradle project is named `:extension-api`, but it publishes the artifact
// `thor-extension-api`. Gradle's automatic substitution matches on the project name, so it would
// look for `:extension-api` and miss the `:thor-extension-api` dependency. We therefore map it
// explicitly via dependencySubstitution.
val thorExtensionApiDir = providers.gradleProperty("thorExtensionApiDir").orNull
if (thorExtensionApiDir != null) {
    includeBuild(thorExtensionApiDir) {
        dependencySubstitution {
            substitute(module("com.trinadhthatakula:thor-extension-api"))
                .using(project(":extension-api"))
        }
    }
}

// Local cross-repo development against the Asgard UI library. Set `asgardDir` to a local Asgard
// checkout (via -PasgardDir=... or a Gradle properties file — local.properties is NOT consulted) to
// build against its source without publishing; unset to use the pinned published version.
//
// Asgard is a Kotlin Multiplatform build: its `:asgard` project publishes the
// `com.trinadhthatakula:asgard` coordinate through vanniktech, but Gradle's automatic composite
// substitution keys off project identity (group), which isn't set to the publishing group — so the
// automatic mapping silently misses and the pinned Maven artifact wins. We therefore map it
// explicitly via dependencySubstitution, exactly like thor-extension-api above.
val asgardDir = providers.gradleProperty("asgardDir").orNull
if (asgardDir != null) {
    includeBuild(asgardDir) {
        dependencySubstitution {
            substitute(module("com.trinadhthatakula:asgard"))
                .using(project(":asgard"))
        }
    }
}

rootProject.name = "Thor"
include(":app")
include(":suCore")
include(":bypass")
include(":vm-runtime")
