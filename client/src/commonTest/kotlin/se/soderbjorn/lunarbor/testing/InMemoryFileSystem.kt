/*
 * InMemoryFileSystem.kt (commonTest)
 * Map-backed [FileSystem] for tests: the storage rules in NoteRepository
 * and Document run against it exactly as they would against disk.
 */

package se.soderbjorn.lunarbor.testing

import se.soderbjorn.lunarbor.platform.FileSystem
import se.soderbjorn.lunarbor.platform.VaultDirectoryEntry

/**
 * In-memory file system. Paths are absolute, `/`-separated. Directories
 * are tracked explicitly so empty folders exist, like on a real disk.
 * [moveDirectory] fails when the destination exists, as `rename(2)` does
 * for a non-empty directory — so a test catches any save that would
 * rename onto an occupied path.
 */
class InMemoryFileSystem : FileSystem {
    /** File path → content. Binary files are stored as `"<binary n>"`. */
    val files: MutableMap<String, String> = linkedMapOf()

    /** Every directory path that exists. */
    val dirs: MutableSet<String> = linkedSetOf()

    private fun parentOf(path: String) = path.substringBeforeLast('/', "")

    private fun mkdirs(path: String) {
        var p = path
        while (p.isNotEmpty() && dirs.add(p)) p = parentOf(p)
    }

    override suspend fun ensureDirectory(path: String) = mkdirs(path)

    override suspend fun readFileIfExists(path: String): String? = files[path]

    override suspend fun writeFile(path: String, content: String) {
        check(path !in dirs) { "writeFile onto a directory: $path" }
        mkdirs(parentOf(path))
        files[path] = content
    }

    override suspend fun writeBinary(path: String, bytes: ByteArray) {
        mkdirs(parentOf(path))
        files[path] = "<binary ${bytes.size}>"
        binaries[path] = bytes
    }

    /** Raw bytes of files written with [writeBinary], by path. */
    val binaries: MutableMap<String, ByteArray> = linkedMapOf()

    override suspend fun fileSize(path: String): Long? =
        binaries[path]?.size?.toLong() ?: files[path]?.encodeToByteArray()?.size?.toLong()

    override suspend fun readBinaryIfExists(path: String): ByteArray? =
        binaries[path] ?: files[path]?.encodeToByteArray()

    override suspend fun deleteFile(path: String) {
        files.remove(path)
    }

    override suspend fun deleteDirectoryIfEmpty(path: String) {
        if (path !in dirs) return
        val prefix = "$path/"
        if (files.keys.any { it.startsWith(prefix) } || dirs.any { it.startsWith(prefix) }) return
        dirs.remove(path)
    }

    override suspend fun moveFile(from: String, to: String) {
        val content = files.remove(from) ?: error("moveFile: no such file $from")
        mkdirs(parentOf(to))
        files[to] = content
    }

    override suspend fun moveDirectory(from: String, to: String) {
        check(from in dirs) { "moveDirectory: no such directory $from" }
        check(to !in dirs && to !in files) { "moveDirectory: destination exists $to" }
        check(!to.startsWith("$from/")) { "moveDirectory into itself: $from -> $to" }
        mkdirs(parentOf(to))
        val prefix = "$from/"
        val movedFiles = files.keys.filter { it.startsWith(prefix) }
        for (f in movedFiles) files[to + f.substring(from.length)] = files.remove(f)!!
        val movedDirs = dirs.filter { it == from || it.startsWith(prefix) }
        dirs.removeAll(movedDirs.toSet())
        for (d in movedDirs) dirs += to + d.substring(from.length)
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
        for (f in files.keys) if (f.startsWith(prefix) && '/' !in f.substring(prefix.length)) {
            out += VaultDirectoryEntry(f.substring(prefix.length), isDirectory = false)
        }
        return out
    }

    /**
     * Snapshot of the tree under [root] as sorted relative paths: folders
     * end in `/`, files are `path = content`. Handy for exact assertions.
     */
    fun tree(root: String): List<String> {
        val prefix = "$root/"
        val out = ArrayList<String>()
        for (d in dirs) if (d.startsWith(prefix)) out += d.substring(prefix.length) + "/"
        for ((f, c) in files) if (f.startsWith(prefix)) out += f.substring(prefix.length) + " = " + c
        return out.sorted()
    }

    /** Content of the file at [root]/[rel], or `null`. */
    fun read(root: String, rel: String): String? = files["$root/$rel"]
}
