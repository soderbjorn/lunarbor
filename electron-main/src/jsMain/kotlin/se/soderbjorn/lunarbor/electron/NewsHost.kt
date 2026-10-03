/* NewsHost.kt — main-process side of the "News & updates" bell.
 *
 * The renderer (`web/.../main/NewsUpdates.kt`, over the common
 * `NewsUpdatesBackingViewModel`) does the checking; this file gives it
 * what only the main process has:
 *
 *  - The running build's version: [versionArguments] passes
 *    `--lunarbor-version-name=` (`app.getVersion()`) and
 *    `--lunarbor-version-code=` (`CFBundleVersion` — see [readBundleVersion])
 *    to the preload, which exposes them as `noteApi.appVersionName` /
 *    `appVersionCode`.
 *  - The bell's state between launches, `lunarbor-news.json` beside
 *    `lunarbor-backup.json` (`{ dismissedNewsIds, dismissedUpdateVersionCode,
 *    lastCheckEpochMillis }`), over `lunarbor:getNewsState` /
 *    `lunarbor:setNewsState`. App-scoped: a vault switch keeps it.
 *  - `lunarbor:openExternalUrl`, which opens a news / download link in the
 *    system browser — `https:` only.
 */
package se.soderbjorn.lunarbor.electron

/**
 * Registers the news IPC handlers and remembers where the state file is.
 *
 * ### Callers
 * - `main` (ElectronMain.kt): [install] once, before the first window.
 * - `createWindow`: [versionArguments] for the preload.
 */
internal object NewsHost {
    private var settingsPath: () -> String = { "" }

    /**
     * Registers `lunarbor:getNewsState`, `lunarbor:setNewsState` and
     * `lunarbor:openExternalUrl`.
     *
     * @param settingsFile Path of `lunarbor-news.json`.
     */
    fun install(settingsFile: () -> String) {
        settingsPath = settingsFile
        ipcMain.handle("lunarbor:getNewsState") { _, _ -> readState() }
        ipcMain.handle("lunarbor:setNewsState") { _, json ->
            writeState(json as? String)
            Unit
        }
        ipcMain.handle("lunarbor:openExternalUrl") { _, url ->
            val text = url as? String
            if (text != null && isOpenableUrl(text)) {
                shell.openExternal(text)
            } else {
                console.error("lunarbor:openExternalUrl refused", url)
            }
            Unit
        }
    }

    /**
     * The `additionalArguments` that carry the version to the preload.
     *
     * @return `--lunarbor-version-name=…` and `--lunarbor-version-code=…`
     *   (the code is blank when it cannot be read; the renderer then reads
     *   it as 0).
     */
    fun versionArguments(): List<String> = listOf(
        "--lunarbor-version-name=${js("encodeURIComponent")(app.getVersion())}",
        "--lunarbor-version-code=${js("encodeURIComponent")(readBundleVersion())}",
    )

    /** The state file's text, or `null` when there is none (the renderer starts fresh). */
    private fun readState(): String? = try {
        fsSync.readFileSync(settingsPath(), "utf8")
    } catch (_: Throwable) {
        null
    }

    /** Writes [json] (write-tmp + rename); ignores anything that is not a JSON object. */
    private fun writeState(json: String?) {
        if (json == null || !isJsonObject(json)) return
        try {
            val target = settingsPath()
            val opts: dynamic = js("({})")
            opts.recursive = true
            fsSync.mkdirSync(pathModule.dirname(target), opts)
            val tmp = "$target.tmp"
            fsSync.writeFileSync(tmp, json)
            fsSync.renameSync(tmp, target)
        } catch (e: Throwable) {
            console.error("lunarbor:setNewsState failed", e)
        }
    }

    private fun isJsonObject(json: String): Boolean = try {
        val parsed: dynamic = js("JSON.parse(json)")
        parsed != null && jsTypeOf(parsed) == "object" && !(js("Array.isArray(parsed)") as Boolean)
    } catch (_: Throwable) {
        false
    }

    /**
     * The running build's version code: `CFBundleVersion` from the packaged
     * app's `Info.plist` (electron-builder strips `build` from the bundled
     * `package.json`), else `build.mac.bundleVersion` from the source
     * `package.json` in dev.
     */
    private fun readBundleVersion(): String =
        (if (app.isPackaged) readBundleVersionFromPlist() else "").ifEmpty { readBundleVersionFromPackageJson() }

    /** `…/Lunarbor.app/Contents/Resources/app.asar` → `…/Contents/Info.plist`. */
    private fun readBundleVersionFromPlist(): String = try {
        val plist = pathModule.join(app.getAppPath(), "..", "..", "Info.plist")
        val raw = fsSync.readFileSync(plist, "utf8")
        Regex("""<key>CFBundleVersion</key>\s*<string>([^<]*)</string>""").find(raw)?.groupValues?.get(1)?.trim() ?: ""
    } catch (_: Throwable) {
        ""
    }

    /** `electron/package.json`'s `build.mac.bundleVersion` (dev launches). */
    private fun readBundleVersionFromPackageJson(): String = try {
        val raw = fsSync.readFileSync(pathModule.join(app.getAppPath(), "package.json"), "utf8")
        val parsed: dynamic = js("JSON.parse(raw)")
        (parsed.build?.mac?.bundleVersion as? String) ?: ""
    } catch (_: Throwable) {
        ""
    }
}

/**
 * Whether `lunarbor:openExternalUrl` may open [url]: an absolute `https:`
 * URL. Everything else (`file:`, `javascript:`, app schemes) is refused.
 */
internal fun isOpenableUrl(url: String): Boolean =
    Regex("^https://[^\\s/?#]+", RegexOption.IGNORE_CASE).containsMatchIn(url)
