/*
 * FileSystem.js.kt (jsMain)
 * Renderer-side [PlatformFileSystem] backed by the Electron `noteApi` bridge.
 */

package se.soderbjorn.treefacts.platform

import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

/**
 * Electron implementation of [FileSystem]: every call goes over the
 * preload script's `noteApi` IPC bridge to the main process, which runs
 * Node `fs.promises`. Fails fast outside the Electron shell.
 */
class PlatformFileSystem : FileSystem {
    private val api: dynamic
        get() {
            val bridge = window.asDynamic().noteApi
            if (bridge == null || bridge == undefined) {
                error("noteApi bridge unavailable; FileSystem only works inside the Electron shell")
            }
            return bridge
        }

    override suspend fun ensureDirectory(path: String) {
        (api.ensureDirectory(path) as Promise<Unit>).await()
    }

    override suspend fun readFileIfExists(path: String): String? {
        val result = (api.readFileIfExists(path) as Promise<String?>).await()
        return result
    }

    override suspend fun writeFile(path: String, content: String) {
        (api.writeFile(path, content) as Promise<Unit>).await()
    }

    override suspend fun writeBinary(path: String, bytes: ByteArray) {
        // ByteArray on Kotlin/JS compiles to an Int8Array; the Electron
        // bridge expects Uint8Array (signed/unsigned reinterpretation of
        // the same buffer). Sharing the buffer avoids a copy.
        val int8 = bytes.unsafeCast<Int8Array>()
        val uint8 = Uint8Array(int8.buffer, int8.byteOffset, int8.length)
        (api.writeBinary(path, uint8) as Promise<Unit>).await()
    }

    override suspend fun deleteFile(path: String) {
        (api.deleteFile(path) as Promise<Unit>).await()
    }

    override suspend fun deleteDirectoryIfEmpty(path: String) {
        (api.deleteDirectoryIfEmpty(path) as Promise<Unit>).await()
    }

    override suspend fun moveFile(from: String, to: String) {
        (api.moveFile(from, to) as Promise<Unit>).await()
    }

    override suspend fun moveDirectory(from: String, to: String) {
        (api.moveDirectory(from, to) as Promise<Unit>).await()
    }

    override suspend fun listDirectory(path: String): List<String> {
        val result = (api.listDirectory(path) as Promise<Array<String>>).await()
        return result.toList()
    }

    override suspend fun listDirectoryEntries(path: String): List<VaultDirectoryEntry> {
        val result = (api.listDirectoryEntries(path) as Promise<Array<dynamic>>).await()
        val out = ArrayList<VaultDirectoryEntry>(result.size)
        for (raw in result) {
            val name = raw.name as String
            val isDir = raw.isDirectory as Boolean
            val mtimeMs: Double = (raw.lastModifiedMs as? Double) ?: 0.0
            out += VaultDirectoryEntry(
                name = name,
                isDirectory = isDir,
                lastModifiedMs = mtimeMs.toLong(),
            )
        }
        return out
    }
}
