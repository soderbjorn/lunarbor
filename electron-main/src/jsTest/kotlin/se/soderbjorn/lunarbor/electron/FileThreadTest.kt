/* FileThreadTest.kt (electron-main, jsTest) — [fsPromises] as served by FileThread.kt.
 *
 * Runs the stand-in for `fs/promises` against a temp folder: the shapes
 * callers rely on (text and Buffer reads, plain and typed listings,
 * stat's methods) and Node's error codes surviving the thread hop.
 * Opening, writing and compressing are covered by VaultBackupTest's
 * zip round trip. */
package se.soderbjorn.lunarbor.electron

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileThreadTest {
    @Test
    fun file_operations_run_on_the_file_thread(): Promise<Unit> = GlobalScope.promise {
        val os: dynamic = js("require")("os")
        val root = fsSync.asDynamic().mkdtempSync(pathModule.join(os.tmpdir() as String, "tf-thread-")) as String
        try {
            val dir = pathModule.join(root, "a/b")
            fsPromises.mkdir(dir, js("({ recursive: true })")).await()
            val file = pathModule.join(dir, "_node.md")
            fsPromises.writeFile(file, "- Smörgås\n").await()

            assertEquals("- Smörgås\n", fsPromises.readFile(file, "utf8").await())
            val bytes: dynamic = fsPromises.readFile(file).await()
            assertTrue(js("Buffer").isBuffer(bytes) as Boolean)
            assertEquals("- Smörgås\n", bytes.toString("utf8") as String)

            assertEquals(listOf("_node.md"), fsPromises.readdir(dir).await().toList())
            val entries = fsPromises.readdir(pathModule.join(root, "a"), js("({ withFileTypes: true })")).await()
            assertEquals("b", entries.single().name as String)
            assertTrue(entries.single().isDirectory() as Boolean)

            val stat: dynamic = fsPromises.stat(file).await()
            assertTrue(stat.isFile() as Boolean)
            assertFalse(stat.isDirectory() as Boolean)
            assertTrue((stat.mtimeMs as Double) > 0.0)

            val moved = pathModule.join(root, "moved.md")
            fsPromises.rename(file, moved).await()
            fsPromises.unlink(moved).await()
            val code = try {
                fsPromises.unlink(moved).await()
                null
            } catch (e: Throwable) {
                e.asDynamic().code as String?
            }
            assertEquals("ENOENT", code)
        } finally {
            fsSync.asDynamic().rmSync(root, js("({ recursive: true, force: true })"))
        }
    }
}
