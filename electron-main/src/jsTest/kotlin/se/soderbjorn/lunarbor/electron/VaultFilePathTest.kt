/* VaultFilePathTest.kt (electron-main, jsTest)
 *
 * Pins [vaultFilePath]: the main process only opens files inside the vault,
 * whatever vault-relative path the renderer sends to `lunarbor:openPath`. */
package se.soderbjorn.lunarbor.electron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaultFilePathTest {

    private val vault = "/tmp/ai-dev/vault"

    @Test
    fun a_plain_relative_path_resolves_under_the_vault() {
        assertEquals("/tmp/ai-dev/vault/Recipes/data.csv", vaultFilePath(vault, "Recipes/data.csv"))
        assertEquals("/tmp/ai-dev/vault/Recipes/data.csv", vaultFilePath("$vault/", "./Recipes//data.csv"))
    }

    @Test
    fun dot_dot_inside_the_vault_is_folded() {
        assertEquals("/tmp/ai-dev/vault/b.pdf", vaultFilePath(vault, "a/../b.pdf"))
    }

    @Test
    fun paths_that_leave_the_vault_are_refused() {
        assertNull(vaultFilePath(vault, "../secret.txt"))
        assertNull(vaultFilePath(vault, "a/../../secret.txt"))
        assertNull(vaultFilePath(vault, "/etc/passwd"))
        assertNull(vaultFilePath(vault, ""))
        assertNull(vaultFilePath(vault, "."))
    }
}
