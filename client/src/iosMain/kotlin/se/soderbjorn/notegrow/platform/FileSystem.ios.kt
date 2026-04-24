package se.soderbjorn.notegrow.platform

actual class FileSystem actual constructor() {
    actual suspend fun ensureDirectory(path: String) {
        throw NotImplementedError("FileSystem is not yet implemented for iOS")
    }

    actual suspend fun readFileIfExists(path: String): String? {
        throw NotImplementedError("FileSystem is not yet implemented for iOS")
    }

    actual suspend fun writeFile(path: String, content: String) {
        throw NotImplementedError("FileSystem is not yet implemented for iOS")
    }
}
