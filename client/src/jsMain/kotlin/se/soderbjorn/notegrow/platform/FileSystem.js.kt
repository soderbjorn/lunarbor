package se.soderbjorn.notegrow.platform

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise

actual class FileSystem actual constructor() {
    private val api: dynamic
        get() {
            val bridge = window.asDynamic().noteApi
            if (bridge == null || bridge == undefined) {
                error("noteApi bridge unavailable; FileSystem only works inside the Electron shell")
            }
            return bridge
        }

    actual suspend fun ensureDirectory(path: String) {
        (api.ensureDirectory(path) as Promise<Unit>).await()
    }

    actual suspend fun readFileIfExists(path: String): String? {
        val result = (api.readFileIfExists(path) as Promise<String?>).await()
        return result
    }

    actual suspend fun writeFile(path: String, content: String) {
        (api.writeFile(path, content) as Promise<Unit>).await()
    }
}
