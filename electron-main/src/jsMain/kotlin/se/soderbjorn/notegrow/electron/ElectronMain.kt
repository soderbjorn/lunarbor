/* ElectronMain.kt — Electron main process, written in Kotlin/JS.
 *
 * Direct port of the previous electron/main.js. Owns:
 *  - Per-OS persistence path resolution. UI settings split across the
 *    cross-app `<DarknessDir>/themes.json` (theme/scheme definitions
 *    shared with every Darkness app) and the per-app
 *    `<DarknessDir>/notegrow.json` (selections + UI prefs). Layout
 *    state stays per-app under `<DarknessDir>/Notegrow/`.
 *  - Atomic JSON I/O (write-tmp + rename) for both UI-settings files
 *    plus `layout-state.json` and `layout-toolkit-state.json`.
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import se.soderbjorn.darkness.core.SHARED_THEMES_KEYS
import se.soderbjorn.darkness.core.mergeSharedThemes

private const val APP_NAME = "Notegrow"

/**
 * App-name stem for the per-app UI-settings file. Lives at
 * `<Darkness>/notegrow.json` next to the cross-app `themes.json`.
 * Lower-kebab-case to match the toolkit's `defaultAppUiSettingsPath`
 * convention (used by termtastic too).
 */
private const val APP_NAME_KEBAB = "notegrow"

private var mainWindow: BrowserWindow? = null

/**
 * Cached window-chrome preference (custom title bar on/off). Read once
 * from disk at startup so [createWindow] can pick the right
 * `titleBarStyle` synchronously, and updated by the
 * `darkness:setCustomTitleBar` IPC handler on toggle. Defaults to
 * `false` (native OS title bar) on first launch.
 *
 * Stored on disk at `<userData>/electron-chrome.json` — Electron's
 * per-app `userData` directory, distinct from the cross-app
 * `themes.json` / `notegrow.json` since this is a window-cosmetic
 * concern that must be readable *before* the renderer comes up
 * (`titleBarStyle` is fixed at BrowserWindow construction).
 */
private var chromePrefs: ChromePrefs = ChromePrefs(customTitleBar = false)

private data class ChromePrefs(val customTitleBar: Boolean)

private fun chromePrefsPath(): String =
    pathModule.join(app.getPath("userData"), "electron-chrome.json")

/**
 * Read `electron-chrome.json` synchronously. Returns a default
 * (`customTitleBar = false`) on any failure — missing file, parse
 * error, permissions — since a cosmetic preference should never block
 * window creation.
 */
private fun loadChromePrefs(): ChromePrefs = try {
    val raw = fsSync.readFileSync(chromePrefsPath(), "utf8")
    val parsed: dynamic = js("JSON.parse(raw)")
    ChromePrefs(customTitleBar = parsed.customTitleBar == true)
} catch (_: Throwable) {
    ChromePrefs(customTitleBar = false)
}

/**
 * Write [prefs] to `electron-chrome.json`. Silently swallows errors —
 * if the cache write fails the value still drives the current session
 * via the in-memory [chromePrefs] and the next launch falls back to the
 * default (matches termtastic's tolerance for the same case).
 */
private fun saveChromePrefs(prefs: ChromePrefs) {
    try {
        val opts: dynamic = js("({})")
        opts.recursive = true
        fsSync.mkdirSync(pathModule.dirname(chromePrefsPath()), opts)
        val payload: dynamic = js("({})")
        payload.customTitleBar = prefs.customTitleBar
        fsSync.writeFileSync(chromePrefsPath(), js("JSON.stringify(payload)") as String)
    } catch (_: Throwable) {
        // Cosmetic; the next launch just forgets the preference.
    }
}

/**
 * Bytes most recently written by this Electron process to the per-app
 * UI-settings file (`notegrow.json`). Compared against fresh reads
 * from [installSharedThemesWatcher] so self-induced fs.watch events
 * don't loop back to the renderer as "external" changes. `null` until
 * the first write (the watcher tolerates that — first write wins).
 */
private var lastWrittenAppUiSettings: dynamic = null

/** Active fs.watch handle for the cross-app `themes.json`. */
private var sharedThemesWatcher: FsWatcher? = null
/** Active fs.watch handle for the per-app `<appName>.json`. */
private var appUiSettingsWatcher: FsWatcher? = null

/**
 * Coalesce timers for the two fs.watch debounces. Some editors fire
 * `change` twice per save; we collapse all events inside a 200 ms
 * window into one read+broadcast cycle. Separate timers per file so
 * the two watch streams don't smother each other.
 */
private var sharedThemesDebounce: dynamic = null
private var appUiSettingsDebounce: dynamic = null

fun main() {
    app.setName(APP_NAME)

    if (!app.requestSingleInstanceLock()) {
        app.quit()
        return
    }

    // Privileged-scheme registration must happen before `whenReady`, so
    // `<img src="notegrow-asset://…">` in the renderer behaves like an
    // `https://` URL: not blocked by `webSecurity`, no mixed-content
    // warnings, fetch+XHR work.
    registerNotegrowAssetScheme()

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
        // Load the window-chrome cache so [createWindow] picks the right
        // `titleBarStyle` synchronously. Deferred until `whenReady`
        // because `app.getPath("userData")` is only valid afterwards.
        chromePrefs = loadChromePrefs()
        installNotegrowAssetProtocol()
        buildAppMenu()
        createWindow()
    }
}

/**
 * Declare the `notegrow-asset` scheme as privileged. The renderer uses
 * URLs of the form `notegrow-asset://<absPath>` to load image files
 * from outside the app bundle — without this declaration, Electron's
 * `webSecurity` would block the load.
 */
private fun registerNotegrowAssetScheme() {
    val privileges: dynamic = js("({})")
    privileges.secure = true
    privileges.standard = true
    privileges.supportFetchAPI = true
    privileges.bypassCSP = true
    val scheme: dynamic = js("({})")
    scheme.scheme = "notegrow-asset"
    scheme.privileges = privileges
    protocol.registerSchemesAsPrivileged(arrayOf(scheme))
}

/**
 * Wire `notegrow-asset://<absPath>` URLs to filesystem reads. The
 * renderer encodes the absolute path of the vault asset into the URL's
 * pathname component (e.g. `notegrow-asset:///Users/foo/notegrow-db/Images/x.png`),
 * so this handler URL-decodes the pathname and delegates to Electron's
 * built-in `net.fetch` against a `file://` URL.
 *
 * Uses `protocol.handle` (Web Fetch style, Electron 25+) rather than
 * the legacy callback-style `protocol.registerFileProtocol`. The
 * legacy method is deprecated in Electron 25+ and silently fails to
 * load assets in some configurations on Electron 32.
 */
private fun installNotegrowAssetProtocol() {
    protocol.handle("notegrow-asset") { request ->
        GlobalScope.promise<dynamic> {
            val urlString = request.url as String
            // Chromium's standard-scheme URL parser interprets the
            // first segment after `//` as the host, so naïve
            // `notegrow-asset:///abs/path` URLs end up with host=`abs`,
            // path=`/path` by the time they reach us. Defend against
            // every parse outcome by reconstructing the absolute path
            // from both host AND pathname components.
            //
            // Renderer constructs URLs as
            // `notegrow-asset://local/<encoded abs path>` (since the
            // fix below); for backwards compatibility we also accept
            // the older `notegrow-asset:///<encoded abs path>` form
            // by gluing host + path back together when host is empty.
            val parsed: dynamic = try { js("new URL(urlString)") } catch (_: Throwable) { null }
            val host = (parsed?.host as? String).orEmpty()
            val rawPath = (parsed?.pathname as? String).orEmpty()
            // Strip a leading `/local` placeholder host (or any host)
            // and treat the remaining pathname as the absolute path.
            val absPath = try {
                js("decodeURI")(rawPath) as String
            } catch (_: Throwable) {
                rawPath
            }
            val mime = mimeForExtension(absPath)
            console.log("notegrow-asset: url=$urlString host=$host path=$absPath")
            try {
                val bytes: dynamic = fsPromises.readFile(absPath).await()
                val init: dynamic = js("({})")
                val headers: dynamic = js("({})")
                headers["Content-Type"] = mime
                init.headers = headers
                init.status = 200
                js("new Response(bytes, init)")
            } catch (err: Throwable) {
                val msg = (err.asDynamic().message as? String) ?: err.toString()
                console.error("notegrow-asset: read failed", absPath, msg)
                val init: dynamic = js("({})")
                init.status = 404
                val headers: dynamic = js("({})")
                headers["Content-Type"] = "text/plain"
                init.headers = headers
                js("new Response('notegrow-asset: failed to read ' + absPath + ' — ' + msg, init)")
            }
        }
    }
}

/** Map a vault asset path's extension to a sensible Content-Type. */
private fun mimeForExtension(path: String): String {
    val lower = path.lowercase()
    return when {
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".svg") -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}

/* --- Path resolution -------------------------------------------------- */

/**
 * Cross-app shared darkness themes file path: holds custom themes,
 * custom schemes, and favorites — read/written by every Darkness app
 * on this machine. Lives directly under the Darkness data dir, *not*
 * under any per-app sub-directory.
 */
private fun sharedThemesPath(): String =
    sharedDarknessPath("themes.json")

/**
 * Per-app UI-settings file path: holds notegrow's selected theme slots,
 * appearance, fonts, sizes, app-specific toggles. Sibling of
 * [sharedThemesPath] (flat in the Darkness data dir, not under a
 * per-app sub-directory) so it lines up with what termtastic and the
 * toolkit's `defaultAppUiSettingsPath("notegrow")` would resolve to.
 */
private fun appUiSettingsPath(): String =
    sharedDarknessPath("$APP_NAME_KEBAB.json")

/**
 * Resolve a path relative to the OS-conventional Darkness data
 * directory (the same root every Darkness app on this machine uses).
 *
 * - macOS: `~/Library/Application Support/Darkness/<filename>`
 * - Windows: `%APPDATA%\Darkness\<filename>`
 * - Linux: `$XDG_CONFIG_HOME/darkness/<filename>` (defaults to
 *   `~/.config/darkness/`).
 */
private fun sharedDarknessPath(filename: String): String {
    val home = osModule.homedir()
    return when (process.platform) {
        "darwin" ->
            pathModule.join(home, "Library", "Application Support", "Darkness", filename)
        "win32" -> {
            val appData = (process.env.APPDATA as String?)
                ?.takeIf { it.isNotEmpty() }
                ?: pathModule.join(home, "AppData", "Roaming")
            pathModule.join(appData, "Darkness", filename)
        }
        else -> {
            val xdg = (process.env.XDG_CONFIG_HOME as String?)
                ?.takeIf { it.isNotEmpty() }
                ?: pathModule.join(home, ".config")
            pathModule.join(xdg, "darkness", filename)
        }
    }
}

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

/**
 * Synchronously read both the cross-app shared themes file and the
 * per-app UI-settings file and return a merged JSON-object string. The
 * per-app file's keys win on collisions — they're notegrow-local
 * choices that should not be overwritten by another Darkness app's
 * edits.
 *
 * Returns null if both files are missing/empty so the renderer can
 * fall back to defaults.
 */
private fun readMergedUiSettingsJsonSync(): String? {
    val sharedRaw = readSyncOrNull(sharedThemesPath())
    val appRaw = readSyncOrNull(appUiSettingsPath())
    if (sharedRaw == null && appRaw == null) return null
    val sharedObj: dynamic = parseJsonObjectOrEmpty(sharedRaw)
    val perAppObj: dynamic = parseJsonObjectOrEmpty(appRaw)
    val merged: dynamic = js("({})")
    val sharedKeys: Array<String> = js("Object.keys(sharedObj)") as Array<String>
    for (k in sharedKeys) merged[k] = sharedObj[k]
    val perAppKeys: Array<String> = js("Object.keys(perAppObj)") as Array<String>
    for (k in perAppKeys) merged[k] = perAppObj[k]
    return js("JSON.stringify(merged)") as String
}

/**
 * Parse [raw] as a JSON object, or return an empty object on null /
 * blank / parse error. Returns a plain JS object (used here as a
 * dictionary).
 */
private fun parseJsonObjectOrEmpty(raw: String?): dynamic {
    if (raw.isNullOrBlank()) return js("({})")
    return try {
        val parsed: dynamic = js("JSON.parse(raw)")
        if (parsed != null && (js("typeof parsed === 'object'") as Boolean) &&
            !(js("Array.isArray(parsed)") as Boolean)
        ) parsed else js("({})")
    } catch (_: Throwable) {
        js("({})")
    }
}

/**
 * Partition a complete UI-settings JSON object into (sharedThemes,
 * appUiSettings) sub-objects according to the toolkit's
 * [SHARED_THEMES_KEYS] classification. Both halves are returned as
 * JSON object strings ready for atomic write. Either may be `"{}"` if
 * the corresponding bucket is empty after partitioning.
 */
/**
 * Per-key merge the outgoing shared-themes JSON string with whatever
 * is currently on disk and return the JSON string ready for an atomic
 * write. Delegates to the toolkit's [mergeSharedThemes] for the actual
 * merge logic — this wrapper just bridges between the JS-side string
 * representation used at the IPC boundary and kotlinx-serialization
 * [JsonObject] inputs.
 */
private fun mergeSharedThemesAtomically(outgoing: String): String {
    val outgoingObj = parseKxJsonObject(outgoing) ?: return outgoing
    val onDiskRaw = readSyncOrNull(sharedThemesPath())
    val onDiskObj = parseKxJsonObject(onDiskRaw) ?: JsonObject(emptyMap())
    val merged = mergeSharedThemes(outgoingObj, onDiskObj)
    return Json.encodeToString(JsonObject.serializer(), merged)
}

private fun parseKxJsonObject(raw: String?): JsonObject? {
    if (raw.isNullOrBlank()) return null
    return runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

private fun partitionUiSettingsJson(json: String): Pair<String, String> {
    val parsed: dynamic = parseJsonObjectOrEmpty(json)
    val sharedOut: dynamic = js("({})")
    val perAppOut: dynamic = js("({})")
    val keys: Array<String> = js("Object.keys(parsed)") as Array<String>
    for (k in keys) {
        if (SHARED_THEMES_KEYS.contains(k)) {
            sharedOut[k] = parsed[k]
        } else {
            perAppOut[k] = parsed[k]
        }
    }
    return (js("JSON.stringify(sharedOut)") as String) to (js("JSON.stringify(perAppOut)") as String)
}

private fun createWindow() {
    val settingsJson = readMergedUiSettingsJsonSync()
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
    // Authoritative window-chrome flag from `electron-chrome.json`. The
    // renderer can't recover this from the toolkit's `ThemeSnapshot` —
    // the stock `ElectronIpcPersister` doesn't round-trip THEME_SNAPSHOT,
    // so the boolean would otherwise be lost across restarts. The toolkit's
    // `autoApplyCustomTitleBarBodyClass` consumes this preload-exposed
    // value to set `dt-custom-titlebar` synchronously on the first frame.
    additionalArguments += "--darkness-custom-titlebar=${chromePrefs.customTitleBar}"

    val options: dynamic = js("({})")
    options.width = 1024
    options.height = 720
    options.title = APP_NAME
    // Honour the persisted window-chrome preference. `hiddenInset` lets
    // the themed top-bar bleed across the title bar on macOS (with the
    // OS traffic-light cluster still floating over the corner); the
    // default style restores the native OS title bar. `titleBarStyle`
    // is immutable post-creation — toggling at runtime destroys this
    // window and creates a new one (see the `darkness:setCustomTitleBar`
    // IPC handler in [registerIpcHandlers]).
    options.titleBarStyle = if (chromePrefs.customTitleBar) "hiddenInset" else "default"
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

    // Forward macOS native fullscreen state to the renderer so the
    // toolkit can drop its 80 px traffic-light reservation while the
    // OS hides the traffic-light cluster (see
    // `setDtMacFullscreenBodyClass` in darkness-toolkit). Listeners are
    // attached on the BrowserWindow itself so they're tied to the
    // window's lifetime.
    w.asDynamic().on("enter-full-screen") {
        if (!w.isDestroyed()) w.webContents.send("fullscreen-changed", true)
    }
    w.asDynamic().on("leave-full-screen") {
        if (!w.isDestroyed()) w.webContents.send("fullscreen-changed", false)
    }
    // Initial-state emit: macOS may relaunch directly into a restored
    // fullscreen Space, so wait for the renderer to be ready and push
    // the current value once. Subsequent changes flow via the events
    // above.
    w.webContents.asDynamic().on("did-finish-load") {
        if (!w.isDestroyed()) w.webContents.send("fullscreen-changed", w.isFullScreen())
    }

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
    //
    // The renderer treats UI settings as a single blob, but on disk we
    // split it across the cross-app shared `themes.json` and the
    // per-app `notegrow.json` so theme/scheme *definitions* can be
    // reused across apps while *selections* (slot picks, fonts, etc.)
    // stay app-local. Partition logic lives in toolkit-core's
    // [SHARED_THEMES_KEYS]; we mirror it here at the disk boundary.
    ipcMain.handle("darkness:writeUiSettings") { _, json ->
        GlobalScope.promise {
            val (sharedJson, perAppJson) = partitionUiSettingsJson(json as String)
            // Read-merge-write on `themes.json`: re-read disk and
            // per-key merge before atomically writing, so a peer
            // Darkness app's additions survive even if our file watcher
            // missed the announcement (Node fs.watch occasionally drops
            // events on macOS).
            val sharedFinal = mergeSharedThemesAtomically(sharedJson)
            atomicWriteUtf8(sharedThemesPath(), sharedFinal)
            val bytes = atomicWriteUtf8(appUiSettingsPath(), perAppJson)
            // Track the per-app bytes for the file watcher's
            // self-write suppression — most renderer-driven writes
            // touch per-app keys (selected theme slot, fonts, …), so
            // suppressing the per-app bounce is what matters in
            // practice. Cross-app theme/scheme definition writes are
            // rarer and a one-event self-bounce on those is harmless.
            lastWrittenAppUiSettings = bytes
        }
    }
    ipcMain.handle("darkness:readUiSettings") { _, _ ->
        GlobalScope.promise<String?> { readMergedUiSettingsJsonSync() }
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

    // Toggle the custom (themed) title bar. `titleBarStyle` is
    // immutable post-creation, so we persist the new value and
    // recreate the BrowserWindow with the requested style. All
    // in-renderer state is reconstructed from disk (`themes.json`,
    // `notegrow.json`, layout-state files) so the reload is purely
    // visual. Idempotent — calls with the unchanged value short-circuit.
    ipcMain.handle("darkness:setCustomTitleBar") { _, enabled ->
        val next = enabled == true
        if (next != chromePrefs.customTitleBar) {
            chromePrefs = ChromePrefs(customTitleBar = next)
            saveChromePrefs(chromePrefs)
            val old = mainWindow
            createWindow()
            if (old != null && !old.isDestroyed()) old.destroy()
        }
        Unit
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
    // Binary write path — used by paste-an-image (the renderer hands us
    // the clipboard image as a Uint8Array). Crosses the IPC boundary
    // efficiently because Electron transfers typed arrays as
    // Buffer-backed ArrayBuffers without re-encoding.
    ipcMain.handle("notegrow:writeBinary") { _, filePath, bytes ->
        GlobalScope.promise {
            val opts: dynamic = js("({})")
            opts.recursive = true
            fsPromises.mkdir(pathModule.dirname(filePath as String), opts).await()
            // `bytes` arrives as a Uint8Array; `fsPromises.writeFile`
            // accepts that directly (it's a TypedArray, which fs treats
            // as raw bytes — no encoding parameter needed).
            fsPromises.writeFile(filePath, bytes).await()
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
                    val isDir = (ed.isDirectory() as Boolean)
                    obj.isDirectory = isDir
                    obj.lastModifiedMs = 0.0
                    if (!isDir) {
                        // Stat each file so the renderer can sort by last edit.
                        // Best-effort — if a file disappears between readdir
                        // and stat, fall back to 0 rather than failing the
                        // whole listing.
                        try {
                            val full = pathModule.join(dirPath as String, ed.name as String)
                            val stat = fsPromises.stat(full).await()
                            obj.lastModifiedMs = (stat.mtimeMs as? Double) ?: 0.0
                        } catch (_: Throwable) {
                            obj.lastModifiedMs = 0.0
                        }
                    }
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
 * Watches both the cross-app shared `themes.json` and the per-app
 * `notegrow.json`. On each (debounced) change, re-reads both files,
 * merges them, and broadcasts the merged JSON to the renderer over
 * `darkness:uiSettingsChanged`. Skips broadcasts whose per-app bytes
 * match what this process just wrote (self-write suppression).
 * Idempotent: closes any prior watchers before installing fresh ones.
 *
 * Some filesystems / sandbox configs reject `fs.watch`; in that case
 * the renderer simply doesn't get live updates (boot-time read still
 * works).
 */
private fun installSharedThemesWatcher() {
    sharedThemesWatcher?.let {
        try { it.close() } catch (_: Throwable) { /* already closed */ }
        sharedThemesWatcher = null
    }
    appUiSettingsWatcher?.let {
        try { it.close() } catch (_: Throwable) { /* already closed */ }
        appUiSettingsWatcher = null
    }
    val sharedTarget = sharedThemesPath()
    val appTarget = appUiSettingsPath()
    val dir = pathModule.dirname(sharedTarget)
    val sharedName = pathModule.basename(sharedTarget)
    val appName = pathModule.basename(appTarget)
    try {
        val mkdirOpts: dynamic = js("({})")
        mkdirOpts.recursive = true
        fsSync.mkdirSync(dir, mkdirOpts)
    } catch (_: Throwable) { /* dir already exists */ }

    val broadcastMerged: () -> Unit = {
        val merged = readMergedUiSettingsJsonSync()
        if (merged != null) {
            val w = mainWindow
            if (w != null && !w.isDestroyed()) {
                w.webContents.send("darkness:uiSettingsChanged", merged)
            }
        }
    }

    val onSharedChange: () -> Unit = {
        sharedThemesDebounce = null
        // Cross-app shared writes don't have a self-bounce track here
        // (rare path; another Darkness app wrote them). Always broadcast.
        broadcastMerged()
    }
    val onAppChange: () -> Unit = appChange@{
        appUiSettingsDebounce = null
        // Suppress self-bounces on the per-app file (the common case
        // for renderer-driven writes).
        val bytes: dynamic = try { fsSync.readFileSync(appTarget) } catch (_: Throwable) { null }
        if (bytes != null) {
            val last = lastWrittenAppUiSettings
            val sameAsSelf = last != null && (bytes.equals(last) as Boolean)
            if (sameAsSelf) return@appChange
        }
        broadcastMerged()
    }

    val watchOne = { fname: String, debounceVar: () -> dynamic, setDebounce: (dynamic) -> Unit, onChange: () -> Unit ->
        // We watch the directory and filter by filename; one watcher
        // can serve both files, but we use two so each can be torn
        // down independently and so the debounce timers don't collide.
        try {
            fsSync.watch(dir) { _, changedName ->
                if (changedName != fname) return@watch
                val pending = debounceVar()
                if (pending != null) js("clearTimeout")(pending)
                setDebounce(js("setTimeout")(onChange, 200))
            }
        } catch (_: Throwable) {
            null
        }
    }
    sharedThemesWatcher = watchOne(sharedName, { sharedThemesDebounce }, { sharedThemesDebounce = it }, onSharedChange)
    appUiSettingsWatcher = watchOne(appName, { appUiSettingsDebounce }, { appUiSettingsDebounce = it }, onAppChange)
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
