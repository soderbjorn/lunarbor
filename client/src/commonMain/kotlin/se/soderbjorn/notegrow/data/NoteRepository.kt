package se.soderbjorn.notegrow.data

import se.soderbjorn.notegrow.platform.FileSystem

class NoteRepository(
    private val fileSystem: FileSystem,
    private val directoryPath: String = DEFAULT_DIRECTORY,
    fileName: String = DEFAULT_FILE_NAME
) {
    private val filePath: String = "$directoryPath/$fileName"

    suspend fun load(): String {
        fileSystem.ensureDirectory(directoryPath)
        return fileSystem.readFileIfExists(filePath) ?: ""
    }

    suspend fun save(content: String) {
        fileSystem.ensureDirectory(directoryPath)
        fileSystem.writeFile(filePath, content)
    }

    companion object {
        const val DEFAULT_DIRECTORY: String = "/Users/soderbjorn/notegrow-db"
        const val DEFAULT_FILE_NAME: String = "root.nogr"
    }
}
