package se.soderbjorn.treefacts.platform

actual class FileSystem actual constructor() {
    private fun notImplemented(): Nothing =
        throw NotImplementedError("FileSystem is not yet implemented for wasmJs")

    actual suspend fun ensureDirectory(path: String): Unit = notImplemented()
    actual suspend fun readFileIfExists(path: String): String? = notImplemented()
    actual suspend fun writeFile(path: String, content: String): Unit = notImplemented()
    actual suspend fun writeBinary(path: String, bytes: ByteArray): Unit = notImplemented()
    actual suspend fun deleteFile(path: String): Unit = notImplemented()
    actual suspend fun deleteDirectoryIfEmpty(path: String): Unit = notImplemented()
    actual suspend fun moveFile(from: String, to: String): Unit = notImplemented()
    actual suspend fun moveDirectory(from: String, to: String): Unit = notImplemented()
    actual suspend fun listDirectory(path: String): List<String> = notImplemented()
    actual suspend fun listDirectoryEntries(path: String): List<VaultDirectoryEntry> = notImplemented()
}
