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
        // Committed file-Maven-repo holding darkness-toolkit artifacts. Lets
        // notegrow build with no darkness-toolkit checkout on disk. Refresh
        // from the toolkit checkout with `./gradlew publishAllToLibsRepo`.
        maven {
            name = "darknessLibsLocal"
            url = uri("libs-repo")
        }
    }
}

// Auto-detect a sibling darkness-toolkit checkout. When present, switch to a
// Gradle composite build so toolkit edits flow into notegrow with no extra
// steps. Pass -Pdarkness.toolkit.useArtifacts=true to force resolution from
// the committed libs-repo even when sources are present (verifies published
// artifacts). Pass -Pdarkness.toolkit.path=… to point at an explicit checkout.
val toolkitOverride: String? = settings.providers.gradleProperty("darkness.toolkit.path").orNull
val useArtifacts: Boolean = settings.providers.gradleProperty("darkness.toolkit.useArtifacts").orNull == "true"
val toolkitCandidates: List<String> = listOfNotNull(
    toolkitOverride,
    "../../darkness-toolkit/main",
)
val toolkitPath: String? = if (useArtifacts) null else toolkitCandidates
    .firstOrNull { File(rootDir, it).resolve("settings.gradle.kts").exists() }
if (toolkitPath != null) {
    includeBuild(toolkitPath) {
        dependencySubstitution {
            substitute(module("se.soderbjorn.darkness:toolkit-core")).using(project(":toolkit-core"))
            substitute(module("se.soderbjorn.darkness:toolkit-store")).using(project(":toolkit-store"))
            substitute(module("se.soderbjorn.darkness:toolkit-web")).using(project(":toolkit-web"))
            substitute(module("se.soderbjorn.darkness:toolkit-compose")).using(project(":toolkit-compose"))
        }
    }
}

include(":composeApp")
include(":client")
include(":web")
include(":electron")
include(":electron-main")