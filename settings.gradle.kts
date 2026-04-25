rootProject.name = "Notegrow"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// Composite build against the sibling darkness-toolkit checkout.
// Default points at the toolkit's `main` worktree; override with
// -Pdarkness.toolkit.path=../../darkness-toolkit/<other-worktree>
// when developing against a feature worktree of the toolkit.
val toolkitPath: String = (settings.providers.gradleProperty("darkness.toolkit.path")
    .orElse("../../darkness-toolkit/main")).get()
includeBuild(toolkitPath) {
    dependencySubstitution {
        substitute(module("se.soderbjorn.darkness:toolkit-core")).using(project(":toolkit-core"))
        substitute(module("se.soderbjorn.darkness:toolkit-store")).using(project(":toolkit-store"))
        substitute(module("se.soderbjorn.darkness:toolkit-web")).using(project(":toolkit-web"))
        substitute(module("se.soderbjorn.darkness:toolkit-compose")).using(project(":toolkit-compose"))
    }
}

include(":composeApp")
include(":client")
include(":web")
include(":electron")