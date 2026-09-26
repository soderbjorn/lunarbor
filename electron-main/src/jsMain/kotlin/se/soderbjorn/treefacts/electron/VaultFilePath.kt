/* VaultFilePath.kt (electron-main, jsMain) — maps a vault-relative path
 * from the renderer to an absolute path inside the vault.
 *
 * Used by the `treefacts:openPath` IPC handler (TRF-7: "other file" rows of
 * the folder contents list open in the system's default app). The renderer
 * only ever sends vault-relative paths; this is where the main process makes
 * sure such a path cannot climb out of the vault before it is handed to
 * `shell.openPath`.
 *
 * Rules for editors:
 *  - Keep [vaultFilePath] pure (no Node / Electron calls) so jsTest can pin
 *    it. The separator is always `/` (macOS; desktop only). */
package se.soderbjorn.treefacts.electron

/**
 * The absolute path of the vault file [pathRel] under [vaultDir], or `null`
 * when [pathRel] is empty, absolute, or would leave the vault (a `..`
 * segment that climbs above the root). `.` and empty segments are dropped.
 *
 * Called by the `treefacts:openPath` handler in `ElectronMain.kt`.
 *
 * @param vaultDir Absolute vault root (`RunPaths.vaultDir`).
 * @param pathRel Vault-relative path as sent by the renderer, e.g.
 *   `Recipes/data.csv`.
 */
fun vaultFilePath(vaultDir: String, pathRel: String): String? {
    if (pathRel.isEmpty() || pathRel.startsWith("/") || '\u0000' in pathRel) return null
    val out = ArrayList<String>()
    for (seg in pathRel.split('/')) {
        when (seg) {
            "", "." -> {}
            ".." -> {
                if (out.isEmpty()) return null
                out.removeAt(out.lastIndex)
            }
            else -> out += seg
        }
    }
    if (out.isEmpty()) return null
    return vaultDir.trimEnd('/') + "/" + out.joinToString("/")
}
