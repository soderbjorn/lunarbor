/* NodeExternals.kt
 * Thin `external` shims for the Node modules the main process uses:
 * fs (sync read), fs/promises (async writes/reads/mkdir/rename/unlink/
 * readdir/rmdir), os (homedir), path (join/dirname/basename), process
 * (platform/env/argv) — plus `fs.watch` for the shared-themes file
 * watcher. Loaded via `kotlin.js.require` because the bundle's CommonJS
 * output emits literal `require("…")` calls at runtime; that matches
 * what Electron's main process expects. */
package se.soderbjorn.notegrow.electron

import kotlin.js.Promise

private val nodeRequire: dynamic = js("require")

internal val fsPromises: FsPromises = nodeRequire("fs/promises").unsafeCast<FsPromises>()
internal val fsSync: FsSync = nodeRequire("fs").unsafeCast<FsSync>()
internal val osModule: OsModule = nodeRequire("os").unsafeCast<OsModule>()
internal val pathModule: PathModule = nodeRequire("path").unsafeCast<PathModule>()

external interface FsPromises {
    fun mkdir(path: String, options: dynamic = definedExternally): Promise<dynamic>
    fun writeFile(path: String, data: dynamic): Promise<Unit>
    fun rename(oldPath: String, newPath: String): Promise<Unit>
    fun readFile(path: String, encoding: String): Promise<String>
    fun unlink(path: String): Promise<Unit>
    fun rmdir(path: String): Promise<Unit>
    fun readdir(path: String): Promise<Array<String>>
    fun readdir(path: String, options: dynamic): Promise<Array<dynamic>>
}

external interface FsSync {
    fun readFileSync(path: String, encoding: String): String
    fun readFileSync(path: String): dynamic
    fun mkdirSync(path: String, options: dynamic = definedExternally)
    fun watch(path: String, listener: (eventType: String, filename: String?) -> Unit): FsWatcher
}

external interface FsWatcher {
    fun close()
}

external interface OsModule {
    fun homedir(): String
}

external interface PathModule {
    fun join(vararg parts: String): String
    fun dirname(p: String): String
    fun basename(p: String): String
}

internal external val process: NodeProcess

external interface NodeProcess {
    val platform: String
    val env: dynamic
    val argv: Array<String>
}
