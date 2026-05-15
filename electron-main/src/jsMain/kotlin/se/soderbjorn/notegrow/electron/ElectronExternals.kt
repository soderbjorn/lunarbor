/* ElectronExternals.kt
 * Minimal `external` declarations for the Electron APIs the main
 * process touches: app lifecycle, BrowserWindow, ipcMain, Menu, shell.
 * Loaded as a CommonJS module via `@JsModule("electron")`. */
@file:JsModule("electron")
@file:JsNonModule

package se.soderbjorn.notegrow.electron

import kotlin.js.Promise

external val app: ElectronApp
external val ipcMain: IpcMain
external val shell: Shell

external interface ElectronApp {
    fun setName(name: String)
    fun requestSingleInstanceLock(): Boolean
    fun quit()
    fun on(event: String, listener: (dynamic, dynamic) -> Unit): ElectronApp
    fun whenReady(): Promise<Unit>
    fun getPath(name: String): String
}

external interface IpcMain {
    fun handle(channel: String, listener: (event: dynamic, arg: dynamic) -> dynamic)
    fun handle(channel: String, listener: (event: dynamic, arg1: dynamic, arg2: dynamic) -> dynamic)
}

external interface Shell {
    fun openExternal(url: String): Promise<Unit>
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
