// Thin Gradle wrapper around the Electron desktop shell.
// The shell itself is a plain npm project (package.json + main.js).
// This module provides `:electron:run` and `:electron:dist` so the
// project can be driven through the existing ./gradlew workflow.

val nodeModulesDir = layout.projectDirectory.dir("node_modules")
val distDir = layout.projectDirectory.dir("dist")
val resourcesDir = layout.projectDirectory.dir("resources")
val webResourcesDir = resourcesDir.dir("web")
val mainResourcesDir = resourcesDir.dir("main")

// Resolve npm via PATH so Gradle's subprocess can find it even when
// /opt/homebrew/bin isn't on the JVM's default search path.
val npmExec: String = System.getenv("PATH")
    ?.split(File.pathSeparator)
    ?.map { File(it, "npm") }
    ?.firstOrNull { it.canExecute() }
    ?.absolutePath
    ?: "npm"

val npmInstall by tasks.registering(Exec::class) {
    group = "electron"
    description = "Install Electron npm dependencies."
    workingDir = projectDir
    commandLine(npmExec, "install")
    inputs.file("package.json")
    outputs.dir(nodeModulesDir)
}

val copyWebBundle by tasks.registering(Copy::class) {
    group = "electron"
    description = "Copy the web distribution into electron/resources/web."
    val webDist = project(":web").tasks.named("jsBrowserDistribution")
    dependsOn(webDist)
    from(webDist)
    into(webResourcesDir)
}

// Copy the Kotlin/JS Node bundle (electron-main module) into
// electron/resources/main/. The stub main.js requires the entry file
// from this directory at startup.
val copyMainBundle by tasks.registering(Copy::class) {
    group = "electron"
    description = "Copy the Kotlin/JS Node bundle into electron/resources/main."
    val mainCompile = project(":electron-main").tasks.named("jsProductionExecutableCompileSync")
    dependsOn(mainCompile)
    from(project(":electron-main").layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin"))
    into(mainResourcesDir)
}

tasks.register<Exec>("run") {
    group = "electron"
    description = "Launch the Electron desktop shell."
    dependsOn(npmInstall, copyWebBundle, copyMainBundle)
    workingDir = projectDir
    commandLine(npmExec, "start")
}

tasks.register<Exec>("dist") {
    group = "electron"
    description = "Build a distributable Electron app via electron-builder."
    dependsOn(npmInstall, copyWebBundle, copyMainBundle)
    workingDir = projectDir
    commandLine(npmExec, "run", "dist")
    inputs.file("package.json")
    inputs.file("main.js")
    inputs.dir(resourcesDir)
    outputs.dir(distDir)
}

tasks.register<Delete>("clean") {
    group = "electron"
    description = "Remove node_modules, dist, and staged resources."
    delete(nodeModulesDir, distDir, resourcesDir)
}
