/*
 * FileSystem.ios.kt (iosMain)
 * Stub [PlatformFileSystem] for iOS. TreeFacts is desktop-only for now;
 * every call throws [NotImplementedError].
 */

package se.soderbjorn.treefacts.platform

/** Not-yet-implemented iOS filesystem; see [FileSystem]. */
class PlatformFileSystem : FileSystem {
    private fun notImplemented(): Nothing =
        throw NotImplementedError("FileSystem is not yet implemented for iOS")

    override suspend fun ensureDirectory(path: String): Unit = notImplemented()
    override suspend fun readFileIfExists(path: String): String? = notImplemented()
    override suspend fun writeFile(path: String, content: String): Unit = notImplemented()
    override suspend fun writeBinary(path: String, bytes: ByteArray): Unit = notImplemented()
    override suspend fun deleteFile(path: String): Unit = notImplemented()
    override suspend fun deleteDirectoryIfEmpty(path: String): Unit = notImplemented()
    override suspend fun moveFile(from: String, to: String): Unit = notImplemented()
    override suspend fun moveDirectory(from: String, to: String): Unit = notImplemented()
    override suspend fun listDirectory(path: String): List<String> = notImplemented()
    override suspend fun listDirectoryEntries(path: String): List<VaultDirectoryEntry> = notImplemented()
}
