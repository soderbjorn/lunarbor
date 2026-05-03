package se.soderbjorn.notegrow.platform

/**
 * Platform abstraction over the local filesystem operations Notegrow needs.
 *
 * The auto-promotion feature splits one outline across many `.md` files in a
 * directory tree that mirrors the outline shape, so the surface goes beyond
 * "read/write a single file" — directories must be created and listed,
 * promoted subtrees may be renamed when the user edits the parent bullet's
 * title, and demoted subtrees must be cleaned up.
 *
 * Each Notegrow-managed file starts with a `notegrow: true` YAML
 * frontmatter marker; `NoteRepository` adds it on every write and strips
 * it on every read. Files without the marker are treated as opaque
 * markdown — Notegrow displays the link to them but never auto-splices
 * or rewrites them, so it's safe to share a directory with hand-authored
 * notes (e.g. an Obsidian vault).
 *
 * All paths are absolute and use `/` as separator on every platform; the JS
 * actual normalises to host conventions internally if needed.
 */
/**
 * One direct entry in a directory listing produced by [FileSystem.listDirectoryEntries].
 * Used by the filesystem-tree footer in the editor view to lazy-load each
 * folder's contents without recursing.
 *
 * @property name Basename of the entry (no leading path).
 * @property isDirectory `true` for subdirectories, `false` for regular files.
 */
data class VaultDirectoryEntry(val name: String, val isDirectory: Boolean)

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

    /**
     * Like [listDirectory] but returns each entry tagged with whether it is a
     * directory. Used by the vault-tree footer to lazy-load one folder at a
     * time without having to probe each child with a follow-up call. Returns
     * an empty list if the directory does not exist. Does not recurse.
     */
    suspend fun listDirectoryEntries(path: String): List<VaultDirectoryEntry>
}
