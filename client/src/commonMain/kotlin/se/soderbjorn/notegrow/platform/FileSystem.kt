package se.soderbjorn.notegrow.platform

expect class FileSystem() {
    suspend fun ensureDirectory(path: String)
    suspend fun readFileIfExists(path: String): String?
    suspend fun writeFile(path: String, content: String)
}
