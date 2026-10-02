import java.util.Base64

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
                // Excalidraw's stylesheet is imported from Kotlin (DrawingEditor.kt).
                cssSupport { enabled.set(true) }
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
            // The drawing editor (DrawingEditor.kt), loaded lazily in its own chunk.
            implementation(npm("@excalidraw/excalidraw", "0.18.1"))
            implementation(npm("react", "19.1.1"))
            implementation(npm("react-dom", "19.1.1"))
        }
    }
}

// Excalidraw's fonts, served next to web.js as `excalidraw-assets/fonts/…`
// (DrawingEditor.kt points `window.EXCALIDRAW_ASSET_PATH` there), so
// drawings render offline. Xiaolai (CJK, ~16 MB) is left out: Excalidraw
// falls back to its CDN for it.
tasks.named<ProcessResources>("jsProcessResources") {
    dependsOn(rootProject.tasks.named("kotlinNpmInstall"))
    from(rootProject.layout.buildDirectory.dir("js/node_modules/@excalidraw/excalidraw/dist/prod/fonts")) {
        exclude("Xiaolai/**")
        into("excalidraw-assets/fonts")
    }
}

// The browser demo's vault (demo/DemoMode.kt): `demo/vault/` at the repo
// root is an ordinary Lunarbor vault — open it in the desktop app with
// LUNARBOR_VAULT=demo/vault to edit the tour — packed into one
// `demo-vault.js` next to web.js, which the demo loads into memory. It is a
// script (`window.lunarborDemoVault = {…}`), not a JSON file to fetch, so
// the demo also runs from a page opened straight from disk (file://),
// where the browser refuses fetch() but still runs <script src>.
// Text files go in as text, everything else as base64; dotfiles (the
// trash, .DS_Store) are left out. `demo/state.json`, when present, seeds
// the demo's in-memory persister (e.g. which nodes start unfolded).
/** Packs a vault folder into one `demo-vault.js` (see the comment above). */
abstract class GenerateDemoVault : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val vaultDir: DirectoryProperty

    /** Optional `{ key: value }` JSON object of initial persister values (demo/state.json). */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stateFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = vaultDir.get().asFile
        val textExtensions = setOf("lunarbor", "md", "excalidraw", "html", "htm", "css", "js", "json", "svg", "txt", "csv")
        val dirs = mutableListOf<String>()
        val files = mutableListOf<Map<String, Any>>()
        root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith(".") }
            .filter { it != root && !it.name.startsWith(".") }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                if (f.isDirectory) {
                    dirs += rel
                } else if (f.extension.lowercase() in textExtensions) {
                    files += mapOf("path" to rel, "text" to f.readText())
                } else {
                    files += mapOf("path" to rel, "base64" to Base64.getEncoder().encodeToString(f.readBytes()))
                }
            }
        // start clean, so a file the task no longer writes (the old
        // demo-vault.json) never lingers in the bundle
        outputDir.get().asFile.deleteRecursively()
        val out = outputDir.get().file("demo-vault.js").asFile
        out.parentFile.mkdirs()
        val state = stateFile.orNull?.asFile?.takeIf { it.exists() }
            ?.let { groovy.json.JsonSlurper().parse(it) as Map<*, *> }
            ?.mapValues { (_, v) -> if (v is String) v else groovy.json.JsonOutput.toJson(v) }
            .orEmpty()
        val json = groovy.json.JsonOutput.toJson(mapOf("dirs" to dirs, "files" to files, "state" to state))
        out.writeText("window.lunarborDemoVault = $json;\n")
    }
}

val generateDemoVault by tasks.registering(GenerateDemoVault::class) {
    vaultDir.set(rootProject.layout.projectDirectory.dir("demo/vault"))
    stateFile.set(rootProject.layout.projectDirectory.file("demo/state.json").takeIf { it.asFile.exists() })
    outputDir.set(layout.buildDirectory.dir("generated/demoVault"))
}
kotlin.sourceSets.named("jsMain") { resources.srcDir(generateDemoVault) }
