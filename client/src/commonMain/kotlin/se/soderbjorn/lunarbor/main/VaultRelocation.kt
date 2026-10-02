/* VaultRelocation.kt (commonMain)
 *
 * Where a pane lands when the vault root moves (App settings → Vault).
 * Pane locations are vault-relative ([PaneBackingViewModel.FileHistoryEntry]),
 * so a pane survives a vault change only when the place it shows lies
 * inside the new vault: then its path is rebased onto the new root. Picking
 * a parent of the old vault keeps every pane; picking a subfolder keeps the
 * panes inside it; an unrelated folder keeps none.
 *
 * Pure path arithmetic on absolute `/`-separated roots — no I/O, commonMain
 * only. The web shell (`AppShell.switchVault`) applies the result. */
package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.NoteRepository

/** Rebases pane locations from one vault root onto another. */
object VaultRelocation {

    /**
     * [pathRel] (relative to [oldRoot]) re-expressed relative to
     * [newRoot], or `null` when it lies outside [newRoot].
     *
     * @param oldRoot Absolute root the path is relative to now.
     * @param newRoot Absolute root to express it against. Trailing slashes
     *   are ignored on both roots.
     * @param pathRel Vault-relative path; `""` is the root folder itself.
     * @return The rebased path (`""` when it is the new root), or `null`.
     */
    fun rebasePath(oldRoot: String, newRoot: String, pathRel: String): String? {
        val from = oldRoot.trimEnd('/')
        val to = newRoot.trimEnd('/')
        val abs = if (pathRel.isEmpty()) from else "$from/$pathRel"
        return when {
            abs == to -> ""
            to.isEmpty() -> abs.removePrefix("/")
            abs.startsWith("$to/") -> abs.substring(to.length + 1)
            else -> null
        }
    }

    /**
     * Where a pane at [location] should open after the vault moves from
     * [oldRoot] to [newRoot], or `null` when the pane must close.
     *
     * - The pane's file is inside the new vault: same file, same zoom.
     * - Otherwise, the place it shows ([locationPath] — the zoomed node's
     *   folder, or the open note / image) is inside: that node's outline
     *   (or that file), unzoomed. This keeps a pane zoomed from the old
     *   root into what is now the new root's content.
     * - Neither: `null`.
     *
     * Called by `AppShell.switchVault` for every pane before it hands the
     * new root to the Electron main process.
     *
     * @param location The pane's current (or persisted) location.
     * @param locationPath The pane's [PaneBackingViewModel.currentLocationPath],
     *   or `null` when unknown (a pane that has not been shown yet).
     */
    fun relocate(
        oldRoot: String,
        newRoot: String,
        location: PaneBackingViewModel.FileHistoryEntry,
        locationPath: String?,
    ): PaneBackingViewModel.FileHistoryEntry? {
        rebasePath(oldRoot, newRoot, location.fileRel)?.let {
            return PaneBackingViewModel.FileHistoryEntry(it, location.zoomTitlePath)
        }
        val path = locationPath ?: return null
        val rebased = rebasePath(oldRoot, newRoot, path) ?: return null
        val isFile = path.endsWith(NoteRepository.NOTE_EXTENSION) || NoteRepository.isFileViewPath(path)
        return PaneBackingViewModel.FileHistoryEntry(
            if (isFile) rebased else NoteRepository.outlineFileOf(rebased),
        )
    }
}
