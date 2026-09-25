/* RunPathsTest.kt (electron-main, jsTest)
 *
 * Pins the TREEFACTS_VAULT / TREEFACTS_LOCAL_DATA precedence rules in
 * [resolveRunPaths], including the safety default that an isolated run
 * (TREEFACTS_LOCAL_DATA set) never falls back to the real ~/treefacts-db,
 * and the exact startup log-line shapes other tooling greps for. Pure —
 * path joining/resolution are injected, no Node or Electron calls. */
package se.soderbjorn.treefacts.electron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunPathsTest {

    private val home = "/Users/someone"

    private fun resolve(vault: String?, data: String?): RunPaths =
        resolveRunPaths(
            vaultEnv = vault,
            localDataEnv = data,
            homeDir = home,
            resolve = { if (it.startsWith("/")) it else "/cwd/$it" },
            join = { a, b -> "$a/$b" },
        )

    @Test
    fun neitherSet_usesHomeTreefactsDbAndSharedSettings() {
        val p = resolve(null, null)
        assertEquals("/Users/someone/treefacts-db", p.vaultDir)
        assertEquals("default", p.vaultSource)
        assertNull(p.localDataDir)
        assertNull(p.userDataDir)
        assertFalse(p.isIsolated)
    }

    @Test
    fun onlyLocalData_vaultLivesUnderDataDir() {
        val p = resolve(null, "/tmp/ai-dev/x")
        assertEquals("/tmp/ai-dev/x/vault", p.vaultDir)
        assertEquals(ENV_TREEFACTS_LOCAL_DATA, p.vaultSource)
        assertEquals("/tmp/ai-dev/x", p.localDataDir)
        assertEquals("/tmp/ai-dev/x/electron", p.userDataDir)
        assertTrue(p.isIsolated)
    }

    @Test
    fun bothSet_explicitVaultWins() {
        val p = resolve("/tmp/v", "/tmp/d")
        assertEquals("/tmp/v", p.vaultDir)
        assertEquals(ENV_TREEFACTS_VAULT, p.vaultSource)
        assertEquals("/tmp/d/electron", p.userDataDir)
    }

    @Test
    fun onlyVault_settingsStayShared() {
        val p = resolve("/tmp/v", null)
        assertEquals("/tmp/v", p.vaultDir)
        assertNull(p.localDataDir)
        assertNull(p.userDataDir)
    }

    @Test
    fun blankValuesCountAsUnset() {
        val p = resolve("  ", "")
        assertEquals("/Users/someone/treefacts-db", p.vaultDir)
        assertNull(p.localDataDir)
        val q = resolve("", "/tmp/d")
        assertEquals("/tmp/d/vault", q.vaultDir)
    }

    @Test
    fun relativePathsAreResolved() {
        val p = resolve(null, "run1")
        assertEquals("/cwd/run1/vault", p.vaultDir)
        assertEquals("/cwd/run1/electron", p.userDataDir)
    }

    @Test
    fun logLines() {
        val p = resolve(null, "/tmp/ai-dev/x")
        assertEquals("==> Vault: /tmp/ai-dev/x/vault (TREEFACTS_LOCAL_DATA)", p.vaultLogLine())
        assertEquals("==> Data: /tmp/ai-dev/x (TREEFACTS_LOCAL_DATA)", p.dataLogLine("/shared"))
        val d = resolve(null, null)
        assertEquals("==> Vault: /Users/someone/treefacts-db (default)", d.vaultLogLine())
        assertEquals("==> Data: /shared (default)", d.dataLogLine("/shared"))
        assertEquals("==> Vault: /v (TREEFACTS_VAULT)", resolve("/v", null).vaultLogLine())
    }
}
