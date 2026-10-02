/* RunPaths.kt (electron-main, jsMain) — where one Electron run keeps its data.
 *
 * Resolves, from two environment variables and the persisted `vaultPath`
 * setting, the vault root and the settings/data directory a single
 * Lunarbor run uses:
 *
 *  - `LUNARBOR_VAULT=<abs path>` — the vault root handed to the renderer's
 *    `NoteRepository`. Wins over everything and locks the vault: the App
 *    settings sidebar shows it but cannot change it.
 *  - `vaultPath` in the app's settings file (`lunarbor.json`, under the
 *    settings root below) — the vault the user picked in the App settings
 *    sidebar. Used when `LUNARBOR_VAULT` is unset. Defaults to
 *    `~/lunarbor-db`.
 *  - `LUNARBOR_LOCAL_DATA=<dir>` — isolates everything else: Electron's
 *    `userData` (and with it the single-instance lock) moves to
 *    `<dir>/electron`, and the themes / per-app UI settings / layout files
 *    move from the shared Darkness folder into `<dir>`. When this is set and
 *    `LUNARBOR_VAULT` is not, the vault is `<dir>/vault` — an isolated run
 *    must never fall back to the real vault.
 *
 * Rules for editors:
 *  - Keep [resolveRunPaths] pure (no Node / Electron calls). Path joining is
 *    injected so the jsTest suite can pin the precedence rules without a
 *    real filesystem. `ElectronMain.kt` supplies Node's `path.join`.
 *  - The variable names and the `==> Vault:` / `==> Data:` log-line shapes
 *    are a contract other tooling (ai-dev run scripts) relies on. */
package se.soderbjorn.lunarbor.electron

/** Environment variable naming the vault root. */
const val ENV_LUNARBOR_VAULT: String = "LUNARBOR_VAULT"

/** Environment variable naming the isolated per-run data directory. */
const val ENV_LUNARBOR_LOCAL_DATA: String = "LUNARBOR_LOCAL_DATA"

/** Key in the app's settings file (`lunarbor.json`) holding the user-picked vault root. */
const val SETTINGS_KEY_VAULT_PATH: String = "vaultPath"

/** [RunPaths.vaultSource] when the vault came from [SETTINGS_KEY_VAULT_PATH]. */
const val VAULT_SOURCE_SETTINGS: String = "settings"

/** Directory name (under the home directory) of the default vault. */
const val DEFAULT_VAULT_DIR_NAME: String = "lunarbor-db"

/**
 * Resolved storage locations for one Electron run.
 *
 * @property vaultDir Absolute vault root the renderer's `NoteRepository`
 *   reads and writes. Always non-blank.
 * @property vaultSource Which rule chose [vaultDir]: [ENV_LUNARBOR_VAULT],
 *   [VAULT_SOURCE_SETTINGS] (the persisted `vaultPath`),
 *   [ENV_LUNARBOR_LOCAL_DATA] (the `<dir>/vault` safety default) or
 *   `"default"` (`~/lunarbor-db`). Printed in the startup log line.
 * @property localDataDir The `LUNARBOR_LOCAL_DATA` directory, or `null`
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
     * `true` when `LUNARBOR_VAULT` pinned the vault, so the user cannot
     * change it from the App settings sidebar (the env var would win again
     * on the next launch anyway).
     */
    val isVaultLocked: Boolean get() = vaultSource == ENV_LUNARBOR_VAULT

    /**
     * This run with the vault moved to [dir], as chosen in the App settings
     * sidebar. Called by the `lunarbor:setVault` IPC handler after it has
     * persisted [dir] as `vaultPath`.
     *
     * @param dir Absolute path of the new vault root.
     */
    fun withVault(dir: String): RunPaths = copy(vaultDir = dir, vaultSource = VAULT_SOURCE_SETTINGS)

    /**
     * Startup log line for the vault, e.g.
     * `==> Vault: /tmp/ai-dev/x/vault (LUNARBOR_LOCAL_DATA)`.
     */
    fun vaultLogLine(): String = "==> Vault: $vaultDir ($vaultSource)"

    /**
     * Startup log line for the data directory, e.g.
     * `==> Data: /tmp/ai-dev/x (LUNARBOR_LOCAL_DATA)`.
     *
     * @param defaultDataDir The shared Darkness folder, printed when the run
     *   is not isolated.
     */
    fun dataLogLine(defaultDataDir: String): String =
        if (localDataDir != null) "==> Data: $localDataDir ($ENV_LUNARBOR_LOCAL_DATA)"
        else "==> Data: $defaultDataDir (default)"
}

/**
 * Apply the `LUNARBOR_VAULT` / `LUNARBOR_LOCAL_DATA` precedence rules.
 *
 * Called once by `ElectronMain.main` before the single-instance lock, and
 * by the jsTest suite.
 *
 * Precedence for the vault:
 *  1. `LUNARBOR_VAULT` when set and non-blank.
 *  2. The persisted `vaultPath` setting ([settingsVault]) when non-blank.
 *     An isolated run reads it from its own `LUNARBOR_LOCAL_DATA`
 *     settings file, so it is only ever a vault that run picked itself.
 *  3. `<LUNARBOR_LOCAL_DATA>/vault` when only the data dir is set.
 *  4. `<homeDir>/lunarbor-db` otherwise.
 *
 * Blank values (empty or whitespace) count as unset, so `LUNARBOR_VAULT=`
 * in a shell script never produces a vault at the filesystem root.
 *
 * @param vaultEnv Raw value of `LUNARBOR_VAULT`, or `null` when unset.
 * @param localDataEnv Raw value of `LUNARBOR_LOCAL_DATA`, or `null`.
 * @param settingsVault The persisted `vaultPath` setting, or `null` when
 *   the settings file has none.
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
    settingsVault: String? = null,
): RunPaths {
    val vault = vaultEnv?.trim()?.takeIf { it.isNotEmpty() }?.let(resolve)
    val localData = localDataEnv?.trim()?.takeIf { it.isNotEmpty() }?.let(resolve)
    val picked = settingsVault?.trim()?.takeIf { it.isNotEmpty() }?.let(resolve)
    val (vaultDir, source) = when {
        vault != null -> vault to ENV_LUNARBOR_VAULT
        picked != null -> picked to VAULT_SOURCE_SETTINGS
        localData != null -> join(localData, "vault") to ENV_LUNARBOR_LOCAL_DATA
        else -> join(homeDir, DEFAULT_VAULT_DIR_NAME) to "default"
    }
    return RunPaths(
        vaultDir = vaultDir,
        vaultSource = source,
        localDataDir = localData,
        userDataDir = localData?.let { join(it, "electron") },
    )
}
