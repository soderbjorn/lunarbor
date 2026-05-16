package se.soderbjorn.notegrow.platform

actual class FileSystem actual constructor() {
    private fun notImplemented(): Nothing =
        throw NotImplementedError("FileSystem is not yet implemented for iOS")

    actual suspend fun ensureDirectory(path: String) = notImplemented()
    actual suspend fun readFileIfExists(path: String): String? = notImplemented()
    actual suspend fun writeFile(path: String, content: String) = notImplemented()
    actual suspend fun writeBinary(path: String, bytes: ByteArray) = notImplemented()
    actual suspend fun deleteFile(path: String) = notImplemented()
    actual suspend fun deleteDirectoryIfEmpty(path: String) = notImplemented()
    actual suspend fun moveFile(from: String, to: String) = notImplemented()
    actual suspend fun moveDirectory(from: String, to: String) = notImplemented()
    actual suspend fun listDirectory(path: String): List<String> = notImplemented()
    actual suspend fun listDirectoryEntries(path: String): List<VaultDirectoryEntry> = notImplemented()
}
