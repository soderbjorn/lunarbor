/*
 * FileSystem.kt (commonMain)
 * --------------------------
 * Platform abstraction over the local filesystem operations Lunarbor needs.
 *
 * The vault is a tree of folders, one per folder-backed bullet, each holding
 * a `_node.md` outline file (see `NoteRepository`). Keeping that
 * tree in step with the outline needs more than "read/write a single file":
 * directories are created, listed, renamed and moved (so attachments travel
 * with their bullet), and emptied folders are removed.
 *
 * [FileSystem] is a plain interface so commonTest can run the repository
 * against an in-memory implementation. Each platform source set supplies
 * its own `PlatformFileSystem` class (the Electron bridge on JS, stubs
 * elsewhere), constructed by that platform's DI graph. `NoteRepository` is
 * the only production caller.
 *
 * All paths are absolute and use `/` as separator on every platform.
 */

package se.soderbjorn.lunarbor.platform

/**
 * One direct entry in a directory listing produced by [FileSystem.listDirectoryEntries].
 * Used by `NoteRepository` (folder listings for the folder contents list,
 * the save planner) to read one folder at a time without recursing.
 *
 * @property name Basename of the entry (no leading path).
 * @property isDirectory `true` for subdirectories, `false` for regular files.
 * @property lastModifiedMs Last-modified timestamp in milliseconds since the
 *   Unix epoch. `0` when the platform cannot provide one (or for directories
 *   where the timestamp is not meaningful for sort purposes).
 */
data class VaultDirectoryEntry(
    val name: String,
    val isDirectory: Boolean,
    val lastModifiedMs: Long = 0L,
)

/**
 * The filesystem operations `NoteRepository` performs.
 *
 * ### Implementations
 * - `PlatformFileSystem` in each platform source set (Electron IPC bridge
 *   on JS; stubs elsewhere).
 * - An in-memory map-backed fake in commonTest.
 */
interface FileSystem {
    /** Creates [path] (and any missing ancestors); no-op if it already exists. */
    suspend fun ensureDirectory(path: String)

    /** Returns the file's UTF-8 contents, or `null` if the file does not exist. */
    suspend fun readFileIfExists(path: String): String?

    /** Writes [content] to [path], creating any missing parent directories. */
    suspend fun writeFile(path: String, content: String)

    /**
     * Writes raw [bytes] to [path], creating any missing parent
     * directories. Used by paste-an-image.
     */
    suspend fun writeBinary(path: String, bytes: ByteArray)

    /**
     * The file's size in bytes, or `null` when it does not exist (or is a
     * directory). Used by the agent `read` tool before reading a file whole.
     * Platforms without it report `null`.
     */
    suspend fun fileSize(path: String): Long? = null

    /**
     * The file's raw bytes, or `null` when it does not exist. Used by the
     * agent `read` tool for images and other binary files. Platforms
     * without it report `null`.
     */
    suspend fun readBinaryIfExists(path: String): ByteArray? = null

    /** Deletes the file at [path]. No-op if the file does not exist. */
    suspend fun deleteFile(path: String)

    /** Removes [path] only if it is an empty directory. No-op otherwise. */
    suspend fun deleteDirectoryIfEmpty(path: String)

    /** Atomically renames or moves a file from [from] to [to]. */
    suspend fun moveFile(from: String, to: String)

    /**
     * Atomically renames or moves a directory (with all contents) from
     * [from] to [to], creating [to]'s parent directories first. [to] must
     * not exist yet.
     */
    suspend fun moveDirectory(from: String, to: String)

    /**
     * Returns the basenames of every direct entry (files and subdirectories) in
     * [path], or an empty list if the directory does not exist. Does not recurse.
     */
    suspend fun listDirectory(path: String): List<String>

    /**
     * Like [listDirectory] but returns each entry tagged with whether it is a
     * directory. Returns an empty list if the directory does not exist. Does
     * not recurse.
     */
    suspend fun listDirectoryEntries(path: String): List<VaultDirectoryEntry>

    /**
     * Starts reporting changes made under [root] by anything but this
     * app: [onChange] gets the absolute paths of changed files and
     * folders, batched and debounced. This app's own writes, moves and
     * deletes are filtered out by the implementation. Default: no
     * watching (platforms without a watcher, tests).
     *
     * Called once by `NoteRepository.watchExternalChanges`.
     */
    fun watchExternalChanges(root: String, onChange: (List<String>) -> Unit) {}
}

