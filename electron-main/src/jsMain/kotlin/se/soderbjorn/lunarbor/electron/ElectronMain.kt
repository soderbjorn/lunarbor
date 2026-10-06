/* ElectronMain.kt — Electron main process, written in Kotlin/JS.
 *
 * Direct port of the previous electron/main.js. Owns:
 *  - Run-path resolution from `LUNARBOR_VAULT` / `LUNARBOR_LOCAL_DATA`
 *    (see RunPaths.kt): the vault root handed to the renderer via
 *    `--lunarbor-vault=`, and — for an isolated run — `userData`, the
 *    single-instance lock and every settings file moved under the
 *    `LUNARBOR_LOCAL_DATA` directory instead of the Darkness folder.
 *  - Per-OS persistence path resolution. UI settings split across the
 *    cross-app `<DarknessDir>/themes.json` (v2 custom theme definitions
 *    shared with every Darkness app) and the per-app
 *    `<DarknessDir>/lunarbor.json` (selections + UI prefs). Layout
 *    state stays per-app under `<DarknessDir>/Lunarbor/`.
 *  - Atomic JSON I/O (write-tmp + rename) for both UI-settings files
 *    plus `layout-state.json` and `layout-toolkit-state.json`.
 *  - `darkness:*` IPC handlers (`readUiSettings` / `writeUiSettings` /
 *    `readLayoutState` / `writeLayoutState`).
 *  - File-watcher on the shared darkness ui-settings file, debounced
 *    + self-write-suppressed, broadcast over `darkness:uiSettingsChanged`.
 *  - `lunarbor:*` file-ops IPC handlers powering the renderer's
 *    [se.soderbjorn.lunarbor.platform.FileSystem] (ensureDirectory,
 *    readFileIfExists, writeFile, deleteFile, deleteDirectoryIfEmpty,
 *    moveFile, moveDirectory, listDirectory, listDirectoryEntries), plus
 *    `lunarbor:openPath`, which opens a vault file in the system's
 *    default app via `shell.openPath` (TRF-7; see VaultFilePath.kt).
 *  - The vault watcher (VaultWatcher.kt): changes made outside the app
 *    are sent to the renderer over `lunarbor:vaultChanged`; the file-op
 *    handlers record this app's own writes so they are filtered out.
 *  - `lunarbor:revealPath`, which shows a vault entry in Finder via
 *    `shell.showItemInFolder` (folder-entry menu), confined like openPath.
 *  - The vault setting: `lunarbor:getVault` / `chooseVaultFolder` /
 *    `setVault` back the App settings sidebar's Vault section. The picked
 *    root is persisted as `vaultPath` in `lunarbor.json` (owned by this
 *    process — renderer ui-settings writes never change it), read at
 *    startup by [resolveRunPaths], and applied at runtime by recreating
 *    the window against the new vault.
 *  - App settings → Agent access: the MCP endpoint, its settings and the
 *    request relay to the renderer (McpHttpServer.kt).
 *  - App settings → Lunicle: named Lunicle connections (base URL +
 *    token, `lunarbor-lunicle.json`) and the API request relay
 *    (LunicleHost.kt).
 *  - App settings → Backup: zipping the vault into a backup folder, on
 *    demand or on a schedule, with the file-op handlers held meanwhile
 *    (VaultBackup.kt, ZipWriter.kt).
 *  - The topbar's News & updates bell: the running version for the
 *    renderer, `lunarbor-news.json`, and opening its links (NewsHost.kt).
 *  - A watchdog on the thread pool behind every async file operation,
 *    offering a restart when it stops answering (FsWatchdog.kt).
 *  - BrowserWindow setup with the boot-time `--darkness-settings=` /
 *    `--darkness-layout-state=` argument injection the renderer's
 *    preload script picks up.
 *  - Single-instance lock + second-instance focus relay.
 *  - External-link routing (window.open + will-navigate → shell.openExternal).
 *  - macOS application menu with a "Hotkeys…" item that fires the
 *    `lunarbor:show-hotkeys` IPC the renderer subscribes to.
 *
 * The renderer-side contract (`globalThis.darknessApi`,
 * `globalThis.noteApi`, `globalThis.__darknessSettings`,
 * `globalThis.__darknessLayoutState`, the `lunarbor:show-hotkeys`
 * channel) is unchanged from the JS predecessor — preload.js still
 * exposes those bindings and only the bytes-on-disk path changed
 * languages. */
package se.soderbjorn.lunarbor.electron

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import se.soderbjorn.lunula.core.SHARED_THEMES_KEYS
import se.soderbjorn.lunula.core.mergeSharedThemes

private const val APP_NAME = "Lunarbor"

/**
 * App-name stem for the per-app UI-settings file. Lives at
 * `<Darkness>/lunarbor.json` next to the cross-app `themes.json`.
 * Lower-kebab-case to match the toolkit's `defaultAppUiSettingsPath`
 * convention (used by termtastic too).
 */
private const val APP_NAME_KEBAB = "lunarbor"

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
 * `themes.json` / `lunarbor.json` since this is a window-cosmetic
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
 * UI-settings file (`lunarbor.json`). Compared against fresh reads
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

/**
 * This run's storage locations, resolved from `LUNARBOR_VAULT` /
 * `LUNARBOR_LOCAL_DATA` once at the top of [main] (before anything reads a
 * settings path or takes the single-instance lock). See [RunPaths].
 */
private lateinit var runPaths: RunPaths

/**
 * Electron main-process entry point. Resolves [runPaths], isolates
 * `userData` when `LUNARBOR_LOCAL_DATA` is set, takes the single-instance
 * lock (keyed on `userData`, so isolated runs never collide with each other
 * or with the maintainer's own app), then wires IPC and opens the window.
 */
fun main() {
    app.setName(APP_NAME)

    val resolveWith = { settingsVault: String? ->
        resolveRunPaths(
            vaultEnv = process.env[ENV_LUNARBOR_VAULT] as String?,
            localDataEnv = process.env[ENV_LUNARBOR_LOCAL_DATA] as String?,
            homeDir = osModule.homedir(),
            resolve = { pathModule.resolve(it) },
            join = { a, b -> pathModule.join(a, b) },
            settingsVault = settingsVault,
        )
    }
    // Two passes: the settings file's location depends only on
    // LUNARBOR_LOCAL_DATA, so the first pass finds it, and the second
    // applies the user-picked `vaultPath` stored there.
    runPaths = resolveWith(null)
    runPaths = resolveWith(readPersistedVaultPath())
    // Must precede `requestSingleInstanceLock()` and `whenReady`: Electron
    // keys the lock on `userData`, and every later `getPath("userData")`
    // (e.g. [chromePrefsPath]) must see the isolated directory.
    runPaths.userDataDir?.let { dir ->
        ensureDirSync(dir)
        app.setPath("userData", dir)
    }
    ensureDirSync(runPaths.vaultDir)
    console.log(runPaths.vaultLogLine())
    console.log(runPaths.dataLogLine(darknessDataDir()))

    if (!app.requestSingleInstanceLock()) {
        app.quit()
        return
    }

    // Privileged-scheme registration must happen before `whenReady`, so
    // `<img src="lunarbor-asset://…">` in the renderer behaves like an
    // `https://` URL: not blocked by `webSecurity`, no mixed-content
    // warnings, fetch+XHR work.
    registerLunarborAssetScheme()

    registerIpcHandlers()
    // App settings → Agent access: the MCP endpoint (off unless turned on).
    McpHost.install({ sharedDarknessPath("$APP_NAME_KEBAB-mcp.json") }) { mainWindow }
    // App settings → Lunicle: connections + the API request relay.
    LunicleHost.install { sharedDarknessPath("$APP_NAME_KEBAB-lunicle.json") }
    // App settings → Backup: zips of the vault and their schedule.
    BackupHost.install({ sharedDarknessPath("$APP_NAME_KEBAB-backup.json") }, { runPaths.vaultDir }) { mainWindow }
    // The topbar's News & updates bell: its state file, the version, links.
    NewsHost.install { sharedDarknessPath("$APP_NAME_KEBAB-news.json") }
    // Offers a restart if async file access stops answering (FsWatchdog.kt).
    FsWatchdog.start { mainWindow }

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
        applyDevDockIcon()
        NewsHost.applyAboutPanelVersion()
        installLunarborAssetProtocol()
        buildAppMenu()
        createWindow()
    }
}

/**
 * Absolute path to the app icon shipped under `electron/icons/`. Only
 * meaningful in dev — `icons/` is the build-resources directory that
 * electron-builder consumes at packaging time and does *not* copy into
 * the packaged app, so this path resolves only while running unpackaged.
 */
private fun devIconPngPath(): String =
    pathModule.join(app.getAppPath(), "icons", "icon.png")

/**
 * Dev-only: set the macOS Dock icon at runtime so `electron .` shows the
 * Lunarbor icon instead of the stock Electron diamond.
 *
 * The packaged (electron-builder) bundle already carries the icon in its
 * `Info.plist`, so this is skipped when [ElectronApp.isPackaged]. It's
 * also a no-op off macOS (`app.dock` is undefined there — Linux/Windows
 * get their dev icon from the BrowserWindow `icon` option instead) and
 * best-effort: a missing/unreadable file must never block startup.
 */
private fun applyDevDockIcon() {
    if (app.isPackaged) return
    val dock = app.asDynamic().dock ?: return
    try {
        dock.setIcon(devIconPngPath())
    } catch (_: Throwable) {
        // Cosmetic; fall back to whatever icon the bundle provides.
    }
}

/**
 * Declare the `lunarbor-asset` scheme as privileged. The renderer uses
 * URLs of the form `lunarbor-asset://local/<absPath>` to load image files
 * from outside the app bundle — without this declaration, Electron's
 * `webSecurity` would block the load.
 */
private fun registerLunarborAssetScheme() {
    val privileges: dynamic = js("({})")
    privileges.secure = true
    privileges.standard = true
    privileges.supportFetchAPI = true
    privileges.bypassCSP = true
    val scheme: dynamic = js("({})")
    scheme.scheme = "lunarbor-asset"
    scheme.privileges = privileges
    protocol.registerSchemesAsPrivileged(arrayOf(scheme))
}

/**
 * Wire `lunarbor-asset://local/<absPath>` URLs to filesystem reads. The
 * renderer encodes the absolute path of the vault asset into the URL's
 * pathname component (e.g. `lunarbor-asset://local/Users/foo/lunarbor-db/Images/x.png`),
 * so this handler URL-decodes the pathname and delegates to Electron's
 * built-in `net.fetch` against a `file://` URL.
 *
 * Uses `protocol.handle` (Web Fetch style, Electron 25+) rather than
 * the legacy callback-style `protocol.registerFileProtocol`. The
 * legacy method is deprecated in Electron 25+ and silently fails to
 * load assets in some configurations on Electron 32.
 */
private fun installLunarborAssetProtocol() {
    protocol.handle("lunarbor-asset") { request ->
        GlobalScope.promise<dynamic> {
            val urlString = request.url as String
            // The renderer builds `lunarbor-asset://local/<encoded abs
            // path>`: Chromium parses a standard scheme's first segment
            // as the host, so `local` is a placeholder host and the
            // pathname is the absolute path.
            val parsed: dynamic = try { js("new URL(urlString)") } catch (_: Throwable) { null }
            val rawPath = (parsed?.pathname as? String).orEmpty()
            val absPath = try {
                js("decodeURI")(rawPath) as String
            } catch (_: Throwable) {
                rawPath
            }
            val mime = mimeForExtension(absPath)
            console.log("lunarbor-asset: url=$urlString path=$absPath")
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
                console.error("lunarbor-asset: read failed", absPath, msg)
                val init: dynamic = js("({})")
                init.status = 404
                val headers: dynamic = js("({})")
                headers["Content-Type"] = "text/plain"
                init.headers = headers
                js("new Response('lunarbor-asset: failed to read ' + absPath + ' — ' + msg, init)")
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
        // HTML pages shown in a pane (HtmlViewer) and what they load.
        lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html; charset=utf-8"
        lower.endsWith(".css") -> "text/css; charset=utf-8"
        lower.endsWith(".js") || lower.endsWith(".mjs") -> "text/javascript; charset=utf-8"
        lower.endsWith(".json") -> "application/json"
        lower.endsWith(".txt") || lower.endsWith(".md") -> "text/plain; charset=utf-8"
        lower.endsWith(".woff2") -> "font/woff2"
        lower.endsWith(".woff") -> "font/woff"
        lower.endsWith(".ttf") -> "font/ttf"
        lower.endsWith(".mp4") -> "video/mp4"
        lower.endsWith(".webm") -> "video/webm"
        lower.endsWith(".mp3") -> "audio/mpeg"
        lower.endsWith(".pdf") -> "application/pdf"
        else -> "application/octet-stream"
    }
}

/* --- Path resolution -------------------------------------------------- */

/**
 * Cross-app shared darkness themes file path: holds the v2 custom
 * themes (`PersistKeys.THEME_V2_CUSTOM`) — read/written by every
 * Darkness app on this machine. Lives directly under the Darkness data
 * dir, *not* under any per-app sub-directory.
 */
private fun sharedThemesPath(): String =
    sharedDarknessPath("themes.json")

/**
 * Per-app UI-settings file path: holds lunarbor's selected theme slots,
 * appearance, fonts, sizes, app-specific toggles. Sibling of
 * [sharedThemesPath] (flat in the Darkness data dir, not under a
 * per-app sub-directory) so it lines up with what termtastic and the
 * toolkit's `defaultAppUiSettingsPath("lunarbor")` would resolve to.
 */
private fun appUiSettingsPath(): String =
    sharedDarknessPath("$APP_NAME_KEBAB.json")

/**
 * Resolve a path relative to this run's settings directory.
 *
 * - Isolated run (`LUNARBOR_LOCAL_DATA` set): `<localDataDir>/<filename>`.
 * - Otherwise the OS-conventional Darkness data directory (the same root
 *   every Darkness app on this machine uses) — see [darknessDataDir].
 *
 * @param filename Leaf filename, e.g. `themes.json`.
 */
private fun sharedDarknessPath(filename: String): String =
    pathModule.join(settingsRootDir(), filename)

/**
 * Root under which [sharedDarknessPath] and [perAppPath] resolve: the
 * `LUNARBOR_LOCAL_DATA` directory for an isolated run, else
 * [darknessDataDir]. Keeping every settings path funnelled through here is
 * what guarantees an isolated run never reads or writes the shared
 * Darkness folder.
 */
private fun settingsRootDir(): String =
    runPaths.localDataDir ?: darknessDataDir()

/**
 * The OS-conventional shared Darkness data directory.
 *
 * - macOS: `~/Library/Application Support/Darkness`
 * - Windows: `%APPDATA%\Darkness`
 * - Linux: `$XDG_CONFIG_HOME/darkness` (defaults to `~/.config/darkness`).
 */
private fun darknessDataDir(): String {
    val home = osModule.homedir()
    return when (process.platform) {
        "darwin" ->
            pathModule.join(home, "Library", "Application Support", "Darkness")
        "win32" -> {
            val appData = (process.env.APPDATA as String?)
                ?.takeIf { it.isNotEmpty() }
                ?: pathModule.join(home, "AppData", "Roaming")
            pathModule.join(appData, "Darkness")
        }
        else -> {
            val xdg = (process.env.XDG_CONFIG_HOME as String?)
                ?.takeIf { it.isNotEmpty() }
                ?: pathModule.join(home, ".config")
            pathModule.join(xdg, "darkness")
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
 * from [defaultAppLayoutStatePath] (which holds lunarbor's typed
 * tab list under `PersistKeys.LAYOUT`).
 */
private fun defaultAppLayoutToolkitStatePath(): String =
    perAppPath("layout-toolkit-state.json")

/**
 * Resolve a per-app file under `<settingsRoot>/Lunarbor/` (lower-case
 * `lunarbor` on Linux, matching the historical layout). The settings root
 * is the `LUNARBOR_LOCAL_DATA` directory for an isolated run — see
 * [settingsRootDir].
 *
 * @param filename Leaf filename, e.g. `layout-state.json`.
 */
private fun perAppPath(filename: String): String {
    val subdir = if (process.platform == "darwin" || process.platform == "win32") APP_NAME
    else APP_NAME.lowercase()
    return pathModule.join(settingsRootDir(), subdir, filename)
}

/**
 * `mkdir -p` [dir] synchronously. Used at startup for the vault and the
 * isolated `userData` directory; failures are logged rather than thrown so a
 * permissions problem surfaces in the renderer's first file op instead of a
 * silent crash before any window exists.
 */
private fun ensureDirSync(dir: String) {
    try {
        val opts: dynamic = js("({})")
        opts.recursive = true
        fsSync.mkdirSync(dir, opts)
    } catch (err: Throwable) {
        console.error("Could not create directory", dir, err.message)
    }
}

/* --- Persisted vault path ---------------------------------------------- */

/**
 * The user-picked vault root stored as [SETTINGS_KEY_VAULT_PATH] in the
 * per-app settings file, or `null` when none is stored (or the file is
 * missing / unreadable).
 *
 * Called by [main] while resolving [runPaths] and by the
 * `darkness:writeUiSettings` handler, which keeps the stored value.
 */
private fun readPersistedVaultPath(): String? {
    val obj: dynamic = parseJsonObjectOrEmpty(readSyncOrNull(appUiSettingsPath()))
    return (obj[SETTINGS_KEY_VAULT_PATH] as? String)?.takeIf { it.isNotBlank() }
}

/**
 * [perAppJson] with its [SETTINGS_KEY_VAULT_PATH] replaced by [vaultPath]
 * (or removed when `null`), so a renderer write never changes or drops
 * the vault setting.
 *
 * @param perAppJson The per-app half of a renderer ui-settings blob.
 * @param vaultPath The value currently on disk.
 * @return The JSON object string to write to `lunarbor.json`.
 */
private fun withPersistedVaultPath(perAppJson: String, vaultPath: String?): String {
    val obj: dynamic = parseJsonObjectOrEmpty(perAppJson)
    if (vaultPath != null) obj[SETTINGS_KEY_VAULT_PATH] = vaultPath
    else js("delete obj[\"vaultPath\"]")
    return js("JSON.stringify(obj)") as String
}

/**
 * Stores [dir] as [SETTINGS_KEY_VAULT_PATH] in the per-app settings file,
 * keeping every other key. Called by the `lunarbor:setVault` handler.
 *
 * @param dir Absolute path of the new vault root.
 */
private suspend fun persistVaultPath(dir: String) {
    val current = readSyncOrNull(appUiSettingsPath())
    val obj: dynamic = parseJsonObjectOrEmpty(current)
    obj[SETTINGS_KEY_VAULT_PATH] = dir
    lastWrittenAppUiSettings = atomicWriteUtf8(appUiSettingsPath(), js("JSON.stringify(obj, null, 2)") as String)
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
 * per-app file's keys win on collisions — they're lunarbor-local
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
    installVaultWatcher(runPaths.vaultDir) { mainWindow }
    // The new window's renderer announces itself before MCP requests go to it.
    McpHost.onWindowCreated()
    BackupHost.onWindowCreated()
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
    // renderer can't recover this from the toolkit's persisted theme
    // state — the custom-title-bar boolean isn't part of any persister
    // key, so it would otherwise be lost across restarts. The toolkit's
    // `autoApplyCustomTitleBarBodyClass` consumes this preload-exposed
    // value to set `dt-custom-titlebar` synchronously on the first frame.
    additionalArguments += "--darkness-custom-titlebar=${chromePrefs.customTitleBar}"
    // Vault root for the renderer's `NoteRepository`, exposed by
    // preload.js as `noteApi.vaultRoot` and read in `JsAppGraph`.
    additionalArguments += "--lunarbor-vault=${js("encodeURIComponent")(runPaths.vaultDir)}"
    // Version name + code for the news & update check (NewsHost.kt),
    // exposed by preload.js as `noteApi.appVersionName` / `appVersionCode`.
    additionalArguments += NewsHost.versionArguments()

    val options: dynamic = js("({})")
    options.width = 1024
    options.height = 720
    options.title = APP_NAME
    // Dev window/taskbar icon on Linux & Windows (macOS ignores this and
    // uses the Dock icon set in [applyDevDockIcon] / the bundle icon).
    // Guarded to dev — the packaged bundle supplies its own icon.
    if (!app.isPackaged) options.icon = devIconPngPath()
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
    // `setDtMacFullscreenBodyClass` in lunula). Listeners are
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
    // per-app `lunarbor.json` so custom-theme *definitions* can be
    // reused across apps while *selections* (slot picks, fonts, etc.)
    // stay app-local. Partition logic lives in toolkit-core's
    // [SHARED_THEMES_KEYS]; we mirror it here at the disk boundary.
    ipcMain.handle("darkness:writeUiSettings") { _, json ->
        GlobalScope.promise {
            val (sharedJson, rendererPerAppJson) = partitionUiSettingsJson(json as String)
            // `vaultPath` belongs to the main process (see
            // `lunarbor:setVault`): keep what is on disk, whatever the
            // renderer's blob — possibly booted before a change — says.
            val perAppJson = withPersistedVaultPath(rendererPerAppJson, readPersistedVaultPath())
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
            // practice. Cross-app custom-theme definition writes are
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
    // `lunarbor.json`, layout-state files) so the reload is purely
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

    // ── lunarbor:*Vault (App settings → Vault) ─────────────────
    // The vault root is fixed for a window's lifetime (the renderer's
    // NoteRepository is built around it), so changing it persists the
    // new root as `vaultPath` in `lunarbor.json` and recreates the
    // window, which boots against the new vault. The renderer has
    // already flushed every edit and rebased its pane locations before
    // it calls `setVault`.
    ipcMain.handle("lunarbor:getVault") { _, _ ->
        val info: dynamic = js("({})")
        info.path = runPaths.vaultDir
        info.locked = runPaths.isVaultLocked
        info
    }
    ipcMain.handle("lunarbor:chooseVaultFolder") { _, _ ->
        GlobalScope.promise<String?> {
            val w = mainWindow ?: return@promise null
            val options: dynamic = js("({})")
            options.title = "Choose vault folder"
            options.buttonLabel = "Use as vault"
            options.defaultPath = runPaths.vaultDir
            options.properties = arrayOf("openDirectory", "createDirectory")
            val result = dialog.showOpenDialog(w, options).await()
            if (result.canceled == true) return@promise null
            (result.filePaths as Array<String>).firstOrNull()
        }
    }
    ipcMain.handle("lunarbor:setVault") { _, dir ->
        GlobalScope.promise<String> {
            if (runPaths.isVaultLocked) return@promise "The vault is set by $ENV_LUNARBOR_VAULT"
            val abs = pathModule.resolve(dir as String)
            val isDir = try {
                fsSync.statSync(abs).isDirectory() as Boolean
            } catch (_: Throwable) {
                false
            }
            if (!isDir) return@promise "Not a folder: $abs"
            if (abs == runPaths.vaultDir) return@promise ""
            persistVaultPath(abs)
            runPaths = runPaths.withVault(abs)
            console.log(runPaths.vaultLogLine())
            val old = mainWindow
            createWindow()
            if (old != null && !old.isDestroyed()) old.destroy()
            ""
        }
    }

    // ── lunarbor:openPath (TRF-7) ──────────────────────────────
    // Opens a vault file in the system's default app ("other file" rows
    // of the folder contents list). The renderer sends a vault-relative
    // path; [vaultFilePath] refuses anything that would leave the vault.
    ipcMain.handle("lunarbor:openPath") { _, pathRel ->
        GlobalScope.promise {
            val abs = vaultFilePath(runPaths.vaultDir, pathRel as String)
            if (abs == null) {
                console.error("lunarbor:openPath refused", pathRel)
                return@promise "refused: outside the vault"
            }
            val error = shell.openPath(abs).await()
            if (error.isNotEmpty()) console.error("lunarbor:openPath failed", abs, error)
            error
        }
    }

    // ── lunarbor:revealPath ────────────────────────────────────
    // "Reveal in Finder" in a folder-entry menu: shows the vault entry
    // selected in its parent folder. Same vault confinement as openPath.
    ipcMain.handle("lunarbor:revealPath") { _, pathRel ->
        val abs = vaultFilePath(runPaths.vaultDir, pathRel as String)
        if (abs == null) {
            console.error("lunarbor:revealPath refused", pathRel)
            "refused: outside the vault"
        } else {
            shell.showItemInFolder(abs)
            ""
        }
    }

    // ── lunarbor:* (renderer-side FileSystem operations) ────────
    // Every handler that changes the vault runs through
    // [VaultWriteGate.write], so it waits while a backup zips the vault.
    ipcMain.handle("lunarbor:ensureDirectory") { _, dirPath ->
        GlobalScope.promise { VaultWriteGate.write {
            val opts: dynamic = js("({})")
            opts.recursive = true
            vaultChanges.recordTouch(dirPath as String, js("Date.now()") as Double)
            fsPromises.mkdir(dirPath, opts).await()
        } }
    }
    ipcMain.handle("lunarbor:readFileIfExists") { _, filePath ->
        GlobalScope.promise {
            val text = readJsonOrNull(filePath as String).await()
            if (text != null) vaultChanges.recordRead(filePath, text)
            text
        }
    }
    ipcMain.handle("lunarbor:writeFile") { _, filePath, content ->
        GlobalScope.promise { VaultWriteGate.write {
            val opts: dynamic = js("({})")
            opts.recursive = true
            fsPromises.mkdir(pathModule.dirname(filePath as String), opts).await()
            vaultChanges.recordText(filePath, content as String)
            fsPromises.writeFile(filePath, content).await()
        } }
    }
    // Binary write path — used by paste-an-image (the renderer hands us
    // the clipboard image as a Uint8Array). Crosses the IPC boundary
    // efficiently because Electron transfers typed arrays as
    // Buffer-backed ArrayBuffers without re-encoding.
    ipcMain.handle("lunarbor:writeBinary") { _, filePath, bytes ->
        GlobalScope.promise { VaultWriteGate.write {
            val opts: dynamic = js("({})")
            opts.recursive = true
            fsPromises.mkdir(pathModule.dirname(filePath as String), opts).await()
            // `bytes` arrives as a Uint8Array; `fsPromises.writeFile`
            // accepts that directly (it's a TypedArray, which fs treats
            // as raw bytes — no encoding parameter needed).
            vaultChanges.recordTouch(filePath, js("Date.now()") as Double)
            fsPromises.writeFile(filePath, bytes).await()
        } }
    }
    // Binary read + size — the agent `read` tool (McpTools) for images and
    // other non-text files. Only reads; nothing is recorded.
    ipcMain.handle("lunarbor:readBinary") { _, filePath ->
        GlobalScope.promise<dynamic> {
            try { fsPromises.readFile(filePath as String).await() } catch (_: Throwable) { null }
        }
    }
    ipcMain.handle("lunarbor:fileSize") { _, filePath ->
        GlobalScope.promise<dynamic> {
            try {
                val st: dynamic = fsPromises.stat(filePath as String).await()
                if (st.isFile() as Boolean) st.size else null
            } catch (_: Throwable) { null }
        }
    }
    ipcMain.handle("lunarbor:deleteFile") { _, filePath ->
        GlobalScope.promise<Unit> { VaultWriteGate.write {
            try {
                vaultChanges.recordTouch(filePath as String, js("Date.now()") as Double)
                fsPromises.unlink(filePath).await()
            } catch (err: Throwable) {
                val code = (err.asDynamic().code as String?)
                if (code != "ENOENT") throw err
            }
        } }
    }
    ipcMain.handle("lunarbor:deleteDirectoryIfEmpty") { _, dirPath ->
        GlobalScope.promise<Unit> { VaultWriteGate.write {
            try {
                vaultChanges.recordTouch(dirPath as String, js("Date.now()") as Double)
                fsPromises.rmdir(dirPath).await()
            } catch (err: Throwable) {
                val code = (err.asDynamic().code as String?)
                if (code != "ENOENT" && code != "ENOTEMPTY" && code != "EEXIST") throw err
            }
        } }
    }
    ipcMain.handle("lunarbor:moveFile") { _, from, to ->
        GlobalScope.promise { VaultWriteGate.write {
            val opts: dynamic = js("({})")
            opts.recursive = true
            fsPromises.mkdir(pathModule.dirname(to as String), opts).await()
            vaultChanges.recordTouch(from as String, js("Date.now()") as Double)
            vaultChanges.recordTouch(to, js("Date.now()") as Double)
            fsPromises.rename(from, to).await()
        } }
    }
    ipcMain.handle("lunarbor:moveDirectory") { _, from, to ->
        GlobalScope.promise { VaultWriteGate.write {
            val opts: dynamic = js("({})")
            opts.recursive = true
            fsPromises.mkdir(pathModule.dirname(to as String), opts).await()
            vaultChanges.recordTouch(from as String, js("Date.now()") as Double)
            vaultChanges.recordTouch(to, js("Date.now()") as Double)
            fsPromises.rename(from, to).await()
        } }
    }
    ipcMain.handle("lunarbor:listDirectory") { _, dirPath ->
        GlobalScope.promise {
            try {
                fsPromises.readdir(dirPath as String).await()
            } catch (err: Throwable) {
                val code = (err.asDynamic().code as String?)
                if (code == "ENOENT") emptyArray<String>() else throw err
            }
        }
    }
    ipcMain.handle("lunarbor:listDirectoryEntries") { _, dirPath ->
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
 * `lunarbor.json`. On each (debounced) change, re-reads both files,
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
            w.webContents.send("lunarbor:show-hotkeys")
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
