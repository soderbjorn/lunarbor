package se.soderbjorn.notegrow.platform

/**
 * Platform abstraction over the local filesystem operations Notegrow needs.
 *
 * The auto-promotion feature splits one outline across many `.nogr` files in a
 * directory tree that mirrors the outline shape, so the surface goes beyond
 * "read/write a single file" — directories must be created and listed,
 * promoted subtrees may be renamed when the user edits the parent bullet's
 * title, and demoted subtrees must be cleaned up.
 *
 * All paths are absolute and use `/` as separator on every platform; the JS
 * actual normalises to host conventions internally if needed.
 */
expect class FileSystem() {
    /** Creates [path] (and any missing ancestors); no-op if it already exists. */
    suspend fun ensureDirectory(path: String)

    /** Returns the file's UTF-8 contents, or `null` if the file does not exist. */
    suspend fun readFileIfExists(path: String): String?

    /** Writes [content] to [path], creating any missing parent directories. */
    suspend fun writeFile(path: String, content: String)

    /** Deletes the file at [path]. No-op if the file does not exist. */
    suspend fun deleteFile(path: String)

    /** Removes [path] only if it is an empty directory. No-op otherwise. */
    suspend fun deleteDirectoryIfEmpty(path: String)

    /** Atomically renames or moves a file from [from] to [to]. */
    suspend fun moveFile(from: String, to: String)

    /** Atomically renames or moves a directory (with all contents) from [from] to [to]. */
    suspend fun moveDirectory(from: String, to: String)

    /**
     * Returns the basenames of every direct entry (files and subdirectories) in
     * [path], or an empty list if the directory does not exist. Does not recurse.
     */
    suspend fun listDirectory(path: String): List<String>
}
