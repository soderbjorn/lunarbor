/*
 * FileSystem.kt (commonMain)
 * --------------------------
 * Platform abstraction over the local filesystem operations TreeFacts needs.
 *
 * The vault is a tree of folders, one per folder-backed bullet, each holding
 * a hidden `.treefacts` outline file (see `NoteRepository`). Keeping that
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

package se.soderbjorn.treefacts.platform

/**
 * One direct entry in a directory listing produced by [FileSystem.listDirectoryEntries].
 * Used by the filesystem-tree footer in the editor view to lazy-load each
 * folder's contents without recursing.
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
}

