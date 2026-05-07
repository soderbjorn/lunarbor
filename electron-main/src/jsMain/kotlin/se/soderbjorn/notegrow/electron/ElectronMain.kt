/* ElectronMain.kt — Electron main process, written in Kotlin/JS.
 *
 * Direct port of the previous electron/main.js. Owns:
 *  - Per-OS persistence path resolution (per-app `Library/Application
 *    Support/Darkness/Notegrow/`-style directories).
 *  - Atomic JSON I/O (write-tmp + rename) for `ui-settings.json` and
 *    `layout-state.json`.
 *  - `darkness:*` IPC handlers (`readUiSettings` / `writeUiSettings` /
 *    `readLayoutState` / `writeLayoutState`).
 *  - File-watcher on the shared darkness ui-settings file, debounced
 *    + self-write-suppressed, broadcast over `darkness:uiSettingsChanged`.
 *  - `notegrow:*` file-ops IPC handlers powering the renderer's
 *    [se.soderbjorn.notegrow.platform.FileSystem] (ensureDirectory,
 *    readFileIfExists, writeFile, deleteFile, deleteDirectoryIfEmpty,
 *    moveFile, moveDirectory, listDirectory, listDirectoryEntries).
 *  - BrowserWindow setup with the boot-time `--darkness-settings=` /
 *    `--darkness-layout-state=` argument injection the renderer's
 *    preload script picks up.
 *  - Single-instance lock + second-instance focus relay.
 *  - External-link routing (window.open + will-navigate → shell.openExternal).
 *  - macOS application menu with a "Hotkeys…" item that fires the
 *    `notegrow:show-hotkeys` IPC the renderer subscribes to.
 *
 * The renderer-side contract (`globalThis.darknessApi`,
 * `globalThis.noteApi`, `globalThis.__darknessSettings`,
 * `globalThis.__darknessLayoutState`, the `notegrow:show-hotkeys`
 * channel) is unchanged from the JS predecessor — preload.js still
 * exposes those bindings and only the bytes-on-disk path changed
 * languages. */
package se.soderbjorn.notegrow.electron

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise

private const val APP_NAME = "Notegrow"

private var mainWindow: BrowserWindow? = null

/**
 * Bytes most recently written by this Electron process to the shared
 * `ui-settings.json`. Compared against fresh reads from
 * [installSharedThemesWatcher] so self-induced fs.watch events don't
 * loop back to the renderer as "external" changes. `null` until the
 * first write (the watcher tolerates that — first write wins).
 */
private var lastWrittenUiSettings: dynamic = null

/** Active fs.watch handle, kept so window re-creation doesn't leak. */
private var sharedThemesWatcher: FsWatcher? = null

/**
 * Coalesce timer for the fs.watch debounce. Some editors fire `change`
 * twice per save; we collapse all events inside a 200 ms window into
 * one read+broadcast cycle.
 */
private var sharedThemesDebounce: dynamic = null

fun main() {
    app.setName(APP_NAME)

    if (!app.requestSingleInstanceLock()) {
        app.quit()
        return
    }

    registerIpcHandlers()

    app.on("second-instance") { _, _ ->
        val w = mainWindow
        if (w != null && !w.isDestroyed()) {
            if (w.isMinimized()) w.restore()
            w.focus()
        }
    }

    app.on("window-all-closed") { _, _ -> app.quit() }

    app.whenReady().then {
        buildAppMenu()
        createWindow()
    }
}

/* --- Path resolution -------------------------------------------------- */

/** Per-app darkness ui-settings file path. */
private fun defaultDarknessSettingsPath(): String =
    perAppPath("ui-settings.json")

/** Per-app darkness layout-state file path. */
private fun defaultAppLayoutStatePath(): String =
    perAppPath("layout-state.json")

/**
 * Per-app file path for the toolkit-owned layout state — per-tab pane
 * geometry, layout preset, and paneOrder, written by the toolkit's
 * `persistLayoutState()` under `PersistKeys.LAYOUT_STATE`. Distinct
 * from [defaultAppLayoutStatePath] (which holds notegrow's typed
 * tab list under `PersistKeys.LAYOUT`).
 */
private fun defaultAppLayoutToolkitStatePath(): String =
    perAppPath("layout-toolkit-state.json")

private fun perAppPath(filename: String): String {
    val home = osModule.homedir()
    return when (process.platform) {
        "darwin" ->
            pathModule.join(home, "Library", "Application Support", "Darkness", APP_NAME, filename)
        "win32" -> {
            val appData = (process.env.APPDATA as String?)
                ?.takeIf { it.isNotEmpty() }
                ?: pathModule.join(home, "AppData", "Roaming")
            pathModule.join(appData, "Darkness", APP_NAME, filename)
        }
        else -> {
            val xdg = (process.env.XDG_CONFIG_HOME as String?)
                ?.takeIf { it.isNotEmpty() }
                ?: pathModule.join(home, ".config")
            pathModule.join(xdg, "darkness", APP_NAME.lowercase(), filename)
        }
    }
}

/* --- Boot snapshot + window ------------------------------------------ */

private fun readSyncOrNull(p: String): String? = try {
    fsSync.readFileSync(p, "utf8")
} catch (_: Throwable) {
    null
}

private fun createWindow() {
    val settingsJson = readSyncOrNull(defaultDarknessSettingsPath())
    val layoutJson = readSyncOrNull(defaultAppLayoutStatePath())
    val layoutToolkitJson = readSyncOrNull(defaultAppLayoutToolkitStatePath())
    val additionalArguments = mutableListOf<String>()
    if (settingsJson != null) {
        additionalArguments += "--darkness-settings=${js("encodeURIComponent")(settingsJson)}"
    }
    if (layoutJson != null) {
        additionalArguments += "--darkness-layout-state=${js("encodeURIComponent")(layoutJson)}"
    }
    if (layoutToolkitJson != null) {
        additionalArguments += "--darkness-layout-toolkit-state=${js("encodeURIComponent")(layoutToolkitJson)}"
    }

    val options: dynamic = js("({})")
    options.width = 1024
    options.height = 720
    options.title = APP_NAME
    val webPreferences: dynamic = js("({})")
    webPreferences.contextIsolation = true
    webPreferences.nodeIntegration = false
    // Resource layout (owned by electron/build.gradle.kts):
    //   electron/main.js                    — stub that loads the Kotlin bundle
    //   electron/preload.js                 — preload (still JS)
    //   electron/resources/main/*.js        — this Kotlin bundle + its deps
    //   electron/resources/web/index.html   — the renderer
    // __dirname here resolves to electron/resources/main/, so we go up
    // two levels to reach electron/ for preload, and up one to reach
    // resources/ for the renderer.
    val moduleDir = js("__dirname") as String
    webPreferences.preload = pathModule.join(moduleDir, "..", "..", "preload.js")
    webPreferences.additionalArguments = additionalArguments.toTypedArray()
    options.webPreferences = webPreferences

    val w = BrowserWindow(options)
    mainWindow = w

    val externalScheme = Regex("^(https?|mailto|tel|ftps?):", RegexOption.IGNORE_CASE)

    w.webContents.setWindowOpenHandler { details ->
        val url = details.url as String
        if (externalScheme.containsMatchIn(url)) shell.openExternal(url)
        js("({ action: 'deny' })")
    }

    w.webContents.on("will-navigate") { event, url ->
        val current = w.webContents.getURL()
        if (url != current && externalScheme.containsMatchIn(url)) {
            event.preventDefault()
            shell.openExternal(url)
        }
    }

    w.loadFile(pathModule.join(moduleDir, "..", "web", "index.html"))

    installSharedThemesWatcher()
}

/* --- Atomic write ----------------------------------------------------- */

private suspend fun atomicWriteUtf8(target: String, json: String): dynamic {
    val opts: dynamic = js("({})")
    opts.recursive = true
    fsPromises.mkdir(pathModule.dirname(target), opts).await()
    val tmp = "$target.tmp"
    val bufferModule: dynamic = js("require")("buffer")
    val bytes = bufferModule.Buffer.from(json, "utf8")
    fsPromises.writeFile(tmp, bytes).await()
    fsPromises.rename(tmp, target).await()
    return bytes
}

/* --- IPC handlers ----------------------------------------------------- */

private fun registerIpcHandlers() {
    // ── darkness:* (toolkit-canonical persistence channels) ─────
    ipcMain.handle("darkness:writeUiSettings") { _, json ->
        GlobalScope.promise {
            lastWrittenUiSettings = atomicWriteUtf8(defaultDarknessSettingsPath(), json as String)
        }
    }
    ipcMain.handle("darkness:readUiSettings") { _, _ ->
        readJsonOrNull(defaultDarknessSettingsPath())
    }
    ipcMain.handle("darkness:writeLayoutState") { _, json ->
        GlobalScope.promise {
            atomicWriteUtf8(defaultAppLayoutStatePath(), json as String)
        }
    }
    ipcMain.handle("darkness:readLayoutState") { _, _ ->
        readJsonOrNull(defaultAppLayoutStatePath())
    }
    ipcMain.handle("darkness:writeLayoutToolkitState") { _, json ->
        GlobalScope.promise {
            atomicWriteUtf8(defaultAppLayoutToolkitStatePath(), json as String)
        }
    }
    ipcMain.handle("darkness:readLayoutToolkitState") { _, _ ->
        readJsonOrNull(defaultAppLayoutToolkitStatePath())
    }

    // ── notegrow:* (renderer-side FileSystem operations) ────────
    ipcMain.handle("notegrow:ensureDirectory") { _, dirPath ->
        GlobalScope.promise {
            val opts: dynamic = js("({})")
            opts.recursive = true
            fsPromises.mkdir(dirPath as String, opts).await()
        }
    }
    ipcMain.handle("notegrow:readFileIfExists") { _, filePath ->
        readJsonOrNull(filePath as String)
    }
    ipcMain.handle("notegrow:writeFile") { _, filePath, content ->
        GlobalScope.promise {
            val opts: dynamic = js("({})")
            opts.recursive = true
            fsPromises.mkdir(pathModule.dirname(filePath as String), opts).await()
            fsPromises.writeFile(filePath, content).await()
        }
    }
    ipcMain.handle("notegrow:deleteFile") { _, filePath ->
        GlobalScope.promise<Unit> {
            try {
                fsPromises.unlink(filePath as String).await()
            } catch (err: Throwable) {
                val code = (err.asDynamic().code as String?)
                if (code != "ENOENT") throw err
            }
        }
    }
    ipcMain.handle("notegrow:deleteDirectoryIfEmpty") { _, dirPath ->
        GlobalScope.promise<Unit> {
            try {
                fsPromises.rmdir(dirPath as String).await()
            } catch (err: Throwable) {
                val code = (err.asDynamic().code as String?)
                if (code != "ENOENT" && code != "ENOTEMPTY" && code != "EEXIST") throw err
            }
        }
    }
    ipcMain.handle("notegrow:moveFile") { _, from, to ->
        GlobalScope.promise {
            val opts: dynamic = js("({})")
            opts.recursive = true
            fsPromises.mkdir(pathModule.dirname(to as String), opts).await()
            fsPromises.rename(from as String, to).await()
        }
    }
    ipcMain.handle("notegrow:moveDirectory") { _, from, to ->
        GlobalScope.promise {
            val opts: dynamic = js("({})")
            opts.recursive = true
            fsPromises.mkdir(pathModule.dirname(to as String), opts).await()
            fsPromises.rename(from as String, to).await()
        }
    }
    ipcMain.handle("notegrow:listDirectory") { _, dirPath ->
        GlobalScope.promise {
            try {
                fsPromises.readdir(dirPath as String).await()
            } catch (err: Throwable) {
                val code = (err.asDynamic().code as String?)
                if (code == "ENOENT") emptyArray<String>() else throw err
            }
        }
    }
    ipcMain.handle("notegrow:listDirectoryEntries") { _, dirPath ->
        GlobalScope.promise {
            val opts: dynamic = js("({})")
            opts.withFileTypes = true
            try {
                val raw = fsPromises.readdir(dirPath as String, opts).await()
                // `raw` elements are Node Dirent objects; treat each as
                // dynamic without an `.asDynamic()` call (the receiver is
                // already a dynamic Array<dynamic>, so .asDynamic compiles
                // to a literal method call that doesn't exist on Dirent).
                val out: dynamic = js("[]")
                for (i in 0 until raw.size) {
                    val ed = raw[i]
                    val obj: dynamic = js("({})")
                    obj.name = ed.name
                    obj.isDirectory = (ed.isDirectory() as Boolean)
                    out.push(obj)
                }
                out
            } catch (err: Throwable) {
                val code = (err.asDynamic().code as String?)
                if (code == "ENOENT") js("[]") else throw err
            }
        }
    }
}

private fun readJsonOrNull(path: String): Promise<String?> = GlobalScope.promise {
    try {
        fsPromises.readFile(path, "utf8").await()
    } catch (err: Throwable) {
        val code = (err.asDynamic().code as String?)
        if (code == "ENOENT") null else throw err
    }
}

/* --- Shared-themes file watcher --------------------------------------- */

/**
 * Watches the directory that holds the shared darkness ui-settings file.
 * On each (debounced) change that doesn't match this process's last
 * write, broadcasts the freshly-read JSON to the renderer over
 * `darkness:uiSettingsChanged`. Idempotent: closes any prior watcher
 * before installing a fresh one.
 *
 * Some filesystems / sandbox configs reject `fs.watch`; in that case the
 * renderer simply doesn't get live updates (boot-time read still works).
 */
private fun installSharedThemesWatcher() {
    sharedThemesWatcher?.let {
        try { it.close() } catch (_: Throwable) { /* already closed */ }
        sharedThemesWatcher = null
    }
    val target = defaultDarknessSettingsPath()
    val dir = pathModule.dirname(target)
    val fname = pathModule.basename(target)
    try {
        val mkdirOpts: dynamic = js("({})")
        mkdirOpts.recursive = true
        fsSync.mkdirSync(dir, mkdirOpts)
    } catch (_: Throwable) { /* dir already exists */ }
    val onDebouncedChange: () -> Unit = {
        sharedThemesDebounce = null
        val bytes: dynamic = try {
            fsSync.readFileSync(target)
        } catch (_: Throwable) {
            null
        }
        if (bytes != null) {
            val last = lastWrittenUiSettings
            val sameAsSelf = last != null && (bytes.equals(last) as Boolean)
            if (!sameAsSelf) {
                val w = mainWindow
                if (w != null && !w.isDestroyed()) {
                    w.webContents.send("darkness:uiSettingsChanged", bytes.toString("utf8"))
                }
            }
        }
    }
    try {
        sharedThemesWatcher = fsSync.watch(dir) { _, changedName ->
            if (changedName != fname) return@watch
            val pending = sharedThemesDebounce
            if (pending != null) js("clearTimeout")(pending)
            sharedThemesDebounce = js("setTimeout")(onDebouncedChange, 200)
        }
    } catch (_: Throwable) {
        sharedThemesWatcher = null
    }
}

/* --- App menu --------------------------------------------------------- */

private fun buildAppMenu() {
    val isMac = process.platform == "darwin"
    val showHotkeys = {
        val w = mainWindow
        if (w != null && !w.isDestroyed()) {
            w.webContents.send("notegrow:show-hotkeys")
        }
    }

    val template = ArrayList<dynamic>()

    if (isMac) {
        template.add(menuItem(APP_NAME) {
            arrayOf(
                role("about"),
                separator(),
                clickItem("Hotkeys…", "Cmd+/", showHotkeys),
                separator(),
                role("services"),
                separator(),
                role("hide"),
                role("hideOthers"),
                role("unhide"),
                separator(),
                role("quit"),
            )
        })
    }

    template.add(menuItem("Edit") {
        arrayOf(
            role("undo"),
            role("redo"),
            separator(),
            role("cut"),
            role("copy"),
            role("paste"),
            role("selectAll"),
        )
    })

    template.add(menuItem("View") {
        arrayOf(
            role("reload"),
            role("forceReload"),
            role("toggleDevTools"),
            separator(),
            role("resetZoom"),
            role("zoomIn"),
            role("zoomOut"),
            separator(),
            role("togglefullscreen"),
        )
    })

    val windowMenu: dynamic = js("({})")
    windowMenu.role = "window"
    windowMenu.submenu = if (isMac) {
        arrayOf(role("minimize"), role("zoom"), separator(), role("front"))
    } else {
        arrayOf(role("minimize"), role("close"))
    }
    template.add(windowMenu)

    val helpMenu: dynamic = js("({})")
    helpMenu.role = "help"
    helpMenu.submenu = if (isMac) {
        emptyArray<dynamic>()
    } else {
        arrayOf(clickItem("Hotkeys…", "Ctrl+/", showHotkeys))
    }
    template.add(helpMenu)

    Menu.setApplicationMenu(Menu.buildFromTemplate(template.toTypedArray()))
}

private inline fun menuItem(label: String, submenu: () -> Array<dynamic>): dynamic {
    val item: dynamic = js("({})")
    item.label = label
    item.submenu = submenu()
    return item
}

private fun role(role: String): dynamic {
    val item: dynamic = js("({})")
    item.role = role
    return item
}

private fun separator(): dynamic = js("({ type: 'separator' })")

private fun clickItem(label: String, accelerator: String, click: () -> Unit): dynamic {
    val item: dynamic = js("({})")
    item.label = label
    item.accelerator = accelerator
    item.click = click
    return item
}
