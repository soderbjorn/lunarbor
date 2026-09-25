/* RunPaths.kt (electron-main, jsMain) — where one Electron run keeps its data.
 *
 * Resolves, from two environment variables, the vault root and the
 * settings/data directory a single TreeFacts run uses:
 *
 *  - `TREEFACTS_VAULT=<abs path>` — the vault root handed to the renderer's
 *    `NoteRepository`. Defaults to `~/treefacts-db`.
 *  - `TREEFACTS_LOCAL_DATA=<dir>` — isolates everything else: Electron's
 *    `userData` (and with it the single-instance lock) moves to
 *    `<dir>/electron`, and the themes / per-app UI settings / layout files
 *    move from the shared Darkness folder into `<dir>`. When this is set and
 *    `TREEFACTS_VAULT` is not, the vault is `<dir>/vault` — an isolated run
 *    must never fall back to the real vault.
 *
 * Rules for editors:
 *  - Keep [resolveRunPaths] pure (no Node / Electron calls). Path joining is
 *    injected so the jsTest suite can pin the precedence rules without a
 *    real filesystem. `ElectronMain.kt` supplies Node's `path.join`.
 *  - The variable names and the `==> Vault:` / `==> Data:` log-line shapes
 *    are a contract other tooling (ai-dev run scripts) relies on. */
package se.soderbjorn.treefacts.electron

/** Environment variable naming the vault root. */
const val ENV_TREEFACTS_VAULT: String = "TREEFACTS_VAULT"

/** Environment variable naming the isolated per-run data directory. */
const val ENV_TREEFACTS_LOCAL_DATA: String = "TREEFACTS_LOCAL_DATA"

/** Directory name (under the home directory) of the default vault. */
const val DEFAULT_VAULT_DIR_NAME: String = "treefacts-db"

/**
 * Resolved storage locations for one Electron run.
 *
 * @property vaultDir Absolute vault root the renderer's `NoteRepository`
 *   reads and writes. Always non-blank.
 * @property vaultSource Which rule chose [vaultDir]: [ENV_TREEFACTS_VAULT],
 *   [ENV_TREEFACTS_LOCAL_DATA] (the `<dir>/vault` safety default) or
 *   `"default"` (`~/treefacts-db`). Printed in the startup log line.
 * @property localDataDir The `TREEFACTS_LOCAL_DATA` directory, or `null`
 *   when unset — in which case settings live in the shared Darkness folder
 *   and Electron's `userData` is left at its OS default.
 * @property userDataDir `<localDataDir>/electron` when isolated, else
 *   `null` (leave Electron's default). Passed to `app.setPath("userData")`
 *   before the single-instance lock is requested.
 */
data class RunPaths(
    val vaultDir: String,
    val vaultSource: String,
    val localDataDir: String?,
    val userDataDir: String?,
) {
    /** `true` when this run keeps its settings out of the shared Darkness folder. */
    val isIsolated: Boolean get() = localDataDir != null

    /**
     * Startup log line for the vault, e.g.
     * `==> Vault: /tmp/ai-dev/x/vault (TREEFACTS_LOCAL_DATA)`.
     */
    fun vaultLogLine(): String = "==> Vault: $vaultDir ($vaultSource)"

    /**
     * Startup log line for the data directory, e.g.
     * `==> Data: /tmp/ai-dev/x (TREEFACTS_LOCAL_DATA)`.
     *
     * @param defaultDataDir The shared Darkness folder, printed when the run
     *   is not isolated.
     */
    fun dataLogLine(defaultDataDir: String): String =
        if (localDataDir != null) "==> Data: $localDataDir ($ENV_TREEFACTS_LOCAL_DATA)"
        else "==> Data: $defaultDataDir (default)"
}

/**
 * Apply the `TREEFACTS_VAULT` / `TREEFACTS_LOCAL_DATA` precedence rules.
 *
 * Called once by `ElectronMain.main` before the single-instance lock, and
 * by the jsTest suite.
 *
 * Precedence for the vault:
 *  1. `TREEFACTS_VAULT` when set and non-blank.
 *  2. `<TREEFACTS_LOCAL_DATA>/vault` when only the data dir is set.
 *  3. `<homeDir>/treefacts-db` otherwise.
 *
 * Blank values (empty or whitespace) count as unset, so `TREEFACTS_VAULT=`
 * in a shell script never produces a vault at the filesystem root.
 *
 * @param vaultEnv Raw value of `TREEFACTS_VAULT`, or `null` when unset.
 * @param localDataEnv Raw value of `TREEFACTS_LOCAL_DATA`, or `null`.
 * @param homeDir The user's home directory (`os.homedir()`); never a
 *   hardcoded user path.
 * @param resolve Turns a possibly relative path into an absolute one
 *   (Node's `path.resolve`). Identity in tests.
 * @param join Joins path segments (Node's `path.join`).
 * @return The resolved [RunPaths].
 */
fun resolveRunPaths(
    vaultEnv: String?,
    localDataEnv: String?,
    homeDir: String,
    resolve: (String) -> String,
    join: (String, String) -> String,
): RunPaths {
    val vault = vaultEnv?.trim()?.takeIf { it.isNotEmpty() }?.let(resolve)
    val localData = localDataEnv?.trim()?.takeIf { it.isNotEmpty() }?.let(resolve)
    val (vaultDir, source) = when {
        vault != null -> vault to ENV_TREEFACTS_VAULT
        localData != null -> join(localData, "vault") to ENV_TREEFACTS_LOCAL_DATA
        else -> join(homeDir, DEFAULT_VAULT_DIR_NAME) to "default"
    }
    return RunPaths(
        vaultDir = vaultDir,
        vaultSource = source,
        localDataDir = localData,
        userDataDir = localData?.let { join(it, "electron") },
    )
}
