plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.metro)
}

metro {
    // Kotlin/JS IC doesn't support Metro's top-level codegen; disable those options.
    enableTopLevelFunctionInjection.set(false)
    generateContributionHints.set(false)
    generateContributionHintsInFir.set(false)
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "web.js"
            }
            binaries.executable()
        }
    }

    sourceSets {
        jsMain.dependencies {
            implementation(projects.client)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.lunula.core)
            implementation(libs.lunula.store)
            implementation(libs.lunula.web)
        }
    }
}
