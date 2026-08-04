rootProject.name = "TreeFacts"
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

// Auto-detect a sibling lunula checkout. When present, switch to a Gradle
// composite build so toolkit edits flow into treefacts with no extra steps.
// Pass -Plunula.toolkit.useArtifacts=true to force resolution from the
// Maven Central even when sources are present (verifies published
// artifacts). Pass -Plunula.toolkit.path=… to point at an explicit checkout.
// Candidate list is searched in order; the first existing checkout wins.
val toolkitOverride: String? = settings.providers.gradleProperty("lunula.toolkit.path").orNull
val useArtifacts: Boolean = settings.providers.gradleProperty("lunula.toolkit.useArtifacts").orNull == "true"
val toolkitCandidates: List<String> = listOfNotNull(
    toolkitOverride,
    "../../lunula/develop",
    "../../lunula/main",
)
val toolkitPath: String? = if (useArtifacts) null else toolkitCandidates
    .firstOrNull { File(rootDir, it).resolve("settings.gradle.kts").exists() }
if (toolkitPath != null) {
    includeBuild(toolkitPath) {
        dependencySubstitution {
            substitute(module("se.soderbjorn.lunula:lunula-core")).using(project(":lunula-core"))
            substitute(module("se.soderbjorn.lunula:lunula-store")).using(project(":lunula-store"))
            substitute(module("se.soderbjorn.lunula:lunula-web")).using(project(":lunula-web"))
            substitute(module("se.soderbjorn.lunula:lunula-compose")).using(project(":lunula-compose"))
        }
    }
}

include(":composeApp")
include(":client")
include(":web")
include(":electron")
include(":electron-main")