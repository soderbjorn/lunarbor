/*
 * DemoFileSystem.kt (jsMain)
 * --------------------------
 * The in-memory [FileSystem] behind demo mode (see DemoMode.kt): the
 * vault is a map of paths to bytes living in the browser tab, seeded from
 * the bundled `demo-vault.js` and lost on reload. NoteRepository runs
 * against it exactly as it runs against disk, so every feature — saves,
 * folder moves, the trash, links, search — works unchanged.
 *
 * It also stands in for the Electron `lunarbor-asset://` protocol: images,
 * drawings and HTML pages are served from memory as `blob:` URLs
 * ([assetUrl]), cached per file version.
 *
 * Paths are absolute and `/`-separated, like every FileSystem's.
 */

package se.soderbjorn.lunarbor.demo

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import se.soderbjorn.lunarbor.platform.FileSystem
import se.soderbjorn.lunarbor.platform.VaultDirectoryEntry
import kotlin.js.Date

/**
 * Map-backed [FileSystem] for the browser demo. Directories are tracked
 * explicitly, so empty folders exist as on a real disk; [moveDirectory]
 * refuses an existing destination, as `rename(2)` does.
 *
 * Constructed once by [loadDemoVault] and provided to the DI graph by
 * `JsAppGraph.provideFileSystem` while demo mode is on.
 */
class DemoFileSystem : FileSystem {
    /** One stored file: its bytes and when it was last written. */
    private class Entry(val bytes: ByteArray, val modifiedMs: Long)

    /** Absolute file path → content. */
    private val files = LinkedHashMap<String, Entry>()

    /** Every directory that exists, by absolute path. */
    private val dirs = LinkedHashSet<String>()

    /** `blob:` URLs handed out by [assetUrl], by path; revoked when the file changes. */
    private val blobUrls = HashMap<String, String>()

    private fun parentOf(path: String) = path.substringBeforeLast('/', "")

    private fun mkdirs(path: String) {
        var p = path
        while (p.isNotEmpty() && dirs.add(p)) p = parentOf(p)
    }

    private fun put(path: String, bytes: ByteArray) {
        check(path !in dirs) { "write onto a directory: $path" }
        mkdirs(parentOf(path))
        files[path] = Entry(bytes, Date.now().toLong())
        dropBlobUrl(path)
    }

    private fun dropBlobUrl(path: String) {
        blobUrls.remove(path)?.let { URL.revokeObjectURL(it) }
    }

    /**
     * Seeds one file without the bookkeeping a live write does. Called by
     * [loadDemoVault] while unpacking the bundled vault.
     *
     * @param path Absolute path of the file.
     * @param bytes Its content.
     * @param modifiedMs Its last-modified time, so the folder contents list
     *   shows plausible dates.
     */
    fun seed(path: String, bytes: ByteArray, modifiedMs: Long) {
        mkdirs(parentOf(path))
        files[path] = Entry(bytes, modifiedMs)
    }

    /** Creates the folder [path] (and its ancestors) while seeding. */
    fun seedDirectory(path: String) = mkdirs(path)

    /**
     * A URL the page can load the file at [absPath] from — what the
     * Electron `lunarbor-asset://` protocol is in the desktop app. A
     * `blob:` URL of the file's current bytes with a MIME type from its
     * extension, created on first use and reused until the file changes.
     *
     * Called by `lunarborAssetUrl` (images, drawings, HTML pages) and by
     * `MainViewModel.openInDefaultApp` (other files open in a new tab).
     *
     * @param absPath Absolute path of the file.
     * @return The URL, or `null` when no such file exists.
     */
    fun assetUrl(absPath: String): String? {
        blobUrls[absPath]?.let { return it }
        val entry = files[absPath] ?: return null
        val int8 = entry.bytes.unsafeCast<Int8Array>()
        val blob = Blob(
            arrayOf(Uint8Array(int8.buffer, int8.byteOffset, int8.length)),
            BlobPropertyBag(type = mimeTypeOf(absPath)),
        )
        return URL.createObjectURL(blob).also { blobUrls[absPath] = it }
    }

    override suspend fun ensureDirectory(path: String) = mkdirs(path)

    override suspend fun readFileIfExists(path: String): String? =
        files[path]?.bytes?.decodeToString()

    override suspend fun writeFile(path: String, content: String) = put(path, content.encodeToByteArray())

    override suspend fun writeBinary(path: String, bytes: ByteArray) = put(path, bytes.copyOf())

    override suspend fun fileSize(path: String): Long? = files[path]?.bytes?.size?.toLong()

    override suspend fun readBinaryIfExists(path: String): ByteArray? = files[path]?.bytes?.copyOf()

    override suspend fun deleteFile(path: String) {
        files.remove(path)
        dropBlobUrl(path)
    }

    override suspend fun deleteDirectoryIfEmpty(path: String) {
        if (path !in dirs) return
        val prefix = "$path/"
        if (files.keys.any { it.startsWith(prefix) } || dirs.any { it.startsWith(prefix) }) return
        dirs.remove(path)
    }

    override suspend fun moveFile(from: String, to: String) {
        val entry = files.remove(from) ?: error("moveFile: no such file $from")
        dropBlobUrl(from)
        mkdirs(parentOf(to))
        files[to] = entry
        dropBlobUrl(to)
    }

    override suspend fun moveDirectory(from: String, to: String) {
        check(from in dirs) { "moveDirectory: no such directory $from" }
        check(to !in dirs && to !in files) { "moveDirectory: destination exists $to" }
        check(!to.startsWith("$from/")) { "moveDirectory into itself: $from -> $to" }
        mkdirs(parentOf(to))
        val prefix = "$from/"
        for (f in files.keys.filter { it.startsWith(prefix) }) {
            files[to + f.substring(from.length)] = files.remove(f)!!
            dropBlobUrl(f)
        }
        val moved = dirs.filter { it == from || it.startsWith(prefix) }
        dirs.removeAll(moved.toSet())
        for (d in moved) dirs += to + d.substring(from.length)
    }

    override suspend fun listDirectory(path: String): List<String> =
        listDirectoryEntries(path).map { it.name }

    override suspend fun listDirectoryEntries(path: String): List<VaultDirectoryEntry> {
        if (path !in dirs) return emptyList()
        val prefix = "$path/"
        val out = ArrayList<VaultDirectoryEntry>()
        for (d in dirs) if (d.startsWith(prefix) && '/' !in d.substring(prefix.length)) {
            out += VaultDirectoryEntry(d.substring(prefix.length), isDirectory = true)
        }
        for ((f, entry) in files) if (f.startsWith(prefix) && '/' !in f.substring(prefix.length)) {
            out += VaultDirectoryEntry(f.substring(prefix.length), isDirectory = false, lastModifiedMs = entry.modifiedMs)
        }
        return out
    }

    private companion object {
        /** MIME types by lower-case extension, for [assetUrl]'s blobs. */
        val MIME_TYPES = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "svg" to "image/svg+xml",
            "html" to "text/html;charset=utf-8",
            "htm" to "text/html;charset=utf-8",
            "css" to "text/css;charset=utf-8",
            "js" to "text/javascript;charset=utf-8",
            "json" to "application/json",
            "excalidraw" to "application/json",
            "md" to "text/plain;charset=utf-8",
            "txt" to "text/plain;charset=utf-8",
            "csv" to "text/plain;charset=utf-8",
            "pdf" to "application/pdf",
        )

        fun mimeTypeOf(path: String): String =
            MIME_TYPES[path.substringAfterLast('/').substringAfterLast('.', "").lowercase()]
                ?: "application/octet-stream"
    }
}
