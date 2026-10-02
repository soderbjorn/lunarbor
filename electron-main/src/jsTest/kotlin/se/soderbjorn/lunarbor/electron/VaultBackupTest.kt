/* VaultBackupTest.kt (electron-main, jsTest)
 *
 * Pins the backup rules in VaultBackup.kt / ZipWriter.kt: backup file names
 * and reading their dates back ("Last backup"), when an automatic backup is
 * due, the backup-folder-inside-the-vault check, and the ZIP primitives
 * (CRC-32, DOS times, ZIP64 end records), plus a round trip of [zipFolder]
 * checked with the system `unzip` (skipped where there is none). */
package se.soderbjorn.lunarbor.electron

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultBackupTest {

    private fun localMs(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Double =
        js("new Date(y, mo - 1, d, h, mi, s).getTime()") as Double

    @Test
    fun the_file_name_carries_the_vault_and_local_date() {
        val ms = localMs(2026, 10, 1, 9, 5, 7)
        assertEquals("Notes backup 2026-10-01 09.05.07.zip", backupFileName("Notes", ms))
    }

    @Test
    fun a_backup_name_reads_back_to_its_time() {
        val ms = localMs(2026, 10, 1, 14, 30, 5)
        assertEquals(ms, backupTimeOf(backupFileName("My vault", ms), "My vault"))
    }

    @Test
    fun other_files_and_other_vaults_are_not_backups() {
        assertNull(backupTimeOf("Notes backup 2026-10-01 14.30.05.zip", "Other"))
        assertNull(backupTimeOf("Notes backup 2026-10-01 14.30.05.zip.partial", "Notes"))
        assertNull(backupTimeOf("Notes backup 2026-10-01.zip", "Notes"))
        assertNull(backupTimeOf("holiday.zip", "Notes"))
    }

    @Test
    fun a_backup_is_due_once_the_newest_is_as_old_as_the_interval() {
        val hour = 3_600_000.0
        assertFalse(isBackupDue(0, null, 10 * hour), "off")
        assertTrue(isBackupDue(24, null, 10 * hour), "no backup yet")
        assertFalse(isBackupDue(24, 0.0, 23 * hour))
        assertTrue(isBackupDue(24, 0.0, 24 * hour))
        assertTrue(isBackupDue(1, 0.0, 5 * hour))
    }

    @Test
    fun a_folder_inside_the_vault_is_caught() {
        assertTrue(isSameOrInside("/u/vault", "/u/vault", caseInsensitive = false))
        assertTrue(isSameOrInside("/u/vault/Backups", "/u/vault/", caseInsensitive = false))
        assertTrue(isSameOrInside("/U/Vault/Backups", "/u/vault", caseInsensitive = true))
        assertFalse(isSameOrInside("/U/Vault/Backups", "/u/vault", caseInsensitive = false))
        assertFalse(isSameOrInside("/u/vault-backups", "/u/vault", caseInsensitive = false))
        assertFalse(isSameOrInside("/u", "/u/vault", caseInsensitive = false))
    }

    @Test
    fun crc32_matches_the_reference_value() {
        assertEquals(0xCBF43926L, crc32("123456789".encodeToByteArray()))
        assertEquals(0L, crc32(ByteArray(0)))
    }

    @Test
    fun dos_times_pack_local_date_and_time() {
        val packed = dosDateTime(localMs(2026, 10, 1, 14, 30, 5))
        assertEquals(((2026 - 1980L) shl 9) or (10L shl 5) or 1L, packed ushr 16)
        assertEquals((14L shl 11) or (30L shl 5) or 2L, packed and 0xFFFF)
    }

    @Test
    fun zip64_records_appear_only_when_needed() {
        assertEquals(22, endRecords(3, 100, 50, 150).size)
        assertEquals(22 + 56 + 20, endRecords(70_000, 100, 50, 150).size)
        assertEquals(22 + 56 + 20, endRecords(3, 5_000_000_000L, 50, 5_000_000_050L).size)
    }

    @Test
    fun zip_folder_round_trips_through_unzip(): Promise<Unit> = GlobalScope.promise {
        val req: dynamic = js("require")
        val cp: dynamic = req("child_process")
        val hasUnzip = try { cp.execFileSync("unzip", arrayOf("-v")); true } catch (_: Throwable) { false }
        if (!hasUnzip) return@promise
        val os: dynamic = req("os")
        val root = fsSync.asDynamic().mkdtempSync(pathModule.join(os.tmpdir() as String, "tf-zip-")) as String
        try {
            val vault = pathModule.join(root, "vault")
            fsSync.mkdirSync(pathModule.join(vault, "Recipes/Soups"), js("({ recursive: true })"))
            fsSync.mkdirSync(pathModule.join(vault, "Empty"))
            fsSync.writeFileSync(pathModule.join(vault, "_node.md"), "* Buy oat milk\n+ [Recipes](Recipes)\n")
            fsSync.writeFileSync(pathModule.join(vault, "Recipes/Soups/Smörgås.md"), "# Smörgås\n".repeat(200))
            val random = req("crypto").randomBytes(5000)
            fsSync.asDynamic().writeFileSync(pathModule.join(vault, "Recipes/shot.png"), random)
            val zip = pathModule.join(root, "out.zip")

            assertEquals(6, zipFolder(vault, zip))

            cp.execFileSync("unzip", arrayOf("-tq", zip))
            val text = cp.execFileSync("unzip", arrayOf("-p", zip, "Recipes/Soups/Smörgås.md")).toString("utf8") as String
            assertEquals("# Smörgås\n".repeat(200), text)
            val png = cp.execFileSync("unzip", arrayOf("-p", zip, "Recipes/shot.png"))
            assertTrue(png.equals(random) as Boolean)
            val listing = cp.execFileSync("unzip", arrayOf("-Z1", zip)).toString("utf8") as String
            assertTrue("Empty/" in listing.lines())
        } finally {
            fsSync.asDynamic().rmSync(root, js("({ recursive: true, force: true })"))
        }
    }
}
