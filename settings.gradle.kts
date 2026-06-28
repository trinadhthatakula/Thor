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

// Local cross-repo development: set `thorExtensionApiDir` (in ~/.gradle/gradle.properties or
// local.properties) to a local thor-extension-api checkout to build against its source without
// publishing. Leave it unset to use the pinned published version.
//
// The included build's Gradle project is named `:extension-api`, but it publishes the artifact
// `thor-extension-api`. Gradle's automatic substitution matches on the project name, so it would
// look for `:extension-api` and miss the `:thor-extension-api` dependency. We therefore map it
// explicitly via dependencySubstitution.
val thorExtensionApiDir = providers.gradleProperty("thorExtensionApiDir").orNull
if (thorExtensionApiDir != null) {
    includeBuild(thorExtensionApiDir) {
        dependencySubstitution {
            substitute(module("io.github.trinadhthatakula:thor-extension-api"))
                .using(project(":extension-api"))
        }
    }
}

// Local cross-repo development against the Asgard UI library. Set `asgardDir` to a local Asgard
// checkout to build against its source without publishing; unset to use the pinned published
// version. The included build's project IS named `asgard` (== its artifactId), so Gradle's
// automatic dependency substitution resolves `com.trinadhthatakula:asgard` to it with no explicit mapping.
val asgardDir = providers.gradleProperty("asgardDir").orNull
if (asgardDir != null) {
    includeBuild(asgardDir)
}

rootProject.name = "Thor"
include(":app")
include(":suCore")
include(":bypass")
include(":vm-runtime")
