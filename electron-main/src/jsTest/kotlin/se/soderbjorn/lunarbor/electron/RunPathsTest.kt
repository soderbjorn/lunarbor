/* RunPathsTest.kt (electron-main, jsTest)
 *
 * Pins the LUNARBOR_VAULT / LUNARBOR_LOCAL_DATA precedence rules in
 * [resolveRunPaths], including the safety default that an isolated run
 * (LUNARBOR_LOCAL_DATA set) never falls back to the real ~/lunarbor-db,
 * and the exact startup log-line shapes other tooling greps for. Pure —
 * path joining/resolution are injected, no Node or Electron calls. */
package se.soderbjorn.lunarbor.electron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunPathsTest {

    private val home = "/Users/someone"

    private fun resolve(vault: String?, data: String?, settings: String? = null): RunPaths =
        resolveRunPaths(
            vaultEnv = vault,
            localDataEnv = data,
            homeDir = home,
            resolve = { if (it.startsWith("/")) it else "/cwd/$it" },
            join = { a, b -> "$a/$b" },
            settingsVault = settings,
        )

    @Test
    fun settingsVault_winsOverDefaultAndLocalData() {
        val p = resolve(null, null, settings = "/Volumes/notes")
        assertEquals("/Volumes/notes", p.vaultDir)
        assertEquals(VAULT_SOURCE_SETTINGS, p.vaultSource)
        assertFalse(p.isVaultLocked)
        val isolated = resolve(null, "/tmp/d", settings = "/tmp/picked")
        assertEquals("/tmp/picked", isolated.vaultDir)
    }

    @Test
    fun envVault_winsOverSettingsAndLocksIt() {
        val p = resolve("/tmp/v", null, settings = "/Volumes/notes")
        assertEquals("/tmp/v", p.vaultDir)
        assertTrue(p.isVaultLocked)
    }

    @Test
    fun blankSettingsVault_countsAsUnset() {
        assertEquals("/Users/someone/lunarbor-db", resolve(null, null, settings = "  ").vaultDir)
    }

    @Test
    fun withVault_movesVaultKeepsData() {
        val p = resolve(null, "/tmp/d").withVault("/tmp/other")
        assertEquals("/tmp/other", p.vaultDir)
        assertEquals(VAULT_SOURCE_SETTINGS, p.vaultSource)
        assertEquals("/tmp/d/electron", p.userDataDir)
    }

    @Test
    fun neitherSet_usesHomeLunarborDbAndSharedSettings() {
        val p = resolve(null, null)
        assertEquals("/Users/someone/lunarbor-db", p.vaultDir)
        assertEquals("default", p.vaultSource)
        assertNull(p.localDataDir)
        assertNull(p.userDataDir)
        assertFalse(p.isIsolated)
    }

    @Test
    fun onlyLocalData_vaultLivesUnderDataDir() {
        val p = resolve(null, "/tmp/ai-dev/x")
        assertEquals("/tmp/ai-dev/x/vault", p.vaultDir)
        assertEquals(ENV_LUNARBOR_LOCAL_DATA, p.vaultSource)
        assertEquals("/tmp/ai-dev/x", p.localDataDir)
        assertEquals("/tmp/ai-dev/x/electron", p.userDataDir)
        assertTrue(p.isIsolated)
    }

    @Test
    fun bothSet_explicitVaultWins() {
        val p = resolve("/tmp/v", "/tmp/d")
        assertEquals("/tmp/v", p.vaultDir)
        assertEquals(ENV_LUNARBOR_VAULT, p.vaultSource)
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
        assertEquals("/Users/someone/lunarbor-db", p.vaultDir)
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
        assertEquals("==> Vault: /tmp/ai-dev/x/vault (LUNARBOR_LOCAL_DATA)", p.vaultLogLine())
        assertEquals("==> Data: /tmp/ai-dev/x (LUNARBOR_LOCAL_DATA)", p.dataLogLine("/shared"))
        val d = resolve(null, null)
        assertEquals("==> Vault: /Users/someone/lunarbor-db (default)", d.vaultLogLine())
        assertEquals("==> Data: /shared (default)", d.dataLogLine("/shared"))
        assertEquals("==> Vault: /v (LUNARBOR_VAULT)", resolve("/v", null).vaultLogLine())
    }
}
