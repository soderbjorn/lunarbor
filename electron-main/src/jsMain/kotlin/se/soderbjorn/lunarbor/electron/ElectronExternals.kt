/* ElectronExternals.kt
 * Minimal `external` declarations for the Electron APIs the main
 * process touches: app lifecycle, BrowserWindow, ipcMain, Menu, shell,
 * dialog.
 * Loaded as a CommonJS module via `@JsModule("electron")`. */
@file:JsModule("electron")
@file:JsNonModule

package se.soderbjorn.lunarbor.electron

import kotlin.js.Promise

external val app: ElectronApp
external val ipcMain: IpcMain
external val shell: Shell
external val protocol: Protocol
external val net: ElectronNet
external val dialog: Dialog

external interface Dialog {
    /**
     * Native open dialog attached to [window] as a sheet. Resolves to
     * `{ canceled: Boolean, filePaths: Array<String> }`. Used by the
     * `lunarbor:chooseVaultFolder` IPC handler with the `openDirectory`
     * property to pick a new vault root.
     */
    fun showOpenDialog(window: BrowserWindow, options: dynamic): Promise<dynamic>
}

external interface Protocol {
    /**
     * Declare a custom scheme as privileged before `app.whenReady()`. The
     * `secure: true, standard: true, supportFetchAPI: true` flag set is
     * what makes `<img src="lunarbor-asset://…">` work the same way an
     * `https://` URL would: no `webSecurity` blocking, no
     * mixed-content warnings, fetch+XHR allowed.
     */
    fun registerSchemesAsPrivileged(customSchemes: Array<dynamic>)

    /**
     * Modern Web-Fetch-style protocol handler (Electron 25+). The
     * handler receives a `Request` and returns a `Response` (or a
     * Promise resolving to one). Replaces the deprecated
     * callback-style `registerFileProtocol`.
     */
    fun handle(scheme: String, handler: (request: dynamic) -> dynamic)
}

external interface ElectronNet {
    /** Web-Fetch-style request executed in the main process. Used in
     *  [Protocol.handle] handlers to read files via `file://` URLs. */
    fun fetch(url: String): kotlin.js.Promise<dynamic>
}

external interface ElectronApp {
    fun setName(name: String)
    fun requestSingleInstanceLock(): Boolean
    fun quit()
    fun on(event: String, listener: (dynamic, dynamic) -> Unit): ElectronApp
    fun whenReady(): Promise<Unit>
    fun getPath(name: String): String

    /**
     * Override a special directory. Called with `"userData"` before
     * `requestSingleInstanceLock()` for a `LUNARBOR_LOCAL_DATA` run, since
     * Electron keys the lock on `userData`.
     */
    fun setPath(name: String, path: String)

    /**
     * Absolute path to the app's root directory. In dev this is the
     * `electron/` folder (where `package.json`'s `main` lives); when
     * packaged it points inside the `.asar`/app bundle. Used to locate
     * the dev-only dock icon under `build/`.
     */
    fun getAppPath(): String

    /**
     * `true` once running inside a packaged (electron-builder) bundle,
     * `false` under `electron .` in dev. The packaged bundle already
     * carries the correct name + icon from its `Info.plist`, so the
     * dev-only branding fallbacks are gated on this being `false`.
     */
    val isPackaged: Boolean

    /**
     * The app's version name (`CFBundleShortVersionString` when packaged,
     * `package.json`'s `version` in dev). Sent to the renderer for the
     * news & update check (NewsHost.kt).
     */
    fun getVersion(): String

    /**
     * Sets what the macOS About panel (`role("about")`) shows. Without it
     * the panel reads the running bundle's `Info.plist`, which under
     * `electron .` in dev is `Electron.app`'s (Electron's own version).
     * Called by `NewsHost.applyAboutPanelVersion`.
     *
     * @param options `{ applicationVersion, version }` and friends.
     */
    fun setAboutPanelOptions(options: dynamic)
}

external interface IpcMain {
    fun handle(channel: String, listener: (event: dynamic, arg: dynamic) -> dynamic)
    fun handle(channel: String, listener: (event: dynamic, arg1: dynamic, arg2: dynamic) -> dynamic)
}

external interface Shell {
    fun openExternal(url: String): Promise<Unit>

    /**
     * Opens the file at the absolute [path] in the desktop's default app
     * for its type. Resolves to `""` on success, or an error message.
     */
    fun openPath(path: String): Promise<String>

    /** Shows the file or folder at the absolute [path] selected in its parent folder (Finder on macOS). */
    fun showItemInFolder(path: String)
}

@JsName("BrowserWindow")
external class BrowserWindow(options: dynamic = definedExternally) {
    val webContents: WebContents
    fun isDestroyed(): Boolean
    fun isMinimized(): Boolean
    fun isFullScreen(): Boolean
    fun restore()
    fun focus()
    fun loadFile(filePath: String): Promise<Unit>
    fun destroy()
}

external interface WebContents {
    fun setWindowOpenHandler(handler: (details: dynamic) -> dynamic)
    fun on(event: String, listener: (event: dynamic, url: String) -> Unit)
    fun getURL(): String
    fun send(channel: String, vararg args: dynamic)
}

@JsName("Menu")
external object Menu {
    fun setApplicationMenu(menu: dynamic)
    fun buildFromTemplate(template: Array<dynamic>): dynamic
}
